# JavaRTC

Receives a WebRTC video stream from a browser, displays it in a Java Swing window on the server,
and echoes it back to the browser's remote video element — all using GStreamer's `webrtcbin`.
The browser-side stack is vanilla WebRTC (no external libraries). The server handles ICE,
DTLS-SRTP, codec negotiation, RTP decode, re-encode, and send entirely within a single GStreamer
pipeline — no hand-rolled signalling or crypto code.

---

## How it works

```
Browser (WebRTC / getUserMedia)
    │  offer SDP + ICE candidates  (WebSocket /rtc)
    ▼
Spring Boot WebSocket handler  (WebRtcConfig / RtcApplication)
    │
    ▼
WebRtcSession  — one instance per browser tab
    │
    ├─ webrtcbin  (GStreamer element)
    │      ├─ ICE agent   (libnice, via gstreamer1.0-nice)
    │      ├─ DTLS-SRTP   (managed internally by webrtcbin)
    │      └─ SDP negotiation  (offer/answer built at runtime — no hardcoded templates)
    │
    ├─ RECEIVE path  (pad-added fires when first decoded RTP pad is exposed)
    │      webrtcbin.src_0
    │        → queue → rtpvp8depay → vp8dec
    │        → tee
    │            ├─ queue → videoconvert → AppSink → BufferedImage → Swing JPanel (MediaPlayer)
    │            └─ queue → videoconvert → AppSink (I420) ──────────────────────────┐
    │                                                                                │ Java bridge
    └─ SEND path  (encode chain linked BEFORE pipeline goes to PLAYING)             │
           AppSrc (I420) ◄──────────────────────────────────────────────────────────┘
             → videorate → capsfilter (30 fps) → queue → vp8enc → rtpvp8pay
             → webrtcbin.sink_0  →  browser remoteVideo
```

### Sequence of events

1. **Browser opens the page** at `http://localhost:8088/` and establishes a WebSocket to `/rtc`.
2. **User clicks "Start Call"** — the browser calls `getUserMedia` to capture the webcam, creates
   an `RTCPeerConnection`, adds the video track, and calls `createOffer`.
3. **Offer sent to server** — the browser waits for ICE gathering to complete, then sends a single
   JSON message containing the SDP offer and all gathered ICE candidates together.
4. **`WebRtcSession.handleOffer`** is called:
   - A GStreamer `Pipeline` containing a single `webrtcbin` element is created.
   - STUN (`stun.l.google.com:19302`) and TURN (`coturn` container) servers are configured.
   - `pipeline.setState(READY)` is called **synchronously** — critical: webrtcbin's internal
     task queue only starts on the `NULL → READY` transition. Signals emitted before that are
     silently ignored.
   - **The webrtcbin sink pad `sink_%u` is requested immediately** (before `set-remote-description`).
     This forces webrtcbin to create a SENDRECV transceiver so the generated answer naturally
     contains `a=sendrecv` — no SDP string patching is needed.
   - **The full encode chain is linked to `sink_0` right now**, before the pipeline reaches PLAYING.
     This is the key requirement: webrtcbin configures its DTLS-SRTP send context when the pipeline
     transitions to PLAYING. If the encode chain is only linked after PLAYING (e.g. in `pad-added`),
     webrtcbin silently sends nothing — the send path is never configured. The chain at this point
     is: `AppSrc → videorate → capsfilter(30fps) → queue → vp8enc → rtpvp8pay → sink_0`.
     AppSrc starts with no caps; they are filled in from the first captured frame (see step 8).
   - Browser ICE candidates are added via `webrtcBin.addIceCandidate(...)`.
   - `set-remote-description` is emitted as a GStreamer action signal with the browser's offer.
5. **`set-remote-description` promise fires** (on a GStreamer internal thread):
   - The promise reply is `NULL` on success — intentional in GStreamer. Calling `getReply()` causes
     a `NullPointerException` inside gst1-java-core because `Structure.objectFor(null)` hits a
     `ConcurrentHashMap.get(null)`. The callback firing at all means success.
   - `create-answer` is emitted immediately.
6. **`create-answer` promise fires**:
   - The answer `WebRTCSessionDescription` is extracted from the reply structure.
   - `setLocalDescription` is called so webrtcbin knows its own SDP.
   - `pipeline.setState(PLAYING)` — media flows; the encode chain is already linked so webrtcbin
     configures the DTLS-SRTP send path immediately.
   - The answer SDP + locally gathered ICE candidates are sent back to the browser over WebSocket.
7. **ICE connectivity checks** run between browser and webrtcbin via coturn. On success the
   connection state transitions to `connected`.
8. **`pad-added` fires** on webrtcbin when the first decoded RTP pad is exposed (`src_0`):
   - The codec is identified from the pad's caps string (`VP8`/`VP9`/`H264`), selecting the
     matching depayloader and decoder at runtime.
   - A **tee** splits decoded frames to two branches:
     - **Display branch**: `queue → videoconvert(BGRx) → AppSink → BufferedImage → Swing JPanel`
     - **Capture branch**: `queue → videoconvert(I420) → AppSink`
   - The capture AppSink's `NEW_SAMPLE` callback:
     1. On the **first sample only**: reads the actual caps (including width/height) from the sample
        and calls `encoderSrc.setCaps(...)`. Without this, vp8enc cannot negotiate formats and
        emits an `Internal data stream error`.
     2. Copies the `Buffer` bytes to a new `Buffer` (gst1-java-core 1.4.0 has no `Buffer.copy()`),
        preserving `PresentationTimestamp` and `Duration`.
     3. Calls `encoderSrc.pushBuffer(...)` — the frame enters the encode chain and is eventually
        sent to the browser.
9. **Video appears** in the Swing window and in the browser's `remoteVideo` element simultaneously.

---

## Prerequisites

### Host machine

- **Java 21** (build + optional direct-run)
- **Maven 3.9+** (build)
- **Docker + Docker Compose** (recommended runtime)
- **GStreamer 1.20+** with the following plugin packages (Ubuntu/Debian):
  ```
  gstreamer1.0-tools
  gstreamer1.0-plugins-base
  gstreamer1.0-plugins-good
  gstreamer1.0-plugins-bad      # contains webrtcbin
  gstreamer1.0-plugins-ugly
  gstreamer1.0-libav            # avdec_h264 for H.264 decode
  gstreamer1.0-nice             # libnice ICE agent (separate from plugins-bad — easy to miss!)
  ```
  > **`gstreamer1.0-nice` is the single most common missing dependency.** Without it,
  > `pipeline.setState(READY)` returns `FAILURE` with no obvious error message.

- **X11 libraries** (required by Java AWT/Swing even when `DISPLAY` is set):
  ```
  libxtst6   libxrender1   libxi6
  ```
  The JVM throws `UnsatisfiedLinkError: libXtst.so.6` at the point a Swing window is first
  created — not at startup — making this easy to miss when running inside Docker.

- **An X display** accessible to the process. For Docker:
  ```bash
  xhost +local:docker          # run once on the host, before docker compose up
  ```
  And in `docker-compose.yml`:
  ```yaml
  environment:
    DISPLAY: "${DISPLAY:-:0}"
    XAUTHORITY: "/root/.Xauthority"
  volumes:
    - /tmp/.X11-unix:/tmp/.X11-unix
    - "${XAUTHORITY:-${HOME}/.Xauthority}:/root/.Xauthority:ro"
  ```

### Maven dependencies (pom.xml)

| Artifact | Purpose |
|---|---|
| `spring-boot-starter-websocket` | WebSocket endpoint at `/rtc` |
| `spring-boot-starter-web` | REST endpoints (debug bridge, SSE) |
| `org.freedesktop.gstreamer:gst1-java-core:1.4.0` | GStreamer Java bindings (JNA); includes `WebRTCBin`, `Promise`, `AppSink`, `AppSrc` |
| `org.jitsi:ice4j:3.0-23-ga86c4be` | ICE utilities (transitive; pulled from Jitsi Maven repo) |
| `org.bouncycastle:bcprov-jdk18on:1.80` | Crypto (certificate utilities — retained, not on active call path) |

---

## Building

```bash
mvn package -DskipTests
```

The fat JAR is written to `target/javartc-0.0.1-SNAPSHOT.jar`.

### Building the Docker image

The `docker-compose.yml` intentionally has **no `build:` directive** so you can hot-swap the JAR
without rebuilding the entire image. Build the image once:

```bash
docker build -t javartc-javartc:latest .
```

After that, deploying a new JAR is:

```bash
mvn package -DskipTests && docker compose restart javartc
```

Static files (`src/main/resources/static/`) are volume-mounted live — a browser hard-refresh
(`Ctrl+Shift+R`) is sufficient for HTML/JS changes; no server restart needed.

---

## Running

### With Docker Compose (recommended)

```bash
xhost +local:docker
mvn package -DskipTests
docker build -t javartc-javartc:latest .   # only needed first time or after Dockerfile changes
docker compose up
```

Open `http://localhost:8088/` and click **Start Call**.

Docker Compose starts two containers:

| Container | Image | Role |
|---|---|---|
| `coturn` | `coturn/coturn:latest` | STUN + TURN relay on UDP 3478 |
| `javartc` | `javartc-javartc:latest` | Spring Boot app on HTTP 8088 |

Both use `network_mode: host` so ICE candidates and TURN relay ports work without additional NAT configuration.

### Without Docker (direct run)

Install all GStreamer packages listed above on the host, then:

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     -Dorg.ice4j.ice.harvest.DISABLE_AWS_HARVESTER=true \
     -jar target/javartc-0.0.1-SNAPSHOT.jar
```

Point `application.yaml`'s `turn.*` settings at any accessible TURN server.

### Configuration (`application.yaml`)

```yaml
server:
  port: 8088          # avoid well-known ports; Chrome blocks several (e.g. 6000)

turn:
  uri: "turn:localhost:3478"
  username: "javartc"
  credential: "javartc"
```

Override at runtime with `SPRING_APPLICATION_JSON` (see `docker-compose.yml`).

---

## Architecture notes

### Why webrtcbin instead of libjitsi / ice4j / manual DTLS?

The original project tried to hand-roll ICE, DTLS-SRTP, and SDP. All three have subtle edge cases
that are extremely difficult to get right without deep protocol expertise, and the original code
never actually worked. `webrtcbin` is a production-grade GStreamer element that handles all of this
internally using the same libraries as real WebRTC implementations (libnice for ICE, OpenSSL for
DTLS). The Java code only needs to exchange SDP and ICE candidates over a WebSocket.

### Dynamic SDP negotiation

There are no hardcoded SDP strings. The browser's offer SDP is passed verbatim to webrtcbin via
`set-remote-description`. webrtcbin inspects the installed GStreamer plugin registry to determine
which codecs it supports, intersects them with the offer, and generates the answer via
`create-answer`. Adding a new codec is as simple as installing the GStreamer plugin — no Java
changes required.

### Echo pipeline: breaking the GStreamer loop

Naively connecting `webrtcbin.src_0 → decode → encode → webrtcbin.sink_0` creates a cycle in
GStreamer's element graph. GStreamer detects this with a `"loop detected"` warning and refuses to
schedule the encode chain.

The fix is an **AppSink/AppSrc bridge**:
- `captureAppSink` is a graph leaf (GStreamer sees nothing downstream of it).
- `encoderSrc` is a graph root (GStreamer sees nothing upstream of it).
- A Java `NEW_SAMPLE` callback copies buffers between them, breaking the cycle from GStreamer's
  perspective while preserving the data flow.

### Send path timing: encode chain must be linked before PLAYING

This is the subtlest requirement in the entire implementation. webrtcbin configures its
DTLS-SRTP send context (including RTP session setup and SRTP key derivation) during the
`PAUSED → PLAYING` state transition. If a `sink_%u` pad exists at that moment **and has an
upstream chain linked to it**, webrtcbin sets up the send path. If the pad is only linked later
(e.g. dynamically in `pad-added` which fires after PLAYING), webrtcbin silently sends nothing —
`outbound-rtp bytesSent` in the browser's `getStats()` stays at zero.

Additionally, the sink pad must be **requested before `set-remote-description`**. Requesting it
early makes webrtcbin create a SENDRECV transceiver, so `create-answer` naturally produces
`a=sendrecv` in the SDP. Requesting it after the offer is processed creates a RECVONLY
transceiver, and the answer says `a=recvonly` — the browser's `ontrack` never fires.

### AppSrc caps must come from the first live sample

The encode chain's AppSrc is created without explicit caps because the actual video dimensions
(width, height, framerate) are not known until the first decoded frame arrives. Setting incorrect
caps causes `vp8enc` to emit an `Internal data stream error`. The bridge callback detects the
first sample with an `AtomicBoolean`, reads `sample.getCaps()` (which contains the full negotiated
caps including dimensions), and calls `encoderSrc.setCaps(...)` before pushing the buffer.
A `videorate` element downstream converts the variable/unknown framerate from webrtcbin's decoder
to a stable 30 fps that `vp8enc` requires.

### GLib main loop

`webrtcbin` async operations (promises, pad-added callbacks, bus messages) require an active GLib
main loop. `WebRtcConfig` starts one as a daemon thread at application startup:

```java
Gst.init(Version.of(1, 14), "javartc");
Thread loop = new Thread(Gst::main, "gst-main-loop");
loop.setDaemon(true);
loop.start();
```

`Version.of(1, 14)` is the minimum API version requested; GStreamer 1.20 (Ubuntu 22.04) satisfies it.

### State ordering matters

```
pipeline.setState(READY)              // synchronous — blocks until element is READY
webrtcBin.getRequestPad("sink_%u")    // creates SENDRECV transceiver
// link full encode chain to sink_0
webrtcBin.emit("set-remote-description", offer, promise1)
// inside promise1 callback:
webrtcBin.emit("create-answer", null, promise2)
// inside promise2 callback:
webrtcBin.setLocalDescription(answer)
pipeline.setState(PLAYING)            // encode chain already linked → send path configured
sendAnswerOverWebSocket(answer)
// pad-added fires for src_0 → link decode+display+capture chain dynamically
```

### Debug bridge

A browser-to-server debug channel is available at runtime:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/debug/events` | SSE (GET) | Push commands to the browser (`call`, `state`, `stats`) |
| `/api/debug/log` | POST | Receive browser console output server-side |
| `/api/debug/cmd?cmd=call` | GET | Trigger a call from the browser via SSE |

The browser intercepts `console.log/warn/error` and forwards them to `/api/debug/log`, making it
possible to see browser WebRTC state transitions in the server log without opening browser DevTools.

---

## Known limitations / next steps

- **Video only** — audio is not negotiated; the pipeline has no audio branch.
- Dead code remains: `ICEManager.java`, `GStreamerMediaManager.java`, `CertificateGenerator.java`,
  `SdpNegotiator.java` — these are not wired as Spring beans and can be removed.
- Session teardown on WebSocket close disposes the pipeline but does not send a WebRTC `BYE`.
- The app requires a physical or virtual X display. Headless rendering (e.g. Xvfb) would remove
  the X11 dependency for server deployments.


---

## How it works

```
Browser (WebRTC / getUserMedia)
    │  offer SDP + ICE candidates  (WebSocket /rtc)
    ▼
Spring Boot WebSocket handler  (WebRtcConfig / RtcApplication)
    │
    ▼
WebRtcSession  — one instance per browser tab
    │
    ├─ webrtcbin  (GStreamer element)
    │      ├─ ICE agent   (libnice, via gstreamer1.0-nice)
    │      ├─ DTLS-SRTP   (managed internally by webrtcbin)
    │      └─ SDP negotiation  (offer/answer built at runtime — no hardcoded templates)
    │
    └─ on pad-added (decoded RTP arrives):
           queue → rtpvp8depay → vp8dec → videoconvert → AppSink
                                                               │
                                                      BufferedImage → Swing JPanel (MediaPlayer)
```

### Sequence of events

1. **Browser opens the page** at `http://localhost:8088/` and establishes a WebSocket to `/rtc`.
2. **User clicks "Start Call"** — the browser calls `getUserMedia` to capture the webcam, creates
   an `RTCPeerConnection`, adds the video track, and calls `createOffer`.
3. **Offer sent to server** — the browser waits for ICE gathering to complete, then sends a single
   JSON message containing the SDP offer and all gathered ICE candidates together.
4. **`WebRtcSession.handleOffer`** is called:
   - A GStreamer `Pipeline` containing a single `webrtcbin` element is created.
   - STUN (`stun.l.google.com:19302`) and TURN (`coturn` container) servers are configured.
   - `pipeline.setState(READY)` is called **synchronously** — this is critical: webrtcbin's internal
     task queue only starts on the `NULL → READY` state transition. If you call
     `set-remote-description` before that transition, the signal is silently ignored.
   - Browser ICE candidates are added via `webrtcBin.addIceCandidate(...)`.
   - `set-remote-description` is emitted as a GStreamer action signal with the browser's offer.
5. **`set-remote-description` promise fires** (on a GStreamer internal thread):
   - The promise reply is `NULL` on success — this is intentional in GStreamer. Calling
     `getReply()` on it causes a `NullPointerException` inside gst1-java-core because
     `Structure.objectFor(null)` hits a `ConcurrentHashMap.get(null)`. The callback firing
     at all means success.
   - `create-answer` is emitted immediately.
6. **`create-answer` promise fires**:
   - The answer `WebRTCSessionDescription` is extracted from the reply structure.
   - `setLocalDescription` is called on webrtcbin so it knows its own SDP.
   - `pipeline.setState(PLAYING)` is called — now media can actually flow.
   - The answer SDP + any locally gathered ICE candidates are sent back to the browser over WebSocket.
7. **ICE connectivity checks** run between browser and webrtcbin via coturn. On success the
   connection state transitions to `connected`.
8. **`pad-added` fires** on webrtcbin when the first decoded RTP pad is exposed:
   - GStreamer 1.20 names this pad `src_0` (older docs say `recv_rtp_src_…` — that name is wrong
     for this version). The pad is identified by checking its caps string for `application/x-rtp`.
   - The encoding name in the caps (`VP8`, `VP9`, `H264`) selects the correct depayloader and
     decoder elements at runtime.
   - A decode chain is dynamically linked: `queue → depay → decode → videoconvert → AppSink`.
   - `MediaPlayer` creates a Swing `JFrame` backed by an `AppSink`. Each `BufferedImage` produced
     by the sink is painted onto a `JPanel` via `repaint()`.
9. **Video appears** in the Swing window on the server desktop.

---

## Prerequisites

### Host machine

- **Java 21** (build + optional direct-run)
- **Maven 3.9+** (build)
- **Docker + Docker Compose** (recommended runtime)
- **GStreamer 1.20+** with the following plugin packages (Ubuntu/Debian):
  ```
  gstreamer1.0-tools
  gstreamer1.0-plugins-base
  gstreamer1.0-plugins-good
  gstreamer1.0-plugins-bad      # contains webrtcbin
  gstreamer1.0-plugins-ugly
  gstreamer1.0-libav            # avdec_h264 for H.264 decode
  gstreamer1.0-nice             # libnice ICE agent (separate from plugins-bad — easy to miss!)
  ```
  > **`gstreamer1.0-nice` is the single most common missing dependency.** Without it,
  > `pipeline.setState(READY)` returns `FAILURE` with no obvious error message. webrtcbin
  > uses libnice internally for ICE but the GStreamer plugin packaging splits it out.

- **X11 libraries** (required by Java AWT/Swing even when `DISPLAY` is set):
  ```
  libxtst6   libxrender1   libxi6
  ```
  The JVM throws `UnsatisfiedLinkError: libXtst.so.6` at the point a Swing window is first
  created — not at startup — making this easy to miss when running inside Docker.

- **An X display** accessible to the process. For Docker:
  ```bash
  xhost +local:docker          # run once on the host, before docker compose up
  ```
  And in `docker-compose.yml`:
  ```yaml
  environment:
    DISPLAY: "${DISPLAY:-:0}"
    XAUTHORITY: "/root/.Xauthority"
  volumes:
    - /tmp/.X11-unix:/tmp/.X11-unix
    - "${XAUTHORITY:-${HOME}/.Xauthority}:/root/.Xauthority:ro"
  ```

### Maven dependencies (pom.xml)

| Artifact | Purpose |
|---|---|
| `spring-boot-starter-websocket` | WebSocket endpoint at `/rtc` |
| `spring-boot-starter-web` | REST endpoints (debug bridge, SSE) |
| `org.freedesktop.gstreamer:gst1-java-core:1.4.0` | GStreamer Java bindings (JNA); includes `WebRTCBin`, `Promise`, `AppSink` |
| `org.jitsi:ice4j:3.0-23-ga86c4be` | ICE utilities (transitive; pulled from Jitsi Maven repo) |
| `org.bouncycastle:bcprov-jdk18on:1.80` | Crypto (certificate utilities — retained, not on active call path) |

---

## Building

```bash
mvn package -DskipTests
```

The fat JAR is written to `target/javartc-0.0.1-SNAPSHOT.jar`.

### Building the Docker image

The `docker-compose.yml` intentionally has **no `build:` directive** so you can hot-swap the JAR
without rebuilding the entire image. Build the image once:

```bash
docker build -t javartc-javartc:latest .
```

After that, deploying a new JAR is:

```bash
mvn package -DskipTests && docker compose restart javartc
```

Static files (`src/main/resources/static/`) are volume-mounted live — a browser hard-refresh
(`Ctrl+Shift+R`) is sufficient for HTML/JS changes; no server restart needed.

---

## Running

### With Docker Compose (recommended)

```bash
xhost +local:docker
mvn package -DskipTests
docker build -t javartc-javartc:latest .   # only needed first time or after Dockerfile changes
docker compose up
```

Open `http://localhost:8088/` and click **Start Call**.

Docker Compose starts two containers:

| Container | Image | Role |
|---|---|---|
| `coturn` | `coturn/coturn:latest` | STUN + TURN relay on UDP 3478 |
| `javartc` | `javartc-javartc:latest` | Spring Boot app on HTTP 8088 |

Both use `network_mode: host` so ICE candidates and TURN relay ports work without additional NAT configuration.

### Without Docker (direct run)

Install all GStreamer packages listed above on the host, then:

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     -Dorg.ice4j.ice.harvest.DISABLE_AWS_HARVESTER=true \
     -jar target/javartc-0.0.1-SNAPSHOT.jar
```

Point `application.yaml`'s `turn.*` settings at any accessible TURN server.

### Configuration (`application.yaml`)

```yaml
server:
  port: 8088          # avoid well-known ports; Chrome blocks several (e.g. 6000)

turn:
  uri: "turn:localhost:3478"
  username: "javartc"
  credential: "javartc"
```

Override at runtime with `SPRING_APPLICATION_JSON` (see `docker-compose.yml`).

---

## Architecture notes

### Why webrtcbin instead of libjitsi / ice4j / manual DTLS?

The original project tried to hand-roll ICE, DTLS-SRTP, and SDP. All three have subtle edge cases
that are extremely difficult to get right without deep protocol expertise, and the original code
never actually worked. `webrtcbin` is a production-grade GStreamer element that handles all of this
internally using the same libraries as real WebRTC implementations (libnice for ICE, OpenSSL for
DTLS). The Java code only needs to exchange SDP and ICE candidates over a WebSocket.

### Dynamic SDP negotiation

There are no hardcoded SDP strings. The browser's offer SDP is passed verbatim to webrtcbin via
`set-remote-description`. webrtcbin inspects the installed GStreamer plugin registry to determine
which codecs it supports, intersects them with the offer, and generates the answer via
`create-answer`. Adding a new codec is as simple as installing the GStreamer plugin — no Java
changes required.

### GLib main loop

`webrtcbin` async operations (promises, pad-added callbacks, bus messages) require an active GLib
main loop. `WebRtcConfig` starts one as a daemon thread at application startup:

```java
Gst.init(Version.of(1, 14), "javartc");
Thread loop = new Thread(Gst::main, "gst-main-loop");
loop.setDaemon(true);
loop.start();
```

`Version.of(1, 14)` is the minimum API version requested; GStreamer 1.20 (Ubuntu 22.04) satisfies it.

### State ordering matters

webrtcbin's internal worker thread starts only on `NULL → READY`. Any `set-remote-description` or
`create-answer` signal emitted before that transition is silently dropped. The required order is:

```
pipeline.setState(READY)              // synchronous — blocks until element is READY
webrtcBin.emit("set-remote-description", offer, promise1)
// inside promise1 callback:
webrtcBin.emit("create-answer", null, promise2)
// inside promise2 callback:
webrtcBin.setLocalDescription(answer)
pipeline.setState(PLAYING)            // now media flows
sendAnswerOverWebSocket(answer)
```

Calling `setState(PLAYING)` at the start (async) and then immediately emitting signals is the
most common mistake — the element is still in NULL when the signals arrive.

### Debug bridge

A browser-to-server debug channel is available at runtime:

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/debug/events` | SSE (GET) | Push commands to the browser (`call`, `state`) |
| `/api/debug/log` | POST | Receive browser console output server-side |
| `/api/debug/cmd?cmd=call` | GET | Trigger a call from the browser via SSE |

The browser intercepts `console.log/warn/error` and forwards them to `/api/debug/log`, making it
possible to see browser WebRTC state transitions in the server log without opening browser DevTools.

---

## Known limitations / next steps

- **Video only** — audio is not negotiated; the pipeline has no audio branch.
- Dead code remains: `ICEManager.java`, `GStreamerMediaManager.java`, `CertificateGenerator.java`,
  `SdpNegotiator.java` — these are not wired as Spring beans and can be removed.
- Session teardown on WebSocket close disposes the pipeline but does not send a WebRTC `BYE`.
- The app requires a physical or virtual X display. Headless rendering (e.g. Xvfb) would remove
  the X11 dependency for server deployments.

# JavaRTC

Receives a WebRTC video stream from a browser, displays it in two Java Swing windows on the server
(raw input + processed output), and echoes the (optionally processed) video back to the browser —
all using GStreamer's `webrtcbin`. The browser-side stack is vanilla WebRTC (no external libraries).
ICE, DTLS-SRTP, codec negotiation, RTP decode, optional video processing, re-encode, and send are
handled entirely within a single GStreamer pipeline per session.

---

## Pipeline overview

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
    │      └─ SDP offer/answer built at runtime — no hardcoded templates
    │
    ├─ RECEIVE path  (linked dynamically in pad-added when src_0 is exposed)
    │      webrtcbin.src_0
    │        → queue                    ← default size; must NOT drop packets (VP8 needs all)
    │        → rtpvp8depay → vp8dec
    │        → tee
    │            ├─ queue(1,leaky) → videoconvert(BGRx) → AppSink
    │            │                                             │
    │            │                            BufferedImage → Swing "Raw Input" panel
    │            │
    │            └─ queue(1,leaky) → videoconvert(BGR) → captureAppSink
    │                                                          │
    │                                                   NEW_SAMPLE callback (Java)
    │                                                          │
    │                                                   VideoProcessor.process()
    │                                                          │
    │                                         ┌──────────────┴──────────────┐
    │                                         ▼                             ▼
    │                              Swing "Processed Output"          encoderSrc (AppSrc)
    │                                                                       │
    └─ SEND path  (encode chain linked BEFORE pipeline goes to PLAYING)     │
           encoderSrc (AppSrc, BGR)  ◄──────────────────────────────────────┘
             → videoconvert (BGR→I420) → capsfilter(I420)
             → queue(1,leaky) → vp8enc → rtpvp8pay
             → webrtcbin.sink_0  →  browser remoteVideo
```

---

## Sequence of events

1. **Browser opens the page** at `http://localhost:8088/` and establishes a WebSocket to `/rtc`.
2. **User clicks "Start Call"** — `getUserMedia` captures the webcam, an `RTCPeerConnection` is
   created, and `createOffer` is called. ICE gathering completes, then a single JSON message
   containing the SDP offer + all ICE candidates is sent over WebSocket.
3. **`WebRtcSession.handleOffer`** runs on the server:
   - A GStreamer `Pipeline` + `webrtcbin` element are created.
   - `pipeline.setState(READY)` is called **synchronously** — webrtcbin's internal task queue only
     starts on `NULL → READY`. Signals emitted before this are silently ignored.
   - `webrtcBin.set("latency", 0)` eliminates the 200 ms default jitter buffer (wasteful on LAN).
   - **`getRequestPad("sink_%u")` is called BEFORE `set-remote-description`**. This forces webrtcbin
     to create a SENDRECV transceiver, so `create-answer` naturally produces `a=sendrecv`. If
     requested after `set-remote-description`, webrtcbin creates a RECVONLY transceiver and the
     browser's `ontrack` never fires.
   - **The full encode chain is linked to `sink_0` right now**, before PLAYING. This is the critical
     send-path requirement: webrtcbin configures its DTLS-SRTP send context during `PAUSED →
     PLAYING`. The chain is: `AppSrc(BGR) → videoconvert → capsfilter(I420) → queue → vp8enc →
     rtpvp8pay → sink_0`. AppSrc starts without caps; they are set from the first captured frame.
   - Browser ICE candidates are added via `webrtcBin.addIceCandidate(...)`.
   - `set-remote-description` → `create-answer` → `setLocalDescription` → `setState(PLAYING)` →
     answer is sent back to the browser.
4. **ICE connects**, DTLS-SRTP handshake completes.
5. **`pad-added` fires** for `src_0` (the decoded RTP output pad):
   - Codec is identified from caps (`VP8`/`VP9`/`H264`) to select depayloader + decoder.
   - A **tee** splits decoded BGR frames to:
     - **Swing "Raw Input" panel** — pure GStreamer, BGRx via `AppSink → BufferedImage`.
     - **`captureAppSink`** — `NEW_SAMPLE` callback copies the BGR frame, calls `VideoProcessor`,
       updates **Swing "Processed Output" panel**, and pushes the result to `encoderSrc`.
6. **Video appears** in both Swing windows and in the browser's `remoteVideo` element.

---

## VideoProcessor hook

Implement the `VideoProcessor` interface to add frame-level processing in the server-to-browser path:

```java
public interface VideoProcessor {
    /**
     * Process one BGR video frame.
     * @param bgr    raw frame bytes in BGR byte order (matches OpenCV Mat and BufferedImage.TYPE_3BYTE_BGR)
     * @param width  frame width in pixels
     * @param height frame height in pixels
     * @return processed BGR bytes (same size), or null to pass the original frame through unchanged
     */
    byte[] process(byte[] bgr, int width, int height);
}
```

The default `PassthroughVideoProcessor` (`@Component`) returns `null` — original frames are echoed unchanged.

To activate custom processing, create a class annotated with `@Primary @Component`:

```java
@Primary
@Component
public class MyProcessor implements VideoProcessor {
    @Override
    public byte[] process(byte[] bgr, int width, int height) {
        // ... modify bgr in place or return a new array ...
        return bgr;
    }
}
```

`OpenCvVideoProcessor` in the source tree is a ready-made template for OpenCV integration —
`@Component` is commented out by default. See `pom.xml` for OpenCV install instructions.

> **Panel behaviour**: Swing "Raw Input" always shows the original decoded video regardless of the
> processor. Swing "Processed Output" shows the result of `VideoProcessor.process()`.
> Only the re-encoded stream sent to the browser is affected by processing.

---

## Latency tuning

| Setting | Value | Effect |
|---|---|---|
| `webrtcBin.set("latency", 0)` | 0 ms | Removes webrtcbin's 200 ms jitter buffer — worthless on LAN |
| Post-decoder queues `max-size-buffers=1, leaky=2` | 1 buffer | Display/capture/encode queues drop stale frames, always show latest |
| `captureAppSink` `sync=false` | — | No clock stalling in the Java bridge callback |
| `vp8enc deadline=1, cpu-used=8, lag-in-frames=0` | — | Fastest libvpx preset; no lookahead |
| Pre-decoder queue | **default (200 buffers, no leak)** | VP8 needs all RTP packets to reconstruct a frame; dropping here causes severe block artifacts |

The pre-decoder queue deliberately uses GStreamer defaults. VP8 is a compressed format — each RTP
packet is part of a coded frame. Dropping any packet before the decoder produces irreversible block
artifacts. Only post-decoder queues use the single-buffer leaky configuration.

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

- **X11 libraries** (required by Java AWT/Swing):
  ```
  libxtst6   libxrender1   libxi6
  ```
  The JVM throws `UnsatisfiedLinkError: libXtst.so.6` when the first Swing window is created,
  not at startup — easy to miss inside Docker.

- **An X display** accessible to the process. For Docker:
  ```bash
  xhost +local:docker          # run once on the host, before docker compose up
  ```

### Maven dependencies

| Artifact | Purpose |
|---|---|
| `spring-boot-starter-websocket` | WebSocket endpoint at `/rtc`; `ServletServerContainerFactoryBean` sets 512 KB message buffer |
| `spring-boot-starter-web` | REST endpoints (debug bridge, SSE) |
| `org.freedesktop.gstreamer:gst1-java-core:1.4.0` | GStreamer Java bindings (JNA); includes `WebRTCBin`, `AppSink`, `AppSrc` |
| `org.bouncycastle:bcprov-jdk18on:1.80` | Crypto (retained, not on active call path) |

> **WebSocket message size**: Tomcat's default WebSocket text buffer is 8 KB. SDP + 16 ICE
> candidates easily exceeds this (closes with code 1009). `WebRtcConfig` registers a
> `ServletServerContainerFactoryBean` that raises it to 512 KB.

---

## Building & running

### With Docker Compose (recommended)

The Dockerfile is a **multi-stage build**: the Maven build runs inside Docker (where OpenCV is
available), so no separate `mvn package` step is needed.

```bash
xhost +local:docker
docker build -t javartc-javartc:latest .
docker compose up
```

Open `http://localhost:8088/` and click **Start Call**.

The first build downloads all Maven and apt dependencies — subsequent builds are fast thanks to
Docker layer caching (only the Maven compile step re-runs when sources change).

> Static files in `src/main/resources/static/` are volume-mounted; a browser hard-refresh
> (`Ctrl+Shift+R`) is enough for JS/HTML changes — no rebuild needed.

Docker Compose starts two containers:

| Container | Image | Role |
|---|---|---|
| `coturn` | `coturn/coturn:latest` | STUN + TURN relay on UDP 3478 |
| `javartc` | `javartc-javartc:latest` | Spring Boot app on HTTP 8088 |

Both use `network_mode: host` so ICE candidates and TURN relay work without NAT configuration.

### Host build (IDE / no Docker)

For IDE support and faster compile-check cycles, you can still build on the host. OpenCV will
not be available, so `EdgeDetectionVideoProcessor` will not be compiled or activated — the other
processors (Passthrough, Grayscale) work as normal.

```bash
mvn package -DskipTests
```

To also activate OpenCV locally, install `libopencv-java`, register its JAR in your local Maven
repo, and build with the profile:

```bash
sudo apt install libopencv-java
mvn install:install-file \
  -Dfile=$(find /usr/share/java -name "opencv*.jar" | head -1) \
  -DgroupId=org.opencv -DartifactId=opencv-java -Dversion=4.5.4 -Dpackaging=jar
mvn package -DskipTests -Popencv
```

### Without Docker (direct run)

Install the GStreamer packages above on the host, then:

```bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     -Dorg.ice4j.ice.harvest.DISABLE_AWS_HARVESTER=true \
     -jar target/javartc-0.0.1-SNAPSHOT.jar
```

### Configuration (`application.yaml`)

```yaml
server:
  port: 8088

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
`set-remote-description`. webrtcbin intersects the offered codecs with those provided by installed
GStreamer plugins and generates the answer via `create-answer`. Adding a new codec is as simple as
installing the GStreamer plugin — no Java changes required.

### Echo pipeline: breaking the GStreamer loop

Naively connecting `webrtcbin.src_0 → decode → encode → webrtcbin.sink_0` creates a cycle.
GStreamer detects this with a "loop detected" warning and refuses to schedule the pipeline.

The fix is an **AppSink/AppSrc bridge**:
- `captureAppSink` is a graph leaf (GStreamer sees nothing downstream of it).
- `encoderSrc` is a graph root (GStreamer sees nothing upstream of it).
- A Java `NEW_SAMPLE` callback copies buffers between them, breaking the cycle from GStreamer's
  perspective while preserving the data flow.

### Send path: encode chain must be linked before PLAYING

webrtcbin configures its DTLS-SRTP send context during the `PAUSED → PLAYING` transition. If
`sink_0` has an upstream chain linked to it at that moment, the send path is configured. If the
chain is linked later (e.g. in `pad-added` which fires after PLAYING), webrtcbin silently sends
nothing — `outbound-rtp bytesSent` in the browser's `getStats()` stays at zero.

Additionally, the sink pad must be **requested before `set-remote-description`**. Requesting it
early forces a SENDRECV transceiver so `create-answer` produces `a=sendrecv`. Requesting it after
the offer is processed creates a RECVONLY transceiver — the browser's `ontrack` never fires.

### AppSrc caps from first live sample

The encode chain's AppSrc is created without caps because video dimensions are not known until the
first decoded frame arrives. The `NEW_SAMPLE` callback detects the first sample with an
`AtomicBoolean`, reads `sample.getCaps()` (width, height, format), and calls
`encoderSrc.setCaps(...)` before pushing the buffer. Without this, `vp8enc` emits
`Internal data stream error`.

### GLib main loop

`webrtcbin` async operations require an active GLib main loop. `WebRtcConfig` starts one as a
daemon thread at application startup:

```java
Gst.init(Version.of(1, 14), "javartc");
Thread loop = new Thread(Gst::main, "gst-main-loop");
loop.setDaemon(true);
loop.start();
```

### State ordering matters

```
pipeline.setState(READY)              // synchronous — webrtcbin task queue starts here
webrtcBin.set("latency", 0)
webrtcBin.getRequestPad("sink_%u")    // creates SENDRECV transceiver
// build + link full encode chain to sink_0
webrtcBin.addIceCandidate(...)        // pre-add browser candidates
webrtcBin.emit("set-remote-description", offer, promise1)
// inside promise1 callback:
webrtcBin.emit("create-answer", null, promise2)
// inside promise2 callback:
webrtcBin.setLocalDescription(answer)
pipeline.setState(PLAYING)            // encode chain already linked → send path configured
sendAnswerOverWebSocket(answer)
// pad-added fires for src_0 → decode+display+capture chain linked dynamically
```

### gst1-java-core 1.4.0 quirks

| Issue | Detail |
|---|---|
| `set-remote-description` reply is `null` | Success — do NOT call `getReply()`. `Structure.objectFor(null)` NPEs in `ConcurrentHashMap.get(null)`. |
| No `Buffer.getSize()` | Use `buf.map(false).remaining()` |
| No `Buffer.copy()` | Manually: `map(false)` → read bytes → `unmap()` → new `Buffer(n)` → `map(true)` → put → `unmap()` |
| No WebRTCRTPTransceiver direction API | Use the pre-request-sink-pad workaround instead |

### Debug bridge

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/debug/events` | SSE (GET) | Push commands to the browser (`call`, `state`, `stats`) |
| `/api/debug/log` | POST | Receive browser console output server-side |
| `/api/debug/cmd?cmd=call` | GET | Trigger a call via SSE |

The browser intercepts `console.log/warn/error` and forwards them to `/api/debug/log`, making it
possible to see browser WebRTC state in the server log without opening DevTools.

---

## Known limitations

- **Video only** — audio is not negotiated; the pipeline has no audio branch.
- The app requires a physical or virtual X display. Headless rendering (Xvfb) would remove the X11
  dependency for server deployments.
- Session teardown on WebSocket close disposes the pipeline but does not send a WebRTC `BYE`.

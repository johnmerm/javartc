# Copilot Instructions

## Build & Run

**OS prerequisites:** GStreamer must be installed with VP8/H264/Opus support.

```bash
# Ubuntu/Debian
sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav
```

```bash
mvn package              # build
mvn package -DskipTests  # build, skipping tests
mvn test                 # run all tests
mvn test -Dtest=CertificateGeneratorTest  # run a single test class
```

**Running with Docker Compose (recommended):**
```bash
mvn package -DskipTests
docker compose up
```
This starts coturn (STUN/TURN on UDP 3478) and the Spring Boot app (HTTP 8080) together.

**Running directly (without Docker):**
1. Start coturn separately or point `application.yaml` `turn.*` at any TURN server.
2. `java --add-opens=java.base/java.lang=ALL-UNNAMED -jar target/javartc-*.jar`
3. Open `http://localhost:8080` and click "Start Call".
4. The app requires a display (non-headless) — Swing JFrames show the video.

## Architecture

**Goal:** WebRTC video from a browser → decoded and displayed in a Swing app.

```
Browser (vanilla JS, modern WebRTC API)
    └─ WebSocket /rtc  (Spring Boot 3.5.3, Jakarta EE)
           └─ RtcApplication  (signaling handler)
                  ├─ ICEManager (ice4j 3.x)
                  │        └─ TurnCandidateHarvester → coturn (docker-compose)
                  └─ GStreamerMediaManager
                           ├─ SdpNegotiator  — queries GStreamer registry at startup
                           └─ per-session Pipeline:
                                AppSrc (ice4j DatagramSocket)
                                  → dtlssrtpdec  (DTLS-SRTP, GStreamer-managed)
                                  → rtpptdemux → rtpvp8depay → vp8dec
                                  → videoconvert → AppSink → BufferedImage
                                                              → Swing JPanel (MediaPlayer)

docker-compose.yml
    ├─ coturn  (STUN + TURN, UDP 3478)
    └─ javartc (Spring Boot app, port 8080)
```

### Key classes

| Class | Role |
|---|---|
| `RtcApplication` | Spring Boot entry point; wires all beans; WebSocket handler at `/rtc` |
| `ICEManager` / `ICEHandler` | ice4j 3.x wrapper; one `ICEHandler` per WebSocket session; uses `CompletableFuture` for async ICE completion |
| `GStreamerMediaManager` / `SessionHandler` | Builds and manages the GStreamer pipeline per session; pumps UDP from ice4j socket into `AppSrc` via a reader thread |
| `SdpNegotiator` | Inspects GStreamer element registry at startup; builds SDP answers by intersecting offer codecs with supported ones |
| `MediaPlayer` | `AppSink` → `BufferedImage` → Swing `JPanel` renderer |
| `CertificateGenerator` | Generates a self-signed RSA-2048 cert (BouncyCastle 1.80); fingerprint is placed in the SDP answer |
| `MediaType` | Local enum (VIDEO/AUDIO/DATA) replacing removed libjitsi `MediaType` |

### SDP negotiation flow

1. Browser sends JSON `{ type:"offer", sdp:"...", candidates:[...] }` over WebSocket.
2. `RtcApplication` parses the SDP with `NistSdpFactory`.
3. `ICEManager` inits an ICE stream (video + rtcp-mux).
4. `SdpNegotiator.buildAnswer()` inspects GStreamer for supported codecs and generates a proper SDP answer string with real ICE credentials and our certificate fingerprint.
5. Answer is sent back; `GStreamerMediaManager.startMedia()` is called which awaits ICE completion on a background thread, then builds and starts the GStreamer pipeline.

### Session-scoped handler pattern

Both `ICEManager` and `GStreamerMediaManager` store per-session handlers in `ConcurrentHashMap<sessionId, Handler>`. Handlers are lazily created on first access and cleaned up in `afterConnectionClosed`.

## Key Conventions

- **No embedded STUN/TURN server.** ICE/TURN are external infrastructure run via `docker-compose.yml` (coturn). `TurnServerApplication` has been deleted.
- **GStreamer manages DTLS** — `dtlssrtpdec`/`dtlssrtpenc` elements handle the DTLS handshake internally. `CertificateGenerator` produces the fingerprint placed in the SDP; it must match what GStreamer uses.
- **Dynamic SDP** — no hardcoded SDP template files. `SdpNegotiator` builds the answer at runtime. Adding codec support = install the GStreamer plugin; the negotiator picks it up automatically.
- **ice4j 3.x** brings Kotlin runtime + `jitsi-utils` + `jicoco-config` as transitive deps from the Jitsi Maven repo. This is expected.
- **`javax.sdp`** is still used (via `java-sdp-nist-bridge 1.2`, pulled transitively by ice4j). This is not the same namespace migration as `javax` → `jakarta` EE — it's fine.
- **Reactor 1.x removed** — async ICE/pair events use `CompletableFuture` throughout.
- Spring Boot 3.x requires `jakarta.*` (not `javax.annotation`, `javax.servlet` etc.).

## Configuration (`application.yaml`)

```yaml
server:
  port: 8080
turn:
  uri: "turn:localhost:3478"
  username: "javartc"
  credential: "javartc"
```

TURN config can be overridden via `SPRING_APPLICATION_JSON` env var in Docker.

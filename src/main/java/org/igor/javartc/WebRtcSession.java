package org.igor.javartc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.webrtc.*;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.igor.javartc.msg.WebSocketMsg.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-WebSocket-session WebRTC peer connection backed by GStreamer's webrtcbin.
 * webrtcbin handles ICE, DTLS-SRTP, and codec negotiation internally.
 * <p>
 * Flow:
 * <ol>
 *   <li>Browser sends offer SDP + ICE candidates via WebSocket.</li>
 *   <li>{@link #handleOffer} sets the remote description, creates an answer,
 *       and sends it back along with server ICE candidates.</li>
 *   <li>Video track arrives via {@code pad-added}; pipeline decodes and
 *       displays it in a Swing {@link MediaPlayer}.</li>
 * </ol>
 */
public class WebRtcSession {

    private static final Logger LOG = LoggerFactory.getLogger(WebRtcSession.class);

    private final WebSocketSession wsSession;
    private final ObjectMapper mapper;
    private final String turnServer; // turn://user:pass@host:port
    private final List<VideoProcessor> processors;
    private final AtomicReference<VideoProcessor> activeProcessor;

    private Pipeline pipeline;
    private WebRTCBin webrtcBin;
    private MediaPlayer rawPlayer;       // Panel 1: raw decoded video (GStreamer-driven)
    private MediaPlayer processedPlayer; // Panel 2: processed output (Java-driven)
    private AppSrc encoderSrc;           // echo path — kept for EOS on close()

    // Pre-requested webrtcbin sink pad for the echo (send) track.
    // Must exist before create-answer so the SDP answer includes a sendrecv m-line.
    private Pad webrtcSinkPad;

    // Set to true after the first sample's caps have been applied to encoderSrc.
    private final AtomicBoolean encoderCapsSet = new AtomicBoolean(false);

    // Cached frame dimensions from first sample caps (used by processor).
    private volatile int frameWidth;
    private volatile int frameHeight;

    // Candidates gathered by webrtcbin before the answer is sent
    private final List<CandidateMsg> localCandidates = new CopyOnWriteArrayList<>();
    private volatile boolean answerSent = false;

    public WebRtcSession(WebSocketSession wsSession, ObjectMapper mapper,
                         String turnServer, List<VideoProcessor> processors) {
        this.wsSession = wsSession;
        this.mapper = mapper;
        this.turnServer = turnServer;
        this.processors = processors;
        this.activeProcessor = new AtomicReference<>(processors.isEmpty() ? null : processors.get(0));
    }

    /**
     * Process an incoming SDP offer (+ bundled ICE candidates) from the browser.
     */
    public void handleOffer(String offerSdp, List<CandidateMsg> remoteCandidates) {
        pipeline = new Pipeline("webrtc-" + wsSession.getId());
        webrtcBin = new WebRTCBin("webrtcbin");

        // Log all GStreamer bus errors and warnings so encode-chain failures are visible
        Bus bus = pipeline.getBus();
        bus.connect((Bus.ERROR) (source, code, message) ->
            LOG.error("GStreamer ERROR from {}: [{}] {}", source.getName(), code, message));
        bus.connect((Bus.WARNING) (source, code, message) ->
            LOG.warn("GStreamer WARNING from {}: [{}] {}", source.getName(), code, message));
        bus.connect((Bus.INFO) (source, code, message) ->
            LOG.info("GStreamer INFO from {}: [{}] {}", source.getName(), code, message));

        // STUN is enough for LAN; TURN is for relay
        webrtcBin.setStunServer("stun://stun.l.google.com:19302");
        if (turnServer != null && !turnServer.isBlank()) {
            webrtcBin.setTurnServer(turnServer);
        }

        // Collect local ICE candidates; send them with the answer
        webrtcBin.connect((WebRTCBin.ON_ICE_CANDIDATE) (sdpMLineIndex, candidate) -> {
            LOG.debug("Local ICE candidate [{}]: {}", sdpMLineIndex, candidate);
            CandidateMsg msg = new CandidateMsg(null, sdpMLineIndex, "candidate:" + candidate);
            localCandidates.add(msg);
        });

        // Decode chain attached dynamically when webrtcbin exposes a received RTP pad.
        // Uses a tee to split decoded frames to:
        //   (1) Panel 1 "Raw Input"      — GStreamer-driven Swing display
        //   (2) Panel 2 "Processed Out"  — Java-driven, via captureAppSink bridge
        webrtcBin.connect((Element.PAD_ADDED) (element, pad) -> {
            Caps caps = pad.getCurrentCaps();
            String capsStr = (caps != null) ? caps.toString() : "";
            LOG.info("webrtcbin pad-added: {} caps={}", pad.getName(), capsStr);

            // Only handle RTP video pads
            if (!capsStr.contains("application/x-rtp")) {
                LOG.info("Skipping non-RTP pad: {}", pad.getName());
                return;
            }

            String depayName, decodeName;
            if (capsStr.contains("VP9") || capsStr.contains("vp9")) {
                depayName = "rtpvp9depay"; decodeName = "vp9dec";
            } else if (capsStr.contains("H264") || capsStr.contains("h264")) {
                depayName = "rtph264depay"; decodeName = "avdec_h264";
            } else {
                depayName = "rtpvp8depay"; decodeName = "vp8dec";
            }
            LOG.info("Receive pad {}: depay={} decode={}", pad.getName(), depayName, decodeName);

            String id = pad.getName();
            // The pre-decoder queue must NOT drop packets — VP8 needs every RTP packet
            // to reconstruct a complete frame. Dropping here causes severe block artifacts.
            Element queue  = ElementFactory.make("queue", "q-" + id);
            Element depay  = ElementFactory.make(depayName,            "depay-" + id);
            Element decode = ElementFactory.make(decodeName,           "dec-"   + id);
            Element tee    = ElementFactory.make("tee",                "tee-"   + id);

            // ── Branch 1: raw display (BGRx → Swing Panel 1) ─────────────────────
            // Post-decoder: decoded frames are independent — safe to drop old ones.
            Element displayQueue = tuned(ElementFactory.make("queue",        "dq-"    + id));
            Element displayConv  = ElementFactory.make("videoconvert",       "dconv-" + id);
            rawPlayer = new MediaPlayer("JavaRTC – Raw Input");
            AppSink displayAppSink = rawPlayer.createSink(); // BGRx, sync=false inside createSink

            // ── Branch 2: capture for processing + echo (BGR → Java bridge) ───────
            Element captureQueue = tuned(ElementFactory.make("queue",        "cq-"    + id));
            Element captureConv  = ElementFactory.make("videoconvert",       "cconv-" + id);
            AppSink captureAppSink = new AppSink("cap-" + id);
            captureAppSink.set("emit-signals", true);
            captureAppSink.set("sync",         false); // no clock stalls
            captureAppSink.set("drop",         true);  // never block the decode chain
            captureAppSink.set("max-buffers",  1);
            captureAppSink.setCaps(new Caps("video/x-raw,format=BGR"));

            pipeline.addMany(queue, depay, decode, tee,
                             displayQueue, displayConv, displayAppSink,
                             captureQueue, captureConv, captureAppSink);
            Element.linkMany(queue, depay, decode, tee);

            // tee → display branch
            Pad teeDisplayPad = tee.getRequestPad("src_%u");
            teeDisplayPad.link(displayQueue.getStaticPad("sink"));
            Element.linkMany(displayQueue, displayConv, displayAppSink);

            // tee → capture branch
            Pad teeCapturePad = tee.getRequestPad("src_%u");
            teeCapturePad.link(captureQueue.getStaticPad("sink"));
            Element.linkMany(captureQueue, captureConv, captureAppSink);

            // Open the processed-output Swing panel with processor selector
            processedPlayer = new MediaPlayer("JavaRTC – Processed Output", processors, activeProcessor::set);

            // Bridge: BGR frame → VideoProcessor → encoderSrc + Panel 2
            final AppSrc src = encoderSrc;
            captureAppSink.connect((AppSink.NEW_SAMPLE) sink -> {
                Sample sample = sink.pullSample();
                if (sample == null) return FlowReturn.ERROR;
                try {
                    // On first sample: extract dimensions + set AppSrc caps
                    if (encoderCapsSet.compareAndSet(false, true)) {
                        Caps sampleCaps = sample.getCaps();
                        if (sampleCaps != null) {
                            Structure s = sampleCaps.getStructure(0);
                            frameWidth  = s.getInteger("width");
                            frameHeight = s.getInteger("height");
                            src.setCaps(sampleCaps);
                            LOG.info("encoderSrc caps set: {}x{} BGR", frameWidth, frameHeight);
                        }
                    }

                    Buffer srcBuf = sample.getBuffer();
                    ByteBuffer srcData = srcBuf.map(false);
                    if (srcData == null) return FlowReturn.ERROR;
                    int size = srcData.remaining();
                    byte[] bgr = new byte[size];
                    srcData.get(bgr);
                    srcBuf.unmap();

                    // Call the active video processor (passthrough returns null)
                    VideoProcessor vp = activeProcessor.get();
                    byte[] processed = (vp != null) ? vp.process(bgr, frameWidth, frameHeight) : null;
                    byte[] out = (processed != null) ? processed : bgr;

                    // Push to encoder (→ browser)
                    Buffer dstBuf = new Buffer(out.length);
                    ByteBuffer dstData = dstBuf.map(true);
                    dstData.put(out);
                    dstBuf.unmap();
                    dstBuf.setPresentationTimestamp(srcBuf.getPresentationTimestamp());
                    dstBuf.setDuration(srcBuf.getDuration());
                    FlowReturn ret = src.pushBuffer(dstBuf);
                    if (ret != FlowReturn.OK) {
                        LOG.warn("encoderSrc.pushBuffer returned: {}", ret);
                    }

                    // Update Panel 2 with the processed frame
                    if (processedPlayer != null && frameWidth > 0) {
                        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                                frameWidth, frameHeight, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
                        byte[] imgData = ((java.awt.image.DataBufferByte)
                                img.getRaster().getDataBuffer()).getData();
                        System.arraycopy(out, 0, imgData, 0, Math.min(out.length, imgData.length));
                        processedPlayer.showFrame(img);
                    }
                } finally {
                    sample.dispose();
                }
                return FlowReturn.OK;
            });

            for (Element e : List.of(queue, depay, decode, tee,
                                     displayQueue, displayConv, displayAppSink,
                                     captureQueue, captureConv, captureAppSink)) {
                e.syncStateWithParent();
            }
            try {
                pad.link(queue.getStaticPad("sink"));
                LOG.info("Decode+echo chain linked for pad {}", pad.getName());
            } catch (Exception ex) {
                LOG.error("Failed to link decode chain", ex);
            }
        });

        pipeline.add(webrtcBin);

        // webrtcbin's task queue (used by set-remote-description and create-answer) only
        // starts when the element transitions NULL → READY.  setState(PLAYING) is async
        // so the element may still be in NULL by the time we emit signals.  Force READY
        // first (synchronous), do the full SDP exchange, then go to PLAYING so media flows.
        StateChangeReturn readyRet = pipeline.setState(State.READY);
        LOG.info("Pipeline → READY: {}", readyRet);

        // Reduce webrtcbin jitter buffer from default 200ms to 0 — no benefit on localhost.
        webrtcBin.set("latency", 0);

        // ── Pre-request the send pad BEFORE set-remote-description ──────────────────
        // This creates a SENDRECV transceiver in webrtcbin BEFORE it processes the
        // remote offer.  When create-answer runs it naturally produces a=sendrecv
        // and configures the DTLS-SRTP send context — no SDP patching required.
        webrtcSinkPad = webrtcBin.getRequestPad("sink_%u");
        LOG.info("Pre-requested webrtcbin sink pad: {}",
                webrtcSinkPad != null ? webrtcSinkPad.getName() : "null");

        // ── Echo encode chain: AppSrc(BGR) → videoconvert → capsfilter(I420) → vp8enc → rtpvp8pay ──
        // AppSrc is the root of this sub-graph (breaks the loop from webrtcbin's src pad).
        // Capture branch produces BGR; a GStreamer videoconvert handles BGR→I420 for vp8enc.
        // Caps are set dynamically from the first captured sample (actual dimensions unknown here).
        encoderSrc = (AppSrc) ElementFactory.make("appsrc", "enc-src");
        encoderSrc.set("is-live",     true);
        encoderSrc.set("format",      3);     // GST_FORMAT_TIME
        encoderSrc.set("block",       false);
        encoderSrc.set("stream-type", 0);     // STREAM

        Element encConvert = ElementFactory.make("videoconvert", "enc-conv"); // BGR→I420
        Element capsfilter = ElementFactory.make("capsfilter",   "vcaps");
        capsfilter.set("caps", new Caps("video/x-raw,format=I420"));
        Element encQueue   = tuned(ElementFactory.make("queue",  "eq"));
        Element encoder    = ElementFactory.make("vp8enc",       "enc");
        encoder.set("deadline",       1L); // realtime mode
        encoder.set("cpu-used",       8);  // fastest libvpx preset
        encoder.set("lag-in-frames",  0);  // no lookahead
        encoder.set("error-resilient", 1);
        Element payer      = ElementFactory.make("rtpvp8pay",    "pay");
        pipeline.addMany(encoderSrc, encConvert, capsfilter, encQueue, encoder, payer);
        Element.linkMany(encoderSrc, encConvert, capsfilter, encQueue, encoder, payer);
        if (webrtcSinkPad != null) {
            try {
                payer.getStaticPad("src").link(webrtcSinkPad);
                LOG.info("Encode chain linked to webrtcbin sink pad {} BEFORE SDP exchange",
                        webrtcSinkPad.getName());
            } catch (Exception ex) {
                LOG.error("Failed to link encode chain to webrtcSinkPad", ex);
            }
        }

        // Add browser's ICE candidates (can be done before remote description)
        if (remoteCandidates != null) {
            for (CandidateMsg c : remoteCandidates) {
                String line = c.getCandidate();
                String bare = line.startsWith("candidate:") ? line.substring("candidate:".length()) : line;
                webrtcBin.addIceCandidate(c.getSdpMLineIndex(), bare);
            }
        }

        // Chain: set-remote-description → create-answer → set-local-description → PLAYING → send
        SDPMessage offerMsg = new SDPMessage();
        offerMsg.parseBuffer(offerSdp);
        // disown() on BOTH the SDPMessage AND the WebRTCSessionDescription:
        // gst_webrtc_session_description_new() takes ownership of the GstSDPMessage struct.
        // If only the descriptor is disowned, the Java GC will still call gst_sdp_message_free()
        // on offerMsg when it is collected — a use-after-free that manifests as a SIGSEGV in
        // gst_sdp_message_medias_len during pipeline teardown.
        offerMsg.disown();
        WebRTCSessionDescription offer = new WebRTCSessionDescription(WebRTCSDPType.OFFER, offerMsg);
        offer.disown(); // webrtcbin takes full ownership of descriptor + message

        org.freedesktop.gstreamer.Promise setRemotePromise =
                new org.freedesktop.gstreamer.Promise(setRemoteDone -> {
            // set-remote-description replies with NULL on success — do NOT call getReply()
            // (gst1-java-core NPEs on null native pointer in Structure.objectFor).
            // The callback firing at all means it completed; errors would show on the bus.
            LOG.info("set-remote-description done, creating answer");
            setRemoteDone.dispose();

            org.freedesktop.gstreamer.Promise createAnswerPromise =
                    new org.freedesktop.gstreamer.Promise(createAnswerDone -> {
                try {
                    org.freedesktop.gstreamer.Structure reply = createAnswerDone.getReply();
                    LOG.info("create-answer reply: {}", reply);

                    if (reply == null || !reply.hasField("answer")) {
                        LOG.error("create-answer returned no answer field. Reply: {}", reply);
                        return;
                    }

                    WebRTCSessionDescription answer =
                            (WebRTCSessionDescription) reply.getValue("answer");

                    // With the sink pad pre-requested, webrtcbin should naturally produce
                    // a=sendrecv — no patching needed.
                    String rawSdp = answer.getSDPMessage().toString();
                    LOG.info("SDP answer:\n{}", rawSdp);

                    SDPMessage patchedMsg = new SDPMessage();
                    patchedMsg.parseBuffer(rawSdp);
                    patchedMsg.disown(); // same ownership rule as offerMsg above
                    WebRTCSessionDescription localAnswer =
                            new WebRTCSessionDescription(WebRTCSDPType.ANSWER, patchedMsg);
                    localAnswer.disown();
                    webrtcBin.setLocalDescription(localAnswer);

                    // Start media pipeline — encode chain is already linked
                    StateChangeReturn playRet = pipeline.setState(State.PLAYING);
                    LOG.info("Pipeline → PLAYING: {}", playRet);

                    CompletableFuture.runAsync(() -> sendAnswer(rawSdp));
                } catch (Exception e) {
                    LOG.error("Error creating answer", e);
                } finally {
                    createAnswerDone.dispose();
                }
            });

            webrtcBin.emit("create-answer", (Object) null, createAnswerPromise);
        });

        webrtcBin.emit("set-remote-description", offer, setRemotePromise);
    }

    private void sendAnswer(String answerSdp) {
        try {
            // Wait briefly for ICE gathering to produce at least some candidates
            Thread.sleep(500);
            WebSocketMsg reply = new WebSocketMsg(Type.answer);
            reply.setSdp(answerSdp);
            reply.setCandidates(new ArrayList<>(localCandidates));
            wsSession.sendMessage(new TextMessage(mapper.writeValueAsBytes(reply)));
            answerSent = true;
            LOG.info("Answer sent with {} local ICE candidate(s)", localCandidates.size());
        } catch (Exception e) {
            LOG.error("Failed to send SDP answer", e);
        }
    }

    public void close() {
        // Stop the pipeline first. setState(NULL) is normally synchronous but webrtcbin
        // can return ASYNC — getState() with a timeout blocks until the transition completes
        // (or times out). Only after all GStreamer threads have stopped is it safe to call
        // dispose(); calling it while the GLib main loop still has callbacks queued for this
        // pipeline causes use-after-free crashes in native code.
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);
                pipeline.getState(5_000_000_000L); // wait up to 5 s for NULL state
            } catch (Exception e) {
                LOG.warn("Error stopping pipeline", e);
            }
            try { pipeline.dispose(); } catch (Exception ignored) {}
            pipeline = null;
        }
        if (rawPlayer != null)       { rawPlayer.close();       rawPlayer = null; }
        if (processedPlayer != null) { processedPlayer.close(); processedPlayer = null; }
    }

    /**
     * Returns a queue element tuned for low latency: holds at most one buffer,
     * drops old frames (leaky downstream) rather than blocking the producer.
     */
    private static Element tuned(Element queue) {
        queue.set("max-size-buffers", 1);
        queue.set("max-size-bytes",   0);
        queue.set("max-size-time",    0L);
        queue.set("leaky",            2); // GST_QUEUE_LEAK_DOWNSTREAM
        return queue;
    }
}

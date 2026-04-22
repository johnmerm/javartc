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

    private Pipeline pipeline;
    private WebRTCBin webrtcBin;
    private MediaPlayer mediaPlayer;
    private AppSrc encoderSrc; // echo path — kept for EOS on close()

    // Pre-requested webrtcbin sink pad for the echo (send) track.
    // Must exist before create-answer so the SDP answer includes a sendrecv m-line.
    private Pad webrtcSinkPad;

    // Set to true after the first sample's caps have been applied to encoderSrc.
    private final AtomicBoolean encoderCapsSet = new AtomicBoolean(false);

    // Candidates gathered by webrtcbin before the answer is sent
    private final List<CandidateMsg> localCandidates = new CopyOnWriteArrayList<>();
    private volatile boolean answerSent = false;

    public WebRtcSession(WebSocketSession wsSession, ObjectMapper mapper, String turnServer) {
        this.wsSession = wsSession;
        this.mapper = mapper;
        this.turnServer = turnServer;
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
        // Uses a tee to split decoded frames to (a) Swing display and (b) echo capture sink.
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
            Element queue   = ElementFactory.make("queue",        "q-"     + id);
            Element depay   = ElementFactory.make(depayName,      "depay-" + id);
            Element decode  = ElementFactory.make(decodeName,     "dec-"   + id);
            Element tee     = ElementFactory.make("tee",          "tee-"   + id);

            // ── Branch 1: display in Swing ────────────────────────────────────
            Element displayQueue = ElementFactory.make("queue",        "dq-"    + id);
            Element displayConv  = ElementFactory.make("videoconvert", "dconv-" + id);
            mediaPlayer = new MediaPlayer("JavaRTC – Remote Video");
            AppSink displayAppSink = mediaPlayer.createSink(); // BGRx

            // ── Branch 2: capture for echo ────────────────────────────────────
            Element captureQueue = ElementFactory.make("queue",        "cq-"    + id);
            Element captureConv  = ElementFactory.make("videoconvert", "cconv-" + id);
            AppSink captureAppSink = new AppSink("cap-" + id);
            captureAppSink.set("emit-signals", true);
            captureAppSink.set("drop",         true); // never block the decode chain
            captureAppSink.set("max-buffers",  1);
            captureAppSink.setCaps(new Caps("video/x-raw,format=I420"));

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

            // Bridge: pull I420 frame from captureAppSink → push to encoderSrc
            final AppSrc src = encoderSrc;
            captureAppSink.connect((AppSink.NEW_SAMPLE) sink -> {
                Sample sample = sink.pullSample();
                if (sample == null) return FlowReturn.ERROR;
                try {
                    // Set AppSrc caps from the very first sample so GStreamer knows the
                    // actual width/height for caps negotiation with vp8enc downstream.
                    if (encoderCapsSet.compareAndSet(false, true)) {
                        Caps sampleCaps = sample.getCaps();
                        if (sampleCaps != null) {
                            src.setCaps(sampleCaps);
                            LOG.info("encoderSrc caps set from first sample: {}", sampleCaps);
                        }
                    }

                    Buffer srcBuf = sample.getBuffer();
                    ByteBuffer srcData = srcBuf.map(false);
                    if (srcData == null) return FlowReturn.ERROR;
                    int size = srcData.remaining();
                    byte[] bytes = new byte[size];
                    srcData.get(bytes);
                    srcBuf.unmap();

                    Buffer dstBuf = new Buffer(size);
                    ByteBuffer dstData = dstBuf.map(true);
                    dstData.put(bytes);
                    dstBuf.unmap();
                    dstBuf.setPresentationTimestamp(srcBuf.getPresentationTimestamp());
                    dstBuf.setDuration(srcBuf.getDuration());
                    FlowReturn ret = src.pushBuffer(dstBuf);
                    if (ret != FlowReturn.OK) {
                        LOG.warn("encoderSrc.pushBuffer returned: {}", ret);
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

        // ── Pre-request the send pad BEFORE set-remote-description ──────────────────
        // This creates a SENDRECV transceiver in webrtcbin BEFORE it processes the
        // remote offer.  When create-answer runs it naturally produces a=sendrecv
        // and configures the DTLS-SRTP send context — no SDP patching required.
        webrtcSinkPad = webrtcBin.getRequestPad("sink_%u");
        LOG.info("Pre-requested webrtcbin sink pad: {}",
                webrtcSinkPad != null ? webrtcSinkPad.getName() : "null");

        // ── Echo encode chain: AppSrc → videorate → capsfilter → vp8enc → rtpvp8pay ──
        // AppSrc is the root of this sub-graph (breaks the loop from webrtcbin's src pad).
        // The captureAppSink bridge (set up dynamically in pad-added) pushes I420 frames here.
        encoderSrc = (AppSrc) ElementFactory.make("appsrc", "enc-src");
        encoderSrc.set("is-live", true);
        encoderSrc.set("format", 3);       // GST_FORMAT_TIME
        encoderSrc.set("block", false);
        encoderSrc.set("stream-type", 0);  // STREAM
        // Caps are set dynamically from the first captured sample (actual width/height unknown here)

        // videorate converts variable/unknown framerate → fixed 30 fps for vp8enc
        Element videorate  = ElementFactory.make("videorate",   "vrate");
        Element capsfilter = ElementFactory.make("capsfilter",  "vcaps");
        capsfilter.set("caps", new Caps("video/x-raw,format=I420,framerate=30/1"));
        Element encQueue   = ElementFactory.make("queue",       "eq");
        encQueue.set("leaky", 2); // drop old frames if encoder is slow
        Element encoder    = ElementFactory.make("vp8enc",      "enc");
        encoder.set("deadline", 1L);
        encoder.set("error-resilient", 1);
        Element payer      = ElementFactory.make("rtpvp8pay",   "pay");
        pipeline.addMany(encoderSrc, videorate, capsfilter, encQueue, encoder, payer);
        Element.linkMany(encoderSrc, videorate, capsfilter, encQueue, encoder, payer);
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
        WebRTCSessionDescription offer = new WebRTCSessionDescription(WebRTCSDPType.OFFER, offerMsg);
        offer.disown(); // webrtcbin takes ownership

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
        if (encoderSrc != null) {
            try { encoderSrc.endOfStream(); } catch (Exception ignored) {}
        }
        if (pipeline != null) {
            pipeline.setState(State.NULL);
            pipeline.dispose();
        }
        if (mediaPlayer != null) {
            mediaPlayer.close();
        }
    }
}

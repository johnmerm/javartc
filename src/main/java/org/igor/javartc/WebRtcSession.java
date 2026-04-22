package org.igor.javartc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.webrtc.*;import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.igor.javartc.msg.WebSocketMsg.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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

        // Decode chain attached when webrtcbin exposes a decoded pad
        webrtcBin.connect((Element.PAD_ADDED) (element, pad) -> {
            Caps caps = pad.getCurrentCaps();
            String capsStr = (caps != null) ? caps.toString() : "";
            LOG.info("webrtcbin pad-added: {} caps={}", pad.getName(), capsStr);

            // Only handle RTP video pads; ignore RTCP and other internal pads
            if (!capsStr.contains("application/x-rtp") && !pad.getName().startsWith("recv_rtp_src")) {
                LOG.info("Skipping non-RTP pad: {}", pad.getName());
                return;
            }

            // Select depayloader/decoder from negotiated encoding name
            String depayName, decodeName;
            if (capsStr.contains("VP9") || capsStr.contains("vp9")) {
                depayName = "rtpvp9depay"; decodeName = "vp9dec";
            } else if (capsStr.contains("H264") || capsStr.contains("h264")) {
                depayName = "rtph264depay"; decodeName = "avdec_h264";
            } else {
                // Default VP8
                depayName = "rtpvp8depay"; decodeName = "vp8dec";
            }
            LOG.info("Using depayloader={} decoder={}", depayName, decodeName);

            Element queue   = ElementFactory.make("queue",        "q-"     + pad.getName());
            Element depay   = ElementFactory.make(depayName,      "depay-" + pad.getName());
            Element decode  = ElementFactory.make(decodeName,     "dec-"   + pad.getName());
            Element convert = ElementFactory.make("videoconvert", "conv-"  + pad.getName());

            mediaPlayer = new MediaPlayer("JavaRTC – Remote Video");
            AppSink sink = mediaPlayer.createSink();

            pipeline.addMany(queue, depay, decode, convert, sink);
            Element.linkMany(queue, depay, decode, convert, sink);

            for (Element e : List.of(queue, depay, decode, convert, sink)) {
                e.syncStateWithParent();
            }

            Pad sinkPad = queue.getStaticPad("sink");
            try {
                pad.link(sinkPad);
                LOG.info("Decode chain linked for pad {}", pad.getName());
            } catch (Exception ex) {
                LOG.error("Failed to link webrtcbin pad to decode chain", ex);
            }
        });

        pipeline.add(webrtcBin);

        // webrtcbin's task queue (used by set-remote-description and create-answer) only
        // starts when the element transitions NULL → READY.  setState(PLAYING) is async
        // so the element may still be in NULL by the time we emit signals.  Force READY
        // first (synchronous), do the full SDP exchange, then go to PLAYING so media flows.
        StateChangeReturn readyRet = pipeline.setState(State.READY);
        LOG.info("Pipeline → READY: {}", readyRet);

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
                    webrtcBin.setLocalDescription(answer);
                    String sdp = answer.getSDPMessage().toString();
                    LOG.info("SDP answer:\n{}", sdp);

                    // Start media pipeline now that SDP is agreed
                    StateChangeReturn playRet = pipeline.setState(State.PLAYING);
                    LOG.info("Pipeline → PLAYING: {}", playRet);

                    CompletableFuture.runAsync(() -> sendAnswer(sdp));
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
        if (pipeline != null) {
            pipeline.setState(State.NULL);
            pipeline.dispose();
        }
        if (mediaPlayer != null) {
            mediaPlayer.close();
        }
    }
}

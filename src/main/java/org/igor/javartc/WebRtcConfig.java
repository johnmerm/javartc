package org.igor.javartc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class WebRtcConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WebRtcConfig.class);

    @Value("${turn.uri:}") private String turnUri;
    @Value("${turn.username:}") private String turnUsername;
    @Value("${turn.credential:}") private String turnCredential;

    /** TURN URL in the format webrtcbin expects: turn://user:pass@host:port */
    private String turnServer() {
        if (turnUri == null || turnUri.isBlank()) return null;
        try {
            // turnUri is e.g. "turn:127.0.0.1:3478"
            String hostPort = turnUri.replaceFirst("^turns?://", "").replaceFirst("^turns?:", "");
            return "turn://" + turnUsername + ":" + turnCredential + "@" + hostPort;
        } catch (Exception e) {
            LOG.warn("Could not build TURN server URL from {}", turnUri);
            return null;
        }
    }

    /**
     * Initialises the GStreamer runtime and starts the GLib main loop.
     *
     * <p><b>Why this must run before anything else:</b>
     * {@code webrtcbin} — and every other GStreamer element — requires the native library to be
     * initialised exactly once via {@link Gst#init} before any element is created or pipeline
     * state is changed.  Calling it a second time is a no-op, but calling it zero times causes
     * a JVM crash when the first native call is made.
     *
     * <p><b>Why a GLib main loop thread:</b>
     * {@code webrtcbin} internally posts work items (promise callbacks, pad-added events, bus
     * messages) onto the GLib default main context.  Without an active {@link Gst#main()} loop
     * consuming that context, those callbacks are never dispatched — ICE completion, SDP answer
     * creation, and pad-added notifications all silently stall.  The loop is started as a daemon
     * thread so it does not prevent JVM shutdown.
     *
     * <p><b>Codec discovery:</b>
     * The method also queries the GStreamer element registry for the decoder plugins that
     * {@link WebRtcSession} will use at runtime (one per negotiable codec family).  This is a
     * pure startup sanity-check: if a plugin is missing the operator sees an explicit log line
     * rather than a cryptic pipeline error mid-call.  The set of probed elements must stay in
     * sync with the depayloader/decoder selection logic in {@code WebRtcSession.PAD_ADDED}.
     *
     * <p><b>Spring wiring note:</b>
     * This bean has no meaningful return value; it exists only for its side effects.  Returning
     * {@code Void} (null) satisfies Spring's requirement that {@code @Bean} methods have a
     * non-void return type while making the intent explicit.
     */
    @Bean
    public Void initGStreamer() {
        // Request GStreamer ≥ 1.14 API; the runtime version on Ubuntu 22.04 is 1.20.
        Gst.init(Version.of(1, 14), "javartc");

        // GLib main loop — must stay running for the lifetime of the application.
        Thread loop = new Thread(Gst::main, "gst-main-loop");
        loop.setDaemon(true);
        loop.start();

        // Probe the element registry for the decoders WebRtcSession needs.
        // Keep this list in sync with the depay/decode selection in WebRtcSession.PAD_ADDED.
        record CodecProbe(String element, String codec) {}
        List.of(
            new CodecProbe("vp8dec",     "VP8"),
            new CodecProbe("vp9dec",     "VP9"),
            new CodecProbe("avdec_h264", "H264"),
            new CodecProbe("opusdec",    "Opus")
        ).forEach(p -> {
            if (ElementFactory.find(p.element()) != null) {
                LOG.info("Codec available: {} ({})", p.codec(), p.element());
            } else {
                LOG.warn("Codec NOT available: {} — {} not found in GStreamer registry; "
                       + "install the corresponding plugin package", p.codec(), p.element());
            }
        });

        return null;
    }

    /** Increase Tomcat WebSocket message buffer from the 8 KB default to 512 KB.
     *  SDP offer + ICE candidates can exceed 8 KB with many candidates. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        return container;
    }

    @Bean
    public WebSocketHandler rtcHandler(ObjectMapper mapper, List<VideoProcessor> processors) {
        Map<String, WebRtcSession> sessions = new ConcurrentHashMap<>();
        String turn = turnServer();

        return new TextWebSocketHandler() {

            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                LOG.info("WebSocket connected: {}", session.getId());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                LOG.info("WebSocket closed: {} status={}", session.getId(), status);
                WebRtcSession s = sessions.remove(session.getId());
                if (s != null) s.close();
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                LOG.error("WebSocket transport error: {} — {}", session.getId(), exception.getMessage(), exception);
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                LOG.info("WebSocket message received: {} bytes", message.getPayloadLength());
                try {
                    WebSocketMsg msg = mapper.readValue(message.getPayload(), WebSocketMsg.class);

                    if (msg.getSdp() != null) {
                        // New call — close any existing session for this WebSocket
                        WebRtcSession old = sessions.remove(session.getId());
                        if (old != null) old.close();

                        WebRtcSession rtcSession = new WebRtcSession(session, mapper, turn, processors);
                        sessions.put(session.getId(), rtcSession);

                        List<CandidateMsg> candidates = msg.getCandidates();
                        rtcSession.handleOffer(msg.getSdp(), candidates);

                    } else if (msg.getCandidates() != null) {
                        // Trickle ICE candidates arriving separately (shouldn't happen with our JS,
                        // but handle gracefully)
                        WebRtcSession rtcSession = sessions.get(session.getId());
                        if (rtcSession != null) {
                            LOG.warn("Late trickle candidates — webrtcbin may already be negotiating");
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Error handling WebSocket message", e);
                    throw e;
                }
            }
        };
    }
}

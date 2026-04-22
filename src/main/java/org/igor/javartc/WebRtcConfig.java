package org.igor.javartc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.igor.javartc.sdp.SdpNegotiator;
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

    @Bean
    public SdpNegotiator sdpNegotiator() {
        Gst.init(Version.of(1, 14), "javartc");
        // GLib main loop — required for webrtcbin async operations
        Thread loop = new Thread(Gst::main, "gst-main-loop");
        loop.setDaemon(true);
        loop.start();
        return new SdpNegotiator();
    }

    @Bean
    public WebSocketHandler rtcHandler(ObjectMapper mapper, VideoProcessor videoProcessor) {
        Map<String, WebRtcSession> sessions = new ConcurrentHashMap<>();
        String turn = turnServer();

        return new TextWebSocketHandler() {

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                WebRtcSession s = sessions.remove(session.getId());
                if (s != null) s.close();
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                try {
                    WebSocketMsg msg = mapper.readValue(message.getPayload(), WebSocketMsg.class);

                    if (msg.getSdp() != null) {
                        // New call — close any existing session for this WebSocket
                        WebRtcSession old = sessions.remove(session.getId());
                        if (old != null) old.close();

                        WebRtcSession rtcSession = new WebRtcSession(session, mapper, turn, videoProcessor);
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

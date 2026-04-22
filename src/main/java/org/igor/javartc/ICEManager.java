package org.igor.javartc;
import org.igor.javartc.MediaType;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ICEManager {

    private static final Logger LOG = LoggerFactory.getLogger(ICEManager.class);

    private final ObjectMapper mapper;
    private final String turnUri;
    private final String turnUsername;
    private final String turnCredential;
    private final Map<String, ICEHandler> handlers = new ConcurrentHashMap<>();

    public ICEManager(String turnUri, String turnUsername, String turnCredential, ObjectMapper mapper) {
        this.turnUri = turnUri;
        this.turnUsername = turnUsername;
        this.turnCredential = turnCredential;
        this.mapper = mapper;
    }

    public ICEHandler getHandler(WebSocketSession session) {
        return handlers.computeIfAbsent(session.getId(), id -> new ICEHandler(session));
    }

    public void closeSession(WebSocketSession session) {
        ICEHandler handler = handlers.remove(session.getId());
        if (handler != null) handler.close();
    }

    @PreDestroy
    public void cleanup() {
        handlers.values().forEach(ICEHandler::close);
        handlers.clear();
    }

    // -------------------------------------------------------------------------

    public class ICEHandler {

        private final WebSocketSession session;
        private final Agent agent;
        private final Map<MediaType, IceMediaStream> streamMap = new LinkedHashMap<>();
        private final List<IceMediaStream> streams = new ArrayList<>();
        private final CompletableFuture<IceProcessingState> agentStateFuture = new CompletableFuture<>();
        private final Map<Component, CompletableFuture<CandidatePair>> pairFutures = new HashMap<>();
        private final List<CandidateMsg> pendingCandidates = new ArrayList<>();

        private String remoteUfrag;
        private String remotePassword;

        public String getLocalUfrag()    { return agent.getLocalUfrag(); }
        public String getLocalPassword() { return agent.getLocalPassword(); }

        private ICEHandler(WebSocketSession session) {
            this.session = session;
            this.agent = buildAgent(turnUri, turnUsername, turnCredential);
            this.agent.addStateChangeListener(evt -> {
                IceProcessingState newState = (IceProcessingState) evt.getNewValue();
                LOG.info("ICE [{}]: {} -> {}", session.getId(), evt.getOldValue(), newState);
                if (newState == IceProcessingState.TERMINATED || newState == IceProcessingState.FAILED) {
                    agentStateFuture.complete(newState);
                }
            });
        }

        public CompletableFuture<IceProcessingState> getAgentStateFuture() {
            return agentStateFuture;
        }

        public CompletableFuture<CandidatePair> getPairFuture(Component component) {
            return pairFutures.getOrDefault(component, CompletableFuture.failedFuture(
                    new IllegalStateException("No future for component " + component.getComponentID())));
        }

        public void initStream(MediaType mediaType, boolean rtcpMux) {
            IceMediaStream stream = agent.createMediaStream(mediaType.name() + session.getId());

            stream.addPairChangeListener(evt -> {
                CandidatePair pair = (CandidatePair) evt.getSource();
                if (pair.getState() == CandidatePairState.SUCCEEDED) {
                    CompletableFuture<CandidatePair> f = pairFutures.get(pair.getParentComponent());
                    if (f != null && !f.isDone()) f.complete(pair);
                }
            });

            streams.add(stream);
            streamMap.put(mediaType, stream);

            try {
                Component rtp = agent.createComponent(stream, Transport.UDP, 10000, 10000, 11000);
                pairFutures.put(rtp, new CompletableFuture<>());
                if (!rtcpMux) {
                    Component rtcp = agent.createComponent(stream, Transport.UDP, 10001, 10001, 11000);
                    pairFutures.put(rtcp, new CompletableFuture<>());
                }
            } catch (IllegalArgumentException | IOException e) {
                LOG.error("Failed to create ICE components", e);
            }

            // Flush any candidates that arrived before this stream was created
            if (!pendingCandidates.isEmpty()) {
                LOG.info("Flushing {} buffered candidate(s) after stream init", pendingCandidates.size());
                List<CandidateMsg> toFlush = new ArrayList<>(pendingCandidates);
                pendingCandidates.clear();
                applyRemoteCandidates(toFlush);
            }
        }

        public IceMediaStream getICEMediaStream(MediaType mediaType) {
            return streamMap.get(mediaType);
        }

        public void setupFragPasswd(String ufrag, String password) {
            this.remoteUfrag = ufrag;
            this.remotePassword = password;
        }

        public List<CandidateMsg> getLocalCandidates() {
            List<CandidateMsg> result = new ArrayList<>();
            for (int i = 0; i < streams.size(); i++) {
                IceMediaStream stream = streams.get(i);
                for (Component cmp : stream.getComponents()) {
                    for (LocalCandidate lc : cmp.getLocalCandidates()) {
                        result.add(new CandidateMsg(stream.getName(), i, lc.toString()));
                    }
                }
            }
            return result;
        }

        public void processRemoteCandidates(List<CandidateMsg> candidates) {
            if (streams.isEmpty()) {
                // Streams not initialised yet (trickle candidates arrived before offer).
                // Buffer them; initStream() will flush the buffer.
                LOG.warn("ICE streams not ready — buffering {} candidate(s)", candidates.size());
                pendingCandidates.addAll(candidates);
                return;
            }
            applyRemoteCandidates(candidates);
        }

        private void applyRemoteCandidates(List<CandidateMsg> candidates) {
            streams.forEach(s -> {
                s.setRemoteUfrag(remoteUfrag);
                s.setRemotePassword(remotePassword);
            });

            for (CandidateMsg msg : candidates) {
                addRemoteCandidate(msg.getSdpMLineIndex(), msg.getCandidate());
            }

            try {
                WebSocketMsg reply = new WebSocketMsg(null).setCandidates(getLocalCandidates());
                session.sendMessage(new TextMessage(mapper.writeValueAsBytes(reply)));
                agent.startConnectivityEstablishment();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void addRemoteCandidate(int sdpMLineIndex, String candidateLine) {
            String[] tokens = candidateLine.split(":", 2);
            if (!"candidate".equalsIgnoreCase(tokens[0])) {
                throw new IllegalArgumentException("Expected 'candidate:' prefix: " + candidateLine);
            }
            IceMediaStream stream = streams.get(sdpMLineIndex);
            String[] parts = tokens[1].trim().split("\\s+");
            int i = 0;
            String foundation = parts[i++];
            int cmpId = Integer.parseInt(parts[i++]);
            Component parentComponent = stream.getComponent(cmpId);
            if (parentComponent == null) return;

            Transport transport = Transport.parse(parts[i++].toLowerCase());
            long priority = Long.parseLong(parts[i++]);
            String host = parts[i++];
            int port = Integer.parseInt(parts[i++]);
            TransportAddress addr = new TransportAddress(host, port, transport);

            CandidateType type = null;
            if (i < parts.length && "typ".equalsIgnoreCase(parts[i])) {
                type = CandidateType.parse(parts[++i].toLowerCase());
                i++;
            }

            String rAddr = null;
            int rPort = -1;
            while (i < parts.length) {
                switch (parts[i].toLowerCase()) {
                    case "raddr" -> rAddr = parts[++i];
                    case "rport" -> rPort = Integer.parseInt(parts[++i]);
                    default -> { /* skip generation, network-cost, etc. */ }
                }
                i++;
            }

            RemoteCandidate related = null;
            if (rAddr != null) {
                TransportAddress relatedAddr = new TransportAddress(rAddr, rPort, transport);
                related = new RemoteCandidate(relatedAddr, parentComponent, type, foundation, priority, null);
            }
            RemoteCandidate rc = new RemoteCandidate(addr, parentComponent, type, foundation, priority, related);
            parentComponent.addRemoteCandidate(rc);
        }

        public void prepareAnswerSdp(javax.sdp.SessionDescription answerSdp) {
            try {
                @SuppressWarnings("unchecked")
                Vector<javax.sdp.MediaDescription> mds =
                        (Vector<javax.sdp.MediaDescription>) answerSdp.getMediaDescriptions(false);
                if (mds != null) {
                    for (javax.sdp.MediaDescription md : mds) {
                        md.setAttribute("ice-ufrag", agent.getLocalUfrag());
                        md.setAttribute("ice-pwd", agent.getLocalPassword());
                    }
                }
            } catch (javax.sdp.SdpException e) {
                throw new RuntimeException(e);
            }
        }

        public void close() {
            streams.forEach(agent::removeStream);
            pairFutures.values().forEach(f -> f.cancel(true));
            agentStateFuture.cancel(true);
            agent.free();
        }
    }

    // -------------------------------------------------------------------------
    // Static factory helpers

    public static Agent buildAgent(String turnUri, String turnUsername, String turnCredential) {
        Agent agent = new Agent();
        agent.setControlling(false);

        if (turnUri != null && !turnUri.isBlank()) {
            try {
                URI uri = URI.create(turnUri);
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 3478;
                TransportAddress turnAddr = new TransportAddress(
                        InetAddress.getByName(host), port, Transport.UDP);
                LongTermCredential cred = new LongTermCredential(turnUsername, turnCredential);
                agent.addCandidateHarvester(new TurnCandidateHarvester(turnAddr, cred));
            } catch (Exception e) {
                LOG.warn("Failed to configure TURN harvester, falling back to host candidates only", e);
            }
        }
        return agent;
    }
}

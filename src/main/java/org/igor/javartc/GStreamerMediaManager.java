package org.igor.javartc;
import org.igor.javartc.MediaType;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.igor.javartc.ICEManager.ICEHandler;
import org.igor.javartc.sdp.SdpNegotiator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import javax.sdp.SessionDescription;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-session GStreamer pipelines for DTLS-SRTP RTP reception and
 * video display. Replaces the old libjitsi-based JitsiMediaManager.
 *
 * <p>Pipeline (video receive path):
 * <pre>
 *   appsrc  ──►  dtlssrtpdec  ──►  rtpptdemux  ──►  rtpvp8depay  ──►  vp8dec
 *                                                                     ──►  videoconvert  ──►  appsink (Swing)
 * </pre>
 */
public class GStreamerMediaManager {

    private static final Logger LOG = LoggerFactory.getLogger(GStreamerMediaManager.class);

    private final ICEManager iceManager;
    private final SdpNegotiator sdpNegotiator;
    private final org.igor.javartc.dtls.CertificateGenerator certGen;
    private final Map<String, SessionHandler> handlers = new ConcurrentHashMap<>();

    public GStreamerMediaManager(ICEManager iceManager, SdpNegotiator sdpNegotiator,
                                 org.igor.javartc.dtls.CertificateGenerator certGen) {
        this.iceManager = iceManager;
        this.sdpNegotiator = sdpNegotiator;
        this.certGen = certGen;
    }

    public SdpNegotiator getSdpNegotiator() {
        return sdpNegotiator;
    }

    public SessionHandler getHandler(WebSocketSession session) {
        return handlers.computeIfAbsent(session.getId(), id -> new SessionHandler(session));
    }

    public void closeSession(WebSocketSession session) {
        SessionHandler h = handlers.remove(session.getId());
        if (h != null) {
            try { h.close(); } catch (IOException e) { LOG.error("Error closing session", e); }
        }
    }

    // -------------------------------------------------------------------------

    public class SessionHandler implements Closeable {

        private final WebSocketSession session;
        private final ICEHandler iceHandler;
        private String remoteFingerprint;
        private Pipeline pipeline;
        private MediaPlayer player;
        private Thread udpReaderThread;

        SessionHandler(WebSocketSession session) {
            this.session = session;
            this.iceHandler = iceManager.getHandler(session);
        }

        public void setRemoteFingerprint(String fingerprint) {
            this.remoteFingerprint = fingerprint;
        }

        /** Called after SDP exchange to start media once ICE completes. */
        public void startMedia(MediaType mediaType) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Wait for ICE to complete
                    IceProcessingState state = iceHandler.getAgentStateFuture().get();
                    LOG.info("ICE completed with state: {}", state);

                    IceMediaStream stream = iceHandler.getICEMediaStream(mediaType);
                    if (stream == null) {
                        LOG.error("No ICE stream for {} — skipping", mediaType);
                        return;
                    }
                    Component rtpComponent = stream.getComponent(Component.RTP);
                    if (rtpComponent == null) {
                        LOG.error("No RTP component in stream — skipping");
                        return;
                    }

                    // getSocket() returns the multiplexing socket used after ICE completes
                    DatagramSocket socket = rtpComponent.getSocket();
                    buildAndStartPipeline(socket, mediaType);

                } catch (Exception e) {
                    LOG.error("Failed to start media", e);
                }
            });
        }

        private void buildAndStartPipeline(DatagramSocket socket, MediaType mediaType) {
            pipeline = new Pipeline("javartc-" + session.getId());

            // Source: UDP packets from ice4j socket fed via AppSrc
            AppSrc appSrc = (AppSrc) ElementFactory.make("appsrc", "udp-src");
            appSrc.set("is-live", true);
            appSrc.set("format", Format.TIME);
            appSrc.setCaps(Caps.fromString("application/x-rtp"));

            // DTLS-SRTP decryptor — GStreamer handles the DTLS handshake
            Element dtlsDec = ElementFactory.make("dtlssrtpdec", "dtls-dec");
            if (remoteFingerprint != null) {
                dtlsDec.set("connection-id", session.getId());
            }

            // RTP demux by payload type
            Element ptDemux = ElementFactory.make("rtpptdemux", "pt-demux");

            // Video path
            Element depay  = ElementFactory.make("rtpvp8depay",  "vp8-depay");
            Element decode = ElementFactory.make("vp8dec",        "vp8-dec");
            Element conv   = ElementFactory.make("videoconvert",  "conv");

            player = new MediaPlayer("JavaRTC – Remote Video");
            AppSink sink = player.createSink();

            pipeline.addMany(appSrc, dtlsDec, ptDemux, depay, decode, conv, sink);
            Element.linkMany(appSrc, dtlsDec);

            // rtpptdemux has dynamic pads; link depay when a video pad appears
            ptDemux.connect((Element.PAD_ADDED) (element, pad) -> {
                LOG.info("rtpptdemux pad added: {}", pad.getName());
                Pad sinkPad = depay.getStaticPad("sink");
                if (sinkPad != null && !sinkPad.isLinked()) {
                    pad.link(sinkPad);
                    Element.linkMany(depay, decode, conv, sink);
                }
            });
            dtlsDec.getStaticPad("rtp_src").link(ptDemux.getStaticPad("sink"));

            pipeline.setState(State.PLAYING);
            LOG.info("GStreamer pipeline started for session {}", session.getId());

            // Thread to pump UDP packets from ice4j socket into appsrc
            udpReaderThread = new Thread(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                    try {
                        socket.receive(packet);
                        Buffer gstBuf = new Buffer(packet.getLength());
                        gstBuf.map(true).put(buf, 0, packet.getLength());
                        gstBuf.unmap();
                        appSrc.pushBuffer(gstBuf);
                    } catch (IOException e) {
                        if (!socket.isClosed()) LOG.warn("UDP read error", e);
                        break;
                    }
                }
                LOG.info("UDP reader thread exiting for session {}", session.getId());
            }, "udp-reader-" + session.getId());
            udpReaderThread.setDaemon(true);
            udpReaderThread.start();
        }

        @Override
        public void close() throws IOException {
            if (udpReaderThread != null) udpReaderThread.interrupt();
            if (pipeline != null) {
                pipeline.setState(State.NULL);
                pipeline.dispose();
            }
            if (player != null) player.close();
        }
    }
}

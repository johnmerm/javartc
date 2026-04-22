package org.igor.javartc.sdp;

import org.freedesktop.gstreamer.ElementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sdp.*;
import java.util.*;

/**
 * Builds SDP answers by intersecting the browser's offer with codecs that
 * are actually available in the local GStreamer installation.
 */
public class SdpNegotiator {

    private static final Logger LOG = LoggerFactory.getLogger(SdpNegotiator.class);

    private record CodecInfo(String gstElement, String sdpName, int clockRate, int channels, String fmtp) {}

    private static final List<CodecInfo> CANDIDATE_CODECS = List.of(
        new CodecInfo("vp8dec",     "VP8",  90000, 0, null),
        new CodecInfo("vp9dec",     "VP9",  90000, 0, null),
        new CodecInfo("avdec_h264", "H264", 90000, 0, "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"),
        new CodecInfo("opusdec",    "opus", 48000, 2, "minptime=10;useinbandfec=1")
    );

    private final List<CodecInfo> supportedCodecs;

    public SdpNegotiator() {
        supportedCodecs = new ArrayList<>();
        for (CodecInfo codec : CANDIDATE_CODECS) {
            if (ElementFactory.find(codec.gstElement()) != null) {
                supportedCodecs.add(codec);
                LOG.info("Codec supported: {} (via {})", codec.sdpName(), codec.gstElement());
            } else {
                LOG.info("Codec NOT available: {} ({} not found)", codec.sdpName(), codec.gstElement());
            }
        }
        if (supportedCodecs.isEmpty()) {
            LOG.warn("No GStreamer codecs found — check GStreamer plugin installation");
        }
    }

    public String buildAnswer(SessionDescription offerSdp,
                              String iceUfrag, String icePwd,
                              String dtlsFingerprint) throws SdpException {

        @SuppressWarnings("unchecked")
        Vector<MediaDescription> offerMedia =
                (Vector<MediaDescription>) offerSdp.getMediaDescriptions(false);

        // Collect BUNDLE mids from offer
        List<String> bundleMids = new ArrayList<>();
        List<String> mediaSections = new ArrayList<>();

        if (offerMedia != null) {
            for (MediaDescription offerMd : offerMedia) {
                String mid = offerMd.getAttribute("mid");
                String section = buildMediaSection(offerMd, iceUfrag, icePwd, dtlsFingerprint);
                if (mid != null) bundleMids.add(mid);
                mediaSections.add(section);
            }
        }

        long sessionId = System.currentTimeMillis();
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=javartc ").append(sessionId).append(" 1 IN IP4 0.0.0.0\r\n");
        sdp.append("s=-\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=msid-semantic: WMS *\r\n");
        if (!bundleMids.isEmpty()) {
            sdp.append("a=group:BUNDLE ").append(String.join(" ", bundleMids)).append("\r\n");
        }
        for (String section : mediaSections) {
            sdp.append(section);
        }

        String result = sdp.toString();
        LOG.info("SDP answer:\n{}", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private String buildMediaSection(MediaDescription offerMd,
                                     String iceUfrag, String icePwd,
                                     String fingerprint) throws SdpException {
        String mediaType = offerMd.getMedia().getMediaType();

        // Collect offered payload types: pt -> codec name
        Map<Integer, String> offeredPt = new LinkedHashMap<>();
        Vector<Attribute> offerAttrs = (Vector<Attribute>) offerMd.getAttributes(false);
        if (offerAttrs != null) {
            for (Attribute a : offerAttrs) {
                try {
                    if ("rtpmap".equals(a.getName())) {
                        String[] parts = a.getValue().split("\\s+", 2);
                        if (parts.length == 2) {
                            offeredPt.put(Integer.parseInt(parts[0].trim()),
                                          parts[1].split("/")[0]);
                        }
                    }
                } catch (SdpParseException ignored) {}
            }
        }

        // Intersect with locally supported codecs
        List<Map.Entry<Integer, CodecInfo>> negotiated = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : offeredPt.entrySet()) {
            for (CodecInfo supported : supportedCodecs) {
                boolean isAudio = supported.channels() > 0;
                if ((isAudio == "audio".equals(mediaType))
                        && supported.sdpName().equalsIgnoreCase(entry.getValue())) {
                    negotiated.add(Map.entry(entry.getKey(), supported));
                    break;
                }
            }
        }

        String mid = offerMd.getAttribute("mid");
        String offerDirection = getDirection(offerAttrs);

        StringBuilder s = new StringBuilder();

        if (negotiated.isEmpty()) {
            LOG.warn("No common codecs for '{}' — rejecting m-section", mediaType);
            s.append("m=").append(mediaType).append(" 0 UDP/TLS/RTP/SAVPF 0\r\n");
            s.append("c=IN IP4 0.0.0.0\r\n");
            s.append("a=inactive\r\n");
            if (mid != null) s.append("a=mid:").append(mid).append("\r\n");
            return s.toString();
        }

        // m= line with all negotiated payload types
        StringJoiner pts = new StringJoiner(" ");
        negotiated.forEach(e -> pts.add(String.valueOf(e.getKey())));
        s.append("m=").append(mediaType).append(" 9 UDP/TLS/RTP/SAVPF ").append(pts).append("\r\n");
        s.append("c=IN IP4 0.0.0.0\r\n");
        s.append("a=rtcp:9 IN IP4 0.0.0.0\r\n");
        s.append("a=ice-ufrag:").append(iceUfrag).append("\r\n");
        s.append("a=ice-pwd:").append(icePwd).append("\r\n");
        s.append("a=ice-options:trickle\r\n");
        s.append("a=fingerprint:").append(fingerprint).append("\r\n");
        s.append("a=setup:passive\r\n");
        if (mid != null) s.append("a=mid:").append(mid).append("\r\n");
        s.append("a=").append(flipDirection(offerDirection)).append("\r\n");
        s.append("a=rtcp-mux\r\n");

        // One rtpmap + optional fmtp + rtcp-fb per negotiated codec
        for (Map.Entry<Integer, CodecInfo> e : negotiated) {
            int pt = e.getKey();
            CodecInfo codec = e.getValue();
            s.append("a=rtpmap:").append(pt).append(" ")
             .append(codec.sdpName()).append("/").append(codec.clockRate());
            if (codec.channels() > 0) s.append("/").append(codec.channels());
            s.append("\r\n");
            if (codec.fmtp() != null) {
                s.append("a=fmtp:").append(pt).append(" ").append(codec.fmtp()).append("\r\n");
            }
            if ("video".equals(mediaType)) {
                s.append("a=rtcp-fb:").append(pt).append(" nack\r\n");
                s.append("a=rtcp-fb:").append(pt).append(" nack pli\r\n");
                s.append("a=rtcp-fb:").append(pt).append(" ccm fir\r\n");
                s.append("a=rtcp-fb:").append(pt).append(" goog-remb\r\n");
            }
        }
        return s.toString();
    }

    private static String getDirection(Vector<Attribute> attrs) {
        if (attrs == null) return "sendrecv";
        for (Attribute a : attrs) {
            try {
                String n = a.getName();
                if ("sendrecv".equals(n) || "sendonly".equals(n)
                        || "recvonly".equals(n) || "inactive".equals(n)) return n;
            } catch (SdpParseException ignored) {}
        }
        return "sendrecv";
    }

    private static String flipDirection(String d) {
        return switch (d) {
            case "sendonly" -> "recvonly";
            case "recvonly" -> "sendonly";
            default -> "sendrecv";
        };
    }
}

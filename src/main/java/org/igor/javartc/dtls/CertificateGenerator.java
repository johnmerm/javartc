package org.igor.javartc.dtls;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HexFormat;

/**
 * Generates a self-signed RSA certificate and exposes its SHA-256 fingerprint
 * for use in SDP fingerprint attributes.
 * <p>
 * Note: when using GStreamer's dtlssrtp elements the DTLS handshake is managed
 * internally by GStreamer. This class is only used if we need to supply/verify
 * fingerprints independently (e.g. for logging or SDP pre-population).
 */
public class CertificateGenerator {

    private static final long ONE_DAY_MS = 86_400_000L;

    private final X509Certificate certificate;
    private final KeyPair keyPair;
    private final String fingerprint;

    public CertificateGenerator() {
        try {
            SecureRandom rng = SecureRandom.getInstanceStrong();

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, rng);
            keyPair = kpg.generateKeyPair();

            long now = System.currentTimeMillis();
            X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.CN, "JavaRTC")
                    .build();

            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.valueOf(now),
                    new Date(now - ONE_DAY_MS),
                    new Date(now + 7 * ONE_DAY_MS),
                    subject,
                    keyPair.getPublic());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());

            certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));

            fingerprint = computeFingerprint(certificate.getEncoded());

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate self-signed certificate", e);
        }
    }

    private static String computeFingerprint(byte[] derEncoded) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(derEncoded);
        StringBuilder sb = new StringBuilder("sha-256 ");
        String hex = HexFormat.of().formatHex(digest).toUpperCase();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** Returns the fingerprint in SDP format, e.g. {@code sha-256 AA:BB:...} */
    public String getLocalFingerPrint() {
        return fingerprint;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }
}

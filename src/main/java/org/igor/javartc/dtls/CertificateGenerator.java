package org.igor.javartc.dtls;

import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.crypto.tls.DefaultTlsSignerCredentials;
import org.bouncycastle.crypto.tls.HashAlgorithm;
import org.bouncycastle.crypto.tls.SignatureAlgorithm;
import org.bouncycastle.crypto.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.crypto.tls.TlsContext;
import org.bouncycastle.crypto.tls.TlsSignerCredentials;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDefaultDigestProvider;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CertificateGenerator {
	private static final long ONE_DAY = 1000L * 60L * 60L * 24L;
	private static final Logger LOG = LoggerFactory.getLogger(CertificateGenerator.class);
	// @Autowired
	private SecureRandom secureRandom;

	// @Value
	private String signatureAlgorithm = "SHA256withRSA";

	private Certificate certificate;
	private AsymmetricCipherKeyPair keyPair;
	private SignatureAndHashAlgorithm sigHashAlg;
	private String fingerPrint;

	// @Value
	private String applicationName = "MyWebRTCClient";

	public CertificateGenerator() {
		try {
			secureRandom = SecureRandom.getInstanceStrong();
			sigHashAlg = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa);
			keyPair = generateKeyPair();
			generateX509Certificate(generateCN(applicationName), keyPair);
			

		} catch (NoSuchAlgorithmException |OperatorCreationException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	public TlsSignerCredentials getSignerCredentials(TlsContext context) {
		org.bouncycastle.crypto.tls.Certificate cert = new org.bouncycastle.crypto.tls.Certificate(
				new org.bouncycastle.asn1.x509.Certificate[] { certificate });
		;
		AsymmetricKeyParameter keyParam = keyPair.getPrivate();
		return new DefaultTlsSignerCredentials(context, cert, keyParam, sigHashAlg);
	}

	private X500Name generateCN(String cn) {
		X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
		builder.addRDN(BCStyle.CN, cn.toString());
		return builder.build();
	}

	private AsymmetricCipherKeyPair generateKeyPair() {
		RSAKeyPairGenerator generator = new RSAKeyPairGenerator();

		generator.init(new RSAKeyGenerationParameters(new BigInteger("10001", 16), secureRandom, 1024, 80));
		return generator.generateKeyPair();
	}

	private void generateX509Certificate(X500Name subject, AsymmetricCipherKeyPair keyPair)
			throws IOException, OperatorCreationException {

		long now = System.currentTimeMillis();
		Date notBefore = new Date(now - ONE_DAY);
		Date notAfter = new Date(now + 6 * ONE_DAY);
		X509v3CertificateBuilder builder = new X509v3CertificateBuilder(/* issuer */ subject,
				/* serial */ BigInteger.valueOf(now), notBefore, notAfter, subject,
				/* publicKeyInfo */
				SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(keyPair.getPublic()));
		AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find(signatureAlgorithm);
		AlgorithmIdentifier digAlgId = new DefaultDigestAlgorithmIdentifierFinder().find(sigAlgId);
		ContentSigner signer = new BcRSAContentSignerBuilder(sigAlgId, digAlgId).build(keyPair.getPrivate());

		certificate = builder.build(signer).toASN1Structure();
		
		Digest digest = BcDefaultDigestProvider.INSTANCE.get(digAlgId);
        byte[] in = certificate.getEncoded(ASN1Encoding.DER);
        byte[] out = new byte[digest.getDigestSize()];

        digest.update(in, 0, in.length);
        digest.doFinal(out, 0);
        
        
        
        StringBuilder sb = new StringBuilder();
        char[] os = Hex.toHexString(out).toCharArray();
        for (int i=0;i<os.length;i+=2){
        	sb.append(os[i]).append(os[i+1]).append(":");
        }
        fingerPrint = "sha-256 "+sb.substring(0,sb.length()-1).toString().toUpperCase(); 


	}
	
    public String getLocalFingerPrint(){
    	return fingerPrint;
    }

	public Certificate getCertificate() {
		return certificate;
	}


}

package org.igor.javartc.dtls;

import java.io.IOException;
import java.net.DatagramSocket;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.crypto.tls.AlertDescription;
import org.bouncycastle.crypto.tls.AlertLevel;
import org.bouncycastle.crypto.tls.CertificateRequest;
import org.bouncycastle.crypto.tls.ClientCertificateType;
import org.bouncycastle.crypto.tls.DTLSClientProtocol;
import org.bouncycastle.crypto.tls.DefaultTlsClient;
import org.bouncycastle.crypto.tls.ProtocolVersion;
import org.bouncycastle.crypto.tls.SRTPProtectionProfile;
import org.bouncycastle.crypto.tls.TlsAuthentication;
import org.bouncycastle.crypto.tls.TlsCredentials;
import org.bouncycastle.crypto.tls.TlsSRTPUtils;
import org.bouncycastle.crypto.tls.TlsSession;
import org.bouncycastle.crypto.tls.TlsUtils;
import org.bouncycastle.crypto.tls.UseSRTPData;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DtlsClient extends DefaultTlsClient{
	
	//@Autowired
	private CertificateGenerator certgen = new CertificateGenerator();
	private SecureRandom secureRandom;
	
	private static final Logger LOG = LoggerFactory.getLogger(DtlsClient.class);

	
	protected TlsSession session;
	protected UseSRTPData _srtpData;

	public DtlsClient(TlsSession session) {
		try {
			secureRandom = SecureRandom.getInstanceStrong();
		} catch (NoSuchAlgorithmException e) {
			secureRandom = new SecureRandom();
		}
		this.session = session;
		
		int[] protectionProfiles = {SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
	            					SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32};
        byte[] mki = TlsUtils.EMPTY_BYTES;
        this._srtpData = new UseSRTPData(protectionProfiles, mki);
	}

	public TlsSession getSessionToResume() {
		return this.session;
	}

	private void logAlert(String method, short alertLevel, short alertDescription, String message, Throwable cause) {
		String msg = "Method:" + AlertLevel.getText(alertLevel) + ", " + AlertDescription.getText(alertDescription)
				+ (message != null ? "|" + message : "");
		switch (alertLevel) {
		case AlertLevel.fatal:
			LOG.error(msg, cause);
			break;
		case AlertLevel.warning:
			LOG.warn(msg, cause);
			break;
		default:
			LOG.info(msg, cause);
			break;
		}
	}

	public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause) {
		logAlert("AlertRaised", alertLevel, alertDescription, message, cause);
	}

	public void notifyAlertReceived(short alertLevel, short alertDescription) {
		logAlert("AlertReceived", alertLevel, alertDescription, null, null);

	}

	public ProtocolVersion getClientVersion() {
		return ProtocolVersion.DTLSv12;
	}

	public ProtocolVersion getMinimumVersion() {
		return ProtocolVersion.DTLSv10;
	}

	
	
	@Override
	public Hashtable<Integer,byte[]> getClientExtensions() throws IOException {

		@SuppressWarnings("unchecked")
		Hashtable<Integer,byte[]> clientExtensions = super.getClientExtensions();
		if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null) {

			if (clientExtensions == null) {
				clientExtensions = new Hashtable<Integer,byte[]>();
			}

			TlsSRTPUtils.addUseSRTPExtension(clientExtensions, _srtpData);
		}

		return clientExtensions;
	}

	
	@Override
	public void processServerExtensions(Hashtable serverExtensions) throws IOException {
		super.processServerExtensions(serverExtensions);
	}
	
	@Override
	public void processServerSupplementalData(Vector serverSupplementalData) throws IOException {
		super.processServerSupplementalData(serverSupplementalData);
	}
	
	public void notifyServerVersion(ProtocolVersion serverVersion) throws IOException {
		super.notifyServerVersion(serverVersion);

		LOG.info("Negotiated " + serverVersion);
	}

	public TlsAuthentication getAuthentication() throws IOException {
		return new TlsAuthentication() {
			public void notifyServerCertificate(org.bouncycastle.crypto.tls.Certificate serverCertificate)
					throws IOException {
				Certificate[] chain = serverCertificate.getCertificateList();
				LOG.info("DTLS client received server certificate chain of length " + chain.length);
				for (int i = 0; i != chain.length; i++) {
					Certificate entry = chain[i];
					// TODO Create fingerprint based on certificate signature
					// algorithm digest
					LOG.info("fingerprint:SHA-256 " + MyTlsUtils.fingerprint(entry) + " ("
							+ entry.getSubject() + ")");
				}
			}

			public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException {
				short[] certificateTypes = certificateRequest.getCertificateTypes();
				if (certificateTypes == null || !Arrays.contains(certificateTypes, ClientCertificateType.rsa_sign)) {
					return null;
				}
				return certgen.getSignerCredentials(context);
				
			}
		};
	}

	public void notifyHandshakeComplete() throws IOException {
		super.notifyHandshakeComplete();

		TlsSession newSession = context.getResumableSession();
		if (newSession != null) {
			byte[] newSessionID = newSession.getSessionID();
			String hex = Hex.toHexString(newSessionID);

			if (this.session != null && Arrays.areEqual(this.session.getSessionID(), newSessionID)) {
				LOG.info("Resumed session: " + hex);
			} else {
				LOG.info("Established session: " + hex);
			}

			this.session = newSession;
		}
	}
	
	
	public void connect(DatagramSocket socket) throws IOException{
		DTLSClientProtocol clientProtocol = new DTLSClientProtocol(secureRandom);
		clientProtocol.connect(this, new DtlsTransport(socket));
	}
	
	public String getLocalFingerPrint(){
		return certgen.getLocalFingerPrint();
	}
	
	private String remoteFingerprint;
	public void setRemoteFingerPrint(String remoteFingerprint){
		this.remoteFingerprint = remoteFingerprint;
	}
}

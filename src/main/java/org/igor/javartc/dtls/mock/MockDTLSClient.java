package org.igor.javartc.dtls.mock;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Hashtable;

import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.crypto.tls.AlertDescription;
import org.bouncycastle.crypto.tls.AlertLevel;
import org.bouncycastle.crypto.tls.CertificateRequest;
import org.bouncycastle.crypto.tls.ClientCertificateType;
import org.bouncycastle.crypto.tls.DefaultTlsClient;
import org.bouncycastle.crypto.tls.ProtocolVersion;
import org.bouncycastle.crypto.tls.SRTPProtectionProfile;
import org.bouncycastle.crypto.tls.SignatureAlgorithm;
import org.bouncycastle.crypto.tls.TlsAuthentication;
import org.bouncycastle.crypto.tls.TlsCredentials;
import org.bouncycastle.crypto.tls.TlsSRTPUtils;
import org.bouncycastle.crypto.tls.TlsSession;
import org.bouncycastle.crypto.tls.UseSRTPData;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

public class MockDTLSClient
    extends DefaultTlsClient
{
    protected TlsSession session;
    protected UseSRTPData _srtpData;
    public MockDTLSClient(TlsSession session)
    {
        this.session = session;
        int[] protectionProfiles = {SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80};
        byte[] mki = new byte[0];
        _srtpData = new UseSRTPData(protectionProfiles, mki);
    }

    public TlsSession getSessionToResume()
    {
        return this.session;
    }

    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("DTLS client raised alert: " + AlertLevel.getText(alertLevel)
            + ", " + AlertDescription.getText(alertDescription));
        if (message != null)
        {
            out.println(message);
        }
        if (cause != null)
        {
            cause.printStackTrace(out);
        }
    }

    public void notifyAlertReceived(short alertLevel, short alertDescription)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("DTLS client received alert: " + AlertLevel.getText(alertLevel)
            + ", " + AlertDescription.getText(alertDescription));
    }

    public ProtocolVersion getClientVersion()
    {
        return ProtocolVersion.DTLSv12;
    }

    public ProtocolVersion getMinimumVersion()
    {
        return ProtocolVersion.DTLSv10;
    }


    @Override
    public Hashtable getClientExtensions() throws IOException {

        Hashtable clientExtensions = super.getClientExtensions();
        if (TlsSRTPUtils.getUseSRTPExtension(clientExtensions) == null)
        {

            if (clientExtensions == null)
            {
                clientExtensions = new Hashtable();
            }

            TlsSRTPUtils.addUseSRTPExtension(clientExtensions, _srtpData);
        }

        return clientExtensions;
    }

    public void notifyServerVersion(ProtocolVersion serverVersion) throws IOException
    {
        super.notifyServerVersion(serverVersion);

        System.out.println("Negotiated " + serverVersion);
    }

    public TlsAuthentication getAuthentication()
        throws IOException
    {
        return new TlsAuthentication()
        {
            public void notifyServerCertificate(org.bouncycastle.crypto.tls.Certificate serverCertificate)
                throws IOException
            {
                Certificate[] chain = serverCertificate.getCertificateList();
                System.out.println("DTLS client received server certificate chain of length " + chain.length);
                for (int i = 0; i != chain.length; i++)
                {
                    Certificate entry = chain[i];
                    // TODO Create fingerprint based on certificate signature algorithm digest
                    System.out.println("    fingerprint:SHA-256 " + TlsTestUtils.fingerprint(entry) + " ("
                        + entry.getSubject() + ")");
                }
            }

            public TlsCredentials getClientCredentials(CertificateRequest certificateRequest)
                throws IOException
            {
                short[] certificateTypes = certificateRequest.getCertificateTypes();
                if (certificateTypes == null || !Arrays.contains(certificateTypes, ClientCertificateType.rsa_sign))
                {
                    return null;
                }

                return TlsTestUtils.loadSignerCredentials(context, certificateRequest.getSupportedSignatureAlgorithms(),
                    SignatureAlgorithm.rsa, "x509-client.pem", "x509-client-key.pem");
            }
        };
    }

    /**
     * @author johnmerm
     * @return
     */
    public String getLocalFingerPrint(){
    	try {
			Certificate cert = TlsTestUtils.loadCertificateResource( "x509-client.pem");
			String fPrint = TlsTestUtils.fingerprint(cert);
			return "sha-256 "+fPrint;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    	
    }
    
    public void notifyHandshakeComplete() throws IOException
    {
        super.notifyHandshakeComplete();

        TlsSession newSession = context.getResumableSession();
        if (newSession != null)
        {
            byte[] newSessionID = newSession.getSessionID();
            String hex = Hex.toHexString(newSessionID);

            if (this.session != null && Arrays.areEqual(this.session.getSessionID(), newSessionID))
            {
                System.out.println("Resumed session: " + hex);
            }
            else
            {
                System.out.println("Established session: " + hex);
            }

            this.session = newSession;
        }
    }
    
    
    
    
    
    
}
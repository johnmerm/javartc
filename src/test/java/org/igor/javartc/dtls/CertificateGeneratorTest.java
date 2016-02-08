package org.igor.javartc.dtls;

import org.bouncycastle.asn1.x509.Certificate;
import org.junit.Test;

public class CertificateGeneratorTest {

	
	@Test
	public void testCertificateGenerator(){
		CertificateGenerator gen = new CertificateGenerator();
		Certificate cert = gen.getCertificate();
		
		System.out.println(cert);
		
		String f = gen.getLocalFingerPrint();
		System.out.println(f);
	}
}

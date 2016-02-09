package org.igor.javartc.dtls.mock;

import org.junit.Test;

public class MockClientTest {
	@Test
    public void testGetLocalFingerPrint(){
    	MockDTLSClient client = new MockDTLSClient(null);
    	System.out.println(client.getLocalFingerPrint());
    }
}

package org.igor.javartc.dtls.mock;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.security.SecureRandom;

import org.bouncycastle.crypto.tls.DTLSClientProtocol;
import org.bouncycastle.crypto.tls.DTLSTransport;
import org.bouncycastle.crypto.tls.DatagramTransport;
import org.bouncycastle.crypto.tls.TlsClient;
import org.bouncycastle.crypto.tls.UDPTransport;

public class Connect implements Closeable{
	private MockDTLSClient client;
	private DTLSTransport dtls;
	
	public void connect(DatagramSocket socket) throws IOException{
		SecureRandom secureRandom = new SecureRandom();
		client = new MockDTLSClient(null);
		
        int mtu = 1500;
        DatagramTransport transport = new UDPTransport(socket, mtu);
        transport = new UnreliableDatagramTransport(transport, secureRandom, 0, 0);
        transport = new LoggingDatagramTransport(transport, System.out);

        DTLSClientProtocol protocol = new DTLSClientProtocol(secureRandom);

        dtls = protocol.connect(client, transport);
        
        
        Thread listenerThread = new Thread(()->{
        	try{
        		while (true){
        		
	        		byte[] response = new byte[dtls.getReceiveLimit()];
	        		int received = dtls.receive(response, 0, response.length, 30000);
	                if (received >= 0)
	                {
	                    System.out.println("received:"+received);
	                }
        		}
    		}catch (IOException e){
    			
    		}
        	
        },"DTLSlistener");
        
        listenerThread.start();
	}

	@Override
	public void close() throws IOException {
		if (dtls!=null){
			dtls.close();
		}
		
	}
	
	public String getLocalFingerPrint(){
		return client.getLocalFingerPrint();
	}
	
	
}

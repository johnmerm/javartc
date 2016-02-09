package org.igor.javartc.dtls;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.crypto.tls.DatagramTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DtlsTransport implements DatagramTransport,Runnable{
	private static final Logger LOG = LoggerFactory.getLogger(DtlsTransport.class);
	private ArrayBlockingQueue<byte[]> packetQueue = new ArrayBlockingQueue<>(100);
	private DatagramSocket socket;
	
	private volatile boolean running = false; 
	
	@Override
	public void run() {
		while (running && !socket.isClosed()){
			byte[] buffer = new byte[4*1024];
			DatagramPacket packet = new DatagramPacket(buffer, 4*1024);
			try {
				socket.receive(packet);
			} catch (IOException e1) {
				LOG.error("IOException on recv thread!",e1);
				running = false;
			}
			byte[] destBuffer = new byte[packet.getLength()];
			System.arraycopy(buffer, 0, destBuffer, 0, destBuffer.length);
			try {
				packetQueue.put(destBuffer);
			} catch (InterruptedException e) {
				LOG.warn("Interrupted recv thread!");
				running = false;
			}
		}
		
	}
	
	private Thread recvThread;
	public DtlsTransport(DatagramSocket socket) {
		this.socket = socket;
		recvThread = new Thread(this);
		running = true;
		recvThread.start();
	}
	
	@Override
	public void send(byte[] buf, int off, int len) throws IOException {
		DatagramPacket packet = new DatagramPacket(buf, off, len);
		socket.send(packet);
	}
	
	@Override
	public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
		byte[] packet;
		try {
			packet = packetQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			LOG.warn("Interrupted while waiting for receive");
			packet = null;
		}
		if (packet !=null){
			if (buf.length!=len+off){
				LOG.warn("WTF buf.len:"+buf.length+" len:"+len+" off:"+off);
			}
			
			if (packet.length>buf.length-off){
				LOG.warn("cannot Fit packet of size "+packet.length+"into buffer size:"+buf.length+" and offset:"+off);
			}
			System.arraycopy(packet, 0, buf, off, Math.min(packet.length, len));
			return packet.length;
		}
		return -1;
	}
	
	@Override
	public int getSendLimit() throws IOException {
		return 4*1024;
	}
	
	@Override
	public int getReceiveLimit() throws IOException {
		return 4*1024;
	}
	
	@Override
	public void close() throws IOException {
		running = false;
		
	}
}

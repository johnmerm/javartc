package org.igor.javartc;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.jitsi.turnserver.TurnException;
import org.jitsi.turnserver.stack.TurnServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class TurnServerApplication {
    public static void main(String[] args) throws IOException, TurnException, InterruptedException {
        TransportAddress localUDPAddress = new TransportAddress(InetAddress.getLocalHost(),30000, Transport.UDP);
        TurnServer turnServer = new TurnServer(localUDPAddress);
        turnServer.start();
        Thread.currentThread().join();
    }
}

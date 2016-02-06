package org.igor.javartc;


import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;
import javax.sdp.MediaDescription;
import javax.sdp.SdpFactory;
import javax.sdp.SdpParseException;
import javax.sdp.SessionDescription;

import org.apache.commons.io.IOUtils;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TrickleCallback;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.igor.javartc.ICEManager.ICEHandler;
import org.igor.javartc.MediaManager.MediaHandler;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.igor.javartc.msg.WebSocketMsg.Type;
import org.jitsi.impl.neomedia.transform.dtls.DtlsControlImpl;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaType;
import org.jitsi.turnserver.TurnException;
import org.jitsi.turnserver.stack.TurnServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableWebSocket
public class RtcApplication implements WebSocketConfigurer{

	@Bean
	public MediaService mediaService(){
		String libPath = System.getProperty("java.library.path");
		if (libPath == null){
			libPath = "";
		}else{
			libPath=libPath+";";
		}
		libPath = libPath+"C:\\mine\\jitsi\\jitsi-universe\\libjitsi\\lib\\native";
		System.setProperty("java.library.path", libPath);
		
		LibJitsi.start();
		
		ConfigurationService cfg = LibJitsi.getConfigurationService();
		cfg.setProperty(DtlsControlImpl.PROP_SIGNATURE_ALGORITHM, "SHA256withRSA");
		
		MediaService ms = LibJitsi.getMediaService();
		return ms;
	}
	
	@Bean
	public MediaManager mediaManager(){
		return new MediaManager(mediaService(),iceManager());
	}
	
	
	@Bean
	public TurnServer turnServer() throws TurnException, IOException{
		TransportAddress localUDPAddress = new TransportAddress(InetAddress.getLocalHost(),30000, Transport.UDP);
		TurnServer turnServer = new TurnServer(localUDPAddress);
		turnServer.start();
		return turnServer;
	}
	
	@Autowired
	private TurnServer turnServer;
	
	@Bean
	public  Agent iceAgent(){
		Agent agent = new Agent();
		try {
			agent.addCandidateHarvester(new TurnCandidateHarvester(new TransportAddress(InetAddress.getLocalHost(),30000, Transport.UDP)));
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
		
		agent.setControlling(false);
		return agent;
	}
	
	@Bean
	public ICEManager iceManager(){
		return new ICEManager(iceAgent());
	}
	
	
	@Autowired
	private ICEManager iceManager;
	
	@Autowired
	private MediaManager mediaManager;
	
	@PreDestroy
	public void cleanup(){
		turnServer.shutDown();
		LibJitsi.stop();
		executorService.shutdown();
	}
	
	@Bean
	public ExecutorService executorService(){
		return Executors.newCachedThreadPool();
	}
	
	
	@Autowired
	private ExecutorService executorService;
	
	
	
	@Bean
	public WebSocketHandler rtcHandler(final ObjectMapper mapper){
		return new TextWebSocketHandler(){
			
			@Override
			public void afterConnectionEstablished(WebSocketSession session) throws Exception {
				super.afterConnectionEstablished(session);
			}
			
			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
				super.afterConnectionClosed(session, status);
				iceManager.closeSession(session);
				mediaManager.closeSession(session);
			}
			
			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
				try{
					ICEHandler iceHandler = iceManager.getHandler(session);
					MediaHandler mediaHandler = mediaManager.getHandler(session);
					
					String payload = message.getPayload();
					WebSocketMsg payMap = mapper.readValue(payload, WebSocketMsg.class);
					
					WebSocketMsg answerMsg = new WebSocketMsg(Type.answer);
					
					String sdpString = payMap.getSdp();
					if (sdpString!=null){
						
						try{
							SessionDescription offerSdp = SdpFactory.getInstance().createSessionDescription(sdpString);
							
							MediaDescription md = (MediaDescription) offerSdp.getMediaDescriptions(false).get(0);
							
							
							iceHandler.initStream(MediaType.VIDEO,true);
							
							String remoteUfrag = md.getAttribute("ice-ufrag");
							String remotePasswd = md.getAttribute("ice-pwd");
							
							iceHandler.setupFragPasswd(remoteUfrag, remotePasswd);		
									
							List<CandidateMsg> remoteCandidates =payMap.getCandidates();
							
							if (remoteCandidates !=null && remoteCandidates.size()>0){
								iceHandler.processRemoteCandidates(remoteCandidates);	
							}
							
							
							String answerDescription = IOUtils.toString(new FileInputStream("src/main/resources/mozsdp_videoonly.answer"));
							SessionDescription answerSdp = SdpFactory.getInstance().createSessionDescription(answerDescription);
							
							iceHandler.prepareAnswer(offerSdp, answerSdp);
							mediaHandler.prepareAnswer(offerSdp, answerSdp);
							
							answerMsg.setSdp(answerSdp.toString());
							session.sendMessage(new TextMessage(mapper.writeValueAsBytes(answerMsg)));
							
							mediaHandler.openStream(MediaType.VIDEO);
							
							
							
							
							
						}catch (SdpParseException e){
							System.err.println(e.getMessage());
							System.err.println(sdpString);
						}
						
						
						
					}
				}catch (Exception e){
					e.printStackTrace();
					throw e;
				}
				
				
				
			}
			
			
			
		};
	}
	@Autowired
	private WebSocketHandler rtcHandler;
	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(rtcHandler,"/rtc").withSockJS();
	}
	
	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(RtcApplication.class);
		app.setHeadless(false);
		
		app.run(args);
		
	}
}

package org.igor.javartc;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
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
import org.opentelecoms.javax.sdp.NistSdpFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import javax.annotation.PreDestroy;
import javax.sdp.MediaDescription;
import javax.sdp.SdpParseException;
import javax.sdp.SessionDescription;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@SpringBootApplication
@EnableWebSocket
public class RtcApplication implements WebSocketConfigurer{

	@Value("${libJitsi.platform}")
	//TODO: Find from jvm
	private String platform;

	@Value("${libJitsi.nativeLibFolder}")
	private String libJitsiNativeFolder;



	@Bean
	public MediaService mediaService(){

			/* Doe not seem to work in windows,
			 LibJitsi falls back to looking the libs inside libjits/target/classes/native

			File libJitsiNativeDir = new File(libJitsiNativeFolder+ File.separatorChar+platform);
			if (libJitsiNativeDir.exists() && libJitsiNativeDir.isDirectory()){
				String libPath = System.getProperty("java.library.path");
				if (libPath == null){
					libPath = "";
				}else{
					libPath=libPath+";";
				}
				libPath = libPath+libJitsiNativeDir.getAbsolutePath();
				System.setProperty("java.library.path", libPath);
			*/

			LibJitsi.start();


			ConfigurationService cfg = LibJitsi.getConfigurationService();
			cfg.setProperty(DtlsControlImpl.PROP_SIGNATURE_ALGORITHM, "SHA256withRSA");

			MediaService ms = LibJitsi.getMediaService();
			return ms;
			/*
			}else{
				throw new IllegalStateException(libJitsiNativeDir.getAbsolutePath()+ "does not lead to a directory. Pleas specify via system property libJitsiNativePath");
			}
			*/

	}

	@Bean
	public MediaManager mediaManager(){
		return new JitsiMediaManager(iceManager(),mediaService());
	}
	
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
		LibJitsi.stop();
	}
	

	
	
	
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
							SessionDescription offerSdp = new NistSdpFactory().createSessionDescription(sdpString);
							
							MediaDescription md = (MediaDescription) offerSdp.getMediaDescriptions(false).get(0);
							
							
							iceHandler.initStream(MediaType.VIDEO,true);
							
							String remoteUfrag = md.getAttribute("ice-ufrag");
							String remotePasswd = md.getAttribute("ice-pwd");
							
							iceHandler.setupFragPasswd(remoteUfrag, remotePasswd);		
									
							List<CandidateMsg> remoteCandidates =payMap.getCandidates();
							
							if (remoteCandidates !=null && remoteCandidates.size()>0){
								iceHandler.processRemoteCandidates(remoteCandidates);	
							}
							
							
							String answerDescription = IOUtils.toString(getClass().getResourceAsStream("/mozsdp_videoonly.answer"));
							SessionDescription answerSdp = new NistSdpFactory().createSessionDescription(answerDescription);
							
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
		registry.addHandler(rtcHandler,"/rtc");
	}
	
	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(RtcApplication.class);
		app.setHeadless(false);
		
		app.run(args);
		
	}
}

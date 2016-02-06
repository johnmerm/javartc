package org.igor.javartc;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.annotation.PreDestroy;
import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sdp.SessionDescription;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidatePairState;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.RemoteCandidate;
import org.igor.javartc.msg.CandidateMsg;
import org.igor.javartc.msg.WebSocketMsg;
import org.jitsi.service.neomedia.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.composable.Deferred;
import reactor.core.composable.Promise;
import reactor.core.composable.spec.Promises;
import reactor.event.Event;

public class ICEManager {

	@Autowired
	private ObjectMapper mapper;
	
	private Agent agent;
	
	public ICEManager(Agent agent) {
		this.agent = agent;
		
		agent.addStateChangeListener(new PropertyChangeListener() {
			
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				System.err.println("Agent:"+evt.getOldValue()+"->"+evt.getNewValue());
			}
		});
		
		
	}
	
	private Map<String,ICEHandler> handlers = new HashMap<>();
	
	public ICEHandler getHandler(WebSocketSession session){
		if (handlers.containsKey(session.getId())){
			return handlers.get(session.getId());
		}else{
			ICEHandler handler = new  ICEHandler(session);
			handlers.put(session.getId(), handler);
			return handler;
		}
		
	}
	
	
	@PreDestroy
	public void cleanup(){
		agent.free();
	}
	public class ICEHandler{
		
		
		private Deferred<IceProcessingState, Promise<IceProcessingState>> agentStatePromise = Promises.<IceProcessingState>defer().get();
		public Promise<IceProcessingState> getAgentStatePromise(){
			return agentStatePromise.compose();
		}
		
		private Map<Component,Deferred<CandidatePair,Promise<CandidatePair>>> promises = new HashMap<>();
		
		class PairChangeListener implements PropertyChangeListener{
			@Override
			public synchronized void propertyChange(PropertyChangeEvent evt) {
				CandidatePair source = (CandidatePair)evt.getSource();
				Component cmp = source.getParentComponent();
				if (source.getState() == CandidatePairState.SUCCEEDED && evt.getNewValue() instanceof Boolean && (Boolean)evt.getNewValue()){
					Deferred<CandidatePair,Promise<CandidatePair>> deferred = promises.get(cmp);
					if(deferred.compose().isPending()){
							deferred.accept(source);
					}
				}
				
				
			}
		}
		private WebSocketSession session;
	
		private ICEHandler(WebSocketSession session){
			this.session = session;
			
			agent.addStateChangeListener(new PropertyChangeListener() {
				@Override
				public synchronized void propertyChange(PropertyChangeEvent evt) {
					IceProcessingState oldState = (IceProcessingState) evt.getOldValue();
					IceProcessingState newState = (IceProcessingState) evt.getNewValue();
					if (newState == IceProcessingState.TERMINATED && agentStatePromise.compose().isPending()){
						agentStatePromise.accept(newState);
					}
					
						
						
				}
			});
		}
		
		public Promise<CandidatePair> getPairPromise(Component cmp){
			if (promises.containsKey(cmp)){
				return promises.get(cmp).compose();
			}else{
				return null;
			}
		}
	
		private Map<MediaType,IceMediaStream> iceMediaStreamMap = new LinkedHashMap<>();
		private List<IceMediaStream> iceMediaStreams = new ArrayList<>();
		
		
		public IceMediaStream getICEMediaStream(MediaType mediaType) {
			return iceMediaStreamMap.get(mediaType);
		}
		
		public void initStream(MediaType mediaType,boolean rtcpmux){
			IceMediaStream mediaStream = agent.createMediaStream(mediaType.name()+session.getId());
			mediaStream.addPairChangeListener(new PairChangeListener());
			
			iceMediaStreams.add(mediaStream);
			iceMediaStreamMap.put(mediaType,mediaStream);
			
			//For each Stream create two components (RTP & RTCP)
			try {
				Component rtp = agent.createComponent(mediaStream, Transport.UDP, 10000, 10000, 11000);
				promises.put(rtp, Promises.<CandidatePair>defer().get());
				if (!rtcpmux){
					Component rtcp = agent.createComponent(mediaStream, Transport.UDP, 10001, 10001, 11000);
					promises.put(rtcp, Promises.<CandidatePair>defer().get());
				}
				
			} catch (IllegalArgumentException |IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		public List<CandidateMsg> getLocalCandidates(){
	
			List<CandidateMsg> localCandidates = new ArrayList<>();
			
			for (int sdpMLineIndex=0;sdpMLineIndex<iceMediaStreams.size();sdpMLineIndex++){
				IceMediaStream stream = iceMediaStreams.get(sdpMLineIndex);
				for (Component cmp:stream.getComponents()){
					for (LocalCandidate lc:cmp.getLocalCandidates()){
						CandidateMsg lm = new CandidateMsg(stream.getName(), sdpMLineIndex, lc.toString());
						localCandidates.add(lm);
					}
				}
			}
			
			return localCandidates;
		}
	
		private String remoteUfrag,remotePassword;
		public void setupFragPasswd(String remoteUfrag,String remotePassword){
			this.remoteUfrag = remoteUfrag;
			this.remotePassword = remotePassword;
		}
		
		public void processRemoteCandidates(List<CandidateMsg> candidates){
			iceMediaStreams.forEach(stream->{
				stream.setRemoteUfrag(remoteUfrag);
				stream.setRemotePassword(remotePassword);
			});
			
			
			for (CandidateMsg candidateMsg:candidates){
				String candidate = candidateMsg.getCandidate();
				int sdpMLineIndex =candidateMsg.getSdpMLineIndex();
				
				processRemoteCandidate(sdpMLineIndex, candidate);
			}
			
			WebSocketMsg candidatesMsg = new WebSocketMsg(null).setCandidates(getLocalCandidates());
			try {
				session.sendMessage(new TextMessage(mapper.writeValueAsBytes(candidatesMsg)));
				agent.startConnectivityEstablishment();
			}catch (IOException e){
				throw new RuntimeException(e);
			}
		}
		
		private void  processRemoteCandidate(int sdpMLineIndex,String candidate){
			String[] tokens = candidate.split(":");
			if ("candidate".equalsIgnoreCase(tokens[0])){
				IceMediaStream stream = iceMediaStreams.get(sdpMLineIndex);
				
				
				tokens = tokens[1].split(" ");
				int i=0;
				String foundation = tokens[i++].trim();
				int cmpId = Integer.parseInt(tokens[i++].trim());
				Component parentComponent =stream.getComponent(cmpId);
				if (parentComponent!=null){	
					Transport transport = Transport.parse(tokens[i++].trim().toLowerCase());
					
					
					
					long priority = Long.parseLong(tokens[i++].trim());
					String hostaddress = tokens[i++].trim();
					
					int port = Integer.parseInt(tokens[i++].trim());
					TransportAddress transportAddress = new TransportAddress(hostaddress, port, transport);
					CandidateType type = null;
					if ("typ".equalsIgnoreCase(tokens[i].trim())){
						type = CandidateType.parse(tokens[++i].trim().toLowerCase());
					}
					
					if (tokens.length>i && "generation".equals(tokens[i])){
						int generation = Integer.parseInt(tokens[++i].trim());
						i++;
					}
					RemoteCandidate related = null;
					
				
					String rAddr = null;
					if (tokens.length>i && "raddr".equalsIgnoreCase(tokens[i])){
						rAddr = tokens[++i].trim();
						i++;
					}
					int rport = -1;
					if (tokens.length>i && "rport".equalsIgnoreCase(tokens[i])){
						rport = Integer.parseInt(tokens[++i].trim());
						i++;
					}
					if (rAddr!=null){
						TransportAddress rAddress = new TransportAddress(rAddr, rport, transport);
						related = new RemoteCandidate(rAddress, parentComponent, type, foundation, priority, null);
					}
					RemoteCandidate rc = new RemoteCandidate(transportAddress, parentComponent, type, foundation, priority, related);
					parentComponent.addRemoteCandidate(rc);
				}
			}else{
				throw new IllegalArgumentException("Does not start with candidate:");
			}
		}
	
		@SuppressWarnings("unchecked")
		public SessionDescription prepareAnswer(SessionDescription offerSdp,SessionDescription answerSdp){
			
			
				try {
					((Vector<MediaDescription>)answerSdp.getMediaDescriptions(false)).forEach(md->{
						try {
							if ("audio".equals(md.getMedia().getMediaType())){
								//md.setAttribute("mid", audiomediaStream.getName());
							}else if ("video".equals(md.getMedia().getMediaType())){
								//md.setAttribute("mid", videomediaStream.getName());
							}
							
							
							md.setAttribute("ice-ufrag", agent.getLocalUfrag());	
							md.setAttribute("ice-pwd", agent.getLocalPassword());
						} catch (SdpException e) {
							throw new RuntimeException(e);
						}
					});
				} catch (SdpException e) {
					throw new RuntimeException(e);
				}
				
			
			
			return answerSdp;
		}
		public void close() {
			iceMediaStreams.forEach(stream->{
				agent.removeStream(stream);
			});
			
			
		}
	}
	public void closeSession(WebSocketSession session) {
		ICEHandler handler = handlers.remove(session.getId());
		if (handler !=null){
			handler.close();
		}
		
	}
}

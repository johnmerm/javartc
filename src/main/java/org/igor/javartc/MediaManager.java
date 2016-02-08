package org.igor.javartc;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sdp.SdpParseException;
import javax.sdp.SessionDescription;

import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.igor.javartc.ICEManager.ICEHandler;
import org.jitsi.service.neomedia.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;



public abstract class MediaManager {
	private static final Logger LOG = LoggerFactory.getLogger(MediaManager.class);
	
	protected ICEManager iceManager;

	protected Map<String,MediaHandler> handlers = new HashMap<>();
	
	public MediaManager(ICEManager iceManager) {
		super();
		this.iceManager = iceManager;
	}
	
	protected abstract MediaHandler createMediaHandler(WebSocketSession session);
	public MediaHandler getHandler(WebSocketSession session){
		if (handlers.containsKey(session.getId())){
			return handlers.get(session.getId());
		}else{
			MediaHandler handler = createMediaHandler(session);
			handlers.put(session.getId(), handler);
			return handler;
		}
	}
	
	abstract class MediaHandler implements Closeable{
		protected boolean rtcpmux = false;
		protected ICEHandler iceHandler;
		
		MediaPlayer player;
		
		public MediaHandler(WebSocketSession session) {
			super();
			iceHandler = iceManager.getHandler(session);
		}
		
		protected abstract String getLocaFingerPrint();
		protected abstract void notifyRemoteFingerprint(String mediaType,String remoteFingerprint);
		
		@SuppressWarnings("unchecked")
		public SessionDescription prepareAnswer(SessionDescription offerSdp,SessionDescription answerSdp){
			try {
				
				answerSdp.setAttribute("fingerprint", getLocaFingerPrint());
				
				String globalFingerPrint = offerSdp.getAttribute("fingerprint");
				if (globalFingerPrint!=null){
					notifyRemoteFingerprint(null,globalFingerPrint);
					
				
				}else{
					Map<String,Map<String,String>> mids = new HashMap<>();
					((Vector<MediaDescription>)offerSdp.getMediaDescriptions(false)).forEach(md->{
						try {
							mids.put(md.getMedia().getMediaType(), new HashMap<String,String>());
							
							
							mids.get(md.getMedia().getMediaType()).put("mid",md.getAttribute("mid"));
							mids.get(md.getMedia().getMediaType()).put("msid",md.getAttribute("msid"));
							mids.get(md.getMedia().getMediaType()).put("ssrc",md.getAttribute("ssrc"));
							
							String fingerPrint = md.getAttribute("fingerprint");
							notifyRemoteFingerprint(md.getMedia().getMediaType(),fingerPrint);
							
							
						} catch (SdpParseException e) {
							throw new RuntimeException(e);
						}
					});
					
					((Vector<MediaDescription>)answerSdp.getMediaDescriptions(false)).forEach(md->{
						try {
							md.setAttribute("mid", mids.get(md.getMedia().getMediaType()).get("mid"));
							md.setAttribute("msid", mids.get(md.getMedia().getMediaType()).get("msid"));
							md.setAttribute("ssrc", mids.get(md.getMedia().getMediaType()).get("ssrc"));
							if ("audio".equalsIgnoreCase(md.getMedia().getMediaType())){
								md.setAttribute("fingerprint", getLocaFingerPrint());
							}else if ("video".equalsIgnoreCase(md.getMedia().getMediaType())){
								answerSdp.setAttribute("fingerprint", getLocaFingerPrint());
							}
						}catch (SdpException e) {
							throw new RuntimeException(e);
						}
					});
				}
				
			} catch (SdpException e) {
				throw new RuntimeException(e);
			}
			
			return answerSdp;
		}
		
		protected abstract void doOpenMediaStream(MediaType mediaType,CandidatePair rtpPair,CandidatePair rtcpPair,boolean rtcpmux) throws IOException;
		
		public void openStream(MediaType mediaType){
			try{
				
				IceProcessingState state = iceHandler.getAgentStatePromise().await();
				
				IceMediaStream iceMediaStream = iceHandler.getICEMediaStream(mediaType);
				
				
				Component rtp = iceMediaStream.getComponent(Component.RTP);
				
				
				CandidatePair rtpPair = rtp.getSelectedPair()!=null?rtp.getSelectedPair():iceHandler.getPairPromise(rtp).await(),rtcpPair = null;		
				
				
				if (!rtcpmux){
					Component rtcp = iceMediaStream.getComponent(Component.RTP);
					rtcpPair = rtcp.getSelectedPair()!=null?rtp.getSelectedPair():iceHandler.getPairPromise(rtcp).await();
					
				}
				doOpenMediaStream(mediaType, rtpPair, rtcpPair, rtcpmux);
				
				
				
				
			}catch(InterruptedException |IOException e){
				throw new RuntimeException(e);
			}
		}

		
		
	}

	public void closeSession(WebSocketSession session) {
		MediaHandler handler = handlers.remove(session.getId());
		if (handler!=null){
			try {
				handler.close();
			} catch (IOException e) {
				LOG.error("Failed to close handler",e);
			}
		}
		
	}
	
	
}

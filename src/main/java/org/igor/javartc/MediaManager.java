package org.igor.javartc;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.annotation.PostConstruct;
import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sdp.SdpParseException;
import javax.sdp.SessionDescription;

import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.igor.javartc.ICEManager.ICEHandler;
import org.jitsi.service.neomedia.DefaultStreamConnector;
import org.jitsi.service.neomedia.DtlsControl;
import org.jitsi.service.neomedia.MediaDirection;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.MediaType;
import org.jitsi.service.neomedia.MediaUseCase;
import org.jitsi.service.neomedia.SrtpControlType;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.service.neomedia.VideoMediaStream;
import org.jitsi.service.neomedia.device.MediaDevice;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.springframework.web.socket.WebSocketSession;

public class MediaManager {

	private MediaService mediaService;
	private ICEManager iceManager;

	private Map<String,MediaHandler> handlers = new HashMap<>();
	
	public MediaManager(MediaService mediaService,ICEManager iceManager) {
		super();
		this.mediaService = mediaService;
		this.iceManager = iceManager;
	}
	private MediaFormat videoFormat,audioFormat;
	
	
	Map<MediaFormat, Byte> dpp;
	
	@PostConstruct
	public void initMedia(){
		
		dpp = mediaService.getDynamicPayloadTypePreferences();

		videoFormat = mediaService.getFormatFactory().createMediaFormat("vp8",90000.0);
		if (videoFormat == null){
			videoFormat = mediaService.getFormatFactory().createUnknownMediaFormat(MediaType.VIDEO);
		}else{
			
		}
		
		audioFormat = mediaService.getFormatFactory().createMediaFormat("opus",48000,2);
		if (audioFormat == null){
			audioFormat = mediaService.getFormatFactory().createUnknownMediaFormat(MediaType.AUDIO);
		}
		
		
		
	}
	
	
	
	public MediaHandler getHandler(WebSocketSession session){
		if (handlers.containsKey(session.getId())){
			return handlers.get(session.getId());
		}else{
			MediaHandler handler = new MediaHandler(session);
			handlers.put(session.getId(), handler);
			return handler;
		}
	}
	
	public class MediaHandler{
		private boolean rtcpmux = false;
		private ICEHandler iceHandler;
		
		MediaPlayer player;
		
		public MediaHandler(WebSocketSession session) {
			super();
			iceHandler = iceManager.getHandler(session);
			initStream(MediaType.VIDEO,true);
		}
		private List<MediaStream> mediaStreams = new ArrayList<>();
		private Map<MediaType,MediaStream> mediaStreamMap=  new LinkedHashMap<>();
		private Map<MediaType,DtlsControl> dtlsControlMap=  new LinkedHashMap<>();
		
		private void initStream(MediaType mediaType,boolean rtcpmux){
			List<MediaDevice> audioDevices = mediaService.getDevices(MediaType.AUDIO, MediaUseCase.CALL);
			List<MediaDevice> videoDevices = mediaService.getDevices(MediaType.VIDEO, MediaUseCase.CALL);
			
			MediaDevice randomVideoDevice = videoDevices.get(0);
			MediaDevice randomAudioDevice = audioDevices.get(0);
			
			
			
			this.rtcpmux = rtcpmux;
			DtlsControl dtlsControl = (DtlsControl) mediaService.createSrtpControl(SrtpControlType.DTLS_SRTP);
			dtlsControl.setSetup(DtlsControl.Setup.ACTIVE);
			dtlsControlMap.put(mediaType, dtlsControl);
			
			
			MediaStream mediaStream = null;
			
			if (mediaType == MediaType.VIDEO){
				mediaStream = mediaService.createMediaStream(
				        null,
				        randomVideoDevice,
				        dtlsControl);
				mediaStream.setFormat(videoFormat);
				mediaStream.addDynamicRTPPayloadType((byte)100,videoFormat);
				mediaStream.addDynamicRTPPayloadType((byte)120,videoFormat);
				
			}else if (mediaType == MediaType.AUDIO){
				mediaStream = mediaService.createMediaStream(
				        null,
				        randomAudioDevice,
				        dtlsControl);
				mediaStream.setFormat(audioFormat);
				mediaStream.addDynamicRTPPayloadType((byte)111, audioFormat);
			}
			
			
			mediaStream.setName(mediaType.name().toLowerCase());
			mediaStream.setDirection(MediaDirection.SENDRECV);
			mediaStreams.add(mediaStream);
			mediaStreamMap.put(mediaType, mediaStream);
			
			
			mediaStream.addPropertyChangeListener(new PropertyChangeListener() {
				
				@Override
				public void propertyChange(PropertyChangeEvent evt) {
					System.err.println("mediaStream:"+evt);
					
				}
			});
		}
		
		public SessionDescription prepareAnswer(SessionDescription offerSdp,SessionDescription answerSdp){
			//Normally, you need to set the fingerprint and the hash function of the remote target
			//(and also send yours to the remote target).
			//For now, libjitsi doesn't absolutely need this, so you can skip it if you want
			//But that's way cleaner to do it.
			try {
				DtlsControl aControl = dtlsControlMap.values().iterator().next();
				answerSdp.setAttribute("fingerprint", aControl.getLocalFingerprintHashFunction()+" "+aControl.getLocalFingerprint());
				Map<String,String> videoMap = new HashMap<String,String>();
				Map<String,String> audioMap = new HashMap<String,String>();
				
				String globalFingerPrint = offerSdp.getAttribute("fingerprint");
				if (globalFingerPrint!=null){
					String fingerPrintHashFunction = globalFingerPrint.split(" ")[0];
					String fingerPringBytes = globalFingerPrint.split(" ")[1];
					videoMap.put(
							fingerPrintHashFunction,
							fingerPringBytes);
					audioMap.put(
							fingerPrintHashFunction,
							fingerPringBytes);
					
				
				}else{
					Map<String,Map<String,String>> mids = new HashMap<>();
					((Vector<MediaDescription>)offerSdp.getMediaDescriptions(false)).forEach(md->{
						try {
							mids.put(md.getMedia().getMediaType(), new HashMap<String,String>());
							
							
							mids.get(md.getMedia().getMediaType()).put("mid",md.getAttribute("mid"));
							mids.get(md.getMedia().getMediaType()).put("msid",md.getAttribute("msid"));
							mids.get(md.getMedia().getMediaType()).put("ssrc",md.getAttribute("ssrc"));
							
							String fingerPrint = md.getAttribute("fingerprint");
							String fingerPrintHashFunction = fingerPrint.split(" ")[0];
							String fingerPringBytes = fingerPrint.split(" ")[1];
							if ("audio".equalsIgnoreCase(md.getMedia().getMediaType())){
								audioMap.put(
										fingerPrintHashFunction,
										fingerPringBytes);
							}else if ("video".equalsIgnoreCase(md.getMedia().getMediaType())){
								videoMap.put(
										fingerPrintHashFunction,
										fingerPringBytes);
							}
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
								md.setAttribute("fingerprint", dtlsControlMap.get(MediaType.AUDIO).getLocalFingerprintHashFunction()+" "+dtlsControlMap.get(MediaType.AUDIO).getLocalFingerprint());
							}else if ("video".equalsIgnoreCase(md.getMedia().getMediaType())){
								answerSdp.setAttribute("fingerprint", dtlsControlMap.get(MediaType.VIDEO).getLocalFingerprintHashFunction()+" "+dtlsControlMap.get(MediaType.VIDEO).getLocalFingerprint());
							}
						}catch (SdpException e) {
							throw new RuntimeException(e);
						}
					});
				}
				
				
				
				
				//The DtlsControl need a Map of hash functions and their corresponding fingerprints
				//that have been presented by the remote endpoint via the signaling path
				if (dtlsControlMap.containsKey(MediaType.VIDEO)){
					dtlsControlMap.get(MediaType.VIDEO).setRemoteFingerprints(videoMap);
				}
				if (dtlsControlMap.containsKey(MediaType.AUDIO)){
					dtlsControlMap.get(MediaType.AUDIO).setRemoteFingerprints(audioMap);
				}

			} catch (SdpException e) {
				throw new RuntimeException(e);
			}
			
			return answerSdp;
		}
		
		public void openStream(MediaType mediaType){
			try{
				
				IceProcessingState state = iceHandler.getAgentStatePromise().await();
				
				IceMediaStream iceMediaStream = iceHandler.getICEMediaStream(mediaType);
				MediaStream mediaStream = mediaStreamMap.get(mediaType);
				
				Component rtp = iceMediaStream.getComponent(Component.RTP);
				
				
				CandidatePair rtpPair = rtp.getSelectedPair()!=null?rtp.getSelectedPair():iceHandler.getPairPromise(rtp).await();		
				DatagramSocket rtpSocket = rtpPair.getDatagramSocket();
				
				StreamConnector connector = null;
				if (rtcpmux){
					
					
					
					
					connector = new DefaultStreamConnector(rtpSocket, null,true);
					mediaStream.setConnector(connector);
					mediaStream.setTarget(
				               new MediaStreamTarget(
				                		rtpPair.getRemoteCandidate().getTransportAddress(),
				                		rtpPair.getRemoteCandidate().getTransportAddress()) );
					
				
				}else{
					CandidatePair rtcpPair = rtp.getSelectedPair()!=null?rtp.getSelectedPair():iceHandler.getPairPromise(rtp).await();
					DatagramSocket rtcpSocket = rtcpPair.getDatagramSocket();
					connector = new DefaultStreamConnector(rtpSocket, rtcpSocket);
					mediaStream.setConnector(connector);
					mediaStream.setTarget(
				                new MediaStreamTarget(
				                		rtpPair.getRemoteCandidate().getTransportAddress(),
				                		rtcpPair.getRemoteCandidate().getTransportAddress()) );
				}
				
				DtlsControl control = dtlsControlMap.get(mediaType);
				control.setRtcpmux(rtcpmux);
				control.start(mediaType);
				
				
				System.err.println("Starting stream");
				
				player = new MediaPlayer((VideoMediaStream)mediaStream);
				mediaStream.start();
				
				
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
		}

		public void close() {
			
			mediaStreamMap.values().forEach(s->{
				s.close();
			});
			if (player !=null){
				player.close();
			}
			
			
		}
		
	}

	public void closeSession(WebSocketSession session) {
		MediaHandler handler = handlers.remove(session.getId());
		if (handler!=null){
			handler.close();
		}
		
	}
	
	
}

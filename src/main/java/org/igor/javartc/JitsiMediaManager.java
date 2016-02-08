package org.igor.javartc;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.PostConstruct;

import org.ice4j.ice.CandidatePair;
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

public class JitsiMediaManager extends MediaManager{
	protected MediaService mediaService;
	private MediaFormat videoFormat,audioFormat;
	
	private MediaDevice randomVideoDevice,randomAudioDevice;
	
	public JitsiMediaManager(ICEManager iceManager,MediaService mediaService) {
		super(iceManager);
		this.mediaService = mediaService;
	}
	
	@PostConstruct
	public void initMedia(){
		videoFormat = mediaService.getFormatFactory().createMediaFormat("vp8",90000.0);
		if (videoFormat == null){
			videoFormat = mediaService.getFormatFactory().createUnknownMediaFormat(MediaType.VIDEO);
		}else{
			
		}
		
		audioFormat = mediaService.getFormatFactory().createMediaFormat("opus",48000,2);
		if (audioFormat == null){
			audioFormat = mediaService.getFormatFactory().createUnknownMediaFormat(MediaType.AUDIO);
		}
		
		List<MediaDevice> audioDevices = mediaService.getDevices(MediaType.AUDIO, MediaUseCase.CALL);
		List<MediaDevice> videoDevices = mediaService.getDevices(MediaType.VIDEO, MediaUseCase.CALL);
		
		randomVideoDevice = videoDevices.get(0);
		randomAudioDevice = audioDevices.get(0);
		
		System.out.println("Selected AudioDevice:"+randomAudioDevice);
		System.out.println("Selected VideoDevice:"+randomVideoDevice);
		
		
	}

	@Override
	protected MediaHandler createMediaHandler(WebSocketSession session) {
		return new MediaHandler(session);
	}
	
	class MediaHandler extends MediaManager.MediaHandler{
		public MediaHandler(WebSocketSession session) {
			super(session);
			initStream(MediaType.VIDEO,true);
		}
		
		private List<MediaStream> mediaStreams = new ArrayList<>();
		private Map<MediaType,MediaStream> mediaStreamMap=  new LinkedHashMap<>();
		private Map<MediaType,DtlsControl> dtlsControlMap=  new LinkedHashMap<>();
		
		private DtlsControl dtlsControl;
		private void initStream(MediaType mediaType,boolean rtcpmux){
			this.rtcpmux = rtcpmux;
			dtlsControl = (DtlsControl) mediaService.createSrtpControl(SrtpControlType.DTLS_SRTP);
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
		
		protected void doOpenMediaStream(MediaType mediaType,CandidatePair rtpPair,CandidatePair rtcpPair,boolean rtcpmux){
			MediaStream mediaStream = mediaStreamMap.get(mediaType);
			StreamConnector connector = null;
			
			DatagramSocket rtpSocket = Optional.ofNullable(rtpPair.getDatagramSocket()).orElse(null);
			DatagramSocket rtcpSocket = null;
			
			if (rtcpmux){
				
				connector = new DefaultStreamConnector(rtpSocket, null,true);
				mediaStream.setConnector(connector);
				mediaStream.setTarget(
			               new MediaStreamTarget(
			                		rtpPair.getRemoteCandidate().getTransportAddress(),
			               			rtpPair.getRemoteCandidate().getTransportAddress()
			               	)
			    );
				
			
			}else if (rtcpPair!=null){
				rtcpSocket = Optional.ofNullable(rtcpPair.getDatagramSocket()).orElse(null);
				
				connector = new DefaultStreamConnector(rtpSocket, rtcpSocket);
				mediaStream.setConnector(connector);
				mediaStream.setTarget(
			               new MediaStreamTarget(
			                		rtpPair.getRemoteCandidate().getTransportAddress(),
			               			rtcpPair.getRemoteCandidate().getTransportAddress()
			               	)
			    );
			}
			
			DtlsControl control = dtlsControlMap.get(mediaType);
			control.setRtcpmux(rtcpmux);
			control.start(mediaType);
			
			
			System.err.println("Starting stream");
			
			player = new MediaPlayer((VideoMediaStream)mediaStream);
			mediaStream.start();
		}

		@Override
		protected String getLocaFingerPrint() {
			return dtlsControl.getLocalFingerprintHashFunction()+" "+dtlsControl.getLocalFingerprint();
		}
		
		protected void notifyRemoteFingerprint(String mediaType,String fingerPrint){
			String fingerPrintHashFunction = fingerPrint.split(" ")[0];
			String fingerPringBytes = fingerPrint.split(" ")[1];
			
			Map<String,String> videoMap = new HashMap<String,String>();
			Map<String,String> audioMap = new HashMap<String,String>();
			
			videoMap.put(
					fingerPrintHashFunction,
					fingerPringBytes);
			audioMap.put(
					fingerPrintHashFunction,
					fingerPringBytes);
			
			if (dtlsControlMap.containsKey(MediaType.VIDEO)){
				dtlsControlMap.get(MediaType.VIDEO).setRemoteFingerprints(videoMap);
			}
			if (dtlsControlMap.containsKey(MediaType.AUDIO)){
				dtlsControlMap.get(MediaType.AUDIO).setRemoteFingerprints(audioMap);
			}
			
		}
		@Override
		public void close() {
			
			mediaStreamMap.values().forEach(s->{
				s.close();
			});
			if (player !=null){
				player.close();
			}
			
			
		}
		
	}
}

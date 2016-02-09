package org.igor.javartc;

import java.io.IOException;

import org.ice4j.ice.CandidatePair;
import org.igor.javartc.dtls.mock.Connect;
import org.jitsi.service.neomedia.MediaType;
import org.springframework.web.socket.WebSocketSession;

public class DtlsMediaManager extends MediaManager{
	
	
	
	public DtlsMediaManager(ICEManager iceManager) {
		super(iceManager);
	}

	@Override
	protected MediaHandler createMediaHandler(WebSocketSession session) {
		return new MediaHandler(session);
	}
	
	class MediaHandler extends MediaManager.MediaHandler{
		
		//private DtlsClient client = new DtlsClient(null);
		private Connect connect = new Connect();
		public MediaHandler(WebSocketSession session) {
			super(session);
		}

		@Override
		public void close() throws IOException {
			
			
		}

		@Override
		protected String getLocaFingerPrint() {
			//return client.getLocalFingerPrint();
			return connect.getLocalFingerPrint();
		}

		@Override
		protected void notifyRemoteFingerprint(String mediaType, String remoteFingerprint) {
			//client.setRemoteFingerPrint(remoteFingerprint);
			
		}

		@Override
		protected void doOpenMediaStream(MediaType mediaType, CandidatePair rtpPair, CandidatePair rtcpPair,
				boolean rtcpmux) throws IOException {
			if (rtpPair !=null){
				//client.connect(rtpPair.getDatagramSocket());
				connect.connect(rtpPair.getDatagramSocket());
			}
			
		}
		
	}

}

package org.igor.javartc.msg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CandidateMsg {

	private String sdpMid;
	private int sdpMLineIndex;
	private String candidate;
	
	@JsonCreator
	public CandidateMsg(
			@JsonProperty("sdpMid") String sdpMid,
			@JsonProperty("sdpMLineIndex") Integer sdpMLineIndex,
			@JsonProperty("candidate") String candidate) {
		super();
		this.sdpMid = sdpMid;
		this.sdpMLineIndex = sdpMLineIndex;
		this.candidate = candidate;
	}
	
	public String getSdpMid() {
		return sdpMid;
	}
	public int getSdpMLineIndex() {
		return sdpMLineIndex;
	}
	public String getCandidate() {
		return candidate;
	}
	
	
 
}

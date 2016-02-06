package org.igor.javartc.msg;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class WebSocketMsg {
	public enum Type{offer,answer}
	private Type type;
	
	@JsonCreator
	public WebSocketMsg(@JsonProperty("type")Type type) {
		super();
		this.type = type;
	}
	private List<CandidateMsg> candidates;
	private String sdp;
	
	
	public Type getType() {
		return type;
	}
	public List<CandidateMsg> getCandidates() {
		return candidates;
	}
	public WebSocketMsg setCandidates(List<CandidateMsg> candidates) {
		this.candidates = candidates;
		return this;
	}
	
	public String getSdp() {
		return sdp;
	}
	public WebSocketMsg setSdp(String sdp) {
		this.sdp = sdp;
		return this;
	}
	
	

}

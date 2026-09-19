package com.speedrunmcalt.net;

public final class VerifyResult {
	public final String uuid;
	public final String username;
	public final String sessionToken;

	public VerifyResult(String uuid, String username, String sessionToken) {
		this.uuid = uuid;
		this.username = username;
		this.sessionToken = sessionToken;
	}
}

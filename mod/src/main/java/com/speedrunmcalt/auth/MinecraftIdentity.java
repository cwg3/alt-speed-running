package com.speedrunmcalt.auth;

public final class MinecraftIdentity {
	private final String uuid;
	private final String username;
	private final String accessToken;

	public MinecraftIdentity(String uuid, String username, String accessToken) {
		this.uuid = uuid;
		this.username = username;
		this.accessToken = accessToken;
	}

	public String getUuid() {
		return uuid;
	}

	public String getUsername() {
		return username;
	}

	public String getAccessToken() {
		return accessToken;
	}
}

package com.speedrunmcalt.auth;

public final class MinecraftProfile {
	private final String uuid;
	private final String name;
	private final String minecraftAccessToken;

	public MinecraftProfile(String uuid, String name, String minecraftAccessToken) {
		this.uuid = uuid;
		this.name = name;
		this.minecraftAccessToken = minecraftAccessToken;
	}

	public String getUuid() {
		return uuid;
	}

	public String getName() {
		return name;
	}

	// Needed later so the client can prove ownership to our backend via
	// Mojang's session-join/hasJoined flow, without our servers ever
	// touching the player's Microsoft/Xbox tokens.
	public String getMinecraftAccessToken() {
		return minecraftAccessToken;
	}
}

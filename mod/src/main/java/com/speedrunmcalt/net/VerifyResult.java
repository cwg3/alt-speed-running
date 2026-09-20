package com.speedrunmcalt.net;

public final class VerifyResult {
	public final String uuid;
	public final String username;
	public final String sessionToken;
	public final int skillRating;
	public final int seasonPoints;

	public VerifyResult(String uuid, String username, String sessionToken,
			int skillRating, int seasonPoints) {
		this.uuid = uuid;
		this.username = username;
		this.sessionToken = sessionToken;
		this.skillRating = skillRating;
		this.seasonPoints = seasonPoints;
	}
}

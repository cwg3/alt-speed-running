package com.speedrunmcalt.net;

/**
 * One finished match, as the history screen shows it.
 *
 * Everything needed to render a row is here, because the endpoint
 * returns it denormalised - no second lookup per row to find out who
 * the opponent was or what the rating change came to.
 */
public final class MatchHistoryEntry {
	public final String matchId;
	public final long completedAt;
	public final String opponentName;
	public final boolean won;
	public final int ratingDelta;
	public final int seasonPointsAwarded;
	public final String seedType;

	/**
	 * Which build's rules made this world.
	 *
	 * 0 means the match predates the stamp - "unknown", not "version
	 * zero". A replay has to refuse those rather than rebuild the
	 * world under today's rules and present it as what was played.
	 */
	public final int worldSetupVersion;

	public MatchHistoryEntry(String matchId, long completedAt, String opponentName,
			boolean won, int ratingDelta, int seasonPointsAwarded,
			String seedType, int worldSetupVersion) {
		this.matchId = matchId;
		this.completedAt = completedAt;
		this.opponentName = opponentName;
		this.won = won;
		this.ratingDelta = ratingDelta;
		this.seasonPointsAwarded = seasonPointsAwarded;
		this.seedType = seedType;
		this.worldSetupVersion = worldSetupVersion;
	}
}

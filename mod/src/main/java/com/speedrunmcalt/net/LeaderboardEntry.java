package com.speedrunmcalt.net;

/** One row of the ladder leaderboard. */
public class LeaderboardEntry {
	public final int rank;
	public final String uuid;
	public final String username;
	public final int skillRating;
	public final int seasonPoints;
	public final int wins;
	public final int losses;
	/**
	 * Counted apart from losses. The rating change is identical, but
	 * giving up on a bad seed and being beaten to the dragon are not the
	 * same result, and nothing else in this mod conflates them.
	 */
	public final int forfeits;
	public final int matches;

	public LeaderboardEntry(int rank, String uuid, String username, int skillRating,
			int seasonPoints, int wins, int losses, int forfeits, int matches) {
		this.rank = rank;
		this.uuid = uuid;
		this.username = username;
		this.skillRating = skillRating;
		this.seasonPoints = seasonPoints;
		this.wins = wins;
		this.losses = losses;
		this.forfeits = forfeits;
		this.matches = matches;
	}
}

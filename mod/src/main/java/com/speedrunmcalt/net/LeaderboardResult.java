package com.speedrunmcalt.net;

import java.util.List;

/**
 * The leaderboard, plus what was left off it.
 *
 * The counts are not decoration. A board that silently omits rows is
 * indistinguishable from a broken one: a player who has raced PaceBot
 * eighteen times and cannot find it has to guess whether bots are
 * excluded on purpose or the page failed. Saying so costs a line.
 */
public class LeaderboardResult {
	public final List<LeaderboardEntry> rows;
	/** Players with at least one finished match. */
	public final int totalRanked;
	/** Known players who have not finished a match yet. */
	public final int unranked;
	/** Bots, which race but do not rank. */
	public final int botsHidden;

	public LeaderboardResult(List<LeaderboardEntry> rows, int totalRanked,
			int unranked, int botsHidden) {
		this.rows = rows;
		this.totalRanked = totalRanked;
		this.unranked = unranked;
		this.botsHidden = botsHidden;
	}
}

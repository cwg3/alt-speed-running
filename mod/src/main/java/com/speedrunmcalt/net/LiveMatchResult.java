package com.speedrunmcalt.net;

import java.util.Map;

public final class LiveMatchResult {
	public final String status;
	public final String winnerUuid;
	public final String opponentUsername;
	/** Split name -> elapsed milliseconds, for the opponent. */
	public final Map<String, Long> opponentSplits;
	/** This caller's own rating change, or null while still in progress. */
	public final Integer ratingDelta;
	public final Integer seasonPoints;
	/** This player has already voted the seed unplayable. */
	public final boolean badSeedYours;
	/** The opponent has voted, and is waiting on this player to agree. */
	public final boolean badSeedOpponent;
	/** What the opponent said was wrong with it, possibly empty. */
	public final String badSeedReason;
	/**
	 * When THIS player's run began, in server-clock millis, or null if
	 * it has not been claimed yet.
	 *
	 * Only its presence is used, not its value: it is how the client
	 * knows it is rejoining a run already underway, and so must not
	 * show the pre-race countdown again.
	 */
	public final Long yourRunStartedAt;

	public LiveMatchResult(String status, String winnerUuid, String opponentUsername,
			Map<String, Long> opponentSplits, Integer ratingDelta, Integer seasonPoints,
			boolean badSeedYours, boolean badSeedOpponent, String badSeedReason,
			Long yourRunStartedAt) {
		this.status = status;
		this.winnerUuid = winnerUuid;
		this.opponentUsername = opponentUsername;
		this.opponentSplits = opponentSplits;
		this.ratingDelta = ratingDelta;
		this.seasonPoints = seasonPoints;
		this.badSeedYours = badSeedYours;
		this.badSeedOpponent = badSeedOpponent;
		this.badSeedReason = badSeedReason;
		this.yourRunStartedAt = yourRunStartedAt;
	}

	/** This player's run is already underway on the server. */
	public boolean yourRunHasStarted() {
		return yourRunStartedAt != null;
	}

	public boolean isComplete() {
		return "completed".equals(status) || isVoided();
	}

	/**
	 * Both players agreed the seed was unplayable. The match did not
	 * happen: no winner, and no rating moved for either side.
	 */
	public boolean isVoided() {
		return "voided".equals(status);
	}
}

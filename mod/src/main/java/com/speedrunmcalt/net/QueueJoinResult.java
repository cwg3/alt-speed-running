package com.speedrunmcalt.net;

public final class QueueJoinResult {
	public final boolean matched;
	public final String matchId;
	public final String opponentUsername;
	public final long overworldSeed;
	public final long netherSeed;

	private QueueJoinResult(boolean matched, String matchId, String opponentUsername,
			long overworldSeed, long netherSeed) {
		this.matched = matched;
		this.matchId = matchId;
		this.opponentUsername = opponentUsername;
		this.overworldSeed = overworldSeed;
		this.netherSeed = netherSeed;
	}

	public static QueueJoinResult waiting() {
		return new QueueJoinResult(false, null, null, 0, 0);
	}

	public static QueueJoinResult matched(String matchId, String opponentUsername,
			long overworldSeed, long netherSeed) {
		return new QueueJoinResult(true, matchId, opponentUsername, overworldSeed, netherSeed);
	}
}

package com.speedrunmcalt.net;

import java.util.Map;

public final class LiveMatchResult {
	public final String status;
	public final String winnerUuid;
	public final String opponentUsername;
	/** Split name -> elapsed milliseconds, for the opponent. */
	public final Map<String, Long> opponentSplits;

	public LiveMatchResult(String status, String winnerUuid, String opponentUsername,
			Map<String, Long> opponentSplits) {
		this.status = status;
		this.winnerUuid = winnerUuid;
		this.opponentUsername = opponentUsername;
		this.opponentSplits = opponentSplits;
	}

	public boolean isComplete() {
		return "completed".equals(status);
	}
}

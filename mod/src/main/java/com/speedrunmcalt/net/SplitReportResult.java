package com.speedrunmcalt.net;

public final class SplitReportResult {
	public final boolean completed;
	public final boolean alreadyCompleted;
	public final int winnerRatingDelta;
	public final int winnerSeasonPoints;

	public SplitReportResult(boolean completed, boolean alreadyCompleted,
			int winnerRatingDelta, int winnerSeasonPoints) {
		this.completed = completed;
		this.alreadyCompleted = alreadyCompleted;
		this.winnerRatingDelta = winnerRatingDelta;
		this.winnerSeasonPoints = winnerSeasonPoints;
	}
}

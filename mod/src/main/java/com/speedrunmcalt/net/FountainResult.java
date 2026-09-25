package com.speedrunmcalt.net;

/**
 * What the server said about a fountain claim.
 *
 * A close finish is held provisionally so the lowest RUN TIME wins
 * rather than the fastest connection, so "did I win" is not always
 * answerable on the first request.
 */
public final class FountainResult {
	public final boolean provisional;
	public final long retryInMs;
	public final boolean won;

	public FountainResult(boolean provisional, long retryInMs, boolean won) {
		this.provisional = provisional;
		this.retryInMs = retryInMs;
		this.won = won;
	}
}

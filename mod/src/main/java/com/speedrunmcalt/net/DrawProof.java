package com.speedrunmcalt.net;

/**
 * The server's commitment to the world it just dealt this match.
 *
 * The seed pool is built in advance, so whoever runs the ladder knows
 * every world in it before any of them is dealt - and they play here
 * too. The backend answers that by deriving every draw from a season
 * key whose hash was published before the season's first match, and by
 * committing to each match's seed as the match is made:
 *
 *   SHA256("alt-draw-v1" | season | matchId | nonce | pair | seeds)
 *
 * The nonce is withheld until the match is over, so the commitment says
 * nothing while the race is running and cannot be moved afterwards. See
 * SEASONS.md.
 *
 * NONE OF WHICH IS WORTH ANYTHING UNLESS SOMEBODY KEPT A COPY. A hash
 * only the server holds is a hash the server can rewrite. That is what
 * this class is for on the client side: the full value goes into the
 * player's own log at match start, and the short form goes on the
 * pre-race screen, where it is in the frame of every stream and every
 * screenshot. A player who never thinks about it once still ends up
 * holding independent, timestamped evidence.
 *
 * Null when the backend did not send one - an older deploy, or a season
 * that was never opened. The client shows nothing rather than claiming
 * a guarantee it does not have.
 */
public final class DrawProof {
	public final String seasonId;
	public final String commitment;

	public DrawProof(String seasonId, String commitment) {
		this.seasonId = seasonId;
		this.commitment = commitment;
	}

	/**
	 * Enough of the hash to compare by eye, and no more.
	 *
	 * Eight hex characters is what fits on the pre-race screen without
	 * crowding the countdown, and it is what a person can actually check
	 * against the published reveal afterwards. The full value is in the
	 * log for anyone who wants to verify properly - this is the part
	 * that has to survive being read off a stream.
	 */
	public String shortForm() {
		if (commitment == null) {
			return null;
		}
		return commitment.length() <= 8 ? commitment : commitment.substring(0, 8);
	}

	/** Null-safe: a proof with no hash in it is not a proof. */
	public static boolean isPresent(DrawProof p) {
		return p != null && p.commitment != null && !p.commitment.isEmpty();
	}
}

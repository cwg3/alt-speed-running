package com.speedrunmcalt.match;

/** Tiny shared state: when the current match's timer started, so split
 * mixins elsewhere can compute elapsed time without needing a reference
 * back to whatever created the world. */
public final class MatchState {
	private MatchState() {
	}

	// -1 means no match is currently active.
	public static volatile long matchStartMillis = -1;

	// Piglin bartering has no advancement to track "already granted" for
	// us (unlike the other splits), so we need our own once-per-match
	// flag. Reset alongside matchStartMillis when a new match starts -
	// a plain static boolean elsewhere would incorrectly carry over
	// between separate matches played in the same client session.
	public static volatile boolean firstBarterLogged = false;
}

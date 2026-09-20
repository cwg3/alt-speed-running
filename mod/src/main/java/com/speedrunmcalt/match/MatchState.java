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

	// Piglin barter fairness window (see PiglinBarterMixin): our own
	// documented policy, not an attempt to reverse-engineer any other
	// platform's undisclosed exact numbers. Reset alongside the fields
	// above when a new match starts.
	public static volatile int barterCount = 0;
	public static volatile int obsidianThisWindow = 0;
	public static volatile int pearlsThisWindow = 0;
}

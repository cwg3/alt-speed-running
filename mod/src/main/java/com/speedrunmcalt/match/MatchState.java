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

	// Identify the active match to the backend when reporting splits.
	// Null when no ranked match is in progress, which is what
	// SplitReporter checks before attempting any network call.
	public static volatile String matchId = null;
	public static volatile String sessionToken = null;

	// Split name -> elapsed ms, for the in-game HUD. Concurrent because
	// the reporter thread and the opponent poll thread both write while
	// the HUD reads them every frame. LinkedHashMap ordering wouldn't
	// survive that, so the HUD sorts by time instead.
	public static final java.util.Map<String, Long> mySplits =
			new java.util.concurrent.ConcurrentHashMap<>();
	public static final java.util.Map<String, Long> opponentSplits =
			new java.util.concurrent.ConcurrentHashMap<>();
	public static volatile String opponentUsername = null;

	public static void reset() {
		matchStartMillis = -1;
		firstBarterLogged = false;
		barterCount = 0;
		obsidianThisWindow = 0;
		pearlsThisWindow = 0;
		matchId = null;
		sessionToken = null;
		mySplits.clear();
		opponentSplits.clear();
		opponentUsername = null;
	}

	public static boolean inMatch() {
		return matchStartMillis > 0 && matchId != null;
	}

	public static String formatTime(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}
}

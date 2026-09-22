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

	// What the world needs guaranteed, and where. Set by the matchmaker
	// from the queue response, consumed once by MatchWorldSetup when the
	// integrated server starts. Null seedType means no setup is pending,
	// which is what stops a practice world being modified.
	public static volatile String seedType = null;
	public static volatile int structureX = 0;
	public static volatile int structureZ = 0;
	public static volatile long overworldSeed = 0;
	public static volatile long netherSeed = 0;

	// The bastion, so its chests can be topped up as the player nears
	// it. Doing it at world creation cost 9 seconds of loading; doing
	// it on portal entry would be the same 9 seconds mid-run. Waiting
	// until the chunks are loaded naturally costs nothing.
	// Set when a ruined portal had to be built because the seed's own
	// one was missing or unusable. Null means vanilla's portal is being
	// used, which is the majority case.
	public static volatile net.minecraft.util.math.BlockPos placedPortal = null;

	/**
	 * The box around this seed's structure that is kept free of ambient
	 * mobs and bats. Null in practice worlds, so spawning is untouched
	 * there.
	 *
	 * Not for safety - for SOUND. Runners locate buried structures by
	 * "pie-ray", reading the F3 pie chart for the entity and audio load
	 * a nearby structure produces. Mobs rattling around inside one
	 * pollute that reading, so a technique that should be precise turns
	 * into guesswork - and whether it is noisy is luck of the seed.
	 *
	 * Built from the containers the loot pass actually found, NEVER
	 * from the predicted structure box: prediction is exact in X and Z
	 * and has been observed wrong in Y by fifty blocks. Using the
	 * prediction gave a box hovering above the structure, which matched
	 * nothing and silently cancelled nothing.
	 */
	public static volatile net.minecraft.util.math.BlockBox quietBox = null;

	/**
	 * The blacksmith's position on a village seed, or 0,0 if unknown.
	 *
	 * structureX/Z is the village's jigsaw ANCHOR, which is not the
	 * building the player is going to. A village box can be 107 by 174
	 * blocks; on one seed the smith was 54 blocks from the anchor and
	 * the player, standing where they were sent, reported that the
	 * village had no blacksmith at all.
	 */
	public static volatile int smithX = 0;
	public static volatile int smithZ = 0;

	public static volatile int bastionX = 0;
	public static volatile int bastionZ = 0;
	public static volatile boolean bastionLootApplied = false;

	/**
	 * The nether arrival check has run for this match.
	 *
	 * Reported from play: "really bad nether spawn - no terrain". A
	 * runner who lands on a ledge over an open lava sea has no route,
	 * and whether that happens is luck of where their portal linked.
	 */
	public static volatile boolean netherArrivalChecked = false;

	/**
	 * When the pre-race countdown ends, or 0 if there is none.
	 *
	 * Both players are shown their seed TYPE and given ten seconds to
	 * plan the opening before the race starts. World generation happens
	 * behind that screen, so loading is not merely uncharged - it is
	 * the planning window.
	 */
	public static volatile long countdownEndsAt = 0;

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
	/**
	 * The opponent has voted that this seed is unplayable and is waiting
	 * on us. Set by the live poller, which is the only channel that
	 * carries it - there is no push from the backend.
	 */
	public static volatile boolean opponentProposedBadSeed = false;

	// Set when the match ends. Non-null freezes the HUD timer and swaps
	// the header for the result, so a player who loses is actually told
	// rather than left watching a clock that no longer means anything.
	public static volatile String result = null;
	public static volatile long resultAtMillis = 0;

	/**
	 * First write wins. Two paths can discover the same ending - a
	 * forfeit and the live poller, or the poller and a split report -
	 * and the run ended when the first of them noticed, not when the
	 * last one got round to saying so. Overwriting would push the
	 * frozen time forward by however long the second path took.
	 */
	public static void finish(String text) {
		if (result != null) {
			return;
		}
		resultAtMillis = System.currentTimeMillis();
		result = text;
	}

	/** Elapsed run time, frozen once the match has ended. */
	public static long elapsedMillis() {
		if (matchStartMillis <= 0) {
			return 0;
		}
		long end = result != null ? resultAtMillis : System.currentTimeMillis();
		return end - matchStartMillis;
	}

	public static void reset() {
		matchStartMillis = -1;
		firstBarterLogged = false;
		barterCount = 0;
		obsidianThisWindow = 0;
		pearlsThisWindow = 0;
		matchId = null;
		sessionToken = null;
		seedType = null;
		structureX = 0;
		structureZ = 0;
		overworldSeed = 0;
		netherSeed = 0;
		placedPortal = null;
		quietBox = null;
		smithX = 0;
		smithZ = 0;
		bastionX = 0;
		bastionZ = 0;
		bastionLootApplied = false;
		netherArrivalChecked = false;
		countdownEndsAt = 0;
		BarterSchedule.reset();
		DropSchedule.reset();
		MatchClock.reset();
		mySplits.clear();
		opponentSplits.clear();
		opponentUsername = null;
		opponentProposedBadSeed = false;
		BadSeedVote.reset();
		result = null;
		resultAtMillis = 0;
	}

	/** Milliseconds left on the pre-race countdown; 0 once it is done. */
	public static long countdownRemaining() {
		return countdownEndsAt <= 0 ? 0 : Math.max(0, countdownEndsAt - System.currentTimeMillis());
	}

	public static boolean inMatch() {
		return matchStartMillis > 0 && matchId != null;
	}

	public static String formatTime(long millis) {
		long totalSeconds = Math.max(0, millis) / 1000;
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}
}

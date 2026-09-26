package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.LiveMatchPoller;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.ReplayRecorder;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.QueueJoinResult;
import com.speedrunmcalt.world.MatchWorldCreator;
import net.minecraft.client.MinecraftClient;

/**
 * Queue search driven by the menu, replacing the old fire-once flow
 * that ran automatically on client launch.
 *
 * Volatile state for the same reason as AltSession: the search runs on
 * a background thread and the screen reads it every frame.
 */
public final class Matchmaker {
	public enum State { IDLE, SEARCHING, LAUNCHING, ERROR }

	private static final long POLL_INTERVAL_MS = 3000;

	private static volatile State state = State.IDLE;
	private static volatile String error;
	private static volatile String opponent;
	/**
	 * The pace being raced, or null for a real match.
	 *
	 * Read by the screens so that nothing anywhere says MATCH FOUND for
	 * something that is not a match. One flag, set in one place.
	 */
	private static volatile String paceLabel;
	private static volatile long searchStartMillis;
	// Bumped on cancel so an in-flight search thread knows to stop
	// without needing to interrupt it mid-request.
	private static volatile int searchGeneration = 0;

	private Matchmaker() {
	}

	/**
	 * A match found but not yet entered.
	 *
	 * Handed to a client TICK rather than to client.execute(), and that
	 * distinction is the whole reason this exists.
	 *
	 * Players queue and then practise in another world instead of
	 * sitting on a menu, so a match is normally found with an
	 * integrated server already running and the client has to leave it
	 * first. MinecraftClient.disconnect() begins with cancelTasks() -
	 * it CLEARS the client task queue - and then spins
	 * `while (!server.isStopping()) render(false)`, a nested render
	 * loop. Called from inside a queued task, as this used to be, it
	 * wipes the queue it is running from (taking the follow-up world
	 * creation with it) and re-enters render from within the render
	 * loop's own task drain. The client hangs on a black screen with no
	 * exception and cannot recover.
	 *
	 * A tick handler runs outside that drain, which is the same place
	 * vanilla's own "save and quit to title" ends up, so disconnect
	 * behaves the way it was written to.
	 */
	private static volatile QueueJoinResult pending;
	private static volatile String pendingToken;

	/**
	 * Ticks to wait after a match is found before leaving the world.
	 *
	 * The level-up chime is the only thing telling a player practising
	 * elsewhere that they have been matched, and it is 1.75 seconds
	 * long - entity.player.levelup, measured from the asset index.
	 * disconnect() tears the sound engine down, so switching worlds on
	 * the very next tick cut the chime off after about a twentieth of
	 * a second.
	 *
	 * 40 ticks is two seconds: the chime finishes, and the pause reads
	 * as "match found, here we go" rather than as a stall. It costs the
	 * run nothing, because the clock does not start until the countdown
	 * ends, well after this.
	 */
	private static final int CHIME_TICKS = 40;

	private static volatile int waited;

	/** Registered once, from the client initializer. */
	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
				.END_CLIENT_TICK.register(Matchmaker::tick);
	}

	private static void tick(MinecraftClient client) {
		QueueJoinResult match = pending;
		if (match == null) {
			return;
		}

		// Say so on screen, not just in sound.
		//
		// The menu already reads "match found vs X - loading world",
		// but a player practising elsewhere is not looking at the menu
		// - that is the whole point of queueing this way. Without this
		// the only signal is a chime, and two seconds of nothing
		// happening afterwards looks like a hang, which is exactly what
		// it was until a moment ago.
		//
		// Held for CHIME_TICKS so it is still up when the world starts
		// tearing down, rather than vanishing first.
		// No world means no HUD, and the title overlay is drawn BY the
		// HUD - so queueing from the menus produced a chime and an
		// unchanged screen, which reads as a hang. Whenever the chime
		// fires the next thing on screen says MATCH FOUND: the overlay
		// in a world, this screen in a menu.
		if (waited == 0 && client.world == null) {
			client.openScreen(new MatchFoundScreen(opponent, match.paceLabel));
		}

		if (waited == 0 && client.world != null && client.inGameHud != null) {
			String vs = opponent == null ? "opponent" : opponent;
			// A pace is not a match and must never be announced as one.
			// "MATCH FOUND / vs Pace 10:00" would read as a person with
			// an odd name, which is the exact misunderstanding the
			// feature's own name exists to prevent.
			String heading = match.isPace() ? "RACE A PACE" : "MATCH FOUND";
			String sub = match.isPace() ? "target " + match.paceLabel : "vs " + vs;
			client.inGameHud.setTitles(
					// Yellow, not phosphor. The green belongs to the "alt"
					// wordmark and nothing else - using it here made a
					// status message look like branding.
					new net.minecraft.text.LiteralText(heading).styled(
							st -> st.withColor(net.minecraft.text.TextColor.fromRgb(Palette.YELLOW))),
					new net.minecraft.text.LiteralText(sub).styled(
							st -> st.withColor(net.minecraft.text.TextColor.fromRgb(Palette.YELLOW))),
					0, CHIME_TICKS, 10);
		}

		// Let the chime finish before anything tears down audio.
		if (waited < CHIME_TICKS) {
			waited++;
			return;
		}

		// Leave the practice world first and do nothing else this tick.
		// disconnect() returns only once the integrated server has
		// stopped, and the next tick sees a client with no world.
		if (client.world != null) {
			// BOTH calls, in this order. This is exactly what vanilla's
			// "Save and Quit to Title" does, and the first one is not
			// optional: ClientWorld.disconnect() closes the connection,
			// which is what makes the integrated server begin stopping.
			//
			// MinecraftClient.disconnect() on its own ends in
			// `while (!integratedServer.isStopping()) render(false)` -
			// a spin waiting for a shutdown that, without the first
			// call, nobody ever asked for. It never returns.
			client.world.disconnect();
			client.disconnect(new net.minecraft.client.gui.screen.SaveLevelScreen(
					new net.minecraft.text.TranslatableText("menu.savingLevel")));
			return;
		}

		pending = null;
		waited = 0;
		String token = pendingToken;
		pendingToken = null;
		try {
			MatchState.reset();
			ReplayRecorder.reset();
			MatchState.matchId = match.matchId;
			// The queue result already carries it. Without this the
			// countdown read MatchState.opponentUsername while only
			// LiveMatchPoller ever wrote it, so the name appeared with
			// about three seconds left - after the poll came back,
			// rather than when the match was made.
			MatchState.opponentUsername = match.opponentUsername;
			MatchState.paceLabel = match.paceLabel;
			MatchState.sessionToken = token;
			// Deliberately NOT started here. MatchClock starts it on the
			// first tick the player can actually play, so world
			// generation, setup and loading are not charged to the run.
			// Set before the world is created: the setup hook fires as
			// soon as the integrated server starts and reads these to
			// know what to guarantee.
			MatchState.seedType = match.seedType;
			MatchState.structureX = match.structureX;
			MatchState.structureZ = match.structureZ;
			MatchState.overworldSeed = match.overworldSeed;
			MatchState.netherSeed = match.netherSeed;
			MatchState.bastionX = match.bastionX;
			MatchState.bastionZ = match.bastionZ;
			MatchState.smithX = match.smithX;
			MatchState.smithZ = match.smithZ;
			MatchState.runAlreadyStarted = match.runAlreadyStarted;
			// Tidy up finished worlds before making another. The one we
			// are about to enter is spared: a rejoin after a crash uses
			// the SAME world and must find it intact.
			com.speedrunmcalt.world.MatchWorlds.pruneOld(client, match.matchId);
			MatchWorldCreator.createMatchWorld(client,
					com.speedrunmcalt.world.MatchWorlds.nameFor(match.matchId),
					match.overworldSeed, match.netherSeed);
			LiveMatchPoller.start();
			state = State.IDLE;
		} catch (Exception e) {
			MatchState.reset();
			ReplayRecorder.reset();
			error = e.getMessage();
			state = State.ERROR;
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match world creation failed", e);
		}
	}


	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static String opponent() {
		return opponent;
	}

	/** The pace's label while one is being raced, else null. */
	public static String paceLabel() {
		return paceLabel;
	}


	public static long searchSeconds() {
		return state == State.SEARCHING
				? (System.currentTimeMillis() - searchStartMillis) / 1000
				: 0;
	}

	public static void cancel() {
		searchGeneration++;
		state = State.IDLE;
		opponent = null;
		paceLabel = null;
	}

	/**
	 * Race a pace instead of a person.
	 *
	 * Deliberately NOT a fallback the client takes on its own after a
	 * quiet queue. A pace is offered and chosen, never substituted - a
	 * player who wanted an opponent and silently got a recording would
	 * be right to feel misled, and the backend refuses this anyway while
	 * anybody is queued.
	 *
	 * Shares the tick handler and therefore the whole world-entry path
	 * with a real match. The only thing that differs is what the screens
	 * are told to call it.
	 */
	public static void racePace(MinecraftClient client, String tierId) {
		// A SEARCH IN FLIGHT IS CANCELLED, NOT AN OBSTACLE. Changing your
		// mind two minutes into an empty queue is the single most likely
		// way anybody reaches this feature, and making them back out to
		// press Cancel Search first is a step that already knows the
		// answer. The generation bump below is what stands the poll
		// thread down - it checks on every pass and returns.
		//
		// LAUNCHING still refuses, and that difference matters: it means
		// a real opponent has been found and the world is being built.
		// Throwing that away for a pace would abandon a human who is
		// already loading into a match with you.
		if (state == State.LAUNCHING) {
			return;
		}
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			state = State.ERROR;
			return;
		}

		final int generation = ++searchGeneration;
		state = State.SEARCHING;
		error = null;
		opponent = null;
		paceLabel = null;
		searchStartMillis = System.currentTimeMillis();

		Thread thread = new Thread(() -> {
			try {
				QueueJoinResult result = BackendClient.racePace(token, tierId);
				if (generation != searchGeneration) {
					return;
				}
				opponent = result.opponentUsername;
				paceLabel = result.paceLabel;
				state = State.LAUNCHING;
				Cues.matchFound();
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Racing pace {} - matchId={} type={}",
						result.paceLabel, result.matchId, result.seedType);
				pendingToken = token;
				waited = 0;
				pending = result;
			} catch (Exception e) {
				if (generation == searchGeneration) {
					// The backend's refusals are written as sentences a
					// player can act on - "someone is waiting to race" -
					// so they go on screen as they are.
					error = e.getMessage();
					state = State.ERROR;
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Race a Pace failed", e);
				}
			}
		}, "speedrunmcalt-racepace");
		thread.setDaemon(true);
		thread.start();
	}

	public static void search(MinecraftClient client) {
		if (state == State.SEARCHING || state == State.LAUNCHING) {
			return;
		}
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			state = State.ERROR;
			return;
		}

		final int generation = ++searchGeneration;
		state = State.SEARCHING;
		error = null;
		opponent = null;
		searchStartMillis = System.currentTimeMillis();

		Thread thread = new Thread(() -> {
			try {
				QueueJoinResult result = BackendClient.joinQueue(token);
				while (!result.matched) {
					if (generation != searchGeneration) {
						return; // cancelled
					}
					Thread.sleep(POLL_INTERVAL_MS);
					if (generation != searchGeneration) {
						return;
					}
					result = BackendClient.joinQueue(token);
				}
				if (generation != searchGeneration) {
					return;
				}

				opponent = result.opponentUsername;
				state = State.LAUNCHING;
				Cues.matchFound();
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Matched vs {} - matchId={} type={} structure={},{} "
								+ "bastion={}@{},{} overworldSeed={} netherSeed={}",
						result.opponentUsername, result.matchId, result.seedType,
						result.structureX, result.structureZ, result.bastionType,
						result.bastionX, result.bastionZ,
						result.overworldSeed, result.netherSeed);

				// Nothing client-side happens on this thread. The tick
				// handler picks it up from here.
				pendingToken = token;
				waited = 0;
				pending = result;
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				if (generation == searchGeneration) {
					error = e.getMessage();
					state = State.ERROR;
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Matchmaking failed", e);
				}
			}
		}, "speedrunmcalt-matchmaker");
		thread.setDaemon(true);
		thread.start();
	}
}

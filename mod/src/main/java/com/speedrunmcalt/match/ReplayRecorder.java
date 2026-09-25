package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records a low-rate timeline of where the player actually was during a
 * match, so reported splits can be checked against a continuous record
 * rather than taken on trust.
 *
 * Deliberately not video. Video is gigabytes per match, is the single
 * largest cost driver in this backend, and is editable anyway. A
 * position and dimension trace is around 50KB per run and answers the
 * questions that matter: was the player actually in the nether when
 * they claimed the nether split, did they move at possible speeds, does
 * the route hold together. Faking one split is easy; faking a coherent
 * ten minute movement trace is not.
 *
 * Honest limit: a modified client can still fabricate this. It raises
 * the cost of cheating and gives a reviewer something concrete to look
 * at - it does not make the client trustworthy.
 */
public final class ReplayRecorder {
	/**
	 * Every 2 ticks - 10 samples a second.
	 *
	 * One per second was enough for the original job: bound movement
	 * speed, catch teleports, check a claimed split against where the
	 * player actually was. It is not enough to WATCH. A replay drives a
	 * camera from these, and at 1Hz that is a slideshow; first-person
	 * playback at 1Hz is unwatchable in a way a third-person path is
	 * not.
	 *
	 * Costs about ten times the storage - a ten minute run goes from
	 * roughly 50KB to 300-500KB gzipped. Still nothing against video,
	 * which is the alternative this design rejected.
	 */
	private static final int SAMPLE_INTERVAL_TICKS = 2;

	/**
	 * Hard ceiling so a stuck or very long session cannot grow without
	 * bound. Two hours at the CURRENT rate.
	 *
	 * This moved with the rate. Left at 7200 it would have silently
	 * truncated every run past twelve minutes - long runs, the ones
	 * most worth reviewing, losing their endings and nothing saying
	 * so.
	 */
	private static final int MAX_SAMPLES = 72000;

	public static final class Sample {
		public final long t;
		public final int dim;
		public final double x;
		public final double y;
		public final double z;
		/**
		 * Where they were LOOKING, in degrees.
		 *
		 * Not recorded at all before, because position alone answers
		 * the verification questions. It does not answer "what did they
		 * see": a first-person replay without rotation is a camera
		 * pointing wherever the viewer happens to drag it, which is not
		 * the player's perspective in any meaningful sense.
		 */
		public final float yaw;
		public final float pitch;

		Sample(long t, int dim, double x, double y, double z, float yaw, float pitch) {
			this.t = t;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}

	/**
	 * A moment worth naming, as opposed to a position.
	 *
	 * A trace says where somebody was; it cannot say that this was when
	 * they died, or took the rod, or lost the bed. Those are the beats
	 * a replay is watched FOR, and none of them are reproducible from
	 * the seed - terrain and loot are deterministic, a zombie arriving
	 * at 4:12 is not.
	 *
	 * Deliberately sparse. Recording every damage tick would bury the
	 * moments that matter in noise and cost more than the positions do.
	 */
	public static final class Event {
		public final long t;
		public final String type;
		public final String detail;

		Event(long t, String type, String detail) {
			this.t = t;
			this.type = type;
			this.detail = detail;
		}
	}

	private static final List<Event> EVENTS = Collections.synchronizedList(new ArrayList<>());

	/** Far fewer than samples, but a stuck client should not grow either. */
	private static final int MAX_EVENTS = 2000;

	private static final List<Sample> SAMPLES = Collections.synchronizedList(new ArrayList<>());
	private static int tickCounter = 0;
	private static volatile boolean uploaded = false;

	private ReplayRecorder() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ReplayRecorder::tick);
	}

	/**
	 * Records a named moment, if there is room and a match is running.
	 *
	 * Public because the things worth recording happen all over the
	 * mod - a death in one mixin, a pickup in another - and routing
	 * them through here keeps the ordering and the guards in one
	 * place.
	 */
	public static void event(String type, String detail) {
		if (!MatchState.inMatch() || MatchState.replayMode) {
			return;
		}
		if (EVENTS.size() >= MAX_EVENTS) {
			return;
		}
		EVENTS.add(new Event(
				System.currentTimeMillis() - MatchState.matchStartMillis, type, detail));
	}

	public static List<Event> events() {
		synchronized (EVENTS) {
			return new ArrayList<>(EVENTS);
		}
	}

	public static void reset() {
		EVENTS.clear();
		SAMPLES.clear();
		tickCounter = 0;
		uploaded = false;
	}

	private static void tick(MinecraftClient client) {
		// Never record a replay of a replay - it would overwrite the
		// original trace with a recording of watching it.
		if (!MatchState.inMatch() || MatchState.replayMode) {
			return;
		}
		ClientPlayerEntity player = client.player;
		if (player == null) {
			return;
		}
		if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		if (SAMPLES.size() >= MAX_SAMPLES) {
			return;
		}

		SAMPLES.add(new Sample(
				System.currentTimeMillis() - MatchState.matchStartMillis,
				dimensionIndex(player.world.getRegistryKey()),
				player.getX(), player.getY(), player.getZ(),
				player.yaw, player.pitch));
	}

	private static int dimensionIndex(net.minecraft.util.registry.RegistryKey<World> key) {
		if (World.NETHER.equals(key)) {
			return 1;
		}
		if (World.END.equals(key)) {
			return 2;
		}
		return 0;
	}

	/**
	 * Uploads once per match. Called when the run finishes; guarded
	 * because both the split reporter and the poller can observe
	 * completion.
	 */
	public static void uploadIfFinished() {
		if (uploaded) {
			return;
		}
		String matchId = MatchState.matchId;
		String token = MatchState.sessionToken;
		if (matchId == null || token == null) {
			return;
		}
		uploaded = true;

		List<Sample> snapshot;
		synchronized (SAMPLES) {
			snapshot = new ArrayList<>(SAMPLES);
		}
		if (snapshot.isEmpty()) {
			return;
		}

		Thread thread = new Thread(() -> {
			try {
				BackendClient.uploadReplay(token, matchId, snapshot, events());
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Uploaded replay: {} samples", snapshot.size());
			} catch (Exception e) {
				// A failed upload must not affect the match result.
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Replay upload failed", e);
			}
		}, "speedrunmcalt-replay");
		thread.setDaemon(true);
		thread.start();
	}
}

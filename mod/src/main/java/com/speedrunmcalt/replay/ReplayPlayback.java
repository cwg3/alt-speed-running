package com.speedrunmcalt.replay;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.net.ReplayData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;

/**
 * Drives the camera along a recorded trace.
 *
 * The world is REGENERATED from the match's seeds rather than stored,
 * so a replay is a trace plus a seed and costs nothing to keep. That
 * only holds while the rules that build a world stay put, which is
 * what worldSetupVersion guards.
 *
 * Playback time is kept separately from wall-clock so pause, scrub and
 * speed are all the same operation: move `positionMillis` and let the
 * next tick put the camera where the trace says it was.
 */
public final class ReplayPlayback {
	/** Ticks the camera; null when nothing is being watched. */
	private static volatile ReplayData data;
	private static volatile String watching;

	private static volatile long positionMillis = 0;
	private static volatile boolean paused = false;
	private static volatile float speed = 1.0f;
	private static long lastTickMillis = 0;

	/**
	 * Locked to the player, or flying free.
	 *
	 * LOCKED takes both position and view angle from the trace: what
	 * the player actually saw, which is the point of the feature and
	 * so the default.
	 *
	 * FREE drives nothing. The camera is already in spectator, so not
	 * touching it IS free flight - ordinary movement keys, ordinary
	 * mouse look, anywhere in the world. Useful for looking at terrain
	 * or a structure on your own terms rather than through somebody
	 * else's run.
	 *
	 * Playback keeps running either way: leaving the camera behind does
	 * not stop the clock, and switching back to LOCKED snaps to
	 * wherever the trace has got to.
	 */
	public enum Camera { LOCKED, FREE }

	private static volatile Camera camera = Camera.LOCKED;

	/** Which dimension the camera is currently IN, to spot a change. */
	private static volatile int cameraDim = -1;

	/** Spectator is set once, after the world finishes loading. */
	private static volatile boolean prepared = false;

	private ReplayPlayback() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ReplayPlayback::tick);
	}

	public static boolean active() {
		return data != null;
	}

	public static ReplayData data() {
		return data;
	}

	public static String watching() {
		return watching;
	}

	public static long positionMillis() {
		return positionMillis;
	}

	public static boolean paused() {
		return paused;
	}

	public static float speed() {
		return speed;
	}

	public static Camera camera() {
		return camera;
	}

	public static long durationMillis() {
		long end = 0;
		if (data != null) {
			for (ReplayData.Track t : data.tracks.values()) {
				if (t.samples != null && !t.samples.isEmpty()) {
					end = Math.max(end, t.samples.get(t.samples.size() - 1).t);
				}
			}
		}
		return end;
	}

	public static void begin(ReplayData replay, String watchUuid) {
		data = replay;
		watching = watchUuid;
		positionMillis = 0;
		paused = false;
		speed = 1.0f;
		camera = Camera.LOCKED;
		cameraDim = -1;
		prepared = false;
		lastTickMillis = System.currentTimeMillis();
	}

	public static void stop() {
		data = null;
		watching = null;
		MatchState.replayMode = false;
	}

	/** Switch which player the camera is following, keeping the time. */
	public static void watch(String uuid) {
		if (data != null && data.tracks.containsKey(uuid)) {
			watching = uuid;
		}
	}

	public static void togglePause() {
		paused = !paused;
	}

	public static void toggleCamera() {
		camera = camera == Camera.LOCKED ? Camera.FREE : Camera.LOCKED;
		// Force a re-teleport on the next tick when locking back on:
		// the viewer may have flown to another dimension entirely.
		if (camera == Camera.LOCKED) {
			cameraDim = -1;
		}
	}

	public static void setSpeed(float s) {
		speed = Math.max(0.25f, Math.min(8.0f, s));
	}

	public static void seek(long millis) {
		positionMillis = Math.max(0, Math.min(durationMillis(), millis));
	}

	public static void nudge(long millis) {
		seek(positionMillis + millis);
	}

	private static void tick(MinecraftClient client) {
		if (data == null || client.player == null || client.world == null) {
			return;
		}

		long now = System.currentTimeMillis();
		long wall = now - lastTickMillis;
		lastTickMillis = now;

		if (!paused) {
			positionMillis += (long) (wall * speed);
			long end = durationMillis();
			if (positionMillis >= end) {
				positionMillis = end;
				paused = true;   // stop at the end rather than looping
			}
		}

		ReplayData.Track track = data.tracks.get(watching);
		if (track == null || track.samples == null || track.samples.isEmpty()) {
			return;
		}
		ReplayData.Sample s = sampleAt(track.samples, positionMillis);
		if (s == null) {
			return;
		}

		net.minecraft.server.MinecraftServer server = client.getServer();
		if (server == null) {
			return;
		}

		// Free-roam: the clock still runs and the timeline still moves,
		// but the camera is the viewer's. Spectator flight is already
		// active, so the whole implementation is to do nothing.
		if (camera == Camera.FREE) {
			return;
		}

		// Spectator, once. Without it the camera collides with terrain,
		// suffocates inside blocks and falls - a survival body being
		// dragged along a path rather than a camera following it.
		if (!prepared) {
			prepared = true;
			server.execute(() -> {
				net.minecraft.server.network.ServerPlayerEntity sp =
						server.getPlayerManager().getPlayer(client.player.getUuid());
				if (sp != null) {
					sp.setGameMode(net.minecraft.world.GameMode.SPECTATOR);
				}
			});
		}

		// A dimension change is a teleport between worlds, not a move.
		// Following nether coordinates while still standing in the
		// overworld puts the camera eight times too far out, drifting
		// through empty sky - which is exactly what it did.
		if (s.dim != cameraDim) {
			cameraDim = s.dim;
			final ReplayData.Sample at = s;
			server.execute(() -> {
				net.minecraft.server.network.ServerPlayerEntity sp =
						server.getPlayerManager().getPlayer(client.player.getUuid());
				net.minecraft.server.world.ServerWorld target =
						server.getWorld(worldKeyFor(at.dim));
				if (sp != null && target != null) {
					sp.teleport(target, at.x, at.y, at.z, at.yaw, at.pitch);
				}
			});
			return;   // let the teleport land before driving the camera
		}

		ClientPlayerEntity p = client.player;
		// Interpolating between samples is what makes 10Hz look like
		// motion rather than teleporting ten times a second.
		p.updatePosition(s.x, s.y, s.z);
		p.yaw = s.yaw;
		p.pitch = s.pitch;
		p.prevYaw = s.yaw;
		p.prevPitch = s.pitch;
	}

	/**
	 * The trace position at a time, interpolated between samples.
	 *
	 * Binary search rather than a scan: scrubbing jumps anywhere in a
	 * trace of tens of thousands of samples, every tick.
	 */
	private static ReplayData.Sample sampleAt(List<ReplayData.Sample> samples, long t) {
		int lo = 0;
		int hi = samples.size() - 1;
		if (t <= samples.get(0).t) {
			return samples.get(0);
		}
		if (t >= samples.get(hi).t) {
			return samples.get(hi);
		}
		while (lo + 1 < hi) {
			int mid = (lo + hi) >>> 1;
			if (samples.get(mid).t <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		ReplayData.Sample a = samples.get(lo);
		ReplayData.Sample b = samples.get(hi);
		if (a.dim != b.dim || b.t == a.t) {
			// Do not interpolate across a dimension change - the
			// coordinates either side are in different spaces, and
			// averaging them puts the camera nowhere real.
			return a;
		}
		double f = (double) (t - a.t) / (double) (b.t - a.t);
		return new ReplayData.Sample(t, a.dim,
				a.x + (b.x - a.x) * f,
				a.y + (b.y - a.y) * f,
				a.z + (b.z - a.z) * f,
				(float) (a.yaw + shortestAngle(a.yaw, b.yaw) * f),
				(float) (a.pitch + (b.pitch - a.pitch) * f));
	}

	/** The dimension index the trace stores, as a world key. */
	private static net.minecraft.util.registry.RegistryKey<net.minecraft.world.World>
			worldKeyFor(int dim) {
		if (dim == 1) {
			return net.minecraft.world.World.NETHER;
		}
		if (dim == 2) {
			return net.minecraft.world.World.END;
		}
		return net.minecraft.world.World.OVERWORLD;
	}

	/** Turning from 350 to 10 degrees is 20 degrees, not -340. */
	private static double shortestAngle(float from, float to) {
		double d = (to - from) % 360.0;
		if (d > 180) {
			d -= 360;
		}
		if (d < -180) {
			d += 360;
		}
		return d;
	}
}

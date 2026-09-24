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
	 * Free look: the camera holds position but the viewer turns it.
	 *
	 * Off by default, because the point is to see what the player saw.
	 */
	private static volatile boolean freeLook = false;

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

	public static boolean freeLook() {
		return freeLook;
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
		freeLook = false;
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

	public static void toggleFreeLook() {
		freeLook = !freeLook;
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

		ClientPlayerEntity p = client.player;
		// Interpolating between samples is what makes 10Hz look like
		// motion rather than teleporting ten times a second.
		p.updatePosition(s.x, s.y, s.z);
		if (!freeLook) {
			p.yaw = s.yaw;
			p.pitch = s.pitch;
			p.prevYaw = s.yaw;
			p.prevPitch = s.pitch;
		}
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

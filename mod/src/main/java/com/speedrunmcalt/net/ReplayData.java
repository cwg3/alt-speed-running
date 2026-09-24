package com.speedrunmcalt.net;

import java.util.List;
import java.util.Map;

/**
 * Everything needed to play a match back.
 *
 * No world data: the seeds rebuild it. That is what makes a replay
 * cheap enough to keep, and it is only sound while the rules that
 * build a world stay put - hence worldSetupVersion.
 */
public final class ReplayData {
	/** One position-and-look sample. Times are ms from the run start. */
	public static final class Sample {
		public final long t;
		public final int dim;
		public final double x;
		public final double y;
		public final double z;
		public final float yaw;
		public final float pitch;

		public Sample(long t, int dim, double x, double y, double z, float yaw, float pitch) {
			this.t = t;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}

	public static final class Track {
		public final String username;
		public final List<Sample> samples;

		public Track(String username, List<Sample> samples) {
			this.username = username;
			this.samples = samples;
		}
	}

	public final String matchId;
	public final long overworldSeed;
	public final long netherSeed;
	public final String seedType;

	/**
	 * Which build's rules made this world.
	 *
	 * 0 means the match predates stamping - unknown, not version zero.
	 * Playback must refuse rather than rebuild under today's rules and
	 * present the result as what was played.
	 */
	public final int worldSetupVersion;

	/** Keyed by player uuid; a track's samples may be null if none was uploaded. */
	public final Map<String, Track> tracks;

	public ReplayData(String matchId, long overworldSeed, long netherSeed, String seedType,
			int worldSetupVersion, Map<String, Track> tracks) {
		this.matchId = matchId;
		this.overworldSeed = overworldSeed;
		this.netherSeed = netherSeed;
		this.seedType = seedType;
		this.worldSetupVersion = worldSetupVersion;
		this.tracks = tracks;
	}
}

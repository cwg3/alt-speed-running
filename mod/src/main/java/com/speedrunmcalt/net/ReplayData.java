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

	/** A named moment: a death, a kill, something picked up. */
	public static final class Event {
		public final long t;
		public final String type;
		public final String detail;

		public Event(long t, String type, String detail) {
			this.t = t;
			this.type = type;
			this.detail = detail;
		}
	}

	/**
	 * One other entity, at one moment.
	 *
	 * Recorded rather than re-simulated because the replay world spawns
	 * no mobs at all: SpawnHelper's player lookup excludes spectators,
	 * and the viewer is one. See EntityTracks for the whole argument.
	 */
	public static final class EntityRow {
		public final long t;
		/** The recording client's network id - stable for one entity's life. */
		public final int id;
		/** Index into the track's typeNames. */
		public final int type;
		public final int dim;
		public final double x;
		public final double y;
		public final double z;
		public final float yaw;

		public EntityRow(long t, int id, int type, int dim,
				double x, double y, double z, float yaw) {
			this.t = t;
			this.id = id;
			this.type = type;
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
		}
	}

	public static final class Track {
		public final String username;
		public final List<Sample> samples;
		/** Empty for traces recorded before events existed. */
		public final List<Event> events;
		/** Entity type ids, referenced by index from entities. */
		public final List<String> typeNames;
		/** Empty for traces recorded before entity tracks existed. */
		public final List<EntityRow> entities;

		public Track(String username, List<Sample> samples, List<Event> events,
				List<String> typeNames, List<EntityRow> entities) {
			this.username = username;
			this.samples = samples;
			this.events = events;
			this.typeNames = typeNames;
			this.entities = entities;
		}
	}

	public final String matchId;
	public final long overworldSeed;
	public final long netherSeed;
	public final String seedType;

	/**
	 * The coordinates MatchWorldSetup keys off.
	 *
	 * A replay world is built by the same setup pass as the match, and
	 * that pass SKIPS the loot top-up when it cannot find the
	 * structure. Without these it logged "No shipwreck structure at
	 * 0,0" and built a world whose chests hold different contents than
	 * the ones the player actually opened - while looking completely
	 * normal. Seeing what was in the chests is most of why replays
	 * exist.
	 */
	public final int structureX;
	public final int structureZ;
	public final int bastionX;
	public final int bastionZ;
	public final Integer smithX;
	public final Integer smithZ;

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

	/**
	 * uuid -> split name -> time in ms.
	 *
	 * A trace says where somebody was. Splits say which moments
	 * mattered, and without them a replay is ten minutes of walking
	 * with no landmarks.
	 */
	public final Map<String, Map<String, Long>> splits;

	public ReplayData(String matchId, long overworldSeed, long netherSeed, String seedType,
			int worldSetupVersion, Map<String, Track> tracks,
			Map<String, Map<String, Long>> splits,
			int structureX, int structureZ, int bastionX, int bastionZ,
			Integer smithX, Integer smithZ) {
		this.structureX = structureX;
		this.structureZ = structureZ;
		this.bastionX = bastionX;
		this.bastionZ = bastionZ;
		this.smithX = smithX;
		this.smithZ = smithZ;
		this.splits = splits;
		this.matchId = matchId;
		this.overworldSeed = overworldSeed;
		this.netherSeed = netherSeed;
		this.seedType = seedType;
		this.worldSetupVersion = worldSetupVersion;
		this.tracks = tracks;
	}
}

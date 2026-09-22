package com.speedrunmcalt.net;

public final class QueueJoinResult {
	public final boolean matched;
	public final String matchId;
	public final String opponentUsername;
	public final long overworldSeed;
	public final long netherSeed;
	/**
	 * Which opening route this seed is for.
	 *
	 * A seed is good for exactly one route, never all of them, and what
	 * the world needs guaranteed depends on which - lava pools near a
	 * village, a usable portal at a ruined portal, and so on. Defaults
	 * to "village" for rows written before the pool carried a type.
	 */
	public final String seedType;
	/** The qualifying structure, so placement knows where to work. */
	public final int structureX;
	public final int structureZ;
	/** Informational: which of the four bastions this seed gives. */
	public final String bastionType;
	/** Where the bastion is, so its chests can be topped up. */
	public final int bastionX;
	public final int bastionZ;
	/**
	 * Where the blacksmith is on a village seed, or 0,0 if unknown.
	 *
	 * structureX/Z is the village's jigsaw ANCHOR and is not where the
	 * player is going: a village box can be 107 by 174 blocks, and a
	 * player sent to the anchor reported finding no blacksmith when it
	 * was 54 blocks away. On a village seed the smith IS the objective.
	 */
	public final int smithX;
	public final int smithZ;

	private QueueJoinResult(boolean matched, String matchId, String opponentUsername,
			long overworldSeed, long netherSeed, String seedType,
			int structureX, int structureZ, String bastionType,
			int bastionX, int bastionZ, int smithX, int smithZ) {
		this.matched = matched;
		this.matchId = matchId;
		this.opponentUsername = opponentUsername;
		this.overworldSeed = overworldSeed;
		this.netherSeed = netherSeed;
		this.seedType = seedType;
		this.structureX = structureX;
		this.structureZ = structureZ;
		this.bastionType = bastionType;
		this.bastionX = bastionX;
		this.bastionZ = bastionZ;
		this.smithX = smithX;
		this.smithZ = smithZ;
	}

	public static QueueJoinResult waiting() {
		return new QueueJoinResult(false, null, null, 0, 0, null, 0, 0, null, 0, 0, 0, 0);
	}

	public static QueueJoinResult matched(String matchId, String opponentUsername,
			long overworldSeed, long netherSeed, String seedType,
			int structureX, int structureZ, String bastionType,
			int bastionX, int bastionZ, int smithX, int smithZ) {
		return new QueueJoinResult(true, matchId, opponentUsername, overworldSeed, netherSeed,
				seedType, structureX, structureZ, bastionType, bastionX, bastionZ,
				smithX, smithZ);
	}
}

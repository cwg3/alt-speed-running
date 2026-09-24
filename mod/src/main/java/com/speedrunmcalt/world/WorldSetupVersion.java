package com.speedrunmcalt.world;

/**
 * Which build of the world-building rules made a match world.
 *
 * A match world is not stored anywhere - it is REGENERATED from its
 * seeds when needed, which is what makes replays cost nothing to keep.
 * That only works while the rules that build it stay the same. Change
 * how lava pools are placed, or how much loot a chest is topped up to,
 * and regenerating an old match produces a world the players never
 * saw: the recorded movement would be truthful and the world around it
 * wrong, which is worse than having no replay at all.
 *
 * So every match records the version that built it, and anything
 * regenerating that world checks the stamp first.
 *
 * BUMP THIS whenever a change alters the blocks or loot a given seed
 * produces. Concretely, the placement surface is:
 *
 *   world/LavaPoolPlacer      lava pools and water sources
 *   world/RuinedPortalPlacer  portal frame completion
 *   world/ShipwreckChests     wreck chest contents
 *   seed/LootTopUp            chest top-ups, obsidian, food
 *   seed/BastionLoot          bastion chest contents
 *   world/MatchWorldSetup     what runs, and in what order
 *
 * Changing a log line or a comment in those files does not count.
 * Changing a salt, a threshold, an amount or an order does.
 *
 * Not bumping it when you should is the failure this exists to catch,
 * and nothing can detect it automatically - the stamp is a claim the
 * author makes. Treat a change to the files above as requiring a
 * deliberate answer to "does this alter any existing seed's world?"
 */
public final class WorldSetupVersion {
	/**
	 * 1 - the rules as of the first build that recorded a stamp.
	 *
	 * Matches made before this exists carry no stamp at all. They are
	 * not replayable and must not be silently treated as version 1:
	 * "unknown" and "the current rules" are different claims.
	 */
	public static final int CURRENT = 1;

	private WorldSetupVersion() {
	}
}

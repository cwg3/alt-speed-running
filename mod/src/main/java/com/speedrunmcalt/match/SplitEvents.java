package com.speedrunmcalt.match;

import net.minecraft.util.Identifier;

/**
 * Maps advancement IDs to split names. Full split list: enter
 * nether, trade with piglin, obtain rod, enter stronghold, enter end,
 * kill dragon.
 *
 * Four of the six reuse vanilla's own advancement system - confirmed
 * against the real advancement JSON files in the server jar, each has
 * exactly one criterion so completing it is unambiguous:
 *  - minecraft:story/enter_the_nether
 *  - minecraft:nether/obtain_blaze_rod
 *  - minecraft:end/root (vanilla's own "entered the End" advancement -
 *    no custom one needed)
 *  - minecraft:end/kill_dragon
 *
 * enter_stronghold is OUR OWN advancement (data/speedrunmcalt/
 * advancements/enter_stronghold.json), since vanilla has no dedicated
 * one - reuses vanilla's generic "location" trigger with a structure
 * (feature: "Stronghold") condition, confirmed supported by
 * LocationPredicate in 1.16.1, rather than writing a bespoke
 * structure-bounds-checking mixin.
 *
 * piglin_barter has no advancement trigger at all in 1.16.1 (bartering
 * is new that version and doesn't seem to have gotten one yet) - that
 * one is a direct mixin instead, see PiglinBarterMixin.
 */
public final class SplitEvents {
	private SplitEvents() {
	}

	public static String nameFor(Identifier advancementId) {
		String id = advancementId.toString();
		switch (id) {
			case "minecraft:story/enter_the_nether":
				return "enter_nether";
			case "minecraft:nether/obtain_blaze_rod":
				return "obtain_rod";
			case "speedrunmcalt:enter_stronghold":
				return "enter_stronghold";
			case "minecraft:end/root":
				return "enter_end";
			case "minecraft:end/kill_dragon":
				return "kill_dragon";
			default:
				return null;
		}
	}
}

package com.speedrunmcalt.match;

import net.minecraft.util.Identifier;

/**
 * Maps vanilla advancement IDs to ranked split names. Confirmed against
 * the actual advancement JSON files in the server jar
 * (data/minecraft/advancements/...) - each of these three has exactly
 * one criterion, so completing it is unambiguous (no partial-progress
 * case to worry about).
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
			case "minecraft:end/kill_dragon":
				return "kill_dragon";
			default:
				return null;
		}
	}
}

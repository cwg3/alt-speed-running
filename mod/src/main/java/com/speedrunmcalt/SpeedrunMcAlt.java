package com.speedrunmcalt;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SpeedrunMcAlt implements ModInitializer {
	public static final String MOD_ID = "speedrunmcalt";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Runs on the integrated server when a match world starts, and
		// no-ops for every other world.
		com.speedrunmcalt.world.MatchWorldSetup.register();
		LOGGER.info("speedrun-mc-alt loaded");
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}

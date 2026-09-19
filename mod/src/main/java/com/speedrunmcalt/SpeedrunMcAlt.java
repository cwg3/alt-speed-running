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
		LOGGER.info("speedrun-mc-alt loaded");
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}

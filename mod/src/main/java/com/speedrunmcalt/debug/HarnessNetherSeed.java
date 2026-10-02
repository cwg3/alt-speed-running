package com.speedrunmcalt.debug;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * TEMPORARY - the nether seed a harness server should generate with.
 * See HarnessNetherSeedMixin. Read once from nethergen.txt; null on a
 * client, or when the file is absent.
 */
public final class HarnessNetherSeed {
	private static boolean read = false;
	private static Long seed = null;

	private HarnessNetherSeed() {
	}

	public static synchronized Long get() {
		if (!read) {
			read = true;
			Path file = Paths.get("nethergen.txt");
			if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER
					&& Files.exists(file)) {
				try {
					seed = Long.parseLong(new String(Files.readAllBytes(file), "UTF-8").trim());
				} catch (Exception e) {
					throw new IllegalStateException("nethergen.txt is not a seed", e);
				}
			}
		}
		return seed;
	}
}

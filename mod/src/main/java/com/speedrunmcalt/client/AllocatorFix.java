package com.speedrunmcalt.client;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Steers LWJGL onto the system allocator before it initialises.
 *
 * The launcher's bundled Java 8 runtime on macOS segfaults inside
 * LWJGL's own jemalloc at shutdown:
 *
 *   SIGSEGV (0xb) ... Problematic frame: C [libjemalloc.dylib+0x331ed]
 *
 * It fires AFTER the world is saved and after "Stopping!" is logged, so
 * nothing is lost - but a tester sees a crash dialog on quit and
 * reasonably concludes the mod broke their game. Four of these on this
 * machine, the earliest predating any of this work, with the same
 * jemalloc frame recurring - so it is the environment, not us.
 *
 * org.lwjgl.system.allocator=system tells LWJGL to use malloc instead.
 * It is read lazily the first time the allocator is used, which happens
 * during Minecraft's own startup - well after a preLaunch entrypoint,
 * so setting it here lands in time.
 *
 * A JVM argument would do the same, but a .mrpack cannot carry one: the
 * format has no field for it, and overrides/ maps into .minecraft
 * rather than the instance root where launchers keep JVM settings.
 * Doing it in the mod means every tester gets it on every launcher
 * without a setup step to forget.
 *
 * Never overrides a value that is already set. Somebody who has chosen
 * an allocator deliberately keeps it.
 */
public class AllocatorFix implements PreLaunchEntrypoint {
	private static final String KEY = "org.lwjgl.system.allocator";

	@Override
	public void onPreLaunch() {
		if (System.getProperty(KEY) == null) {
			System.setProperty(KEY, "system");
			// System.out, not LOGGER. preLaunch runs before the mod's
			// own classes are touched, and loading SpeedrunMcAlt just
			// to log one line would initialise it earlier than intended.
			System.out.println("[speedrunmcalt] " + KEY
					+ "=system (avoids the jemalloc shutdown crash)");
		}
	}
}

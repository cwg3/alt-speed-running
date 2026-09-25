package com.speedrunmcalt.match;

/**
 * Remembers what an item entity held before the inventory drained it.
 *
 * Vanilla's ItemEntity.onPlayerCollision calls insertStack, which
 * MUTATES the stack to empty, and only then calls sendPickup - the one
 * place that knows the pickup succeeded. So the name has to be read in
 * the first and the event emitted from the second, and this carries it
 * across.
 *
 * In com.speedrunmcalt.match, NOT in the mixin package. Anything under
 * com.speedrunmcalt.mixin.* is owned by speedrunmcalt.mixins.json and
 * cannot be referenced directly by anything else; a mixin that tried
 * took the game down with IllegalClassLoadError on the first block
 * broken. Mixin classes are merged into their target and are not a
 * place to keep state.
 *
 * A plain static is correct here because both halves run on the server
 * thread inside one synchronous call. Keyed by entity id so a stale
 * value can never be attributed to the wrong item.
 */
public final class PendingPickup {
	private static int pendingId = -1;
	private static String pendingName = null;

	private PendingPickup() {
	}

	public static void put(int entityId, String name) {
		pendingId = entityId;
		pendingName = name;
	}

	/** Reads once, and only for the entity it was stored against. */
	public static String take(int entityId) {
		if (entityId != pendingId) {
			return null;
		}
		String name = pendingName;
		pendingId = -1;
		pendingName = null;
		return name;
	}
}

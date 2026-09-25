package com.speedrunmcalt.match;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything ELSE was, so a replay is a world rather than a
 * player moving through an empty one.
 *
 * WHY THIS HAS TO BE RECORDED AT ALL. The obvious thought is that it
 * does not: the replay world is regenerated from the match seed and is
 * a live server, so surely it spawns its own mobs. It does not spawn
 * ANY. SpawnHelper asks
 *
 *     ServerWorld.getClosestPlayer(x, y, z, -1.0, false)
 *
 * and both branches of that overload use a spectator-excluding
 * predicate - EXCEPT_SPECTATOR or EXCEPT_CREATIVE_OR_SPECTATOR. The
 * replay viewer is a spectator, the lookup returns null, and the spawn
 * attempt is abandoned. Verified in the 1.16.1 bytecode, not assumed.
 *
 * WHY NOT JUST LET THEM SPAWN. Putting the viewer in creative, or
 * parking a non-spectator anchor near the playback position, would
 * populate the world for about ten lines of code - with DIFFERENT
 * mobs. Spawning is RNG against a world state that no longer matches,
 * so the blaze that killed you is not there and a blaze that never
 * existed is. Replays are kept for forfeited matches specifically so
 * disputes can be settled; a replay that invents plausible mobs is
 * worse than one that honestly shows none.
 *
 * So: record what the client actually saw.
 *
 * WHAT IT COSTS, and why these numbers. The player track is 10Hz
 * because a camera is driven from it and anything slower is a
 * slideshow. Nothing else here is a camera. At 2Hz a zombie's walk
 * still reads correctly once interpolated, and it is a fifth of the
 * data. 64 blocks is inside the server's own tracking range for most
 * mobs, so the client rarely has more to offer anyway, and the 40-entity
 * cap bounds the one case that gets silly - standing in a fortress
 * with six blazes, a magma cube swarm and a floor of dropped rods.
 *
 * Worst case is about 96,000 rows for a twenty minute run, which gzips
 * to a few hundred KB. Against video, which this whole design exists
 * to avoid, that is nothing.
 */
public final class EntityTracks {
	/** Every 10 ticks - 2 samples a second. See the class note. */
	private static final int SAMPLE_INTERVAL_TICKS = 10;

	/**
	 * Blocks. Squared here because the comparison is done squared -
	 * one multiply beats a square root per entity per sample.
	 */
	private static final double RADIUS_SQ = 64.0 * 64.0;

	/** Per sample, nearest first. Bounds a fortress or a mob farm. */
	private static final int MAX_PER_SAMPLE = 40;

	/**
	 * Hard ceiling on the whole match, so a very long session cannot
	 * grow without bound. Roughly half an hour at the full 40-entity
	 * cap, which no real run reaches - the cap that actually binds is
	 * the radius.
	 */
	private static final int MAX_ROWS = 150_000;

	/**
	 * One row per entity per sample: time, entity id, type index,
	 * dimension, position, facing.
	 *
	 * The id is the client's network id, which is what lets playback
	 * follow one zombie across samples instead of redrawing a crowd
	 * from scratch. It is stable for the life of the entity and means
	 * nothing after it, which is exactly the lifetime being recorded.
	 */
	public static final class Row {
		public final long t;
		public final int id;
		public final int type;
		public final int dim;
		public final double x;
		public final double y;
		public final double z;
		public final float yaw;

		Row(long t, int id, int type, int dim, double x, double y, double z, float yaw) {
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

	private static final List<Row> ROWS = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Type ids are interned rather than repeated.
	 *
	 * "minecraft:zombified_piglin" is 26 bytes and would otherwise
	 * appear on every row of every sample - more bytes than the
	 * position it decorates. The table is a few dozen entries.
	 */
	private static final Map<String, Integer> TYPE_IDS = new HashMap<>();
	private static final List<String> TYPE_NAMES = Collections.synchronizedList(new ArrayList<>());

	private static int tickCounter = 0;

	private EntityTracks() {
	}

	public static void reset() {
		ROWS.clear();
		tickCounter = 0;
		synchronized (TYPE_IDS) {
			TYPE_IDS.clear();
			TYPE_NAMES.clear();
		}
	}

	public static List<Row> rows() {
		synchronized (ROWS) {
			return new ArrayList<>(ROWS);
		}
	}

	public static List<String> typeNames() {
		synchronized (TYPE_NAMES) {
			return new ArrayList<>(TYPE_NAMES);
		}
	}

	/**
	 * Called from the replay recorder's tick, which already owns the
	 * in-match and not-a-replay guards.
	 */
	static void tick(MinecraftClient client, ClientPlayerEntity player, long elapsed, int dim) {
		if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		if (ROWS.size() >= MAX_ROWS || client.world == null) {
			return;
		}

		// Nearest first, so the cap drops the far ones rather than
		// whichever the iterator happened to reach last.
		List<Entity> near = new ArrayList<>();
		for (Entity e : client.world.getEntities()) {
			if (e == player || !worthRecording(e)) {
				continue;
			}
			if (e.squaredDistanceTo(player) > RADIUS_SQ) {
				continue;
			}
			near.add(e);
		}
		near.sort((a, b) -> Double.compare(a.squaredDistanceTo(player), b.squaredDistanceTo(player)));

		int n = Math.min(near.size(), MAX_PER_SAMPLE);
		for (int i = 0; i < n; i++) {
			Entity e = near.get(i);
			ROWS.add(new Row(elapsed, e.getEntityId(), typeIndex(e), dim,
					e.getX(), e.getY(), e.getZ(), e.yaw));
		}
	}

	/**
	 * The things a viewer would notice were missing.
	 *
	 * Mobs and other players because they are the match; items,
	 * projectiles, falling blocks and TNT because a pearl in flight or
	 * a dropped rod is a beat somebody rewinds to find. Everything else
	 * - area effect clouds, experience orbs, boats nobody is in - is
	 * noise at this sample rate.
	 */
	private static boolean worthRecording(Entity e) {
		return e instanceof LivingEntity
				|| e instanceof ItemEntity
				|| e instanceof ProjectileEntity
				|| e instanceof FallingBlockEntity
				|| e instanceof TntEntity;
	}

	/**
	 * The type id, and for a dropped item WHICH item.
	 *
	 * An ItemEntity with no stack renders as nothing at all, so a floor
	 * of dropped blaze rods would play back as empty ground - the exact
	 * detail somebody rewinds to count. The item id is appended to the
	 * type name rather than added as a ninth column because the type
	 * table is interned: "minecraft:item|minecraft:blaze_rod" is stored
	 * once no matter how many rods are on the floor, where a per-row
	 * column would be paid for on every row of every sample.
	 */
	private static int typeIndex(Entity e) {
		String id = Registry.ENTITY_TYPE.getId(e.getType()).toString();
		if (e instanceof ItemEntity) {
			ItemStack stack = ((ItemEntity) e).getStack();
			if (!stack.isEmpty()) {
				id = id + "|" + Registry.ITEM.getId(stack.getItem());
			}
		}
		synchronized (TYPE_IDS) {
			Integer known = TYPE_IDS.get(id);
			if (known != null) {
				return known;
			}
			int next = TYPE_NAMES.size();
			TYPE_IDS.put(id, next);
			TYPE_NAMES.add(id);
			return next;
		}
	}
}

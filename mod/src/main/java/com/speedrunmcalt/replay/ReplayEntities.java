package com.speedrunmcalt.replay;

import com.speedrunmcalt.net.ReplayData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redraws the mobs, items and projectiles that were actually there.
 *
 * The replay world generates identical terrain, structures and loot
 * from the seed, and then stands completely empty: SpawnHelper's
 * "is a player near enough" check uses a spectator-excluding predicate
 * and the viewer is a spectator, so nothing ever spawns. Everything
 * alive in a replay is drawn from here.
 *
 * ONE OBSERVER AT A TIME. Entity ids are assigned by each client
 * independently, so id 42 in one player's recording and id 42 in the
 * other's are unrelated entities - and a zombie both players could see
 * appears in both recordings, at two slightly different positions.
 * Merging the tracks would double every shared mob and scramble the
 * ids. So a replay renders exactly one player's entity track: the one
 * being watched. That is also the honest claim - this is what THEY
 * saw, including the ghast the other player never had line of sight
 * on.
 *
 * INTERPOLATED, because the tracks are 2Hz. Drawn raw, a walking
 * zombie would teleport half a second at a time. Positions and facing
 * are lerped between the bracketing samples; an entity that is missing
 * from the later sample is held at its last position rather than
 * lerped toward nothing, because "it despawned or went out of range"
 * is not a movement.
 */
public final class ReplayEntities {
	/**
	 * Client entity ids for our fakes, counting DOWN from a long way
	 * below zero.
	 *
	 * The ghost players already count down from -1, and a collision
	 * would have one overwrite the other in the client's entity table -
	 * a mob would silently replace a player body. Starting a million
	 * apart is cheaper than coordinating two counters.
	 */
	private static int nextId = -1_000_000;

	/** Recording-side entity id -> the client entity standing in for it. */
	private static final Map<Integer, Entity> LIVE = new HashMap<>();

	/** Sample times in order, for the binary search. */
	private static List<Long> times = new ArrayList<>();

	/** Sample time -> every row recorded at that instant. */
	private static Map<Long, List<ReplayData.EntityRow>> blocks = new HashMap<>();

	/** Which track the index was built from, so a switch rebuilds it. */
	private static String indexedFor = null;

	private ReplayEntities() {
	}

	public static void reset() {
		clear();
		times = new ArrayList<>();
		blocks = new HashMap<>();
		indexedFor = null;
	}

	/** Removes every stand-in. Called on dimension change and on exit. */
	public static void clear() {
		for (Entity e : LIVE.values()) {
			e.remove();
		}
		LIVE.clear();
	}

	/**
	 * Groups a track's rows by the instant they were taken.
	 *
	 * Every row from one recorder tick carries the same timestamp,
	 * which is what makes this a clean bucketing rather than a
	 * tolerance match.
	 */
	private static void index(String uuid, ReplayData.Track track) {
		times = new ArrayList<>();
		blocks = new HashMap<>();
		indexedFor = uuid;
		if (track == null || track.entities == null) {
			return;
		}
		for (ReplayData.EntityRow r : track.entities) {
			List<ReplayData.EntityRow> b = blocks.get(r.t);
			if (b == null) {
				b = new ArrayList<>();
				blocks.put(r.t, b);
				times.add(r.t);
			}
			b.add(r);
		}
		java.util.Collections.sort(times);
	}

	/**
	 * Draws the world as the watched player saw it at this moment.
	 *
	 * @param uuid  whose track to render - the player being watched
	 * @param dim   the dimension the CAMERA is in; rows from elsewhere
	 *              are skipped, because nether coordinates drawn in the
	 *              overworld land eight times too far out
	 */
	public static void drive(MinecraftClient client, String uuid, ReplayData.Track track,
			long atMillis, int dim) {
		if (client.world == null) {
			clear();
			return;
		}
		if (!java.util.Objects.equals(uuid, indexedFor)) {
			clear();
			index(uuid, track);
		}
		if (times.isEmpty()) {
			return;
		}

		int i = floorIndex(atMillis);
		long t0 = times.get(i);
		List<ReplayData.EntityRow> a = blocks.get(t0);
		List<ReplayData.EntityRow> b = (i + 1 < times.size()) ? blocks.get(times.get(i + 1)) : null;
		long t1 = (i + 1 < times.size()) ? times.get(i + 1) : t0;

		double alpha = (t1 > t0) ? (double) (atMillis - t0) / (double) (t1 - t0) : 0.0;
		alpha = Math.max(0.0, Math.min(1.0, alpha));

		Map<Integer, ReplayData.EntityRow> next = new HashMap<>();
		if (b != null) {
			for (ReplayData.EntityRow r : b) {
				next.put(r.id, r);
			}
		}

		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (ReplayData.EntityRow r : a) {
			if (r.dim != dim) {
				continue;
			}
			ReplayData.EntityRow n = next.get(r.id);
			double x = r.x;
			double y = r.y;
			double z = r.z;
			float yaw = r.yaw;
			if (n != null && n.dim == r.dim) {
				x = r.x + (n.x - r.x) * alpha;
				y = r.y + (n.y - r.y) * alpha;
				z = r.z + (n.z - r.z) * alpha;
				yaw = r.yaw + (float) (shortestAngle(r.yaw, n.yaw) * alpha);
			}

			Entity e = LIVE.get(r.id);
			if (e == null || e.world != client.world) {
				e = spawn(client, track, r);
				if (e == null) {
					continue;
				}
				LIVE.put(r.id, e);
			}
			seen.add(r.id);

			// Previous position too, for the same reason the camera
			// needs it: without it the renderer interpolates from
			// wherever the entity was last frame and every mob trails
			// its real position permanently.
			e.updatePositionAndAngles(x, y, z, yaw, 0f);
			e.prevX = x;
			e.prevY = y;
			e.prevZ = z;
			e.yaw = yaw;
			e.prevYaw = yaw;
			if (e instanceof net.minecraft.entity.LivingEntity) {
				net.minecraft.entity.LivingEntity le = (net.minecraft.entity.LivingEntity) e;
				le.headYaw = yaw;
				le.prevHeadYaw = yaw;
				le.bodyYaw = yaw;
				le.prevBodyYaw = yaw;
			}
		}

		// Anything not in this sample is gone - despawned, killed, or
		// walked out of the recorded radius. Leaving it standing there
		// would turn a zombie that wandered off into a statue.
		java.util.Iterator<Map.Entry<Integer, Entity>> it = LIVE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Entity> en = it.next();
			if (!seen.contains(en.getKey())) {
				en.getValue().remove();
				it.remove();
			}
		}
	}

	/** Largest index whose time is <= atMillis, clamped into range. */
	private static int floorIndex(long atMillis) {
		int lo = 0;
		int hi = times.size() - 1;
		if (atMillis <= times.get(0)) {
			return 0;
		}
		if (atMillis >= times.get(hi)) {
			return hi;
		}
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (times.get(mid) <= atMillis) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	/** Degrees, taking the short way round, so 350 -> 10 is +20 not -340. */
	private static double shortestAngle(float from, float to) {
		double d = (to - from) % 360.0;
		if (d > 180.0) {
			d -= 360.0;
		}
		if (d < -180.0) {
			d += 360.0;
		}
		return d;
	}

	private static Entity spawn(MinecraftClient client, ReplayData.Track track,
			ReplayData.EntityRow r) {
		if (r.type < 0 || r.type >= track.typeNames.size()) {
			return null;
		}
		String name = track.typeNames.get(r.type);

		// "minecraft:item|minecraft:blaze_rod" - the recorder appends
		// the item id, because an ItemEntity with an empty stack
		// renders as nothing and a floor of rods would play back as
		// bare ground.
		String itemId = null;
		int bar = name.indexOf('|');
		if (bar >= 0) {
			itemId = name.substring(bar + 1);
			name = name.substring(0, bar);
		}

		EntityType<?> type = EntityType.get(name).orElse(null);
		if (type == null) {
			return null;
		}
		Entity e = type.create(client.world);
		if (e == null) {
			return null;
		}
		if (e instanceof ItemEntity && itemId != null) {
			net.minecraft.item.Item item = Registry.ITEM.get(new Identifier(itemId));
			((ItemEntity) e).setStack(new ItemStack(item));
		}
		// Client-side only: the replay world's server knows nothing
		// about these, and addEntity is how the client normally
		// materialises what the server tells it about.
		client.world.addEntity(nextId--, e);
		return e;
	}
}

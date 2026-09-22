package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.LootTopUp;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * TEMPORARY - checks that loot top-up meets its guarantee and does so
 * identically on repeated runs. Prints the final iron in the type's own
 * unit plus a per-container digest, so two runs can be diffed exactly
 * rather than only compared on a total.
 */
public class LootCheckHook implements DedicatedServerModInitializer {
	private static StructureFeature<?> featureFor(LootTopUp.SeedType type) {
		switch (type) {
			case VILLAGE: return StructureFeature.VILLAGE;
			case DESERT_TEMPLE: return StructureFeature.DESERT_PYRAMID;
			case RUINED_PORTAL: return StructureFeature.RUINED_PORTAL;
			case SHIPWRECK: return StructureFeature.SHIPWRECK;
			case BURIED_TREASURE: return StructureFeature.BURIED_TREASURE;
			default: return null;
		}
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Files.exists(Paths.get("portalplace.txt"))) {
				return; // portal-placement run owns this boot
			}
			if (Files.exists(Paths.get("bastion.txt"))) {
				return; // bastion-timing run owns this boot
			}
			if (Files.exists(Paths.get("ravine.txt"))) {
				return; // ravine-check run owns this boot
			}
			if (Files.exists(Paths.get("rppredict.txt"))) {
				return; // rp-predict run owns this boot
			}
			if (Files.exists(Paths.get("rpverify.txt"))) {
				return; // rp-verify run owns this boot
			}
			if (Files.exists(Paths.get("portal.txt"))) {
				return; // portal-diag run owns this boot
			}
			if (!Files.exists(Paths.get("loot.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("loot.txt")), "UTF-8")
						.trim().split("\\s+");
				int sx = Integer.parseInt(p[1]);
				int sz = Integer.parseInt(p[2]);
				LootTopUp.SeedType type = LootTopUp.SeedType.valueOf(p[3].toUpperCase());

				ServerWorld world = server.getWorld(World.OVERWORLD);
				long seed = Long.parseLong(p[0]);
				com.speedrunmcalt.seed.VillageSmith.Village v =
						com.speedrunmcalt.seed.VillageSmith.inspect(
								server.getStructureManager(), seed, featureFor(type), sx, sz);
				if (!v.exists()) {
					SpeedrunMcAlt.LOGGER.error("[loot] no {} structure at {},{}", type, sx, sz);
					server.stop(false);
					return;
				}

				SpeedrunMcAlt.LOGGER.info("[loot] box x[{}..{}] y[{}..{}] z[{}..{}]",
						v.predictedBox.minX, v.predictedBox.maxX, v.predictedBox.minY, v.predictedBox.maxY, v.predictedBox.minZ, v.predictedBox.maxZ);
				// What containers exist there at all, before filtering by
				// loot table - distinguishes "wrong box" from "right box,
				// wrong table name".
				for (BlockPos cp : ContainerScan.find(world, v.xzBox())) {
					net.minecraft.block.entity.LootableContainerBlockEntity lc =
							(net.minecraft.block.entity.LootableContainerBlockEntity)
									world.getBlockEntity(cp);
					SpeedrunMcAlt.LOGGER.info("[loot] raw container {},{},{} table={}",
							cp.getX(), cp.getY(), cp.getZ(),
							lc == null ? "?" : ((com.speedrunmcalt.mixin.LootableContainerAccessor) lc)
									.speedrunmcalt$getLootTableId());
				}

				// Mirror the real setup path: the shipwreck chest
				// normalisation runs BEFORE the top-up there, so a test
				// that skipped it would be testing a different thing.
				if (type == LootTopUp.SeedType.SHIPWRECK) {
					com.speedrunmcalt.world.ShipwreckChests.ensureAllThree(world, v.xzBox(), seed);
					for (BlockPos cp : ContainerScan.find(world, v.xzBox())) {
						net.minecraft.block.entity.LootableContainerBlockEntity lc =
								(net.minecraft.block.entity.LootableContainerBlockEntity)
										world.getBlockEntity(cp);
						SpeedrunMcAlt.LOGGER.info("[loot] after-normalise {},{},{} table={}",
								cp.getX(), cp.getY(), cp.getZ(),
								lc == null ? "?" : ((com.speedrunmcalt.mixin.LootableContainerAccessor) lc)
										.speedrunmcalt$getLootTableId());
					}
				}

				boolean ok = LootTopUp.apply(world, type, v.xzBox(), seed);

				// Independent recount, so a bug in apply() cannot mark
				// its own homework.
				List<BlockPos> containers = ContainerScan.find(world, v.xzBox());
				int ingots = 0, nuggets = 0, food = 0, obsidian = 0;
				boolean light = false;
				for (BlockPos pos : containers) {
					LootableContainerBlockEntity c =
							(LootableContainerBlockEntity) world.getBlockEntity(pos);
					if (c == null) continue;
					int cIngots = 0, cNuggets = 0;
					for (int i = 0; i < c.size(); i++) {
						ItemStack st = c.getStack(i);
						if (st.isEmpty()) continue;
						if (st.getItem() == Items.IRON_INGOT) cIngots += st.getCount();
						if (st.getItem() == Items.IRON_NUGGET) cNuggets += st.getCount();
						if (st.getItem().isFood()) food += st.getCount();
						if (st.getItem() == Items.OBSIDIAN) obsidian += st.getCount();
						if (st.getItem() == Items.FLINT_AND_STEEL
								|| st.getItem() == Items.FIRE_CHARGE) light = true;
					}
					ingots += cIngots;
					nuggets += cNuggets;
					SpeedrunMcAlt.LOGGER.info("[loot] container {},{},{} ingots={} nuggets={}",
							pos.getX(), pos.getY(), pos.getZ(), cIngots, cNuggets);
				}
				SpeedrunMcAlt.LOGGER.info(
						"[loot] RESULT type={} ok={} containers={} ingots={} nuggets={} food={}"
								+ " obsidian={} light={} min={} unit={}",
						type, ok, containers.size(), ingots, nuggets, food,
						obsidian, light, type.minIron, type.unit);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[loot] failed", e);
			}
			server.stop(false);
		});
	}
}

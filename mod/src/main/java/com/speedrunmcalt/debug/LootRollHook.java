package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * TEMPORARY - rolls a loot table with a given seed, to measure what a
 * chest WOULD have contained.
 *
 * An unopened chest stores its table and its LootTableSeed and nothing
 * else: the items do not exist until someone opens it. So a save full
 * of unopened chests looks empty, and a save of opened ones shows only
 * what the player left behind - thirteen opened the incumbent shipwreck chests
 * held no food at all, because the food is exactly what gets taken.
 *
 * Rolling the stored seed recovers the real contents exactly.
 *
 * Reads "table seed" per line from lootroll.txt.
 */
public class LootRollHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("lootroll.txt"))) {
				return;
			}
			try (FileWriter out = new FileWriter("lootroll.csv", true)) {
				ServerWorld world = server.getWorld(World.OVERWORLD);
				for (String line : Files.readAllLines(Paths.get("lootroll.txt"))) {
					if (line.trim().isEmpty()) {
						continue;
					}
					String[] p = line.trim().split("\\s+");
					Identifier id = new Identifier(p[0]);
					long seed = Long.parseLong(p[1]);

					LootTable table = server.getLootManager().getTable(id);
					// CHEST context requires a position parameter; the
					// value is irrelevant to a shipwreck roll but the
					// builder refuses to build without it.
					LootContext ctx = new LootContext.Builder(world)
							.random(seed)
							.parameter(net.minecraft.loot.context.LootContextParameters.POSITION,
									net.minecraft.util.math.BlockPos.ORIGIN)
							.build(LootContextTypes.CHEST);
					List<ItemStack> stacks = table.generateLoot(ctx);

					int hunger = 0;
					StringBuilder items = new StringBuilder();
					for (ItemStack st : stacks) {
						if (st.isEmpty()) {
							continue;
						}
						if (items.length() > 0) {
							items.append(' ');
						}
						items.append(st.getCount()).append('x')
								.append(net.minecraft.util.registry.Registry.ITEM
										.getId(st.getItem()).getPath());
						hunger += hungerOf(st);
					}
					SpeedrunMcAlt.LOGGER.info("[lootroll] {} seed={} hunger={} :: {}",
							id, seed, hunger, items);
					out.write(id + "," + seed + "," + hunger + "," + items + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[lootroll] failed", e);
			}
			server.stop(false);
		});
	}

	/** Same accounting LootTopUp uses: hunger points, hay and wheat included. */
	private static int hungerOf(ItemStack st) {
		net.minecraft.item.Item item = st.getItem();
		int n = st.getCount();
		if (item == Items.HAY_BLOCK) {
			return n * 15;
		}
		if (item == Items.WHEAT) {
			return (n / 3) * 5;
		}
		if (!item.isFood() || item == Items.CHICKEN || item == Items.PUFFERFISH
				|| item == Items.POISONOUS_POTATO || item == Items.SPIDER_EYE) {
			return 0;
		}
		net.minecraft.item.FoodComponent fc = item.getFoodComponent();
		return fc == null ? 0 : fc.getHunger() * n;
	}
}

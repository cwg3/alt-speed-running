package com.speedrunmcalt.world;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.ChunkGeneratorType;
import net.minecraft.world.gen.chunk.SurfaceChunkGenerator;
import net.minecraft.world.level.LevelInfo;

/**
 * Creates and loads a singleplayer world for a ranked match, with the
 * overworld and nether generated from INDEPENDENT seeds - deliberate
 * (see seed-filter/seedfilter.c): it breaks the vanilla correlation
 * between overworld and nether terrain that "Divine Travel"-style
 * strategies exploit to infer nether structure locations from overworld
 * generation.
 *
 * No mixin needed. Vanilla's own GeneratorOptions/DimensionOptions API
 * already supports an independently-seeded chunk generator per
 * dimension - the vanilla "Create World" screen just never exposes that
 * capability through its UI, always building every dimension from one
 * seed (DimensionType.method_28517). We only need to compose the same
 * public APIs it uses ourselves, with a different seed for the nether's
 * generator specifically.
 */
public final class MatchWorldCreator {
	private MatchWorldCreator() {
	}

	public static void createMatchWorld(MinecraftClient client, String worldName, long overworldSeed, long netherSeed) {
		// Vanilla's default dimension set, all built from overworldSeed -
		// we only want its Nether entry's DimensionType (unchanged) and End
		// entry (unchanged; only overworld/nether correlation matters for
		// the anti-inference reasoning above).
		SimpleRegistry<DimensionOptions> defaults = DimensionType.method_28517(overworldSeed);
		DimensionOptions defaultNether = defaults.get(DimensionOptions.NETHER);
		DimensionOptions defaultEnd = defaults.get(DimensionOptions.END);

		// Same construction vanilla's own (private) DimensionType.createNetherGenerator
		// uses, just with netherSeed instead of overworldSeed.
		SurfaceChunkGenerator netherGenerator = new SurfaceChunkGenerator(
				MultiNoiseBiomeSource.Preset.NETHER.getBiomeSource(netherSeed),
				netherSeed,
				ChunkGeneratorType.Preset.NETHER.getChunkGeneratorType());

		SimpleRegistry<DimensionOptions> dimensions =
				new SimpleRegistry<>(Registry.DIMENSION_OPTIONS, Lifecycle.experimental());
		dimensions.add(DimensionOptions.OVERWORLD, new DimensionOptions(
				DimensionType::getOverworldDimensionType,
				GeneratorOptions.createOverworldGenerator(overworldSeed)));
		dimensions.add(DimensionOptions.NETHER, new DimensionOptions(
				defaultNether.getDimensionTypeSupplier(), netherGenerator));
		dimensions.add(DimensionOptions.END, defaultEnd);
		dimensions.markLoaded(DimensionOptions.OVERWORLD);
		dimensions.markLoaded(DimensionOptions.NETHER);
		dimensions.markLoaded(DimensionOptions.END);

		GeneratorOptions generatorOptions = new GeneratorOptions(overworldSeed, true, false, dimensions);

		LevelInfo levelInfo = new LevelInfo(
				worldName,
				GameMode.SURVIVAL,
				false,
				Difficulty.NORMAL,
				false,
				new GameRules(),
				DataPackSettings.SAFE_MODE);

		client.method_29607(worldName, levelInfo, RegistryTracker.create(), generatorOptions);
	}
}

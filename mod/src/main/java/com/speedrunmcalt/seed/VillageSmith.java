package com.speedrunmcalt.seed;

import net.minecraft.structure.StructureManager;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.StructureConfig;
import net.minecraft.world.gen.chunk.SurfaceChunkGenerator;
import net.minecraft.world.gen.feature.ConfiguredStructureFeature;
import net.minecraft.world.gen.feature.StructureFeature;

/**
 * Builds a seed's village jigsaw without generating any chunks.
 *
 * Two things come out of this, and both matter:
 *
 * 1. Whether the village contains a smith. Measured yield is roughly
 *    one smith village in two, and only a smith's chest carries iron,
 *    so rejecting the rest up front avoids paying seconds of chunk
 *    generation for a seed that was never going to qualify. This costs
 *    about 67ms per seed against ~13s for a full generation.
 *
 * 2. The village's actual bounding box. This is the part that was
 *    silently wrong before: the loot sweep used a fixed radius of four
 *    chunks around the position cubiomes predicts, and villages sprawl
 *    well past that. Of 17 seeds whose jigsaw contained a smith but
 *    which measured zero iron, 13 had never had their smith chest
 *    reached at all - the sweep simply stopped short. Those were false
 *    zeros, and they made the iron yield look far worse than it is.
 *    Driving the sweep from the real box removes the guess.
 */
public final class VillageSmith {
	/**
	 * Jigsaw piece names for the three buildings that carry an iron
	 * chest. Matched against the piece's template id, which appears in
	 * the piece's toString for single pool elements.
	 */
	private static final String[] SMITH_PIECES = {
			"armorer",
			"weaponsmith",
			"toolsmith",
	};

	private VillageSmith() {
	}

	public static final class Village {
		public final boolean hasSmith;
		/**
		 * Centre of the smith's own jigsaw piece, or null.
		 *
		 * A village's bounding box can be 107 by 174 blocks, so knowing
		 * only that a smith EXISTS is close to useless for finding it -
		 * a player can stand in the village and still be a hundred
		 * blocks from the building. The piece already carries its own
		 * box; this just stops throwing it away.
		 */
		public net.minecraft.util.math.BlockPos smithPos;
		/**
		 * The predicted structure box. X AND Z ARE EXACT; Y IS NOT.
		 *
		 * The prediction runs the structure's start before terrain
		 * exists, so its vertical position is where the structure would
		 * sit on flat ground. Real generation then moves it to fit the
		 * landscape. Measured errors, all from this project:
		 *
		 *   desert temple  predicted y[64..78]  chests at y53
		 *   shipwreck      predicted y[90..98]  chest  at y39
		 *
		 * Fifty blocks out, and wrong in a direction that reads as
		 * plausible. Three separate features trusted this field and
		 * quietly did nothing: a mob-suppression box hovering above its
		 * temple, a chest scan looking in empty air, and a loot
		 * verifier reporting confident false negatives.
		 *
		 * Named "predicted" so that using it is a decision. If you only
		 * need the footprint - which is almost always the case - call
		 * {@link #xzBox()} instead.
		 */
		public final BlockBox predictedBox;

		Village(boolean hasSmith, BlockBox box) {
			this.hasSmith = hasSmith;
			this.predictedBox = box;
		}

		/**
		 * The footprint, with the unreliable Y replaced by the whole
		 * world column.
		 *
		 * This is the safe default. ContainerScan already ignores Y and
		 * reads chunk block-entity maps, so a full-height box costs it
		 * nothing; anything that genuinely needs a vertical bound
		 * should derive one from what it FINDS in the world - the way
		 * the temple quiet zone is now built from its chests - rather
		 * than from this prediction.
		 */
		public BlockBox xzBox() {
			if (predictedBox == null) {
				return null;
			}
			return new BlockBox(
					predictedBox.minX, 0, predictedBox.minZ,
					predictedBox.maxX, 255, predictedBox.maxZ);
		}

		public boolean exists() {
			return predictedBox != null;
		}
	}

	private static final Village NONE = new Village(false, null);

	/**
	 * @param villageX/villageZ the village position cubiomes predicted
	 */
	/**
	 * The nether generator, built the same way the match world builds
	 * it so structure lookups agree with what actually generates.
	 */
	private static SurfaceChunkGenerator netherGenerator(long seed) {
		return new SurfaceChunkGenerator(
				net.minecraft.world.biome.source.MultiNoiseBiomeSource.Preset.NETHER
						.getBiomeSource(seed),
				seed,
				net.minecraft.world.gen.chunk.ChunkGeneratorType.Preset.NETHER
						.getChunkGeneratorType());
	}

	/** Diagnostic only: the jigsaw piece names this predicts. */
	public static java.util.List<String> debugPieces(StructureManager structureManager,
			long seed, int villageX, int villageZ) {
		java.util.List<String> names = new java.util.ArrayList<>();
		StructureStart<?> start = buildStart(structureManager, seed,
				StructureFeature.VILLAGE, villageX, villageZ, false);
		if (start != null && start.hasChildren()) {
			for (StructurePiece piece : start.getChildren()) {
				names.add(piece.toString());
			}
		}
		return names;
	}

	public static Village inspect(StructureManager structureManager, long seed,
			int villageX, int villageZ) {
		return inspect(structureManager, seed, StructureFeature.VILLAGE, villageX, villageZ);
	}

	/**
	 * Same for any structure, so the match world can learn a
	 * structure's bounding box without depending on the world's
	 * structure accessor - that lookup only answers once the chunks
	 * around the structure are already generated, which is not true at
	 * world creation and fails by returning null rather than loading
	 * them. This path is pure worldgen maths and was verified piece for
	 * piece against a real generated village.
	 */
	public static Village inspect(StructureManager structureManager, long seed,
			StructureFeature<?> feature, int x, int z) {
		return inspect(structureManager, seed, feature, x, z, false);
	}

	/**
	 * @param nether build against the nether generator instead of the
	 *               overworld one. Required for bastions and
	 *               fortresses: the structure is looked up through the
	 *               biome at its position, so asking an overworld
	 *               generator about a bastion finds nothing at all and
	 *               reports the structure as absent rather than
	 *               failing. That is exactly what happened - the
	 *               bastion top-up logged "no bastion" for every seed
	 *               and quietly did nothing.
	 */
	public static Village inspect(StructureManager structureManager, long seed,
			StructureFeature<?> feature, int x, int z, boolean nether) {
		StructureStart<?> start = buildStart(structureManager, seed, feature, x, z, nether);
		if (start == null || !start.hasChildren()) {
			return NONE;
		}
		boolean smith = false;
		net.minecraft.util.math.BlockPos smithPos = null;
		for (StructurePiece piece : start.getChildren()) {
			String name = piece.toString();
			for (String candidate : SMITH_PIECES) {
				if (name.contains(candidate)) {
					smith = true;
					BlockBox pieceBox = piece.getBoundingBox();
					smithPos = new net.minecraft.util.math.BlockPos(
							(pieceBox.minX + pieceBox.maxX) / 2,
							(pieceBox.minY + pieceBox.maxY) / 2,
							(pieceBox.minZ + pieceBox.maxZ) / 2);
					break;
				}
			}
			if (smith) {
				break;
			}
		}
		Village village = new Village(smith, start.getBoundingBox());
		village.smithPos = smithPos;
		return village;
	}

	private static StructureStart<?> buildStart(StructureManager structureManager, long seed,
			StructureFeature<?> feature, int villageX, int villageZ, boolean nether) {
		SurfaceChunkGenerator generator = nether
				? netherGenerator(seed)
				: GeneratorOptions.createOverworldGenerator(seed);
		BiomeSource biomeSource = generator.getBiomeSource();

		ChunkPos chunkPos = new ChunkPos(villageX >> 4, villageZ >> 4);
		// Biome lookup is on the noise grid (quarter-block resolution),
		// sampled at the middle of the chunk.
		Biome biome = biomeSource.getBiomeForNoiseGen(
				(chunkPos.x << 2) + 2, 0, (chunkPos.z << 2) + 2);

		ConfiguredStructureFeature<?, ?> configured = null;
		for (ConfiguredStructureFeature<?, ?> candidate : biome.method_28413()) {
			if (candidate.field_24835 == feature) {
				configured = candidate;
				break;
			}
		}
		if (configured == null) {
			return null; // biome has no villages at all
		}

		StructureConfig structureConfig = generator.getConfig().method_28600(feature);
		if (structureConfig == null) {
			return null;
		}

		return configured.method_28622(
				generator, biomeSource, structureManager, seed, chunkPos, biome, 0, structureConfig);
	}
}

package com.speedrunmcalt.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes a chunk's block entities, including ones not yet built.
 *
 * A freshly generated chunk keeps most of its block entities as packed
 * NBT in pendingBlockEntityTags and only instantiates them when
 * something asks. getBlockEntityPositions() lists only the instantiated
 * ones, so a newly generated village looks almost empty.
 *
 * The first workaround for that was to scan block STATES for container
 * blocks instead - correct, but brutally expensive: a village-sized box
 * over a full Y column is about 9.4 million reads, each allocating a
 * BlockPos. That crashed the real client. The launcher runs an x86
 * Java 8 JVM under Rosetta on Apple Silicon, and the JIT pressure from
 * that loop killed it with a SIGBUS inside nmethodLocker. It never
 * showed up in development because the dev runtime is a native ARM Java
 * 21, which absorbed it.
 *
 * Reading both maps gives the same answer for the cost of iterating a
 * few dozen entries.
 */
@Mixin(WorldChunk.class)
public interface WorldChunkAccessor {
	@Accessor("pendingBlockEntityTags")
	Map<BlockPos, CompoundTag> speedrunmcalt$getPendingBlockEntityTags();

	@Accessor("blockEntities")
	Map<BlockPos, BlockEntity> speedrunmcalt$getBlockEntities();
}

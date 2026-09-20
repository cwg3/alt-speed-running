package com.speedrunmcalt.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

// World.random is `public final` - Mixin's @Mutable accessor lets us
// write to it anyway (operates at the bytecode level, not subject to
// javac's final-field enforcement), needed by SpawnerDeterminismMixin
// to temporarily substitute a deterministic per-spawner random source.
@Mixin(World.class)
public interface WorldRandomAccessor {
	@Accessor("random")
	@Mutable
	void speedrunmcalt$setRandom(Random random);
}

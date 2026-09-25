package com.speedrunmcalt.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches PlayerEntity's PLAYER_MODEL_PARTS tracked data.
 *
 * It is protected, and the value matters: it is the bitmask of which
 * skin OVERLAY layers to draw - hat, jacket, both sleeves, both
 * trouser legs, cape. A client-spawned body starts at 0, meaning none
 * of them, so a replay ghost wore the base layer of its skin and
 * nothing else. Anything a player put on the outer layer - a hood, a
 * jacket, red boxing gloves - simply was not there.
 */
@Mixin(PlayerEntity.class)
public interface PlayerModelPartsAccessor {
	@Accessor("PLAYER_MODEL_PARTS")
	static TrackedData<Byte> speedrunmcalt$modelParts() {
		throw new AssertionError("mixin did not apply");
	}
}

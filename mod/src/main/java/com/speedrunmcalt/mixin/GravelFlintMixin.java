package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.DropSchedule;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors flint from gravel between the two players.
 *
 * Vanilla is a flat 10% per gravel block with no floor, and a runner
 * needs exactly one piece for flint and steel. That makes it a coin
 * flip with a long tail: one player takes flint off their first block
 * while the other breaks fifteen and has none - same seed, same work,
 * minutes apart. DropSchedule gives both players the identical
 * sequence and guarantees flint inside ten breaks.
 *
 * The rate is unchanged: two in twenty is vanilla's 10%.
 *
 * Left alone deliberately:
 *
 *   - SILK TOUCH, which always yields gravel and never flint. A player
 *     who brings a silk touch shovel gets exactly what vanilla gives.
 *   - FORTUNE, which raises the flint chance. A runner breaking gravel
 *     for a first flint has no fortune shovel, so the case this exists
 *     for never involves it - and quietly overriding an enchantment a
 *     player earned would be a worse deviation than the one being
 *     fixed.
 */
@Mixin(Block.class)
public abstract class GravelFlintMixin {
	@Inject(method = "afterBreak", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$mirrorFlint(World world, PlayerEntity player, BlockPos pos,
			BlockState state, net.minecraft.block.entity.BlockEntity blockEntity,
			ItemStack tool, CallbackInfo ci) {
		if (state.getBlock() != Blocks.GRAVEL) {
			return;
		}
		if (world.isClient || !MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world, or the client copy - leave vanilla alone
		}
		if (tool != null) {
			if (EnchantmentHelper.getLevel(Enchantments.SILK_TOUCH, tool) > 0
					|| EnchantmentHelper.getLevel(Enchantments.FORTUNE, tool) > 0) {
				return; // the player's enchantment decides, not us
			}
		}

		// Runs on the server thread during block breaking; an exception
		// here is a crash report, not a log line. Falling through to
		// vanilla costs the mirroring and keeps the run.
		try {
			boolean gotFlint = DropSchedule.flint(MatchState.overworldSeed).next();
			Block.dropStack(world, pos,
					new ItemStack(gotFlint ? Items.FLINT : Items.GRAVEL, 1));
			// The tool still takes its damage and the stat still counts;
			// only the drop is replaced.
			player.incrementStat(net.minecraft.stat.Stats.MINED.getOrCreateStat(Blocks.GRAVEL));
			player.addExhaustion(0.005F);
			ci.cancel();
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error(
					"[speedrunmcalt] Flint schedule failed - falling back to vanilla", e);
		}
	}
}

package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SuspiciousStewItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Strips harmful effects from suspicious stew during a match.
 *
 * Suspicious stew is food a runner may pick up and eat under pressure,
 * and which flower it was made from is invisible on the item. Eating
 * one brewed from a wither rose applies wither; from a lily of the
 * valley, poison. Both can kill outright at low health, and whether a
 * player meets a harmful one at all is pure luck of the seed - the
 * other player, running the same route, may never see one.
 *
 * The effect is removed, not the item. A runner who eats it still gets
 * the food, and still gets any beneficial effect it carried. What goes
 * away is dying to a hidden property of a stack that looked like lunch.
 *
 * Vanilla's other soups - mushroom stew, rabbit stew, beetroot soup -
 * carry no effects at all, so there is nothing to do for them. The
 * spec line covering "craftable soups" is satisfied by that rather
 * than by code, which is worth saying so nobody goes looking for it.
 */
@Mixin(SuspiciousStewItem.class)
public abstract class SuspiciousStewMixin {
	@Inject(method = "finishUsing", at = @At("HEAD"))
	private void speedrunmcalt$removeHarmfulEffects(ItemStack stack, World world,
			LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world - leave vanilla alone
		}

		// Mutating the tag at HEAD means vanilla's own loop below reads
		// the filtered list and applies only what is left. No need to
		// reimplement effect application or cancel the method.
		try {
			CompoundTag tag = stack.getTag();
			if (tag == null || !tag.contains("Effects", 9)) {
				return;
			}
			ListTag effects = tag.getList("Effects", 10);
			ListTag kept = new ListTag();
			int removed = 0;
			for (int i = 0; i < effects.size(); i++) {
				CompoundTag entry = effects.getCompound(i);
				StatusEffect effect = StatusEffect.byRawId(entry.getByte("EffectId"));
				if (effect != null && effect.getType() == StatusEffectType.HARMFUL) {
					removed++;
					continue;
				}
				kept.add(entry);
			}
			if (removed > 0) {
				tag.put("Effects", kept);
			}
			// Logged even when nothing was removed. Most suspicious stew
			// is harmless, so a message only on the rare bad one leaves
			// the guarantee unobservable: a player ate a stew, no line
			// appeared, and there was no way to tell whether the hook
			// had run and found nothing or had never run at all. A
			// guarantee that cannot be seen working cannot be trusted to
			// work.
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Suspicious stew: {} effect(s), {} harmful removed",
					effects.size(), removed);
		} catch (Exception e) {
			// Eating food must never crash a run.
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Suspicious stew filter failed", e);
		}
	}
}

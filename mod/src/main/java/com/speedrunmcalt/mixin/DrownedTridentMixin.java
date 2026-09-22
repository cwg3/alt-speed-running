package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.DrownedEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.LocalDifficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps tridents out of drowned hands during a match.
 *
 * A thrown trident does 9 damage at range, through water, from a mob
 * the player often cannot see. On the ocean seed types - shipwreck and
 * buried treasure - the route runs through open water where there is no
 * cover and no way to close the distance quickly. One unlucky drowned
 * ends a run that was otherwise going fine, and the other player, on
 * the same seed, may never meet an armed one at all.
 *
 * This is a variance cut, not a difficulty cut. Drowned still spawn,
 * still chase, and still hit hard in melee. What goes away is dying to
 * a ranged attack that only one of the two players was ever offered.
 *
 * Only the trident is taken. A drowned that would have had one is left
 * empty-handed rather than handed a substitute, because giving it a
 * different weapon would be inventing a mob vanilla never generates.
 */
@Mixin(DrownedEntity.class)
public abstract class DrownedTridentMixin {
	@Inject(method = "initEquipment", at = @At("TAIL"))
	private void speedrunmcalt$noTridents(LocalDifficulty difficulty, CallbackInfo ci) {
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world - leave vanilla alone
		}
		DrownedEntity self = (DrownedEntity) (Object) this;
		ItemStack held = self.getEquippedStack(EquipmentSlot.MAINHAND);
		if (held != null && held.getItem() == Items.TRIDENT) {
			self.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
	}
}

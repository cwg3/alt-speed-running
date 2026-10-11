package com.speedrunmcalt.mixin;

import com.speedrunmcalt.menu.MenuMusic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.MusicSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps the vanilla menu tracks for alt's loop (MenuMusic). */
@Mixin(MinecraftClient.class)
public class MenuMusicTypeMixin {
	@Inject(method = "getMusicType", at = @At("RETURN"), cancellable = true)
	private void speedrunmcalt$menuMusic(CallbackInfoReturnable<MusicSound> cir) {
		cir.setReturnValue(MenuMusic.choose(cir.getReturnValue()));
	}
}

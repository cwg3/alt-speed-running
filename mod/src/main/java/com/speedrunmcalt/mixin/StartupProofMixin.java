package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Placeholder proving mixin injection works end to end before we write the
// real ones (split-event hooks, RNG standardization, nether seed override).
@Mixin(MinecraftServer.class)
public class StartupProofMixin {
	@Inject(at = @At("HEAD"), method = "loadWorld")
	private void onLoadWorld(CallbackInfo info) {
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] mixin injection confirmed working");
	}
}

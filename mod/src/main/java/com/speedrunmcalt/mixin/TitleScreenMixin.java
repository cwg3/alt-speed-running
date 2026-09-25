package com.speedrunmcalt.mixin;

import com.speedrunmcalt.menu.AltMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the entry point to the title screen.
 *
 * The mixin extends Screen so the inherited addButton/width/height are
 * available; fabric-screen-api-v1 would normally do this more cleanly
 * but it isn't part of the Fabric API version this mod pins for 1.16.1.
 *
 * Sits in the gap between the logo and Singleplayer, which is the only
 * free space in the layout. Vanilla runs from j = height/4 + 48
 * (Singleplayer) down to the options/quit row at j + 84; below that row
 * the window runs out before a button fits, and it ends up clipped
 * against the version text.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void speedrunmcalt$addAltButton(CallbackInfo ci) {
		int singleplayerTop = this.height / 4 + 48;
		this.addButton(new ButtonWidget(
				this.width / 2 - 100, singleplayerTop - 24, 200, 20,
				// The wordmark's own colours, on the one button that is
				// ours. Plain white it was indistinguishable from
				// Singleplayer and Multiplayer, which is the wrong
				// thing for the only row on this screen that is not
				// Mojang's.
				//
				// A Text with styled siblings works because TextRenderer
				// takes the colour passed by ButtonWidget only as a
				// DEFAULT - any run carrying its own style wins. Same
				// split as the menu: alt and the hyphen phosphor, the
				// two words purple.
				new LiteralText("alt  ").styled(st -> st.withColor(
								net.minecraft.text.TextColor.fromRgb(
										com.speedrunmcalt.menu.Palette.PHOSPHOR)))
						.append(new LiteralText("speed").styled(st -> st.withColor(
								net.minecraft.text.TextColor.fromRgb(
										com.speedrunmcalt.menu.Palette.PURPLE))))
						.append(new LiteralText("-").styled(st -> st.withColor(
								net.minecraft.text.TextColor.fromRgb(
										com.speedrunmcalt.menu.Palette.PHOSPHOR))))
						.append(new LiteralText("running").styled(st -> st.withColor(
								net.minecraft.text.TextColor.fromRgb(
										com.speedrunmcalt.menu.Palette.PURPLE)))),
				button -> this.client.openScreen(new AltMenuScreen(this))));
	}
}

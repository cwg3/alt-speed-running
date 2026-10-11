package com.speedrunmcalt.menu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * A button wearing the enchantment glint - the shimmer on enchanted
 * netherite.
 *
 * Not an imitation: it is vanilla's own glint texture, moved the way
 * 1.16.1 moves it (RenderPhase.setupGlintTexturing - one layer,
 * rotated 10 degrees, panned on 110000/30000 ms cycles at 8x speed)
 * and blended the way vanilla blends it (SRC_COLOR, ONE, so it only
 * ever adds light). Drawn in renderBg, which ButtonWidget calls after
 * the stone background and before the label, so the label stays
 * crisp on top of the shimmer instead of being washed out by it.
 */
public class GlintButton extends ButtonWidget {
	/**
	 * GUI pixels per texture repeat. Items squeeze the whole texture
	 * into 16px at 1/8 scale; on a 200px button that density turns to
	 * noise, so the stripes are spread out - tight enough to read as
	 * a busy shimmer rather than a few broad streaks.
	 */
	private static final float TILE = 24.0F;
	private static final float ANGLE = (float) Math.toRadians(10.0);

	public GlintButton(int x, int y, int width, int height, Text message, PressAction onPress) {
		super(x, y, width, height, message, onPress);
	}

	@Override
	protected void renderBg(MatrixStack matrices, MinecraftClient client, int mouseX, int mouseY) {
		long time = Util.getMeasuringTimeMs() * 8L;
		float panU = -(time % 110000L) / 110000.0F;
		float panV = (time % 30000L) / 30000.0F;
		// Brighter under the cursor, the same cue the stone gives.
		float strength = this.isHovered() ? 1.0F : 0.7F;

		client.getTextureManager().bindTexture(ItemRenderer.ENCHANTED_ITEM_GLINT);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_COLOR, GlStateManager.DstFactor.ONE);

		Matrix4f model = matrices.peek().getModel();
		BufferBuilder buf = Tessellator.getInstance().getBuffer();
		buf.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR_TEXTURE);
		corner(buf, model, 0, this.height, panU, panV, strength);
		corner(buf, model, this.width, this.height, panU, panV, strength);
		corner(buf, model, this.width, 0, panU, panV, strength);
		corner(buf, model, 0, 0, panU, panV, strength);
		Tessellator.getInstance().draw();

		RenderSystem.defaultBlendFunc();
		// Put the widget texture back: the vanilla code after renderBg
		// only draws text, but a subclass or mixin further down the
		// line may assume it is still bound.
		client.getTextureManager().bindTexture(WIDGETS_LOCATION);
	}

	private void corner(BufferBuilder buf, Matrix4f model, int dx, int dy,
			float panU, float panV, float strength) {
		float cos = (float) Math.cos(ANGLE);
		float sin = (float) Math.sin(ANGLE);
		float u = (dx * cos - dy * sin) / TILE + panU;
		float v = (dx * sin + dy * cos) / TILE + panV;
		buf.vertex(model, this.x + dx, this.y + dy, 0.0F)
				.color(strength, strength, strength, this.alpha)
				.texture(u, v)
				.next();
	}
}

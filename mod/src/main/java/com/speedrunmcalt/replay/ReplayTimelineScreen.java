package com.speedrunmcalt.replay;

import com.speedrunmcalt.menu.Palette;
import com.speedrunmcalt.net.ReplayData;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.List;

/**
 * The scrub bar, on Escape.
 *
 * The bar is banded by DIMENSION, which the trace already carries on
 * every sample - overworld, nether, end. That colouring is the useful
 * part: "where did the nether start" is the first question anyone asks
 * of a run, and it is answered by looking rather than seeking.
 */
public class ReplayTimelineScreen extends Screen {
	private static final int BAR_HEIGHT = 14;

	private static final int OVERWORLD_COLOUR = Palette.CYAN;
	private static final int NETHER_COLOUR = 0xC0392B;
	private static final int END_COLOUR = Palette.PURPLE;

	private int barLeft;
	private int barRight;
	private int barY;

	public ReplayTimelineScreen() {
		super(new LiteralText("Replay"));
	}

	@Override
	protected void init() {
		barLeft = 30;
		barRight = this.width - 30;
		barY = this.height - 70;

		int cx = this.width / 2;
		int row = this.height - 44;

		this.addButton(new ButtonWidget(cx - 156, row, 60, 20,
				new LiteralText(ReplayPlayback.paused() ? "Play" : "Pause"),
				b -> {
					ReplayPlayback.togglePause();
					b.setMessage(new LiteralText(ReplayPlayback.paused() ? "Play" : "Pause"));
				}));

		this.addButton(new ButtonWidget(cx - 92, row, 40, 20,
				new LiteralText("-10s"), b -> ReplayPlayback.nudge(-10_000)));
		this.addButton(new ButtonWidget(cx - 48, row, 40, 20,
				new LiteralText("+10s"), b -> ReplayPlayback.nudge(10_000)));

		this.addButton(new ButtonWidget(cx - 4, row, 60, 20,
				new LiteralText(speedLabel()),
				b -> {
					// 1x, 2x, 4x, 8x, then back. A slower-than-real
					// speed is for frame-by-frame work the arrow keys
					// already cover.
					float next = ReplayPlayback.speed() >= 8f ? 1f : ReplayPlayback.speed() * 2f;
					ReplayPlayback.setSpeed(next);
					b.setMessage(new LiteralText(speedLabel()));
				}));

		// Switching perspective is the feature, so it gets a button
		// rather than a hidden key.
		ReplayData data = ReplayPlayback.data();
		if (data != null) {
			List<String> uuids = new ArrayList<>(data.tracks.keySet());
			int x = cx + 60;
			for (String uuid : uuids) {
				ReplayData.Track t = data.tracks.get(uuid);
				boolean watching = uuid.equals(ReplayPlayback.watching());
				boolean hasTrace = t.samples != null && !t.samples.isEmpty();
				// Say WHY it cannot be clicked. A greyed button with a
				// name on it reads as broken; one that says "no replay"
				// reads as a fact about that match.
				String label = !hasTrace
						? t.username + " - none"
						: (watching ? "> " : "") + t.username;
				ButtonWidget b = new ButtonWidget(x, row, 96, 20,
						new LiteralText(label),
						btn -> {
							ReplayPlayback.watch(uuid);
							this.init(this.client, this.width, this.height);
						});
				// A player who never uploaded cannot be watched. Showing
				// the button greyed says that; hiding it would look like
				// the match had one player.
				b.active = hasTrace && !watching;
				this.addButton(b);
				x += 100;
			}
		}

		// Top right, clear of the control row and of the helper line at
		// the bottom - those two were drawn over each other.
		this.addButton(new ButtonWidget(this.width - 90, 10, 80, 20,
				new LiteralText("Exit replay"), b -> ReplayLauncher.exit(this.client)));
	}

	private static String speedLabel() {
		float s = ReplayPlayback.speed();
		return (s == (long) s ? String.valueOf((long) s) : String.valueOf(s)) + "x";
	}

	private static String clock(long ms) {
		long total = Math.max(0, ms / 1000);
		return String.format("%d:%02d", total / 60, total % 60);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Click anywhere on the bar to seek there.
		if (mouseY >= barY && mouseY <= barY + BAR_HEIGHT
				&& mouseX >= barLeft && mouseX <= barRight) {
			double f = (mouseX - barLeft) / (double) (barRight - barLeft);
			ReplayPlayback.seek((long) (f * ReplayPlayback.durationMillis()));
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);

		ReplayData data = ReplayPlayback.data();
		if (data == null) {
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("REPLAY"), this.width / 2, 16, Palette.DIM);

		ReplayData.Track t = data.tracks.get(ReplayPlayback.watching());
		if (t != null) {
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer,
					this.width / 2, 30,
					new String[] { "watching ", t.username },
					new int[] { Palette.DIM, Palette.YELLOW });
		}

		long duration = ReplayPlayback.durationMillis();
		long pos = ReplayPlayback.positionMillis();

		// The bar, banded by dimension.
		if (t != null && t.samples != null && !t.samples.isEmpty() && duration > 0) {
			int width = barRight - barLeft;
			for (int px = 0; px < width; px++) {
				long at = (long) ((px / (double) width) * duration);
				int dim = dimensionAt(t.samples, at);
				int colour = dim == 1 ? NETHER_COLOUR : dim == 2 ? END_COLOUR : OVERWORLD_COLOUR;
				fill(matrices, barLeft + px, barY, barLeft + px + 1, barY + BAR_HEIGHT,
						0xFF000000 | colour);
			}
			// Playhead.
			int head = barLeft + (int) ((pos / (double) duration) * width);
			fill(matrices, head - 1, barY - 3, head + 1, barY + BAR_HEIGHT + 3, 0xFFFFFFFF);
		}

		this.textRenderer.drawWithShadow(matrices, clock(pos), barLeft, barY - 12, Palette.CYAN);
		String total = clock(duration);
		this.textRenderer.drawWithShadow(matrices, total,
				barRight - this.textRenderer.getWidth(total), barY - 12, Palette.DIM);

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("click the bar to seek  -  Escape to resume watching"),
				this.width / 2, this.height - 18, Palette.DIM);

		super.render(matrices, mouseX, mouseY, delta);
	}

	private static int dimensionAt(List<ReplayData.Sample> samples, long t) {
		int dim = 0;
		for (ReplayData.Sample s : samples) {
			if (s.t > t) {
				break;
			}
			dim = s.dim;
		}
		return dim;
	}

	@Override
	public boolean isPauseScreen() {
		// The world keeps ticking behind this, which is what lets the
		// bar update while it is open.
		return false;
	}
}

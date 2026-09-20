package com.speedrunmcalt.menu;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/** The lobby: connection state, profile, and queue controls. */
public class AltMenuScreen extends Screen {
	// Phosphor green from the wordmark, so the screen and the brand match.
	private static final int PHOSPHOR = 0x56FF42;
	private static final int DIM = 0x2E8F3A;
	private static final int ALERT = 0xFF6B5B;

	private final Screen parent;
	private ButtonWidget actionButton;

	public AltMenuScreen(Screen parent) {
		super(new LiteralText("alt"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;

		actionButton = this.addButton(new ButtonWidget(
				cx - 100, this.height / 2, 200, 20,
				new LiteralText("..."), button -> onAction()));

		this.addButton(new ButtonWidget(
				cx - 100, this.height / 2 + 28, 200, 20,
				new LiteralText("Back"), button -> this.client.openScreen(parent)));

		// Kick off the handshake on open so the player doesn't have to
		// press anything just to see their own profile.
		if (AltSession.state() == AltSession.State.DISCONNECTED) {
			AltSession.connect();
		}
	}

	private void onAction() {
		switch (AltSession.state()) {
			case ERROR:
			case DISCONNECTED:
				AltSession.connect();
				break;
			case READY:
				if (Matchmaker.state() == Matchmaker.State.SEARCHING) {
					Matchmaker.cancel();
				} else {
					Matchmaker.search(this.client);
				}
				break;
			default:
				break; // connecting - nothing useful to do yet
		}
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int top = this.height / 2 - 70;

		drawCenteredText(matrices, this.textRenderer, new LiteralText("alt"), cx, top, PHOSPHOR);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("speed-running"), cx, top + 12, DIM);

		String status;
		String detail = null;
		int statusColor = PHOSPHOR;

		switch (AltSession.state()) {
			case DISCONNECTED:
				status = "not connected";
				statusColor = DIM;
				break;
			case CONNECTING:
				status = "verifying account...";
				statusColor = DIM;
				break;
			case ERROR:
				status = "connection failed";
				detail = AltSession.error();
				statusColor = ALERT;
				break;
			case READY:
			default:
				status = AltSession.username();
				detail = AltSession.skillRating() + " elo  -  "
						+ AltSession.seasonPoints() + " season points";
				break;
		}

		drawCenteredText(matrices, this.textRenderer, new LiteralText(status), cx, top + 34, statusColor);
		if (detail != null) {
			drawCenteredText(matrices, this.textRenderer, new LiteralText(fit(detail)), cx, top + 46, DIM);
		}

		// Queue state sits below the profile so both stay visible while
		// searching.
		if (AltSession.state() == AltSession.State.READY) {
			String queue = null;
			int queueColor = DIM;
			switch (Matchmaker.state()) {
				case SEARCHING:
					queue = "searching for opponent... " + Matchmaker.searchSeconds() + "s";
					break;
				case LAUNCHING:
					queue = "match found vs " + Matchmaker.opponent() + " - loading world";
					queueColor = PHOSPHOR;
					break;
				case ERROR:
					queue = "error: " + Matchmaker.error();
					queueColor = ALERT;
					break;
				default:
					break;
			}
			if (queue != null) {
				drawCenteredText(matrices, this.textRenderer, new LiteralText(fit(queue)), cx, top + 64, queueColor);
			}
		}

		actionButton.setMessage(new LiteralText(actionLabel()));
		actionButton.active = AltSession.state() != AltSession.State.CONNECTING
				&& Matchmaker.state() != Matchmaker.State.LAUNCHING;

		super.render(matrices, mouseX, mouseY, delta);
	}

	/**
	 * Clamps a line to the screen width. Backend and Mojang errors come
	 * through as raw JSON and ran off both edges of the screen without
	 * this.
	 */
	private String fit(String text) {
		int max = this.width - 40;
		if (this.textRenderer.getWidth(text) <= max) {
			return text;
		}
		return this.textRenderer.trimToWidth(text, max - this.textRenderer.getWidth("...")) + "...";
	}

	private String actionLabel() {
		switch (AltSession.state()) {
			case CONNECTING:
				return "Connecting...";
			case ERROR:
			case DISCONNECTED:
				return "Connect";
			case READY:
			default:
				switch (Matchmaker.state()) {
					case SEARCHING:
						return "Cancel Search";
					case LAUNCHING:
						return "Loading...";
					default:
						return "Find Match";
				}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

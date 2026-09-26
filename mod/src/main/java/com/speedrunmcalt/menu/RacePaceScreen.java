package com.speedrunmcalt.menu;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Pick a pace to race.
 *
 * WHY THIS EXISTS. With nobody else queued the ladder is not slow, it is
 * unplayable, and a player who finds it unplayable once does not come
 * back later to check. This is the thing to do instead.
 *
 * WHY IT IS HONEST ABOUT WHAT IT IS. A pace is a recorded time, not a
 * person: it cannot get a bad blind travel, cannot die, cannot choke the
 * dragon. It runs the same splits every time at a given tier, and a
 * player who worked that out for themselves after being told they had an
 * opponent would be right to feel misled. So the name says "pace", the
 * tiers are named after TIMES rather than skill words, and the screen
 * says plainly that nothing is rated and that the times repeat.
 *
 * Every one of those is cheaper to say here than to be caught not saying
 * later, and this project's whole pitch against the incumbent is that it
 * does not hide how it works.
 */
public class RacePaceScreen extends Screen {
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = Palette.CYAN;

	/**
	 * Mirrors PACE_TIERS in backend/lambda/lib/paceSchedule.ts.
	 *
	 * A second copy, and the honest reason is that the client has to draw
	 * buttons before it has spoken to anything. It is safe to drift in
	 * only one direction: the backend rejects an id it does not know and
	 * returns the real list with the error, so a stale client fails with
	 * a message rather than starting a race at a time nobody chose.
	 */
	private static final String[][] TIERS = {
		{ "p10", "10:00" },
		{ "p13", "13:00" },
		{ "p17", "17:00" },
		{ "p22", "22:00" },
		{ "p30", "30:00" },
	};

	private final Screen parent;

	public RacePaceScreen(Screen parent) {
		super(new LiteralText("Race a Pace"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.height / 2 - 30;

		// One row, because five tiers in a column would push Back off a
		// short window and the labels are short enough to read at 56px.
		int w = 56;
		int gap = 4;
		int total = TIERS.length * w + (TIERS.length - 1) * gap;
		int x = cx - total / 2;
		for (String[] tier : TIERS) {
			final String id = tier[0];
			this.addButton(new ButtonWidget(x, top, w, 20,
					new LiteralText(tier[1]),
					button -> {
						Matchmaker.racePace(this.client, id);
						// Straight back to the lobby, which already knows
						// how to show SEARCHING, an error, or a match
						// starting. A second place rendering those states
						// is a second place for them to disagree.
						this.client.openScreen(parent);
					}));
			x += w + gap;
		}

		this.addButton(new ButtonWidget(
				cx - 100, this.height / 2 + 40, 200, 20,
				new LiteralText("Back"), button -> this.client.openScreen(parent)));
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Race a Pace"), cx, this.height / 2 - 62, ACCENT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("A time to beat, for when nobody is queued."),
				cx, this.height / 2 - 48, Palette.DIM);

		// ONE line, carrying all three things a player should know before
		// choosing rather than after.
		//
		// This started as three, and four lines of identical grey read as
		// a block - which is precisely how a player skips them. These
		// exist to be read BEFORE committing rather than discovered
		// afterwards, so saying less is what makes them work.
		//
		// The seed cost belongs HERE, before the button is pressed - a
		// paced world is one this player can then never be dealt in a
		// ranked match, and that is a decision, not a notification.
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Nothing is rated. A pace repeats its splits, and it spends a seed."),
				cx, this.height / 2 + 4, Palette.DIM);

		super.render(matrices, mouseX, mouseY, delta);
	}
}

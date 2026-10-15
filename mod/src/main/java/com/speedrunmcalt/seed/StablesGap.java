package com.speedrunmcalt.seed;

import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;

/**
 * Whether a hoglin stable has a usable gap, read from its jigsaw pieces.
 *
 * Each of the stable's two side walls is one of two vanilla templates,
 * picked independently: side_wall_1 is the "good gap" runners route
 * through, side_wall_0 the "bad gap" - the one with cracked blackstone
 * in the centre of the wall, which is how a runner tells them apart in
 * play. The labels come from the MCSR Practice map's bastion builder,
 * whose Good Gap / Bad Gap setting places exactly these two templates.
 *
 * The rule follows Ranked's, as runners describe it: at least one good
 * gap, or - with two bad gaps - the triple-chest rampart (ramparts_1,
 * the only stables rampart with three chests) to make up the gold.
 *
 * Only template names are read, never blocks, so this needs nothing
 * beyond the structure start.
 */
public final class StablesGap {
	public enum Verdict {
		/** Not a hoglin stable; the rule does not apply. */
		NOT_STABLES("-"),
		GOOD_GAP("good-gap"),
		TRIPLE_CHEST("triple-chest"),
		/** Two bad gaps and no triple chest. */
		BAD("bad-gap");

		/** What the nether check writes in its CSV column. */
		public final String csv;

		Verdict(String csv) {
			this.csv = csv;
		}

		public boolean passes() {
			return this != BAD;
		}
	}

	private static final String STABLES = "bastion/hoglin_stable/";
	private static final String GOOD_WALL = "hoglin_stable/walls/side_wall_1";
	private static final String TRIPLE_RAMPART = "hoglin_stable/ramparts/ramparts_1";

	private StablesGap() {
	}

	/**
	 * Matched against the piece's template id, which appears in the
	 * piece's toString for single pool elements - the same way
	 * VillageSmith finds a smith.
	 */
	public static Verdict judge(StructureStart<?> start) {
		boolean stables = false;
		boolean goodGap = false;
		boolean triple = false;
		for (StructurePiece piece : start.getChildren()) {
			String name = piece.toString();
			stables |= name.contains(STABLES);
			goodGap |= name.contains(GOOD_WALL);
			triple |= name.contains(TRIPLE_RAMPART);
		}
		if (!stables) {
			return Verdict.NOT_STABLES;
		}
		if (goodGap) {
			return Verdict.GOOD_GAP;
		}
		return triple ? Verdict.TRIPLE_CHEST : Verdict.BAD;
	}

	/** Diagnostic: the stable's wall and rampart pieces, for the log. */
	public static String describe(StructureStart<?> start) {
		StringBuilder out = new StringBuilder();
		for (StructurePiece piece : start.getChildren()) {
			String name = piece.toString();
			int at = name.indexOf("hoglin_stable/");
			if (at < 0 || !(name.contains("/walls/") || name.contains("/ramparts/"))) {
				continue;
			}
			int end = name.indexOf(']', at);
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(name, at + "hoglin_stable/".length(), end < 0 ? name.length() : end);
		}
		return out.toString();
	}
}

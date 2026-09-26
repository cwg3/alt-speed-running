package com.speedrunmcalt.net;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What is installed, as {@code id@version} strings, for the match record.
 *
 * This is NOT an anti-cheat. Anyone able to patch the client can patch
 * this class, and a signature would not change that. What it does is
 * make a mod accusation checkable AND clearable: today neither player
 * can demonstrate anything either way, which fails an honest player
 * harder than a dishonest one. The rule it serves is the published
 * whitelist in README.md; deliberate cheating is caught by run
 * evidence, not by asking the client what it is running.
 *
 * Collected once per launch, which is all that is needed: Fabric loads
 * mods at startup, so the list cannot change without relaunching the
 * game. It is sent at login and describes that whole session.
 *
 * {@code id@version} rather than an object per mod. A Fabric mod id is
 * restricted to lowercase letters, digits, hyphen and underscore, so it
 * can never contain an '@' and splitting on the first one is exact.
 * Field names repeated per entry would cost more than the values.
 */
public final class ModList {
	private ModList() {
	}

	/**
	 * Ids the pack installs by definition: Minecraft, Java, the loader,
	 * Fabric API and this mod. Everything nested inside them is added at
	 * runtime by {@link #packBaseline()}.
	 *
	 * Both "fabric" and "fabric-api" appear because the API's id changed
	 * between versions and a pinned build is no guarantee against that
	 * moving again.
	 */
	private static final String[] PACK_ROOTS = {
			"minecraft", "java", "fabricloader", "fabric", "fabric-api", "speedrunmcalt",
	};

	private static List<String> cached;
	private static Set<String> baseline;

	/** Every loaded mod, {@code id@version}, sorted. Modules included. */
	public static synchronized List<String> installed() {
		if (cached == null) {
			List<String> out = new ArrayList<>();
			for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
				out.add(c.getMetadata().getId() + "@"
						+ c.getMetadata().getVersion().getFriendlyString());
			}
			// Sorted, so two players running the same mods produce the
			// same list. Load order is not stable between launches, and
			// a list that reorders itself cannot be compared by eye.
			Collections.sort(out);
			cached = Collections.unmodifiableList(out);
		}
		return cached;
	}

	/**
	 * The ids the pack accounts for, including every module nested
	 * inside them.
	 *
	 * Membership is asked of the loader rather than inferred from the
	 * name. Filtering ids that begin with "fabric-" would hide anything
	 * calling itself fabric-whatever, which is a hole with an obvious
	 * shape; walking {@code getContainedMods()} from the roots does not
	 * have it.
	 */
	private static synchronized Set<String> packBaseline() {
		if (baseline == null) {
			Set<String> ids = new LinkedHashSet<>();
			for (String root : PACK_ROOTS) {
				FabricLoader.getInstance().getModContainer(root).ifPresent(c -> collect(c, ids));
			}
			baseline = Collections.unmodifiableSet(ids);
		}
		return baseline;
	}

	private static void collect(ModContainer c, Set<String> into) {
		if (!into.add(c.getMetadata().getId())) {
			return; // already walked - a cycle would otherwise not end
		}
		for (ModContainer child : c.getContainedMods()) {
			collect(child, into);
		}
	}

	/**
	 * The entries of {@code mods} that the pack does not account for -
	 * what is worth showing a player.
	 *
	 * Takes a list rather than reading the loader, because the opponent's
	 * list arrives from the backend and has no containers behind it. The
	 * baseline is this install's, which is exactly right for any client
	 * running the pack: the Fabric API build is pinned, so a legitimate
	 * opponent's modules carry the same ids as ours.
	 *
	 * The limit, stated rather than hidden: a mod that takes the id of a
	 * real Fabric module would be filtered out of this view. It would
	 * still be in the recorded list, which is the thing a dispute is
	 * settled from.
	 */
	public static List<String> beyondPack(List<String> mods) {
		Set<String> known = packBaseline();
		List<String> out = new ArrayList<>();
		for (String entry : mods) {
			int at = entry.indexOf('@');
			String id = at < 0 ? entry : entry.substring(0, at);
			if (!known.contains(id)) {
				out.add(entry);
			}
		}
		return out;
	}

	/** "sodium@0.2.0" as "sodium 0.2.0", for a screen rather than a record. */
	public static String pretty(String entry) {
		int at = entry.indexOf('@');
		return at < 0 ? entry : entry.substring(0, at) + " " + entry.substring(at + 1);
	}
}

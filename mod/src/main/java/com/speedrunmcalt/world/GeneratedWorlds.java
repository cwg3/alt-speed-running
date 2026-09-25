package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deleting worlds this mod made, and refusing to delete anything else.
 *
 * Both kinds of generated world need the same two things - trim the
 * old ones, never touch a world a player built - and they used to have
 * one copy of it in the replay package, which a match-world cleaner
 * would have had to reach backwards into. This is the shared half,
 * sitting where both can see it.
 *
 * What it does NOT decide is which worlds are expendable. A replay
 * world is a cache and is deliberately deleted before being recreated;
 * a match world is a save in progress that a crashed player rejoins.
 * Those callers own that difference - see ReplayWorlds and MatchWorlds.
 */
public final class GeneratedWorlds {
	private GeneratedWorlds() {
	}

	/**
	 * Trims worlds carrying one prefix, newest kept, sparing one name.
	 *
	 * @param spare a directory name never to delete, or null. This is
	 *              what keeps a match world that is about to be entered
	 *              - or rejoined after a crash - out of the cull.
	 */
	public static void prune(Path saves, String prefix, int keep, String spare)
			throws IOException {
		prune(saves, prefix, keep, spare, Integer.MAX_VALUE);
	}

	/**
	 * As above, removing at most {@code max} worlds in this pass.
	 *
	 * A bound matters when the backlog is large: clearing gigabytes in
	 * one go is a long disk operation, and the caller that needs this
	 * runs as a match is starting.
	 */
	public static void prune(Path saves, String prefix, int keep, String spare, int max)
			throws IOException {
		if (!Files.isDirectory(saves)) {
			return;
		}
		List<Path> worlds = new ArrayList<>();
		try (java.util.stream.Stream<Path> list = Files.list(saves)) {
			for (Path p : (Iterable<Path>) list::iterator) {
				String name = p.getFileName().toString();
				if (Files.isDirectory(p) && name.startsWith(prefix) && !name.equals(spare)) {
					worlds.add(p);
				}
			}
		}
		if (worlds.size() <= keep) {
			return;
		}
		worlds.sort(Comparator.comparingLong(p -> -lastModified(p)));
		List<Path> doomed = worlds.subList(keep, worlds.size());
		int n = Math.min(doomed.size(), max);
		for (Path old : doomed.subList(0, n)) {
			delete(saves, old, prefix);
		}
		if (doomed.size() > n) {
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] {} more {} worlds to clear - trimming a few per match",
					doomed.size() - n, prefix);
		}
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	/**
	 * Deletes one generated world, or refuses.
	 *
	 * Both conditions are load-bearing and neither is redundant. The
	 * prefix says this is one of ours; the parent check says a crafted
	 * match id cannot walk the path somewhere else. A recursive delete
	 * whose target comes from a server response deserves both.
	 */
	public static void delete(Path saves, Path world, String prefix) throws IOException {
		Path resolved = world.toAbsolutePath().normalize();
		if (!resolved.getFileName().toString().startsWith(prefix)
				|| !resolved.getParent().equals(saves.toAbsolutePath().normalize())) {
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Refusing to delete {} - not a generated world", resolved);
			return;
		}
		if (!Files.isDirectory(resolved)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(resolved)) {
			List<Path> all = new ArrayList<>();
			for (Path p : (Iterable<Path>) walk::iterator) {
				all.add(p);
			}
			// Deepest first: a directory will not delete while it has
			// anything in it.
			all.sort(Comparator.reverseOrder());
			for (Path p : all) {
				Files.deleteIfExists(p);
			}
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Deleted generated world {}",
				resolved.getFileName());
	}
}

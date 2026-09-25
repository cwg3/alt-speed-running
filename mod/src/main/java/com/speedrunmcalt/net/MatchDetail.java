package com.speedrunmcalt.net;

import java.util.List;
import java.util.Map;

/** One match, split by split, for the detail screen. */
public final class MatchDetail {
	public static final class Player {
		public final String uuid;
		public final String username;

		public Player(String uuid, String username) {
			this.uuid = uuid;
			this.username = username;
		}
	}

	public static final class Row {
		public final String split;
		/** uuid -> time in ms, or null if they never reached it. */
		public final Map<String, Long> times;
		/**
		 * uuid -> difference against the other player, or null.
		 *
		 * Null when only one of them reached the split. A delta against
		 * somebody who never got there is not a lead, it is a
		 * comparison that does not exist.
		 */
		public final Map<String, Long> deltas;

		public Row(String split, Map<String, Long> times, Map<String, Long> deltas) {
			this.split = split;
			this.times = times;
			this.deltas = deltas;
		}
	}

	public final String matchId;
	public final List<Player> players;
	public final String winnerUuid;
	public final String seedType;
	public final boolean forfeited;
	public final int worldSetupVersion;
	public final List<Row> rows;

	public MatchDetail(String matchId, List<Player> players, String winnerUuid,
			String seedType, boolean forfeited, int worldSetupVersion, List<Row> rows) {
		this.matchId = matchId;
		this.players = players;
		this.winnerUuid = winnerUuid;
		this.seedType = seedType;
		this.forfeited = forfeited;
		this.worldSetupVersion = worldSetupVersion;
		this.rows = rows;
	}
}

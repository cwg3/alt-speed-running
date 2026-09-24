package com.speedrunmcalt.net;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client for our own AWS backend - see backend/lambda/ in the repo. */
public final class BackendClient {
	private static final String API_BASE = "https://4q2boikc88.execute-api.us-west-2.amazonaws.com";

	private BackendClient() {
	}

	/**
	 * This build's version, read from the mod's own metadata.
	 *
	 * Taken from fabric.mod.json rather than a constant, so it cannot
	 * disagree with the jar it is compiled into - a hand-maintained
	 * version string is exactly the kind that gets forgotten on a
	 * release and then reports the wrong thing forever.
	 */
	private static String clientVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer("speedrunmcalt")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	public static VerifyResult verify(String username, String serverId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("serverId", serverId);
		// Sent on login, which is the only moment a stale client can be
		// turned away before it reaches matchmaking and fails in ways
		// that look like server bugs.
		body.addProperty("clientVersion", clientVersion());
		// Which build of the world-building rules this client has.
		//
		// Separate from clientVersion on purpose: most releases change
		// nothing about how a seed becomes a world, and the two move at
		// different rates. This is the one the backend must not let
		// differ between two players in a match - they each build their
		// own world from the shared seed, so different rules mean
		// different worlds, and the race stops being the same race.
		body.addProperty("worldSetupVersion",
				com.speedrunmcalt.world.WorldSetupVersion.CURRENT);

		JsonObject resp = post(API_BASE + "/auth/verify", body, null);
		return new VerifyResult(
				resp.get("uuid").getAsString(),
				resp.get("username").getAsString(),
				resp.get("sessionToken").getAsString(),
				resp.get("skillRating").getAsInt(),
				resp.get("seasonPoints").getAsInt());
	}

	public static QueueJoinResult joinQueue(String sessionToken) throws IOException {
		JsonObject resp = post(API_BASE + "/queue/join", new JsonObject(), sessionToken);
		if (!resp.get("matched").getAsBoolean()) {
			return QueueJoinResult.waiting();
		}
		JsonObject opponent = resp.getAsJsonObject("opponent");
		return QueueJoinResult.matched(
				resp.get("matchId").getAsString(),
				opponent.get("username").getAsString(),
				resp.get("overworldSeed").getAsLong(),
				resp.get("netherSeed").getAsLong(),
				optString(resp, "seedType", "village"),
				optInt(resp, "structureX", 0),
				optInt(resp, "structureZ", 0),
				optString(resp, "bastionType", null),
				optInt(resp, "bastionX", 0),
				optInt(resp, "bastionZ", 0),
				optInt(resp, "smithX", 0),
				optInt(resp, "smithZ", 0),
				resp.has("yourRunStartedAt") && !resp.get("yourRunStartedAt").isJsonNull());
	}

	/** Tolerates a backend older than this client, and nulls in JSON. */
	private static String optString(JsonObject obj, String key, String fallback) {
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		return obj.get(key).getAsString();
	}

	private static int optInt(JsonObject obj, String key, int fallback) {
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return fallback;
		}
		return obj.get(key).getAsInt();
	}

	public static SplitReportResult reportSplit(String sessionToken, String matchId,
			String splitName, long elapsedMs) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("matchId", matchId);
		body.addProperty("splitName", splitName);
		body.addProperty("elapsedMs", elapsedMs);

		JsonObject resp = post(API_BASE + "/matches/split", body, sessionToken);
		boolean completed = resp.has("completed") && resp.get("completed").getAsBoolean();
		boolean alreadyCompleted = resp.has("alreadyCompleted") && resp.get("alreadyCompleted").getAsBoolean();

		int ratingDelta = 0;
		int seasonPoints = 0;
		if (completed && resp.has("winner") && resp.get("winner").isJsonObject()) {
			JsonObject winner = resp.getAsJsonObject("winner");
			ratingDelta = winner.get("ratingDelta").getAsInt();
			seasonPoints = winner.get("seasonPointsAwarded").getAsInt();
		}
		return new SplitReportResult(completed, alreadyCompleted, ratingDelta, seasonPoints);
	}

	public static LiveMatchResult getLiveMatch(String sessionToken, String matchId) throws IOException {
		JsonObject resp = get(API_BASE + "/matches/" + matchId + "/live", sessionToken);

		JsonObject opponent = resp.getAsJsonObject("opponent");
		Map<String, Long> splits = new LinkedHashMap<>();
		JsonObject splitsJson = opponent.getAsJsonObject("splits");
		for (Map.Entry<String, com.google.gson.JsonElement> entry : splitsJson.entrySet()) {
			splits.put(entry.getKey(), entry.getValue().getAsLong());
		}

		String winnerUuid = resp.get("winnerUuid").isJsonNull() ? null : resp.get("winnerUuid").getAsString();

		// Only present once the match is decided.
		Integer ratingDelta = null;
		Integer seasonPoints = null;
		if (resp.has("yourResult") && !resp.get("yourResult").isJsonNull()) {
			JsonObject yours = resp.getAsJsonObject("yourResult");
			ratingDelta = yours.get("ratingDelta").getAsInt();
			seasonPoints = yours.get("seasonPointsAwarded").getAsInt();
		}

		// Absent on a backend older than this client, hence the guards.
		boolean badYours = false;
		boolean badOpponent = false;
		String badReason = null;
		if (resp.has("badSeed") && resp.get("badSeed").isJsonObject()) {
			JsonObject bad = resp.getAsJsonObject("badSeed");
			badYours = bad.has("yours") && bad.get("yours").getAsBoolean();
			badOpponent = bad.has("opponent") && bad.get("opponent").getAsBoolean();
			badReason = optString(bad, "opponentReason", null);
		}

		// Likewise absent on an older backend, which simply means the
		// countdown keeps its old unconditional behaviour rather than
		// the client guessing.
		Long yourRunStartedAt = null;
		if (resp.has("yourRunStartedAt") && !resp.get("yourRunStartedAt").isJsonNull()) {
			yourRunStartedAt = resp.get("yourRunStartedAt").getAsLong();
		}

		return new LiveMatchResult(
				resp.get("status").getAsString(),
				winnerUuid,
				opponent.get("username").getAsString(),
				splits, ratingDelta, seasonPoints,
				badYours, badOpponent, badReason,
				yourRunStartedAt);
	}

	/**
	 * Claims this player's run start and returns when it began, as a
	 * local-clock millisecond value.
	 *
	 * The server mints the timestamp once per player per match and
	 * hands back the same one on every later call, so a client that
	 * crashed and rejoined resumes the run it was already on instead of
	 * starting a new one.
	 *
	 * The conversion matters. The server's answer is in ITS clock, and
	 * the two machines do not necessarily agree - a client an hour off
	 * would otherwise show an hour on the timer. So the elapsed time is
	 * taken as a difference computed entirely server-side
	 * (serverNow - runStartedAt) and subtracted from the local clock,
	 * which leaves skew out of it altogether.
	 */
	public static long claimRunStart(String sessionToken, String matchId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("matchId", matchId);
		JsonObject resp = post(API_BASE + "/matches/start", body, sessionToken);

		long runStartedAt = resp.get("runStartedAt").getAsLong();
		long serverNow = resp.get("serverNow").getAsLong();
		boolean claimed = resp.has("claimed") && resp.get("claimed").getAsBoolean();

		long elapsedMs = Math.max(0, serverNow - runStartedAt);
		SpeedrunMcAlt.LOGGER.info(
				"[speedrunmcalt] Run start {} - {} ms already elapsed",
				claimed ? "claimed" : "resumed (rejoined an existing run)", elapsedMs);
		return System.currentTimeMillis() - elapsedMs;
	}

	/**
	 * Votes that this match's seed is unplayable.
	 *
	 * Takes both players: one vote records and waits, the second voids
	 * the match with no rating change for either side and pulls the seed
	 * from the pool. One player alone cannot void a match, because that
	 * would just be a way to escape a loss.
	 *
	 * @return true once BOTH players have agreed and the match is void
	 */
	public static boolean voteBadSeed(String sessionToken, String matchId, String reason)
			throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("matchId", matchId);
		if (reason != null) {
			body.addProperty("reason", reason);
		}
		JsonObject resp = post(API_BASE + "/matches/bad-seed", body, sessionToken);
		boolean voided = resp.has("voided") && resp.get("voided").getAsBoolean();
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Bad seed vote: {}",
				voided ? "both agreed - match voided" : "recorded, waiting for opponent");
		return voided;
	}

	/** Gives up the current match. The opponent is awarded the win. */
	public static void forfeit(String sessionToken, String matchId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("matchId", matchId);
		post(API_BASE + "/matches/forfeit", body, sessionToken);
	}

	/**
	 * Sends the recorded position timeline. Samples are packed as flat
	 * arrays rather than named objects - at one sample per second a
	 * long run is thousands of entries, and repeating four field names
	 * on each of them roughly triples the payload for no benefit.
	 */
	public static void uploadReplay(String sessionToken, String matchId,
			java.util.List<com.speedrunmcalt.match.ReplayRecorder.Sample> samples) throws IOException {
		JsonArray packed = new JsonArray();
		for (com.speedrunmcalt.match.ReplayRecorder.Sample s : samples) {
			JsonArray row = new JsonArray();
			row.add(s.t);
			row.add(s.dim);
			row.add(Math.round(s.x * 10.0) / 10.0);
			row.add(Math.round(s.y * 10.0) / 10.0);
			row.add(Math.round(s.z * 10.0) / 10.0);
			packed.add(row);
		}

		JsonObject body = new JsonObject();
		body.addProperty("matchId", matchId);
		body.add("samples", packed);
		post(API_BASE + "/matches/replay", body, sessionToken);
	}

	private static JsonObject get(String url, String bearerToken) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "application/json");
		if (bearerToken != null) {
			conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
		}

		int status = conn.getResponseCode();
		boolean ok = status >= 200 && status < 300;
		String text = readAll(ok ? conn.getInputStream() : conn.getErrorStream());
		if (!ok) {
			throw new IOException("GET " + url + " failed with HTTP " + status + ": " + text);
		}
		return new JsonParser().parse(text).getAsJsonObject();
	}

	private static JsonObject post(String url, JsonObject body, String bearerToken) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Accept", "application/json");
		if (bearerToken != null) {
			conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
		}
		conn.setDoOutput(true);
		byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload);
		}

		int status = conn.getResponseCode();
		boolean ok = status >= 200 && status < 300;
		InputStream stream = ok ? conn.getInputStream() : conn.getErrorStream();
		String text = readAll(stream);

		JsonObject json = null;
		if (text != null && !text.isEmpty()) {
			try {
				// Minecraft's bundled Gson predates JsonParser.parseString().
				json = new JsonParser().parse(text).getAsJsonObject();
			} catch (RuntimeException notJson) {
				json = null;
			}
		}

		if (!ok) {
			// The backend's own "error" string, when it sent one.
			// AltSession puts getMessage() straight on the screen, and
			// the whole JSON body plus a URL is not something to show
			// somebody - least of all for the version gate, whose
			// entire job is to say one clear sentence.
			String friendly = null;
			if (json != null && json.has("error") && json.get("error").isJsonPrimitive()) {
				friendly = json.get("error").getAsString();
			}
			if (friendly != null) {
				throw new IOException(friendly);
			}
			String detail = json != null ? json.toString() : text;
			throw new IOException("POST " + url + " failed with HTTP " + status + ": " + detail);
		}
		if (json == null) {
			throw new IOException("POST " + url + " returned a non-JSON body: " + text);
		}
		return json;
	}

	private static String readAll(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buf.write(chunk, 0, n);
		}
		return buf.toString("UTF-8");
	}
}

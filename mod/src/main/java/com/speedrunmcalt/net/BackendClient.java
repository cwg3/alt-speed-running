package com.speedrunmcalt.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Client for our own AWS backend - see backend/lambda/ in the repo. */
public final class BackendClient {
	private static final String API_BASE = "https://4q2boikc88.execute-api.us-west-2.amazonaws.com";

	private BackendClient() {
	}

	public static VerifyResult verify(String username, String serverId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		body.addProperty("serverId", serverId);

		JsonObject resp = post(API_BASE + "/auth/verify", body, null);
		return new VerifyResult(
				resp.get("uuid").getAsString(),
				resp.get("username").getAsString(),
				resp.get("sessionToken").getAsString());
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
				resp.get("netherSeed").getAsLong());
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

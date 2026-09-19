package com.speedrunmcalt.auth;

import com.google.gson.JsonObject;
import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Reads the identity already established by the real Minecraft Launcher
 * this mod is running inside of, and proves ownership of it to our
 * backend via Mojang's session join/hasJoined flow - the same mechanism
 * every vanilla multiplayer server has used for identity verification
 * for years.
 *
 * This replaces an earlier attempt (see git history for MicrosoftAuth)
 * where the mod performed its own independent Microsoft/Xbox login.
 * That path calls api.minecraftservices.com/authentication/login_with_xbox
 * directly, which as of 2026 Microsoft gates behind ID@Xbox developer
 * program approval for new app registrations - confirmed via a live
 * HTTP 403 "Invalid app registration" during testing, not just docs.
 * Since this mod runs inside an already-authenticated game session
 * (the player logs in through the real, already-approved Minecraft
 * Launcher before our code ever runs), we don't need our own login at
 * all - we just read the session that's already there.
 */
public final class SessionAuth {
	private SessionAuth() {
	}

	public static MinecraftIdentity currentIdentity() {
		Session session = MinecraftClient.getInstance().getSession();
		return new MinecraftIdentity(session.getUuid(), session.getUsername(), session.getAccessToken());
	}

	/**
	 * Tells Mojang's session server this client is "joining" a server
	 * identified by serverId. In the real flow, serverId comes from our
	 * backend when a match starts; our backend then independently calls
	 * the matching hasJoined endpoint to confirm this happened, proving
	 * account ownership without our servers ever seeing the access token.
	 */
	public static void joinServer(MinecraftIdentity identity, String serverId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("accessToken", identity.getAccessToken());
		body.addProperty("selectedProfile", identity.getUuid());
		body.addProperty("serverId", serverId);

		HttpURLConnection conn = (HttpURLConnection) URI.create(
				"https://sessionserver.mojang.com/session/minecraft/join").toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setDoOutput(true);
		byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(payload);
		}

		int status = conn.getResponseCode();
		if (status == 200 || status == 204) {
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Joined session server as {} ({})",
					identity.getUsername(), identity.getUuid());
			return;
		}

		InputStream err = conn.getErrorStream();
		String text = err == null ? "" : readAll(err);
		throw new IOException("Session join failed with HTTP " + status + ": " + text);
	}

	public static String randomServerId() {
		byte[] buf = new byte[16];
		new SecureRandom().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static String readAll(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buf.write(chunk, 0, n);
		}
		return buf.toString("UTF-8");
	}
}

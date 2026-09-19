package com.speedrunmcalt.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.speedrunmcalt.SpeedrunMcAlt;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft -> Xbox Live -> Minecraft Services login for a native/public
 * client (PKCE, no client secret - the mod ships to every player, so it
 * can never hold a secret safely).
 *
 * Flow: https://minecraft.wiki/w/Microsoft_authentication
 */
public final class MicrosoftAuth {
	// Not a secret - Azure client IDs are meant to be public, embedded in
	// distributed native apps.
	private static final String CLIENT_ID = "dfe6388e-3d3b-4158-bee9-36029fa9116a";
	private static final String SCOPE = "XboxLive.signin offline_access openid profile email";
	private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
	private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

	private MicrosoftAuth() {
	}

	public static CompletableFuture<MinecraftProfile> login() {
		return CompletableFuture.supplyAsync(MicrosoftAuth::loginBlocking);
	}

	private static MinecraftProfile loginBlocking() {
		try {
			String codeVerifier = randomUrlSafeString(64);
			String codeChallenge = sha256UrlSafe(codeVerifier);
			String state = randomUrlSafeString(16);

			LoopbackResult loopback = startLoopbackServer(state);
			String redirectUri = "http://localhost:" + loopback.port;

			String authorizeUri = AUTHORIZE_URL
					+ "?client_id=" + urlEncode(CLIENT_ID)
					+ "&response_type=code"
					+ "&redirect_uri=" + urlEncode(redirectUri)
					+ "&scope=" + urlEncode(SCOPE)
					+ "&code_challenge=" + urlEncode(codeChallenge)
					+ "&code_challenge_method=S256"
					+ "&state=" + urlEncode(state);

			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Opening browser for Microsoft login. "
					+ "If it doesn't open automatically, visit: {}", authorizeUri);
			openBrowser(authorizeUri);

			String authCode;
			try {
				authCode = loopback.codeFuture.get(5, TimeUnit.MINUTES);
			} catch (java.util.concurrent.TimeoutException timedOut) {
				// get() timing out doesn't complete codeFuture itself, so
				// without this the loopback HttpServer would keep listening
				// forever. Cancelling it completes the future (exceptionally),
				// which triggers the whenComplete below that stops the server.
				loopback.codeFuture.cancel(true);
				throw new IOException("Timed out waiting for Microsoft login (5 minutes)", timedOut);
			}

			JsonObject tokenResp = postForm(TOKEN_URL, formParams(
					"client_id", CLIENT_ID,
					"scope", SCOPE,
					"code", authCode,
					"redirect_uri", redirectUri,
					"grant_type", "authorization_code",
					"code_verifier", codeVerifier));
			String msAccessToken = tokenResp.get("access_token").getAsString();

			JsonObject xblBody = new JsonObject();
			JsonObject xblProps = new JsonObject();
			xblProps.addProperty("AuthMethod", "RPS");
			xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
			xblProps.addProperty("RpsTicket", "d=" + msAccessToken);
			xblBody.add("Properties", xblProps);
			xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
			xblBody.addProperty("TokenType", "JWT");
			JsonObject xblResp = postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody);
			String xblToken = xblResp.get("Token").getAsString();
			String userHash = xblResp.getAsJsonObject("DisplayClaims")
					.getAsJsonArray("xui").get(0).getAsJsonObject()
					.get("uhs").getAsString();

			JsonObject xstsBody = new JsonObject();
			JsonObject xstsProps = new JsonObject();
			xstsProps.addProperty("SandboxId", "RETAIL");
			JsonArray userTokens = new JsonArray();
			userTokens.add(xblToken);
			xstsProps.add("UserTokens", userTokens);
			xstsBody.add("Properties", xstsProps);
			xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
			xstsBody.addProperty("TokenType", "JWT");

			JsonObject xstsResp;
			try {
				xstsResp = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody);
			} catch (XstsException e) {
				throw new IOException(describeXstsError(e.xErr), e);
			}
			String xstsToken = xstsResp.get("Token").getAsString();

			JsonObject mcBody = new JsonObject();
			mcBody.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
			JsonObject mcResp = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcBody);
			String mcAccessToken = mcResp.get("access_token").getAsString();

			JsonObject profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);
			if (profile.has("error")) {
				throw new IOException("No Minecraft: Java Edition license on this account ("
						+ profile.get("errorMessage").getAsString() + ")");
			}
			String uuid = profile.get("id").getAsString();
			String name = profile.get("name").getAsString();

			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Logged in as {} ({})", name, uuid);
			return new MinecraftProfile(uuid, name, mcAccessToken);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Microsoft login failed", e);
			throw new RuntimeException("Microsoft login failed: " + e.getMessage(), e);
		}
	}

	// --- loopback redirect listener ---

	private static final class LoopbackResult {
		final int port;
		final CompletableFuture<String> codeFuture;

		LoopbackResult(int port, CompletableFuture<String> codeFuture) {
			this.port = port;
			this.codeFuture = codeFuture;
		}
	}

	private static LoopbackResult startLoopbackServer(String expectedState) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		CompletableFuture<String> codeFuture = new CompletableFuture<>();

		server.createContext("/", (HttpExchange exchange) -> {
			try {
				String query = exchange.getRequestURI().getQuery();
				Map<String, String> params = parseQuery(query);
				String response;
				if (params.containsKey("code") && !expectedState.equals(params.get("state"))) {
					codeFuture.completeExceptionally(new IOException(
							"OAuth state mismatch - discarding response (possible CSRF)"));
					response = "<html><body>Login failed: state mismatch, please try again.</body></html>";
				} else if (params.containsKey("code")) {
					codeFuture.complete(params.get("code"));
					response = "<html><body>Login complete - you can close this tab and return to Minecraft.</body></html>";
				} else {
					String error = params.getOrDefault("error_description", "unknown error");
					codeFuture.completeExceptionally(new IOException("Microsoft login was not completed: " + error));
					response = "<html><body>Login failed: " + escapeHtml(error) + "</body></html>";
				}
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			} finally {
				exchange.close();
			}
		});

		server.start();
		int port = server.getAddress().getPort();

		// Shut the tiny loopback server down once we have (or fail to get) a code.
		codeFuture.whenComplete((code, err) -> server.stop(0));

		return new LoopbackResult(port, codeFuture);
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new HashMap<>();
		if (query == null) {
			return result;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			try {
				String key = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
				String value = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
				result.put(key, value);
			} catch (IOException ignored) {
				// UTF-8 is always supported; unreachable.
			}
		}
		return result;
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static void openBrowser(String uri) {
		// java.awt.Desktop.browse() is unreliable from inside a GLFW-based
		// game window (GLFW takes over the platform's main-thread/event-loop
		// handling in a way that breaks AWT's Cocoa integration on macOS in
		// particular) - shell out to the OS's native URL opener directly,
		// which is what every real Minecraft launcher/mod does for this
		// exact reason.
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("mac")) {
				new ProcessBuilder("open", uri).start();
				return;
			} else if (os.contains("win")) {
				new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start();
				return;
			} else {
				new ProcessBuilder("xdg-open", uri).start();
				return;
			}
		} catch (IOException nativeOpenFailed) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Native browser open failed, trying AWT Desktop", nativeOpenFailed);
		}

		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(uri));
				return;
			}
		} catch (IOException desktopFailed) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] AWT Desktop browse failed too", desktopFailed);
		}

		SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Can't open a browser automatically on this system; "
				+ "open the URL above manually.");
	}

	// --- PKCE helpers ---

	private static String randomUrlSafeString(int numBytes) {
		byte[] buf = new byte[numBytes];
		new SecureRandom().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	private static String sha256UrlSafe(String input) throws NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	// --- HTTP helpers ---

	private static class XstsException extends IOException {
		final long xErr;

		XstsException(long xErr) {
			super("XSTS authorization failed, XErr=" + xErr);
			this.xErr = xErr;
		}
	}

	private static String describeXstsError(long xErr) {
		if (xErr == 2148916233L) {
			return "This Microsoft account has no Xbox Live account. Create one at xbox.com, then try again.";
		} else if (xErr == 2148916235L) {
			return "Xbox Live is not available in this account's country/region.";
		} else if (xErr == 2148916236L || xErr == 2148916237L) {
			return "This account needs adult verification on xbox.com before it can be used.";
		} else if (xErr == 2148916238L) {
			return "This is a child account and must be added to a Microsoft Family group by an adult first.";
		}
		return "XSTS authorization failed with code " + xErr + ".";
	}

	private static String urlEncode(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (IOException e) {
			throw new AssertionError("UTF-8 is always supported", e);
		}
	}

	private static Map<String, String> formParams(String... kv) {
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put(kv[i], kv[i + 1]);
		}
		return map;
	}

	private static JsonObject postForm(String url, Map<String, String> params) throws IOException {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (body.length() > 0) {
				body.append('&');
			}
			body.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
		}
		JsonObject resp = sendRequest(url, "POST", "application/x-www-form-urlencoded",
				body.toString().getBytes(StandardCharsets.UTF_8), null);
		if (resp.has("error")) {
			String desc = resp.has("error_description")
					? resp.get("error_description").getAsString()
					: resp.get("error").getAsString();
			throw new IOException("Microsoft token request failed: " + desc);
		}
		requireOk("Token request to " + url, resp);
		return resp;
	}

	private static JsonObject postJson(String url, JsonObject body) throws IOException {
		JsonObject resp = sendRequest(url, "POST", "application/json",
				body.toString().getBytes(StandardCharsets.UTF_8), null);
		if (resp.has("XErr")) {
			throw new XstsException(resp.get("XErr").getAsLong());
		}
		requireOk("Request to " + url, resp);
		return resp;
	}

	private static JsonObject getJson(String url, String bearerToken) throws IOException {
		JsonObject resp = sendRequest(url, "GET", null, null, bearerToken);
		// The profile endpoint reports "no license on this account" as a
		// structured error field rather than (only) a bad status - let the
		// caller check resp.has("error") itself before we'd otherwise throw.
		if (!resp.has("error")) {
			requireOk("GET " + url, resp);
		}
		return resp;
	}

	private static JsonObject sendRequest(String url, String method, String contentType,
			byte[] body, String bearerToken) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setRequestMethod(method);
		conn.setRequestProperty("Accept", "application/json");
		if (contentType != null) {
			conn.setRequestProperty("Content-Type", contentType);
		}
		if (bearerToken != null) {
			conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
		}
		if (body != null) {
			conn.setDoOutput(true);
			try (OutputStream os = conn.getOutputStream()) {
				os.write(body);
			}
		}

		int status = conn.getResponseCode();
		InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
		String text = readAll(stream);
		boolean ok = status >= 200 && status < 300;

		// Xbox/XSTS/Minecraft Services all return a JSON body describing the
		// specific error even on a non-2xx status (e.g. XSTS returns 401
		// with an XErr code) - parse it whenever possible instead of
		// failing before callers get a chance to inspect it.
		JsonObject json = null;
		if (text != null && !text.isEmpty()) {
			try {
				// Minecraft 1.16.1 bundles a Gson version older than 2.8.9,
				// so the static JsonParser.parseString() isn't available.
				json = new JsonParser().parse(text).getAsJsonObject();
			} catch (RuntimeException notJson) {
				json = null;
			}
		}

		if (json == null) {
			if (ok) {
				return new JsonObject();
			}
			throw new IOException(method + " " + url + " failed with HTTP " + status
					+ (text == null || text.isEmpty() ? " (empty body)" : ": " + text));
		}

		json.addProperty("_httpStatus", status);
		return json;
	}

	private static void requireOk(String context, JsonObject resp) throws IOException {
		int status = resp.has("_httpStatus") ? resp.get("_httpStatus").getAsInt() : 200;
		if (status < 200 || status >= 300) {
			throw new IOException(context + " failed with HTTP " + status + ": " + resp);
		}
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

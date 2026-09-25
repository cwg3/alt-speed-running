package com.speedrunmcalt.replay;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real player skins for the replay hotbar.
 *
 * The ghost bodies cannot help here. AbstractClientPlayerEntity's
 * getSkinTexture falls back to a DEFAULT skin whenever the player is
 * not in the tab list, and a replay ghost never is - so asking a ghost
 * for its skin returns Steve no matter whose ghost it is.
 *
 * Loading it directly works because the replay payload is keyed by the
 * players' REAL uuids. A GameProfile built from one resolves through
 * the session service to that account's actual skin.
 *
 * Asynchronous, so the first frame shows the default and the real skin
 * replaces it a moment later. That is the right way round: a head that
 * appears late is better than a hotbar that waits on the network
 * before it can draw.
 */
public final class ReplaySkins {
	private static final Map<String, Identifier> LOADED = new HashMap<>();
	private static final Map<String, String> MODELS = new HashMap<>();
	private static final Map<String, Identifier> CAPES = new HashMap<>();
	private static final Map<String, Boolean> REQUESTED = new HashMap<>();

	private ReplaySkins() {
	}

	/**
	 * Clears the cache. Not called between replays on purpose: it is
	 * keyed by account uuid, so there is nothing stale to drop and
	 * clearing would only force the same skins to be fetched again.
	 * Here for a sign-out or a test that needs a clean slate.
	 */
	public static void reset() {
		synchronized (LOADED) {
			LOADED.clear();
			MODELS.clear();
			CAPES.clear();
			REQUESTED.clear();
		}
	}

	/**
	 * That player's skin if it has arrived, the default for their uuid
	 * if it has not.
	 *
	 * @param uuid     the real account uuid, as the replay payload keys it
	 * @param username the name to resolve against
	 */
	public static Identifier forPlayer(String uuid, String username) {
		UUID parsed = parse(uuid);
		if (parsed == null) {
			return DefaultSkinHelper.getTexture();
		}
		synchronized (LOADED) {
			Identifier known = LOADED.get(uuid);
			if (known != null) {
				return known;
			}
			if (!Boolean.TRUE.equals(REQUESTED.get(uuid))) {
				REQUESTED.put(uuid, true);
				request(parsed, uuid, username);
			}
		}
		return DefaultSkinHelper.getTexture(parsed);
	}

	/** That player's cape, or null if they have none. */
	public static Identifier capeFor(String uuid) {
		synchronized (LOADED) {
			return CAPES.get(uuid);
		}
	}

	/** "default" or "slim", falling back to the uuid's usual answer. */
	public static String modelFor(String uuid) {
		synchronized (LOADED) {
			String known = MODELS.get(uuid);
			if (known != null) {
				return known;
			}
		}
		UUID parsed = parse(uuid);
		return parsed == null ? "default" : DefaultSkinHelper.getModel(parsed);
	}

	private static void request(UUID parsed, String key, String username) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getSkinProvider() == null) {
			return;
		}
		try {
			client.getSkinProvider().loadSkin(
					new GameProfile(parsed, username == null ? "player" : username),
					(type, id, texture) -> {
						if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.CAPE
								&& id != null) {
							// Capes are rare and the people who have
							// them notice. The model-parts bitmask
							// already enables the cape layer, so this
							// is the only piece that was missing.
							synchronized (LOADED) {
								CAPES.put(key, id);
							}
						}
						if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN
								&& id != null) {
							synchronized (LOADED) {
								LOADED.put(key, id);
								// "slim" arrives as texture metadata.
								// Without it an Alex skin is stretched
								// over Steve's arms.
								String model = texture == null
										? null : texture.getMetadata("model");
								if (model != null) {
									MODELS.put(key, model);
								}
							}
						}
					},
					false);
		} catch (Exception e) {
			// A skin that will not load is a cosmetic loss. The default
			// is already being returned, so there is nothing to do and
			// nothing worth interrupting a replay for.
			com.speedrunmcalt.SpeedrunMcAlt.LOGGER.debug(
					"[speedrunmcalt] Could not load skin for {}", username);
		}
	}

	/**
	 * The backend stores uuids without dashes; UUID.fromString needs
	 * them. Anything else is left alone rather than guessed at.
	 */
	private static UUID parse(String uuid) {
		if (uuid == null) {
			return null;
		}
		try {
			if (uuid.length() == 32) {
				return UUID.fromString(uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-"
						+ uuid.substring(12, 16) + "-" + uuid.substring(16, 20) + "-"
						+ uuid.substring(20));
			}
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}

package com.speedrunmcalt.replay;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.menu.AltSession;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.ReplayData;
import com.speedrunmcalt.world.MatchWorldCreator;
import com.speedrunmcalt.world.WorldSetupVersion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

/**
 * Loads a replay: fetch the traces, rebuild the world, start the
 * camera.
 *
 * The world is regenerated from the match's seeds rather than stored.
 * That is the whole reason a replay costs nothing to keep, and it is
 * only sound while the rules that build a world have not changed -
 * which is what the version check below is for.
 */
public final class ReplayLauncher {
	/** Set on the polling thread, consumed by a client tick. */
	private static volatile ReplayData pending;
	private static volatile String pendingWatch;
	private static volatile String error;
	private static volatile boolean loading;

	private ReplayLauncher() {
	}

	public static boolean loading() {
		return loading;
	}

	public static String error() {
		return error;
	}

	public static void clearError() {
		error = null;
	}

	/**
	 * Fetch a match's replay and, if it can be rebuilt, start it.
	 *
	 * @param myUuid whose perspective to open on - your own run is the
	 *               one you came to see.
	 */
	public static void open(MinecraftClient client, String matchId, String myUuid) {
		if (loading) {
			return;
		}
		loading = true;
		error = null;
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			loading = false;
			return;
		}

		Thread t = new Thread(() -> {
			try {
				ReplayData data = BackendClient.getReplay(token, matchId);

				// Refuse rather than rebuild a world we cannot
				// reproduce.
				//
				// 0 means the match predates stamping - unknown, not
				// version zero - and a different number means the
				// placement rules have changed since. Either way the
				// world would come back subtly wrong: lava somewhere
				// else, different chest contents. The trace would be
				// truthful and everything around it false, which is a
				// worse outcome than saying no.
				if (data.worldSetupVersion != WorldSetupVersion.CURRENT) {
					error = data.worldSetupVersion == 0
							? "this match was played before replays existed"
							: "this match was built by a different version ("
									+ data.worldSetupVersion + ", this build makes "
									+ WorldSetupVersion.CURRENT + ")";
					return;
				}

				boolean any = data.tracks.values().stream()
						.anyMatch(tr -> tr.samples != null && !tr.samples.isEmpty());
				if (!any) {
					error = "no replay was recorded for this match";
					return;
				}

				pendingWatch = data.tracks.containsKey(myUuid)
						? myUuid
						: data.tracks.keySet().iterator().next();
				pending = data;
			} catch (Exception e) {
				error = e.getMessage();
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Replay load failed", e);
			} finally {
				loading = false;
			}
		}, "speedrunmcalt-replay-load");
		t.setDaemon(true);
		t.start();
	}

	/** Registered from the client initializer; drives the world switch. */
	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
				.END_CLIENT_TICK.register(ReplayLauncher::tick);
	}

	private static void tick(MinecraftClient client) {
		ReplayData data = pending;
		if (data == null) {
			return;
		}

		// Leave whatever world is loaded first, exactly as the
		// matchmaker does - world.disconnect() then disconnect(), from
		// a tick rather than a queued task.
		if (client.world != null) {
			client.world.disconnect();
			client.disconnect(new net.minecraft.client.gui.screen.SaveLevelScreen(
					new net.minecraft.text.TranslatableText("menu.savingLevel")));
			return;
		}

		pending = null;
		String watch = pendingWatch;
		pendingWatch = null;

		// MatchState drives the world build - MatchWorldSetup and the
		// nether-seed redirect both key off it - so a replay populates
		// the same fields. replayMode is what keeps the recorder, HUD,
		// end handling and forfeit from treating this as a live match.
		MatchState.reset();
		MatchState.replayMode = true;
		MatchState.matchId = data.matchId;
		MatchState.seedType = data.seedType;
		MatchState.overworldSeed = data.overworldSeed;
		MatchState.netherSeed = data.netherSeed;

		ReplayPlayback.begin(data, watch);
		MatchWorldCreator.createMatchWorld(client, "replay-" + data.matchId,
				data.overworldSeed, data.netherSeed);
	}

	/** Leaves a replay and returns to the title screen. */
	public static void exit(MinecraftClient client) {
		ReplayPlayback.stop();
		MatchState.reset();
		if (client.world != null) {
			client.world.disconnect();
			client.disconnect(new net.minecraft.client.gui.screen.SaveLevelScreen(
					new net.minecraft.text.TranslatableText("menu.savingLevel")));
		}
		client.openScreen(new TitleScreen());
	}
}

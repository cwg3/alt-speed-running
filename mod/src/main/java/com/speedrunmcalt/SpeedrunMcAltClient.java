package com.speedrunmcalt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// MinecraftClient.getInstance() isn't guaranteed to be assigned yet
		// during onInitializeClient() itself (confirmed against the actual
		// 1.16.1 boot order) - CLIENT_STARTED fires once the client is
		// fully constructed, which is the safe point to start.
		//
		// Auto-starting matchmaking on client launch is a placeholder for
		// real UX (a menu button) - this proves the pipeline before
		// building a real screen around it.
		ClientLifecycleEvents.CLIENT_STARTED.register(MatchFlow::start);
	}
}

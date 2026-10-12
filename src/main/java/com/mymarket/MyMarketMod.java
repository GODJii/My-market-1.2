package com.mymarket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyMarketMod implements ModInitializer {
	public static final String MOD_ID = "mymarket";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Build the price list every time a world/server starts.
		ServerLifecycleEvents.SERVER_STARTING.register(Prices::reload);
		Net.init();
		// Register /sell /shop /buy /money /balance /price
		CommandRegistrationCallback.EVENT.register(MarketCommands::register);
		LOGGER.info("My Market loaded");
	}
}

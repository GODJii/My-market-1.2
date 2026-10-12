package com.mymarket;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.effect.MobEffectInstance;

/** Client side: small money display in the top-right corner + mouse wheel scrolling in the shop. */
public class MyMarketClient implements ClientModInitializer {
	private static final float SCALE = 0.8f;
	private static final int COLOR = 0xFFFFAA00;

	/** -1 = unknown (not on a My Market server): nothing is drawn. */
	private static volatile long balance = -1;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Net.BalancePayload.TYPE,
				(payload, context) -> balance = payload.amount());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> balance = -1);
		HudRenderCallback.EVENT.register(MyMarketClient::renderHud);

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen<?>
					&& screen.getTitle().getString().startsWith(Net.SHOP_TITLE)) {
				ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
					if (vertical != 0 && ClientPlayNetworking.canSend(Net.ScrollPayload.TYPE)) {
						ClientPlayNetworking.send(new Net.ScrollPayload(vertical > 0 ? -1 : 1));
						return false; // handled, don't let anything else react
					}
					return true;
				});
			}
		});
	}

	private static void renderHud(GuiGraphics g, DeltaTracker tick) {
		long money = balance;
		Minecraft mc = Minecraft.getInstance();
		if (money < 0 || mc.player == null || mc.options.hideGui) return;

		String text = "$" + Ui.money(money);
		Font font = mc.font;
		int textWidth = (int) (font.width(text) * SCALE);
		int x = mc.getWindow().getGuiScaledWidth() - textWidth - 4;

		// Vanilla draws potion effect icons in the top-right corner; sit below them.
		int y = 3;
		boolean good = false, bad = false;
		for (MobEffectInstance e : mc.player.getActiveEffects()) {
			if (e.getEffect().value().isBeneficial()) good = true; else bad = true;
		}
		if (good) y += 26;
		if (bad) y += 26;

		g.pose().pushPose();
		g.pose().scale(SCALE, SCALE, 1f);
		g.drawString(font, text, (int) (x / SCALE), (int) (y / SCALE), COLOR, true);
		g.pose().popPose();
	}
}

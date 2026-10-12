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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.effect.MobEffectInstance;

/** Client side: small money display in the top-right corner + mouse wheel scrolling in the shop. */
public class MyMarketClient implements ClientModInitializer {
	private static final float SCALE = 0.8f;
	private static final int COLOR = 0xFFFFAA00;

	/** -1 = unknown (not on a My Market server): nothing is drawn. */
	private static volatile long balance = -1;

	/** Text of the shop search box. Survives window resizes; the server can overwrite it (/shop <text>, clear button). */
	private static String query = "";
	private static EditBox searchBox;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Net.BalancePayload.TYPE,
				(payload, context) -> balance = payload.amount());
		ClientPlayNetworking.registerGlobalReceiver(Net.PrefillPayload.TYPE, (payload, context) -> {
			query = payload.text();
			EditBox box = searchBox;
			if (box != null && Minecraft.getInstance().screen != null) {
				box.setValue(query); // note: setValue fires the responder, which re-sends the same text; harmless
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> balance = -1);
		HudRenderCallback.EVENT.register(MyMarketClient::renderHud);

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)
					|| !screen.getTitle().getString().startsWith(Net.SHOP_TITLE)) {
				searchBox = null;
				return;
			}

			// Mouse wheel scrolls the shop list
			ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
				if (vertical != 0 && ClientPlayNetworking.canSend(Net.ScrollPayload.TYPE)) {
					ClientPlayNetworking.send(new Net.ScrollPayload(vertical > 0 ? -1 : 1));
					return false; // handled, don't let anything else react
				}
				return true;
			});

			// Search box in the title row, focused right away (like the creative inventory search)
			int left = (width - 176) / 2;
			int top = (height - 222) / 2;
			EditBox box = new EditBox(client.font, left + 82, top + 4, 88, 12, Component.literal("Search"));
			box.setMaxLength(32);
			box.setHint(Component.literal("Search..."));
			box.setCanLoseFocus(false);
			box.setValue(query);
			box.setResponder(text -> {
				query = text;
				if (ClientPlayNetworking.canSend(Net.SearchPayload.TYPE)) {
					ClientPlayNetworking.send(new Net.SearchPayload(text));
				}
			});
			Screens.getButtons(screen).add(box);
			screen.setFocused(box);
			box.setFocused(true);
			searchBox = box;

			// While typing, letters (like E) must go into the box instead of closing the window.
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
				if (key == GLFW.GLFW_KEY_ESCAPE || !box.canConsumeInput()) return true;
				box.keyPressed(key, scancode, modifiers);
				return false;
			});
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

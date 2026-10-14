package com.mymarket;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/** Client side: money HUD, search + amount boxes inside the shop window, and mouse-wheel scrolling. */
public class MyMarketClient implements ClientModInitializer {
	private static final float SCALE = 0.8F;
	private static final int COLOR = 0xFFFFAA00;
	private static volatile long balance = -1L;
	private static String query = "";
	private static EditBox searchBox;
	private static String qtyText = "";
	private static EditBox qtyBox;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Net.BalancePayload.TYPE,
				(payload, context) -> balance = payload.amount());

		ClientPlayNetworking.registerGlobalReceiver(Net.PrefillPayload.TYPE, (payload, context) -> {
			query = payload.text();
			EditBox box = searchBox;
			if (box != null && Minecraft.getInstance().screen != null) {
				box.setValue(query);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(Net.AmountPrefillPayload.TYPE, (payload, context) -> {
			qtyText = payload.qty() > 0 ? String.valueOf(payload.qty()) : "";
			EditBox box = qtyBox;
			if (box != null && Minecraft.getInstance().screen != null) {
				box.setValue(qtyText);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> balance = -1L);
		HudRenderCallback.EVENT.register(MyMarketClient::renderHud);

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AbstractContainerScreen && screen.getTitle().getString().startsWith(Net.SHOP_TITLE)) {
				// Mouse wheel scrolls the shop list
				ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, horizontal, vertical) -> {
					if (vertical != 0 && ClientPlayNetworking.canSend(Net.ScrollPayload.TYPE)) {
						ClientPlayNetworking.send(new Net.ScrollPayload(vertical > 0 ? -1 : 1));
						return false;
					}
					return true;
				});

				// Search box on the title line of the chest window
				int left = (width - 176) / 2;
				int top = (height - 222) / 2;
				EditBox box = new EditBox(client.font, left + 36, top + 4, 70, 12, Component.literal("Search"));
				box.setMaxLength(32);
				box.setHint(Component.literal("Search..."));
				box.setBordered(false);
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

				// Amount box (next to the search box): how many items one click buys, 1-10000
				EditBox qty = new EditBox(client.font, left + 110, top + 4, 58, 12, Component.literal("Amount"));
				qty.setMaxLength(5);
				qty.setFilter(t -> t.matches("\\d{0,5}"));
				qty.setHint(Component.literal("Amount"));
				qty.setBordered(false);
				qty.setValue(qtyText);
				qty.setResponder(text -> {
					qtyText = text;
					int n = 0;
					try {
						n = text.isEmpty() ? 0 : Integer.parseInt(text);
					} catch (NumberFormatException ignored) {
					}
					if (ClientPlayNetworking.canSend(Net.AmountPayload.TYPE)) {
						ClientPlayNetworking.send(new Net.AmountPayload(n));
					}
				});
				Screens.getButtons(screen).add(qty);
				qtyBox = qty;

				// Keep typing letters (like "e") from closing the window: send keys to whichever box is active
				ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
					if (key == 256) return true;
					for (EditBox b : new EditBox[]{box, qty}) {
						if (b.canConsumeInput()) {
							b.keyPressed(key, scancode, modifiers);
							return false;
						}
					}
					return true;
				});
			} else {
				searchBox = null;
				qtyBox = null;
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
		int y = 3;

		// Move down so we don't overlap the potion effect icons
		boolean good = false;
		boolean bad = false;
		for (MobEffectInstance e : mc.player.getActiveEffects()) {
			if (e.getEffect().value().isBeneficial()) good = true;
			else bad = true;
		}
		if (good) y += 26;
		if (bad) y += 26;

		g.pose().pushPose();
		g.pose().scale(SCALE, SCALE, 1.0F);
		g.drawString(font, text, (int) (x / SCALE), (int) (y / SCALE), COLOR, true);
		g.pose().popPose();
	}
}

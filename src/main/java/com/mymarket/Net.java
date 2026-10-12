package com.mymarket;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Networking shared by client and server.
 * Everything here is optional for the player: without the mod on the client the shop still works
 * (arrow buttons instead of the mouse wheel, no money HUD).
 */
public final class Net {
	private Net() {}

	/** Window titles start with this; the client uses it to know when the mouse wheel should scroll the shop. */
	public static final String SHOP_TITLE = "Shop";

	private static MinecraftServer server;

	/** Server -> client: your current balance (drawn in the top-right corner). */
	public record BalancePayload(long amount) implements CustomPacketPayload {
		public static final Type<BalancePayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(MyMarketMod.MOD_ID, "balance"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BalancePayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_LONG, BalancePayload::amount, BalancePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: mouse wheel moved while the shop is open (-1 = up, 1 = down). */
	public record ScrollPayload(int dir) implements CustomPacketPayload {
		public static final Type<ScrollPayload> TYPE =
				new Type<>(ResourceLocation.fromNamespaceAndPath(MyMarketMod.MOD_ID, "scroll"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScrollPayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_INT, ScrollPayload::dir, ScrollPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(BalancePayload.TYPE, BalancePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ScrollPayload.TYPE, ScrollPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ScrollPayload.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (p.containerMenu instanceof BuyMenu menu) {
				menu.scroll(Integer.signum(payload.dir()));
			}
		});

		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> sync(handler.getPlayer().getUUID()));
	}

	/** Pushes the player's balance to their client (if they have the mod). */
	public static void sync(UUID id) {
		MinecraftServer s = server;
		if (s == null) return;
		ServerPlayer p = s.getPlayerList().getPlayer(id);
		if (p != null && ServerPlayNetworking.canSend(p, BalancePayload.TYPE)) {
			ServerPlayNetworking.send(p, new BalancePayload(MoneyData.get(s).get(id)));
		}
	}
}

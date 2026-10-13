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

/** Networking between the (optional) client part of the mod and the server. */
public final class Net {
	public static final String SHOP_TITLE = "Shop";
	private static MinecraftServer server;

	private Net() {}

	/** True if the player has this mod on the client (needed for the search box and mouse wheel). */
	public static boolean hasClientMod(ServerPlayer p) {
		return ServerPlayNetworking.canSend(p, PrefillPayload.TYPE);
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(BalancePayload.TYPE, BalancePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ScrollPayload.TYPE, ScrollPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SearchPayload.TYPE, SearchPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PrefillPayload.TYPE, PrefillPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SearchPayload.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (p.containerMenu instanceof BuyMenu menu) {
				String t = payload.text();
				menu.setQuery(t.length() > 32 ? t.substring(0, 32) : t);
			}
		});
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

	/** Sends the player's current balance to their client (for the HUD). */
	public static void sync(UUID id) {
		MinecraftServer s = server;
		if (s == null) return;
		ServerPlayer p = s.getPlayerList().getPlayer(id);
		if (p != null && ServerPlayNetworking.canSend(p, BalancePayload.TYPE)) {
			ServerPlayNetworking.send(p, new BalancePayload(MoneyData.get(s).get(id)));
		}
	}

	public record BalancePayload(long amount) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<BalancePayload> TYPE =
				new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mymarket", "balance"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BalancePayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_LONG, BalancePayload::amount, BalancePayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record PrefillPayload(String text) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<PrefillPayload> TYPE =
				new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mymarket", "prefill"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PrefillPayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PrefillPayload::text, PrefillPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record ScrollPayload(int dir) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ScrollPayload> TYPE =
				new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mymarket", "scroll"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScrollPayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.VAR_INT, ScrollPayload::dir, ScrollPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SearchPayload(String text) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SearchPayload> TYPE =
				new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("mymarket", "search"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SearchPayload> CODEC =
				StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SearchPayload::text, SearchPayload::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}

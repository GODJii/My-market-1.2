package com.mymarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Fast Hopper": a hopper that pulls items in and pushes them out many times faster than a normal one.
 * A normal hopper moves 1 item every 8 ticks (2.5 per second). A Fast Hopper moves up to 8 items EVERY tick
 * (about 160 per second); change it in config/mymarket_fasthopper.json ("itemsPerTick").
 * It works like a hopper in every other way (direction, redstone lock, items on top, chests above and below).
 * Place it like a normal hopper. Breaking it gives the Fast Hopper item back.
 */
public final class FastHopper {
	public static final String TAG = "mymarket_fast_hopper";
	/** Base value. The shop price is 2x this = 1,000. */
	public static final long VALUE = 500L;

	/** Items moved (in and out) per tick. */
	private static int itemsPerTick = 8;

	/** Hoppers placed this tick by a player holding the Fast Hopper item (confirmed at the end of the tick). */
	private static final Set<GlobalPos> PENDING = new HashSet<>();

	private FastHopper() {}

	// ---------------------------------------------------------------- the item

	public static ItemStack create() {
		ItemStack s = new ItemStack(Items.HOPPER);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Fast Hopper")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("A hopper that pulls and pushes items", ChatFormatting.YELLOW),
				Ui.text("many times faster than a normal one", ChatFormatting.YELLOW),
				Ui.text("Place it like a normal hopper", ChatFormatting.GRAY))));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	public static boolean isFastHopper(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(TAG);
	}

	// ---------------------------------------------------------------- setup

	public static void init() {
		loadConfig();
		UseBlockCallback.EVENT.register(FastHopper::onUse);
		PlayerBlockBreakEvents.BEFORE.register(FastHopper::onBreak);
		ServerTickEvents.END_SERVER_TICK.register(FastHopper::onTick);
	}

	private static void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mymarket_fasthopper.json");
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, "{\n  \"itemsPerTick\": " + itemsPerTick + "\n}\n");
				return;
			}
			try (Reader r = Files.newBufferedReader(file)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("itemsPerTick")) itemsPerTick = Math.max(1, Math.min(64, o.get("itemsPerTick").getAsInt()));
			}
		} catch (Exception ex) {
			MyMarketMod.LOGGER.error("Could not read mymarket_fasthopper.json, using defaults", ex);
		}
	}

	private static GlobalPos key(ServerLevel level, BlockPos pos) {
		return GlobalPos.of(level.dimension(), pos.immutable());
	}

	// ---------------------------------------------------------------- placing

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide || !(player instanceof ServerPlayer sp) || sp.isSpectator()) return InteractionResult.PASS;
		if (!isFastHopper(sp.getItemInHand(hand))) return InteractionResult.PASS;
		ServerLevel sl = (ServerLevel) level;

		// Let the game place it, then register it at the end of this tick (only if a hopper really appeared)
		BlockPlaceContext pc = new BlockPlaceContext(new UseOnContext(sp, hand, hit));
		if (pc.canPlace()) PENDING.add(key(sl, pc.getClickedPos()));
		return InteractionResult.PASS;
	}

	private static void processPending(MinecraftServer server) {
		FastHopperData data = FastHopperData.get(server);
		for (GlobalPos key : new ArrayList<>(PENDING)) {
			PENDING.remove(key);
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null && level.getBlockState(key.pos()).is(Blocks.HOPPER)) data.add(key);
		}
	}

	// ---------------------------------------------------------------- breaking

	private static boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be) {
		if (level.isClientSide || !(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) return true;
		FastHopperData data = FastHopperData.get(sl.getServer());
		GlobalPos key = key(sl, pos);
		if (!data.contains(key)) return true;

		data.remove(key);
		if (sp.isCreative()) return true; // the game breaks it normally
		// Break it ourselves so the player gets a Fast Hopper back (not a plain hopper)
		if (be instanceof Container own) {
			Containers.dropContents(sl, pos, own);
			own.clearContent();
		}
		Block.popResource(sl, pos, create());
		sl.removeBlock(pos, false);
		return false;
	}

	// ---------------------------------------------------------------- the work

	private static void onTick(MinecraftServer server) {
		if (!PENDING.isEmpty()) processPending(server);

		FastHopperData data = FastHopperData.get(server);
		if (data.view().isEmpty()) return;

		List<GlobalPos> gone = null;
		for (GlobalPos key : data.view()) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null) continue;
			BlockPos pos = key.pos();
			if (!level.hasChunkAt(pos)) continue; // not loaded: do nothing, keep it registered
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.HOPPER)) {
				if (gone == null) gone = new ArrayList<>();
				gone.add(key); // destroyed some other way (explosion, piston, /setblock ...)
				continue;
			}
			if (!state.getValue(HopperBlock.ENABLED)) continue; // locked by redstone
			if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) continue;
			work(level, pos, state, hopper);
		}
		if (gone != null) {
			for (GlobalPos key : gone) data.remove(key);
		}
	}

	private static void work(ServerLevel level, BlockPos pos, BlockState state, HopperBlockEntity hopper) {
		Direction facing = state.getValue(HopperBlock.FACING);
		Container dest = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
		for (int n = 0; n < itemsPerTick; n++) {
			boolean moved = false;
			if (dest != null && push(hopper, dest, facing)) moved = true;
			if (HopperBlockEntity.suckInItems(level, hopper)) moved = true;
			if (!moved) break; // nothing left to do this tick
		}
	}

	/** Moves ONE item from the hopper into the container it points at. */
	private static boolean push(HopperBlockEntity hopper, Container dest, Direction facing) {
		Direction into = facing.getOpposite();
		for (int i = 0; i < hopper.getContainerSize(); i++) {
			if (hopper.getItem(i).isEmpty()) continue;
			ItemStack one = hopper.removeItem(i, 1);
			ItemStack rest = HopperBlockEntity.addItem(hopper, dest, one, into);
			if (rest.isEmpty()) {
				dest.setChanged();
				return true;
			}
			// did not fit: put it back
			ItemStack cur = hopper.getItem(i);
			if (cur.isEmpty()) hopper.setItem(i, rest);
			else cur.grow(rest.getCount());
		}
		return false;
	}
}

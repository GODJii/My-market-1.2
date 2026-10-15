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
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "Auto Buy Chest": a chest that buys its own stock.
 *   - Buy it in the shop, place it like a normal chest. The shop opens: click the item it should buy.
 *   - Whenever the chest holds less than one stack of that item, it buys one stack every 5 seconds
 *     (money comes from the owner, at the normal shop price, even while the owner is offline).
 *   - Sneak + right-click it with an EMPTY hand to change the item or turn it off.
 *   - Enchant sparkles float above it so you can tell it from a normal chest.
 * It only works while its chunk is loaded. If the owner runs out of money it waits and tries again.
 */
public final class AutoBuyChest {
	public static final String TAG = "mymarket_auto_buy_chest";
	/** Base value. The shop price is 2x this = 15,000,000. */
	public static final long VALUE = 7_500_000L;

	// ---- settings (config/mymarket_autobuy.json, created on first run)
	/** Seconds between two refills. */
	private static int intervalSeconds = 5;
	/** Stacks bought per refill. */
	private static int stacksPerRefill = 1;
	/** A refill happens when the chest holds fewer than this many stacks of its item. */
	private static int keepStacks = 1;
	/** Auto Buy Chests one player may own. */
	private static int maxPerPlayer = 10;

	private record Pending(UUID owner, String ownerName, int tick) {}

	/** Chests that were just placed by a player holding the Auto Buy Chest item (confirmed at the end of the tick). */
	private static final Map<GlobalPos, Pending> PENDING = new HashMap<>();
	/** When the owner was last told about missing money (server tick). */
	private static final Map<GlobalPos, Integer> LAST_WARN = new HashMap<>();
	/** Chests whose owner was already told that the spending limit is used up. */
	private static final Set<GlobalPos> TOLD_BUDGET_DONE = new HashSet<>();

	private AutoBuyChest() {}

	// ---------------------------------------------------------------- the item

	public static ItemStack create() {
		ItemStack s = new ItemStack(Items.CHEST);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Auto Buy Chest")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("A chest that buys its own stock", ChatFormatting.YELLOW),
				Ui.text("Place it, then pick the item in the shop menu", ChatFormatting.GRAY),
				Ui.text("Buys 1 stack every " + intervalSeconds + "s when the chest is low", ChatFormatting.GRAY),
				Ui.text("You can set a spending limit (e.g. 20M)", ChatFormatting.GRAY),
				Ui.text("Sneak + right-click with an empty hand to change", ChatFormatting.GRAY))));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	public static boolean isAutoChest(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(TAG);
	}

	/** Items that may not be auto-bought (special shop items). */
	private static boolean forbidden(ItemStack s) {
		return GodPickaxe.isGod(s) || QuickSeller.isQuickSeller(s) || QuickBuyer.isQuickBuyer(s) || isAutoChest(s);
	}

	// ---------------------------------------------------------------- setup

	public static void init() {
		loadConfig();
		UseBlockCallback.EVENT.register(AutoBuyChest::onUse);
		PlayerBlockBreakEvents.BEFORE.register(AutoBuyChest::onBreak);
		ServerTickEvents.END_SERVER_TICK.register(AutoBuyChest::onTick);
	}

	private static void loadConfig() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mymarket_autobuy.json");
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, "{\n  \"intervalSeconds\": " + intervalSeconds
						+ ",\n  \"stacksPerRefill\": " + stacksPerRefill
						+ ",\n  \"keepStacks\": " + keepStacks
						+ ",\n  \"maxPerPlayer\": " + maxPerPlayer + "\n}\n");
				return;
			}
			try (Reader r = Files.newBufferedReader(file)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("intervalSeconds")) intervalSeconds = Math.max(1, o.get("intervalSeconds").getAsInt());
				if (o.has("stacksPerRefill")) stacksPerRefill = Math.max(1, o.get("stacksPerRefill").getAsInt());
				if (o.has("keepStacks")) keepStacks = Math.max(1, o.get("keepStacks").getAsInt());
				if (o.has("maxPerPlayer")) maxPerPlayer = Math.max(1, o.get("maxPerPlayer").getAsInt());
			}
		} catch (Exception ex) {
			MyMarketMod.LOGGER.error("Could not read mymarket_autobuy.json, using defaults", ex);
		}
	}

	public static int intervalSeconds() {
		return intervalSeconds;
	}

	public static int stacksPerRefill() {
		return stacksPerRefill;
	}

	public static int keepStacks() {
		return keepStacks;
	}

	// ---------------------------------------------------------------- helpers

	private static GlobalPos key(ServerLevel level, BlockPos pos) {
		return GlobalPos.of(level.dimension(), pos.immutable());
	}

	/** The registered chest at {@code pos}, or at the other half of the same double chest. Null if there is none. */
	public static AutoBuyData.Chest chestAt(MinecraftServer server, ServerLevel level, BlockPos pos) {
		AutoBuyData.Chest c = AutoBuyData.get(server).get(key(level, pos));
		if (c != null) return c;
		BlockPos other = otherHalf(level, pos);
		return other == null ? null : AutoBuyData.get(server).get(key(level, other));
	}

	/** Position of the registered chest that {@code pos} belongs to (itself or its double-chest partner). */
	private static BlockPos registeredPos(MinecraftServer server, ServerLevel level, BlockPos pos) {
		AutoBuyData data = AutoBuyData.get(server);
		if (data.get(key(level, pos)) != null) return pos;
		BlockPos other = otherHalf(level, pos);
		if (other != null && data.get(key(level, other)) != null) return other;
		return null;
	}

	private static BlockPos otherHalf(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (!s.is(Blocks.CHEST) || s.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		Direction d = ChestBlock.getConnectedDirection(s);
		return pos.relative(d);
	}

	private static boolean canManage(ServerPlayer p, AutoBuyData.Chest c) {
		return p.getUUID().equals(c.owner) || p.createCommandSourceStack().hasPermission(2);
	}

	// ---------------------------------------------------------------- placing + opening the menu

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide || !(player instanceof ServerPlayer sp) || sp.isSpectator()) return InteractionResult.PASS;
		ServerLevel sl = (ServerLevel) level;
		MinecraftServer server = sl.getServer();
		BlockPos pos = hit.getBlockPos();

		// 1) Sneak + right-click an Auto Buy Chest with an empty hand: choose / change / turn off the item
		if (hand == InteractionHand.MAIN_HAND && sp.isShiftKeyDown() && sp.getMainHandItem().isEmpty()) {
			BlockPos reg = registeredPos(server, sl, pos);
			if (reg != null) {
				AutoBuyData.Chest c = AutoBuyData.get(server).get(key(sl, reg));
				if (c != null) {
					if (!canManage(sp, c)) {
						sp.displayClientMessage(Ui.text("This Auto Buy Chest belongs to " + c.ownerName, ChatFormatting.RED), true);
						return InteractionResult.SUCCESS;
					}
					if (!sl.mayInteract(sp, reg)) return InteractionResult.FAIL;
					BuyMenu.openForSelect(sp, sl, reg);
					return InteractionResult.SUCCESS;
				}
			}
		}

		// 2) Placing an Auto Buy Chest: let the game place it, then register it at the end of this tick
		ItemStack held = sp.getItemInHand(hand);
		if (isAutoChest(held)) {
			if (AutoBuyData.get(server).countOwned(sp.getUUID()) + pendingCount(sp.getUUID()) >= maxPerPlayer) {
				sp.displayClientMessage(Ui.text("You can own at most " + maxPerPlayer + " Auto Buy Chests", ChatFormatting.RED), true);
				return InteractionResult.FAIL;
			}
			BlockPlaceContext pc = new BlockPlaceContext(new UseOnContext(sp, hand, hit));
			if (pc.canPlace()) {
				PENDING.put(key(sl, pc.getClickedPos()), new Pending(sp.getUUID(), sp.getName().getString(), server.getTickCount()));
			}
		}
		return InteractionResult.PASS;
	}

	private static int pendingCount(UUID owner) {
		int n = 0;
		for (Pending p : PENDING.values()) {
			if (p.owner().equals(owner)) n++;
		}
		return n;
	}

	private static void processPending(MinecraftServer server) {
		AutoBuyData data = AutoBuyData.get(server);
		for (Map.Entry<GlobalPos, Pending> e : new HashMap<>(PENDING).entrySet()) {
			GlobalPos key = e.getKey();
			Pending p = e.getValue();
			PENDING.remove(key);
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null || data.get(key) != null) continue;
			if (!level.getBlockState(key.pos()).is(Blocks.CHEST)) continue; // nothing was placed

			data.add(key, new AutoBuyData.Chest(p.owner(), p.ownerName()));
			ServerPlayer owner = server.getPlayerList().getPlayer(p.owner());
			if (owner != null && owner.containerMenu == owner.inventoryMenu) {
				owner.displayClientMessage(Ui.text("Auto Buy Chest placed. Pick the item it should buy", ChatFormatting.LIGHT_PURPLE), true);
				BuyMenu.openForSelect(owner, level, key.pos());
			}
		}
	}

	// ---------------------------------------------------------------- choosing what to buy (called by the shop menu)

	/** Sets the item. Returns true if it worked (the menu then closes). */
	public static boolean select(ServerPlayer p, ServerLevel level, BlockPos pos, ItemStack proto) {
		AutoBuyData.Chest c = AutoBuyData.get(level.getServer()).get(key(level, pos));
		if (c == null) {
			p.displayClientMessage(Ui.text("This Auto Buy Chest is gone", ChatFormatting.RED), true);
			return false;
		}
		if (!canManage(p, c)) {
			p.displayClientMessage(Ui.text("This Auto Buy Chest belongs to " + c.ownerName, ChatFormatting.RED), true);
			return false;
		}
		if (p.level() != level || p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 12.0 * 12.0) {
			p.displayClientMessage(Ui.text("You are too far from the chest", ChatFormatting.RED), true);
			return false;
		}
		ItemStack one = proto.copyWithCount(1);
		if (forbidden(one) || Prices.baseValue(one) <= 0L) {
			p.displayClientMessage(Ui.text("That item cannot be auto-bought", ChatFormatting.RED), true);
			return false;
		}
		c.target = one;
		AutoBuyData.get(level.getServer()).setDirty();
		LAST_WARN.remove(key(level, pos));
		TOLD_BUDGET_DONE.remove(key(level, pos));

		long perStack = Prices.buyPrice(Prices.baseValue(one)) * Math.min(one.getMaxStackSize(), 64);
		p.displayClientMessage(Component.literal("Auto Buy: ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(one.getHoverName().copy().withStyle(ChatFormatting.WHITE))
				.append(Component.literal("  $" + Ui.money(perStack) + " per stack").withStyle(ChatFormatting.GOLD)), true);
		return true;
	}

	/** Sets how much money the chest may spend in total. 0 = no limit. Returns true if it worked. */
	public static boolean setBudget(ServerPlayer p, ServerLevel level, BlockPos pos, long amount) {
		AutoBuyData data = AutoBuyData.get(level.getServer());
		AutoBuyData.Chest c = data.get(key(level, pos));
		if (c == null) {
			p.displayClientMessage(Ui.text("This Auto Buy Chest is gone", ChatFormatting.RED), true);
			return false;
		}
		if (!canManage(p, c)) {
			p.displayClientMessage(Ui.text("This Auto Buy Chest belongs to " + c.ownerName, ChatFormatting.RED), true);
			return false;
		}
		c.budgetLeft = amount <= 0L ? -1L : amount;
		data.setDirty();
		TOLD_BUDGET_DONE.remove(key(level, pos));
		p.displayClientMessage(c.budgetLeft < 0
				? Ui.text("Spending limit removed", ChatFormatting.YELLOW)
				: Ui.text("This chest will spend at most $" + Ui.money(c.budgetLeft) + " ("
						+ Ui.exact(c.budgetLeft) + ")", ChatFormatting.GOLD), true);
		return true;
	}

	/** Price of one item of the chest's target (0 if it has none). */
	private static long unitPrice(AutoBuyData.Chest c) {
		if (c.target.isEmpty()) return 0L;
		long value = Prices.baseValue(c.target);
		return value <= 0L ? 0L : Prices.buyPrice(value);
	}

	/** True when a spending limit is set and not even one more item fits in it. */
	private static boolean budgetUsedUp(AutoBuyData.Chest c) {
		return c.budgetLeft >= 0L && c.budgetLeft < Math.max(1L, unitPrice(c));
	}

	/** Turns the chest off. Items already inside stay. */
	public static void clear(ServerPlayer p, ServerLevel level, BlockPos pos) {
		AutoBuyData.Chest c = AutoBuyData.get(level.getServer()).get(key(level, pos));
		if (c == null || !canManage(p, c)) return;
		c.target = ItemStack.EMPTY;
		AutoBuyData.get(level.getServer()).setDirty();
		p.displayClientMessage(Ui.text("Auto Buy turned off", ChatFormatting.YELLOW), true);
	}

	// ---------------------------------------------------------------- breaking

	private static boolean onBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity be) {
		if (level.isClientSide || !(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) return true;
		AutoBuyData data = AutoBuyData.get(sl.getServer());
		GlobalPos key = key(sl, pos);
		AutoBuyData.Chest c = data.get(key);
		if (c == null) return true;

		if (!canManage(sp, c)) {
			sp.displayClientMessage(Ui.text("This Auto Buy Chest belongs to " + c.ownerName, ChatFormatting.RED), true);
			return false;
		}
		// Break it ourselves so the player gets an Auto Buy Chest back (not a plain chest)
		data.remove(key);
		LAST_WARN.remove(key);
		TOLD_BUDGET_DONE.remove(key);
		if (!sp.isCreative()) {
			if (be instanceof Container own) {
				Containers.dropContents(sl, pos, own);
				own.clearContent();
			}
			Block.popResource(sl, pos, create());
		}
		sl.removeBlock(pos, false);
		return false;
	}

	// ---------------------------------------------------------------- the work: sparkles + buying

	private static void onTick(MinecraftServer server) {
		if (!PENDING.isEmpty()) processPending(server);

		int t = server.getTickCount();
		if (t % 20 != 0) return;
		AutoBuyData data = AutoBuyData.get(server);
		if (data.isEmpty()) return;
		boolean refillNow = t % (intervalSeconds * 20) == 0;

		for (Map.Entry<GlobalPos, AutoBuyData.Chest> e : data.all().entrySet()) {
			GlobalPos key = e.getKey();
			AutoBuyData.Chest c = e.getValue();
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null) continue;
			BlockPos pos = key.pos();
			if (!level.hasChunkAt(pos)) continue; // not loaded: do nothing, keep the chest registered
			if (!level.getBlockState(pos).is(Blocks.CHEST)) {
				data.remove(key); // destroyed some other way (explosion, piston, /setblock ...)
				LAST_WARN.remove(key);
				TOLD_BUDGET_DONE.remove(key);
				continue;
			}
			boolean active = !c.target.isEmpty() && !budgetUsedUp(c);
			level.sendParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
					active ? 10 : 3, 0.35, 0.3, 0.35, 0.8);
			if (refillNow && active) refill(server, level, key, c);
		}
	}

	private static void refill(MinecraftServer server, ServerLevel level, GlobalPos key, AutoBuyData.Chest c) {
		ItemStack proto = c.target;
		long value = Prices.baseValue(proto);
		if (value <= 0L) return;
		Container box = QuickBuyer.containerAt(level, key.pos());
		if (box == null) return;

		int max = Math.min(proto.getMaxStackSize(), box.getMaxStackSize());
		if (count(box, proto) >= (long) keepStacks * max) return; // still stocked
		int room = BuyMenu.capacity(box, proto);
		int amount = (int) Math.min((long) stacksPerRefill * max, room);
		if (amount <= 0) return; // chest is full of other things

		long unit = Prices.buyPrice(value);
		if (c.budgetLeft >= 0L) {
			// Spending limit: buy only what still fits in it
			long affordable = c.budgetLeft / unit;
			if (affordable <= 0L) {
				tellBudgetDone(server, key, c);
				return;
			}
			amount = (int) Math.min(amount, affordable);
		}
		long cost = unit * amount;
		if (!MoneyData.get(server).take(c.owner, cost)) {
			warnNoMoney(server, key, c, cost, amount);
			return;
		}
		LAST_WARN.remove(key);
		if (c.budgetLeft >= 0L) {
			c.budgetLeft -= cost;
			AutoBuyData.get(server).setDirty();
			if (c.budgetLeft < unit) tellBudgetDone(server, key, c);
		}
		BuyMenu.insertInto(box, proto, amount);
		BlockPos p = key.pos();
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.0);
	}

	/** Tells the owner (once) that the spending limit is used up. */
	private static void tellBudgetDone(MinecraftServer server, GlobalPos key, AutoBuyData.Chest c) {
		if (TOLD_BUDGET_DONE.contains(key)) return;
		ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
		if (owner == null) return; // will be told the next time it checks while they are online
		BlockPos p = key.pos();
		owner.displayClientMessage(Ui.text("Auto Buy Chest at " + p.getX() + ", " + p.getY() + ", " + p.getZ()
				+ " used up its spending limit and stopped buying. Sneak + right-click it with an empty hand to set a new one.",
				ChatFormatting.GOLD), false);
		TOLD_BUDGET_DONE.add(key);
	}

	private static long count(Container box, ItemStack proto) {
		long n = 0;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack s = box.getItem(i);
			if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, proto)) n += s.getCount();
		}
		return n;
	}

	private static void warnNoMoney(MinecraftServer server, GlobalPos key, AutoBuyData.Chest c, long cost, int amount) {
		int now = server.getTickCount();
		Integer last = LAST_WARN.get(key);
		if (last != null && now - last < 6000) return; // at most once every 5 minutes
		ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
		if (owner == null) return;
		BlockPos p = key.pos();
		owner.displayClientMessage(Ui.text("Auto Buy Chest at " + p.getX() + ", " + p.getY() + ", " + p.getZ()
				+ " needs $" + Ui.money(cost) + " for " + amount + " x " + c.target.getHoverName().getString()
				+ ". Not enough money, it will keep trying.", ChatFormatting.RED), false);
		LAST_WARN.put(key, now);
	}
}

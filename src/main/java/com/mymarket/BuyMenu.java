package com.mymarket;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shop window.
 *   Top row (9 slots)      = category tabs (same tabs as the creative inventory)
 *   Middle 4 rows          = items, scroll with the mouse wheel (or the arrows at the bottom)
 *   Bottom row             = search, rarity filter, amount, balance, position, clear, scroll up/down
 * Typing in the search box (or using the search button) searches ALL categories.
 */
public class BuyMenu extends ChestMenu {
	private static final int COLS = 9;
	private static final int VISIBLE_ROWS = 4;
	private static final int GRID = COLS * VISIBLE_ROWS;
	private static final int FIRST_ITEM_SLOT = COLS; // row 0 is the category tabs
	private static final int[] QTY = {1, 16, 64};

	private static final int SLOT_SEARCH = 45;
	private static final int SLOT_FILTER = 46;
	private static final int SLOT_QTY = 47;
	private static final int SLOT_BALANCE = 49;
	private static final int SLOT_POS = 50;
	private static final int SLOT_CLEAR = 51;
	private static final int SLOT_UP = 52;
	private static final int SLOT_DOWN = 53;

	private final ServerPlayer player;
	private final SimpleContainer top;
	private final State st;
	private final boolean hasMod;
	private List<Prices.Entry> view = List.of();
	/** Time of the last successful purchase. Used to stop one click from buying several times. */
	private long lastBuyMs = 0L;
	private static final long COOLDOWN_MS = 250L;        // normal click
	private static final long STACK_COOLDOWN_MS = 800L;  // shift+click (a whole stack)

	private BuyMenu(int syncId, Inventory inv, SimpleContainer top, ServerPlayer player, State st) {
		super(MenuType.GENERIC_9x6, syncId, inv, top, 6);
		this.player = player;
		this.top = top;
		this.st = st;
		this.hasMod = Net.hasClientMod(player);
		if (hasMod) {
			ServerPlayNetworking.send(player, new Net.PrefillPayload(st.query));
		}
		rebuild();
	}

	public static void open(ServerPlayer player) {
		open(player, new State());
	}

	public static void open(ServerPlayer player, String query) {
		State s = new State();
		s.query = query == null ? "" : query.trim();
		open(player, s);
	}

	static void open(ServerPlayer player, State state) {
		SimpleContainer top = new SimpleContainer(54);
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new BuyMenu(syncId, inv, top, player, state),
				Component.literal(Net.SHOP_TITLE)));
	}

	private long balance() {
		return MoneyData.get(player.getServer()).get(player.getUUID());
	}

	private int amountFor(Prices.Entry e) {
		return Math.min(QTY[st.qtyIdx], e.stack().getMaxStackSize());
	}

	/** Called when the player types in the search box (client mod). */
	public void setQuery(String q) {
		q = q == null ? "" : q.trim();
		if (!q.equals(st.query)) {
			st.query = q;
			st.row = 0;
			rebuild();
			sendAllDataToRemote();
		}
	}

	private int totalRows() {
		return (view.size() + COLS - 1) / COLS;
	}

	private int maxRow() {
		return Math.max(0, totalRows() - VISIBLE_ROWS);
	}

	/** Called for every mouse wheel notch (client mod). */
	public void scroll(int dir) {
		scrollBy(dir);
		rebuild();
		sendAllDataToRemote();
	}

	private void scrollBy(int rows) {
		st.row = Math.max(0, Math.min(st.row + rows, maxRow()));
	}

	private boolean searching() {
		return !st.query.isEmpty();
	}

	private void rebuild() {
		List<Prices.Category> cats = Prices.categories();
		if (st.catIdx >= cats.size()) st.catIdx = 0;

		// Which items are listed: a whole category, or search results from every category
		List<Prices.Entry> base;
		if (searching() || cats.isEmpty()) base = Prices.all();
		else base = cats.get(st.catIdx).entries();

		String[] tokens = st.query.isEmpty() ? new String[0] : st.query.toLowerCase(Locale.ROOT).split("\\s+");
		Prices.Tier tier = st.tierIdx == 0 ? null : Prices.Tier.values()[st.tierIdx - 1];
		List<Prices.Entry> out = new ArrayList<>();
		for (Prices.Entry e : base) {
			if (tier != null && Prices.tierOfPrice(e.value()) != tier) continue;
			if (tokens.length > 0 && !Prices.matches(e, tokens)) continue;
			out.add(e);
		}
		view = out;
		st.row = Math.max(0, Math.min(st.row, maxRow()));

		// Row 0: category tabs
		for (int i = 0; i < COLS; i++) {
			top.setItem(i, i < cats.size() ? tabButton(cats.get(i), i) : Ui.filler());
		}

		// Rows 1-4: items
		for (int i = 0; i < GRID; i++) {
			int idx = st.row * COLS + i;
			top.setItem(FIRST_ITEM_SLOT + i, idx < view.size() ? display(view.get(idx)) : ItemStack.EMPTY);
		}

		// Row 5: controls
		for (int i = 45; i < 54; i++) top.setItem(i, Ui.filler());

		if (hasMod) {
			top.setItem(SLOT_SEARCH, Ui.button(Items.COMPASS,
					Ui.text(searching() ? "Search: " + st.query : "Search", ChatFormatting.AQUA),
					Ui.text("Type in the box at the top of this window", ChatFormatting.GRAY),
					Ui.text("(searches every category)", ChatFormatting.GRAY)));
		} else {
			top.setItem(SLOT_SEARCH, Ui.button(Items.COMPASS,
					Ui.text(searching() ? "Search: " + st.query : "Search", ChatFormatting.AQUA),
					Ui.text("Click, type a name, then click the", ChatFormatting.GRAY),
					Ui.text("green item on the right. (or use /shop <name>)", ChatFormatting.GRAY)));
		}

		String filterName = st.tierIdx == 0 ? "All" : Prices.Tier.values()[st.tierIdx - 1].label;
		ChatFormatting filterColor = st.tierIdx == 0 ? ChatFormatting.WHITE : Prices.Tier.values()[st.tierIdx - 1].color;
		top.setItem(SLOT_FILTER, Ui.button(Items.HOPPER,
				Ui.text("Rarity filter: " + filterName, filterColor),
				Ui.text("Left click = next / Right click = previous", ChatFormatting.GRAY)));
		top.setItem(SLOT_QTY, Ui.button(Items.CHEST,
				Ui.text("Amount per click: " + QTY[st.qtyIdx], ChatFormatting.YELLOW),
				Ui.text("Click to change (1 / 16 / 64)", ChatFormatting.GRAY)));
		top.setItem(SLOT_BALANCE, Ui.button(Items.GOLD_INGOT,
				Ui.text("Balance: $" + Ui.money(balance()), ChatFormatting.GOLD),
				Ui.text("Exact: " + Ui.exact(balance()), ChatFormatting.GRAY)));

		int rowsTotal = Math.max(1, totalRows());
		int rowEnd = Math.min(rowsTotal, st.row + VISIBLE_ROWS);
		top.setItem(SLOT_POS, Ui.button(Items.PAPER,
				Ui.text("Row " + (st.row + 1) + "-" + rowEnd + " of " + rowsTotal, ChatFormatting.WHITE),
				Ui.text(view.size() + " items", ChatFormatting.GRAY),
				Ui.text("Scroll the mouse wheel to browse", ChatFormatting.GRAY)));
		if (searching()) {
			top.setItem(SLOT_CLEAR, Ui.button(Items.BARRIER, Ui.text("Clear search", ChatFormatting.RED)));
		}
		if (st.row > 0) {
			top.setItem(SLOT_UP, Ui.button(Items.ARROW, Ui.text("Scroll up", ChatFormatting.YELLOW),
					Ui.text("Shift+click = 4 rows", ChatFormatting.GRAY)));
		}
		if (st.row < maxRow()) {
			top.setItem(SLOT_DOWN, Ui.button(Items.SPECTRAL_ARROW, Ui.text("Scroll down", ChatFormatting.YELLOW),
					Ui.text("Shift+click = 4 rows", ChatFormatting.GRAY)));
		}
	}

	private ItemStack tabButton(Prices.Category cat, int index) {
		boolean selected = !searching() && index == st.catIdx;
		ItemStack s = cat.icon().copy();
		ChatFormatting color = selected ? ChatFormatting.GREEN : ChatFormatting.WHITE;
		s.set(DataComponents.CUSTOM_NAME, cat.name().copy()
				.withStyle(Style.EMPTY.withColor(color).withItalic(false).withBold(selected)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text(cat.entries().size() + " items", ChatFormatting.GRAY),
				Ui.text(selected ? "Selected" : "Click to open", selected ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))));
		if (selected) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}

	private ItemStack display(Prices.Entry e) {
		ItemStack s = e.stack().copy();
		Prices.Tier t = Prices.tierOfPrice(e.value());
		List<Component> lines = new ArrayList<>();
		ItemLore existing = s.get(DataComponents.LORE);
		if (existing != null) lines.addAll(existing.lines());
		lines.add(Ui.text("Rarity: " + t.label, t.color));
		lines.add(Ui.text("Price: $" + Ui.money(Prices.buyPrice(e.value())) + " each", ChatFormatting.YELLOW));
		lines.add(Ui.text("Sells back: $" + Ui.money(e.value()) + " (bought here)", ChatFormatting.DARK_GRAY));
		lines.add(Ui.text("Sells for: $" + Ui.money(Prices.foundValue(e.value())) + " (found/crafted)", ChatFormatting.DARK_GRAY));
		lines.add(Ui.text("Click = buy " + amountFor(e), ChatFormatting.GRAY));
		lines.add(Ui.text("Right click = buy 1", ChatFormatting.GRAY));
		lines.add(Ui.text("Shift+click = buy a stack (" + e.stack().getMaxStackSize() + ")", ChatFormatting.GRAY));
		s.set(DataComponents.LORE, new ItemLore(lines));
		return s;
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		// Only plain clicks on the top 54 slots do anything. Everything else is ignored (no item moving possible).
		if (slotId >= 0 && slotId < 54 && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			boolean shift = clickType == ClickType.QUICK_MOVE;
			int dir = button == 1 ? -1 : 1;
			List<Prices.Category> cats = Prices.categories();

			if (slotId < COLS) {
				if (slotId < cats.size()) {
					st.catIdx = slotId;
					st.row = 0;
					if (searching()) {
						st.query = "";
						if (hasMod) ServerPlayNetworking.send(player, new Net.PrefillPayload(""));
					}
				}
			} else if (slotId < FIRST_ITEM_SLOT + GRID) {
				buy(slotId - FIRST_ITEM_SLOT, button, shift);
			} else if (slotId == SLOT_SEARCH) {
				if (!hasMod) {
					SearchMenu.open(player, st);
					return;
				}
			} else if (slotId == SLOT_CLEAR) {
				st.query = "";
				st.row = 0;
				if (hasMod) ServerPlayNetworking.send(player, new Net.PrefillPayload(""));
			} else if (slotId == SLOT_UP) {
				scrollBy(shift ? -VISIBLE_ROWS : -1);
			} else if (slotId == SLOT_DOWN) {
				scrollBy(shift ? VISIBLE_ROWS : 1);
			} else if (slotId == SLOT_FILTER) {
				st.tierIdx = Math.floorMod(st.tierIdx + dir, Prices.Tier.values().length + 1);
				st.row = 0;
			} else if (slotId == SLOT_QTY) {
				st.qtyIdx = Math.floorMod(st.qtyIdx + dir, QTY.length);
			}
			rebuild();
		}
		sendAllDataToRemote();
	}

	private void buy(int gridSlot, int button, boolean shift) {
		int idx = st.row * COLS + gridSlot;
		if (idx >= view.size()) return;
		Prices.Entry entry = view.get(idx);

		// One physical click = one purchase. Anything that arrives too soon after the last buy is ignored,
		// so a held-down mouse, a double click or a shift+drag can't buy several stacks.
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastBuyMs < (shift ? STACK_COOLDOWN_MS : COOLDOWN_MS)) return;

		int stack = entry.stack().getMaxStackSize();
		int amount = shift ? stack : (button == 1 ? 1 : amountFor(entry));
		long unit = Prices.buyPrice(entry.value());
		long cost = unit * amount;

		MoneyData data = MoneyData.get(player.getServer());
		if (!data.take(player.getUUID(), cost)) {
			player.displayClientMessage(Ui.text("Not enough money. Need $" + Ui.money(cost), ChatFormatting.RED), true);
			return;
		}
		lastBuyMs = nowMs;
		ItemStack give = entry.stack().copyWithCount(amount);
		Prices.markBought(give); // items bought here sell back for 50%
		MutableComponent name = give.getHoverName().copy();
		player.getInventory().add(give);
		if (!give.isEmpty()) {
			player.drop(give, false); // inventory full: drop the rest at the player's feet
		}
		player.displayClientMessage(Component.literal("Bought ").withStyle(ChatFormatting.GREEN)
				.append(name)
				.append(Component.literal(" x" + amount + "  -$" + Ui.money(cost)).withStyle(ChatFormatting.GOLD)), true);
	}

	/** Remembers what the player was looking at while moving between the shop and the search window. */
	public static final class State {
		String query = "";
		int row = 0;
		int qtyIdx = 0;
		int tierIdx = 0;
		int catIdx = 0;
	}
}

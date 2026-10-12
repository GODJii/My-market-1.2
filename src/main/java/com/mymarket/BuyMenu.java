package com.mymarket;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/**
 * Shop window: every buyable item in a 9x5 grid, cheapest first. Scroll the list instead of flipping pages:
 *   - mouse wheel (needs the mod on the client), or
 *   - the up / down arrow buttons (shift+click = jump 5 rows) for vanilla clients.
 * Click an item to buy it. Left = chosen amount, right = 1, shift+click = a full stack.
 * Bottom row: search, rarity filter, amount, balance, position, clear search, scroll up/down.
 */
public class BuyMenu extends ChestMenu {
	private static final int COLS = 9, VISIBLE_ROWS = 5, GRID = COLS * VISIBLE_ROWS;
	private static final int[] QTY = {1, 16, 64};
	private static final int SLOT_SEARCH = 45, SLOT_FILTER = 46, SLOT_QTY = 47, SLOT_BALANCE = 49,
			SLOT_POS = 50, SLOT_CLEAR = 51, SLOT_UP = 52, SLOT_DOWN = 53;

	/** Things that survive re-opening the window (e.g. after a search). */
	public static final class State {
		String query = "";
		int row = 0;
		int qtyIdx = 0;
		int tierIdx = 0; // 0 = all, 1.. = Tier.values()[tierIdx-1]
	}

	private final ServerPlayer player;
	private final SimpleContainer top;
	private final State st;
	private List<Item> view = List.of();

	private BuyMenu(int syncId, Inventory inv, SimpleContainer top, ServerPlayer player, State st) {
		super(MenuType.GENERIC_9x6, syncId, inv, top, 6);
		this.player = player;
		this.top = top;
		this.st = st;
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
		String title = state.query.isEmpty() ? Net.SHOP_TITLE : Net.SHOP_TITLE + ": " + state.query;
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new BuyMenu(syncId, inv, top, player, state),
				Component.literal(title)));
	}

	private long balance() {
		return MoneyData.get(player.getServer()).get(player.getUUID());
	}

	private int amountFor(Item item) {
		return Math.min(QTY[st.qtyIdx], item.getDefaultInstance().getMaxStackSize());
	}

	private int totalRows() {
		return (view.size() + COLS - 1) / COLS;
	}

	private int maxRow() {
		return Math.max(0, totalRows() - VISIBLE_ROWS);
	}

	/** Called by the mouse wheel packet and the arrow buttons. dir: -1 = up, 1 = down. */
	public void scroll(int dir) {
		scrollBy(dir);
		rebuild();
		sendAllDataToRemote();
	}

	private void scrollBy(int rows) {
		st.row = Math.max(0, Math.min(st.row + rows, maxRow()));
	}

	private void rebuild() {
		List<Item> all = Prices.buyable();
		String[] tokens = st.query.isEmpty() ? new String[0] : st.query.toLowerCase(Locale.ROOT).split("\\s+");
		Prices.Tier tier = st.tierIdx == 0 ? null : Prices.Tier.values()[st.tierIdx - 1];
		view = all.stream()
				.filter(i -> tier == null || Prices.tierOf(i) == tier)
				.filter(i -> tokens.length == 0 || Prices.matches(i, tokens))
				.toList();
		st.row = Math.max(0, Math.min(st.row, maxRow()));

		for (int i = 0; i < GRID; i++) {
			int idx = st.row * COLS + i;
			top.setItem(i, idx < view.size() ? display(view.get(idx)) : ItemStack.EMPTY);
		}
		for (int i = GRID; i < 54; i++) top.setItem(i, Ui.filler());

		top.setItem(SLOT_SEARCH, Ui.button(Items.OAK_SIGN,
				Ui.text(st.query.isEmpty() ? "Search" : "Search: " + st.query, ChatFormatting.AQUA),
				Ui.text("Click, type a name, then click the", ChatFormatting.GRAY),
				Ui.text("paper on the right. (or use /shop <name>)", ChatFormatting.GRAY)));

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
		if (!st.query.isEmpty()) {
			top.setItem(SLOT_CLEAR, Ui.button(Items.BARRIER, Ui.text("Clear search", ChatFormatting.RED)));
		}
		if (st.row > 0) {
			top.setItem(SLOT_UP, Ui.button(Items.ARROW, Ui.text("Scroll up", ChatFormatting.YELLOW),
					Ui.text("Shift+click = 5 rows", ChatFormatting.GRAY)));
		}
		if (st.row < maxRow()) {
			top.setItem(SLOT_DOWN, Ui.button(Items.SPECTRAL_ARROW, Ui.text("Scroll down", ChatFormatting.YELLOW),
					Ui.text("Shift+click = 5 rows", ChatFormatting.GRAY)));
		}
	}

	private ItemStack display(Item item) {
		ItemStack s = item.getDefaultInstance();
		Prices.Tier t = Prices.tierOf(item);
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("Rarity: " + t.label, t.color),
				Ui.text("Price: $" + Ui.money(Prices.buyPrice(item)) + " each", ChatFormatting.YELLOW),
				Ui.text("Click = buy " + amountFor(item), ChatFormatting.GRAY),
				Ui.text("Right click = buy 1", ChatFormatting.GRAY),
				Ui.text("Shift+click = buy a stack (" + item.getDefaultInstance().getMaxStackSize() + ")",
						ChatFormatting.GRAY))));
		return s;
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		// Only plain clicks on the top 54 slots do anything. All else is ignored (no item moving possible).
		if (slotId >= 0 && slotId < 54 && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			boolean shift = clickType == ClickType.QUICK_MOVE;
			int dir = button == 1 ? -1 : 1;
			if (slotId < GRID) {
				buy(slotId, button, shift);
			} else if (slotId == SLOT_SEARCH) {
				SearchMenu.open(player, st);
				return; // a new window is opening
			} else if (slotId == SLOT_CLEAR) {
				st.query = "";
				st.row = 0;
				reopen();
				return;
			} else if (slotId == SLOT_UP) {
				scrollBy(shift ? -5 : -1);
			} else if (slotId == SLOT_DOWN) {
				scrollBy(shift ? 5 : 1);
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

	/** Re-opens the window so the title matches the new search text. */
	private void reopen() {
		open(player, st);
	}

	private void buy(int slotId, int button, boolean shift) {
		int idx = st.row * COLS + slotId;
		if (idx >= view.size()) return;
		Item item = view.get(idx);

		int stack = item.getDefaultInstance().getMaxStackSize();
		int amount = shift ? stack : (button == 1 ? 1 : amountFor(item));
		long unit = Prices.buyPrice(item);
		long cost = unit * amount;
		MoneyData data = MoneyData.get(player.getServer());

		if (!data.take(player.getUUID(), cost)) {
			player.displayClientMessage(
					Ui.text("Not enough money. Need $" + Ui.money(cost), ChatFormatting.RED), true);
			return;
		}
		ItemStack give = new ItemStack(item, amount);
		MutableComponent name = give.getHoverName().copy();
		player.getInventory().add(give);
		if (!give.isEmpty()) {
			player.drop(give, false); // inventory full: drop the rest at the player's feet
		}
		player.displayClientMessage(Component.literal("Bought ").withStyle(ChatFormatting.GREEN)
				.append(name)
				.append(Component.literal(" x" + amount + "  -$" + Ui.money(cost)).withStyle(ChatFormatting.GOLD)), true);
	}
}

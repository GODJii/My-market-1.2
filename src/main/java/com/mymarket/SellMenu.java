package com.mymarket;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Sell window. The top row holds buttons; your own inventory is shown below.
 * Click an item in your inventory to sell it immediately. Nothing can be moved around.
 *   Left click  = sell the whole stack
 *   Right click = sell 1
 *   Shift+click = sell every stack of that item in your inventory
 * Every item sells for 80% of its shop price.
 */
public class SellMenu extends ChestMenu {
	private static final int SLOT_BALANCE = 0;
	private static final int SLOT_HELP = 2;
	private static final int SLOT_SELL_ALL = 4;
	private static final int SLOT_CLOSE = 8;
	private static final int FIRST_PLAYER_SLOT = 9;   // main inventory 9..35
	private static final int FIRST_HOTBAR_SLOT = 36;  // hotbar 36..44
	private static final int END_PLAYER_SLOT = 45;

	private final ServerPlayer player;
	private final SimpleContainer top;

	private SellMenu(int syncId, Inventory inv, SimpleContainer top, ServerPlayer player) {
		super(MenuType.GENERIC_9x1, syncId, inv, top, 1);
		this.player = player;
		this.top = top;
		refresh();
	}

	public static void open(ServerPlayer player) {
		SimpleContainer top = new SimpleContainer(9);
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SellMenu(syncId, inv, top, player),
				Component.literal("Sell")));
	}

	private long balance() {
		return MoneyData.get(player.getServer()).get(player.getUUID());
	}

	private void refresh() {
		for (int i = 0; i < 9; i++) top.setItem(i, Ui.filler());
		top.setItem(SLOT_BALANCE, Ui.button(Items.GOLD_INGOT,
				Ui.text("Balance: $" + Ui.money(balance()), ChatFormatting.GOLD),
				Ui.text("Exact: " + Ui.exact(balance()), ChatFormatting.GRAY)));
		top.setItem(SLOT_HELP, Ui.button(Items.BOOK,
				Ui.text("How to sell", ChatFormatting.YELLOW),
				Ui.text("Left click = sell the whole stack", ChatFormatting.GRAY),
				Ui.text("Right click = sell 1", ChatFormatting.GRAY),
				Ui.text("Shift+click = sell every stack of this item", ChatFormatting.GRAY),
				Ui.text("Every item sells for 80% of the shop price", ChatFormatting.GREEN)));
		top.setItem(SLOT_SELL_ALL, Ui.button(Items.EMERALD_BLOCK,
				Ui.text("Sell everything in inventory", ChatFormatting.GREEN),
				Ui.text("Hotbar (bottom 9 slots) is not included", ChatFormatting.GRAY)));
		top.setItem(SLOT_CLOSE, Ui.button(Items.BARRIER, Ui.text("Close", ChatFormatting.RED)));
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		if (slotId == SLOT_CLOSE) {
			player.closeContainer();
			return;
		}
		if (slotId == SLOT_SELL_ALL) {
			sellAll();
		} else if (slotId >= FIRST_PLAYER_SLOT && slotId < END_PLAYER_SLOT) {
			sellFromSlot(slotId, button, clickType);
		}
		// Every other click is ignored on purpose, so nothing can be moved or duplicated.
		refresh();
		sendAllDataToRemote();
	}

	/** Returns false and tells the player why if the stack can't be sold. */
	private boolean canSell(ItemStack stack) {
		if (Prices.unitSell(stack) <= 0) {
			player.displayClientMessage(Ui.text("This item cannot be sold", ChatFormatting.RED), true);
			return false;
		}
		if (Prices.hasHiddenContents(stack)) {
			player.displayClientMessage(Ui.text("It has items inside. Empty it first", ChatFormatting.RED), true);
			return false;
		}
		return true;
	}

	private void sellFromSlot(int slotId, int button, ClickType clickType) {
		Slot slot = this.slots.get(slotId);
		ItemStack stack = slot.getItem();
		if (stack.isEmpty() || !canSell(stack)) return;

		Component name = stack.getHoverName();
		long total = 0;
		int count = 0;

		if (clickType == ClickType.QUICK_MOVE) {
			ItemStack reference = stack.copy();
			for (int i = FIRST_PLAYER_SLOT; i < END_PLAYER_SLOT; i++) {
				Slot s = this.slots.get(i);
				ItemStack st = s.getItem();
				if (st.isEmpty() || !st.is(reference.getItem()) || Prices.hasHiddenContents(st)) continue;
				long unit = Prices.unitSell(st);
				if (unit <= 0) continue;
				total += unit * st.getCount();
				count += st.getCount();
				s.set(ItemStack.EMPTY);
			}
		} else if (clickType == ClickType.PICKUP) {
			int amount = button == 1 ? 1 : stack.getCount();
			total = Prices.unitSell(stack) * amount;
			count = amount;
			stack.shrink(amount);
			slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack);
		} else {
			return;
		}
		pay(name, count, total);
	}

	private void sellAll() {
		long total = 0;
		int count = 0;
		for (int i = FIRST_PLAYER_SLOT; i < FIRST_HOTBAR_SLOT; i++) {
			Slot s = this.slots.get(i);
			ItemStack st = s.getItem();
			if (st.isEmpty() || Prices.hasHiddenContents(st)) continue;
			long unit = Prices.unitSell(st);
			if (unit <= 0) continue;
			total += unit * st.getCount();
			count += st.getCount();
			s.set(ItemStack.EMPTY);
		}
		if (count == 0) {
			player.displayClientMessage(Ui.text("Nothing to sell", ChatFormatting.RED), true);
			return;
		}
		pay(Component.literal("Many items"), count, total);
	}

	private void pay(Component name, int count, long total) {
		MoneyData.get(player.getServer()).add(player.getUUID(), total);
		MutableComponent msg = Component.literal("Sold ").withStyle(ChatFormatting.GREEN)
				.append(name)
				.append(Component.literal(" x" + count + "  +$" + Ui.money(total)).withStyle(ChatFormatting.GOLD));
		player.displayClientMessage(msg, true);
	}
}

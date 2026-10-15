package com.mymarket;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Window for players WITHOUT the client mod: type in the anvil, then click the green result.
 * Used for searching the shop, for typing a custom amount (1-10000) and for typing the spending limit of an
 * Auto Buy Chest (works for everybody, with or without the client mod).
 */
public class SearchMenu extends AnvilMenu {
	private static final int SLOT_RESULT = 2;
	private final ServerPlayer viewer;
	private final BuyMenu.State state;
	private final boolean amountMode;
	private final boolean budgetMode;
	private String typed;

	private SearchMenu(int syncId, Inventory inv, ServerPlayer player, BuyMenu.State state, boolean amountMode,
					   boolean budgetMode) {
		super(syncId, inv, ContainerLevelAccess.NULL);
		this.viewer = player;
		this.state = state;
		this.amountMode = amountMode;
		this.budgetMode = budgetMode;
		String start;
		if (budgetMode) {
			AutoBuyData.Chest chest = AutoBuyChest.chestAt(player.getServer(), state.selectLevel, state.selectPos);
			start = chest != null && chest.budgetLeft >= 0L ? String.valueOf(chest.budgetLeft) : "";
		} else {
			start = amountMode ? (state.customQty > 0 ? String.valueOf(state.customQty) : "") : state.query;
		}
		this.typed = start;
		ItemStack input = new ItemStack(Items.PAPER);
		input.set(DataComponents.CUSTOM_NAME, Component.literal(start));
		this.inputSlots.setItem(0, input);
		createResult();
	}

	static void open(ServerPlayer player, BuyMenu.State state) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SearchMenu(syncId, inv, player, state, false, false),
				Component.literal("Search the shop")));
	}

	static void openAmount(ServerPlayer player, BuyMenu.State state) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SearchMenu(syncId, inv, player, state, true, false),
				Component.literal("Type the amount (1-" + BuyMenu.MAX_AMOUNT + ")")));
	}

	static void openBudget(ServerPlayer player, BuyMenu.State state) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SearchMenu(syncId, inv, player, state, false, true),
				Component.literal("Type the spending limit (e.g. 20M)")));
	}

	/** The digits typed so far as a number, limited to 0..10000. */
	private int typedAmount() {
		String digits = typed.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) return 0;
		if (digits.length() > 5) return BuyMenu.MAX_AMOUNT;
		return Math.max(0, Math.min(Integer.parseInt(digits), BuyMenu.MAX_AMOUNT));
	}

	@Override
	public boolean setItemName(String name) {
		this.typed = name == null ? "" : name.trim();
		createResult();
		broadcastChanges();
		return true;
	}

	@Override
	public void createResult() {
		ItemStack button = new ItemStack(Items.LIME_DYE);
		String label;
		ChatFormatting color = ChatFormatting.GREEN;
		if (budgetMode) {
			long money = Ui.parseMoney(typed);
			if (money < 0L) {
				label = "Not a valid amount. Try 20M, 5000000 or 2.5B";
				color = ChatFormatting.RED;
			} else if (money == 0L) {
				label = "Click: no spending limit";
			} else {
				label = "Click: spend at most $" + Ui.money(money);
			}
		} else if (amountMode) {
			int n = typedAmount();
			label = n > 0 ? "Click to buy " + n + " per click" : "Click to turn the custom amount off";
		} else {
			label = typed.isEmpty() ? "Click to show everything" : "Click to search: " + typed;
		}
		button.set(DataComponents.CUSTOM_NAME, Ui.text(label, color));
		this.resultSlots.setItem(0, button);
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		if (slotId == SLOT_RESULT && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			if (budgetMode) {
				long money = Ui.parseMoney(typed);
				if (money < 0L) {
					viewer.displayClientMessage(Ui.text("Not a valid amount. Try 20M, 5000000 or 2.5B", ChatFormatting.RED), true);
					sendAllDataToRemote();
					return;
				}
				AutoBuyChest.setBudget(viewer, state.selectLevel, state.selectPos, money);
			} else if (amountMode) {
				state.customQty = typedAmount();
			} else {
				state.query = typed;
				state.row = 0;
			}
			BuyMenu.open(viewer, state);
		} else {
			sendAllDataToRemote();
		}
	}

	@Override
	public ItemStack quickMoveStack(Player p, int index) {
		return ItemStack.EMPTY;
	}
}

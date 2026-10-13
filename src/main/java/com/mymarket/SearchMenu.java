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

/** Search window for players WITHOUT the client mod: type a name in the anvil, click the result. */
public class SearchMenu extends AnvilMenu {
	private static final int SLOT_RESULT = 2;
	private final ServerPlayer viewer;
	private final BuyMenu.State state;
	private String typed;

	private SearchMenu(int syncId, Inventory inv, ServerPlayer player, BuyMenu.State state) {
		super(syncId, inv, ContainerLevelAccess.NULL);
		this.viewer = player;
		this.state = state;
		this.typed = state.query;
		ItemStack input = new ItemStack(Items.PAPER);
		input.set(DataComponents.CUSTOM_NAME, Component.literal(state.query));
		this.inputSlots.setItem(0, input);
		createResult();
	}

	static void open(ServerPlayer player, BuyMenu.State state) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SearchMenu(syncId, inv, player, state),
				Component.literal("Search the shop")));
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
		button.set(DataComponents.CUSTOM_NAME, Ui.text(
				typed.isEmpty() ? "Click to show everything" : "Click to search: " + typed, ChatFormatting.GREEN));
		this.resultSlots.setItem(0, button);
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		if (slotId == SLOT_RESULT && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			state.query = typed;
			state.row = 0;
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

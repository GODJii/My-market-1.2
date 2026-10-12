package com.mymarket;

import net.minecraft.ChatFormatting;
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
 * Text box for the shop search. It is a normal anvil window used only as a text field:
 * type in the name box, then click the paper in the result slot to search.
 * Nothing can be taken out or moved, and no XP is used.
 */
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
		ItemStack input = new ItemStack(Items.NAME_TAG);
		input.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(state.query));
		this.inputSlots.setItem(0, input);
		createResult();
	}

	static void open(ServerPlayer player, BuyMenu.State state) {
		player.openMenu(new SimpleMenuProvider(
				(syncId, inv, p) -> new SearchMenu(syncId, inv, player, state),
				Component.literal("Search the shop")));
	}

	/** Called whenever the player types in the name box. */
	@Override
	public boolean setItemName(String name) {
		this.typed = name == null ? "" : name.trim();
		createResult();
		broadcastChanges();
		return true;
	}

	/** The result slot is always a "click to search" button. */
	@Override
	public void createResult() {
		ItemStack button = new ItemStack(Items.PAPER);
		button.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				Ui.text(typed.isEmpty() ? "Click to show everything" : "Click to search: " + typed, ChatFormatting.GREEN));
		this.resultSlots.setItem(0, button);
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player p) {
		if (slotId == SLOT_RESULT && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
			state.query = typed;
			state.row = 0;
			BuyMenu.open(viewer, state);
			return;
		}
		// Everything else is ignored so nothing can be taken or duplicated.
		sendAllDataToRemote();
	}

	@Override
	public ItemStack quickMoveStack(Player p, int index) {
		return ItemStack.EMPTY;
	}
}

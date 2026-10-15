package com.mymarket;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * The "Quick Seller": right-click a chest / barrel / shulker box / hopper / dispenser / dropper with it
 * and everything sellable inside is sold at once. It is never used up.
 * Not sold: enchanted gear, renamed items, boxes with stuff inside, special items, anything with no price.
 */
public final class QuickSeller {
	public static final String TAG = "mymarket_quick_seller";
	/** Base value. The shop price is 2x this = 2,500,000. */
	public static final long VALUE = 1_250_000L;

	private QuickSeller() {}

	public static ItemStack create() {
		ItemStack s = new ItemStack(Items.NETHER_STAR);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Quick Seller")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("Right-click a chest, barrel or shulker box", ChatFormatting.YELLOW),
				Ui.text("to sell everything inside at once", ChatFormatting.YELLOW),
				Ui.text("Keeps enchanted, renamed and unsellable items", ChatFormatting.GRAY),
				Ui.text("Never used up", ChatFormatting.GRAY))));
		s.set(DataComponents.MAX_STACK_SIZE, 1);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	public static boolean isQuickSeller(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(TAG);
	}

	public static void init() {
		UseBlockCallback.EVENT.register(QuickSeller::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (player.isSpectator()) return InteractionResult.PASS;
		if (!isQuickSeller(player.getItemInHand(hand))) return InteractionResult.PASS;

		BlockPos pos = hit.getBlockPos();
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof RandomizableContainerBlockEntity)) return InteractionResult.PASS; // not a storage box

		// Client: just stop the box from opening. The server does the selling.
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
		ServerLevel sl = (ServerLevel) level;
		if (!sl.mayInteract(player, pos)) return InteractionResult.FAIL; // spawn protection etc.

		Container box = (Container) be;
		BlockState state = level.getBlockState(pos);
		if (be instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock chest) {
			Container whole = ChestBlock.getContainer(chest, state, level, pos, true); // both halves of a double chest
			if (whole != null) box = whole;
		}
		sellBox(sp, box);
		return InteractionResult.SUCCESS;
	}

	private static boolean sellable(ItemStack st) {
		if (GodPickaxe.isGod(st) || isQuickSeller(st) || QuickBuyer.isQuickBuyer(st) || AutoBuyChest.isAutoChest(st)
				|| FastHopper.isFastHopper(st)) return false;
		if (st.has(DataComponents.CUSTOM_NAME) || st.isEnchanted()) return false;
		if (Prices.hasHiddenContents(st)) return false;
		return Prices.unitSell(st) > 0L;
	}

	private static void sellBox(ServerPlayer p, Container box) {
		long total = 0L;
		int count = 0;
		int kept = 0;
		for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack st = box.getItem(i);
			if (st.isEmpty()) continue;
			if (!sellable(st)) {
				kept++;
				continue;
			}
			total += Prices.unitSell(st) * st.getCount();
			count += st.getCount();
			box.setItem(i, ItemStack.EMPTY);
		}
		box.setChanged();

		if (count == 0) {
			p.displayClientMessage(Ui.text("Nothing sellable in this box", ChatFormatting.RED), true);
			return;
		}
		MoneyData.get(p.getServer()).add(p.getUUID(), total);
		Component msg = Component.literal("Sold " + count + " items from the box  +$" + Ui.money(total))
				.withStyle(ChatFormatting.GREEN);
		if (kept > 0) msg = msg.copy().append(Component.literal("  (" + kept + " stacks kept)").withStyle(ChatFormatting.GRAY));
		p.displayClientMessage(msg, true);
	}
}

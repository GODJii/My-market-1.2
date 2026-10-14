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
 * The "Quick Buyer": right-click a chest / barrel / shulker box / hopper / dispenser / dropper with it
 * and the shop opens. Everything you buy goes straight into that box (great for farms).
 * Use the Amount box (or the "Custom amount" button) to buy up to 10,000 at a time.
 * It is never used up.
 */
public final class QuickBuyer {
	public static final String TAG = "mymarket_quick_buyer";
	/** Base value. The shop price is 2x this = 2,500,000. */
	public static final long VALUE = 1_250_000L;

	private QuickBuyer() {}

	public static ItemStack create() {
		ItemStack s = new ItemStack(Items.ECHO_SHARD);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Quick Buyer")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("Right-click a chest, barrel or shulker box", ChatFormatting.YELLOW),
				Ui.text("to open the shop. Everything you buy", ChatFormatting.YELLOW),
				Ui.text("goes straight into that box", ChatFormatting.YELLOW),
				Ui.text("Buy up to 10,000 at a time", ChatFormatting.GRAY),
				Ui.text("Never used up", ChatFormatting.GRAY))));
		s.set(DataComponents.MAX_STACK_SIZE, 1);
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	public static boolean isQuickBuyer(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(TAG);
	}

	public static void init() {
		UseBlockCallback.EVENT.register(QuickBuyer::onUse);
	}

	private static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (player.isSpectator()) return InteractionResult.PASS;
		if (!isQuickBuyer(player.getItemInHand(hand))) return InteractionResult.PASS;

		BlockPos pos = hit.getBlockPos();
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof RandomizableContainerBlockEntity)) return InteractionResult.PASS; // not a storage box

		// Client: just stop the box from opening. The server opens the shop.
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
		ServerLevel sl = (ServerLevel) level;
		if (!sl.mayInteract(player, pos)) return InteractionResult.FAIL; // spawn protection etc.

		BuyMenu.openForBox(sp, sl, pos);
		return InteractionResult.SUCCESS;
	}

	/** The storage at {@code pos} (both halves of a double chest), or null if there is none any more. */
	public static Container containerAt(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof RandomizableContainerBlockEntity)) return null;
		Container box = (Container) be;
		BlockState state = level.getBlockState(pos);
		if (be instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock chest) {
			Container whole = ChestBlock.getContainer(chest, state, level, pos, true);
			if (whole != null) box = whole;
		}
		return box;
	}
}

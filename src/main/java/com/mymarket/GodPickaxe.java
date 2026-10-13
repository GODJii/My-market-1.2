package com.mymarket;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/** The "GOD Pickaxe": a netherite pickaxe that mines a 3x3 area (sneak = one block). */
public final class GodPickaxe {
	public static final String TAG = "mymarket_god";
	private static boolean busy = false;

	private GodPickaxe() {}

	public static ItemStack create() {
		ItemStack s = new ItemStack(Items.NETHERITE_PICKAXE);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("GOD Pickaxe")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true).withItalic(false)));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				Ui.text("Mines a 3x3 area", ChatFormatting.YELLOW),
				Ui.text("Sneak to mine a single block", ChatFormatting.GRAY),
				Ui.text("Never breaks", ChatFormatting.GRAY))));
		s.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return s;
	}

	public static boolean isGod(ItemStack s) {
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(TAG);
	}

	public static void init() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (busy || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel)) return true;
			if (sp.isShiftKeyDown() || !isGod(sp.getMainHandItem())) return true;

			// Which face of the block is the player mining? (decides the 3x3 plane)
			HitResult hit = sp.pick(6.0D, 1.0F, false);
			if (!(hit instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(pos)) return true;
			Direction.Axis axis = bhr.getDirection().getAxis();

			busy = true;
			try {
				for (int a = -1; a <= 1; a++) {
					for (int b = -1; b <= 1; b++) {
						if (a == 0 && b == 0) continue;
						BlockPos target = switch (axis) {
							case X -> pos.offset(0, a, b);
							case Y -> pos.offset(a, 0, b);
							case Z -> pos.offset(a, b, 0);
						};
						BlockState bs = level.getBlockState(target);
						if (bs.isAir() || !bs.is(BlockTags.MINEABLE_WITH_PICKAXE)) continue;
						if (bs.getDestroySpeed(level, target) < 0) continue; // bedrock etc.
						sp.gameMode.destroyBlock(target);
					}
				}
			} finally {
				busy = false;
			}
			return true; // the middle block breaks normally
		});
	}
}

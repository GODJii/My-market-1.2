package com.mymarket;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/** Small helpers for building GUI buttons and text. */
public final class Ui {
	private Ui() {}

	public static Component text(String s, ChatFormatting color) {
		return Component.literal(s).withStyle(Style.EMPTY.withColor(color).withItalic(false));
	}

	private static final String[] SUFFIX = {"", "K", "M", "B", "T", "Qa", "Qi"};

	/** Compact money: 1-999, then 1K, 1.5M, 2.25B, T, Qa, Qi. Always rounds DOWN so it never shows more than you have. */
	public static String money(long v) {
		if (v < 0) return "-" + money(-v);
		if (v < 1000) return Long.toString(v);
		int idx = 0;
		long pow = 1;
		while (idx < SUFFIX.length - 1 && v / pow >= 1000) {
			pow *= 1000;
			idx++;
		}
		long whole = v / pow;
		int frac = (int) Math.floor((double) (v % pow) / pow * 100);
		String f = frac == 0 ? "" : (frac % 10 == 0 ? "." + (frac / 10) : String.format(Locale.US, ".%02d", frac));
		return whole + f + SUFFIX[idx];
	}

	/** Full number with thousands separators, e.g. 4,706,928. */
	public static String exact(long v) {
		return String.format(Locale.US, "%,d", v);
	}

	public static ItemStack button(Item item, Component name, Component... lore) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.CUSTOM_NAME, name);
		if (lore.length > 0) s.set(DataComponents.LORE, new ItemLore(List.of(lore)));
		return s;
	}

	public static ItemStack filler() {
		return button(Items.GRAY_STAINED_GLASS_PANE, text(" ", ChatFormatting.GRAY));
	}
}

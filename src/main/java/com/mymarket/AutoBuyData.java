package com.mymarket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Every Auto Buy Chest in the world: where it is, who owns it and what it buys. Saved inside the world. */
public class AutoBuyData extends SavedData {
	public static final class Chest {
		public final UUID owner;
		public final String ownerName;
		/** What this chest buys (one item, with its components). Empty = switched off. */
		public ItemStack target = ItemStack.EMPTY;
		/** Money this chest may still spend. -1 = no limit. */
		public long budgetLeft = -1L;

		public Chest(UUID owner, String ownerName) {
			this.owner = owner;
			this.ownerName = ownerName;
		}
	}

	private final Map<GlobalPos, Chest> chests = new HashMap<>();

	public static final SavedData.Factory<AutoBuyData> FACTORY =
			new SavedData.Factory<>(AutoBuyData::new, AutoBuyData::load, null);

	public static AutoBuyData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mymarket_autobuy");
	}

	public Chest get(GlobalPos key) {
		return chests.get(key);
	}

	public void add(GlobalPos key, Chest chest) {
		chests.put(key, chest);
		setDirty();
	}

	public void remove(GlobalPos key) {
		if (chests.remove(key) != null) setDirty();
	}

	/** A copy, safe to loop over while adding or removing. */
	public Map<GlobalPos, Chest> all() {
		return new HashMap<>(chests);
	}

	public boolean isEmpty() {
		return chests.isEmpty();
	}

	public int countOwned(UUID owner) {
		int n = 0;
		for (Chest c : chests.values()) {
			if (c.owner.equals(owner)) n++;
		}
		return n;
	}

	public static AutoBuyData load(CompoundTag tag, HolderLookup.Provider lookup) {
		AutoBuyData data = new AutoBuyData();
		ListTag list = tag.getList("chests", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			try {
				CompoundTag t = list.getCompound(i);
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dim")));
				GlobalPos key = GlobalPos.of(dim, BlockPos.of(t.getLong("pos")));
				Chest c = new Chest(UUID.fromString(t.getString("owner")), t.getString("name"));
				if (t.contains("item")) {
					c.target = ItemStack.parse(lookup, t.get("item")).orElse(ItemStack.EMPTY);
				}
				c.budgetLeft = t.contains("budget") ? t.getLong("budget") : -1L;
				data.chests.put(key, c);
			} catch (RuntimeException ignored) {
				// a broken entry is skipped instead of breaking the world
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
		ListTag list = new ListTag();
		chests.forEach((key, c) -> {
			CompoundTag t = new CompoundTag();
			t.putString("dim", key.dimension().location().toString());
			t.putLong("pos", key.pos().asLong());
			t.putString("owner", c.owner.toString());
			t.putString("name", c.ownerName);
			t.putLong("budget", c.budgetLeft);
			if (!c.target.isEmpty()) t.put("item", c.target.save(lookup));
			list.add(t);
		});
		tag.put("chests", list);
		return tag;
	}
}

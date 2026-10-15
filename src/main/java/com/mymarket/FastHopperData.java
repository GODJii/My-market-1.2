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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/** Where the Fast Hoppers are. Saved inside the world. */
public class FastHopperData extends SavedData {
	private final Set<GlobalPos> hoppers = new HashSet<>();

	public static final SavedData.Factory<FastHopperData> FACTORY =
			new SavedData.Factory<>(FastHopperData::new, FastHopperData::load, null);

	public static FastHopperData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mymarket_fasthopper");
	}

	public boolean contains(GlobalPos key) {
		return hoppers.contains(key);
	}

	public void add(GlobalPos key) {
		if (hoppers.add(key)) setDirty();
	}

	public void remove(GlobalPos key) {
		if (hoppers.remove(key)) setDirty();
	}

	/** The live set. Do not add or remove while looping over it. */
	public Set<GlobalPos> view() {
		return hoppers;
	}

	public static FastHopperData load(CompoundTag tag, HolderLookup.Provider lookup) {
		FastHopperData data = new FastHopperData();
		ListTag list = tag.getList("hoppers", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			try {
				CompoundTag t = list.getCompound(i);
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dim")));
				data.hoppers.add(GlobalPos.of(dim, BlockPos.of(t.getLong("pos"))));
			} catch (RuntimeException ignored) {
				// a broken entry is skipped instead of breaking the world
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
		ListTag list = new ListTag();
		for (GlobalPos key : hoppers) {
			CompoundTag t = new CompoundTag();
			t.putString("dim", key.dimension().location().toString());
			t.putLong("pos", key.pos().asLong());
			list.add(t);
		}
		tag.put("hoppers", list);
		return tag;
	}
}

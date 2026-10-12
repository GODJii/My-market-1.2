package com.mymarket;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Stores every player's money as a plain number, saved inside the world. */
public class MoneyData extends SavedData {
	private final Map<UUID, Long> balances = new HashMap<>();

	public static final SavedData.Factory<MoneyData> FACTORY =
			new SavedData.Factory<>(MoneyData::new, MoneyData::load, null);

	public static MoneyData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mymarket_money");
	}

	public long get(UUID id) {
		return balances.getOrDefault(id, 0L);
	}

	public void add(UUID id, long amount) {
		long now = get(id) + amount;
		if (now < 0) now = Long.MAX_VALUE; // overflow guard
		balances.put(id, now);
		setDirty();
		Net.sync(id);
	}

	/** Returns false (and changes nothing) if the player does not have enough. */
	public boolean take(UUID id, long amount) {
		long have = get(id);
		if (have < amount) return false;
		balances.put(id, have - amount);
		setDirty();
		Net.sync(id);
		return true;
	}

	public static MoneyData load(CompoundTag tag, HolderLookup.Provider lookup) {
		MoneyData data = new MoneyData();
		CompoundTag map = tag.getCompound("balances");
		for (String key : map.getAllKeys()) {
			try {
				data.balances.put(UUID.fromString(key), map.getLong(key));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
		CompoundTag map = new CompoundTag();
		balances.forEach((id, value) -> map.putLong(id.toString(), value));
		tag.put("balances", map);
		return tag;
	}
}

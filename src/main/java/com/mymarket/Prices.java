package com.mymarket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prices, shop categories and the sell rules.
 *
 * value  = base value of an item (what a shop-bought item sells back for = 50% of the shop price)
 * buy    = 2 x value                (the price in the shop)
 * sell   = value                    for items bought from the shop (50%)
 *          1.6 x value              for items you found / mined / crafted yourself (80%)
 */
public final class Prices {
	private static final long[] TIER_PRICE = {5L, 25L, 300L, 3000L, 40000L, 500000L};
	private static final Map<Item, Long> SELL = new HashMap<>();
	private static final List<Entry> ALL = new ArrayList<>();
	private static final List<Category> CATEGORIES = new ArrayList<>();
	private static final Map<String, Long> ENCHANT_BASE = new HashMap<>();
	private static final long ENCHANT_DEFAULT = 10000L;
	private static final Set<String> PLAIN_POTIONS = Set.of("water", "mundane", "thick", "awkward", "empty");
	private static final Map<String, Tier> TIER_BY_ID = new HashMap<>();
	private static final Map<String, Long> OVERRIDES = new HashMap<>();
	private static final Set<String> UNSELLABLE = new HashSet<>();
	private static final Set<String> NOT_BUYABLE = new HashSet<>();

	/** Tag put on every item bought from the shop (those sell back for 50%). */
	public static final String BOUGHT_TAG = "mymarket_bought";
	/** Found / crafted items sell for 80% of the shop price = 1.6 x value. */
	private static final double FOUND_MULTIPLIER = 1.6;
	/** The GOD pickaxe is worth this many normal netherite pickaxes. */
	private static final long GOD_MULTIPLIER = 3L;

	private Prices() {}

	private static void tier(Tier t, String... ids) {
		for (String id : ids) TIER_BY_ID.put(id, t);
	}

	private static void price(String id, long value) {
		OVERRIDES.put(id, value);
	}

	// ---------------------------------------------------------------- loading

	public static void reload(MinecraftServer server) {
		SELL.clear();
		ALL.clear();
		CATEGORIES.clear();
		Map<String, Long> config = loadConfig();

		for (Item item : BuiltInRegistries.ITEM) {
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
			String path = id.getPath();
			long value = config.containsKey(id.toString()) ? config.get(id.toString()) : compute(item, path);
			if (value > 0L) SELL.put(item, value);
		}

		try {
			buildFromCreativeTabs(server);
		} catch (RuntimeException ex) {
			MyMarketMod.LOGGER.error("Could not read the creative tabs, using one big category instead", ex);
			CATEGORIES.clear();
			ALL.clear();
		}
		if (CATEGORIES.isEmpty()) buildFallback();

		insertGodPickaxe();
		MyMarketMod.LOGGER.info("My Market: {} priced items, {} shop entries, {} categories",
				SELL.size(), ALL.size(), CATEGORIES.size());
	}

	/** Shop categories = the same tabs you see in the creative inventory (same items, same order). */
	private static void buildFromCreativeTabs(MinecraftServer server) {
		CreativeModeTabs.tryRebuildTabContents(FeatureFlags.DEFAULT_FLAGS, false, server.registryAccess());
		Map<Item, List<ItemStack>> seen = new HashMap<>();

		for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
			if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
			List<Entry> list = new ArrayList<>();
			for (ItemStack st : tab.getDisplayItems()) {
				Entry e = makeEntry(st);
				if (e == null) continue;
				list.add(e);
				List<ItemStack> same = seen.computeIfAbsent(st.getItem(), k -> new ArrayList<>());
				boolean duplicate = false;
				for (ItemStack other : same) {
					if (ItemStack.isSameItemSameComponents(other, st)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate) {
					same.add(st);
					ALL.add(e);
				}
			}
			if (!list.isEmpty()) {
				CATEGORIES.add(new Category(tab.getDisplayName(), tab.getIconItem().copy(), list));
			}
		}
	}

	private static void buildFallback() {
		List<Entry> list = new ArrayList<>();
		for (Item item : SELL.keySet()) {
			Entry e = makeEntry(item.getDefaultInstance());
			if (e != null) list.add(e);
		}
		list.sort(Comparator.comparingLong(Entry::value).thenComparing(Entry::search));
		ALL.addAll(list);
		CATEGORIES.add(new Category(Component.literal("All items"), new ItemStack(Items.CHEST), list));
	}

	private static boolean isPotionItem(Item item) {
		return item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION
				|| item == Items.TIPPED_ARROW;
	}

	/** Turns a creative-tab stack into a shop entry, or null if it should not be sold in the shop. */
	private static Entry makeEntry(ItemStack st) {
		Item item = st.getItem();
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		if (UNSELLABLE.contains(path) || path.endsWith("_spawn_egg")) return null;

		if (item == Items.ENCHANTED_BOOK) {
			ItemEnchantments en = st.get(DataComponents.STORED_ENCHANTMENTS);
			if (en == null || en.isEmpty()) return null;
		} else if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
			PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
			if (pc == null || pc.potion().isEmpty() || PLAIN_POTIONS.contains(keyPath(pc.potion().get()))) return null;
		} else if (NOT_BUYABLE.contains(path) || isPotionItem(item)) {
			return null;
		}

		long value = baseValue(st);
		if (value <= 0L) return null;
		String search = (path.replace('_', ' ') + " " + st.getHoverName().getString()).toLowerCase(Locale.ROOT);
		return new Entry(st.copy(), value, search);
	}

	private static void insertGodPickaxe() {
		long value = godValue();
		if (value <= 0L) return;
		Entry god = new Entry(GodPickaxe.create(), value, "god pickaxe 3x3 hammer netherite pickaxe");

		boolean placed = false;
		for (Category c : CATEGORIES) {
			int i = indexOfNetheritePickaxe(c.entries());
			if (i >= 0) {
				c.entries().add(i + 1, god);
				placed = true;
				break;
			}
		}
		if (!placed && !CATEGORIES.isEmpty()) CATEGORIES.get(0).entries().add(god);

		int j = indexOfNetheritePickaxe(ALL);
		if (j >= 0) ALL.add(j + 1, god);
		else ALL.add(god);
	}

	private static int indexOfNetheritePickaxe(List<Entry> list) {
		for (int i = 0; i < list.size(); i++) {
			ItemStack s = list.get(i).stack();
			if (s.is(Items.NETHERITE_PICKAXE) && !GodPickaxe.isGod(s)) return i;
		}
		return -1;
	}

	private static long godValue() {
		return SELL.getOrDefault(Items.NETHERITE_PICKAXE, 0L) * GOD_MULTIPLIER;
	}

	private static String keyPath(Holder<?> h) {
		return h.unwrapKey().map(k -> k.location().getPath()).orElse("");
	}

	private static long enchantPrice(Holder<Enchantment> h, int level) {
		long base = ENCHANT_BASE.getOrDefault(keyPath(h), ENCHANT_DEFAULT);
		return Math.max(1L, Math.round(base * Math.pow(2.5, level - 1)));
	}

	private static long potionPrice(Item kind, String potionPath) {
		double v = 800.0;
		if (potionPath.startsWith("long_") || potionPath.startsWith("strong_")) v *= 1.8;
		if (kind == Items.SPLASH_POTION) v *= 1.3;
		else if (kind == Items.LINGERING_POTION) v *= 2.0;
		else if (kind == Items.TIPPED_ARROW) v *= 0.2;
		return Math.max(1L, Math.round(v));
	}

	private static Map<String, Long> loadConfig() {
		Map<String, Long> result = new HashMap<>();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mymarket_prices_v2.json");
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, "{\n}\n");
			}
			try (Reader reader = Files.newBufferedReader(file)) {
				JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
					String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
					result.put(key, e.getValue().getAsLong());
				}
			}
		} catch (RuntimeException | IOException ex) {
			MyMarketMod.LOGGER.error("Could not read mymarket_prices_v2.json, using built-in prices", ex);
		}
		return result;
	}

	private static long compute(Item item, String path) {
		if (item == Items.AIR || UNSELLABLE.contains(path) || path.endsWith("_spawn_egg")) return 0L;
		Long override = OVERRIDES.get(path);
		if (override != null) return override;

		Tier t = TIER_BY_ID.get(path);
		if (t == null) t = byKeyword(path);
		if (t == null) {
			Rarity rarity = item.getDefaultInstance().get(DataComponents.RARITY);
			if (rarity == Rarity.UNCOMMON) t = Tier.UNCOMMON;
			else if (rarity == Rarity.RARE) t = Tier.RARE;
			else if (rarity == Rarity.EPIC) t = Tier.EPIC;
		}
		if (t == null) t = Tier.COMMON;
		return TIER_PRICE[t.ordinal()];
	}

	private static Tier byKeyword(String p) {
		if (p.contains("smithing_template")) return Tier.RARE;
		if (p.contains("netherite")) return Tier.EPIC;
		if (p.contains("music_disc")) return Tier.RARE;
		if (p.endsWith("shulker_box")) return Tier.EPIC;
		if (p.endsWith("_head") || p.endsWith("_skull")) return Tier.RARE;
		if (p.contains("diamond")) return Tier.RARE;
		if (p.contains("emerald")) return Tier.RARE;
		if (p.contains("pottery_sherd")) return Tier.UNCOMMON;
		if (p.contains("golden") || p.contains("gold")) return Tier.UNCOMMON;
		if (p.contains("iron")) return Tier.UNCOMMON;
		if (p.endsWith("_ore")) return Tier.UNCOMMON;
		if (p.endsWith("_leaves")) return Tier.BASIC;
		return null;
	}

	// ---------------------------------------------------------------- public API

	public static Tier tierOfPrice(long p) {
		if (p < 15L) return Tier.BASIC;
		if (p < 150L) return Tier.COMMON;
		if (p < 1500L) return Tier.UNCOMMON;
		if (p < 15000L) return Tier.RARE;
		return p < 200000L ? Tier.EPIC : Tier.LEGENDARY;
	}

	public static boolean matches(Entry e, String[] tokens) {
		for (String t : tokens) {
			if (!e.search().contains(t)) return false;
		}
		return true;
	}

	public static List<Entry> all() {
		return ALL;
	}

	public static List<Category> categories() {
		return CATEGORIES;
	}

	/** Price in the shop for an item whose base value is {@code value}. */
	public static long buyPrice(long value) {
		return Math.max(value * 2L, value + 1L);
	}

	/** What a stack sells for, per item, when it was found/crafted yourself (80% of the shop price). */
	public static long foundValue(long value) {
		return Math.max(1L, Math.round(value * FOUND_MULTIPLIER));
	}

	/** Base value of one item (enchanted books and potions are priced by what they contain). */
	public static long baseValue(ItemStack stack) {
		if (GodPickaxe.isGod(stack)) return godValue();
		Item item = stack.getItem();
		if (item == Items.ENCHANTED_BOOK) {
			ItemEnchantments en = stack.get(DataComponents.STORED_ENCHANTMENTS);
			if (en != null && !en.isEmpty()) {
				long sum = 0L;
				for (Object2IntMap.Entry<Holder<Enchantment>> e : en.entrySet()) {
					sum += enchantPrice(e.getKey(), e.getIntValue());
				}
				return sum;
			}
		} else if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION
				|| item == Items.TIPPED_ARROW) {
			PotionContents pc = stack.get(DataComponents.POTION_CONTENTS);
			if (pc != null && pc.potion().isPresent()) {
				String path = keyPath(pc.potion().get());
				return PLAIN_POTIONS.contains(path) ? 20L : potionPrice(item, path);
			}
		}
		return SELL.getOrDefault(item, 0L);
	}

	public static boolean isBought(ItemStack stack) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().getBoolean(BOUGHT_TAG);
	}

	/** Marks a stack as "bought from the shop" so it sells back for 50%. */
	public static void markBought(ItemStack stack) {
		CustomData d = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag tag = d != null ? d.copyTag() : new CompoundTag();
		tag.putBoolean(BOUGHT_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
	}

	/** Money for ONE item of this stack (0 = cannot be sold). Damaged items are worth less. */
	public static long unitSell(ItemStack stack) {
		long base = baseValue(stack);
		if (base <= 0L) return 0L;
		if (!isBought(stack)) base = foundValue(base);
		if (stack.isDamaged() && stack.getMaxDamage() > 0) {
			double left = (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
			base = Math.max(1L, Math.round(base * left));
		}
		return base;
	}

	/** True for shulker boxes / bundles that still hold items (selling them would destroy the contents). */
	public static boolean hasHiddenContents(ItemStack stack) {
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		if (c != null && !c.equals(ItemContainerContents.EMPTY)) return true;
		BundleContents b = stack.get(DataComponents.BUNDLE_CONTENTS);
		return b != null && !b.isEmpty();
	}

	// ---------------------------------------------------------------- static tables (from V4)

   static {
      UNSELLABLE.addAll(
         List.of(
            "air",
            "barrier",
            "bedrock",
            "command_block",
            "chain_command_block",
            "repeating_command_block",
            "command_block_minecart",
            "structure_block",
            "structure_void",
            "jigsaw",
            "light",
            "debug_stick",
            "knowledge_book",
            "spawner",
            "trial_spawner",
            "vault",
            "reinforced_deepslate",
            "end_portal_frame",
            "budding_amethyst",
            "petrified_oak_slab",
            "farmland",
            "dirt_path"
         )
      );
      NOT_BUYABLE.addAll(
         List.of(
            "enchanted_book",
            "potion",
            "splash_potion",
            "lingering_potion",
            "tipped_arrow",
            "written_book",
            "filled_map",
            "firework_star",
            "suspicious_stew",
            "ominous_bottle",
            "player_head"
         )
      );
      tier(
         Prices.Tier.BASIC,
         "dirt",
         "coarse_dirt",
         "rooted_dirt",
         "cobblestone",
         "cobbled_deepslate",
         "stone",
         "andesite",
         "diorite",
         "granite",
         "tuff",
         "deepslate",
         "netherrack",
         "gravel",
         "sand",
         "red_sand",
         "stick",
         "wheat_seeds",
         "grass_block",
         "podzol",
         "mycelium"
      );
      tier(
         Prices.Tier.UNCOMMON,
         "ender_pearl",
         "obsidian",
         "slime_ball",
         "blaze_powder",
         "magma_cream",
         "glowstone_dust",
         "glowstone",
         "prismarine_shard",
         "phantom_membrane",
         "slime_block",
         "honey_block",
         "piston",
         "sticky_piston",
         "observer",
         "hopper",
         "dispenser",
         "dropper",
         "daylight_detector",
         "compass",
         "clock",
         "spyglass",
         "tnt",
         "brewing_stand",
         "nether_wart"
      );
      tier(
         Prices.Tier.RARE,
         "blaze_rod",
         "ghast_tear",
         "nautilus_shell",
         "golden_apple",
         "name_tag",
         "saddle",
         "sea_lantern",
         "crying_obsidian",
         "wither_rose",
         "enchanted_book",
         "experience_bottle",
         "echo_shard",
         "lodestone",
         "end_crystal",
         "ender_chest",
         "enchanting_table",
         "breeze_rod",
         "ender_eye",
         "scute",
         "sculk_catalyst",
         "sculk_shrieker",
         "sniffer_egg",
         "respawn_anchor",
         "disc_fragment_5"
      );
      tier(
         Prices.Tier.EPIC,
         "totem_of_undying",
         "heart_of_the_sea",
         "trident",
         "shulker_shell",
         "dragon_head",
         "dragon_breath",
         "wither_skeleton_skull",
         "conduit",
         "mace",
         "recovery_compass"
      );
      tier(Prices.Tier.LEGENDARY, "nether_star", "beacon", "elytra", "dragon_egg", "enchanted_golden_apple", "heavy_core");
      price("coal", 60L);
      price("coal_block", 540L);
      price("charcoal", 55L);
      price("copper_ingot", 40L);
      price("raw_copper", 30L);
      price("copper_block", 360L);
      price("iron_ingot", 150L);
      price("raw_iron", 120L);
      price("iron_nugget", 17L);
      price("iron_block", 1350L);
      price("gold_ingot", 300L);
      price("raw_gold", 240L);
      price("gold_nugget", 33L);
      price("gold_block", 2700L);
      price("redstone", 334L);
      price("redstone_block", 1050L);
      price("lapis_lazuli", 80L);
      price("lapis_block", 720L);
      price("quartz", 100L);
      price("amethyst_shard", 120L);
      price("diamond", 12000L);
      price("diamond_block", 108000L);
      price("emerald", 6000L);
      price("emerald_block", 54000L);
      price("ancient_debris", 700000L);
      price("netherite_scrap", 550000L);
      price("netherite_ingot", 2350000L);
      price("netherite_block", 20900000L);
      price("diamond_ore", 12000L);
      price("deepslate_diamond_ore", 12000L);
      price("netherite_upgrade_smithing_template", 250000L);
      price("repeater", 880L);
      price("comparator", 1650L);
      price("hopper", 1480L);
      price("observer", 1520L);
      price("slime_block", 237L);
      price("piston", 1950L);
      price("sticky_piston", 3075L);
      price("totem_of_undying", 31000L);
      price("golden_apple", 15800L);
      price("end_crystal", 13500L);
      price("firework_rocket", 280L);
      price("enchanted_golden_apple", 800000L);
      price("elytra", 184000000L);
      price("dragon_head", 28000000L);
      price("piglin_head", 390000L);
      price("dragon_egg", 50000000L);
      price("nether_star", 2500000L);
      price("beacon", 3000000L);
      price("heart_of_the_sea", 400000L);
      price("shulker_shell", 100000L);
      price("trident", 800000L);
      price("mace", 1500000L);
      price("heavy_core", 600000L);
      price("conduit", 900000L);
      price("ender_pearl", 400L);
      price("blaze_rod", 250L);
      price("obsidian", 120L);
      price("ghast_tear", 900L);
      price("netherite_sword", 3140000L);
      price("netherite_helmet", 2700000L);
      price("netherite_chestplate", 2450000L);
      price("netherite_leggings", 2640000L);
      price("netherite_boots", 2510000L);
      price("netherite_pickaxe", 2500000L);
      price("netherite_axe", 2500000L);
      price("netherite_shovel", 2400000L);
      price("netherite_hoe", 2400000L);
      String[] pieces = new String[]{"sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots"};
      int[] units = new int[]{2, 3, 3, 1, 2, 5, 8, 7, 4};
      String[] mats = new String[]{"iron", "golden", "diamond"};
      long[] matPrice = new long[]{150L, 300L, 12000L};

      for (int m = 0; m < mats.length; m++) {
         for (int i = 0; i < pieces.length; i++) {
            price(mats[m] + "_" + pieces[i], units[i] * matPrice[m]);
         }
      }

      Object[] eb = new Object[]{
         "mending",
         150000,
         "silk_touch",
         60000,
         "infinity",
         60000,
         "swift_sneak",
         50000,
         "frost_walker",
         40000,
         "soul_speed",
         40000,
         "wind_burst",
         30000,
         "channeling",
         30000,
         "multishot",
         25000,
         "riptide",
         20000,
         "aqua_affinity",
         15000,
         "flame",
         15000,
         "fortune",
         12000,
         "looting",
         12000,
         "unbreaking",
         8000,
         "fire_aspect",
         8000,
         "luck_of_the_sea",
         8000,
         "lure",
         8000,
         "breach",
         8000,
         "efficiency",
         6000,
         "sharpness",
         6000,
         "protection",
         6000,
         "sweeping_edge",
         6000,
         "power",
         6000,
         "depth_strider",
         6000,
         "loyalty",
         6000,
         "quick_charge",
         6000,
         "density",
         6000,
         "thorns",
         5000,
         "impaling",
         5000,
         "feather_falling",
         4000,
         "respiration",
         4000,
         "punch",
         4000,
         "piercing",
         4000,
         "smite",
         3000,
         "bane_of_arthropods",
         3000,
         "knockback",
         3000,
         "fire_protection",
         3000,
         "blast_protection",
         3000,
         "projectile_protection",
         3000,
         "binding_curse",
         1000,
         "vanishing_curse",
         1000
      };

      for (int i = 0; i < eb.length; i += 2) {
         ENCHANT_BASE.put((String)eb[i], ((Integer)eb[i + 1]).longValue());
      }
   }

	// ---------------------------------------------------------------- types

	public record Entry(ItemStack stack, long value, String search) {
		public Item item() {
			return this.stack.getItem();
		}
	}

	/** One tab of the shop (same as a creative inventory tab). */
	public record Category(Component name, ItemStack icon, List<Entry> entries) {}

	public enum Tier {
		BASIC("Basic", ChatFormatting.GRAY),
		COMMON("Common", ChatFormatting.WHITE),
		UNCOMMON("Uncommon", ChatFormatting.GREEN),
		RARE("Rare", ChatFormatting.AQUA),
		EPIC("Epic", ChatFormatting.LIGHT_PURPLE),
		LEGENDARY("Legendary", ChatFormatting.GOLD);

		public final String label;
		public final ChatFormatting color;

		Tier(String label, ChatFormatting color) {
			this.label = label;
			this.color = color;
		}
	}
}

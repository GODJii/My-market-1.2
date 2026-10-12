package com.mymarket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Decides what every item is worth.
 * Order of decisions: config file -> exact overrides -> rarity-tier lists -> keywords -> vanilla rarity -> default.
 */
public final class Prices {
	private Prices() {}

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

	/** Base sell price of one item in each tier (same order as Tier). */
	private static final long[] TIER_PRICE = {5, 25, 300, 3_000, 40_000, 500_000};

	private static final Map<Item, Long> SELL = new HashMap<>();
	/** One line in the shop. Plain items, but also every enchanted book and potion variant. */
	public record Entry(ItemStack stack, long sell, String search) {
		public Item item() {
			return stack.getItem();
		}
	}

	private static final List<Entry> ENTRIES = new ArrayList<>();

	/** Sell price of a level-I book of each enchantment; higher levels multiply by 2.5 per level. */
	private static final Map<String, Long> ENCHANT_BASE = new HashMap<>();
	private static final long ENCHANT_DEFAULT = 10_000;
	private static final Set<String> PLAIN_POTIONS = Set.of("water", "mundane", "thick", "awkward", "empty");

	private static final Map<String, Tier> TIER_BY_ID = new HashMap<>();
	private static final Map<String, Long> OVERRIDES = new HashMap<>();
	private static final Set<String> UNSELLABLE = new HashSet<>();
	private static final Set<String> NOT_BUYABLE = new HashSet<>();

	private static void tier(Tier t, String... ids) {
		for (String id : ids) TIER_BY_ID.put(id, t);
	}

	private static void price(String id, long value) {
		OVERRIDES.put(id, value);
	}

	static {
		// Items that cannot exist in survival or would break the game: not sold, not bought
		UNSELLABLE.addAll(List.of("air", "barrier", "bedrock", "command_block", "chain_command_block",
				"repeating_command_block", "command_block_minecart", "structure_block", "structure_void", "jigsaw",
				"light", "debug_stick", "knowledge_book", "spawner", "trial_spawner", "vault", "reinforced_deepslate",
				"end_portal_frame", "budding_amethyst", "petrified_oak_slab", "farmland", "dirt_path"));
		// Items whose default version is empty/useless to buy (you may still sell them)
		NOT_BUYABLE.addAll(List.of("enchanted_book", "potion", "splash_potion", "lingering_potion", "tipped_arrow",
				"written_book", "filled_map", "firework_star", "suspicious_stew", "ominous_bottle", "player_head"));

		tier(Tier.BASIC, "dirt", "coarse_dirt", "rooted_dirt", "cobblestone", "cobbled_deepslate", "stone", "andesite",
				"diorite", "granite", "tuff", "deepslate", "netherrack", "gravel", "sand", "red_sand", "stick",
				"wheat_seeds", "grass_block", "podzol", "mycelium");
		tier(Tier.UNCOMMON, "ender_pearl", "obsidian", "slime_ball", "blaze_powder", "magma_cream", "glowstone_dust",
				"glowstone", "prismarine_shard", "phantom_membrane", "slime_block", "honey_block", "piston",
				"sticky_piston", "observer", "hopper", "dispenser", "dropper", "daylight_detector", "compass", "clock",
				"spyglass", "tnt", "brewing_stand", "nether_wart");
		tier(Tier.RARE, "blaze_rod", "ghast_tear", "nautilus_shell", "golden_apple", "name_tag", "saddle", "sea_lantern",
				"crying_obsidian", "wither_rose", "enchanted_book", "experience_bottle", "echo_shard", "lodestone",
				"end_crystal", "ender_chest", "enchanting_table", "breeze_rod", "ender_eye", "scute", "sculk_catalyst",
				"sculk_shrieker", "sniffer_egg", "respawn_anchor", "disc_fragment_5");
		tier(Tier.EPIC, "totem_of_undying", "heart_of_the_sea", "trident", "shulker_shell", "dragon_head",
				"dragon_breath", "wither_skeleton_skull", "conduit", "mace", "recovery_compass");
		tier(Tier.LEGENDARY, "nether_star", "beacon", "elytra", "dragon_egg", "enchanted_golden_apple", "heavy_core");

		// Prices are SELL prices. Buying costs 2x. Scale follows donut.auction (netherite = millions,
		// elytra = hundreds of millions): donut price / 2 = sell price here.

		// Ores & materials
		price("coal", 60); price("coal_block", 540); price("charcoal", 55);
		price("copper_ingot", 40); price("raw_copper", 30); price("copper_block", 360);
		price("iron_ingot", 150); price("raw_iron", 120); price("iron_nugget", 17); price("iron_block", 1_350);
		price("gold_ingot", 300); price("raw_gold", 240); price("gold_nugget", 33); price("gold_block", 2_700);
		price("redstone", 334); price("redstone_block", 1_050);
		price("lapis_lazuli", 80); price("lapis_block", 720);
		price("quartz", 100); price("amethyst_shard", 120);
		price("diamond", 12_000); price("diamond_block", 108_000);
		price("emerald", 6_000); price("emerald_block", 54_000);
		price("ancient_debris", 700_000); price("netherite_scrap", 550_000);
		price("netherite_ingot", 2_350_000); price("netherite_block", 20_900_000);
		price("diamond_ore", 12_000); price("deepslate_diamond_ore", 12_000);
		price("netherite_upgrade_smithing_template", 250_000);

		// Redstone parts (from donut.auction)
		price("repeater", 880); price("comparator", 1_650); price("hopper", 1_480); price("observer", 1_520);
		price("slime_block", 237); price("piston", 1_950); price("sticky_piston", 3_075);

		// Valuables
		price("totem_of_undying", 31_000); price("golden_apple", 15_800); price("end_crystal", 13_500);
		price("firework_rocket", 280); price("enchanted_golden_apple", 800_000);
		price("elytra", 184_000_000); price("dragon_head", 28_000_000); price("piglin_head", 390_000);
		price("dragon_egg", 50_000_000); price("nether_star", 2_500_000); price("beacon", 3_000_000);
		price("heart_of_the_sea", 400_000); price("shulker_shell", 100_000); price("trident", 800_000);
		price("mace", 1_500_000); price("heavy_core", 600_000); price("conduit", 900_000);
		price("ender_pearl", 400); price("blaze_rod", 250); price("obsidian", 120); price("ghast_tear", 900);

		// Netherite gear
		price("netherite_sword", 3_140_000); price("netherite_helmet", 2_700_000);
		price("netherite_chestplate", 2_450_000); price("netherite_leggings", 2_640_000);
		price("netherite_boots", 2_510_000); price("netherite_pickaxe", 2_500_000);
		price("netherite_axe", 2_500_000); price("netherite_shovel", 2_400_000); price("netherite_hoe", 2_400_000);

		// Iron / gold / diamond gear = number of material units in the recipe x material price
		String[] pieces = {"sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots"};
		int[] units = {2, 3, 3, 1, 2, 5, 8, 7, 4};
		String[] mats = {"iron", "golden", "diamond"};
		long[] matPrice = {150, 300, 12_000};
		for (int m = 0; m < mats.length; m++) {
			for (int i = 0; i < pieces.length; i++) {
				price(mats[m] + "_" + pieces[i], units[i] * matPrice[m]);
			}
		}

		// Enchanted books (sell price of level I)
		Object[] eb = {
				"mending", 150_000, "silk_touch", 60_000, "infinity", 60_000, "swift_sneak", 50_000,
				"frost_walker", 40_000, "soul_speed", 40_000, "wind_burst", 30_000, "channeling", 30_000,
				"multishot", 25_000, "riptide", 20_000, "aqua_affinity", 15_000, "flame", 15_000,
				"fortune", 12_000, "looting", 12_000, "unbreaking", 8_000, "fire_aspect", 8_000,
				"luck_of_the_sea", 8_000, "lure", 8_000, "breach", 8_000, "efficiency", 6_000,
				"sharpness", 6_000, "protection", 6_000, "sweeping_edge", 6_000, "power", 6_000,
				"depth_strider", 6_000, "loyalty", 6_000, "quick_charge", 6_000, "density", 6_000,
				"thorns", 5_000, "impaling", 5_000, "feather_falling", 4_000, "respiration", 4_000,
				"punch", 4_000, "piercing", 4_000, "smite", 3_000, "bane_of_arthropods", 3_000,
				"knockback", 3_000, "fire_protection", 3_000, "blast_protection", 3_000,
				"projectile_protection", 3_000, "binding_curse", 1_000, "vanishing_curse", 1_000};
		for (int i = 0; i < eb.length; i += 2) ENCHANT_BASE.put((String) eb[i], ((Integer) eb[i + 1]).longValue());
	}

	// ---------------------------------------------------------------- loading

	public static void reload(MinecraftServer server) {
		SELL.clear();
		ENTRIES.clear();
		Map<String, Long> config = loadConfig();

		for (Item item : BuiltInRegistries.ITEM) {
			ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
			String path = id.getPath();
			long value = config.containsKey(id.toString()) ? config.get(id.toString()) : compute(item, path);
			if (value <= 0) continue;
			SELL.put(item, value);
			if (!NOT_BUYABLE.contains(path) && !UNSELLABLE.contains(path) && !path.endsWith("_spawn_egg")) {
				String search = (path.replace('_', ' ') + " " + item.getDescription().getString()).toLowerCase(Locale.ROOT);
				ENTRIES.add(new Entry(item.getDefaultInstance(), value, search));
			}
		}

		// Every enchantment at every level, as its own book
		try {
			Registry<Enchantment> reg = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
			for (Holder.Reference<Enchantment> h : reg.holders().toList()) {
				Enchantment e = h.value();
				String path = keyPath(h);
				String name = e.description().getString();
				for (int lvl = 1; lvl <= e.getMaxLevel(); lvl++) {
					ItemStack book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(h, lvl));
					String search = ("enchanted book enchantment " + path.replace('_', ' ') + " " + name + " level " + lvl)
							.toLowerCase(Locale.ROOT);
					ENTRIES.add(new Entry(book, enchantPrice(h, lvl), search));
				}
			}
		} catch (RuntimeException ex) {
			MyMarketMod.LOGGER.error("Could not add enchanted books to the shop", ex);
		}

		// Every potion type as potion / splash / lingering / tipped arrow
		try {
			Item[] kinds = {Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW};
			for (Holder.Reference<Potion> h : BuiltInRegistries.POTION.holders().toList()) {
				String path = keyPath(h);
				if (PLAIN_POTIONS.contains(path)) continue;
				for (Item kind : kinds) {
					ItemStack st = PotionContents.createItemStack(kind, h);
					String search = (BuiltInRegistries.ITEM.getKey(kind).getPath().replace('_', ' ') + " "
							+ path.replace('_', ' ')).toLowerCase(Locale.ROOT);
					ENTRIES.add(new Entry(st, potionPrice(kind, path), search));
				}
			}
		} catch (RuntimeException ex) {
			MyMarketMod.LOGGER.error("Could not add potions to the shop", ex);
		}

		ENTRIES.sort(Comparator.comparingLong(Entry::sell).thenComparing(Entry::search));
		MyMarketMod.LOGGER.info("My Market: {} sellable items, {} shop entries", SELL.size(), ENTRIES.size());
	}

	private static String keyPath(Holder<?> h) {
		return h.unwrapKey().map(k -> k.location().getPath()).orElse("");
	}

	private static long enchantPrice(Holder<Enchantment> h, int level) {
		long base = ENCHANT_BASE.getOrDefault(keyPath(h), ENCHANT_DEFAULT);
		return Math.max(1, Math.round(base * Math.pow(2.5, level - 1)));
	}

	private static long potionPrice(Item kind, String potionPath) {
		double v = 800;
		if (potionPath.startsWith("long_") || potionPath.startsWith("strong_")) v *= 1.8;
		if (kind == Items.SPLASH_POTION) v *= 1.3;
		else if (kind == Items.LINGERING_POTION) v *= 2;
		else if (kind == Items.TIPPED_ARROW) v *= 0.2;
		return Math.max(1, Math.round(v));
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
				for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
					String key = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
					result.put(key, e.getValue().getAsLong());
				}
			}
		} catch (IOException | RuntimeException ex) {
			MyMarketMod.LOGGER.error("Could not read mymarket_prices_v2.json, using built-in prices", ex);
		}
		return result;
	}

	// ---------------------------------------------------------------- price rules

	private static long compute(Item item, String path) {
		if (item == Items.AIR || UNSELLABLE.contains(path) || path.endsWith("_spawn_egg")) return 0;

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
		if (p < 15) return Tier.BASIC;
		if (p < 150) return Tier.COMMON;
		if (p < 1_500) return Tier.UNCOMMON;
		if (p < 15_000) return Tier.RARE;
		if (p < 200_000) return Tier.EPIC;
		return Tier.LEGENDARY;
	}

	public static Tier tierOf(Item item) {
		return tierOfPrice(SELL.getOrDefault(item, 0L));
	}

	/** True if every word in {@code tokens} (lower-case) appears in the entry's name / id / enchantment. */
	public static boolean matches(Entry e, String[] tokens) {
		for (String t : tokens) {
			if (!e.search().contains(t)) return false;
		}
		return true;
	}

	public static List<Entry> buyable() {
		return ENTRIES;
	}

	/** Price the player pays when the sell price is {@code sell} (always higher than the sell price). */
	public static long buyPrice(long sell) {
		return Math.max(sell * 2, sell + 1);
	}

	/** Value of one undamaged item of this stack. Enchanted books and potions are priced by their contents. */
	public static long baseValue(ItemStack stack) {
		Item item = stack.getItem();
		if (item == Items.ENCHANTED_BOOK) {
			ItemEnchantments en = stack.get(DataComponents.STORED_ENCHANTMENTS);
			if (en != null && !en.isEmpty()) {
				long sum = 0;
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
				return PLAIN_POTIONS.contains(path) ? 20 : potionPrice(item, path);
			}
		}
		return SELL.getOrDefault(item, 0L);
	}

	/** Money the player gets for ONE item of this stack (0 = cannot be sold). Damaged items are worth less. */
	public static long unitSell(ItemStack stack) {
		long base = baseValue(stack);
		if (base <= 0) return 0;
		if (stack.isDamaged() && stack.getMaxDamage() > 0) {
			double left = (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
			base = Math.max(1, Math.round(base * left));
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
}

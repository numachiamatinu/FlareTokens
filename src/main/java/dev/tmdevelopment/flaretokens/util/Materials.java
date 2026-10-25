package dev.tmdevelopment.flaretokens.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Material names that work on every version: write the modern name (PURPLE_STAINED_GLASS_PANE, PLAYER_HEAD...)
 * and on 1.8-1.12 it becomes the old one (STAINED_GLASS_PANE:10, SKULL_ITEM:3...). Old names written in
 * configs ("STAINED_GLASS_PANE:10") also work on new versions.
 */
public final class Materials {

    /** modern name -> "OLD_NAME:data" */
    private static final Map<String, String> TO_OLD = new HashMap<>();
    /** "OLD_NAME:data" (and "OLD_NAME") -> modern name */
    private static final Map<String, String> TO_NEW = new HashMap<>();
    private static final Set<String> warned = new HashSet<>();
    private static Logger logger;

    private static final String[] COLORS = {"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"};

    static {
        for (int d = 0; d < 16; d++) {
            String c = COLORS[d];
            map(c + "_WOOL", "WOOL:" + d);
            map(c + "_STAINED_GLASS", "STAINED_GLASS:" + d);
            map(c + "_STAINED_GLASS_PANE", "STAINED_GLASS_PANE:" + d);
            map(c + "_TERRACOTTA", "STAINED_CLAY:" + d);
            map(c + "_CARPET", "CARPET:" + d);
            map(c + "_CONCRETE", "CONCRETE:" + d);
            map(c + "_CONCRETE_POWDER", "CONCRETE_POWDER:" + d);
            map(c + "_BANNER", "BANNER:" + (15 - d));
            map(c + "_BED", "BED:" + d);
        }
        String[] dyes = {"INK_SAC", "RED_DYE", "GREEN_DYE", "COCOA_BEANS", "LAPIS_LAZULI", "PURPLE_DYE", "CYAN_DYE",
                "LIGHT_GRAY_DYE", "GRAY_DYE", "PINK_DYE", "LIME_DYE", "YELLOW_DYE", "LIGHT_BLUE_DYE", "MAGENTA_DYE",
                "ORANGE_DYE", "BONE_MEAL"};
        for (int d = 0; d < dyes.length; d++) map(dyes[d], "INK_SACK:" + d);
        map("BLACK_DYE", "INK_SACK:0");
        map("BLUE_DYE", "INK_SACK:4");
        map("BROWN_DYE", "INK_SACK:3");
        map("WHITE_DYE", "INK_SACK:15");
        map("TERRACOTTA", "HARD_CLAY:0");
        map("GLASS_PANE", "THIN_GLASS:0");

        map("SKELETON_SKULL", "SKULL_ITEM:0");
        map("WITHER_SKELETON_SKULL", "SKULL_ITEM:1");
        map("ZOMBIE_HEAD", "SKULL_ITEM:2");
        map("PLAYER_HEAD", "SKULL_ITEM:3");
        map("CREEPER_HEAD", "SKULL_ITEM:4");
        map("DRAGON_HEAD", "SKULL_ITEM:5");

        String[] tools = {"SWORD", "PICKAXE", "AXE", "HOE"};
        for (String t : tools) {
            map("GOLDEN_" + t, "GOLD_" + t + ":0");
            map("WOODEN_" + t, "WOOD_" + t + ":0");
        }
        map("GOLDEN_SHOVEL", "GOLD_SPADE:0");
        map("WOODEN_SHOVEL", "WOOD_SPADE:0");
        map("STONE_SHOVEL", "STONE_SPADE:0");
        map("IRON_SHOVEL", "IRON_SPADE:0");
        map("DIAMOND_SHOVEL", "DIAMOND_SPADE:0");
        for (String a : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) map("GOLDEN_" + a, "GOLD_" + a + ":0");
        map("GOLDEN_HORSE_ARMOR", "GOLD_BARDING:0");
        map("IRON_HORSE_ARMOR", "IRON_BARDING:0");
        map("DIAMOND_HORSE_ARMOR", "DIAMOND_BARDING:0");

        String[][] simple = {
                {"EXPERIENCE_BOTTLE", "EXP_BOTTLE:0"}, {"ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE:1"},
                {"CLOCK", "WATCH:0"}, {"KNOWLEDGE_BOOK", "BOOK:0"}, {"REDSTONE_TORCH", "REDSTONE_TORCH_ON:0"}, {"SUNFLOWER", "DOUBLE_PLANT:0"}, {"LILAC", "DOUBLE_PLANT:1"},
                {"ROSE_BUSH", "DOUBLE_PLANT:4"}, {"PEONY", "DOUBLE_PLANT:5"},
                {"OAK_PLANKS", "WOOD:0"}, {"SPRUCE_PLANKS", "WOOD:1"}, {"BIRCH_PLANKS", "WOOD:2"},
                {"JUNGLE_PLANKS", "WOOD:3"}, {"ACACIA_PLANKS", "WOOD:4"}, {"DARK_OAK_PLANKS", "WOOD:5"},
                {"OAK_LOG", "LOG:0"}, {"SPRUCE_LOG", "LOG:1"}, {"BIRCH_LOG", "LOG:2"}, {"JUNGLE_LOG", "LOG:3"},
                {"ACACIA_LOG", "LOG_2:0"}, {"DARK_OAK_LOG", "LOG_2:1"},
                {"OAK_SAPLING", "SAPLING:0"}, {"OAK_LEAVES", "LEAVES:0"},
                {"CRAFTING_TABLE", "WORKBENCH:0"}, {"ENDER_EYE", "EYE_OF_ENDER:0"}, {"FIREWORK_ROCKET", "FIREWORK:0"},
                {"FIREWORK_STAR", "FIREWORK_CHARGE:0"}, {"FIRE_CHARGE", "FIREBALL:0"}, {"GUNPOWDER", "SULPHUR:0"},
                {"MAP", "EMPTY_MAP:0"}, {"FILLED_MAP", "MAP:0"}, {"COMMAND_BLOCK", "COMMAND:0"},
                {"OAK_SIGN", "SIGN:0"}, {"OAK_DOOR", "WOOD_DOOR:0"}, {"IRON_BARS", "IRON_FENCE:0"},
                {"LILY_PAD", "WATER_LILY:0"}, {"MELON_SLICE", "MELON:0"}, {"MELON", "MELON_BLOCK:0"},
                {"GLISTERING_MELON_SLICE", "SPECKLED_MELON:0"}, {"WRITABLE_BOOK", "BOOK_AND_QUILL:0"},
                {"ENCHANTING_TABLE", "ENCHANTMENT_TABLE:0"}, {"END_STONE", "ENDER_STONE:0"},
                {"NETHER_BRICKS", "NETHER_BRICK:0"}, {"NETHER_BRICK", "NETHER_BRICK_ITEM:0"},
                {"NETHER_WART", "NETHER_STALK:0"}, {"COBWEB", "WEB:0"}, {"GRASS_BLOCK", "GRASS:0"},
                {"SHORT_GRASS", "LONG_GRASS:1"}, {"FERN", "LONG_GRASS:2"}, {"DEAD_BUSH", "DEAD_BUSH:0"},
                {"SPAWNER", "MOB_SPAWNER:0"}, {"TOTEM_OF_UNDYING", "TOTEM:0"}, {"SNOWBALL", "SNOW_BALL:0"},
                {"POPPY", "RED_ROSE:0"}, {"BLUE_ORCHID", "RED_ROSE:1"}, {"ALLIUM", "RED_ROSE:2"},
                {"DANDELION", "YELLOW_FLOWER:0"}, {"PORKCHOP", "PORK:0"}, {"COOKED_PORKCHOP", "GRILLED_PORK:0"},
                {"BEEF", "RAW_BEEF:0"}, {"CHICKEN", "RAW_CHICKEN:0"}, {"COD", "RAW_FISH:0"}, {"SALMON", "RAW_FISH:1"},
                {"COOKED_COD", "COOKED_FISH:0"}, {"MUSHROOM_STEW", "MUSHROOM_SOUP:0"},
                {"CHEST_MINECART", "STORAGE_MINECART:0"}, {"FURNACE_MINECART", "POWERED_MINECART:0"},
                {"TNT_MINECART", "EXPLOSIVE_MINECART:0"}, {"HOPPER_MINECART", "HOPPER_MINECART:0"},
                {"OAK_BOAT", "BOAT:0"}, {"CARROT", "CARROT_ITEM:0"}, {"POTATO", "POTATO_ITEM:0"},
                {"GOLDEN_CARROT", "GOLDEN_CARROT:0"}, {"WHEAT_SEEDS", "SEEDS:0"}, {"BRICKS", "BRICK:0"},
                {"BRICK", "CLAY_BRICK:0"}, {"STONE_BRICKS", "SMOOTH_BRICK:0"}, {"OAK_FENCE", "FENCE:0"},
                {"OAK_FENCE_GATE", "FENCE_GATE:0"}, {"OAK_SLAB", "WOOD_STEP:0"}, {"STONE_SLAB", "STEP:0"},
                {"SMOOTH_STONE", "STEP:0"}, {"PISTON", "PISTON_BASE:0"}, {"STICKY_PISTON", "PISTON_STICKY_BASE:0"},
                {"REPEATER", "DIODE:0"}, {"COMPARATOR", "REDSTONE_COMPARATOR:0"}, {"CARVED_PUMPKIN", "PUMPKIN:0"},
                {"MYCELIUM", "MYCEL:0"}, {"WET_SPONGE", "SPONGE:1"}, {"NETHER_QUARTZ_ORE", "QUARTZ_ORE:0"},
                {"LEAD", "LEASH:0"}, {"OAK_PRESSURE_PLATE", "WOOD_PLATE:0"}, {"STONE_PRESSURE_PLATE", "STONE_PLATE:0"},
                {"OAK_BUTTON", "WOOD_BUTTON:0"}, {"OAK_TRAPDOOR", "TRAP_DOOR:0"}, {"OAK_STAIRS", "WOOD_STAIRS:0"},
                {"COBBLESTONE_WALL", "COBBLE_WALL:0"}, {"MOSSY_COBBLESTONE", "MOSSY_COBBLESTONE:0"},
                {"RED_SAND", "SAND:1"}, {"GRANITE", "STONE:1"}, {"DIORITE", "STONE:3"}, {"ANDESITE", "STONE:5"},
                {"PRISMARINE_BRICKS", "PRISMARINE:1"}, {"DARK_PRISMARINE", "PRISMARINE:2"},
                {"CHISELED_QUARTZ_BLOCK", "QUARTZ_BLOCK:1"}, {"SLIME_BLOCK", "SLIME_BLOCK:0"},
                {"RABBIT_HIDE", "RABBIT_HIDE:0"}, {"TRIPWIRE_HOOK", "TRIPWIRE_HOOK:0"},
                {"FLOWER_POT", "FLOWER_POT_ITEM:0"}, {"CAULDRON", "CAULDRON_ITEM:0"},
                {"BREWING_STAND", "BREWING_STAND_ITEM:0"}, {"CAKE", "CAKE:0"}, {"REDSTONE_TORCH", "REDSTONE_TORCH_ON:0"},
                {"REDSTONE_LAMP", "REDSTONE_LAMP_OFF:0"}, {"POPPED_CHORUS_FRUIT", "CHORUS_FRUIT_POPPED:0"},
                {"DRAGON_BREATH", "DRAGONS_BREATH:0"}, {"MUSIC_DISC_13", "GOLD_RECORD:0"}, {"MUSIC_DISC_CAT", "GREEN_RECORD:0"},
                {"ZOMBIFIED_PIGLIN_SPAWN_EGG", "MONSTER_EGG:57"}, {"OAK_WOOD", "LOG:0"}
        };
        for (String[] s : simple) map(s[0], s[1]);
    }

    private static void map(String modern, String old) {
        TO_OLD.put(modern, old);
        TO_NEW.putIfAbsent(old, modern);
        String oldName = old.contains(":") ? old.substring(0, old.indexOf(':')) : old;
        if (old.endsWith(":0")) TO_NEW.putIfAbsent(oldName, modern);
    }

    private Materials() {
    }

    public static void init(Logger log) {
        logger = log;
    }

    /**
     * Item from a material text: "DIAMOND", "purple_stained_glass_pane", "STAINED_GLASS_PANE:10".
     * Returns null when the name doesn't exist on this version.
     * @param legacyData data value from the "data" key (only used on 1.8-1.12), -1 = none
     */
    @SuppressWarnings("deprecation")
    public static ItemStack item(String text, int legacyData) {
        if (text == null) return null;
        String name = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("MINECRAFT:", "");
        int data = -1;
        int colon = name.indexOf(':');
        if (colon > 0) {
            try {
                data = Integer.parseInt(name.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
            name = name.substring(0, colon);
        }

        if (Ver.flat()) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                String modern = data >= 0 ? TO_NEW.get(name + ":" + data) : null;
                if (modern == null) modern = TO_NEW.get(name);
                if (modern != null) m = Material.matchMaterial(modern);
            }
            if (m == null || isAir(m) && !name.contains("AIR")) return null;
            return new ItemStack(m);
        }

        // 1.8 - 1.12
        String old = TO_OLD.get(name);
        Material m;
        if (old != null) {
            int c = old.indexOf(':');
            m = Material.matchMaterial(old.substring(0, c));
            if (data < 0) data = Integer.parseInt(old.substring(c + 1));
        } else {
            m = Material.matchMaterial(name);
        }
        if (m == null) return null;
        if (data < 0 && legacyData >= 0) data = legacyData;
        ItemStack it = new ItemStack(m, 1);
        if (data > 0) it.setDurability((short) data);
        return it;
    }

    /** Same as item() but never null: an unknown name becomes a barrier/stone (and is reported once). */
    public static ItemStack itemOrFallback(String text, int legacyData, String where) {
        ItemStack it = item(text, legacyData);
        if (it != null) return it;
        if (logger != null && warned.add(text + "@" + where)) {
            logger.warning("Unknown material '" + text + "' in " + where + " on this version (" + Ver.text() + "), showing a barrier.");
        }
        ItemStack fb = item("BARRIER", -1);
        return fb != null ? fb : new ItemStack(Material.STONE);
    }

    public static boolean isAir(Material m) {
        return m == null || m.name().endsWith("AIR");
    }

    /** True if the material is a player head on this version. */
    @SuppressWarnings("deprecation")
    public static boolean isPlayerHead(ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        if (n.equals("PLAYER_HEAD") || n.equals("PLAYER_WALL_HEAD")) return true;
        return n.equals("SKULL_ITEM") && it.getDurability() == 3;
    }

    public static ItemStack playerHead() {
        return item("PLAYER_HEAD", -1);
    }
}

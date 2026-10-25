package dev.tmdevelopment.flaretokens.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that changes between 1.8 and the latest version, in one place.
 * Newer API is always reached through reflection with a fallback for old servers,
 * so one jar runs everywhere.
 */
public final class Compat {

    private static final Pattern URL_IN_JSON = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"");
    private static CommandMap commandMap;

    private Compat() {
    }

    // ------------------------------------------------------------------ commands

    public static CommandMap commandMap() {
        if (commandMap != null) return commandMap;
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            commandMap = (CommandMap) m.invoke(Bukkit.getServer());
        } catch (Throwable t) {
            throw new IllegalStateException("Can't reach the command map of this server", t);
        }
        return commandMap;
    }

    /** Sends the new command list to the players (1.13+, needed for commands added after startup). */
    public static void syncCommands() {
        try {
            Method m = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            m.setAccessible(true);
            m.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public static void removeKnown(Command command) {
        try {
            CommandMap map = commandMap();
            command.unregister(map);
            Map<String, Command> known = null;
            try {
                Method m = map.getClass().getMethod("getKnownCommands");
                known = (Map<String, Command>) m.invoke(map);
            } catch (NoSuchMethodException e) {
                Class<?> c = map.getClass();
                while (c != null && known == null) {
                    try {
                        Field f = c.getDeclaredField("knownCommands");
                        f.setAccessible(true);
                        known = (Map<String, Command>) f.get(map);
                    } catch (NoSuchFieldException ignored) {
                        c = c.getSuperclass();
                    }
                }
            }
            if (known == null) return;
            Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
            while (it.hasNext()) if (it.next().getValue() == command) it.remove();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ players

    /** Online player, or a player who already joined, by name. Never does a web lookup. */
    public static OfflinePlayer knownPlayer(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    private static Method mainHandGet;
    private static Method mainHandSet;
    private static boolean handChecked;

    private static void handMethods() {
        if (handChecked) return;
        handChecked = true;
        try {
            Class<?> inv = org.bukkit.inventory.PlayerInventory.class;
            mainHandGet = inv.getMethod("getItemInMainHand");
            mainHandSet = inv.getMethod("setItemInMainHand", ItemStack.class);
        } catch (Throwable ignored) {
        }
    }

    /** Item in the main hand (getItemInHand on 1.8). */
    @SuppressWarnings("deprecation")
    public static ItemStack hand(Player p) {
        handMethods();
        try {
            if (mainHandGet != null) return (ItemStack) mainHandGet.invoke(p.getInventory());
        } catch (Throwable ignored) {
        }
        return p.getItemInHand();
    }

    @SuppressWarnings("deprecation")
    public static void setHand(Player p, ItemStack item) {
        handMethods();
        try {
            if (mainHandSet != null) {
                mainHandSet.invoke(p.getInventory(), item);
                return;
            }
        } catch (Throwable ignored) {
        }
        p.setItemInHand(item);
    }

    /** false for the off-hand copy of an interact event (1.9+ fires the event once per hand). */
    public static boolean isMainHand(Object event) {
        try {
            Object hand = event.getClass().getMethod("getHand").invoke(event);
            return hand == null || "HAND".equals(hand.toString());
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ messages

    private static Method title5;
    private static boolean titleChecked;

    /** Title + subtitle (times in ticks; 1.8-1.10 use the default times). */
    @SuppressWarnings("deprecation")
    public static void title(Player p, String title, String subtitle, int in, int stay, int out) {
        if (!titleChecked) {
            titleChecked = true;
            try {
                title5 = Player.class.getMethod("sendTitle", String.class, String.class, int.class, int.class, int.class);
            } catch (Throwable ignored) {
            }
        }
        String t = title == null || title.isEmpty() ? " " : title;
        String s = subtitle == null ? "" : subtitle;
        try {
            if (title5 != null) {
                title5.invoke(p, t, s, in, stay, out);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            p.sendTitle(t, s);
        } catch (Throwable ignored) {
        }
    }

    private static Object actionBarType;
    private static Method actionBarSend;
    private static boolean actionChecked;

    /** Text above the hotbar. 1.9+ through the Spigot API, 1.8 through the chat packet. */
    public static void actionBar(Player p, String legacyText) {
        if (!actionChecked) {
            actionChecked = true;
            try {
                Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType");
                actionBarType = type.getField("ACTION_BAR").get(null);
                actionBarSend = Player.Spigot.class.getMethod("sendMessage", type, net.md_5.bungee.api.chat.BaseComponent[].class);
            } catch (Throwable ignored) {
            }
        }
        try {
            if (actionBarSend != null) {
                net.md_5.bungee.api.chat.BaseComponent[] parts = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(legacyText);
                actionBarSend.invoke(p.spigot(), actionBarType, parts);
                return;
            }
        } catch (Throwable ignored) {
        }
        legacyActionBar(p, legacyText);
    }

    /** 1.8: PacketPlayOutChat with type 2. */
    private static void legacyActionBar(Player p, String text) {
        try {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            String v = pkg.substring(pkg.lastIndexOf('.') + 1);
            String nms = "net.minecraft.server." + v + ".";
            Class<?> chatBase = Class.forName(nms + "IChatBaseComponent");
            Class<?> serializer = Class.forName(nms + "IChatBaseComponent$ChatSerializer");
            Object parts = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text);
            String json = (String) Class.forName("net.md_5.bungee.chat.ComponentSerializer")
                    .getMethod("toString", net.md_5.bungee.api.chat.BaseComponent[].class).invoke(null, parts);
            Object component = serializer.getMethod("a", String.class).invoke(null, json);
            Class<?> packetClass = Class.forName(nms + "PacketPlayOutChat");
            Object packet = packetClass.getConstructor(chatBase, byte.class).newInstance(component, (byte) 2);
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object connection = handle.getClass().getField("playerConnection").get(handle);
            connection.getClass().getMethod("sendPacket", Class.forName(nms + "Packet")).invoke(connection, packet);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ sounds / effects

    /**
     * Plays a sound by name: Sound field (ENTITY_PLAYER_LEVELUP), 1.8 name (LEVEL_UP) or key (entity.player.levelup).
     * Works with the Sound enum (old) and the Sound registry (1.21.3+).
     */
    public static void sound(Player p, String name, float volume, float pitch) {
        if (name == null || name.trim().isEmpty() || name.equalsIgnoreCase("none")) return;
        String n = name.trim();
        Location loc = p.getLocation();
        if (n.indexOf('.') < 0 && n.indexOf(':') < 0) {
            String key = n.toUpperCase(Locale.ROOT);
            Object sound = soundField(key);
            if (sound == null && !Ver.atLeast(9)) sound = soundField(OLD_SOUNDS.getOrDefault(key, key));
            if (sound != null) {
                try {
                    Player.class.getMethod("playSound", Location.class, org.bukkit.Sound.class, float.class, float.class)
                            .invoke(p, loc, sound, volume, pitch);
                    return;
                } catch (Throwable ignored) {
                }
            }
            n = key.toLowerCase(Locale.ROOT).replace('_', '.');
        }
        try {
            p.playSound(loc, n, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private static Object soundField(String name) {
        try {
            return org.bukkit.Sound.class.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Default sounds of the plugin on 1.8. */
    private static final Map<String, String> OLD_SOUNDS = new java.util.HashMap<>();

    static {
        OLD_SOUNDS.put("ENTITY_EXPERIENCE_ORB_PICKUP", "ORB_PICKUP");
        OLD_SOUNDS.put("ENTITY_PLAYER_LEVELUP", "LEVEL_UP");
        OLD_SOUNDS.put("UI_BUTTON_CLICK", "CLICK");
        OLD_SOUNDS.put("BLOCK_NOTE_BLOCK_PLING", "NOTE_PLING");
        OLD_SOUNDS.put("BLOCK_NOTE_PLING", "NOTE_PLING");
        OLD_SOUNDS.put("ENTITY_VILLAGER_NO", "VILLAGER_NO");
        OLD_SOUNDS.put("ENTITY_VILLAGER_YES", "VILLAGER_YES");
        OLD_SOUNDS.put("ENTITY_ITEM_PICKUP", "ITEM_PICKUP");
        OLD_SOUNDS.put("BLOCK_CHEST_OPEN", "CHEST_OPEN");
        OLD_SOUNDS.put("ENTITY_ENDERMAN_TELEPORT", "ENDERMAN_TELEPORT");
    }

    @SuppressWarnings("deprecation")
    public static PotionEffectType effect(String name) {
        if (name == null) return null;
        String n = name.trim();
        PotionEffectType t = PotionEffectType.getByName(n.toUpperCase(Locale.ROOT));
        if (t != null) return t;
        try {
            Class<?> nk = Class.forName("org.bukkit.NamespacedKey");
            Object key = nk.getMethod("minecraft", String.class).invoke(null, n.toLowerCase(Locale.ROOT));
            return (PotionEffectType) PotionEffectType.class.getMethod("getByKey", nk).invoke(null, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ items

    /** Enchantment glint without enchantments in the lore. */
    public static void glow(ItemStack item, ItemMeta meta) {
        try {
            Method m = meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class); // 1.20.5+
            m.invoke(meta, Boolean.TRUE);
            return;
        } catch (Throwable ignored) {
        }
        Enchantment e = enchant("unbreaking", "DURABILITY");
        if (e == null) e = enchant("luck_of_the_sea", "LUCK");
        if (e != null) {
            meta.addEnchant(e, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    @SuppressWarnings("deprecation")
    private static Enchantment enchant(String key, String oldName) {
        try {
            Class<?> nk = Class.forName("org.bukkit.NamespacedKey");
            Object k = nk.getMethod("minecraft", String.class).invoke(null, key);
            Object e = Enchantment.class.getMethod("getByKey", nk).invoke(null, k);
            if (e != null) return (Enchantment) e;
        } catch (Throwable ignored) {
        }
        try {
            return Enchantment.getByName(oldName);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Custom model data (1.14+), ignored on older versions. */
    public static void modelData(ItemMeta meta, int value) {
        if (value <= 0) return;
        try {
            meta.getClass().getMethod("setCustomModelData", Integer.class).invoke(meta, value);
        } catch (Throwable ignored) {
        }
    }

    /** Hides attributes, effects... from the lore of a menu item. */
    public static void hideFlags(ItemMeta meta) {
        for (ItemFlag f : ItemFlag.values()) {
            String n = f.name();
            if (n.equals("HIDE_ATTRIBUTES") || n.equals("HIDE_POTION_EFFECTS") || n.equals("HIDE_ADDITIONAL_TOOLTIP")
                    || n.equals("HIDE_UNBREAKABLE")) {
                try {
                    meta.addItemFlags(f);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Boolean skinLookups;

    /**
     * Can the server look up the skin of a player? Not on offline-mode servers without BungeeCord / Velocity:
     * the ids are not Mojang ids, every lookup fails and Paper writes a warning for each head.
     */
    public static boolean skinLookups() {
        if (skinLookups != null) return skinLookups;
        boolean ok = Bukkit.getOnlineMode();
        if (!ok) {
            try {
                ok = Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
            } catch (Throwable ignored) {
            }
        }
        if (!ok) {
            java.io.File paper = new java.io.File("config", "paper-global.yml");
            java.io.File old = new java.io.File("paper.yml");
            if (paper.isFile()) ok = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(paper).getBoolean("proxies.velocity.enabled", false);
            if (!ok && old.isFile()) ok = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(old).getBoolean("settings.velocity-support.enabled", false);
        }
        skinLookups = ok;
        return ok;
    }

    /** Copies the skin an online player already has (also from SkinsRestorer) on a head, without lookups. 1.18.1+. */
    public static boolean copySkin(SkullMeta meta, Player p) {
        try {
            Class<?> type = Class.forName("org.bukkit.profile.PlayerProfile");
            Object profile = Player.class.getMethod("getPlayerProfile").invoke(p);
            if (!type.isInstance(profile)) return false;
            Object textures = type.getMethod("getTextures").invoke(profile);
            if (Boolean.TRUE.equals(Class.forName("org.bukkit.profile.PlayerTextures").getMethod("isEmpty").invoke(textures))) return false;
            SkullMeta.class.getMethod("setOwnerProfile", type).invoke(meta, profile);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Puts a texture (base64 value from minecraft-heads.com) on a head. */
    public static void setTexture(SkullMeta meta, String base64) {
        UUID id = UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8));
        // 1.18.1+ : PlayerProfile API (Spigot and Paper)
        try {
            String url = textureUrl(base64);
            if (url != null) {
                Method create = Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class);
                Object profile = create.invoke(null, id, "FlareHead");
                Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                textures.getClass().getMethod("setSkin", URL.class).invoke(textures, new URL(url));
                profile.getClass().getMethod("setTextures", Class.forName("org.bukkit.profile.PlayerTextures")).invoke(profile, textures);
                Method set = meta.getClass().getMethod("setOwnerProfile", Class.forName("org.bukkit.profile.PlayerProfile"));
                set.invoke(meta, profile);
                return;
            }
        } catch (Throwable ignored) {
        }
        // older: Mojang GameProfile inside CraftMetaSkull
        try {
            Class<?> gp = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> prop = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gp.getConstructor(UUID.class, String.class).newInstance(id, "FlareHead");
            Object props = gp.getMethod("getProperties").invoke(profile);
            Object property = prop.getConstructor(String.class, String.class).newInstance("textures", base64);
            props.getClass().getMethod("put", Object.class, Object.class).invoke(props, "textures", property);
            try {
                Method set = meta.getClass().getDeclaredMethod("setProfile", gp);
                set.setAccessible(true);
                set.invoke(meta, profile);
            } catch (NoSuchMethodException e) {
                Field f = meta.getClass().getDeclaredField("profile");
                f.setAccessible(true);
                f.set(meta, profile);
            }
        } catch (Throwable ignored) {
        }
    }

    /** A head written back for a menu file: texture:&lt;base64&gt;, head:&lt;name&gt; or null. */
    public static String describeHead(SkullMeta meta) {
        // texture inside the Mojang profile (1.8 - 1.20.4)
        try {
            Field f = meta.getClass().getDeclaredField("profile");
            f.setAccessible(true);
            Object profile = f.get(meta);
            if (profile != null && profile.getClass().getName().equals("com.mojang.authlib.GameProfile")) {
                Object props = profile.getClass().getMethod("getProperties").invoke(profile);
                Object list = props.getClass().getMethod("get", Object.class).invoke(props, "textures");
                for (Object prop : (Iterable<?>) list) {
                    String v;
                    try {
                        v = (String) prop.getClass().getMethod("value").invoke(prop);
                    } catch (NoSuchMethodException e) {
                        v = (String) prop.getClass().getMethod("getValue").invoke(prop);
                    }
                    if (v != null && !v.isEmpty()) return "texture:" + v;
                }
            }
        } catch (Throwable ignored) {
        }
        // PlayerProfile API (1.18.1+)
        try {
            Object profile = meta.getClass().getMethod("getOwnerProfile").invoke(meta);
            if (profile != null) {
                Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
                Object skin = textures.getClass().getMethod("getSkin").invoke(textures);
                if (skin != null) {
                    String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}";
                    return "texture:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
                }
                Object name = profile.getClass().getMethod("getName").invoke(profile);
                if (name != null && !"FlareHead".equals(name)) return "head:" + name;
            }
        } catch (Throwable ignored) {
        }
        try {
            @SuppressWarnings("deprecation")
            String owner = meta.getOwner();
            if (owner != null && !owner.isEmpty() && !"FlareHead".equals(owner)) return "head:" + owner;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ hidden item data (1.14+)

    /** Saves a hidden text in the item (PersistentDataContainer, 1.14+). false on older versions. */
    public static boolean setData(org.bukkit.plugin.Plugin plugin, ItemMeta meta, String key, String value) {
        try {
            Object k = Class.forName("org.bukkit.NamespacedKey").getConstructor(org.bukkit.plugin.Plugin.class, String.class).newInstance(plugin, key);
            Class<?> holder = Class.forName("org.bukkit.persistence.PersistentDataHolder");
            Class<?> container = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Object pdc = holder.getMethod("getPersistentDataContainer").invoke(meta);
            Class<?> type = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object string = type.getField("STRING").get(null);
            if (value == null) {
                container.getMethod("remove", k.getClass()).invoke(pdc, k);
            } else {
                container.getMethod("set", k.getClass(), type, Object.class).invoke(pdc, k, string, value);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Hidden text saved with setData, or null. */
    public static String getData(org.bukkit.plugin.Plugin plugin, ItemMeta meta, String key) {
        try {
            Object k = Class.forName("org.bukkit.NamespacedKey").getConstructor(org.bukkit.plugin.Plugin.class, String.class).newInstance(plugin, key);
            Class<?> holder = Class.forName("org.bukkit.persistence.PersistentDataHolder");
            Class<?> container = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            Object pdc = holder.getMethod("getPersistentDataContainer").invoke(meta);
            Class<?> type = Class.forName("org.bukkit.persistence.PersistentDataType");
            Object string = type.getField("STRING").get(null);
            Object v = container.getMethod("get", k.getClass(), type).invoke(pdc, k, string);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true when the item shows the enchantment glint. */
    public static boolean hasGlow(ItemMeta meta) {
        try {
            Object o = meta.getClass().getMethod("getEnchantmentGlintOverride").invoke(meta);
            if (Boolean.TRUE.equals(o)) return true;
        } catch (Throwable ignored) {
        }
        return meta.hasEnchants();
    }

    /** Custom model data of an item, 0 when none (1.14+). */
    public static int modelData(ItemMeta meta) {
        try {
            if ((Boolean) meta.getClass().getMethod("hasCustomModelData").invoke(meta)) {
                return (Integer) meta.getClass().getMethod("getCustomModelData").invoke(meta);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String textureUrl(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            Matcher m = URL_IN_JSON.matcher(json);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ menus

    /**
     * Holder of the menu a player has open. InventoryView is a class before 1.21 and an interface after,
     * so it is only reached by reflection.
     */
    public static org.bukkit.inventory.InventoryHolder topHolder(Player p) {
        try {
            Object view = Player.class.getMethod("getOpenInventory").invoke(p);
            Object top = view.getClass().getMethod("getTopInventory").invoke(view);
            return top instanceof org.bukkit.inventory.Inventory ? ((org.bukkit.inventory.Inventory) top).getHolder() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Inventory titles longer than 32 characters crash 1.8. */
    public static String menuTitle(String legacy) {
        if (Ver.atLeast(9) || legacy.length() <= 32) return legacy;
        // 1.8: 32 characters, color codes included. Gradients repeat the codes on every letter: write each change once
        String compact = compactLegacy(legacy);
        if (compact.length() <= 32) return compact;
        // still too long: one style for the whole title
        String first = leadingCodes(compact);
        String single = first + compact.replaceAll("\u00a7.", "");
        return cut(single.length() <= 32 ? single : compact, 32);
    }

    /** Legacy text with a color/format code only where the style really changes. */
    static String compactLegacy(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char color = 0, shownColor = 0;
        StringBuilder fmt = new StringBuilder();
        String shownFmt = "";
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                char k = Character.toLowerCase(s.charAt(++i));
                if ((k >= '0' && k <= '9') || (k >= 'a' && k <= 'f')) {
                    color = k;
                    fmt.setLength(0);
                } else if (k == 'r') {
                    color = 0;
                    fmt.setLength(0);
                } else if ("klmno".indexOf(k) >= 0 && fmt.indexOf(String.valueOf(k)) < 0) {
                    fmt.append(k);
                }
                continue;
            }
            String f = fmt.toString();
            boolean same = color == shownColor && f.equals(shownFmt);
            // the color of a space can't be seen (unless underlined or struck through)
            if (c == ' ' && started && f.equals(shownFmt) && f.indexOf('m') < 0 && f.indexOf('n') < 0) same = true;
            if (!same || !started && (color != 0 || !f.isEmpty())) {
                if (started && color == shownColor && f.startsWith(shownFmt)) {
                    for (char x : f.substring(shownFmt.length()).toCharArray()) out.append('\u00a7').append(x);
                } else {
                    if (color != 0 || started) out.append('\u00a7').append(color == 0 ? 'r' : color);
                    for (char x : f.toCharArray()) out.append('\u00a7').append(x);
                }
                shownColor = color;
                shownFmt = f;
            }
            started = true;
            out.append(c);
        }
        return out.toString();
    }

    private static String leadingCodes(String s) {
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '\u00a7') i += 2;
        return s.substring(0, i);
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) return s;
        String t = s.substring(0, max);
        if (t.endsWith("\u00a7")) t = t.substring(0, max - 1);
        return t;
    }
}

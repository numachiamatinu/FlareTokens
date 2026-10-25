package dev.tmdevelopment.flaretokens.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An item written in a config file. Same keys as FlareTokens 3.x:
 * <pre>
 *   material: DIAMOND                (or "[head] &lt;base64&gt;", "[player_head] {player}", "[ia] namespace:id", "[potion] SPEED")
 *   amount: 1
 *   display_name: "&amp;bName"
 *   lore: ["line", "line"]
 *   custom_model_data: 0              (also "data" / "custom_model" like 3.x, on 1.8-1.12 "data" is the color/data value)
 *   glow: false
 * </pre>
 */
public final class ItemSpec {

    private static final Pattern PREFIX = Pattern.compile("^\\[([^]]*)]\\s*(.*)$");

    private String material = "STONE";
    private boolean materialSet;
    private int amount = 1;
    private String name;
    private List<String> lore = Collections.emptyList();
    private int data = -1;
    private int modelData;
    private int damage;
    private boolean glow;
    private String where = "config";

    public static ItemSpec of(ConfigurationSection s, String where) {
        ItemSpec spec = new ItemSpec();
        spec.where = where;
        if (s == null) return spec;
        spec.materialSet = s.isSet("material");
        spec.material = s.getString("material", "STONE");
        spec.amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
        if (s.isSet("display_name")) spec.name = s.getString("display_name");
        else if (s.isSet("display-name")) spec.name = s.getString("display-name");
        else if (s.isSet("name")) spec.name = s.getString("name");
        if (s.isList("lore")) spec.lore = s.getStringList("lore");
        else if (s.isString("lore")) spec.lore = Collections.singletonList(s.getString("lore"));
        if (s.isSet("data")) spec.data = s.getInt("data");
        spec.modelData = s.getInt("custom_model_data", s.getInt("custom-model-data", s.getInt("custom_model", s.getInt("model", 0))));
        // 3.x used "data" as custom model data on new versions
        if (spec.modelData == 0 && spec.data > 0 && Ver.atLeast(14)) spec.modelData = spec.data;
        spec.damage = s.getInt("damage", 0);
        spec.glow = s.getBoolean("glow", false);
        return spec;
    }

    public static ItemSpec simple(String material, String name, List<String> lore) {
        ItemSpec spec = new ItemSpec();
        spec.material = material;
        spec.materialSet = material != null;
        spec.name = name;
        spec.lore = lore == null ? Collections.<String>emptyList() : lore;
        return spec;
    }

    public String name() {
        return name;
    }

    public String material() {
        return material;
    }

    public List<String> lore() {
        return lore;
    }

    public int amount() {
        return amount;
    }

    public ItemSpec glow(boolean value) {
        this.glow = value;
        return this;
    }

    public ItemSpec amount(int value) {
        this.amount = Math.max(1, Math.min(64, value));
        return this;
    }

    /** true when the file has no "material" (items saved whole by the editor). */
    public boolean materialUnset() {
        return !materialSet;
    }

    /** Builds the item for a menu; every text goes through the resolver (placeholders) and then the text engine. */
    public ItemStack build(UnaryOperator<String> resolve) {
        return build(resolve, true);
    }

    /** Builds an item given to a player: no hidden flags and no extra data, so it stacks with normal items. */
    public ItemStack buildReal(UnaryOperator<String> resolve) {
        return build(resolve, false);
    }

    @SuppressWarnings("deprecation")
    private ItemStack build(UnaryOperator<String> resolve, boolean menu) {
        if (resolve == null) resolve = UnaryOperator.identity();
        ItemStack item = base(resolve);
        item.setAmount(amount);
        if (damage > 0) item.setDurability((short) damage);
        if (!menu && name == null && lore.isEmpty() && modelData == 0 && !glow) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.setDisplayName(Text.item(resolve.apply(name)));
        if (!lore.isEmpty()) {
            List<String> out = new ArrayList<>(lore.size());
            for (String line : lore) {
                String r = resolve.apply(line);
                // a placeholder can return more lines
                for (String part : r.split("\n", -1)) out.add(Text.item(part));
            }
            meta.setLore(out);
        }
        Compat.modelData(meta, modelData);
        if (glow) Compat.glow(item, meta);
        if (menu) Compat.hideFlags(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack base(UnaryOperator<String> resolve) {
        String mat = material == null ? "STONE" : material.trim();
        Matcher m = PREFIX.matcher(mat);
        String type = null;
        String value = mat;
        if (m.find()) {
            type = m.group(1).toLowerCase(Locale.ROOT);
            value = m.group(2).trim();
        } else {
            // also accept "head:<base64>", "player:<name>"
            int c = mat.indexOf(':');
            if (c > 0) {
                String p = mat.substring(0, c).toLowerCase(Locale.ROOT);
                if (p.equals("head") || p.equals("texture") || p.equals("base64") || p.equals("player") || p.equals("ia")) {
                    type = p.equals("player") ? "player_head" : p;
                    value = mat.substring(c + 1).trim();
                }
            }
        }
        if (type == null) return Materials.itemOrFallback(mat, data, where);

        // head:{player} / head:Notch = skin of that player; head:<base64> = texture (also [head] <base64> from 3.x)
        if (type.equals("head") && !looksLikeTexture(value)) type = "player_head";
        switch (type) {
            case "head":
            case "base64":
            case "texture": {
                ItemStack head = Materials.playerHead();
                if (head == null) return Materials.itemOrFallback("STONE", -1, where);
                ItemMeta meta = head.getItemMeta();
                if (meta instanceof SkullMeta) {
                    Compat.setTexture((SkullMeta) meta, value);
                    head.setItemMeta(meta);
                }
                return head;
            }
            case "player_head":
            case "player": {
                ItemStack head = Materials.playerHead();
                if (head == null) return Materials.itemOrFallback("STONE", -1, where);
                ItemMeta meta = head.getItemMeta();
                if (meta instanceof SkullMeta) {
                    setOwner((SkullMeta) meta, resolve.apply(value));
                    head.setItemMeta(meta);
                }
                return head;
            }
            case "ia":
            case "itemsadder": {
                ItemStack it = itemsAdder(value);
                return it != null ? it : Materials.itemOrFallback("STONE", -1, where);
            }
            case "ie":
            case "executableitems": {
                ItemStack it = executableItem(value);
                return it != null ? it : Materials.itemOrFallback("STONE", -1, where);
            }
            case "potion": {
                ItemStack potion = Materials.itemOrFallback("POTION", -1, where);
                ItemMeta meta = potion.getItemMeta();
                PotionEffectType t = Compat.effect(value);
                if (meta instanceof PotionMeta && t != null) {
                    ((PotionMeta) meta).addCustomEffect(new PotionEffect(t, 20, 0), true);
                    potion.setItemMeta(meta);
                }
                return potion;
            }
            case "empty":
            case "air":
                return Materials.itemOrFallback("AIR", -1, where);
            default:
                return Materials.itemOrFallback(value, data, where);
        }
    }

    /** A minecraft-heads value (base64, long) or a texture id, not a player name. */
    static boolean looksLikeTexture(String v) {
        if (v == null) return false;
        String t = v.trim();
        if (t.length() > 16) return true;
        return t.matches("[0-9a-f]{40,}");
    }

    @SuppressWarnings("deprecation")
    private static void setOwner(SkullMeta meta, String name) {
        if (name == null || name.isEmpty()) return;
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        if (online != null && Compat.copySkin(meta, online)) return;
        if (!Compat.skinLookups()) return; // offline-mode server: a plain head instead of a failed lookup
        if (online != null) {
            try {
                SkullMeta.class.getMethod("setOwningPlayer", org.bukkit.OfflinePlayer.class).invoke(meta, online);
                return;
            } catch (Throwable ignored) {
            }
        }
        meta.setOwner(name);
    }

    private static ItemStack itemsAdder(String id) {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null;
        try {
            Class<?> cs = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Object stack = cs.getMethod("getInstance", String.class).invoke(null, id);
            if (stack == null) return null;
            return (ItemStack) cs.getMethod("getItemStack").invoke(stack);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack executableItem(String id) {
        if (Bukkit.getPluginManager().getPlugin("ExecutableItems") == null) return null;
        try {
            Class<?> api = Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI");
            Object manager = api.getMethod("getExecutableItemsManager").invoke(null);
            Object opt = manager.getClass().getMethod("getExecutableItem", String.class).invoke(manager, id);
            if (!(opt instanceof java.util.Optional) || !((java.util.Optional<?>) opt).isPresent()) return null;
            Object ei = ((java.util.Optional<?>) opt).get();
            return (ItemStack) ei.getClass().getMethod("buildItem", int.class, java.util.Optional.class)
                    .invoke(ei, 1, java.util.Optional.empty());
        } catch (Throwable t) {
            return null;
        }
    }
}

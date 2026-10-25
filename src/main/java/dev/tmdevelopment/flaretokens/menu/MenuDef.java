package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.util.ItemSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * A menu file: a shop in shops/ or a menu of the plugin in menus/. Same layout as the other Flare plugins:
 * <pre>
 * enabled / title / rows / open-commands / open-permission
 * items (shops) or elements (menus of the plugin): slots, material, name, lore, actions ... and for products
 *     price, give, commands, confirm, limit, stock, permission
 * decorations: glass panes and other items, drawn under the items
 * texts: texts used by this menu
 * </pre>
 * FlareTokens 3.x shop files (menu_title, size, display_name, slot, click_commands, view_requirements ...) are read too.
 */
public final class MenuDef {

    final String id;
    final File file;
    final boolean builtin;
    final YamlConfiguration yaml;
    boolean enabled = true;
    String title = "&8Menu";
    int size = 27;
    InventoryType type;
    String permission;
    List<String> openRequirements = Collections.emptyList();
    List<String> openActions = Collections.emptyList();
    List<String> denyActions = Collections.emptyList();
    List<String> openCommands = Collections.emptyList();
    final Map<String, MenuItem> items = new LinkedHashMap<>();
    final List<Deco> decorations = new ArrayList<>();
    final Map<Integer, List<MenuItem>> slots = new HashMap<>();
    /** Texts of the file, flat ("product.buy" -> value), with the defaults under them. */
    final Map<String, Object> texts = new HashMap<>();
    boolean hasUpdating;
    boolean hasProducts;
    /** The file has a mistake: the defaults are shown and the editor can't save over it. */
    boolean broken;

    public boolean broken() {
        return broken;
    }

    /** A decoration: drawn under the items, it can have actions. */
    public static final class Deco {
        List<Integer> slots = new ArrayList<>();
        ItemSpec spec;
        ItemStack item;
        boolean adminOnly;
        List<String> actions = new ArrayList<>();
        /** Position in the file (editor). */
        int index;
    }

    private MenuDef(String id, File file, boolean builtin, YamlConfiguration yaml) {
        this.id = id;
        this.file = file;
        this.builtin = builtin;
        this.yaml = yaml;
    }

    public String id() {
        return id;
    }

    public boolean builtin() {
        return builtin;
    }

    public boolean enabled() {
        return enabled;
    }

    public File file() {
        return file;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public List<String> openCommands() {
        return openCommands;
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    public Map<String, MenuItem> items() {
        return items;
    }

    public MenuItem item(String key) {
        return items.get(key);
    }

    /** A text of the menu: the file first, then the defaults. Lists are joined with new lines. */
    public String text(String key, String fallback) {
        Object o = texts.get(key);
        if (o == null) return fallback;
        if (o instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<?>) o) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(x);
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    public List<String> lines(String key) {
        Object o = texts.get(key);
        if (o == null) return Collections.emptyList();
        if (o instanceof List<?>) {
            List<String> out = new ArrayList<>();
            for (Object x : (List<?>) o) out.add(String.valueOf(x));
            return out;
        }
        String s = String.valueOf(o);
        return s.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(s);
    }

    // ------------------------------------------------------------------ loading

    static MenuDef load(String id, File file, boolean builtin, YamlConfiguration yaml, ConfigurationSection defaultTexts, Logger log) {
        MenuDef m = new MenuDef(id, file, builtin, yaml);
        String where = (builtin ? "menus/" : "shops/") + file.getName();
        m.enabled = yaml.getBoolean("enabled", true);
        m.title = yaml.getString("title", yaml.getString("menu_title", m.title));
        if (yaml.isSet("type") && !"CHEST".equalsIgnoreCase(yaml.getString("type"))) {
            // hoppers, dispensers ... lose the menu holder on Spigot (items could be taken): always a chest
            log.warning(where + ": type " + yaml.getString("type") + " is not supported, using a chest (rows / size).");
        }
        int size = yaml.isSet("rows") ? yaml.getInt("rows") * 9 : yaml.getInt("size", 27);
        if (size < 9) size = 9;
        if (size > 54) size = 54;
        if (size % 9 != 0) size = ((size / 9) + 1) * 9;
        m.size = size;
        m.permission = blank(first(yaml, "open-permission", "menu_permission", "permission"));
        m.openRequirements = list(yaml, "open-requirements", "open_requirements", "open_requirement");
        m.openActions = list(yaml, "open-actions", "open_commands", "open_actions");
        m.denyActions = list(yaml, "deny-actions", "deny_commands", "deny_actions");
        m.openCommands = list(yaml, "open-commands", "open_command", "commands");

        // texts: defaults first, the file on top
        if (defaultTexts != null) for (String k : defaultTexts.getKeys(true)) if (!defaultTexts.isConfigurationSection(k)) m.texts.put(k, defaultTexts.get(k));
        ConfigurationSection ts = yaml.getConfigurationSection("texts");
        if (ts != null) for (String k : ts.getKeys(true)) if (!ts.isConfigurationSection(k)) m.texts.put(k, ts.get(k));

        int order = 0;
        for (String section : new String[]{"elements", "items"}) {
            ConfigurationSection items = yaml.getConfigurationSection(section);
            if (items == null) continue;
            for (String key : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(key);
                if (s == null || m.items.containsKey(key)) continue;
                MenuItem it = MenuItem.read(key, s, section.equals("elements"), where + " > " + key, log);
                it.order = order++;
                it.slots = slots(s, m.size, log, where + " > " + key);
                m.items.put(key, it);
                if (it.update) m.hasUpdating = true;
                if (it.product != null) m.hasProducts = true;
                for (int slot : it.slots) m.slots.computeIfAbsent(slot, x -> new ArrayList<>()).add(it);
            }
        }
        Comparator<MenuItem> cmp = Comparator.comparingInt((MenuItem i) -> i.priority).thenComparingInt(i -> i.order);
        for (List<MenuItem> l : m.slots.values()) l.sort(cmp);

        List<?> decos = yaml.getList("decorations");
        if (decos != null) {
            int idx = 0;
            for (Object o : decos) {
                if (o instanceof Map<?, ?>) {
                    Deco d = readDeco((Map<?, ?>) o, m.size, where + " > decorations");
                    d.index = idx++;
                    m.decorations.add(d);
                }
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Deco readDeco(Map<?, ?> map, int size, String where) {
        Deco d = new Deco();
        d.slots = parseSlots(map.get("slots"), size);
        if (map.containsKey("slot")) d.slots.addAll(parseSlots(map.get("slot"), size));
        Object item = map.get("item");
        if (item instanceof ItemStack) d.item = (ItemStack) item;
        MemoryMap mm = new MemoryMap((Map<String, Object>) map);
        d.spec = ItemSpec.of(mm.section(), where);
        Object ao = map.get("admin-only");
        d.adminOnly = ao instanceof Boolean && (Boolean) ao;
        Object a = map.get("actions");
        if (a instanceof List<?>) for (Object x : (List<?>) a) d.actions.add(String.valueOf(x));
        return d;
    }

    /** Wraps a plain map (a list entry of the file) as a section, for ItemSpec. */
    static final class MemoryMap {
        private final Map<String, Object> map;

        MemoryMap(Map<String, Object> map) {
            this.map = map;
        }

        ConfigurationSection section() {
            YamlConfiguration y = new YamlConfiguration();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() == null || e.getKey().equals("item")) continue;
                y.set(String.valueOf(e.getKey()), e.getValue());
            }
            return y;
        }
    }

    // ------------------------------------------------------------------ slots "4", "19-25", "0-8,45-53", [1, 2, "3-5"]

    static List<Integer> slots(ConfigurationSection s, int size, Logger log, String where) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String key : new String[]{"slots", "slot"}) {
            if (!s.isSet(key)) continue;
            for (int i : parseSlots(s.get(key), 54)) out.add(i);
        }
        List<Integer> list = new ArrayList<>(out);
        list.removeIf(i -> i < 0 || i >= size);
        return list;
    }

    public static List<Integer> parseSlots(Object o, int size) {
        TreeSet<Integer> out = new TreeSet<>();
        if (o == null) return new ArrayList<>();
        if (o instanceof Number) {
            out.add(((Number) o).intValue());
        } else if (o instanceof List<?>) {
            for (Object x : (List<?>) o) out.addAll(parseSlots(x, size));
        } else {
            for (String part : String.valueOf(o).split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                try {
                    int dash = p.indexOf('-', 1);
                    if (dash > 0) {
                        int a = Integer.parseInt(p.substring(0, dash).trim());
                        int b = Integer.parseInt(p.substring(dash + 1).trim());
                        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) out.add(i);
                    } else {
                        out.add(Integer.parseInt(p));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        out.removeIf(i -> i < 0 || i >= size);
        return new ArrayList<>(out);
    }

    public static String formatSlots(List<Integer> slots) {
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(slots));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            int start = sorted.get(i);
            int end = start;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) end = sorted.get(++i);
            if (sb.length() > 0) sb.append(',');
            sb.append(start == end ? String.valueOf(start) : start + "-" + end);
            i++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    static String first(ConfigurationSection s, String... keys) {
        for (String k : keys) if (s.isSet(k)) return s.getString(k);
        return null;
    }

    static List<String> list(ConfigurationSection s, String... keys) {
        for (String k : keys) {
            if (s.isList(k)) return s.getStringList(k);
            if (s.isString(k)) {
                String v = s.getString(k);
                return v == null || v.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(v);
            }
        }
        return Collections.emptyList();
    }

    static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}

package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.util.ItemSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** One item (or element) of a menu file. It can be in several slots. */
public final class MenuItem {

    String key;
    boolean element;
    ItemSpec spec;
    /** The exact item (saved by the in-game editor), drawn instead of the spec. */
    ItemStack exact;
    List<Integer> slots = new ArrayList<>();
    int priority;
    int order;
    boolean update;
    boolean adminOnly;
    List<String> showIf = Collections.emptyList();
    List<String> hideIf = Collections.emptyList();
    List<String> clickAny = new ArrayList<>();
    Map<String, List<String>> clickBy = new HashMap<>();
    List<String> onUpdate = Collections.emptyList();
    Product product;

    /** What a click on a product sells. */
    public static final class Product {
        long price;
        List<Object> give = new ArrayList<>(); // ItemStack or ItemSpec
        List<String> commands = Collections.emptyList();
        Boolean confirm;
        int limit;
        String limitReset = "never";
        int stock;
        String stockReset = "never";
        String permission;
        boolean sale = true;

        public long price() {
            return price;
        }

        public int limit() {
            return limit;
        }

        public int stock() {
            return stock;
        }

        public boolean sale() {
            return sale;
        }
    }

    public String key() {
        return key;
    }

    public Product product() {
        return product;
    }

    public List<Integer> slots() {
        return slots;
    }

    public boolean adminOnly() {
        return adminOnly;
    }

    public String name() {
        return spec == null ? null : spec.name();
    }

    static final String[] CLICKS = {"LEFT", "SHIFT_LEFT", "RIGHT", "SHIFT_RIGHT", "MIDDLE", "NUMBER_KEY", "DOUBLE_CLICK",
            "DROP", "CONTROL_DROP", "SWAP_OFFHAND"};

    static MenuItem read(String key, ConfigurationSection s, boolean element, String where, Logger log) {
        MenuItem it = new MenuItem();
        it.key = key;
        it.element = element;
        it.spec = ItemSpec.of(s, where);
        Object exact = s.get("item");
        if (exact instanceof ItemStack) it.exact = (ItemStack) exact;
        it.priority = s.getInt("priority", 0);
        it.update = s.getBoolean("update", false);
        it.adminOnly = s.getBoolean("admin-only", false);
        List<String> show = new ArrayList<>(MenuDef.list(s, "show-if"));
        show.addAll(MenuDef.list(s, "view_requirements", "view_requirement_list", "view_requirement"));
        it.showIf = show;
        it.hideIf = MenuDef.list(s, "hide-if");
        it.onUpdate = MenuDef.list(s, "on_update", "on-update");
        for (String line : MenuDef.list(s, "actions", "click_commands", "click_actions")) {
            String l = line.trim();
            String click = clickPrefix(l);
            if (click != null) {
                it.clickBy.computeIfAbsent(click, k -> new ArrayList<>()).add(l.substring(l.indexOf(']') + 1).trim());
            } else {
                it.clickAny.add(l);
            }
        }
        // left_click_commands: / right_click_commands: ... (3.x)
        for (String k : s.getKeys(false)) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.endsWith("_click_commands") || lk.endsWith("_click_actions")) {
                String click = lk.substring(0, lk.indexOf("_click")).toUpperCase(Locale.ROOT);
                it.clickBy.computeIfAbsent(click, x -> new ArrayList<>()).addAll(s.getStringList(k));
            }
        }
        if (s.isSet("price")) {
            Product p = new Product();
            p.price = Math.max(0, s.getLong("price", 0));
            List<?> give = s.getList("give");
            if (give != null) {
                int n = 0;
                for (Object o : give) {
                    if (o instanceof ItemStack) {
                        p.give.add(o);
                    } else if (o instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) o;
                        p.give.add(ItemSpec.of(new MenuDef.MemoryMap(map).section(), where + " > give " + (++n)));
                    } else if (o instanceof String) {
                        // "DIAMOND 3"
                        String[] parts = ((String) o).trim().split("\\s+");
                        ItemSpec spec = ItemSpec.simple(parts[0], null, null);
                        if (parts.length > 1) spec.amount(parseInt(parts[1], 1));
                        p.give.add(spec);
                    }
                }
            }
            p.commands = MenuDef.list(s, "commands");
            if (s.isSet("confirm")) p.confirm = s.getBoolean("confirm");
            p.limit = Math.max(0, s.getInt("limit", 0));
            p.limitReset = s.getString("limit-reset", "never").toLowerCase(Locale.ROOT);
            p.stock = Math.max(0, s.getInt("stock", 0));
            p.stockReset = s.getString("stock-reset", "never").toLowerCase(Locale.ROOT);
            p.permission = MenuDef.blank(s.getString("permission"));
            p.sale = s.getBoolean("sale", true);
            it.product = p;
        }
        return it;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static String clickPrefix(String line) {
        if (!line.startsWith("[")) return null;
        int end = line.indexOf(']');
        if (end < 0) return null;
        String t = line.substring(1, end).trim().toUpperCase(Locale.ROOT);
        for (String c : CLICKS) if (c.equals(t)) return c;
        return null;
    }
}

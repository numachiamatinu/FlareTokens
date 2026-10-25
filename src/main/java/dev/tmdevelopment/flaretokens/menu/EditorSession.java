package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A menu in edit mode, same as in the other Flare plugins: free drag and drop, items from your inventory become
 * decorations, closing saves the file.
 * <ul>
 *   <li>shift + right click on an empty slot: palette (glass, buttons, links to other menus, sell the item in your hand)</li>
 *   <li>shift + right click on an item: visible to admins only on / off</li>
 *   <li>shift + left click on an item (shops): product settings (price, limit, stock ...)</li>
 * </ul>
 */
public final class EditorSession implements InventoryHolder {

    /** Editor items carry a lore line "⚙ ft:i:key" / "⚙ ft:d:index" / "⚙ ft:l:key" (works on every version). */
    static final String MARK = "\u2699 ft:";

    private final FlareTokens plugin;
    final Player viewer;
    MenuDef def;
    private Inventory inventory;
    private final Set<String> rendered = new HashSet<>();
    /** Slots of decorations hidden under an item in the editor (kept while an item stays on them). */
    private final Map<Integer, Set<Integer>> hiddenDeco = new java.util.HashMap<>();
    /** Slots of items hidden by another item with a lower priority (kept while an item stays on them). */
    private final Map<String, Set<Integer>> shadowed = new java.util.HashMap<>();

    public EditorSession(FlareTokens plugin, Player viewer, MenuDef def) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.def = def;
    }

    public void open() {
        if (def.broken) {
            plugin.lang().send(viewer, "editor.broken", "{menu}", def.id);
            return;
        }
        String title = Compat.menuTitle(Text.legacy(Placeholders.apply(plugin.lang().raw("gui.editor.title"), viewer, "{menu}", def.id)));
        inventory = Bukkit.createInventory(this, def.size, title);
        build();
        viewer.openInventory(inventory);
        plugin.lang().send(viewer, "editor.editing", "{menu}", def.id);
    }

    public void openLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            MenuDef fresh = plugin.menus().def(def.id);
            if (fresh != null) def = fresh;
            open();
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ drawing

    private void build() {
        inventory.clear();
        rendered.clear();
        hiddenDeco.clear();
        shadowed.clear();
        Map<Integer, Integer> decoAt = new java.util.HashMap<>();
        for (MenuDef.Deco d : def.decorations) {
            String[] ex = samples(-1);
            ItemStack base = d.item != null ? d.item.clone() : d.spec.build(s -> Placeholders.apply(s, viewer, ex));
            if (base == null) continue;
            if (d.item == null && hasPlaceholder(d.spec)) base = addLore(base, plugin.lang().lines("gui.editor.example"));
            if (d.adminOnly) base = addLore(base, plugin.lang().lines("gui.editor.admin-only-tag"));
            if (!d.actions.isEmpty()) base = addLore(base, plugin.lang().lines("gui.editor.actions-tag", "{actions}", String.join(", ", d.actions)));
            ItemStack tagged = tag(base, "d:" + d.index);
            for (int slot : d.slots) {
                if (slot >= inventory.getSize()) continue;
                inventory.setItem(slot, tagged.clone());
                decoAt.put(slot, d.index);
            }
        }
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize() || e.getValue().isEmpty()) continue;
            MenuItem item = e.getValue().get(0);
            rendered.add(item.key);
            for (int k = 1; k < e.getValue().size(); k++) shadowed.computeIfAbsent(e.getValue().get(k).key, x -> new HashSet<>()).add(slot);
            Integer under = decoAt.get(slot);
            if (under != null) hiddenDeco.computeIfAbsent(under, x -> new HashSet<>()).add(slot);
            if (item.element && def.builtin && item.key.equals("entries")) {
                int n = item.slots.indexOf(slot) + 1;
                ItemStack marker = ItemSpec.simple("LIGHT_BLUE_STAINED_GLASS_PANE", plugin.lang().raw("gui.editor.list-slot.name"),
                        plugin.lang().lines("gui.editor.list-slot.lore")).amount(Math.max(1, Math.min(64, n)))
                        .build(s -> Placeholders.apply(s, viewer, "{number}", String.valueOf(n)));
                inventory.setItem(slot, tag(marker, "l:" + item.key));
                continue;
            }
            if (item.element && def.builtin && def.id.equals(Builtins.CONFIRM) && item.key.equals("product")) {
                ItemStack frame = ItemSpec.simple("ITEM_FRAME", plugin.lang().raw("gui.editor.product-slot.name"),
                        plugin.lang().lines("gui.editor.product-slot.lore")).build(x -> Placeholders.apply(x, viewer));
                inventory.setItem(slot, tag(frame, "i:" + item.key));
                continue;
            }
            String[] ex = samples(slot);
            ItemStack stack = item.exact != null && item.spec.materialUnset() ? item.exact.clone()
                    : withSampleMaterial(item).spec.build(s -> Placeholders.apply(s, viewer, ex));
            if (stack == null) continue;
            List<String> extra = new ArrayList<>();
            if (hasPlaceholder(item.spec)) extra.addAll(plugin.lang().lines("gui.editor.example"));
            if (e.getValue().size() > 1) extra.addAll(plugin.lang().lines("gui.editor.more-tag", "{count}", String.valueOf(e.getValue().size() - 1)));
            if (item.product != null) {
                extra.addAll(plugin.lang().lines("gui.editor.product-tag", "{price}", Num.commas(item.product.price)));
            }
            if (item.adminOnly) extra.addAll(plugin.lang().lines("gui.editor.admin-only-tag"));
            stack = addLore(stack, extra);
            inventory.setItem(slot, tag(stack, "i:" + item.key));
        }
    }

    /** Example values for the placeholders filled by the plugin, so the editor looks like the real menu. */
    private String[] samples(int slot) {
        long bal = plugin.tokens().balance(viewer.getUniqueId());
        return new String[]{"{menu}", def.id, "{slot}", String.valueOf(slot),
                "{product}", "&bDiamond", "{price}", "500", "{price_raw}", "500", "{original_price}", "625", "{sale}", "20",
                "{sale_ends}", "1h 30m", "{limit}", "1", "{bought}", "0", "{left}", "1", "{stock}", "10", "{stock_left}", "7",
                "{missing}", "250", "{position}", "1", "{name}", viewer.getName(), "{amount}", "12500", "{amount_commas}", "12,500",
                "{amount_formatted}", "12.5k", "{target}", viewer.getName(), "{target_tokens}", Num.commas(bal), "{page}", "1",
                "{action}", "Received", "{sign}", "+", "{balance}", "1,000", "{cause}", "shop items: diamonds",
                "{date}", new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date()),
                "{server}", plugin.tokens().serverName(), "{material}", "PAPER"};
    }

    /** A history entry has no material (it depends on the action): paper in the editor. */
    private static MenuItem withSampleMaterial(MenuItem item) {
        if (!item.spec.materialUnset() && !"{material}".equals(item.spec.material())) return item;
        MenuItem c = new MenuItem();
        c.key = item.key;
        c.spec = ItemSpec.simple("PAPER", item.spec.name(), item.spec.lore());
        return c;
    }

    private static boolean hasPlaceholder(ItemSpec spec) {
        if (spec == null) return false;
        if (spec.name() != null && spec.name().indexOf('{') >= 0) return true;
        for (String l : spec.lore()) if (l.indexOf('{') >= 0) return true;
        return false;
    }

    private ItemStack addLore(ItemStack it, List<String> lines) {
        if (lines.isEmpty()) return it;
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<String>();
        for (String l : lines) lore.add(Text.item(Placeholders.apply(l, viewer)));
        m.setLore(lore);
        c.setItemMeta(m);
        return c;
    }

    static ItemStack tag(ItemStack it, String id) {
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        // 1.14+: hidden in the item data; older versions: a lore line
        if (Compat.setData(FlareTokens.get(), m, "editor", id)) {
            c.setItemMeta(m);
            return c;
        }
        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<String>();
        lore.add(ChatColor.DARK_GRAY + MARK + id);
        m.setLore(lore);
        c.setItemMeta(m);
        return c;
    }

    static String tagOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta m = it.getItemMeta();
        if (m == null) return null;
        String data = Compat.getData(FlareTokens.get(), m, "editor");
        if (data != null && (data.startsWith("i:") || data.startsWith("d:") || data.startsWith("l:"))) return data;
        if (!m.hasLore() || m.getLore() == null) return null;
        for (String line : m.getLore()) {
            String plain = ChatColor.stripColor(line);
            if (plain == null || !plain.startsWith(MARK)) continue;
            String id = plain.substring(MARK.length()).trim();
            if (id.startsWith("i:") || id.startsWith("d:") || id.startsWith("l:")) return id;
        }
        return null;
    }

    static boolean isEditorItem(ItemStack it) {
        return tagOf(it) != null;
    }

    // ------------------------------------------------------------------ clicks

    public void handleClick(InventoryClickEvent e) {
        InventoryAction action = e.getAction();
        if (action.name().startsWith("DROP")) {
            e.setCancelled(true); // menu items can't be thrown on the ground
            return;
        }
        int raw = e.getRawSlot();
        int size = inventory.getSize();
        if (raw < 0 || raw >= size || !e.isShiftClick()) return; // free drag and drop
        ItemStack current = e.getCurrentItem();
        boolean empty = current == null || current.getType().name().endsWith("AIR");
        if (e.isRightClick()) {
            e.setCancelled(true);
            if (empty) {
                new PaletteMenu(plugin, viewer, this, raw).openLater();
            } else {
                toggleAdminOnly(raw, current);
            }
            return;
        }
        if (e.isLeftClick() && !empty && !def.builtin) {
            e.setCancelled(true);
            String tag = tagOf(current);
            if (tag != null && tag.startsWith("i:")) {
                String key = tag.substring(2);
                new ProductMenu(plugin, viewer, this, key).openLater();
            } else {
                // any other item: sell it (the exact item, with enchantments and everything)
                ItemStack exact = clean(current);
                plugin.chatInput().ask(viewer, "editor.ask-price", "", text -> {
                    long price = Num.parse(text);
                    if (price < 0) {
                        plugin.lang().send(viewer, "general.invalid-amount", "{input}", text);
                    } else {
                        sell(raw, exact, price);
                    }
                    openLater();
                }, this::openLater);
            }
        }
    }

    private void toggleAdminOnly(int slot, ItemStack current) {
        save();
        String tag = tagOf(current);
        Boolean now = null;
        if (tag != null && tag.startsWith("i:")) {
            ConfigurationSection s = section(tag.substring(2));
            if (s != null) {
                now = !s.getBoolean("admin-only", false);
                s.set("admin-only", now);
            }
        } else {
            List<Map<String, Object>> decos = decorations();
            for (Map<String, Object> d : decos) {
                if (decoSlots(d).contains(slot)) {
                    now = !Boolean.TRUE.equals(d.get("admin-only"));
                    d.put("admin-only", now);
                    break;
                }
            }
            def.yaml.set("decorations", decos);
        }
        def = plugin.menus().save(def);
        if (now != null) plugin.lang().send(viewer, now ? "editor.admin-only-on" : "editor.admin-only-off");
        build();
    }

    public void handleClose(InventoryCloseEvent e) {
        save();
        // menu copies must not stay in the admin's inventory
        Inventory inv = viewer.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isEditorItem(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isEditorItem(viewer.getItemOnCursor())) viewer.setItemOnCursor(null);
        plugin.lang().send(viewer, "editor.saved", "{menu}", def.id);
    }

    // ------------------------------------------------------------------ saving

    /** Section of an item / element in the file. */
    ConfigurationSection section(String key) {
        ConfigurationSection s = def.yaml.getConfigurationSection("items." + key);
        if (s == null) s = def.yaml.getConfigurationSection("elements." + key);
        return s;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> decorations() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<?> list = def.yaml.getList("decorations");
        if (list != null) {
            for (Object o : list) if (o instanceof Map<?, ?>) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        }
        return out;
    }

    /** Slots of a decoration of the file ("slots" and the singular "slot"). */
    static List<Integer> decoSlots(Map<String, Object> d) {
        List<Integer> out = new ArrayList<>(MenuDef.parseSlots(d.get("slots"), 54));
        for (int x : MenuDef.parseSlots(d.get("slot"), 54)) if (!out.contains(x)) out.add(x);
        return out;
    }

    /** Reads the inventory and writes the new layout in the file. Items put by the admin are given back. */
    void save() {
        if (inventory == null) return;
        int size = inventory.getSize();
        Map<String, List<Integer>> itemSlots = new LinkedHashMap<>();
        java.util.Set<Integer> itemTagged = new HashSet<>();
        List<Map<String, Object>> oldDecos = decorations();
        Map<Integer, Map<String, Object>> moved = new LinkedHashMap<>();
        Map<String, Map<String, Object>> added = new LinkedHashMap<>();
        List<ItemStack> giveBack = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            ItemStack it = inventory.getItem(slot);
            if (it == null || it.getType().name().endsWith("AIR")) continue;
            String tag = tagOf(it);
            if (tag != null && (tag.startsWith("i:") || tag.startsWith("l:"))) {
                itemSlots.computeIfAbsent(tag.substring(2), k -> new ArrayList<>()).add(slot);
                itemTagged.add(slot);
                continue;
            }
            if (tag != null && tag.startsWith("d:")) {
                int idx;
                try {
                    idx = Integer.parseInt(tag.substring(2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (idx < 0 || idx >= oldDecos.size()) continue;
                slotsOf(moved.computeIfAbsent(idx, k -> fresh(oldDecos.get(k)))).add(slot);
                continue;
            }
            // new item put by the admin: a decoration written in a readable form, the item goes back to him
            giveBack.add(clean(it));
            Map<String, Object> d = describe(it);
            String sig = d.toString();
            Map<String, Object> target = added.get(sig);
            if (target == null) {
                target = d;
                target.put("slots", new ArrayList<Integer>());
                added.put(sig, target);
            }
            slotsOf(target).add(slot);
        }
        // items: new slots for the ones in the editor; slots hidden under another item stay while an item is there
        // items never shown in the editor (always hidden by priority) are left as they are
        for (String key : rendered) {
            ConfigurationSection s = section(key);
            if (s == null) continue;
            List<Integer> now = itemSlots.containsKey(key) ? new ArrayList<>(itemSlots.get(key)) : new ArrayList<Integer>();
            for (int h : shadowed.getOrDefault(key, java.util.Collections.<Integer>emptySet())) {
                if (itemTagged.contains(h) && !now.contains(h)) now.add(h);
            }
            // slots outside the menu (rows were made smaller) stay as they are
            for (String k : new String[]{"slots", "slot"}) {
                for (int old : MenuDef.parseSlots(s.get(k), 54)) if (old >= size && !now.contains(old)) now.add(old);
            }
            s.set("slot", null);
            s.set("slots", MenuDef.formatSlots(now));
        }
        // decorations: moved ones keep their definition and actions, then the new ones
        List<Map<String, Object>> decos = new ArrayList<>();
        for (int i = 0; i < oldDecos.size(); i++) {
            Map<String, Object> old = oldDecos.get(i);
            List<Integer> keep = new ArrayList<>();
            for (int x : decoSlots(old)) if (x >= size) keep.add(x);
            for (int h : hiddenDeco.getOrDefault(i, java.util.Collections.<Integer>emptySet())) if (itemTagged.contains(h)) keep.add(h);
            Map<String, Object> m = moved.get(i);
            if (m == null && keep.isEmpty()) continue;
            if (m == null) m = fresh(old);
            for (int x : keep) if (!slotsOf(m).contains(x)) slotsOf(m).add(x);
            decos.add(m);
        }
        decos.addAll(added.values());
        for (Map<String, Object> d : decos) d.put("slots", MenuDef.formatSlots(slotsOf(d)));
        def.yaml.set("decorations", decos);
        def = plugin.menus().save(def);
        for (ItemStack it : giveBack) {
            for (ItemStack left : viewer.getInventory().addItem(it).values()) viewer.getWorld().dropItemNaturally(viewer.getLocation(), left);
        }
    }

    /** A copy of a decoration of the file without slots. */
    private static Map<String, Object> fresh(Map<String, Object> old) {
        Map<String, Object> c = new LinkedHashMap<>(old);
        c.remove("slot");
        c.put("slots", new ArrayList<Integer>());
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> slotsOf(Map<String, Object> m) {
        Object o = m.get("slots");
        if (o instanceof List<?> && (((List<?>) o).isEmpty() || ((List<?>) o).get(0) instanceof Integer)) return (List<Integer>) o;
        List<Integer> l = MenuDef.parseSlots(o, 54);
        m.put("slots", l);
        return l;
    }

    /** An item without the editor lines. */
    static ItemStack clean(ItemStack it) {
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        if (m == null) return c;
        if (Compat.getData(FlareTokens.get(), m, "editor") != null) {
            Compat.setData(FlareTokens.get(), m, "editor", null);
            c.setItemMeta(m);
            m = c.getItemMeta();
        }
        if (m == null || !m.hasLore() || m.getLore() == null) return c;
        List<String> lore = new ArrayList<>();
        for (String l : m.getLore()) {
            String p = ChatColor.stripColor(l);
            if (p != null && p.startsWith(MARK)) continue;
            lore.add(l);
        }
        m.setLore(lore.isEmpty() ? null : lore);
        c.setItemMeta(m);
        return c;
    }

    /** Any item as material / name / lore / glow (heads keep their skin), ready to be edited in the file. */
    static Map<String, Object> describe(ItemStack it) {
        Map<String, Object> d = new LinkedHashMap<>();
        ItemStack c = clean(it);
        ItemMeta m = c.getItemMeta();
        String material = c.getType().name();
        if (m instanceof SkullMeta) {
            String head = Compat.describeHead((SkullMeta) m);
            if (head != null) material = head;
        }
        @SuppressWarnings("deprecation")
        short data = c.getDurability();
        if (!dev.tmdevelopment.flaretokens.util.Ver.flat() && data > 0 && !material.contains(":")) material = material + ":" + data;
        d.put("material", material);
        d.put("name", m != null && m.hasDisplayName() ? Text.toEditable(m.getDisplayName()) : " ");
        List<String> lore = new ArrayList<>();
        if (m != null && m.hasLore() && m.getLore() != null) for (String l : m.getLore()) lore.add(Text.toEditable(l));
        d.put("lore", lore);
        d.put("glow", m != null && Compat.hasGlow(m));
        if (c.getAmount() > 1) d.put("amount", c.getAmount());
        int cmd = m == null ? 0 : Compat.modelData(m);
        if (cmd > 0) d.put("custom-model-data", cmd);
        d.put("admin-only", false);
        d.put("actions", new ArrayList<String>());
        return d;
    }

    /** Frees a slot from every item and decoration. */
    void clearSlot(int slot) {
        for (String key : def.items.keySet()) {
            ConfigurationSection s = section(key);
            if (s == null) continue;
            List<Integer> now = new ArrayList<>(MenuDef.parseSlots(s.get("slots"), 54));
            for (int x : MenuDef.parseSlots(s.get("slot"), 54)) if (!now.contains(x)) now.add(x);
            if (now.remove(Integer.valueOf(slot))) {
                s.set("slot", null);
                s.set("slots", MenuDef.formatSlots(now));
            }
        }
        List<Map<String, Object>> decos = decorations();
        List<Map<String, Object>> keep = new ArrayList<>();
        for (Map<String, Object> d : decos) {
            List<Integer> s = decoSlots(d);
            s.remove(Integer.valueOf(slot));
            if (s.isEmpty()) continue;
            d.remove("slot");
            d.put("slots", MenuDef.formatSlots(s));
            keep.add(d);
        }
        def.yaml.set("decorations", keep);
    }

    /** Puts a decoration (glass, a button with actions ...) on a slot. */
    void addDecoration(int slot, Map<String, Object> d) {
        clearSlot(slot);
        d.put("slots", String.valueOf(slot));
        List<Map<String, Object>> decos = decorations();
        decos.add(d);
        def.yaml.set("decorations", decos);
        def = plugin.menus().save(def);
    }

    /** The exact item becomes a product on that slot. */
    void sell(int slot, ItemStack exact, long price) {
        clearSlot(slot);
        Map<String, Object> look = describe(exact);
        String key = freeKey(Text.plain(String.valueOf(look.get("name"))).trim().isEmpty()
                ? exact.getType().name().toLowerCase(java.util.Locale.ROOT) : Text.plain(String.valueOf(look.get("name"))));
        ConfigurationSection s = def.yaml.createSection("items." + key);
        s.set("slots", String.valueOf(slot));
        for (String k : new String[]{"material", "name", "lore", "glow", "amount", "custom-model-data"}) {
            if (look.containsKey(k)) s.set(k, look.get(k));
        }
        s.set("price", price);
        List<Object> give = new ArrayList<>();
        give.add(exact);
        s.set("give", give);
        s.set("confirm", false);
        s.set("limit", 0);
        s.set("stock", 0);
        def = plugin.menus().save(def);
        plugin.lang().send(viewer, "editor.product-created", "{price}", Num.commas(price));
    }

    String freeKey(String wanted) {
        String base = wanted.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "item";
        if (base.length() > 30) base = base.substring(0, 30);
        String key = base;
        int n = 2;
        while (def.yaml.isSet("items." + key) || def.yaml.isSet("elements." + key)) key = base + "-" + n++;
        return key;
    }

    void reloadDef() {
        MenuDef fresh = plugin.menus().def(def.id);
        if (fresh != null) def = fresh;
    }
}

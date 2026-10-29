package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.data.Storage;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A menu open for one player: decorations first, then the items on top. */
public final class MenuSession implements InventoryHolder {

    /** What a click on a slot does. */
    interface Click {
        void run(MenuSession s, String clickType);
    }

    final MenuDef def;
    final Player player;
    private final Inventory inventory;
    MenuSession parent;
    private final Map<Integer, Click> clicks = new HashMap<>();
    private final Map<Integer, MenuItem> shown = new HashMap<>();
    private long lastClick;
    private long renderedBalance = Long.MIN_VALUE;
    private int ticks;

    /** Purchase counters of the products of this menu (limit per player / stock), read when the menu opens. */
    final Map<String, Integer> bought = new HashMap<>();
    final Map<String, Integer> sold = new HashMap<>();

    // context of the menus of the plugin
    MenuItem pendingItem;      // confirm: what is being bought
    MenuSession pendingFrom;   // confirm: the shop it comes from
    long pendingPrice;         // confirm: the price shown
    UUID target;               // history: whose history
    String targetName;
    int page;
    List<Storage.Tx> entries = Collections.emptyList();
    boolean loading;

    MenuSession(MenuDef def, Player player, MenuSession parent) {
        this.def = def;
        this.player = player;
        this.parent = parent;
        String title = Compat.menuTitle(Text.legacy(Placeholders.apply(def.title, player, "{menu}", def.id)));
        this.inventory = def.type != null
                ? Bukkit.createInventory(this, def.type, title)
                : Bukkit.createInventory(this, def.size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public MenuDef def() {
        return def;
    }

    public Player player() {
        return player;
    }

    FlareTokens plugin() {
        return FlareTokens.get();
    }

    boolean admin() {
        return player.hasPermission("flaretokens.admin");
    }

    Actions.Context context() {
        return new Actions.Context("shop " + def.id, this, "{menu}", def.id);
    }

    /** true while the player still has this menu open. */
    boolean isOpen() {
        return player.isOnline() && plugin().menus().isOpen(player, this);
    }

    // ------------------------------------------------------------------ drawing

    /** Draws every slot. */
    public void render() {
        renderedBalance = plugin().tokens().balance(player.getUniqueId());
        clicks.clear();
        shown.clear();
        inventory.clear();
        for (MenuDef.Deco d : def.decorations) {
            if (d.adminOnly && !admin()) continue;
            ItemStack stack = d.item != null ? d.item.clone() : d.spec.build(s -> Placeholders.apply(s, player, "{menu}", def.id));
            if (stack == null) continue;
            List<String> actions = d.actions;
            for (int slot : d.slots) {
                if (slot >= inventory.getSize()) continue;
                inventory.setItem(slot, stack.clone());
                if (!actions.isEmpty()) clicks.put(slot, (s, c) -> Actions.run(player, actions, context()));
            }
        }
        Set<MenuItem> lists = new HashSet<>();
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            MenuItem item = pick(e.getValue());
            if (item == null) continue;
            if (Builtins.isList(this, item)) {
                if (lists.add(item)) Builtins.drawList(this, item);
                continue;
            }
            draw(slot, item);
        }
    }

    /**
     * Called every second: everything again when the balance changed (prices, "you need X more") or, every 5 seconds,
     * when show-if / hide-if / permissions now pick another item; otherwise only the items with update: true.
     */
    void tick() {
        ticks++;
        if (plugin().tokens().balance(player.getUniqueId()) != renderedBalance || ticks % 5 == 0 && pickChanged()) {
            render();
            return;
        }
        if (!def.hasUpdating) return;
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            List<MenuItem> list = e.getValue();
            boolean any = false;
            for (MenuItem i : list) if (i.update || list.size() > 1) any = true;
            if (!any) continue;
            MenuItem item = pick(list);
            MenuItem before = shown.get(slot);
            if (item == before && (item == null || !item.update)) continue;
            if (item == null) {
                // nothing visible any more: the decoration under it (if any) comes back on the next full render
                render();
                return;
            }
            if (Builtins.isList(this, item)) continue;
            draw(slot, item);
            if (!item.onUpdate.isEmpty()) Actions.run(player, item.onUpdate, context());
        }
    }

    /** [refresh] action: everything again on the next tick. */
    public void refresh() {
        Bukkit.getScheduler().runTask(FlareTokens.get(), () -> {
            if (player.isOnline() && plugin().menus().isOpen(player, this)) render();
        });
    }

    private boolean pickChanged() {
        for (Map.Entry<Integer, List<MenuItem>> e : def.slots.entrySet()) {
            int slot = e.getKey();
            if (slot >= inventory.getSize()) continue;
            MenuItem item = pick(e.getValue());
            if (item != null && Builtins.isList(this, item)) continue;
            if (item != shown.get(slot)) return true;
        }
        return false;
    }

    private MenuItem pick(List<MenuItem> list) {
        Actions.Context ctx = context();
        for (MenuItem i : list) {
            if (i.adminOnly && !admin()) continue;
            if (!Actions.check(player, i.showIf, ctx)) continue;
            if (!i.hideIf.isEmpty() && Actions.check(player, i.hideIf, ctx)) continue;
            return i;
        }
        return null;
    }

    void set(int slot, ItemStack stack, Click click) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, stack);
        if (click != null) clicks.put(slot, click);
        else clicks.remove(slot);
    }

    private void draw(int slot, MenuItem item) {
        shown.put(slot, item);
        if (Builtins.drawSpecial(this, item, slot)) return;
        set(slot, build(item, slot, null), (s, c) -> s.clickItem(item, c));
    }

    /** The item as the player sees it, with the product lines when it is for sale. */
    ItemStack build(MenuItem item, int slot, String[] extra) {
        return build(item, slot, extra, player);
    }

    /** Same, with the placeholders of another player (the top list: each entry is its own player). */
    ItemStack build(MenuItem item, int slot, String[] extra, org.bukkit.OfflinePlayer who) {
        String[] pairs = pairs(item, slot, extra);
        ItemStack stack;
        if (item.exact != null && item.spec.materialUnset()) {
            stack = item.exact.clone();
        } else {
            stack = item.spec.build(s -> Placeholders.apply(s, who, pairs));
        }
        if (item.product != null && stack != null) {
            List<String> add = productLines(item);
            if (!add.isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
                    for (String line : add) {
                        String r = Placeholders.apply(line, player, pairs);
                        for (String part : r.split("\n", -1)) lore.add(Text.item(part));
                    }
                    meta.setLore(lore);
                    stack.setItemMeta(meta);
                }
            }
        }
        return stack;
    }

    /** {price} {original_price} {sale} {limit} {bought} {left} {stock} {stock_left} {missing} {name} {menu} {slot}. */
    String[] pairs(MenuItem item, int slot, String[] extra) {
        List<String> p = new ArrayList<>();
        p.add("{menu}");
        p.add(def.id);
        p.add("{slot}");
        p.add(String.valueOf(slot));
        if (item.product != null) {
            MenuItem.Product pr = item.product;
            long price = plugin().sales().price(def.id, pr);
            long has = plugin().tokens().balance(player.getUniqueId());
            int got = bought.getOrDefault(item.key, 0);
            int out = sold.getOrDefault(item.key, 0);
            int sale = pr.sale ? plugin().sales().percent(def.id) : 0;
            String[] kv = {
                    "{price}", Num.commas(price), "{price_raw}", String.valueOf(price),
                    "{original_price}", Num.commas(pr.price), "{sale}", String.valueOf(sale),
                    "{limit}", String.valueOf(pr.limit), "{bought}", String.valueOf(got),
                    "{left}", String.valueOf(Math.max(0, pr.limit - got)),
                    "{stock}", String.valueOf(pr.stock), "{stock_left}", String.valueOf(Math.max(0, pr.stock - out)),
                    "{missing}", Num.commas(Math.max(0, price - has)),
                    "{sale_ends}", plugin().sales().timeLeft()};
            for (String s : kv) p.add(s);
        }
        if (pendingItem != null && pendingFrom != null && item != pendingItem) {
            // confirm menu: the texts can show the product and its price
            for (String s : pendingFrom.pairs(pendingItem, slot, null)) p.add(s);
            p.add("{product}");
            p.add(Purchase.name(pendingFrom, pendingItem));
        }
        if (target != null) {
            p.add("{target}");
            p.add(targetName == null ? "?" : targetName);
            p.add("{target_tokens}");
            p.add(Num.commas(plugin().tokens().balanceForDisplay(target)));
            p.add("{page}");
            p.add(String.valueOf(page + 1));
        }
        if (extra != null) for (String s : extra) p.add(s);
        return p.toArray(new String[0]);
    }

    /** Lines added under the lore of a product: price, sale, limit, stock and what happens on click. */
    private List<String> productLines(MenuItem item) {
        MenuItem.Product pr = item.product;
        List<String> out = new ArrayList<>(def.lines("product.info"));
        if (pr.sale && plugin().sales().percent(def.id) > 0) out.addAll(def.lines("product.sale"));
        if (pr.limit > 0) out.addAll(def.lines("product.limit"));
        if (pr.stock > 0) out.addAll(def.lines("product.stock"));
        String state;
        long price = plugin().sales().price(def.id, pr);
        if (pr.permission != null && !player.hasPermission(pr.permission)) state = "product.no-permission";
        else if (pr.stock > 0 && sold.getOrDefault(item.key, 0) >= pr.stock) state = "product.sold-out";
        else if (pr.limit > 0 && bought.getOrDefault(item.key, 0) >= pr.limit) state = "product.limit-reached";
        else if (plugin().tokens().balance(player.getUniqueId()) < price) state = "product.not-enough";
        else state = "product.can-buy";
        out.addAll(def.lines(state));
        return out;
    }

    // ------------------------------------------------------------------ clicks

    void click(int slot, String clickType) {
        Click c = clicks.get(slot);
        if (c == null) return;
        if (!isBuyClick(clickType)) {
            // Q, Ctrl+Q, number keys, F, middle and double click: only for items that ask for them ([DROP] ...)
            MenuItem it = shown.get(slot);
            if (it == null || it.product != null || !it.clickBy.containsKey(clickType)) return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin().getConfig().getLong("shop.click-cooldown-ms", 200);
        if (now - lastClick < cooldown) return;
        lastClick = now;
        c.run(this, clickType);
    }

    void clickItem(MenuItem item, String clickType) {
        if (item.product != null) {
            // the actions of a product only run after a payment (Purchase.deliver)
            if (isBuyClick(clickType)) Purchase.start(this, item);
            return;
        }
        if (Builtins.click(this, item, clickType)) return;
        List<String> lines = actionsFor(item, clickType);
        if (lines.isEmpty()) return;
        Actions.run(player, lines, context());
        // balances shown in the menu change right after a purchase
        if (player.isOnline() && plugin().menus().isOpen(player, this)) render();
    }

    static List<String> actionsFor(MenuItem item, String clickType) {
        List<String> lines = new ArrayList<>();
        List<String> specific = item.clickBy.get(clickType);
        if (specific != null) lines.addAll(specific);
        lines.addAll(item.clickAny);
        return lines;
    }

    static boolean isBuyClick(String c) {
        return c.equals("LEFT") || c.equals("RIGHT") || c.equals("SHIFT_LEFT") || c.equals("SHIFT_RIGHT");
    }

    /** [back]: the menu this one was opened from, or close. */
    public void back() {
        MenuSession p = parent;
        Bukkit.getScheduler().runTask(plugin(), () -> {
            if (!player.isOnline()) return;
            if (p == null) {
                player.closeInventory();
                return;
            }
            plugin().menus().reopen(player, p);
        });
    }

    /** Reads the purchase counters of the products with a limit or a stock, then draws again. */
    void loadCounters() {
        if (!def.hasProducts) return;
        for (MenuItem it : def.items.values()) {
            MenuItem.Product pr = it.product;
            if (pr == null) continue;
            String key = Purchase.key(def, it);
            if (pr.limit > 0) {
                plugin().tokens().bought(player.getUniqueId().toString(), key, Purchase.period(pr.limitReset)).thenAccept(n -> {
                    bought.put(it.key, n);
                    refresh();
                });
            }
            if (pr.stock > 0) {
                plugin().tokens().bought("*", key, Purchase.period(pr.stockReset)).thenAccept(n -> {
                    sold.put(it.key, n);
                    refresh();
                });
            }
        }
    }

    /** For ItemSpec: material written in the file? (exact items saved by the editor have none). */
    static boolean unset(ItemSpec spec) {
        return spec == null || spec.materialUnset();
    }
}

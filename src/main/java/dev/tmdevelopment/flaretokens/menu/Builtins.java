package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.data.Storage;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the elements of the plugin menus do (menus/tokens.yml, top.yml, history.yml, confirm.yml).
 * An element with its own "actions" in the file runs those instead.
 */
final class Builtins {

    static final String MAIN = "tokens";
    static final String TOP = "top";
    static final String HISTORY = "history";
    static final String CONFIRM = "confirm";

    private Builtins() {
    }

    /** Elements that fill many slots: the top list and the history entries. */
    static boolean isList(MenuSession s, MenuItem item) {
        if (!s.def.builtin || !item.element) return false;
        return item.key.equals("entries") && (s.def.id.equals(TOP) || s.def.id.equals(HISTORY));
    }

    static void drawList(MenuSession s, MenuItem item) {
        List<Integer> slots = item.slots;
        if (slots.isEmpty()) return;
        if (s.def.id.equals(TOP)) {
            List<Storage.Account> top = FlareTokens.get().tokens().top();
            int n = Math.min(slots.size(), top.size());
            for (int i = 0; i < n; i++) {
                Storage.Account a = top.get(i);
                String name = a.name == null || a.name.isEmpty() ? "?" : a.name;
                String[] extra = {"{position}", String.valueOf(i + 1), "{name}", name, "{amount}", String.valueOf(a.tokens),
                        "{amount_commas}", Num.commas(a.tokens), "{amount_formatted}", Num.shortFormat(a.tokens)};
                s.set(slots.get(i), s.build(item, slots.get(i), extra), null);
            }
            if (top.isEmpty()) empty(s, item);
            return;
        }
        // history
        if (s.loading) return;
        List<Storage.Tx> list = s.entries;
        SimpleDateFormat date = new SimpleDateFormat(s.def.text("date-format", "dd/MM/yyyy HH:mm"), Locale.ROOT);
        int n = Math.min(slots.size(), list.size());
        for (int i = 0; i < n; i++) {
            Storage.Tx t = list.get(i);
            String kind = t.action == null ? "" : t.action.toLowerCase(Locale.ROOT);
            String sign = kind.equals("take") ? "-" : kind.equals("set") ? "=" : "+";
            String[] extra = {"{action}", s.def.text("actions." + kind, kind), "{sign}", sign,
                    "{amount}", Num.commas(t.amount), "{balance}", Num.commas(t.balance),
                    "{cause}", t.cause == null || t.cause.isEmpty() ? "-" : t.cause,
                    "{date}", date.format(new Date(t.time)), "{server}", t.server == null || t.server.isEmpty() ? "-" : t.server,
                    "{material}", s.def.text("materials." + kind, "PAPER")};
            ItemStack stack = s.build(withMaterial(item, s.def.text("materials." + kind, null)), slots.get(i), extra);
            s.set(slots.get(i), stack, null);
        }
        if (list.isEmpty()) empty(s, item);
    }

    private static MenuItem withMaterial(MenuItem item, String material) {
        if (material == null || !item.spec.materialUnset() && !"{material}".equals(item.spec.material())) return item;
        MenuItem copy = new MenuItem();
        copy.key = item.key;
        copy.element = true;
        copy.spec = ItemSpec.simple(material, item.spec.name(), item.spec.lore());
        return copy;
    }

    /** Nothing to show: the "empty" text in the middle of the list area. */
    private static void empty(MenuSession s, MenuItem item) {
        String name = s.def.text("empty.name", null);
        if (name == null) return;
        ItemSpec spec = ItemSpec.simple(s.def.text("empty.material", "BARRIER"), name, s.def.lines("empty.lore"));
        s.set(center(item.slots), spec.build(x -> Placeholders.apply(x, s.player)), null);
    }

    static int center(List<Integer> slots) {
        double r = 0, c = 0;
        for (int x : slots) {
            r += x / 9;
            c += x % 9;
        }
        r /= slots.size();
        c /= slots.size();
        int best = slots.get(0);
        double bestDist = Double.MAX_VALUE;
        for (int x : slots) {
            double d = Math.pow(x / 9 - r, 2) + Math.pow(x % 9 - c, 2);
            if (d < bestDist - 1e-9) {
                bestDist = d;
                best = x;
            }
        }
        return best;
    }

    /** Default click of the elements. true = handled. */
    static boolean click(MenuSession s, MenuItem item, String clickType) {
        if (!item.element || !s.def.builtin) return false;
        if (!item.clickAny.isEmpty() || !item.clickBy.isEmpty()) return false; // the file says what to do
        FlareTokens plugin = FlareTokens.get();
        MenuManager menus = plugin.menus();
        switch (item.key) {
            case "close":
                dev.tmdevelopment.flaretokens.action.Actions.closeLater(s.player);
                return true;
            case "back":
                s.back();
                return true;
            case "shop":
                menus.openLater(s.player, menus.mainShop(), s);
                return true;
            case "top":
                menus.openLater(s.player, TOP, s);
                return true;
            case "history":
                menus.openHistory(s.player, s.player.getUniqueId(), s.player.getName(), s);
                return true;
            case "previous":
                if (!s.loading && s.page > 0) {
                    s.page--;
                    menus.loadHistory(s);
                }
                return true;
            case "next":
                if (!s.loading && s.entries.size() > pageSize(s)) {
                    s.page++;
                    menus.loadHistory(s);
                }
                return true;
            case "confirm":
                if (s.pendingItem != null && s.pendingFrom != null) {
                    MenuSession shop = s.pendingFrom;
                    MenuItem product = s.pendingItem;
                    s.pendingItem = null;
                    // never open another inventory inside a click event: next tick
                    long agreed = s.pendingPrice;
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!s.player.isOnline()) return;
                        MenuSession now = menus.reopen(s.player, shop);
                        MenuItem same = now.def.items.get(product.key);
                        if (same == null || same.product == null) return; // removed meanwhile
                        Purchase.execute(now, same, agreed);
                    });
                }
                return true;
            case "cancel":
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!s.player.isOnline()) return;
                    if (s.pendingFrom != null) menus.reopen(s.player, s.pendingFrom);
                    else s.player.closeInventory();
                });
                return true;
            default:
                return false;
        }
    }

    /** Draws the product being confirmed (element "product" of confirm.yml). */
    static boolean drawSpecial(MenuSession s, MenuItem item, int slot) {
        if (!s.def.builtin || !s.def.id.equals(CONFIRM) || !item.key.equals("product") || s.pendingItem == null) return false;
        MenuSession shop = s.pendingFrom;
        ItemStack stack = shop.build(s.pendingItem, slot, null);
        s.set(slot, stack, null);
        return true;
    }

    static int pageSize(MenuSession s) {
        MenuItem e = s.def.items.get("entries");
        return e == null ? 0 : e.slots.size();
    }
}

package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buying a product: permission, confirmation, limit per player and stock (atomic, also across servers),
 * payment, then the items, the commands and the actions of the product.
 */
final class Purchase {

    /** Players with a purchase in progress (no double purchases while the database answers). */
    private static final Set<UUID> busy = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Purchase() {
    }

    static String key(MenuDef def, MenuItem item) {
        String k = def.id.toLowerCase(java.util.Locale.ROOT) + "." + item.key;
        // the database column holds 100 characters
        return k.length() <= 100 ? k : k.substring(0, 80) + "#" + Integer.toHexString(k.hashCode());
    }

    /** Period of a counter: never (all), daily (2026-10-25), weekly (2026-W43), monthly (2026-10). */
    static String period(String reset) {
        LocalDate d = LocalDate.now();
        switch (reset == null ? "never" : reset) {
            case "daily":
            case "day":
                return d.toString();
            case "weekly":
            case "week":
                return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "monthly":
            case "month":
                return d.getYear() + "-" + d.getMonthValue();
            default:
                return "all";
        }
    }

    /** Click on a product. */
    static void start(MenuSession s, MenuItem item) {
        FlareTokens plugin = FlareTokens.get();
        Player p = s.player;
        MenuItem.Product pr = item.product;
        if (pr.permission != null && !p.hasPermission(pr.permission)) {
            fail(s, "shop.no-permission-item", item);
            return;
        }
        long price = plugin.sales().price(s.def.id, pr);
        if (pr.limit > 0 && s.bought.getOrDefault(item.key, 0) >= pr.limit) {
            fail(s, "shop.limit-reached", item);
            return;
        }
        if (pr.stock > 0 && s.sold.getOrDefault(item.key, 0) >= pr.stock) {
            fail(s, "shop.sold-out", item);
            return;
        }
        if (plugin.tokens().balance(p.getUniqueId()) < price) {
            long has = plugin.tokens().balance(p.getUniqueId());
            plugin.lang().send(p, "shop.not-enough", "{price}", Num.commas(price), "{amount}", Num.commas(price),
                    "{missing}", Num.commas(Math.max(0, price - has)), "{product}", name(s, item));
            Actions.sound(p, "sounds.error");
            return;
        }
        long confirmAbove = plugin.getConfig().getLong("shop.confirm-above", 0);
        boolean confirm = pr.confirm != null ? pr.confirm : (confirmAbove > 0 && price >= confirmAbove);
        if (confirm && plugin.menus().openConfirm(s, item, price)) return;
        // not inside the click event: the inventory and the items are changed on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) execute(s, item, -1);
        });
    }

    /**
     * Buys it now (after the confirmation, if any). The shop menu is drawn again at the end.
     * @param agreed price shown in the confirm menu (paid even if a sale ended meanwhile), -1 = the price now
     */
    static void execute(MenuSession shop, MenuItem item, long agreed) {
        FlareTokens plugin = FlareTokens.get();
        Player p = shop.player;
        MenuItem.Product pr = item.product;
        if (pr.permission != null && !p.hasPermission(pr.permission)) {
            fail(shop, "shop.no-permission-item", item);
            return;
        }
        if (!busy.add(p.getUniqueId())) return;
        long now = plugin.sales().price(shop.def.id, pr);
        if (agreed >= 0 && now > agreed) {
            // the sale ended or the price went up while the confirm menu was open
            busy.remove(p.getUniqueId());
            fail(shop, "shop.price-changed", item);
            return;
        }
        long price = now;
        String key = key(shop.def, item);
        String who = p.getUniqueId().toString();
        String limitPeriod = period(pr.limitReset);
        String stockPeriod = period(pr.stockReset);

        CompletableFuture<Boolean> limitOk = pr.limit > 0
                ? plugin.tokens().reserve(who, key, limitPeriod, pr.limit) : CompletableFuture.completedFuture(true);
        limitOk.whenComplete((okLimit, e1) -> {
            if (e1 != null || !Boolean.TRUE.equals(okLimit)) {
                busy.remove(p.getUniqueId());
                if (e1 == null) {
                    shop.bought.put(item.key, pr.limit);
                    fail(shop, "shop.limit-reached", item);
                } else {
                    fail(shop, "shop.error", item);
                }
                return;
            }
            CompletableFuture<Boolean> stockOk = pr.stock > 0
                    ? plugin.tokens().reserve("*", key, stockPeriod, pr.stock) : CompletableFuture.completedFuture(true);
            stockOk.whenComplete((okStock, e2) -> {
                if (e2 != null || !Boolean.TRUE.equals(okStock)) {
                    busy.remove(p.getUniqueId());
                    if (pr.limit > 0) plugin.tokens().release(who, key, limitPeriod);
                    if (e2 == null) {
                        shop.sold.put(item.key, pr.stock);
                        fail(shop, "shop.sold-out", item);
                    } else {
                        fail(shop, "shop.error", item);
                    }
                    return;
                }
                try {
                    if (!p.isOnline() || !plugin.tokens().spend(p, price, "shop " + shop.def.id + ": " + item.key)) {
                        if (pr.limit > 0) plugin.tokens().release(who, key, limitPeriod);
                        if (pr.stock > 0) plugin.tokens().release("*", key, stockPeriod);
                        if (p.isOnline()) {
                            long has = plugin.tokens().balance(p.getUniqueId());
                            plugin.lang().send(p, "shop.not-enough", "{price}", Num.commas(price), "{amount}", Num.commas(price),
                                    "{missing}", Num.commas(Math.max(0, price - has)), "{product}", name(shop, item));
                            Actions.sound(p, "sounds.error");
                        }
                        return;
                    }
                    if (pr.limit > 0) shop.bought.merge(item.key, 1, Integer::sum);
                    if (pr.stock > 0) shop.sold.merge(item.key, 1, Integer::sum);
                    deliver(shop, item, price);
                } finally {
                    busy.remove(p.getUniqueId());
                    if (p.isOnline() && plugin.menus().isOpen(p, shop)) shop.render();
                }
            });
        });
    }

    private static void deliver(MenuSession s, MenuItem item, long price) {
        FlareTokens plugin = FlareTokens.get();
        Player p = s.player;
        MenuItem.Product pr = item.product;
        String[] pairs = s.pairs(item, -1, new String[]{"{uuid}", p.getUniqueId().toString()});
        for (Object o : pr.give) {
            ItemStack stack = o instanceof ItemStack ? ((ItemStack) o).clone()
                    : ((ItemSpec) o).buildReal(x -> Placeholders.apply(x, p, pairs));
            if (stack == null) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
            for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
        }
        for (String cmd : pr.commands) {
            String c = Placeholders.apply(cmd, p, pairs).trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }
        List<String> actions = MenuSession.actionsFor(item, "LEFT");
        if (!actions.isEmpty()) Actions.run(p, actions, new Actions.Context("shop " + s.def.id, s, pairs));
        plugin.lang().send(p, "shop.bought", "{product}", name(s, item), "{price}", Num.commas(price));
        Actions.sound(p, "sounds.buy");
    }

    private static void fail(MenuSession s, String message, MenuItem item) {
        FlareTokens plugin = FlareTokens.get();
        plugin.lang().send(s.player, message, "{product}", name(s, item));
        Actions.sound(s.player, "sounds.error");
        if (s.player.isOnline() && plugin.menus().isOpen(s.player, s)) s.render();
    }

    /** Name of the product for chat messages (colors kept). */
    static String name(MenuSession s, MenuItem item) {
        String n = item.spec.name();
        if (n == null || Text.plain(n).trim().isEmpty()) return item.key;
        return Placeholders.apply(n, s.player, "{menu}", s.def.id);
    }
}

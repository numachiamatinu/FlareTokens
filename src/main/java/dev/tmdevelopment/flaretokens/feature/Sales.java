package dev.tmdevelopment.flaretokens.feature;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.config.Lang;
import dev.tmdevelopment.flaretokens.menu.MenuItem;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.UUID;

/**
 * Timed sales: "-20% on every shop for 2 hours" (/tokens sale). Saved with the balances, so with MySQL
 * every server of the network has the same sale and announces it.
 */
public final class Sales {

    private final FlareTokens plugin;
    private volatile int percent;
    private volatile long until;
    private volatile String scope = "*";
    private volatile String id;
    private BukkitTask task;
    private int polls;

    public Sales(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) task.cancel();
        read(false);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (id != null && System.currentTimeMillis() >= until) {
                expired();
                return;
            }
            // other servers of the network can start / stop a sale
            if (plugin.tokens().storage().shared() && ++polls % 15 == 0) read(true);
        }, 40L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void read(boolean announce) {
        plugin.tokens().meta("sale").thenAccept(v -> {
            String oldId = id;
            parse(v);
            if (!announce) return;
            if (id != null && !id.equals(oldId)) {
                announce("sale.started");
                plugin.menus().renderAll();
            } else if (id == null && oldId != null) {
                Lang.broadcast(Placeholders.apply(plugin.lang().raw("sale.ended"), null));
                plugin.menus().renderAll();
            }
        });
    }

    private void parse(String v) {
        if (v == null || v.isEmpty()) {
            id = null;
            percent = 0;
            return;
        }
        // id|percent|until|scope
        String[] f = v.split("\\|", 4);
        try {
            long u = Long.parseLong(f[2]);
            if (u <= System.currentTimeMillis()) {
                id = null;
                percent = 0;
                return;
            }
            id = f[0];
            percent = Math.max(0, Math.min(100, Integer.parseInt(f[1])));
            until = u;
            scope = f.length > 3 && !f[3].isEmpty() ? f[3] : "*";
        } catch (Exception e) {
            id = null;
            percent = 0;
        }
    }

    /** Starts a sale now (and on every server of the network). */
    public void begin(int pct, long millis, String shop) {
        id = UUID.randomUUID().toString().substring(0, 8);
        percent = Math.max(1, Math.min(100, pct));
        until = System.currentTimeMillis() + millis;
        scope = shop == null || shop.isEmpty() ? "*" : shop.toLowerCase(Locale.ROOT);
        plugin.tokens().meta("sale", id + "|" + percent + "|" + until + "|" + scope);
        announce("sale.started");
        plugin.menus().renderAll();
    }

    /** /tokens sale stop: ends the sale on every server. */
    public void end(boolean announce) {
        if (id == null) return;
        id = null;
        percent = 0;
        plugin.tokens().meta("sale", null);
        if (announce) Lang.broadcast(Placeholders.apply(plugin.lang().raw("sale.ended"), null));
        plugin.menus().renderAll();
    }

    /** The time is over: ends it here, and removes it from the database only if it is still the same sale. */
    private void expired() {
        String old = id;
        id = null;
        percent = 0;
        Lang.broadcast(Placeholders.apply(plugin.lang().raw("sale.ended"), null));
        plugin.menus().renderAll();
        plugin.tokens().meta("sale").thenAccept(v -> {
            if (v != null && v.startsWith(old + "|")) plugin.tokens().meta("sale", null);
        });
    }

    private void announce(String key) {
        String text = plugin.lang().raw(key);
        if (text == null || text.isEmpty()) return;
        Lang.broadcast(Placeholders.apply(text, null, "{percent}", String.valueOf(percent), "{time}", timeLeft(),
                "{shop}", scope.equals("*") ? plugin.lang().raw("sale.every-shop") : scope));
    }

    public boolean active() {
        return id != null && System.currentTimeMillis() < until;
    }

    /** Discount for a shop, 0 when there is no sale there. */
    public int percent(String shop) {
        if (!active()) return 0;
        return scope.equals("*") || scope.equalsIgnoreCase(shop) ? percent : 0;
    }

    public long price(String shop, MenuItem.Product p) {
        if (p == null) return 0;
        if (!p.sale()) return p.price();
        int pct = percent(shop);
        if (pct <= 0) return p.price();
        return Math.max(0, p.price() - Math.round(p.price() * pct / 100.0));
    }

    public String scope() {
        return scope;
    }

    public int percentNow() {
        return active() ? percent : 0;
    }

    /** "1h 20m", "45s"; "-" when there is no sale. */
    public String timeLeft() {
        if (!active()) return "-";
        long s = Math.max(0, (until - System.currentTimeMillis()) / 1000);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /** "30m", "2h", "1d", "1h30m" -> milliseconds, -1 if not valid. */
    public static long duration(String text) {
        if (text == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*([dhms])").matcher(text.toLowerCase(Locale.ROOT));
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d": total += n * 86_400_000L; break;
                case "h": total += n * 3_600_000L; break;
                case "m": total += n * 60_000L; break;
                default: total += n * 1000L;
            }
        }
        return any && total > 0 ? total : -1;
    }
}

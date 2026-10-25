package dev.tmdevelopment.flaretokens.feature;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer FlareTokens release on GitHub (read only, nothing is sent).
 * Pre-releases are never suggested. The answer is kept for 6 hours.
 */
public final class UpdateChecker implements Listener {

    public static final String REPO = "numachiamatinu/FlareTokens";
    public static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CACHE_MS = TimeUnit.HOURS.toMillis(6);
    private static final long SHORT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    public enum State { LATEST, AVAILABLE, FAILED, OFF }

    private final FlareTokens plugin;
    private volatile String latest;
    private volatile long checkedAt;
    private volatile boolean failed;
    private boolean running;
    private final List<Consumer<State>> waiting = new ArrayList<>();
    private boolean announced;

    public UpdateChecker(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("update-check", true);
    }

    public String current() {
        return plugin.getDescription().getVersion();
    }

    /** Latest released version, or null if unknown. */
    public String latest() {
        return latest;
    }

    /** Startup check: one line in the console when a newer version is out. */
    public void checkAtStartup() {
        if (!enabled()) return;
        check(false, state -> {
            if (state == State.AVAILABLE && !announced) {
                announced = true;
                plugin.getLogger().info("A new version is available: " + latest + " (you have " + current() + "). Download: " + PAGE);
            }
        });
    }

    /**
     * Calls back on the main thread. Uses the saved answer if it is less than 6 hours old
     * (10 minutes with fresh = true, or when the last check failed).
     */
    public void check(boolean fresh, Consumer<State> callback) {
        if (!enabled()) {
            callback.accept(State.OFF);
            return;
        }
        long maxAge = fresh || failed ? SHORT_MS : CACHE_MS;
        if (checkedAt > 0 && System.currentTimeMillis() - checkedAt < maxAge) {
            callback.accept(state());
            return;
        }
        synchronized (waiting) {
            waiting.add(callback);
            if (running) return;
            running = true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String found = null;
            try {
                found = fetch();
            } catch (Throwable ignored) {
            }
            String result = found;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result != null) {
                    latest = result;
                    failed = false;
                } else {
                    failed = true;
                }
                checkedAt = System.currentTimeMillis();
                List<Consumer<State>> list;
                synchronized (waiting) {
                    list = new ArrayList<>(waiting);
                    waiting.clear();
                    running = false;
                }
                State s = state();
                for (Consumer<State> c : list) {
                    try {
                        c.accept(s);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Update check: " + t.getMessage());
                    }
                }
            });
        });
    }

    private State state() {
        if (failed || latest == null) return State.FAILED;
        return newer(latest, current()) ? State.AVAILABLE : State.LATEST;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || !p.hasPermission("flaretokens.admin")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            check(false, state -> {
                if (state == State.AVAILABLE && p.isOnline()) {
                    plugin.lang().send(p, "update.available", "{latest}", latest, "{url}", PAGE);
                }
            });
        }, 60L);
    }

    private String fetch() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "TMDevelopment/FlareTokens/" + current());
        try {
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                Matcher m = TAG.matcher(new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (!m.find()) return null;
                String tag = m.group(1).trim();
                if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
                return tag.matches("[0-9][0-9A-Za-z.+-]{0,39}") ? tag : null;
            }
        } finally {
            con.disconnect();
        }
    }

    /** true if a is a higher version than b (4.0.10 > 4.0.9, 4.1 > 4.0.5, 4.0.0 = 4.0). */
    public static boolean newer(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] numbers(String v) {
        String s = v == null ? "" : v;
        int cut = s.indexOf('-');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('+');
        if (cut >= 0) s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String d = parts[i].replaceAll("\\D.*$", "");
            try {
                out[i] = d.isEmpty() ? 0 : Integer.parseInt(d);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}

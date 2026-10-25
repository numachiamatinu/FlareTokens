package dev.tmdevelopment.flaretokens.data;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.api.TokensChangeEvent;
import dev.tmdevelopment.flaretokens.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Balances of the players.
 * <ul>
 *   <li>Online players are kept in memory: reading is instant, changes are visible at once and written to
 *       the storage right after on a separate thread (the server never waits for the database).</li>
 *   <li>Offline players (and players still loading) are changed directly in the storage, async;
 *       the result comes back on the main thread.</li>
 *   <li>With a shared MySQL database the balances of online players are refreshed every few seconds,
 *       so changes made on another server show up here too.</li>
 * </ul>
 * Every read-modify-write of the memory copy happens under one lock, so calls from other threads are safe.
 */
public final class TokenManager {

    /** Result of give/set/take when a TokensChangeEvent listener cancelled the change. */
    public static final long CANCELLED = -2L;

    private final FlareTokens plugin;
    private final Storage storage;
    private final ExecutorService db;
    private final Object lock = new Object();
    private final Map<UUID, Long> cache = new ConcurrentHashMap<>();
    /** One entry per online (or loading) player, increased at every change: stale reads are thrown away. */
    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    /** Balances of offline players read for placeholders: value and time it was read. */
    private final Map<UUID, long[]> offline = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineAsked = new ConcurrentHashMap<>();
    private static final long OFFLINE_MS = 60_000L;
    private static final int OFFLINE_MAX = 5000;
    private volatile List<Storage.Account> top = Collections.emptyList();
    private final AtomicBoolean topPending = new AtomicBoolean();
    private volatile long topTime;
    private volatile int accounts;
    private volatile long startBalance;
    private volatile boolean logTransactions;
    private volatile boolean keepHistory;
    /** Name of this server in the history and for the other servers of the network. */
    private volatile String server = "";
    private final File logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"); // only used on the db thread

    public TokenManager(FlareTokens plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.db = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FlareTokens-Storage");
            t.setDaemon(true);
            return t;
        });
        reloadSettings();
        this.logFile = new File(plugin.getDataFolder(), "logs/transactions.log");
    }

    /** Options of config.yml used here (read again by /tokens reload). */
    public void reloadSettings() {
        this.startBalance = Math.max(0, plugin.getConfig().getLong("first-join-tokens", 0));
        this.logTransactions = plugin.getConfig().getBoolean("logs.transactions", true);
        this.keepHistory = plugin.getConfig().getBoolean("history.enabled", true);
        String name = plugin.getConfig().getString("network.server-name", "");
        if (name == null || name.trim().isEmpty()) name = "server-" + Bukkit.getPort();
        this.server = name.trim();
        if (storage instanceof SqlStorage) ((SqlStorage) storage).server(this.server);
    }

    public String serverName() {
        return server;
    }

    public Storage storage() {
        return storage;
    }

    /** Opens the storage (blocking, at startup). */
    public void open() throws Exception {
        java.util.concurrent.Future<?> f = db.submit(() -> {
            storage.open();
            accounts = storage.count();
            int days = plugin.getConfig().getInt("history.keep-days", 90);
            if (days > 0) {
                try {
                    storage.pruneHistory(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
                } catch (Throwable t) {
                    plugin.getLogger().fine("Could not clean the history: " + t.getMessage());
                }
            }
            return null;
        });
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        refreshTop(true);
    }

    public void shutdown() {
        db.execute(() -> {
            try {
                storage.flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not save the balances", t);
            }
            storage.close();
        });
        db.shutdown();
        try {
            if (!db.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("The storage took too long to close.");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ players

    /** Loads a player that joined (creates the account with the first join tokens). */
    public void load(Player p) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        int version;
        synchronized (lock) {
            bump(id);
            version = versions.get(id);
        }
        async(() -> {
            boolean created = storage.create(id, name, startBalance);
            Long value = storage.get(id);
            if (created) accounts++;
            long v = value == null ? 0 : value;
            if (created) log("CREATE", id, name, startBalance, v, "first join");
            sync(() -> {
                Player online = Bukkit.getPlayer(id);
                if (online == null) return;
                if (!apply(id, version, v)) reload(id, 3);
                if (created && startBalance > 0) plugin.onFirstJoin(online, startBalance);
            });
        });
    }

    /** Puts a value read from the storage in memory, only if nothing changed meanwhile. */
    private boolean apply(UUID id, int version, long value) {
        synchronized (lock) {
            Integer now = versions.get(id);
            if (now == null) return true; // player left
            if (now != version) return false;
            cache.put(id, value);
            return true;
        }
    }

    /** Reads the balance again (a change happened while it was loading). */
    private void reload(UUID id, int tries) {
        Integer v0 = versions.get(id);
        if (v0 == null) return;
        int version = v0;
        async(() -> {
            Long value = storage.get(id);
            sync(() -> {
                if (!apply(id, version, value == null ? 0 : value) && tries > 0) reload(id, tries - 1);
            });
        });
    }

    public void unload(UUID id) {
        synchronized (lock) {
            Long last = cache.remove(id);
            versions.remove(id);
            if (last != null) rememberOffline(id, last);
        }
    }

    public boolean isLoaded(UUID id) {
        return cache.containsKey(id);
    }

    /** Balance of an online (loaded) player; 0 when not loaded yet. For anyone else use {@link #fetch}. */
    public long balance(UUID id) {
        Long v = cache.get(id);
        if (v != null) return v;
        if (storage instanceof YamlStorage) {
            Long s = ((YamlStorage) storage).get(id);
            return s == null ? 0 : s;
        }
        return 0;
    }

    /**
     * Balance for placeholders, never waits: online players from memory; offline players from the last read
     * (max 60 seconds old) or the top list, while a fresh value is read in the background
     * (at most once a minute per player). 0 until the first read is done.
     */
    public long balanceForDisplay(UUID id) {
        Long v = cache.get(id);
        if (v != null) return v;
        if (storage instanceof YamlStorage) return balance(id);
        long now = System.currentTimeMillis();
        long[] known = offline.get(id);
        if (known != null && now - known[1] < OFFLINE_MS) return known[0];
        Long asked = offlineAsked.get(id);
        if (asked == null || now - asked >= OFFLINE_MS) {
            if (offlineAsked.size() >= OFFLINE_MAX) offlineAsked.clear();
            offlineAsked.put(id, now);
            async(() -> {
                Long value = storage.get(id);
                if (!cache.containsKey(id)) rememberOffline(id, value == null ? 0 : value);
            });
        }
        if (known != null) return known[0];
        for (Storage.Account a : top) if (a.uuid.equals(id)) return a.tokens;
        return 0;
    }

    private void rememberOffline(UUID id, long value) {
        if (offline.size() >= OFFLINE_MAX) offline.clear();
        offline.put(id, new long[]{value, System.currentTimeMillis()});
    }

    /** A change of an offline player: the value shown by placeholders follows at once. */
    private void offlineChanged(UUID id, long value) {
        if (value >= 0) offline.computeIfPresent(id, (k, old) -> new long[]{value, System.currentTimeMillis()});
    }

    /** Balance of anyone, waiting for the storage when needed (max 3 seconds). Only for the old 3.x API. */
    public long balanceNow(UUID id) {
        Long v = cache.get(id);
        if (v != null) return v;
        if (storage instanceof YamlStorage) return balance(id);
        try {
            Long s = db.submit(() -> storage.get(id)).get(3, TimeUnit.SECONDS);
            return s == null ? 0 : s;
        } catch (Exception e) {
            return 0;
        }
    }

    public CompletableFuture<Long> fetch(UUID id) {
        Long v = cache.get(id);
        if (v != null) return CompletableFuture.completedFuture(v);
        CompletableFuture<Long> f = new CompletableFuture<>();
        async(() -> {
            Long value = storage.get(id);
            sync(() -> f.complete(value == null ? 0L : value));
        }, f);
        return f;
    }

    // ------------------------------------------------------------------ changes

    /**
     * Gives tokens. Online players change instantly; the future completes with the new balance on the main thread
     * ({@link #CANCELLED} if a listener cancelled the change).
     */
    public CompletableFuture<Long> give(UUID id, String name, long amount, String cause) {
        CompletableFuture<Long> f = new CompletableFuture<>();
        if (amount <= 0) {
            f.complete(balance(id));
            return f;
        }
        synchronized (lock) {
            Long cached = cache.get(id);
            if (cached != null) {
                long now = add(cached, amount);
                if (!event(id, cached, now, TokensChangeEvent.Type.GIVE, cause)) {
                    f.complete(CANCELLED);
                    return f;
                }
                cache.put(id, now);
                bump(id);
                f.complete(now);
                async(() -> storage.add(id, name, amount, startBalance));
                log("GIVE", id, name, amount, now, cause);
                return f;
            }
            bump(id); // player still loading: the value read by the load is thrown away
        }
        async(() -> {
            boolean created = storage.create(id, name, startBalance);
            if (created) accounts++;
            long now = storage.add(id, name, amount, startBalance);
            offlineChanged(id, now);
            log("GIVE", id, name, amount, now, cause);
            sync(() -> f.complete(now));
        }, f);
        return f;
    }

    /**
     * Removes tokens. With {@code onlyIfEnough} nothing is removed when the player has less (result -1),
     * otherwise the balance stops at 0.
     */
    public CompletableFuture<Long> take(UUID id, String name, long amount, boolean onlyIfEnough, String cause) {
        CompletableFuture<Long> f = new CompletableFuture<>();
        if (amount <= 0) {
            f.complete(balance(id));
            return f;
        }
        synchronized (lock) {
            Long cached = cache.get(id);
            if (cached != null) {
                if (onlyIfEnough && cached < amount) {
                    f.complete(-1L);
                    return f;
                }
                long now = Math.max(0, cached - amount);
                if (!event(id, cached, now, TokensChangeEvent.Type.TAKE, cause)) {
                    f.complete(onlyIfEnough ? -1L : CANCELLED);
                    return f;
                }
                cache.put(id, now);
                bump(id);
                f.complete(now);
                long removed = cached - now;
                async(() -> {
                    long result = onlyIfEnough ? storage.take(id, removed) : storage.add(id, name, -removed, startBalance);
                    if (result < 0) {
                        // another server spent them first: show the real balance again
                        plugin.getLogger().warning("The balance of " + name + " was lower in the database (changed by another server?), reloaded.");
                        sync(() -> reload(id, 3));
                    }
                });
                log("TAKE", id, name, amount, now, cause);
                return f;
            }
            bump(id);
        }
        async(() -> {
            long now;
            if (onlyIfEnough) {
                now = storage.take(id, amount);
            } else {
                storage.create(id, name, startBalance);
                now = storage.add(id, name, -amount, startBalance);
            }
            offlineChanged(id, now);
            if (now >= 0) log("TAKE", id, name, amount, now, cause);
            long result = now;
            sync(() -> f.complete(result));
        }, f);
        return f;
    }

    public CompletableFuture<Long> set(UUID id, String name, long value, String cause) {
        CompletableFuture<Long> f = new CompletableFuture<>();
        long v = Math.max(0, value);
        synchronized (lock) {
            Long cached = cache.get(id);
            if (cached != null) {
                if (!event(id, cached, v, TokensChangeEvent.Type.SET, cause)) {
                    f.complete(CANCELLED);
                    return f;
                }
                cache.put(id, v);
                bump(id);
                f.complete(v);
                async(() -> storage.set(id, name, v));
                log("SET", id, name, v, v, cause);
                return f;
            }
            bump(id);
        }
        async(() -> {
            if (storage.get(id) == null) accounts++;
            storage.set(id, name, v);
            offlineChanged(id, v);
            log("SET", id, name, v, v, cause);
            sync(() -> f.complete(v));
        }, f);
        return f;
    }

    /** Instant purchase for an online player: true if he had enough (already removed). */
    public boolean spend(Player p, long amount, String cause) {
        if (amount <= 0) return true;
        synchronized (lock) {
            Long cached = cache.get(p.getUniqueId());
            if (cached == null || cached < amount) return false;
            CompletableFuture<Long> r = take(p.getUniqueId(), p.getName(), amount, true, cause);
            Long after = r.getNow(-1L);
            return after != null && after >= 0;
        }
    }

    private boolean event(UUID id, long before, long after, TokensChangeEvent.Type type, String cause) {
        if (!Bukkit.isPrimaryThread()) return true;
        TokensChangeEvent e = new TokensChangeEvent(id, before, after, type, cause);
        try {
            Bukkit.getPluginManager().callEvent(e);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "A TokensChangeEvent listener failed", t);
        }
        return !e.isCancelled();
    }

    private static long add(long a, long b) {
        return YamlStorage.safeAdd(a, b);
    }

    /** Only players that are online or loading have a version. */
    private void bump(UUID id) {
        if (!versions.containsKey(id) && Bukkit.getPlayer(id) == null) return;
        versions.merge(id, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ lookups

    /** A player by name: online, who already joined this server, or known by the storage. */
    public CompletableFuture<Storage.Account> find(String name) {
        CompletableFuture<Storage.Account> f = new CompletableFuture<>();
        UUID asId = uuidOf(name);
        if (asId != null) {
            // web stores (Tebex...) can send the uuid: works for any player, also renamed ones
            Player p = Bukkit.getPlayer(asId);
            if (p != null) {
                f.complete(new Storage.Account(asId, p.getName(), balance(asId)));
                return f;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(asId);
            String fallback = op.getName() != null ? op.getName() : asId.toString().substring(0, 8);
            async(() -> {
                Storage.Account known = storage.account(asId);
                String n = known != null && known.name != null && !known.name.isEmpty() ? known.name : fallback;
                Storage.Account a = new Storage.Account(asId, n, known == null ? 0 : known.tokens);
                sync(() -> f.complete(a));
            }, f);
            return f;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            f.complete(new Storage.Account(online.getUniqueId(), online.getName(), balance(online.getUniqueId())));
            return f;
        }
        OfflinePlayer known = Compat.knownPlayer(name);
        if (known != null) {
            f.complete(new Storage.Account(known.getUniqueId(), known.getName() == null ? name : known.getName(), balance(known.getUniqueId())));
            return f;
        }
        // other servers of a network: the name saved in the database
        async(() -> {
            Storage.Account a = storage.byName(name);
            sync(() -> f.complete(a));
        }, f);
        return f;
    }

    public List<Storage.Account> top() {
        return top;
    }

    /** A uuid written with or without dashes, or null. */
    public static UUID uuidOf(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() == 32 && t.matches("[0-9a-fA-F]{32}")) {
            t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-" + t.substring(16, 20) + "-" + t.substring(20);
        }
        if (t.length() != 36) return null;
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ history, purchases, shared values

    public CompletableFuture<List<Storage.Tx>> history(UUID id, int offset, int limit) {
        CompletableFuture<List<Storage.Tx>> f = new CompletableFuture<>();
        async(() -> {
            List<Storage.Tx> list = storage.history(id, offset, limit);
            sync(() -> f.complete(list));
        }, f);
        return f;
    }

    public CompletableFuture<Integer> bought(String who, String key, String period) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        async(() -> {
            int n = storage.bought(who, key, period);
            sync(() -> f.complete(n));
        }, f);
        return f;
    }

    public CompletableFuture<Boolean> reserve(String who, String key, String period, int max) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        async(() -> {
            boolean ok = storage.reserve(who, key, period, max);
            sync(() -> f.complete(ok));
        }, f);
        return f;
    }

    public void release(String who, String key, String period) {
        async(() -> storage.release(who, key, period));
    }

    public CompletableFuture<String> meta(String key) {
        CompletableFuture<String> f = new CompletableFuture<>();
        async(() -> {
            String v = storage.meta(key);
            sync(() -> f.complete(v));
        }, f);
        return f;
    }

    public void meta(String key, String value) {
        async(() -> storage.meta(key, value));
    }

    /** Changes made by the other servers of the network: their balances are read again at once. */
    public void pollChanges() {
        if (!storage.shared()) return;
        async(() -> {
            List<UUID> ids = storage.changes(server);
            if (ids.isEmpty()) return;
            Map<UUID, Long> offlineValues = new HashMap<>();
            for (UUID id : ids) {
                if (offline.containsKey(id) && !cache.containsKey(id)) {
                    Long v = storage.get(id);
                    offlineValues.put(id, v == null ? 0 : v);
                }
            }
            for (Map.Entry<UUID, Long> e : offlineValues.entrySet()) rememberOffline(e.getKey(), e.getValue());
            sync(() -> {
                for (UUID id : ids) if (cache.containsKey(id)) reload(id, 3);
            });
        });
    }

    public int accounts() {
        return Math.max(accounts, cache.size());
    }

    public void refreshTop() {
        refreshTop(false);
    }

    /** Reads the top list again (at most once every 5 seconds, never two at the same time). */
    public void refreshTop(boolean force) {
        if (!force && System.currentTimeMillis() - topTime < 5000) return;
        if (!topPending.compareAndSet(false, true)) return;
        topTime = System.currentTimeMillis();
        int size = Math.max(10, plugin.getConfig().getInt("top.size", 10));
        async(() -> {
            try {
                try {
                    storage.flush();
                } catch (Throwable ignored) {
                }
                List<Storage.Account> list = storage.top(size);
                accounts = storage.count();
                top = Collections.unmodifiableList(new ArrayList<>(list));
            } finally {
                topPending.set(false);
            }
        });
    }

    /** Reads the balance of online players again (several servers on one MySQL database). */
    public void refreshOnline() {
        if (cache.isEmpty()) return;
        Map<UUID, Integer> snapshot = new HashMap<>(versions);
        List<UUID> ids = new ArrayList<>(cache.keySet());
        async(() -> {
            Map<UUID, Long> fresh = new HashMap<>();
            for (UUID id : ids) {
                Long v = storage.get(id);
                if (v != null) fresh.put(id, v);
            }
            sync(() -> {
                for (Map.Entry<UUID, Long> e : fresh.entrySet()) {
                    Integer ver = snapshot.get(e.getKey());
                    if (ver != null && cache.containsKey(e.getKey())) apply(e.getKey(), ver, e.getValue());
                }
            });
        });
    }

    public void flushAsync() {
        async(storage::flush);
    }

    /** Copies every account into another storage (/tokens migrate). */
    public void migrate(Storage target, Consumer<String> done) {
        async(() -> {
            storage.flush();
            List<Storage.Account> all = storage.all();
            target.open();
            int n = 0;
            for (Storage.Account a : all) {
                target.set(a.uuid, a.name, a.tokens);
                n++;
            }
            target.flush();
            target.close();
            int total = n;
            sync(() -> done.accept(String.valueOf(total)));
        }, error -> sync(() -> done.accept("error: " + error.getMessage())));
    }

    // ------------------------------------------------------------------ threads

    private interface Work {
        void run() throws Exception;
    }

    private void async(Work work) {
        async(work, (Consumer<Throwable>) null);
    }

    private void async(Work work, CompletableFuture<?> future) {
        async(work, t -> sync(() -> future.completeExceptionally(t)));
    }

    private void async(Work work, Consumer<Throwable> onError) {
        try {
            db.execute(() -> {
                try {
                    work.run();
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Storage error (" + storage.type() + "): " + t.getMessage(), t);
                    if (onError != null) onError.accept(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // shutting down
        }
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        // shutting down: nothing runs on the database thread with the Bukkit API (no free purchases)
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (Throwable ignored) {
            // disabled meanwhile
        }
    }

    private void log(String action, UUID id, String name, long amount, long after, String cause) {
        long time = System.currentTimeMillis();
        boolean file = logTransactions;
        boolean keep = keepHistory;
        if (!file && !keep) return;
        try {
            db.execute(() -> {
                if (keep) {
                    try {
                        storage.history(new Storage.Tx(time, id, name, action, amount, after, cause, server));
                    } catch (Throwable t) {
                        plugin.getLogger().fine("Could not save the history: " + t.getMessage());
                    }
                }
                if (!file) return;
                try {
                    String line = "[" + dateFormat.format(new Date(time)) + "] " + action + " " + (name == null ? "?" : name) + " " + amount
                            + " -> " + after + (cause == null ? "" : " (" + cause + ")") + "\n";
                    if (!logFile.getParentFile().exists()) logFile.getParentFile().mkdirs();
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                        w.write(line);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }
}

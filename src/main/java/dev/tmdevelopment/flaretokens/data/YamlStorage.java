package dev.tmdevelopment.flaretokens.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * storage.type: yaml (called "file" in 3.x). Same file and layout as 3.x: data/data.yml
 * <pre>
 * account:
 *   &lt;uuid&gt;:
 *     tokens: 100
 *     name: Steve
 * </pre>
 * Everything is kept in memory and written every few seconds when something changed.
 */
public final class YamlStorage implements Storage {

    private final File file;
    private final Map<UUID, long[]> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private YamlConfiguration yaml;
    private volatile boolean dirty;
    /** History, purchase counters and shared values: data/state.yml. */
    private final File stateFile;
    private final Map<UUID, List<Tx>> history = new ConcurrentHashMap<>();
    private final Map<String, Integer> purchases = new ConcurrentHashMap<>();
    private final Map<String, String> meta = new ConcurrentHashMap<>();
    private volatile boolean stateDirty;
    private static final int HISTORY_PER_PLAYER = 200;

    public YamlStorage(File file) {
        this.file = file;
        this.stateFile = new File(file.getParentFile(), "state.yml");
    }

    @Override
    public String type() {
        return "yaml";
    }

    @Override
    public void open() throws Exception {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) throw new IllegalStateException("can't create " + file.getParent());
        yaml = YamlConfiguration.loadConfiguration(file);
        loadState();
        ConfigurationSection acc = yaml.getConfigurationSection("account");
        if (acc == null) return;
        for (String key : acc.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            balances.put(id, new long[]{acc.getLong(key + ".tokens")});
            String n = acc.getString(key + ".name");
            if (n != null) names.put(id, n);
        }
    }

    @Override
    public void close() {
        try {
            flush();
        } catch (Exception ignored) {
        }
    }

    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration st = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection p = st.getConfigurationSection("purchases");
        if (p != null) for (String k : p.getKeys(false)) purchases.put(unescape(k), p.getInt(k));
        ConfigurationSection m = st.getConfigurationSection("meta");
        if (m != null) for (String k : m.getKeys(false)) meta.put(k, m.getString(k));
        ConfigurationSection h = st.getConfigurationSection("history");
        if (h == null) return;
        for (String key : h.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<Tx> list = new ArrayList<>();
            for (String line : h.getStringList(key)) {
                // time|kind|amount|balance|server|name|cause
                String[] f = line.split("\\|", 7);
                if (f.length < 7) continue;
                try {
                    list.add(new Tx(Long.parseLong(f[0]), id, f[5], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), f[6], f[4]));
                } catch (NumberFormatException ignored) {
                }
            }
            history.put(id, list);
        }
    }

    /** Keys of purchases contain dots (shop.item): stored with "~" in the file. */
    private static String escape(String k) {
        return k.replace('.', '~');
    }

    private static String unescape(String k) {
        return k.replace('~', '.');
    }

    private synchronized void flushState() throws Exception {
        if (!stateDirty) return;
        stateDirty = false;
        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<String, Integer> e : purchases.entrySet()) out.set("purchases." + escape(e.getKey()), e.getValue());
        for (Map.Entry<String, String> e : meta.entrySet()) out.set("meta." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<Tx>> e : history.entrySet()) {
            List<String> lines = new ArrayList<>();
            for (Tx t : e.getValue()) {
                lines.add(t.time + "|" + t.action + "|" + t.amount + "|" + t.balance + "|" + clean(t.server) + "|" + clean(t.name) + "|"
                        + (t.cause == null ? "" : t.cause));
            }
            out.set("history." + e.getKey(), lines);
        }
        File tmp = new File(stateFile.getParentFile(), stateFile.getName() + ".tmp");
        out.save(tmp);
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("|", "/");
    }

    @Override
    public void history(Tx tx) {
        List<Tx> list = history.computeIfAbsent(tx.uuid, k -> new ArrayList<>());
        synchronized (list) {
            list.add(0, tx);
            while (list.size() > HISTORY_PER_PLAYER) list.remove(list.size() - 1);
        }
        stateDirty = true;
    }

    @Override
    public List<Tx> history(UUID uuid, int offset, int limit) {
        List<Tx> list = history.get(uuid);
        if (list == null) return new ArrayList<>();
        synchronized (list) {
            int from = Math.min(list.size(), Math.max(0, offset));
            int to = Math.min(list.size(), from + limit);
            return new ArrayList<>(list.subList(from, to));
        }
    }

    @Override
    public void pruneHistory(long before) {
        for (List<Tx> list : history.values()) {
            synchronized (list) {
                if (list.removeIf(t -> t.time < before)) stateDirty = true;
            }
        }
    }

    private static String pkey(String who, String key, String period) {
        return who + "|" + key + "|" + period;
    }

    @Override
    public int bought(String who, String key, String period) {
        Integer n = purchases.get(pkey(who, key, period));
        return n == null ? 0 : n;
    }

    @Override
    public synchronized boolean reserve(String who, String key, String period, int max) {
        String k = pkey(who, key, period);
        int n = purchases.getOrDefault(k, 0);
        if (n >= max) return false;
        purchases.put(k, n + 1);
        stateDirty = true;
        return true;
    }

    @Override
    public synchronized void release(String who, String key, String period) {
        String k = pkey(who, key, period);
        int n = purchases.getOrDefault(k, 0);
        if (n > 0) purchases.put(k, n - 1);
        stateDirty = true;
    }

    @Override
    public String meta(String key) {
        return meta.get(key);
    }

    @Override
    public void meta(String key, String value) {
        if (value == null) meta.remove(key);
        else meta.put(key, value);
        stateDirty = true;
    }

    @Override
    public synchronized void flush() throws Exception {
        flushState();
        if (!dirty || yaml == null) return;
        dirty = false;
        YamlConfiguration out = new YamlConfiguration();
        // keep other sections of the old file (rotating_shop, global_multiplier...)
        for (String k : yaml.getKeys(false)) if (!k.equals("account") && !k.equals("players") && !k.equals("mobcoins")) out.set(k, yaml.get(k));
        for (Map.Entry<UUID, long[]> e : balances.entrySet()) {
            String base = "account." + e.getKey();
            out.set(base + ".tokens", e.getValue()[0]);
            String n = names.get(e.getKey());
            if (n != null) out.set(base + ".name", n);
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        out.save(tmp);
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public Long get(UUID uuid) {
        long[] v = balances.get(uuid);
        return v == null ? null : v[0];
    }

    @Override
    public synchronized boolean create(UUID uuid, String name, long start) {
        if (name != null) names.put(uuid, name);
        if (balances.containsKey(uuid)) return false;
        balances.put(uuid, new long[]{start});
        dirty = true;
        return true;
    }

    @Override
    public synchronized long add(UUID uuid, String name, long delta, long start) {
        create(uuid, name, start);
        long[] v = balances.get(uuid);
        v[0] = Math.max(0, safeAdd(v[0], delta));
        dirty = true;
        return v[0];
    }

    @Override
    public synchronized long take(UUID uuid, long amount) {
        long[] v = balances.get(uuid);
        if (v == null || v[0] < amount) return -1;
        v[0] -= amount;
        dirty = true;
        return v[0];
    }

    @Override
    public synchronized void set(UUID uuid, String name, long value) {
        if (name != null) names.put(uuid, name);
        balances.put(uuid, new long[]{Math.max(0, value)});
        dirty = true;
    }

    @Override
    public void updateName(UUID uuid, String name) {
        if (name == null) return;
        String old = names.put(uuid, name);
        if (!name.equals(old)) dirty = true;
    }

    @Override
    public Account account(UUID uuid) {
        long[] v = balances.get(uuid);
        return v == null ? null : new Account(uuid, names.get(uuid), v[0]);
    }

    @Override
    public Account byName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                long[] v = balances.get(e.getKey());
                return new Account(e.getKey(), e.getValue(), v == null ? 0 : v[0]);
            }
        }
        return null;
    }

    @Override
    public List<Account> top(int limit) {
        List<Account> all = all();
        all.sort(Comparator.comparingLong((Account a) -> a.tokens).reversed());
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    @Override
    public List<Account> all() {
        List<Account> out = new ArrayList<>(balances.size());
        for (Map.Entry<UUID, long[]> e : balances.entrySet()) out.add(new Account(e.getKey(), names.get(e.getKey()), e.getValue()[0]));
        return out;
    }

    @Override
    public int count() {
        return balances.size();
    }

    static long safeAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return r;
    }
}

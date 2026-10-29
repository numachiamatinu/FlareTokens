package dev.tmdevelopment.flaretokens.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saves a changed YAML file by touching only what changed: single values are changed on their line, changed
 * blocks (an item, the decorations ...) are written again, everything else (comments, quotes, layout) stays as it
 * was. Bukkit's own save writes the whole file again and drops every comment on 1.8 - 1.17.
 */
public final class YamlKeeper {

    private YamlKeeper() {
    }

    /**
     * The old text changed so that it holds the values of {@code now}.
     * @return the new text, or null when it could not be done safely (the caller then saves the whole file)
     */
    public static String patch(String oldText, YamlConfiguration now) {
        try {
            String text = oldText.replace("\r\n", "\n");
            YamlConfiguration old = new YamlConfiguration();
            old.loadFromString(text);
            String[] out = {text};
            if (!section(old, now, "", out, listIndent(text))) return null;
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(out[0]);
            if (!sameLeaves(leaves(check), leaves(now))) return null;
            return out[0];
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean section(ConfigurationSection old, ConfigurationSection now, String base, String[] out, int listIndent) {
        Set<String> keys = new LinkedHashSet<>();
        ConfigurationSection o = base.isEmpty() ? old : old.getConfigurationSection(base);
        ConfigurationSection n = base.isEmpty() ? now : now.getConfigurationSection(base);
        if (o != null) keys.addAll(o.getKeys(false));
        if (n != null) keys.addAll(n.getKeys(false));
        for (String key : keys) {
            if (key.contains(".")) return false;
            String path = base.isEmpty() ? key : base + "." + key;
            Object a = old.get(path);
            Object b = now.get(path);
            if (a instanceof ConfigurationSection && b instanceof ConfigurationSection) {
                if (!section(old, now, path, out, listIndent)) return false;
                continue;
            }
            if (a != null && b != null && equal(a, b)) continue;
            String changed = null;
            if (a != null && b != null && scalar(a) && scalar(b)) {
                String v = scalarText(b);
                if (v != null) changed = YamlPatch.setKeepComment(out[0], path, v);
            }
            if (changed == null) changed = YamlPatch.replaceBlock(out[0], path, b == null ? null : fragment(key, b, listIndent));
            if (changed == null) return false;
            out[0] = changed;
        }
        return true;
    }

    private static boolean scalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    /** A value as YAML on one line, null if it needs more lines. */
    private static String scalarText(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.contains("\n")) return null;
            return YamlPatch.quote(s);
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        }
        return String.valueOf(v);
    }

    /**
     * "key: value" (and the lines under it), starting at column 0, written like the rest of the file:
     * strings in double quotes, 500 instead of 500.0, lists indented as in the file.
     * Values it doesn't know are written by Bukkit.
     */
    private static List<String> fragment(String key, Object value, int listIndent) {
        List<String> lines = new ArrayList<>();
        if (!emit(key, plain(value), 0, listIndent, lines)) {
            lines.clear();
            YamlConfiguration tmp = new YamlConfiguration();
            tmp.set(key, plain(value));
            for (String l : tmp.saveToString().split("\n", -1)) lines.add(l);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    private static boolean emit(String key, Object v, int indent, int listIndent, List<String> lines) {
        String pad = YamlPatch.repeat(' ', indent);
        String k = key.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") && !key.matches("(?i)y|n|yes|no|on|off|true|false|null|~")
                ? key : YamlPatch.quote(key);
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                lines.add(pad + k + ": {}");
                return true;
            }
            lines.add(pad + k + ":");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!emit(String.valueOf(e.getKey()), e.getValue(), indent + 2, listIndent, lines)) return false;
            }
            return true;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                lines.add(pad + k + ": []");
                return true;
            }
            boolean simple = true;
            for (Object o : l) simple &= scalar(o) || o instanceof Map && !((Map<?, ?>) o).isEmpty();
            if (!simple) return bukkit(k, v, pad, lines); // items and other objects
            lines.add(pad + k + ":");
            String itemPad = YamlPatch.repeat(' ', indent + listIndent);
            for (Object o : l) {
                if (o instanceof Map && !((Map<?, ?>) o).isEmpty()) {
                    // "- slots: ..." and the other keys under it (decorations)
                    List<String> sub = new ArrayList<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        if (!emit(String.valueOf(e.getKey()), e.getValue(), indent + listIndent + 2, listIndent, sub)) return false;
                    }
                    sub.set(0, itemPad + "- " + sub.get(0).substring(itemPad.length() + 2));
                    lines.addAll(sub);
                    continue;
                }
                String one = scalarText(o);
                if (one == null) return false;
                lines.add(itemPad + "- " + one);
            }
            return true;
        }
        if (!scalar(v)) return bukkit(k, v, pad, lines);
        String one = scalarText(v);
        if (one == null) return false;
        lines.add(pad + k + ": " + one);
        return true;
    }

    /** A value only Bukkit can write (an item...), under its key at this indent. */
    private static boolean bukkit(String key, Object v, String pad, List<String> lines) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("k", v);
        String[] out = tmp.saveToString().split("\n", -1);
        if (out.length == 0 || !out[0].startsWith("k:")) return false;
        lines.add(pad + key + out[0].substring(1));
        for (int i = 1; i < out.length; i++) if (!out[i].isEmpty()) lines.add(pad + out[i]);
        return true;
    }

    /** How deep the lists of the file are under their key: 2 ("key:" / "  - a") or 0 ("key:" / "- a"). */
    private static int listIndent(String text) {
        String[] lines = text.split("\n", -1);
        String prev = null;
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (prev != null && t.startsWith("- ") && prev.trim().endsWith(":")) {
                return Math.max(0, YamlPatch.indent(l) - YamlPatch.indent(prev));
            }
            prev = l;
        }
        return 2;
    }

    /** Sections as plain maps, so they can be written on their own. */
    private static Object plain(Object v) {
        if (v instanceof ConfigurationSection) {
            Map<String, Object> m = new LinkedHashMap<>();
            ConfigurationSection s = (ConfigurationSection) v;
            for (String k : s.getKeys(false)) m.put(k, plain(s.get(k)));
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object o : (List<?>) v) l.add(plain(o));
            return l;
        }
        if (v instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(e.getKey(), plain(e.getValue()));
            return m;
        }
        return v;
    }

    private static String dump(Object v) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("v", plain(v));
        return tmp.saveToString();
    }

    /** 500 and 500.0 are the same value (Bukkit reads them as Integer and Double). */
    private static Object same(Object v) {
        v = plain(v);
        if (v instanceof Number && !(v instanceof java.math.BigDecimal)) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d;
            return d;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<>();
            for (Object o : (List<?>) v) l.add(same(o));
            return l;
        }
        if (v instanceof Map) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(e.getKey(), same(e.getValue()));
            return m;
        }
        return v;
    }

    /** Every value of the file (no sections), to compare two files whatever the order. */
    private static Map<String, Object> leaves(ConfigurationSection c) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : c.getValues(true).entrySet()) {
            if (e.getValue() instanceof ConfigurationSection) {
                if (((ConfigurationSection) e.getValue()).getKeys(false).isEmpty()) out.put(e.getKey(), "{}");
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static boolean sameLeaves(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!equal(e.getValue(), b.get(e.getKey()))) return false;
        }
        return true;
    }

    /**
     * Same value? 500 and 500.0 are the same, and items are compared as items: on new versions an item written and
     * read again can list its data in another order.
     */
    private static boolean equal(Object a, Object b) {
        a = same(a);
        b = same(b);
        if (a == null || b == null) return a == b;
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a;
            Map<?, ?> y = (Map<?, ?>) b;
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !equal(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a;
            List<?> y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Number && b instanceof Number || a instanceof String || a instanceof Boolean) return a.equals(b);
        try {
            if (a.equals(b)) return true;
        } catch (Throwable ignored) {
            // compared as text below
        }
        try {
            String x = dump(a);
            String y = dump(b);
            // one comes from the file, the other from memory: read both from text first
            return x.equals(y) || dump(reread(x)).equals(dump(reread(y)));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object reread(String dumped) throws Exception {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.loadFromString(dumped);
        return tmp.get("v");
    }
}

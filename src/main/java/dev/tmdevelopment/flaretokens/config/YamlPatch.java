package dev.tmdevelopment.flaretokens.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Changes single values in a YAML text without losing the comments (Bukkit drops them on old versions).
 * Only for the simple layout of our own files: "key: value" and lists of "- item".
 */
final class YamlPatch {

    private YamlPatch() {
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Sets a scalar value (already in YAML form: 10, true, "text"). Unknown paths are left alone. */
    static String set(String text, String path, String yamlValue) {
        String[] lines = text.split("\n", -1);
        int idx = find(lines, path);
        if (idx < 0) return text;
        String line = lines[idx];
        int colon = keyColon(line);
        lines[idx] = line.substring(0, colon + 1) + " " + yamlValue;
        return String.join("\n", lines);
    }

    /** Replaces a list value. */
    static String setList(String text, String path, List<String> values) {
        String[] lines = text.split("\n", -1);
        int idx = find(lines, path);
        if (idx < 0) return text;
        String line = lines[idx];
        int indent = indent(line);
        int colon = keyColon(line);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < idx; i++) out.add(lines[i]);
        if (values.isEmpty()) {
            out.add(line.substring(0, colon + 1) + " []");
        } else {
            out.add(line.substring(0, colon + 1));
            String pad = repeat(' ', indent + 2);
            for (String v : values) out.add(pad + "- " + quote(v));
        }
        int i = idx + 1;
        // skip the old list items (or an inline value on the key line)
        while (i < lines.length) {
            String l = lines[i];
            String t = l.trim();
            if (t.isEmpty()) break;
            if (t.startsWith("- ") && indent(l) >= indent) {
                i++;
                continue;
            }
            break;
        }
        for (; i < lines.length; i++) out.add(lines[i]);
        return String.join("\n", out);
    }

    /**
     * Adds a missing option with the comment written above it, copied from the default file.
     * The option goes at the end of its section (or of the file); the rest of the text is not touched.
     * Returns the text unchanged when the option is already there or can't be found in the defaults.
     */
    static String addMissing(String text, String defaults, String path) {
        String[] lines = text.split("\n", -1);
        String[] def = defaults.split("\n", -1);
        if (find(lines, path) >= 0) return text;
        // the highest part of the path that is missing: "a.b.c" with "a" present and "a.b" missing -> "a.b"
        String[] parts = path.split("\\.");
        String missing = null;
        String parent = null;
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String before = sb.length() == 0 ? null : sb.toString();
            if (sb.length() > 0) sb.append('.');
            sb.append(part);
            if (find(lines, sb.toString()) < 0) {
                missing = sb.toString();
                parent = before;
                break;
            }
        }
        if (missing == null) return text;
        int at = find(def, missing);
        if (at < 0) return text;
        int keyIndent = indent(def[at]);
        // comment lines right above the option
        int from = at;
        while (from > 0 && def[from - 1].trim().startsWith("#")) from--;
        // the option and everything under it (deeper lines, its list items)
        int to = at + 1;
        while (to < def.length) {
            String l = def[to];
            String t = l.trim();
            if (t.isEmpty()) {
                int n = to + 1;
                while (n < def.length && def[n].trim().isEmpty()) n++;
                if (n < def.length && indent(def[n]) > keyIndent && !def[n].trim().startsWith("#")) {
                    to = n;
                    continue;
                }
                break;
            }
            if (indent(l) > keyIndent || t.startsWith("- ") && indent(l) == keyIndent) {
                to++;
                continue;
            }
            break;
        }
        List<String> block = new ArrayList<>();
        int shift = 0;
        int insert;
        if (parent == null) {
            insert = lines.length;
            while (insert > 0 && lines[insert - 1].trim().isEmpty()) insert--;
            block.add("");
        } else {
            int p = find(lines, parent);
            int pd = find(def, parent);
            if (p < 0 || pd < 0) return text;
            int pIndent = indent(lines[p]);
            shift = pIndent - indent(def[pd]);
            insert = p + 1;
            for (int i = p + 1; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.isEmpty()) continue;
                if (indent(lines[i]) <= pIndent && !(t.startsWith("- ") && indent(lines[i]) == pIndent)) break;
                insert = i + 1;
            }
        }
        for (int i = from; i < to; i++) block.add(reindent(def[i], shift));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < insert; i++) out.add(lines[i]);
        out.addAll(block);
        for (int i = insert; i < lines.length; i++) out.add(lines[i]);
        if (parent == null && (insert == lines.length || !lines[insert].trim().isEmpty())) {
            // keep a final new line
            if (!out.get(out.size() - 1).isEmpty()) out.add("");
        }
        while (out.size() > 1 && out.get(out.size() - 1).isEmpty() && out.get(out.size() - 2).trim().isEmpty()) out.remove(out.size() - 1);
        return String.join("\n", out);
    }

    private static String reindent(String line, int shift) {
        if (shift == 0 || line.trim().isEmpty()) return line;
        if (shift > 0) return repeat(' ', shift) + line;
        int remove = Math.min(-shift, indent(line));
        return line.substring(remove);
    }

    private static int find(String[] lines, String path) {
        String[] want = path.split("\\.");
        List<String> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("- ")) continue;
            int colon = keyColon(line);
            if (colon < 0) continue;
            int ind = indent(line);
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= ind) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            String key = line.substring(ind, colon).trim();
            if (key.startsWith("\"") && key.endsWith("\"") && key.length() > 1) key = key.substring(1, key.length() - 1);
            stack.add(key);
            indents.add(ind);
            if (stack.size() == want.length) {
                boolean same = true;
                for (int k = 0; k < want.length; k++) {
                    if (!want[k].equals(stack.get(k))) {
                        same = false;
                        break;
                    }
                }
                if (same) return i;
            }
        }
        return -1;
    }

    private static int keyColon(String line) {
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quote = !quote;
            if (!quote && c == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) return i;
            if (!quote && c == '#') return -1;
        }
        return -1;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}

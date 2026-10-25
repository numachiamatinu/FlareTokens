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

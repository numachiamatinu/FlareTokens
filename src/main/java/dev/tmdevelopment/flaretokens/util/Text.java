package dev.tmdevelopment.flaretokens.util;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text engine of the plugin, with no library: works the same from 1.8 to the latest version.
 *
 * <p>Understands at the same time:
 * <ul>
 *   <li>legacy codes {@code &a &l &r} (and {@code §})</li>
 *   <li>hex {@code &#8e2de2}, {@code {#8e2de2}}, {@code &x&f&f&8&0&0&8}</li>
 *   <li>MiniMessage tags {@code <gold> <#8e2de2> <bold> <!italic> <gradient:#a:#b> <rainbow> <reset>
 *       <click:run_command:'/tokens'> <hover:show_text:'text'> <newline>}</li>
 *   <li>the old FlareTokens 3.x tags {@code <GRADIENT:#a-#b>...</GRADIENT>}</li>
 * </ul>
 * Below 1.16 hex colors become the nearest normal color.
 */
public final class Text {

    private Text() {
    }

    // ------------------------------------------------------------------ public api

    /** Legacy text with § codes (hex as §x§r§r§g§g§b§b on 1.16+). For titles, boss bars, scoreboard... */
    public static String legacy(String input) {
        return render(spans(input), Ver.hex());
    }

    /** Item name / lore line: starts with §r so the game doesn't make it italic. */
    public static String item(String input) {
        if (input == null || input.isEmpty()) return "";
        return "§r" + legacy(input);
    }

    public static List<String> items(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(item(l));
        return out;
    }

    /** Console text: no hex (many consoles print the raw codes), hex becomes the nearest color. */
    public static String console(String input) {
        return render(spans(input), false);
    }

    /** Text without any color or tag. */
    /** A legacy text (§ codes, §x hex) written back as editable "&" codes and "&#rrggbb". */
    public static String toEditable(String legacy) {
        if (legacy == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            // §x§a§b§c§d§e§f -> &#abcdef
            if (c == '\u00a7' && i + 13 < legacy.length() && (legacy.charAt(i + 1) == 'x' || legacy.charAt(i + 1) == 'X') && hexRun(legacy, i + 2)) {
                out.append("&#");
                for (int k = 0; k < 6; k++) out.append(legacy.charAt(i + 3 + k * 2));
                i += 13;
                continue;
            }
            out.append(c == '\u00a7' ? '&' : c);
        }
        return out.toString();
    }

    private static boolean hexRun(String s, int from) {
        for (int k = 0; k < 6; k++) {
            int at = from + k * 2;
            if (at + 1 >= s.length() || s.charAt(at) != '\u00a7' || Character.digit(s.charAt(at + 1), 16) < 0) return false;
        }
        return true;
    }

    public static String plain(String input) {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans(input)) sb.append(s.text);
        return sb.toString();
    }

    /** Chat component with colors, click and hover. */
    public static BaseComponent component(String input) {
        TextComponent root = new TextComponent("");
        for (Span s : spans(input)) root.addExtra(bungee(s));
        return root;
    }

    // ------------------------------------------------------------------ model

    static final class Style {
        Integer rgb;          // null = default
        char code;            // legacy code of a named color, 0 if hex
        Boolean bold, italic, underlined, strike, obf;
        String clickAction, clickValue;
        String hover;
        Gradient gradient;    // colors every character when no inner color is set

        Style copy() {
            Style s = new Style();
            s.rgb = rgb;
            s.code = code;
            s.bold = bold;
            s.italic = italic;
            s.underlined = underlined;
            s.strike = strike;
            s.obf = obf;
            s.clickAction = clickAction;
            s.clickValue = clickValue;
            s.hover = hover;
            s.gradient = gradient;
            return s;
        }

        boolean same(Style o) {
            return o != null && eq(rgb, o.rgb) && code == o.code && eq(bold, o.bold) && eq(italic, o.italic)
                    && eq(underlined, o.underlined) && eq(strike, o.strike) && eq(obf, o.obf)
                    && eq(clickAction, o.clickAction) && eq(clickValue, o.clickValue) && eq(hover, o.hover);
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    static final class Span {
        final String text;
        final Style style;

        Span(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    /** One node of the parsed text: plain text or a tag with children. */
    private static final class Node {
        String text;                      // text node when not null
        String name;                      // tag name (for closing tags)
        boolean legacy;                   // opened by a & code
        Modifier mod;
        final List<Node> children = new ArrayList<>();
    }

    private interface Modifier {
        void apply(Style s, Node node);
    }

    private static class Gradient {
        final int[] colors;
        final int length;
        int index;

        Gradient(int[] colors, int length) {
            this.colors = colors;
            this.length = Math.max(1, length);
        }

        int next() {
            int i = index++;
            if (colors.length == 1) return colors[0];
            double t = length <= 1 ? 0 : (double) i / (length - 1);
            if (t > 1) t = 1;
            double pos = t * (colors.length - 1);
            int a = (int) Math.floor(pos);
            if (a >= colors.length - 1) return colors[colors.length - 1];
            double f = pos - a;
            return mix(colors[a], colors[a + 1], f);
        }
    }

    // ------------------------------------------------------------------ colors

    private static final Map<String, int[]> NAMED = new HashMap<>(); // name -> {rgb, code}
    private static final int[][] PALETTE = {
            {0x000000, '0'}, {0x0000AA, '1'}, {0x00AA00, '2'}, {0x00AAAA, '3'}, {0xAA0000, '4'}, {0xAA00AA, '5'},
            {0xFFAA00, '6'}, {0xAAAAAA, '7'}, {0x555555, '8'}, {0x5555FF, '9'}, {0x55FF55, 'a'}, {0x55FFFF, 'b'},
            {0xFF5555, 'c'}, {0xFF55FF, 'd'}, {0xFFFF55, 'e'}, {0xFFFFFF, 'f'}};

    static {
        String[] names = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        for (int i = 0; i < names.length; i++) NAMED.put(names[i], PALETTE[i]);
        NAMED.put("grey", PALETTE[7]);
        NAMED.put("dark_grey", PALETTE[8]);
    }

    private static int[] legacyColor(char c) {
        c = Character.toLowerCase(c);
        for (int[] p : PALETTE) if (p[1] == c) return p;
        return null;
    }

    static char nearest(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        long best = Long.MAX_VALUE;
        char code = 'f';
        for (int[] p : PALETTE) {
            int pr = (p[0] >> 16) & 0xFF, pg = (p[0] >> 8) & 0xFF, pb = p[0] & 0xFF;
            long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
            if (d < best) {
                best = d;
                code = (char) p[1];
            }
        }
        return code;
    }

    private static int mix(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static Integer parseColor(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT);
        int[] named = NAMED.get(v);
        if (named != null) return named[0];
        if (v.startsWith("&") || v.startsWith("§")) v = v.substring(1);
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() == 6) {
            try {
                return Integer.parseInt(v, 16);
            } catch (NumberFormatException ignored) {
            }
        }
        if (v.length() == 3) {
            try {
                int r = Integer.parseInt(v.substring(0, 1), 16), g = Integer.parseInt(v.substring(1, 2), 16), b = Integer.parseInt(v.substring(2, 3), 16);
                return (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static boolean hexAt(String s, int start) {
        if (start + 6 > s.length()) return false;
        for (int k = start; k < start + 6; k++) if (Character.digit(s.charAt(k), 16) < 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ parser

    /** Parses the text into styled pieces. */
    static List<Span> spans(String input) {
        List<Span> out = new ArrayList<>();
        if (input == null || input.isEmpty()) return out;
        Node root = parse(input);
        walk(root, new Style(), out);
        // merge pieces with the same style
        List<Span> merged = new ArrayList<>(out.size());
        for (Span s : out) {
            if (s.text.isEmpty()) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).style.same(s.style)) {
                Span last = merged.remove(merged.size() - 1);
                merged.add(new Span(last.text + s.text, last.style));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    private static Node parse(String s) {
        Node root = new Node();
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        StringBuilder text = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);

            // escaped tag: \<
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '<') {
                text.append('<');
                i++;
                continue;
            }

            // {#rrggbb}
            if (c == '{' && i + 8 < n && s.charAt(i + 1) == '#' && s.charAt(i + 8) == '}' && hexAt(s, i + 2)) {
                flush(text, stack);
                legacyHex(Integer.parseInt(s.substring(i + 2, i + 8), 16), stack);
                i += 8;
                continue;
            }

            if ((c == '&' || c == '§') && i + 1 < n) {
                char d = s.charAt(i + 1);
                // &#rrggbb
                if (d == '#' && hexAt(s, i + 2)) {
                    flush(text, stack);
                    legacyHex(Integer.parseInt(s.substring(i + 2, i + 8), 16), stack);
                    i += 7;
                    continue;
                }
                // &x&r&r&g&g&b&b
                if ((d == 'x' || d == 'X') && i + 13 < n) {
                    StringBuilder hex = new StringBuilder();
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int pos = i + 2 + k * 2;
                        char marker = s.charAt(pos);
                        char digit = s.charAt(pos + 1);
                        if ((marker != '&' && marker != '§') || Character.digit(digit, 16) < 0) {
                            ok = false;
                            break;
                        }
                        hex.append(digit);
                    }
                    if (ok) {
                        flush(text, stack);
                        legacyHex(Integer.parseInt(hex.toString(), 16), stack);
                        i += 13;
                        continue;
                    }
                }
                char lc = Character.toLowerCase(d);
                if ("0123456789abcdefklmnor".indexOf(lc) >= 0) {
                    flush(text, stack);
                    legacyCode(lc, stack);
                    i++;
                    continue;
                }
            }

            if (c == '<') {
                int end = tagEnd(s, i);
                if (end > i) {
                    String inner = s.substring(i + 1, end);
                    if (tag(inner, text, stack)) {
                        i = end;
                        continue;
                    }
                }
            }
            text.append(c);
        }
        flush(text, stack);
        return root;
    }

    private static void flush(StringBuilder text, List<Node> stack) {
        if (text.length() == 0) return;
        Node t = new Node();
        t.text = text.toString();
        stack.get(stack.size() - 1).children.add(t);
        text.setLength(0);
    }

    private static void push(List<Node> stack, String name, boolean legacy, Modifier mod) {
        Node node = new Node();
        node.name = name;
        node.legacy = legacy;
        node.mod = mod;
        stack.get(stack.size() - 1).children.add(node);
        stack.add(node);
    }

    /**
     * A legacy color closes the legacy colors/formats opened before it (like in the game),
     * but only inside the current tag: a color inside &lt;click&gt; or &lt;hover&gt; must not close them.
     */
    private static void closeLegacy(List<Node> stack) {
        while (stack.size() > 1 && stack.get(stack.size() - 1).legacy) stack.remove(stack.size() - 1);
    }

    private static void legacyHex(int rgb, List<Node> stack) {
        closeLegacy(stack);
        push(stack, "#", true, (st, node) -> {
            st.rgb = rgb;
            st.code = 0;
            st.gradient = null;
        });
    }

    private static void legacyCode(char c, List<Node> stack) {
        if (c == 'r') {
            while (stack.size() > 1) stack.remove(stack.size() - 1);
            return;
        }
        int[] color = legacyColor(c);
        if (color != null) {
            closeLegacy(stack);
            push(stack, "&" + c, true, (st, node) -> {
                st.rgb = color[0];
                st.code = (char) color[1];
                st.gradient = null;
            });
            return;
        }
        String deco = c == 'l' ? "bold" : c == 'o' ? "italic" : c == 'n' ? "underlined" : c == 'm' ? "strikethrough" : "obfuscated";
        push(stack, "&" + c, true, decoration(deco, true));
    }

    /** Index of the closing &gt; of the tag starting at i (quotes respected), -1 if none. */
    private static int tagEnd(String s, int i) {
        char quote = 0;
        for (int k = i + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '<') return -1;
            if (c == '>') return k;
        }
        return -1;
    }

    private static List<String> args(String inner) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == ':') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static String rest(List<String> a, int from) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < a.size(); k++) {
            if (k > from) sb.append(':');
            sb.append(a.get(k));
        }
        return sb.toString();
    }

    /** Handles one &lt;tag&gt;. Returns false when it is not a known tag (then it stays as text). */
    private static boolean tag(String inner, StringBuilder text, List<Node> stack) {
        if (inner.isEmpty()) return false;
        if (inner.charAt(0) == '/') {
            String closing = inner.substring(1);
            int colon = closing.indexOf(':');
            if (colon >= 0) closing = closing.substring(0, colon);
            String name = normalize(closing.trim().toLowerCase(Locale.ROOT));
            if (name.isEmpty()) {
                if (stack.size() <= 1) return true;
                flush(text, stack);
                stack.remove(stack.size() - 1);
                return true;
            }
            if (!known(name)) return false;
            flush(text, stack);
            for (int k = stack.size() - 1; k >= 1; k--) {
                if (name.equals(stack.get(k).name)) {
                    while (stack.size() > k) stack.remove(stack.size() - 1);
                    break;
                }
            }
            return true;
        }

        List<String> a = args(inner);
        String raw = a.get(0).trim().toLowerCase(Locale.ROOT);
        boolean negate = raw.startsWith("!");
        if (negate) raw = raw.substring(1);
        String name = normalize(raw);

        // old 3.x syntax: <GRADIENT:#aaaaaa-#bbbbbb>
        if (name.equals("gradient") && a.size() == 2 && a.get(1).contains("-")) {
            List<String> fixed = new ArrayList<>();
            fixed.add("gradient");
            for (String part : a.get(1).split("-")) fixed.add(part);
            a = fixed;
        }

        switch (name) {
            case "reset":
                flush(text, stack);
                while (stack.size() > 1) stack.remove(stack.size() - 1);
                return true;
            case "newline":
                text.append('\n');
                return true;
            case "bold":
            case "italic":
            case "underlined":
            case "strikethrough":
            case "obfuscated": {
                boolean value = !negate && !(a.size() > 1 && a.get(1).equalsIgnoreCase("false"));
                flush(text, stack);
                push(stack, name, false, decoration(name, value));
                return true;
            }
            case "color": {
                Integer rgb = a.size() > 1 ? parseColor(a.get(1)) : null;
                if (rgb == null) return false;
                flush(text, stack);
                pushColor(stack, "color", rgb, a.size() > 1 ? a.get(1) : "");
                return true;
            }
            case "gradient": {
                List<Integer> cols = new ArrayList<>();
                for (int k = 1; k < a.size(); k++) {
                    Integer rgb = parseColor(a.get(k));
                    if (rgb != null) cols.add(rgb);
                }
                if (cols.isEmpty()) {
                    cols.add(0xFFFFFF);
                    cols.add(0x000000);
                }
                int[] arr = new int[cols.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = cols.get(k);
                flush(text, stack);
                push(stack, "gradient", false, (st, node) -> {
                    st.rgb = null;
                    st.code = 0;
                    st.gradient = new Gradient(arr, length(node));
                });
                return true;
            }
            case "rainbow": {
                flush(text, stack);
                push(stack, "rainbow", false, (st, node) -> {
                    int len = Math.max(1, length(node));
                    int[] arr = new int[len];
                    for (int k = 0; k < len; k++) arr[k] = java.awt.Color.HSBtoRGB((float) k / len, 1f, 1f) & 0xFFFFFF;
                    st.rgb = null;
                    st.code = 0;
                    st.gradient = new Gradient(arr, len) {
                        @Override
                        int next() {
                            return colors[Math.min(index++, colors.length - 1)];
                        }
                    };
                });
                return true;
            }
            case "click": {
                if (a.size() < 3) return false;
                String action = a.get(1).toLowerCase(Locale.ROOT);
                String value = rest(a, 2);
                flush(text, stack);
                push(stack, "click", false, (st, node) -> {
                    st.clickAction = action;
                    st.clickValue = value;
                });
                return true;
            }
            case "hover": {
                if (a.size() < 3) return false;
                String value = rest(a, 2);
                flush(text, stack);
                push(stack, "hover", false, (st, node) -> st.hover = value);
                return true;
            }
            case "font":
            case "insertion":
                flush(text, stack);
                push(stack, name, false, (st, node) -> {
                });
                return true;
            default:
                break;
        }

        Integer rgb = parseColor(raw);
        if (rgb != null && (raw.startsWith("#") || NAMED.containsKey(raw))) {
            flush(text, stack);
            pushColor(stack, raw, rgb, raw);
            return true;
        }
        return false;
    }

    private static void pushColor(List<Node> stack, String name, int rgb, String rawName) {
        int[] named = NAMED.get(rawName.trim().toLowerCase(Locale.ROOT));
        char code = named != null ? (char) named[1] : 0;
        push(stack, name, false, (st, node) -> {
            st.rgb = rgb;
            st.code = code;
            st.gradient = null;
        });
    }

    private static String normalize(String name) {
        switch (name) {
            case "b": return "bold";
            case "i":
            case "em": return "italic";
            case "u":
            case "underline": return "underlined";
            case "st":
            case "strike": return "strikethrough";
            case "obf": return "obfuscated";
            case "br": return "newline";
            case "c":
            case "colour": return "color";
            default: return name;
        }
    }

    private static boolean known(String name) {
        switch (name) {
            case "bold": case "italic": case "underlined": case "strikethrough": case "obfuscated":
            case "color": case "gradient": case "rainbow": case "click": case "hover": case "font": case "insertion":
                return true;
            default:
                return NAMED.containsKey(name) || (name.startsWith("#") && parseColor(name) != null);
        }
    }

    private static Modifier decoration(String name, boolean value) {
        Boolean v = value;
        switch (name) {
            case "bold": return (st, node) -> st.bold = v;
            case "italic": return (st, node) -> st.italic = v;
            case "underlined": return (st, node) -> st.underlined = v;
            case "strikethrough": return (st, node) -> st.strike = v;
            default: return (st, node) -> st.obf = v;
        }
    }

    private static int length(Node node) {
        if (node.text != null) return node.text.codePointCount(0, node.text.length());
        int sum = 0;
        for (Node c : node.children) sum += length(c);
        return sum;
    }

    private static void walk(Node node, Style inherited, List<Span> out) {
        if (node.text != null) {
            if (inherited.gradient != null) {
                Gradient g = inherited.gradient;
                String t = node.text;
                for (int k = 0; k < t.length(); ) {
                    int cp = t.codePointAt(k);
                    int len = Character.charCount(cp);
                    Style s = inherited.copy();
                    s.gradient = null;
                    s.rgb = g.next();
                    s.code = 0;
                    out.add(new Span(t.substring(k, k + len), s));
                    k += len;
                }
            } else {
                Style s = inherited.copy();
                s.gradient = null;
                out.add(new Span(node.text, s));
            }
            return;
        }
        Style style = inherited;
        if (node.mod != null) {
            style = inherited.copy();
            node.mod.apply(style, node);
        }
        for (Node c : node.children) walk(c, style, out);
    }

    // ------------------------------------------------------------------ output

    private static String render(List<Span> spans, boolean hex) {
        StringBuilder sb = new StringBuilder();
        Style prev = null;
        for (Span sp : spans) {
            Style s = sp.style;
            if (prev == null || !sameLook(prev, s)) {
                if (s.rgb != null) {
                    if (s.code != 0) {
                        sb.append('§').append(s.code);
                    } else if (hex) {
                        String h = String.format("%06x", s.rgb);
                        sb.append("§x");
                        for (int k = 0; k < 6; k++) sb.append('§').append(h.charAt(k));
                    } else {
                        sb.append('§').append(nearest(s.rgb));
                    }
                } else if (prev != null) {
                    sb.append("§r");
                }
                if (Boolean.TRUE.equals(s.obf)) sb.append("§k");
                if (Boolean.TRUE.equals(s.bold)) sb.append("§l");
                if (Boolean.TRUE.equals(s.strike)) sb.append("§m");
                if (Boolean.TRUE.equals(s.underlined)) sb.append("§n");
                if (Boolean.TRUE.equals(s.italic)) sb.append("§o");
            }
            sb.append(sp.text);
            prev = s;
        }
        return sb.toString();
    }

    private static boolean sameLook(Style a, Style b) {
        return (a.rgb == null ? b.rgb == null : a.rgb.equals(b.rgb)) && a.code == b.code
                && Boolean.TRUE.equals(a.bold) == Boolean.TRUE.equals(b.bold)
                && Boolean.TRUE.equals(a.italic) == Boolean.TRUE.equals(b.italic)
                && Boolean.TRUE.equals(a.underlined) == Boolean.TRUE.equals(b.underlined)
                && Boolean.TRUE.equals(a.strike) == Boolean.TRUE.equals(b.strike)
                && Boolean.TRUE.equals(a.obf) == Boolean.TRUE.equals(b.obf);
    }

    private static Method hexColor;
    private static boolean hexChecked;

    private static ChatColor chatColor(Style s) {
        if (s.code != 0) return ChatColor.getByChar(s.code);
        if (Ver.hex()) {
            if (!hexChecked) {
                hexChecked = true;
                try {
                    hexColor = ChatColor.class.getMethod("of", String.class);
                } catch (Throwable ignored) {
                }
            }
            if (hexColor != null) {
                try {
                    return (ChatColor) hexColor.invoke(null, String.format("#%06x", s.rgb));
                } catch (Throwable ignored) {
                }
            }
        }
        return ChatColor.getByChar(nearest(s.rgb));
    }

    @SuppressWarnings("deprecation")
    private static BaseComponent bungee(Span sp) {
        Style s = sp.style;
        TextComponent c = new TextComponent(sp.text);
        if (s.rgb != null) c.setColor(chatColor(s));
        else c.setColor(ChatColor.WHITE);
        // explicit values: old clients pass the style of one piece to the next one
        c.setBold(Boolean.TRUE.equals(s.bold));
        c.setItalic(Boolean.TRUE.equals(s.italic));
        c.setUnderlined(Boolean.TRUE.equals(s.underlined));
        c.setStrikethrough(Boolean.TRUE.equals(s.strike));
        c.setObfuscated(Boolean.TRUE.equals(s.obf));
        if (s.clickAction != null) {
            ClickEvent.Action action = clickAction(s.clickAction);
            if (action != null) c.setClickEvent(new ClickEvent(action, s.clickValue));
        }
        if (s.hover != null) {
            c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[]{component(s.hover)}));
        }
        return c;
    }

    private static ClickEvent.Action clickAction(String a) {
        String name;
        switch (a) {
            case "run_command": name = "RUN_COMMAND"; break;
            case "suggest_command": name = "SUGGEST_COMMAND"; break;
            case "open_url": name = "OPEN_URL"; break;
            case "copy_to_clipboard": name = "COPY_TO_CLIPBOARD"; break;
            case "change_page": name = "CHANGE_PAGE"; break;
            default: return null;
        }
        try {
            return ClickEvent.Action.valueOf(name);
        } catch (IllegalArgumentException e) {
            // copy_to_clipboard is 1.15+: older versions put the text in the chat bar
            return ClickEvent.Action.SUGGEST_COMMAND;
        }
    }
}

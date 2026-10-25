package dev.tmdevelopment.flaretokens.action;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.config.Lang;
import dev.tmdevelopment.flaretokens.hook.VaultHook;
import dev.tmdevelopment.flaretokens.menu.MenuSession;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import dev.tmdevelopment.flaretokens.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The action language of the shops and drops. Same syntax as FlareTokens 3.x:
 * <pre>
 *   - "[buy(tokens)] 100"                     stop here if the player has less than 100 tokens
 *   - "[CONSOLE] give {player} diamond 1"
 *   - "[MESSAGE] &amp;aThanks!"
 *   - if
 *   - "check(expression;{tokens};>=;500)"
 *   - do
 *   - "[MESSAGE] rich"
 *   - else
 *   - "[MESSAGE] poor"
 *   - end
 * </pre>
 * The full list is in shops/main.yml.
 */
public final class Actions {

    /** Result of one step. */
    private enum State { NEXT, STOP }

    private static final Pattern TAG = Pattern.compile("^\\s*\\[([^\\]]+)]\\s?(.*)$", Pattern.DOTALL);
    private static final Pattern KV = Pattern.compile("(\\w+)=(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern OLD_PERMISSION = Pattern.compile("(?i)\\[?(require|check)\\((!)?permission,(.*?)\\)]?");
    private static final Pattern OLD_EXPRESSION_1 = Pattern.compile("(?i)\\[?(require|check)\\(expression;(.*?);(.*?);(.*?)\\)]?");
    private static final Pattern OLD_EXPRESSION_2 = Pattern.compile("(?i)\\[?(require|check)\\(expression\\)]?\\s*(\\S*) (\\S*) (\\S*)");
    private static final Pattern OLD_MONEY_1 = Pattern.compile("(?i)\\[?(require|check)\\(money;(.*?)\\)]?");
    private static final Pattern OLD_MONEY_2 = Pattern.compile("(?i)\\[?(require|check)\\(money\\)]?\\s*amount=(\\S+)(\\s+remove=(\\S+))?");
    private static final Pattern OLD_TOKENS = Pattern.compile("(?i)\\[?(require|check)\\(tokens;(.*?)\\)]?");

    private static final Set<String> warned = Collections.synchronizedSet(new HashSet<>());

    private Actions() {
    }

    /** Where the actions run (for messages, [close], [refresh] and placeholders like {price}). */
    public static final class Context {
        final String cause;
        final MenuSession session;
        final String[] pairs;

        public Context(String cause, MenuSession session, String... pairs) {
            this.cause = cause;
            this.session = session;
            this.pairs = pairs;
        }
    }

    // ------------------------------------------------------------------ running

    /** Runs a list of actions (with if/do/else/end blocks). */
    public static void run(Player p, List<String> lines, Context ctx) {
        if (lines == null || lines.isEmpty() || p == null) return;
        run(p, compile(lines), 0, ctx);
    }

    private static void run(Player p, List<Step> steps, int from, Context ctx) {
        for (int i = from; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.delay > 0) {
                int next = i + 1;
                Bukkit.getScheduler().runTaskLater(FlareTokens.get(), () -> {
                    if (p.isOnline()) run(p, steps, next, ctx);
                }, s.delay);
                return;
            }
            if (s.block != null) {
                boolean ok = true;
                for (String c : s.block.conditions) {
                    if (!condition(p, c, ctx)) {
                        ok = false;
                        break;
                    }
                }
                List<String> branch = ok ? s.block.then : s.block.otherwise;
                if (!branch.isEmpty()) run(p, compile(branch), 0, ctx);
                continue;
            }
            if (isCondition(s.line)) {
                if (!condition(p, s.line, ctx)) return; // a check on its own stops the rest when false
                continue;
            }
            if (action(p, s.line, ctx) == State.STOP) return;
        }
    }

    /** All conditions true (view requirements, open requirements). Empty list = true. */
    public static boolean check(Player p, List<String> conditions, Context ctx) {
        if (conditions == null) return true;
        for (String c : conditions) if (!condition(p, c, ctx)) return false;
        return true;
    }

    // ------------------------------------------------------------------ compiling

    private static final class Step {
        String line;
        Block block;
        long delay;
    }

    private static final class Block {
        final List<String> conditions = new ArrayList<>();
        final List<String> then = new ArrayList<>();
        final List<String> otherwise = new ArrayList<>();
    }

    private static List<Step> compile(List<String> lines) {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("if")) {
                Block b = new Block();
                int depth = 0;
                int mode = 0; // 0 conditions, 1 then, 2 else
                for (i = i + 1; i < lines.size(); i++) {
                    String l = lines.get(i) == null ? "" : lines.get(i).trim();
                    if (l.equalsIgnoreCase("if")) depth++;
                    if (depth == 0 && mode == 0 && l.equalsIgnoreCase("do")) {
                        mode = 1;
                        continue;
                    }
                    if (depth == 0 && mode == 1 && l.equalsIgnoreCase("else")) {
                        mode = 2;
                        continue;
                    }
                    if (l.equalsIgnoreCase("end")) {
                        if (depth == 0) break;
                        depth--;
                    }
                    (mode == 0 ? b.conditions : mode == 1 ? b.then : b.otherwise).add(l);
                }
                Step s = new Step();
                s.block = b;
                out.add(s);
                continue;
            }
            Matcher m = TAG.matcher(line);
            if (m.find() && m.group(1).trim().equalsIgnoreCase("delay")) {
                Step s = new Step();
                s.delay = Math.max(1, Num.parseInt(m.group(2), 20));
                out.add(s);
                continue;
            }
            Step s = new Step();
            s.line = line;
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ conditions

    private static boolean isCondition(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        if (l.startsWith("check(") || l.startsWith("require(") || l.startsWith("[check(") || l.startsWith("[require(")) return true;
        Matcher m = TAG.matcher(line);
        if (!m.find()) return false;
        String t = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("!")) t = t.substring(1);
        return t.equals("has_tokens") || t.equals("has_money") || t.equals("has_permission") || t.equals("permission")
                || t.equals("expression") || t.equals("check");
    }

    public static boolean condition(Player p, String raw, Context ctx) {
        String line = resolve(p, raw, ctx).trim();
        Matcher m;
        if ((m = OLD_PERMISSION.matcher(line)).find()) {
            boolean has = p.hasPermission(m.group(3).trim());
            return m.group(2) != null ? !has : has;
        }
        if ((m = OLD_EXPRESSION_1.matcher(line)).find()) return compare(m.group(2).trim(), m.group(3).trim(), m.group(4).trim());
        if ((m = OLD_EXPRESSION_2.matcher(line)).find()) return compare(m.group(2).trim(), m.group(3).trim(), m.group(4).trim());
        if ((m = OLD_MONEY_2.matcher(line)).find()) {
            double amount = Num.parseDouble(m.group(2), 0);
            if (!VaultHook.has(p, amount)) return false;
            if (m.group(4) != null && Boolean.parseBoolean(m.group(4))) VaultHook.withdraw(p, amount);
            return true;
        }
        if ((m = OLD_MONEY_1.matcher(line)).find()) return VaultHook.has(p, Num.parseDouble(m.group(2), 0));
        if ((m = OLD_TOKENS.matcher(line)).find()) return FlareTokens.get().tokens().balance(p.getUniqueId()) >= Num.parse(m.group(2));
        if (line.toLowerCase(Locale.ROOT).contains("javascript")) {
            warnOnce("javascript", "check(javascript,...) is not supported anymore (Java removed its JavaScript engine). Use check(expression;a;op;b).");
            return true;
        }

        m = TAG.matcher(line);
        if (!m.find()) return true;
        String tag = m.group(1).trim().toLowerCase(Locale.ROOT);
        String arg = m.group(2).trim();
        boolean not = tag.startsWith("!");
        if (not) tag = tag.substring(1);
        boolean result;
        switch (tag) {
            case "has_tokens":
                result = FlareTokens.get().tokens().balance(p.getUniqueId()) >= Math.max(0, Num.parse(arg));
                break;
            case "has_money":
                result = VaultHook.has(p, Num.parseDouble(arg, 0));
                break;
            case "has_permission":
            case "permission":
                result = p.hasPermission(arg);
                break;
            case "expression":
            case "check": {
                String[] parts = arg.split("\\s+");
                result = parts.length >= 3 && compare(parts[0], parts[1], join(parts, 2));
                break;
            }
            default:
                return true;
        }
        return not != result;
    }

    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static boolean compare(String a, String op, String b) {
        String pa = Text.plain(a).replace(",", "");
        String pb = Text.plain(b).replace(",", "");
        switch (op) {
            case "==":
            case "=":
                return number(pa) != null && number(pb) != null ? number(pa).compareTo(number(pb)) == 0 : pa.equals(pb);
            case "!=":
                return number(pa) != null && number(pb) != null ? number(pa).compareTo(number(pb)) != 0 : !pa.equals(pb);
            case "equalsignorecase":
                return pa.equalsIgnoreCase(pb);
            default:
                BigDecimal x = number(pa), y = number(pb);
                if (x == null || y == null) return false;
                int c = x.compareTo(y);
                switch (op) {
                    case ">": return c > 0;
                    case "<": return c < 0;
                    case ">=": return c >= 0;
                    case "<=": return c <= 0;
                    default:
                        warnOnce("op:" + op, "Unknown operator '" + op + "' in a check (use == != > < >= <=).");
                        return false;
                }
        }
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ actions

    private static String resolve(Player p, String s, Context ctx) {
        return Placeholders.apply(s, p, ctx == null ? new String[0] : ctx.pairs);
    }

    private static State action(Player p, String raw, Context ctx) {
        Matcher m = TAG.matcher(raw);
        if (!m.find()) {
            warnOnce("noTag:" + raw, "Action without [type]: '" + raw + "' (example: \"[CONSOLE] say hi\").");
            return State.NEXT;
        }
        String tag = m.group(1).trim();
        String lower = tag.toLowerCase(Locale.ROOT);
        String arg = m.group(2);
        FlareTokens plugin = FlareTokens.get();
        String cause = ctx == null ? "action" : ctx.cause;

        // [buy(tokens)] 100   [buy(money)] 50   [buy] 100
        if (lower.startsWith("buy")) {
            String currency = lower.contains("(") ? lower.substring(lower.indexOf('(') + 1, lower.lastIndexOf(')') < 0 ? lower.length() : lower.lastIndexOf(')')) : "tokens";
            String value = resolve(p, arg, ctx).trim();
            if (currency.equals("money")) {
                double price = Num.parseDouble(value, -1);
                if (price < 0) return bad(raw);
                if (!VaultHook.available()) {
                    warnOnce("vault", "[buy(money)] needs Vault and an economy plugin.");
                    return State.STOP;
                }
                if (!VaultHook.has(p, price)) {
                    plugin.lang().send(p, "shop.not-enough-money", "{price}", Num.commas((long) price), "{amount}", value);
                    sound(p, "sounds.error");
                    return State.STOP;
                }
                VaultHook.withdraw(p, price);
                return State.NEXT;
            }
            if (!currency.equals("tokens") && !currency.equals("token")) {
                warnOnce("buy:" + currency, "[buy(" + currency + ")] is not supported: use [buy(tokens)] or [buy(money)].");
                return State.STOP;
            }
            long price = Num.parse(value);
            if (price < 0) return bad(raw);
            if (!plugin.tokens().spend(p, price, cause)) {
                long has = plugin.tokens().balance(p.getUniqueId());
                plugin.lang().send(p, "shop.not-enough", "{price}", Num.commas(price), "{amount}", Num.commas(price),
                        "{missing}", Num.commas(Math.max(0, price - has)));
                sound(p, "sounds.error");
                return State.STOP;
            }
            return State.NEXT;
        }

        // [tokens_give] 10   [tokens_take] 10   [tokens_set] 10
        if (lower.startsWith("tokens_") || lower.startsWith("token_")) {
            String op = lower.substring(lower.indexOf('_') + 1);
            long amount = Num.roll(resolve(p, arg, ctx));
            switch (op) {
                case "give":
                case "add":
                    plugin.tokens().give(p.getUniqueId(), p.getName(), amount, cause);
                    break;
                case "remove":
                case "take":
                    plugin.tokens().take(p.getUniqueId(), p.getName(), amount, false, cause);
                    break;
                case "set":
                    plugin.tokens().set(p.getUniqueId(), p.getName(), amount, cause);
                    break;
                default:
                    return bad(raw);
            }
            return State.NEXT;
        }

        // [money_give] 10 ...
        if (lower.startsWith("money_")) {
            if (!VaultHook.available()) {
                warnOnce("vault", "[money_...] needs Vault and an economy plugin.");
                return State.NEXT;
            }
            double amount = Num.parseDouble(resolve(p, arg, ctx), 0);
            String op = lower.substring(6);
            if (op.equals("give") || op.equals("add")) VaultHook.deposit(p, amount);
            else if (op.equals("remove") || op.equals("take")) VaultHook.withdraw(p, amount);
            else if (op.equals("set")) VaultHook.set(p, amount);
            return State.NEXT;
        }

        // [message(broadcast)] / [message(nopapi)]
        if (lower.startsWith("message(")) {
            String opt = lower.substring(8);
            if (opt.contains("broadcast")) {
                Lang.broadcast(resolve(p, arg, ctx));
            } else {
                Lang.deliver(p, Placeholders.pairs(arg, ctx == null ? new String[0] : ctx.pairs).replace("{player}", p.getName()));
            }
            return State.NEXT;
        }

        switch (lower) {
            case "console":
            case "console_command":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(resolve(p, arg, ctx)));
                return State.NEXT;
            case "player":
            case "command":
            case "player_command":
                p.performCommand(stripSlash(resolve(p, arg, ctx)));
                return State.NEXT;
            case "sudo":
            case "chat":
                p.chat(resolve(p, arg, ctx));
                return State.NEXT;
            case "message":
            case "msg":
                Lang.deliver(p, resolve(p, arg, ctx));
                return State.NEXT;
            case "broadcast":
                Lang.broadcast(resolve(p, arg, ctx));
                return State.NEXT;
            case "actionbar":
            case "action_bar":
                Compat.actionBar(p, Text.legacy(resolve(p, arg, ctx)));
                return State.NEXT;
            case "title":
                title(p, resolve(p, arg, ctx));
                return State.NEXT;
            case "sound":
                soundAction(p, resolve(p, arg, ctx));
                return State.NEXT;
            case "effect":
                effect(p, resolve(p, arg, ctx), raw);
                return State.NEXT;
            case "bossbar":
            case "boss_bar":
                if (Ver.atLeast(9)) BossBars.show(p, resolve(p, arg, ctx));
                return State.NEXT;
            case "switch_server":
            case "server":
            case "connect":
                connect(p, resolve(p, arg, ctx).trim());
                return State.NEXT;
            case "close":
            case "exit":
            case "close_menu":
            case "bye":
            case "leave":
            case "stop":
                closeLater(p);
                return State.NEXT;
            case "refresh":
            case "update":
                if (ctx != null && ctx.session != null) ctx.session.refresh();
                return State.NEXT;
            case "open":
            case "open_menu":
            case "open_shop":
            case "openmenu":
            case "openshop":
            case "menu":
            case "shop": {
                String name = resolve(p, arg, ctx).trim();
                // the menu it comes from: [back] in the new menu goes there
                plugin.menus().openLater(p, name, ctx == null ? null : ctx.session);
                return State.NEXT;
            }
            case "back":
                if (ctx != null && ctx.session != null) ctx.session.back();
                else closeLater(p);
                return State.NEXT;
            case "op":
                warnOnce("op", "[op] is not supported for safety reasons. Use [CONSOLE] with {player}.");
                return State.NEXT;
            default:
                if (lower.startsWith("op(")) {
                    warnOnce("op", "[op(...)] is not supported for safety reasons. Use [CONSOLE] with {player}.");
                    return State.NEXT;
                }
                warnOnce("unknown:" + lower, "Unknown action [" + tag + "] in: " + raw);
                return State.NEXT;
        }
    }

    /**
     * Closes the menu on the next tick (never inside its click event), only if it is still the same menu:
     * another plugin may open its own window in the same tick.
     */
    public static void closeLater(Player p) {
        org.bukkit.inventory.InventoryHolder now = Compat.topHolder(p);
        Bukkit.getScheduler().runTask(FlareTokens.get(), () -> {
            if (p.isOnline() && Compat.topHolder(p) == now) p.closeInventory();
        });
    }

    private static State bad(String raw) {
        warnOnce("bad:" + raw, "Wrong value in the action: " + raw);
        return State.STOP;
    }

    private static String stripSlash(String s) {
        String t = s.trim();
        return t.startsWith("/") ? t.substring(1) : t;
    }

    public static void sound(Player p, String configPath) {
        FlareTokens plugin = FlareTokens.get();
        if (!plugin.getConfig().getBoolean("sounds.enabled", true)) return;
        String s = plugin.getConfig().getString(configPath, "");
        if (s != null && !s.isEmpty()) Compat.sound(p, s, 1f, 1f);
    }

    /** "title=&amp;aHi subtitle=&amp;7there" (3.x) or "title;subtitle;fadeIn;stay;fadeOut" (seconds). */
    private static void title(Player p, String arg) {
        String title;
        String subtitle = "";
        int in = 10, stay = 40, out = 10;
        String lower = arg.toLowerCase(Locale.ROOT);
        if (lower.startsWith("title=") || lower.startsWith("subtitle=")) {
            int sub = lower.indexOf("subtitle=");
            if (lower.startsWith("subtitle=")) {
                title = "";
                subtitle = arg.substring(9);
            } else if (sub > 0) {
                title = arg.substring(6, sub).trim();
                subtitle = arg.substring(sub + 9).trim();
            } else {
                title = arg.substring(6);
            }
        } else {
            String[] parts = arg.split(";");
            title = parts[0];
            if (parts.length > 1) subtitle = parts[1];
            if (parts.length > 4) {
                in = (int) (Num.parseDouble(parts[2], 0.5) * 20);
                stay = (int) (Num.parseDouble(parts[3], 2) * 20);
                out = (int) (Num.parseDouble(parts[4], 0.5) * 20);
            }
        }
        Compat.title(p, Text.legacy(title), Text.legacy(subtitle), in, stay, out);
    }

    /** "sound=NAME volume=1 pitch=1" (3.x) or "NAME 1 1" / "NAME;1;1". */
    private static void soundAction(Player p, String arg) {
        String name;
        float volume = 1f, pitch = 1f;
        if (arg.toLowerCase(Locale.ROOT).contains("sound=")) {
            Matcher kv = KV.matcher(arg);
            name = null;
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                if (k.equals("sound")) name = v;
                else if (k.equals("volume")) volume = (float) Num.parseDouble(v, 1);
                else if (k.equals("pitch")) pitch = (float) Num.parseDouble(v, 1);
            }
        } else {
            String[] parts = arg.trim().split("[;\\s]+");
            name = parts[0];
            if (parts.length > 1) volume = (float) Num.parseDouble(parts[1], 1);
            if (parts.length > 2) pitch = (float) Num.parseDouble(parts[2], 1);
        }
        if (name != null) Compat.sound(p, name, volume, pitch);
    }

    /** "effect=SPEED duration=200 amplifier=1" (3.x, ticks) or "SPEED;seconds;level". */
    private static void effect(Player p, String arg, String raw) {
        String name;
        int ticks = 200, amp = 0;
        if (arg.toLowerCase(Locale.ROOT).contains("effect=")) {
            Matcher kv = KV.matcher(arg);
            name = null;
            while (kv.find()) {
                String k = kv.group(1).toLowerCase(Locale.ROOT);
                String v = unquote(kv.group(2));
                if (k.equals("effect")) name = v;
                else if (k.equals("duration")) ticks = (int) Num.parseDouble(v, 200);
                else if (k.equals("amplifier")) amp = (int) Num.parseDouble(v, 0);
            }
        } else {
            String[] parts = arg.trim().split("[;\\s]+");
            name = parts[0];
            if (parts.length > 1) ticks = (int) (Num.parseDouble(parts[1], 10) * 20);
            if (parts.length > 2) amp = Math.max(0, (int) Num.parseDouble(parts[2], 1) - 1);
        }
        PotionEffectType type = Compat.effect(name);
        if (type == null) {
            warnOnce("effect:" + name, "Unknown effect in: " + raw);
            return;
        }
        p.addPotionEffect(new PotionEffect(type, Math.max(1, ticks), Math.max(0, amp)), true);
    }

    private static void connect(Player p, String server) {
        if (server.isEmpty()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(server);
            p.sendPluginMessage(FlareTokens.get(), "BungeeCord", bytes.toByteArray());
        } catch (Exception e) {
            warnOnce("connect", "Could not send the player to " + server + ": " + e.getMessage());
        }
    }

    static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) return v.substring(1, v.length() - 1);
        return v;
    }

    static void warnOnce(String key, String message) {
        if (warned.add(key)) FlareTokens.get().getLogger().warning(message);
    }
}

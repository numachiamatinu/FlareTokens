package dev.tmdevelopment.flaretokens.util;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.hook.PapiHook;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Internal placeholders, in the new style {name} and in the old 3.x style %pl_name%:
 * {player} {tokens} {tokens_formatted} {tokens_commas} {prefix} {world} {version}
 * plus every PlaceholderAPI placeholder when it is installed.
 */
public final class Placeholders {

    private static final java.util.regex.Pattern MISSING = java.util.regex.Pattern.compile("\\{missing:([0-9.,kKmMbB]+)}");
    private static boolean papi;

    private Placeholders() {
    }

    public static void setPapi(boolean enabled) {
        papi = enabled;
    }

    public static boolean papi() {
        return papi;
    }

    /**
     * Replaces pairs (key, value, key, value...). Write keys as "{amount}": "%pl_amount%" is replaced too.
     */
    public static String pairs(String s, String... pairs) {
        if (s == null) return "";
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String k = pairs[i];
            String v = pairs[i + 1] == null ? "" : pairs[i + 1];
            if (s.indexOf(k) >= 0) s = s.replace(k, v);
            if (k.length() > 2 && k.charAt(0) == '{' && k.charAt(k.length() - 1) == '}') {
                String old = "%pl_" + k.substring(1, k.length() - 1) + "%";
                if (s.indexOf(old) >= 0) s = s.replace(old, v);
            }
        }
        return s;
    }

    /** Full resolution: given pairs, then the internal placeholders of the player, then PlaceholderAPI. */
    public static String apply(String s, OfflinePlayer p, String... pairs) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        s = pairs(s, pairs);
        FlareTokens plugin = FlareTokens.get();
        if (plugin != null) {
            s = pairs(s, "{prefix}", plugin.lang().prefix(), "{version}", plugin.getDescription().getVersion());
            if (p != null && (s.indexOf('{') >= 0 || s.contains("%pl_"))) {
                long t = plugin.tokens() == null ? 0 : plugin.tokens().balanceForDisplay(p.getUniqueId());
                s = pairs(s,
                        "{player}", p.getName(),
                        "{tokens_formatted}", Num.shortFormat(t),
                        "{tokens_commas}", Num.commas(t),
                        "{tokens}", String.valueOf(t),
                        "{world}", p instanceof Player ? ((Player) p).getWorld().getName() : "");
                if (s.contains("{missing:")) {
                    java.util.regex.Matcher m = MISSING.matcher(s);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        long need = Num.parse(m.group(1));
                        m.appendReplacement(sb, Num.commas(Math.max(0, need - t)));
                    }
                    m.appendTail(sb);
                    s = sb.toString();
                }
            }
        }
        // offline players: the LuckPerms expansion gives nothing, their rank comes from LuckPerms itself
        if (p != null && s.contains("%luckperms_") && (!papi || !p.isOnline())) {
            s = dev.tmdevelopment.flaretokens.hook.LuckPermsHook.fill(p.getUniqueId(), s);
        }
        if (papi && p != null && s.indexOf('%') >= 0) s = PapiHook.apply(p, s);
        return s;
    }
}

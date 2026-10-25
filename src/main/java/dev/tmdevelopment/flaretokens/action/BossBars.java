package dev.tmdevelopment.flaretokens.action;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [BOSSBAR] action, 1.9+ only (this class is never loaded on 1.8).
 * 3.x style: message="text" screen_time="5" bar_color="RED" bar_style="SOLID"   (screen_time in seconds)
 * short style: text;seconds;COLOR;STYLE
 */
final class BossBars {

    private static final Pattern KV = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private BossBars() {
    }

    static void show(Player p, String arg) {
        String text = arg;
        double seconds = 5;
        String color = "PURPLE";
        String style = "SOLID";
        if (arg.contains("=\"")) {
            Matcher m = KV.matcher(arg);
            while (m.find()) {
                String k = m.group(1).toLowerCase(Locale.ROOT);
                String v = m.group(2);
                if (k.equals("message")) text = v;
                else if (k.equals("screen_time")) seconds = parse(v, 5);
                else if (k.equals("bar_color")) color = v;
                else if (k.equals("bar_style")) style = v;
            }
        } else {
            String[] parts = arg.split(";");
            text = parts[0];
            if (parts.length > 1) seconds = parse(parts[1], 5);
            if (parts.length > 2) color = parts[2].trim();
            if (parts.length > 3) style = parts[3].trim();
        }
        if (seconds <= 0) seconds = 5;
        BarColor c;
        BarStyle s;
        try {
            c = BarColor.valueOf(color.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            c = BarColor.PURPLE;
        }
        try {
            s = BarStyle.valueOf(style.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            s = BarStyle.SOLID;
        }
        BossBar bar = Bukkit.createBossBar(Text.legacy(text), c, s);
        bar.addPlayer(p);
        bar.setVisible(true);
        long ticks = Math.max(1, Math.round(seconds * 20));
        Bukkit.getScheduler().runTaskLater(FlareTokens.get(), bar::removeAll, ticks);
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}

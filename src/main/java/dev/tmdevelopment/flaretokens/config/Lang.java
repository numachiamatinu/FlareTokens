package dev.tmdevelopment.flaretokens.config;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.Headers;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat messages from lang/&lt;language&gt;.yml. A message is one line or a list of lines; "" or [] turns it off.
 * Messages added by newer versions are written into the file by themselves, edited ones are never touched.
 */
public final class Lang {

    private static final String[] BUNDLED = {"en"};

    private final FlareTokens plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    /** Texts of the admin menus: menus/admin.yml (paths "gui.*"), like the other Flare plugins. */
    private YamlConfiguration admin = new YamlConfiguration();
    private String prefix = "";

    public Lang(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Could not create the lang folder");
        for (String b : BUNDLED) {
            if (!new File(dir, b + ".yml").exists()) plugin.saveResource("lang/" + b + ".yml", false);
        }
        String language = plugin.getConfig().getString("language", "en");
        File file = new File(dir, language + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("lang/" + language + ".yml not found, using en.yml");
            file = new File(dir, "en.yml");
        }
        Headers.sync(plugin, file, "lang/en.yml");
        cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(defaults);
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    if (defaults.isConfigurationSection(key)) continue;
                    if (!cfg.isSet(key)) {
                        cfg.set(key, defaults.get(key));
                        changed = true;
                    }
                }
                if (changed) {
                    cfg.save(file);
                    plugin.getLogger().info("New messages added to lang/" + file.getName());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the default messages: " + e.getMessage());
        }
        prefix = cfg.getString("prefix", "");
        loadAdmin();
    }

    private void loadAdmin() {
        File f = new File(plugin.getDataFolder(), "menus/admin.yml");
        if (!f.exists()) plugin.saveResource("menus/admin.yml", false);
        Headers.sync(plugin, f, "menus/admin.yml");
        admin = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource("menus/admin.yml")) {
            if (in == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            admin.setDefaults(defaults);
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || admin.isSet(key)) continue;
                admin.set(key, defaults.get(key));
                changed = true;
            }
            if (changed) admin.save(f);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the default texts of menus/admin.yml: " + e.getMessage());
        }
    }

    private Object value(String path) {
        if (path.startsWith("gui.")) return admin.get(path.substring(4));
        return cfg.get(path);
    }

    public String prefix() {
        return prefix;
    }

    /** First line of a message, raw. */
    public String raw(String path) {
        Object v = value(path);
        if (v instanceof List<?>) {
            List<?> l = (List<?>) v;
            return l.isEmpty() ? "" : String.valueOf(l.get(0));
        }
        return v == null ? "&cMissing message: " + path : String.valueOf(v);
    }

    public List<String> lines(String path) {
        List<String> out = new ArrayList<>();
        Object v = value(path);
        if (v instanceof List<?>) {
            for (Object o : (List<?>) v) out.add(String.valueOf(o));
        } else if (v == null) {
            out.add("&cMissing message: " + path);
        } else if (!String.valueOf(v).isEmpty()) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    /** Lines of a text with "{key}", "value" pairs already replaced. */
    public List<String> lines(String path, String... pairs) {
        List<String> out = new java.util.ArrayList<>();
        for (String l : lines(path)) out.add(dev.tmdevelopment.flaretokens.util.Placeholders.pairs(l, pairs));
        return out;
    }

    public boolean isSet(String path) {
        return cfg.isSet(path);
    }

    /** Sends a message; pairs: "{player}", "Steve", "{amount}", "10"... */
    public void send(CommandSender to, String path, String... pairs) {
        for (String line : lines(path)) chat(to, line, pairs);
    }

    /** Same as send, with the placeholders of another player (e.g. the one whose balance is shown). */
    public void sendAbout(CommandSender to, OfflinePlayer about, String path, String... pairs) {
        for (String line : lines(path)) {
            String s = Placeholders.apply(line, about, pairs);
            deliver(to, s);
        }
    }

    /** Sends one raw line to someone, resolving placeholders for that player. */
    public static void chat(CommandSender to, String line, String... pairs) {
        if (to == null || line == null) return;
        OfflinePlayer p = to instanceof Player ? (Player) to : null;
        deliver(to, Placeholders.apply(line, p, pairs));
    }

    public static void deliver(CommandSender to, String resolved) {
        if (to instanceof Player) {
            ((Player) to).spigot().sendMessage(Text.component(resolved));
        } else if (to != null) {
            to.sendMessage(Text.console(resolved));
        }
    }

    public static void broadcast(String resolved) {
        for (Player p : Bukkit.getOnlinePlayers()) deliver(p, resolved);
        deliver(Bukkit.getConsoleSender(), resolved);
    }
}

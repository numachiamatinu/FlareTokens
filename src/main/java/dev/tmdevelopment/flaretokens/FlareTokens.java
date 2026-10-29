package dev.tmdevelopment.flaretokens;

import dev.tmdevelopment.flaretokens.command.TokensCommand;
import dev.tmdevelopment.flaretokens.config.Lang;
import dev.tmdevelopment.flaretokens.config.Migration;
import dev.tmdevelopment.flaretokens.data.SqlStorage;
import dev.tmdevelopment.flaretokens.data.Storage;
import dev.tmdevelopment.flaretokens.data.TokenManager;
import dev.tmdevelopment.flaretokens.data.YamlStorage;
import dev.tmdevelopment.flaretokens.feature.Cheques;
import dev.tmdevelopment.flaretokens.feature.Drops;
import dev.tmdevelopment.flaretokens.feature.PlayerListener;
import dev.tmdevelopment.flaretokens.feature.UpdateChecker;
import dev.tmdevelopment.flaretokens.hook.PapiExpansion;
import dev.tmdevelopment.flaretokens.hook.VaultHook;
import dev.tmdevelopment.flaretokens.install.DependencyInstaller;
import dev.tmdevelopment.flaretokens.menu.MenuListener;
import dev.tmdevelopment.flaretokens.menu.MenuManager;
import dev.tmdevelopment.flaretokens.stats.TMDStats;
import dev.tmdevelopment.flaretokens.util.Headers;
import dev.tmdevelopment.flaretokens.util.Materials;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import dev.tmdevelopment.flaretokens.util.Ver;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FlareTokens extends JavaPlugin {

    private static FlareTokens instance;

    private Lang lang;
    private TokenManager tokens;
    private MenuManager menus;
    private Cheques cheques;
    private Drops drops;
    private TMDStats stats;
    private UpdateChecker updates;
    private dev.tmdevelopment.flaretokens.feature.Sales sales;
    private dev.tmdevelopment.flaretokens.menu.ChatInput chatInput;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public static FlareTokens get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();
        Materials.init(getLogger());

        // FlareTokens 3.x -> new files (balances are not touched)
        Migration.run(this);

        saveDefaultConfig();
        Headers.sync(this, new File(getDataFolder(), "config.yml"), "config.yml");
        reloadConfig();
        updateConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));

        boolean papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        Placeholders.setPapi(papi);

        lang = new Lang(this);
        lang.load();

        Storage storage = createStorage(getConfig().getString("storage.type", "sqlite"));
        tokens = new TokenManager(this, storage);
        try {
            tokens.open();
        } catch (Throwable t) {
            getLogger().severe("Could not open the storage (" + storage.type() + "): " + t.getMessage());
            getLogger().severe("Check storage: in config.yml. FlareTokens is disabled to keep your balances safe.");
            tokens = null;
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        VaultHook.setup();
        dev.tmdevelopment.flaretokens.hook.LuckPermsHook.reset();
        sales = new dev.tmdevelopment.flaretokens.feature.Sales(this);
        chatInput = new dev.tmdevelopment.flaretokens.menu.ChatInput(this);
        menus = new MenuManager(this);
        menus.load();
        cheques = new Cheques(this);
        drops = new Drops(this);
        drops.load();
        updates = new UpdateChecker(this);

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(updates, this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(cheques, this);
        Bukkit.getPluginManager().registerEvents(drops, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        PluginCommand cmd = getCommand("flaretokens");
        if (cmd != null) {
            TokensCommand executor = new TokensCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
            Bukkit.getPluginManager().registerEvents(executor, this);
        }

        if (papi) registerPapi();
        new DependencyInstaller(this).run(name -> {
            if (name.equalsIgnoreCase("PlaceholderAPI") && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Placeholders.setPapi(true);
                registerPapi();
            }
            if (name.equalsIgnoreCase("Vault")) VaultHook.setup();
        });
        // economy plugins register in Vault after us
        Bukkit.getScheduler().runTaskLater(this, VaultHook::setup, 40L);

        startTasks();
        sales.start();
        startMetrics();
        banner(papi, System.currentTimeMillis() - start);
        updates.checkAtStartup();
    }

    @Override
    public void onDisable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (stats != null) stats.shutdown();
        if (sales != null) sales.stop();
        if (menus != null) menus.shutdown();
        if (tokens != null) tokens.shutdown();
        instance = null;
    }

    private void startTasks() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        String type = tokens.storage().type();
        long refresh = getConfig().getLong("storage.refresh-seconds", 10);
        if ((type.equals("mysql") || type.equals("mariadb")) && refresh > 0) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> tokens.refreshOnline(), refresh * 20L, refresh * 20L));
        }
        if (tokens.storage().shared()) {
            // changes made on the other servers of the network show up here within about a second
            long sync = Math.max(1, getConfig().getLong("network.sync-seconds", 1));
            tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> tokens.pollChanges(), 40L, sync * 20L));
        }
        long top = Math.max(10, getConfig().getLong("top.refresh-seconds", 60));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> tokens.refreshTop(), top * 20L, top * 20L));
        if (type.equals("yaml")) tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> tokens.flushAsync(), 200L, 200L));
    }

    private void registerPapi() {
        try {
            new PapiExpansion(this).register();
        } catch (Throwable t) {
            getLogger().warning("Could not register the PlaceholderAPI placeholders: " + t.getMessage());
        }
    }

    /** Storage from its name (yaml / sqlite / mysql / mariadb), with the settings of config.yml. */
    public Storage createStorage(String type) {
        FileConfiguration c = getConfig();
        String t = type == null ? "sqlite" : type.toLowerCase(Locale.ROOT).trim();
        String table = c.getString("storage.table", "flaretokens");
        switch (t) {
            case "yaml":
            case "yml":
            case "file":
                return new YamlStorage(new File(getDataFolder(), "data/data.yml"));
            case "mysql":
            case "mariadb":
                return SqlStorage.mysql(t.equals("mariadb"), c.getString("storage.mysql.host", "localhost"),
                        c.getInt("storage.mysql.port", 3306), c.getString("storage.mysql.database", "minecraft"),
                        c.getString("storage.mysql.username", "root"), c.getString("storage.mysql.password", ""),
                        c.getString("storage.mysql.flags", ""), table, getLogger());
            case "sqlite":
            default:
                if (!t.equals("sqlite")) getLogger().warning("Unknown storage.type '" + type + "', using sqlite.");
                return SqlStorage.sqlite(new File(getDataFolder(), "data/data.db"), table, getLogger());
        }
    }

    /** Adds the options of newer versions to config.yml, keeping the values that are already there. */
    private void updateConfig() {
        FileConfiguration cfg = getConfig();
        Configuration defaults = cfg.getDefaults();
        if (defaults == null) return;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (key.startsWith("auto-install.plugins.") && cfg.isSet("auto-install.plugins")) continue;
            if (key.startsWith("cheque.item.") && cfg.isSet("cheque.item")) continue;
            if (!cfg.isSet(key)) missing.add(key);
        }
        if (missing.isEmpty()) return;
        File file = new File(getDataFolder(), "config.yml");
        String text = dev.tmdevelopment.flaretokens.config.ConfigUpdater.resource(this, "config.yml");
        if (text != null && dev.tmdevelopment.flaretokens.config.ConfigUpdater.addMissing(file, text, missing, getLogger())) {
            reloadConfig();
        } else {
            for (String key : missing) cfg.set(key, defaults.get(key));
            saveConfig();
        }
        getLogger().info("config.yml updated with the new options of this version.");
    }

    /** Options changed from the settings menu: applied without closing the menus. */
    public void applyConfig() {
        reloadConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));
        tokens.reloadSettings();
        VaultHook.setup();
        dev.tmdevelopment.flaretokens.hook.LuckPermsHook.reset();
        startTasks();
    }

    public void reloadAll() {
        Headers.sync(this, new File(getDataFolder(), "config.yml"), "config.yml");
        reloadConfig();
        Num.setSuffixes(getConfig().getStringList("format.suffixes"));
        lang.load();
        tokens.reloadSettings();
        menus.closeAll();
        menus.load();
        drops.load();
        VaultHook.setup();
        dev.tmdevelopment.flaretokens.hook.LuckPermsHook.reset();
        startTasks();
        sales.start();
        tokens.refreshTop();
    }

    public void onFirstJoin(Player p, long amount) {
        lang.send(p, "first-join", "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
    }

    /** Anonymous usage statistics (TMDStats). Server owners can turn them off in plugins/TMDStats/config.yml. */
    private void startMetrics() {
        try {
            stats = new TMDStats(this, "flaretokens", getConfig().getBoolean("stats", true));
            stats.addValue("storage", () -> tokens.storage().type());
            stats.addValue("shops", () -> menus.size());
            stats.addValue("drops", () -> drops.enabled() ? "On" : "Off");
        } catch (Throwable t) {
            getLogger().fine("TMDStats not started: " + t.getMessage());
        }
    }

    private void banner(boolean papi, long ms) {
        boolean vault = Bukkit.getPluginManager().isPluginEnabled("Vault");
        String g = "<gradient:#8e2de2:#c471f5>";
        String[] lines = {
                "",
                g + "  ███████╗██╗      █████╗ ██████╗ ███████╗</gradient>",
                g + "  ██╔════╝██║     ██╔══██╗██╔══██╗██╔════╝</gradient>",
                g + "  █████╗  ██║     ███████║██████╔╝█████╗  </gradient>",
                g + "  ██╔══╝  ██║     ██╔══██║██╔══██╗██╔══╝  </gradient>",
                g + "  ██║     ███████╗██║  ██║██║  ██║███████╗</gradient>",
                g + "  ╚═╝     ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝</gradient>",
                "<gray>            T O K E N S  <dark_gray>|  <white>v" + getDescription().getVersion(),
                "",
                "<gray>  Developed by " + g + "<bold>TMDevelopment</bold></gradient>",
                "<gray>  Minecraft: <white>" + Ver.text() + " <dark_gray>| <gray>Storage: <white>" + tokens.storage().type()
                        + " <dark_gray>| <gray>Accounts: <white>" + tokens.accounts(),
                "<gray>  Shops: <white>" + menus.size() + " <dark_gray>| <gray>Drops: " + (drops.enabled() ? "<green>on" : "<white>off"),
                "<gray>  PlaceholderAPI: " + (papi ? "<green>hooked" : "<red>not found")
                        + " <dark_gray>| <gray>Vault: " + (vault ? "<green>found" : "<red>not found"),
                "<gray>  Enabled in <white>" + ms + "ms",
                ""
        };
        for (String line : lines) Bukkit.getConsoleSender().sendMessage(Text.console(line));
    }

    public Lang lang() {
        return lang;
    }

    public TokenManager tokens() {
        return tokens;
    }

    public MenuManager menus() {
        return menus;
    }

    public Cheques cheques() {
        return cheques;
    }

    public Drops drops() {
        return drops;
    }

    public dev.tmdevelopment.flaretokens.feature.Sales sales() {
        return sales;
    }

    public dev.tmdevelopment.flaretokens.menu.ChatInput chatInput() {
        return chatInput;
    }

    public UpdateChecker updates() {
        return updates;
    }
}

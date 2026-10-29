package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.Headers;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the shops (shops/*.yml, as many as you want) and the menus of the plugin (menus/*.yml), opens them,
 * keeps the way back ([back]) and registers their open-commands (/tokenshop, /store ...).
 */
public final class MenuManager {

    /** Menus of the plugin, in the order shown in the editor list. */
    public static final List<String> BUILTIN = Collections.unmodifiableList(java.util.Arrays.asList(
            Builtins.MAIN, Builtins.TOP, Builtins.HISTORY, Builtins.CONFIRM));

    private final FlareTokens plugin;
    private final Map<String, MenuDef> shops = new LinkedHashMap<>();
    private final Map<String, MenuDef> builtins = new LinkedHashMap<>();
    private final Map<UUID, MenuSession> open = new ConcurrentHashMap<>();
    /** Open commands in use now: command -> menu. Paper keeps old commands until a restart, they check this. */
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final List<Command> registered = new ArrayList<>();
    private BukkitTask ticker;
    private YamlConfiguration shopTexts;

    public MenuManager(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public File shopDir() {
        return new File(plugin.getDataFolder(), "shops");
    }

    public File menuDir() {
        return new File(plugin.getDataFolder(), "menus");
    }

    // ------------------------------------------------------------------ loading

    public void load() {
        shops.clear();
        builtins.clear();
        shopTexts = resource("shop-texts.yml");
        File dir = shopDir();
        if (!dir.exists()) {
            for (String f : new String[]{"main", "ranks", "items"}) plugin.saveResource("shops/" + f + ".yml", false);
        }
        loadShops(dir, "");
        loadBuiltins();
        registerCommands();
        if (ticker != null) ticker.cancel();
        long every = Math.max(5, plugin.getConfig().getLong("shop.update-ticks", 20));
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (MenuSession s : new ArrayList<>(open.values())) {
                if (!s.player().isOnline()) {
                    open.remove(s.player().getUniqueId());
                    continue;
                }
                try {
                    s.tick();
                } catch (Throwable t) {
                    plugin.getLogger().warning("Error updating menu " + s.def.id + ": " + t.getMessage());
                }
            }
        }, every, every);
    }

    private void loadShops(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) loadShops(f, prefix);
                continue;
            }
            String n = f.getName();
            if (!n.toLowerCase(Locale.ROOT).endsWith(".yml")) continue;
            String id = n.substring(0, n.length() - 4);
            YamlConfiguration yml = read(f, "shops/" + n);
            if (yml == null) continue;
            try {
                shops.put(id.toLowerCase(Locale.ROOT), MenuDef.load(id, f, false, yml, shopTexts(), plugin.getLogger()));
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not load shops/" + n + ": " + t.getMessage());
            }
        }
    }

    private void loadBuiltins() {
        File dir = menuDir();
        if (!dir.exists() && !dir.mkdirs()) plugin.getLogger().warning("Could not create the menus folder");
        for (String id : BUILTIN) {
            File f = new File(dir, id + ".yml");
            if (!f.exists()) plugin.saveResource("menus/" + id + ".yml", false);
            Headers.sync(plugin, f, "menus/" + id + ".yml");
            YamlConfiguration yml = read(f, "menus/" + id + ".yml");
            YamlConfiguration def = resource("menus/" + id + ".yml");
            boolean broken = yml == null;
            if (broken) yml = def == null ? new YamlConfiguration() : def; // broken file: defaults until fixed, never overwritten
            else if (def != null && complete(yml, def)) save(f, "menus/" + id + ".yml", yml);
            ConfigurationSection texts = def == null ? null : def.getConfigurationSection("texts");
            MenuDef loaded = MenuDef.load(id, f, true, yml, texts, plugin.getLogger());
            loaded.broken = broken;
            builtins.put(id, loaded);
        }
    }

    /** Elements and texts added by new versions are written in the file, so they can be edited. */
    private boolean complete(YamlConfiguration yml, YamlConfiguration def) {
        boolean changed = false;
        ConfigurationSection els = def.getConfigurationSection("elements");
        if (els != null) {
            for (String key : els.getKeys(false)) {
                if (yml.isConfigurationSection("elements." + key)) continue;
                yml.set("elements." + key, els.getConfigurationSection(key));
                changed = true;
            }
        }
        ConfigurationSection ts = def.getConfigurationSection("texts");
        if (ts != null) {
            for (String k : ts.getKeys(true)) {
                if (ts.isConfigurationSection(k) || yml.isSet("texts." + k)) continue;
                yml.set("texts." + k, ts.get(k));
                changed = true;
            }
        }
        for (String k : new String[]{"enabled", "open-commands", "open-permission"}) {
            if (!yml.isSet(k) && def.isSet(k)) {
                yml.set(k, def.get(k));
                changed = true;
            }
        }
        return changed;
    }

    /** Reads a menu file; a broken file is reported with its line and never overwritten. */
    YamlConfiguration read(File f, String name) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f);
            return y;
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning(name + " has a mistake and was not loaded: " + firstLine(e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read " + name + ": " + e.getMessage());
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) return "?";
        String t = s.replace("\r", "");
        int nl = t.indexOf('\n');
        String first = nl < 0 ? t : t.substring(0, nl);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+)").matcher(t);
        return m.find() ? "line " + m.group(1) + ": " + first : first;
    }

    /** Default texts of the shops (shop-texts.yml in the jar). */
    private ConfigurationSection shopTexts() {
        return shopTexts == null ? null : shopTexts.getConfigurationSection("texts");
    }

    YamlConfiguration resource(String path) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes a menu file: the explanations of the jar on top, then the values. */
    void save(File f, String resource, YamlConfiguration yml) {
        // only what changed is written: comments, quotes and layout of the file stay as they are
        if (f.isFile()) {
            try {
                String old = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String patched = dev.tmdevelopment.flaretokens.config.YamlKeeper.patch(old, yml);
                if (patched != null) {
                    if (!patched.equals(old.replace("\r\n", "\n"))) {
                        dev.tmdevelopment.flaretokens.util.J8.write(f.toPath(), patched, StandardCharsets.UTF_8);
                    }
                    return;
                }
            } catch (Exception ignored) {
                // written again below
            }
        }
        String header = Headers.of(plugin, resource);
        // 1.18+ keeps the header of the loaded file in saveToString: written only once, from the jar
        String body = Headers.split(yml.saveToString())[1];
        try {
            dev.tmdevelopment.flaretokens.util.J8.write(f.toPath(), header + body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save " + f.getName() + ": " + e.getMessage());
        }
    }

    /** Saves a menu edited in game and loads it again. */
    public MenuDef save(MenuDef def) {
        if (def.broken) return def; // the user's file has a mistake: don't write the defaults over it
        String resource = def.builtin ? "menus/" + def.id + ".yml" : "shops/main.yml";
        save(def.file, resource, def.yaml);
        YamlConfiguration yml = read(def.file, def.file.getName());
        if (yml == null) return def;
        ConfigurationSection texts = def.builtin
                ? (resource("menus/" + def.id + ".yml") == null ? null : resource("menus/" + def.id + ".yml").getConfigurationSection("texts"))
                : shopTexts();
        MenuDef fresh = MenuDef.load(def.id, def.file, def.builtin, yml, texts, plugin.getLogger());
        if (def.builtin) builtins.put(def.id, fresh);
        else shops.put(def.id.toLowerCase(Locale.ROOT), fresh);
        registerCommands();
        return fresh;
    }

    /** New empty shop file (in-game "New menu"). Returns null if the name is taken or not valid. */
    public MenuDef create(String name) {
        String id = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty() || shops.containsKey(id) || BUILTIN.contains(id)) return null;
        File f = new File(shopDir(), id + ".yml");
        if (f.exists()) return null;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("enabled", true);
        yml.set("title", "<gradient:#8e2de2:#c471f5><bold>" + id.toUpperCase(Locale.ROOT) + "</bold></gradient>");
        yml.set("rows", 3);
        yml.set("open-commands", new ArrayList<String>());
        yml.set("open-permission", "");
        yml.createSection("items");
        yml.set("decorations", new ArrayList<Object>());
        if (shopTexts != null && shopTexts.isConfigurationSection("texts")) yml.set("texts", shopTexts.getConfigurationSection("texts"));
        save(f, "shops/main.yml", yml);
        MenuDef def = MenuDef.load(id, f, false, yml, shopTexts(), plugin.getLogger());
        shops.put(id, def);
        return def;
    }

    // ------------------------------------------------------------------ lookups

    /** Names of the shops (shops/*.yml). */
    public Collection<String> names() {
        List<String> out = new ArrayList<>();
        for (MenuDef m : shops.values()) out.add(m.id());
        return out;
    }

    public int size() {
        return shops.size();
    }

    /** A shop by name. */
    public MenuDef get(String name) {
        return name == null ? null : shops.get(name.toLowerCase(Locale.ROOT));
    }

    /** A shop, or a menu of the plugin. */
    public MenuDef def(String name) {
        if (name == null) return null;
        MenuDef d = shops.get(name.toLowerCase(Locale.ROOT));
        return d != null ? d : builtins.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<MenuDef> shops() {
        return Collections.unmodifiableCollection(shops.values());
    }

    public MenuDef builtin(String id) {
        return builtins.get(id);
    }

    public String mainShop() {
        return plugin.getConfig().getString("shop.main-shop", "main");
    }

    /** player-access in config.yml plus the permission. Admins can always. */
    public boolean canOpen(CommandSender s, String what) {
        if (s.hasPermission("flaretokens.admin")) return true;
        if (!plugin.getConfig().getBoolean("player-access." + what, true)) return false;
        return s.hasPermission("flaretokens.command." + what);
    }

    private static String accessOf(String builtinId) {
        switch (builtinId) {
            case Builtins.MAIN:
                return "menu";
            case Builtins.TOP:
                return "top";
            case Builtins.HISTORY:
                return "history";
            default:
                return "shop";
        }
    }

    // ------------------------------------------------------------------ opening

    public boolean open(Player p, String name, boolean check) {
        return open(p, name, check, null);
    }

    /**
     * Opens a shop or a menu of the plugin. With {@code check} the access, the permission and the open requirements
     * are checked. {@code parent} is the menu [back] goes to.
     */
    public boolean open(Player p, String name, boolean check, MenuSession parent) {
        MenuDef menu = def(name);
        if (menu == null) {
            plugin.lang().send(p, "shop.not-found", "{shop}", name == null ? "" : name);
            return false;
        }
        if (menu.builtin && menu.id.equals(Builtins.HISTORY)) return openHistory(p, p.getUniqueId(), p.getName(), parent);
        if (menu.builtin && menu.id.equals(Builtins.CONFIRM)) return false;
        if (!menu.builtin && !plugin.getConfig().getBoolean("shop.enabled", true) && !p.hasPermission("flaretokens.admin")) {
            plugin.lang().send(p, "shop.disabled");
            return false;
        }
        if (!menu.enabled && !p.hasPermission("flaretokens.admin")) {
            plugin.lang().send(p, "shop.disabled");
            return false;
        }
        if (check && !gate(p, menu)) return false;
        MenuSession session = new MenuSession(menu, p, parent);
        show(p, session);
        session.loadCounters();
        if (!menu.openActions.isEmpty()) Actions.run(p, menu.openActions, session.context());
        return true;
    }

    private boolean gate(Player p, MenuDef menu) {
        Actions.Context ctx = new Actions.Context("shop " + menu.id(), null, "{menu}", menu.id());
        if (!canOpen(p, menu.builtin ? accessOf(menu.id) : "shop")) {
            plugin.lang().send(p, "general.no-permission");
            return false;
        }
        if (menu.permission != null && !p.hasPermission(menu.permission) && !p.hasPermission("flaretokens.admin")) {
            if (menu.denyActions.isEmpty()) plugin.lang().send(p, "general.no-permission");
            else Actions.run(p, menu.denyActions, ctx);
            return false;
        }
        if (!Actions.check(p, menu.openRequirements, ctx)) {
            if (menu.denyActions.isEmpty()) plugin.lang().send(p, "shop.requirements");
            else Actions.run(p, menu.denyActions, ctx);
            return false;
        }
        return true;
    }

    private void show(Player p, MenuSession session) {
        session.render();
        open.put(p.getUniqueId(), session);
        p.openInventory(session.getInventory());
        // openInventory closes the previous menu (close event) after we stored the new one
        open.put(p.getUniqueId(), session);
        Actions.sound(p, "sounds.open-shop");
    }

    /** Opens on the next tick (safe from clicks). */
    public void openLater(Player p, String name, MenuSession parent) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) open(p, name, true, parent);
        });
    }

    /** Shows a menu again (same state: page, way back). Returns the session that is open now. */
    public MenuSession reopen(Player p, MenuSession s) {
        if (s.def.builtin && s.def.id.equals(Builtins.HISTORY)) {
            show(p, s);
            loadHistory(s);
            return s;
        }
        MenuDef now = def(s.def.id);
        if (now != null && now != s.def) {
            // the file was reloaded meanwhile
            MenuSession fresh = new MenuSession(now, p, s.parent);
            show(p, fresh);
            fresh.loadCounters();
            return fresh;
        }
        show(p, s);
        s.loadCounters();
        return s;
    }

    /** Confirmation of a purchase. false = the confirm menu is turned off (buy at once). */
    boolean openConfirm(MenuSession shop, MenuItem item, long price) {
        MenuDef menu = builtins.get(Builtins.CONFIRM);
        if (menu == null || !menu.enabled) return false;
        MenuSession s = new MenuSession(menu, shop.player, shop);
        s.pendingItem = item;
        s.pendingFrom = shop;
        s.pendingPrice = price;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shop.player.isOnline()) show(shop.player, s);
        });
        return true;
    }

    /** History of a player (own one, or another one for admins). */
    public boolean openHistory(Player viewer, UUID target, String name, MenuSession parent) {
        MenuDef menu = builtins.get(Builtins.HISTORY);
        if (menu == null) return false;
        boolean own = viewer.getUniqueId().equals(target);
        if (own ? !canOpen(viewer, "history") : !viewer.hasPermission("flaretokens.command.history.others") && !viewer.hasPermission("flaretokens.admin")) {
            plugin.lang().send(viewer, "general.no-permission");
            return false;
        }
        if (!menu.enabled && !viewer.hasPermission("flaretokens.admin")) {
            plugin.lang().send(viewer, "shop.disabled");
            return false;
        }
        MenuSession s = new MenuSession(menu, viewer, parent);
        s.target = target;
        s.targetName = name;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            show(viewer, s);
            loadHistory(s);
        });
        return true;
    }

    void loadHistory(MenuSession s) {
        int size = Math.max(1, Builtins.pageSize(s));
        s.loading = true;
        if (isOpen(s.player, s)) s.render();
        // one more than the page: tells if there is a next page
        plugin.tokens().history(s.target, s.page * size, size + 1).whenComplete((list, error) -> {
            s.entries = list == null ? java.util.Collections.<dev.tmdevelopment.flaretokens.data.Storage.Tx>emptyList() : list;
            s.loading = false;
            if (s.player.isOnline() && isOpen(s.player, s)) s.render();
        });
    }

    public boolean isOpen(Player p, MenuSession s) {
        return open.get(p.getUniqueId()) == s;
    }

    MenuSession session(Player p) {
        return open.get(p.getUniqueId());
    }

    void closed(Player p, MenuSession s) {
        open.remove(p.getUniqueId(), s);
    }

    /** Redraws every open menu (sale started / ended). */
    public void renderAll() {
        for (MenuSession s : new ArrayList<>(open.values())) {
            if (s.player().isOnline()) s.render();
        }
    }

    public void closeAll() {
        for (MenuSession s : new ArrayList<>(open.values())) {
            try {
                s.player().closeInventory();
            } catch (Throwable ignored) {
            }
        }
        open.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = Compat.topHolder(p);
            if (h instanceof Gui || h instanceof EditorSession) p.closeInventory();
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        closeAll();
        unregisterCommands();
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        unregisterCommands();
        Map<String, String> wanted = new HashMap<>(); // command -> menu
        Object main = plugin.getConfig().get("shop.open-command");
        List<String> mainCmds = main instanceof List<?> ? plugin.getConfig().getStringList("shop.open-command")
                : main == null ? Collections.<String>emptyList() : Collections.singletonList(String.valueOf(main));
        for (String c : mainCmds) put(wanted, c, mainShop());
        for (MenuDef m : shops.values()) for (String c : m.openCommands()) put(wanted, c, m.id());
        for (MenuDef m : builtins.values()) for (String c : m.openCommands()) put(wanted, c, m.id());
        active.clear();
        active.putAll(wanted);
        if (wanted.isEmpty()) {
            Compat.syncCommands();
            return;
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            String label = e.getKey();
            String target = e.getValue();
            Command cmd = new Command(label, "Opens the " + target + " menu", "/" + label, new ArrayList<String>()) {
                @Override
                public boolean execute(CommandSender sender, String l, String[] args) {
                    if (!(sender instanceof Player)) {
                        plugin.lang().send(sender, "general.players-only");
                        return true;
                    }
                    String now = active.get(label);
                    if (now == null) {
                        // the command was removed from the menu: answer like an unknown command
                        sender.sendMessage(unknownCommand());
                        return true;
                    }
                    MenuDef d = def(now);
                    if (d != null && !d.builtin && !canOpen(sender, "shop")) {
                        plugin.lang().send(sender, "general.no-permission");
                        return true;
                    }
                    open((Player) sender, now, true);
                    return true;
                }
            };
            try {
                Compat.commandMap().register("flaretokens", cmd);
                registered.add(cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not register /" + label + ": " + t.getMessage());
            }
        }
        Compat.syncCommands();
    }

    private static String unknownCommand() {
        try {
            String m = Bukkit.spigot().getConfig().getString("messages.unknown-command");
            if (m != null) return org.bukkit.ChatColor.translateAlternateColorCodes('&', m);
        } catch (Throwable ignored) {
        }
        return "Unknown command. Type \"/help\" for help.";
    }

    private static void put(Map<String, String> map, String cmd, String shop) {
        if (cmd == null) return;
        String c = cmd.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isEmpty() || c.contains(" ")) return;
        map.putIfAbsent(c, shop);
    }

    private void unregisterCommands() {
        for (Command c : registered) Compat.removeKnown(c);
        registered.clear();
    }
}

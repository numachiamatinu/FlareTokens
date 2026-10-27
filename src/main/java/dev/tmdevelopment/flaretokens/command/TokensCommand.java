package dev.tmdevelopment.flaretokens.command;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.config.Lang;
import dev.tmdevelopment.flaretokens.data.Storage;
import dev.tmdevelopment.flaretokens.util.Num;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /flaretokens (aliases: tokens, token, ftokens, ftoken, ft, tmtokens, coins). Permissions are the same as 3.x: flaretokens.command.&lt;sub&gt;.
 */
public final class TokensCommand implements CommandExecutor, TabCompleter, org.bukkit.event.Listener {

    private static final List<String> SUBS = Arrays.asList("help", "balance", "pay", "withdraw", "menu", "shop", "top", "history",
            "give", "remove", "set", "sale", "settings", "editor", "layout", "reload", "migrate", "about", "version");

    private final FlareTokens plugin;
    private final Map<UUID, Long> payCooldown = new HashMap<>();
    private final Map<UUID, Long> lastLookup = new HashMap<>();

    public TokensCommand(FlareTokens plugin) {
        this.plugin = plugin;
    }

    private Lang lang() {
        return plugin.lang();
    }

    /** Players can look up other players (storage queries) at most once per second. */
    private boolean throttled(CommandSender s) {
        if (!(s instanceof Player) || s.hasPermission("flaretokens.admin")) return false;
        UUID id = ((Player) s).getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastLookup.get(id);
        if (last != null && now - last < 1000) {
            lang().send(s, "general.wait");
            return true;
        }
        lastLookup.put(id, now);
        return false;
    }

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        forget(e.getPlayer().getUniqueId());
    }

    public void forget(UUID id) {
        lastLookup.remove(id);
        payCooldown.remove(id);
    }

    /** Commands that player-access in config.yml can turn off for normal players. */
    private static boolean isPlayerFeature(String sub) {
        return sub.equals("menu") || sub.equals("shop") || sub.equals("top") || sub.equals("history") || sub.equals("pay") || sub.equals("withdraw");
    }

    private static boolean perm(CommandSender s, String sub) {
        return s.hasPermission("flaretokens.command." + sub) || s.hasPermission("flaretokens.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains(":")) l = l.substring(l.indexOf(':') + 1);
        if (args.length == 0) {
            if (sender instanceof Player && perm(sender, "balance")) {
                lang().send(sender, "balance.self", "{label}", l);
                lang().send(sender, "balance.hint", "{label}", l);
            } else {
                help(sender, l);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
            case "?":
                help(sender, l);
                return true;
            case "balance":
            case "bal":
            case "money":
                balance(sender, l, args);
                return true;
            case "pay":
            case "send":
                pay(sender, l, args);
                return true;
            case "withdraw":
            case "cheque":
                withdraw(sender, l, args);
                return true;
            case "shop":
            case "shops":
            case "open":
                shop(sender, l, args);
                return true;
            case "top":
            case "baltop":
                top(sender, l, args);
                return true;
            case "give":
            case "add":
                change(sender, l, args, "give");
                return true;
            case "remove":
            case "take":
                change(sender, l, args, "remove");
                return true;
            case "set":
                change(sender, l, args, "set");
                return true;
            case "reload":
                if (!perm(sender, "reload")) {
                    lang().send(sender, "general.no-permission");
                    return true;
                }
                plugin.reloadAll();
                lang().send(sender, "general.reloaded", "{shops}", String.valueOf(plugin.menus().size()));
                return true;
            case "migrate":
            case "importer":
            case "import":
                migrate(sender, l, args);
                return true;
            case "version":
            case "ver":
            case "about":
                version(sender, l, args);
                return true;
            case "menu":
            case "gui":
                if (!(sender instanceof Player)) {
                    lang().send(sender, "general.players-only");
                    return true;
                }
                plugin.menus().open((Player) sender, "tokens", true);
                return true;
            case "history":
            case "log":
            case "transactions":
                history(sender, l, args);
                return true;
            case "sale":
            case "sales":
                sale(sender, l, args);
                return true;
            case "settings":
            case "admin":
                if (!perm(sender, "settings")) {
                    lang().send(sender, "general.no-permission");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    lang().send(sender, "general.players-only");
                    return true;
                }
                new dev.tmdevelopment.flaretokens.menu.SettingsMenu(plugin, (Player) sender).open();
                return true;
            case "editor":
            case "layout":
            case "layouts":
            case "edit":
                editor(sender, l, args);
                return true;
            default:
                // /tokens <player> = balance of another player
                if (args.length == 1 && perm(sender, "balance.others") && Bukkit.getPlayerExact(args[0]) != null) {
                    balance(sender, l, new String[]{"balance", args[0]});
                    return true;
                }
                lang().send(sender, "general.unknown-command", "{label}", l);
                return true;
        }
    }

    // ------------------------------------------------------------------ help

    private void help(CommandSender sender, String label) {
        if (!perm(sender, "help") && !perm(sender, "balance")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        lang().send(sender, "help.header", "{label}", label);
        String[][] lines = {
                {"balance", "balance"}, {"pay", "pay"}, {"withdraw", "withdraw"}, {"shop", "shop"}, {"top", "top"},
                {"menu", "menu"}, {"history", "history"},
                {"give", "give"}, {"remove", "remove"}, {"set", "set"}, {"sale", "sale"}, {"settings", "settings"}, {"editor", "editor"},
                {"reload", "reload"}, {"migrate", "migrate"}, {"version", "version"}};
        boolean admin = false;
        for (String[] line : lines) {
            if (!perm(sender, line[1])) continue;
            if (isPlayerFeature(line[1]) && !plugin.menus().canOpen(sender, line[1])) continue;
            if (line[1].equals("give") || line[1].equals("remove") || line[1].equals("set")) admin = true;
            lang().send(sender, "help.lines." + line[0], "{label}", label);
        }
        if (admin) lang().send(sender, "help.footer", "{label}", label);
    }

    // ------------------------------------------------------------------ balance

    private void balance(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "balance")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            if (!(sender instanceof Player)) {
                lang().send(sender, "usage.balance", "{label}", label);
                return;
            }
            lang().send(sender, "balance.self", "{label}", label);
            return;
        }
        if (!perm(sender, "balance.others")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String name = args[1];
        if (throttled(sender)) return;
        plugin.tokens().find(name).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", name);
                return;
            }
            plugin.tokens().fetch(acc.uuid).thenAccept(v -> lang().send(sender, "balance.other", "{target}", nameOf(acc, name),
                    "{amount}", String.valueOf(v), "{amount_commas}", Num.commas(v), "{amount_formatted}", Num.shortFormat(v)));
        });
    }

    // ------------------------------------------------------------------ pay

    private void pay(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "pay") || !plugin.menus().canOpen(sender, "pay")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (!plugin.getConfig().getBoolean("pay.enabled", true)) {
            lang().send(p, "pay.disabled");
            return;
        }
        if (args.length < 3) {
            lang().send(p, "usage.pay", "{label}", label);
            return;
        }
        // 3.x help said "pay <amount> <player>": accept both orders
        String targetName = args[1];
        String amountText = args[2];
        if (Num.parse(targetName) >= 0 && Num.parse(amountText) < 0) {
            targetName = args[2];
            amountText = args[1];
        }
        long amount = Num.parse(amountText);
        if (amount <= 0) {
            lang().send(p, "general.invalid-amount", "{input}", amountText);
            return;
        }
        long min = Math.max(1, plugin.getConfig().getLong("pay.min-amount", 1));
        if (amount < min) {
            lang().send(p, "pay.min", "{min}", Num.commas(min));
            return;
        }
        if (targetName.equalsIgnoreCase(p.getName())) {
            lang().send(p, "pay.self");
            return;
        }
        long cd = plugin.getConfig().getLong("pay.cooldown-seconds", 0) * 1000L;
        Long last = payCooldown.get(p.getUniqueId());
        if (cd > 0 && last != null && System.currentTimeMillis() - last < cd && !p.hasPermission("flaretokens.bypass.cooldown")) {
            long left = (cd - (System.currentTimeMillis() - last) + 999) / 1000;
            lang().send(p, "pay.cooldown", "{seconds}", String.valueOf(left));
            return;
        }
        if (throttled(p)) return;
        String finalTarget = targetName;
        plugin.tokens().find(targetName).thenAccept(acc -> {
            if (!p.isOnline()) return;
            if (acc == null) {
                lang().send(p, "general.player-not-found", "{target}", finalTarget);
                return;
            }
            if (acc.uuid.equals(p.getUniqueId())) {
                lang().send(p, "pay.self");
                return;
            }
            Player online = Bukkit.getPlayer(acc.uuid);
            String targetDisplay = nameOf(acc, finalTarget);
            if (online == null && !plugin.getConfig().getBoolean("pay.allow-offline", true)) {
                lang().send(p, "pay.offline", "{target}", targetDisplay);
                return;
            }
            if (!plugin.tokens().spend(p, amount, "pay to " + targetDisplay)) {
                lang().send(p, "pay.not-enough", "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
                Actions.sound(p, "sounds.error");
                return;
            }
            payCooldown.put(p.getUniqueId(), System.currentTimeMillis());
            long given = plugin.tokens().give(acc.uuid, targetDisplay, amount, "pay from " + p.getName()).getNow(0L);
            if (given == dev.tmdevelopment.flaretokens.data.TokenManager.CANCELLED) {
                // another plugin blocked the payment: money back
                plugin.tokens().give(p.getUniqueId(), p.getName(), amount, "pay refund");
                lang().send(p, "general.cancelled");
                return;
            }
            lang().send(p, "pay.sent", "{target}", targetDisplay, "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
            Actions.sound(p, "sounds.pay");
            if (online != null) {
                lang().send(online, "pay.received", "{player}", p.getName(), "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
                Actions.sound(online, "sounds.receive");
            }
        });
    }

    // ------------------------------------------------------------------ withdraw

    private void withdraw(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "withdraw") || !plugin.menus().canOpen(sender, "withdraw")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (!plugin.getConfig().getBoolean("cheque.enabled", true)) {
            lang().send(p, "withdraw.disabled");
            return;
        }
        if (args.length < 2) {
            lang().send(p, "usage.withdraw", "{label}", label);
            return;
        }
        long amount = args[1].equalsIgnoreCase("all") ? plugin.tokens().balance(p.getUniqueId()) : Num.parse(args[1]);
        if (amount <= 0) {
            lang().send(p, "general.invalid-amount", "{input}", args[1]);
            return;
        }
        long min = Math.max(1, plugin.getConfig().getLong("cheque.min-amount", 1));
        if (amount < min) {
            lang().send(p, "withdraw.min", "{min}", Num.commas(min));
            return;
        }
        if (p.getInventory().firstEmpty() < 0) {
            lang().send(p, "withdraw.inventory-full");
            return;
        }
        if (!plugin.tokens().spend(p, amount, "withdraw")) {
            lang().send(p, "withdraw.not-enough", "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
            Actions.sound(p, "sounds.error");
            return;
        }
        ItemStack cheque = plugin.cheques().create(p, amount);
        Map<Integer, ItemStack> left = p.getInventory().addItem(cheque);
        for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
        lang().send(p, "withdraw.done", "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount));
    }

    // ------------------------------------------------------------------ shop

    private void shop(CommandSender sender, String label, String[] args) {
        if ((!perm(sender, "shop") && !perm(sender, "openshop")) || !plugin.menus().canOpen(sender, "shop")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String shop = plugin.menus().mainShop();
        Player target = sender instanceof Player ? (Player) sender : null;
        // 3.x: /tokens shop <player> [shop]   new: /tokens shop [shop] [player]
        if (args.length >= 2) {
            Player asPlayer = Bukkit.getPlayerExact(args[1]);
            if (plugin.menus().get(args[1]) != null || asPlayer == null) {
                shop = args[1];
                if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
            } else {
                target = asPlayer;
                if (args.length >= 3) shop = args[2];
            }
            if (args.length >= 3 && target == null) {
                lang().send(sender, "general.player-not-found", "{target}", args[2]);
                return;
            }
        }
        if (target == null) {
            lang().send(sender, "general.players-only");
            return;
        }
        boolean forOther = !(sender instanceof Player) || !((Player) sender).getUniqueId().equals(target.getUniqueId());
        if (forOther && !perm(sender, "shop.others") && !perm(sender, "openshop.others") && sender instanceof Player) {
            lang().send(sender, "general.no-permission");
            return;
        }
        boolean opened = plugin.menus().open(target, shop, !forOther);
        if (opened && forOther) lang().send(sender, "shop.opened-for", "{shop}", shop, "{target}", target.getName());
    }

    // ------------------------------------------------------------------ top

    private void top(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "top") || !plugin.menus().canOpen(sender, "top")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        // players get the top menu (menus/top.yml), the console the chat list
        if (sender instanceof Player && !(args.length > 1 && args[1].equalsIgnoreCase("chat"))) {
            dev.tmdevelopment.flaretokens.menu.MenuDef menu = plugin.menus().builtin("top");
            if (menu != null && menu.enabled()) {
                plugin.menus().open((Player) sender, "top", true);
                return;
            }
        }
        List<Storage.Account> top = plugin.tokens().top();
        int size = Math.min(top.size(), Math.max(1, plugin.getConfig().getInt("top.size", 10)));
        List<java.util.UUID> offline = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Storage.Account a = top.get(i);
            if (a.uuid != null && Bukkit.getPlayer(a.uuid) == null) offline.add(a.uuid);
        }
        Runnable send = () -> {
            if (sender instanceof Player && !((Player) sender).isOnline()) return;
            lang().send(sender, "top.header", "{label}", label);
            if (top.isEmpty()) {
                lang().send(sender, "top.empty");
            } else {
                for (int i = 0; i < size; i++) {
                    Storage.Account a = top.get(i);
                    String[] pairs = {"{position}", String.valueOf(i + 1), "{name}", a.name == null || a.name.isEmpty() ? "?" : a.name,
                            "{amount}", String.valueOf(a.tokens), "{amount_commas}", Num.commas(a.tokens), "{amount_formatted}", Num.shortFormat(a.tokens)};
                    // PlaceholderAPI placeholders (%luckperms_prefix%...) are of the player of this line
                    if (a.uuid != null) lang().sendAbout(sender, Bukkit.getOfflinePlayer(a.uuid), "top.line", pairs);
                    else lang().send(sender, "top.line", pairs);
                }
            }
            lang().send(sender, "top.footer", "{label}", label);
        };
        // offline players: LuckPerms loads their rank first
        if (!dev.tmdevelopment.flaretokens.util.Placeholders.papi()
                || !dev.tmdevelopment.flaretokens.hook.LuckPermsHook.load(offline, send)) send.run();
    }

    // ------------------------------------------------------------------ admin

    private void change(CommandSender sender, String label, String[] args, String what) {
        if (!perm(sender, what)) {
            lang().send(sender, "general.no-permission");
            return;
        }
        List<String> a = new ArrayList<>(Arrays.asList(args));
        boolean silent = a.removeIf(s -> s.equalsIgnoreCase("-s"));
        if (a.size() < 3) {
            lang().send(sender, "usage." + what, "{label}", label);
            return;
        }
        String targetName = a.get(1);
        long amount = Num.parse(a.get(2));
        if (amount < 0 || (amount == 0 && !what.equals("set"))) {
            lang().send(sender, "general.invalid-amount", "{input}", a.get(2));
            return;
        }
        String cause = "command by " + sender.getName();
        String ac = Num.commas(amount);

        if (targetName.equals("*") || targetName.equalsIgnoreCase("@a")) {
            if (what.equals("set")) {
                lang().send(sender, "usage.set", "{label}", label);
                return;
            }
            int count = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                count++;
                if (what.equals("give")) plugin.tokens().give(p.getUniqueId(), p.getName(), amount, cause);
                else plugin.tokens().take(p.getUniqueId(), p.getName(), amount, false, cause);
                if (!silent) notify(p, what, amount);
            }
            lang().send(sender, what.equals("give") ? "admin.give-all" : "admin.remove-all", "{amount}", String.valueOf(amount),
                    "{amount_commas}", ac, "{count}", String.valueOf(count));
            return;
        }

        plugin.tokens().find(targetName).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", targetName);
                return;
            }
            String name = nameOf(acc, targetName);
            java.util.concurrent.CompletableFuture<Long> f;
            if (what.equals("give")) f = plugin.tokens().give(acc.uuid, name, amount, cause);
            else if (what.equals("remove")) f = plugin.tokens().take(acc.uuid, name, amount, false, cause);
            else f = plugin.tokens().set(acc.uuid, name, amount, cause);
            f.thenAccept(after -> {
                if (after == dev.tmdevelopment.flaretokens.data.TokenManager.CANCELLED) {
                    lang().send(sender, "general.cancelled");
                    return;
                }
                lang().send(sender, "admin." + what, "{target}", name, "{amount}", String.valueOf(amount), "{amount_commas}", ac,
                        "{tokens_after}", Num.commas(Math.max(0, after)));
                Player online = Bukkit.getPlayer(acc.uuid);
                if (online != null && !silent && !(sender instanceof Player && ((Player) sender).getUniqueId().equals(acc.uuid) && what.equals("set"))) {
                    notify(online, what, what.equals("set") ? Math.max(0, after) : amount);
                }
            });
        });
    }

    private void notify(Player p, String what, long amount) {
        lang().send(p, "received." + what, "{amount}", String.valueOf(amount), "{amount_commas}", Num.commas(amount),
                "{amount_formatted}", Num.shortFormat(amount));
        if (what.equals("give")) Actions.sound(p, "sounds.receive");
    }

    private void migrate(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "migrate") && !perm(sender, "importer")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            lang().send(sender, "usage.migrate", "{label}", label);
            return;
        }
        String target = args[1].toLowerCase(Locale.ROOT);
        if (target.equals("file")) target = "yaml";
        if (target.equals("mariadb")) target = "mysql";
        if (!target.equals("yaml") && !target.equals("sqlite") && !target.equals("mysql")) {
            lang().send(sender, "usage.migrate", "{label}", label);
            return;
        }
        String current = plugin.tokens().storage().type();
        if (current.equals(target) || (target.equals("mysql") && current.equals("mariadb"))) {
            lang().send(sender, "admin.migrate-same", "{target}", target);
            return;
        }
        Storage to = plugin.createStorage(target);
        String t = target;
        lang().send(sender, "admin.migrate-start", "{target}", t);
        plugin.tokens().migrate(to, result -> {
            if (result.startsWith("error: ")) lang().send(sender, "admin.migrate-error", "{error}", result.substring(7));
            else lang().send(sender, "admin.migrate-done", "{target}", t, "{count}", result);
        });
    }

    // ------------------------------------------------------------------ history

    private void history(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "history")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        boolean other = args.length >= 2 && !(sender instanceof Player && args[1].equalsIgnoreCase(sender.getName()));
        if (!other) {
            if (!(sender instanceof Player)) {
                lang().send(sender, "usage.history", "{label}", label);
                return;
            }
            Player p = (Player) sender;
            plugin.menus().openHistory(p, p.getUniqueId(), p.getName(), null);
            return;
        }
        if (!perm(sender, "history.others")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        String name = args[1];
        int page = args.length >= 3 ? Math.max(1, (int) Num.parse(args[2])) : 1;
        if (throttled(sender)) return;
        plugin.tokens().find(name).thenAccept(acc -> {
            if (acc == null) {
                lang().send(sender, "general.player-not-found", "{target}", name);
                return;
            }
            String shown = nameOf(acc, name);
            if (sender instanceof Player) {
                plugin.menus().openHistory((Player) sender, acc.uuid, shown, null);
                return;
            }
            int size = 10;
            plugin.tokens().history(acc.uuid, (page - 1) * size, size).thenAccept(list -> {
                lang().send(sender, "history.header", "{target}", shown, "{page}", String.valueOf(page));
                if (list.isEmpty()) lang().send(sender, "history.empty");
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT);
                for (Storage.Tx t : list) {
                    String kind = t.action == null ? "" : t.action.toLowerCase(Locale.ROOT);
                    lang().send(sender, "history.line", "{date}", f.format(new java.util.Date(t.time)),
                            "{sign}", kind.equals("take") ? "-" : kind.equals("set") ? "=" : "+",
                            "{amount}", Num.commas(t.amount), "{balance}", Num.commas(t.balance),
                            "{cause}", t.cause == null || t.cause.isEmpty() ? "-" : t.cause, "{server}", t.server == null ? "-" : t.server);
                }
            });
        });
    }

    // ------------------------------------------------------------------ sale

    private void sale(CommandSender sender, String label, String[] args) {
        dev.tmdevelopment.flaretokens.feature.Sales sales = plugin.sales();
        if (args.length < 2) {
            if (sales.active()) {
                lang().send(sender, "sale.status", "{percent}", String.valueOf(sales.percentNow()), "{time}", sales.timeLeft(),
                        "{shop}", sales.scope().equals("*") ? lang().raw("sale.every-shop") : sales.scope());
            } else {
                lang().send(sender, "sale.none");
            }
            if (perm(sender, "sale")) lang().send(sender, "sale.usage", "{label}", label);
            return;
        }
        if (!perm(sender, "sale")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args[1].equalsIgnoreCase("stop") || args[1].equalsIgnoreCase("end")) {
            if (!sales.active()) lang().send(sender, "sale.none");
            else sales.end(true);
            return;
        }
        long pct = Num.parse(args[1].replace("%", ""));
        long ms = args.length >= 3 ? dev.tmdevelopment.flaretokens.feature.Sales.duration(args[2]) : -1;
        if (pct <= 0 || pct > 100 || ms <= 0) {
            lang().send(sender, "sale.usage", "{label}", label);
            return;
        }
        String shop = args.length >= 4 ? args[3] : null;
        if (shop != null && plugin.menus().get(shop) == null) {
            lang().send(sender, "shop.not-found", "{shop}", shop);
            return;
        }
        sales.begin((int) pct, ms, shop);
    }

    // ------------------------------------------------------------------ editor

    private void editor(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "editor")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player)) {
            lang().send(sender, "general.players-only");
            return;
        }
        Player p = (Player) sender;
        if (args.length < 2) {
            new dev.tmdevelopment.flaretokens.menu.LayoutListMenu(plugin, p).open();
            return;
        }
        dev.tmdevelopment.flaretokens.menu.MenuDef def = plugin.menus().def(args[1]);
        if (def == null) {
            lang().send(p, "shop.not-found", "{shop}", args[1]);
            return;
        }
        new dev.tmdevelopment.flaretokens.menu.EditorSession(plugin, p, def).open();
    }

    // ------------------------------------------------------------------ version

    private void version(CommandSender sender, String label, String[] args) {
        if (!perm(sender, "version")) {
            lang().send(sender, "general.no-permission");
            return;
        }
        if (args.length > 1 && args[1].toLowerCase(Locale.ROOT).startsWith("perm")) {
            permissions(sender);
            return;
        }
        dev.tmdevelopment.flaretokens.feature.UpdateChecker u = plugin.updates();
        boolean[] answered = {false};
        u.check(sender.hasPermission("flaretokens.admin"), state -> {
            answered[0] = true;
            if (sender instanceof Player && !((Player) sender).isOnline()) return;
            about(sender, label, state);
        });
        if (!answered[0]) lang().send(sender, "about.checking");
    }

    /** /tokens about: version (green = latest, red = outdated), server, update notifier, hooks, permissions. */
    private void about(CommandSender sender, String label, dev.tmdevelopment.flaretokens.feature.UpdateChecker.State state) {
        dev.tmdevelopment.flaretokens.feature.UpdateChecker u = plugin.updates();
        String[] base = {"{label}", label, "{version}", u.current(), "{latest}", u.latest() == null ? "?" : u.latest(),
                "{url}", dev.tmdevelopment.flaretokens.feature.UpdateChecker.PAGE};
        lang().send(sender, "about.header", base);
        switch (state) {
            case AVAILABLE:
                lang().send(sender, "about.outdated", base);
                break;
            case LATEST:
                lang().send(sender, "about.latest", base);
                break;
            case OFF:
                lang().send(sender, "about.check-off", base);
                break;
            default:
                lang().send(sender, "about.failed", base);
        }
        String sep = lang().raw("about.separator");
        StringBuilder hooks = new StringBuilder();
        String[][] list = {{"PlaceholderAPI", String.valueOf(dev.tmdevelopment.flaretokens.util.Placeholders.papi())},
                {"LuckPerms", String.valueOf(dev.tmdevelopment.flaretokens.hook.LuckPermsHook.installed())},
                {"Vault", String.valueOf(dev.tmdevelopment.flaretokens.hook.VaultHook.available())},
                {"ItemsAdder", String.valueOf(enabled("ItemsAdder"))},
                {"ExecutableItems", String.valueOf(enabled("ExecutableItems"))}};
        for (String[] h : list) {
            if (hooks.length() > 0) hooks.append(sep);
            hooks.append(lang().raw(Boolean.parseBoolean(h[1]) ? "about.hooked" : "about.missing").replace("{hook}", h[0]));
        }
        lang().send(sender, "about.info", "{label}", label, "{version}", u.current(),
                "{server}", Bukkit.getName(), "{minecraft}", dev.tmdevelopment.flaretokens.util.Ver.text(),
                "{java}", System.getProperty("java.version", "?"), "{storage}", plugin.tokens().storage().type(),
                "{notifier}", lang().raw(u.enabled() ? "about.notifier-on" : "about.notifier-off"), "{hooks}", hooks.toString());
    }

    private static boolean enabled(String plugin) {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        return p != null && p.isEnabled();
    }

    /** Every permission of plugin.yml with its description and who has it by default. */
    private void permissions(CommandSender sender) {
        lang().send(sender, "about.permissions.header");
        for (org.bukkit.permissions.Permission perm : plugin.getDescription().getPermissions()) {
            String def;
            switch (perm.getDefault()) {
                case TRUE:
                    def = lang().raw("about.permissions.everyone");
                    break;
                case OP:
                    def = lang().raw("about.permissions.op");
                    break;
                case NOT_OP:
                    def = lang().raw("about.permissions.not-op");
                    break;
                default:
                    def = lang().raw("about.permissions.nobody");
            }
            String desc = perm.getDescription() == null || perm.getDescription().isEmpty() ? "-" : perm.getDescription();
            lang().send(sender, "about.permissions.line", "{permission}", perm.getName(), "{description}", desc, "{default}", def);
        }
    }

    private static String nameOf(Storage.Account acc, String fallback) {
        if (acc.name != null && !acc.name.isEmpty()) return acc.name;
        OfflinePlayer op = Bukkit.getOfflinePlayer(acc.uuid);
        return op.getName() != null ? op.getName() : fallback;
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                String need = s.equals("layout") ? "editor" : s.equals("about") ? "version" : s;
                if ((s.equals("help") || perm(sender, need)) && s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "shop":
                case "open":
                    return filter(new ArrayList<>(plugin.menus().names()), args[1]);
                case "editor":
                case "layout": {
                    List<String> all = new ArrayList<>(plugin.menus().names());
                    all.addAll(dev.tmdevelopment.flaretokens.menu.MenuManager.BUILTIN);
                    return filter(all, args[1]);
                }
                case "sale":
                    return filter(Arrays.asList("10", "20", "50", "stop"), args[1]);
                case "about":
                case "version":
                case "ver":
                    return perm(sender, "version") ? filter(Collections.singletonList("permissions"), args[1]) : Collections.<String>emptyList();
                case "migrate":
                    return filter(Arrays.asList("yaml", "sqlite", "mysql"), args[1]);
                case "withdraw":
                    return Arrays.asList("100", "1000", "all");
                case "top":
                case "reload":
                case "help":
                case "menu":
                case "settings":
                    return Collections.emptyList();
                default: {
                    List<String> names = players(args[1]);
                    if ((sub.equals("give") || sub.equals("add") || sub.equals("remove") || sub.equals("take")) && perm(sender, sub.equals("add") ? "give" : sub.equals("take") ? "remove" : sub)) {
                        names.add(0, "*");
                    }
                    return names;
                }
            }
        }
        if (args.length == 3) {
            switch (sub) {
                case "pay":
                case "give":
                case "add":
                case "remove":
                case "take":
                case "set":
                    return Arrays.asList("10", "100", "1000");
                case "shop":
                case "open":
                    return players(args[2]);
                case "sale":
                    return filter(Arrays.asList("30m", "1h", "2h", "1d", "7d"), args[2]);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 4 && sub.equals("sale")) return filter(new ArrayList<>(plugin.menus().names()), args[3]);
        if (args.length == 4 && (sub.equals("give") || sub.equals("remove") || sub.equals("set") || sub.equals("add") || sub.equals("take"))) {
            return Collections.singletonList("-s");
        }
        return Collections.emptyList();
    }

    private static List<String> players(String start) {
        List<String> out = new ArrayList<>();
        String s = start.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(s)) out.add(p.getName());
        return out;
    }

    private static List<String> filter(List<String> list, String start) {
        List<String> out = new ArrayList<>();
        String s = start.toLowerCase(Locale.ROOT);
        for (String x : list) if (x.toLowerCase(Locale.ROOT).startsWith(s)) out.add(x);
        return out;
    }
}

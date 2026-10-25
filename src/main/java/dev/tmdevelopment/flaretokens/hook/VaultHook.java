package dev.tmdevelopment.flaretokens.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Money through Vault, for [buy(money)] and [money_give]. Safe to call without Vault: everything just says "no".
 */
public final class VaultHook {

    private static Object economy; // net.milkbowl.vault.economy.Economy, kept as Object so this class loads without Vault

    private VaultHook() {
    }

    public static void setup() {
        economy = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        try {
            economy = Inner.find();
        } catch (Throwable ignored) {
        }
    }

    public static boolean available() {
        if (economy == null) setup();
        return economy != null;
    }

    public static boolean has(Player p, double amount) {
        if (!available()) return false;
        try {
            return Inner.has(economy, p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void withdraw(Player p, double amount) {
        if (!available()) return;
        try {
            Inner.withdraw(economy, p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void deposit(Player p, double amount) {
        if (!available()) return;
        try {
            Inner.deposit(economy, p, amount);
        } catch (Throwable ignored) {
        }
    }

    public static void set(Player p, double amount) {
        if (!available()) return;
        try {
            double now = Inner.balance(economy, p);
            if (now > amount) Inner.withdraw(economy, p, now - amount);
            else if (now < amount) Inner.deposit(economy, p, amount - now);
        } catch (Throwable ignored) {
        }
    }

    /** Only loaded when Vault is installed. */
    private static final class Inner {
        static Object find() {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return rsp == null ? null : rsp.getProvider();
        }

        static boolean has(Object eco, Player p, double amount) {
            return ((net.milkbowl.vault.economy.Economy) eco).has(p, amount);
        }

        static void withdraw(Object eco, Player p, double amount) {
            ((net.milkbowl.vault.economy.Economy) eco).withdrawPlayer(p, amount);
        }

        static void deposit(Object eco, Player p, double amount) {
            ((net.milkbowl.vault.economy.Economy) eco).depositPlayer(p, amount);
        }

        static double balance(Object eco, Player p) {
            return ((net.milkbowl.vault.economy.Economy) eco).getBalance(p);
        }
    }
}

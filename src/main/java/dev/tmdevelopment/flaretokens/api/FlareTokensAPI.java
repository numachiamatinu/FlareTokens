package dev.tmdevelopment.flaretokens.api;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.data.TokenManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * API for other plugins. Add FlareTokens as (soft)depend in your plugin.yml.
 * <pre>
 *   long tokens = FlareTokensAPI.getTokens(player);          // online player, instant
 *   FlareTokensAPI.giveTokens(player, 100);
 *   if (FlareTokensAPI.takeTokens(player, 50)) { ... }        // true if he had enough
 *   FlareTokensAPI.fetchTokens(uuid).thenAccept(t -&gt; ...);    // anyone, also offline (main thread)
 * </pre>
 * Call it from the main thread.
 */
public final class FlareTokensAPI {

    private FlareTokensAPI() {
    }

    private static TokenManager tokens() {
        FlareTokens plugin = FlareTokens.get();
        if (plugin == null || plugin.tokens() == null) throw new IllegalStateException("FlareTokens is not enabled");
        return plugin.tokens();
    }

    /** Balance of an online player (0 for a player who is not online). */
    public static long getTokens(Player player) {
        return tokens().balance(player.getUniqueId());
    }

    public static long getTokens(UUID uuid) {
        return tokens().balance(uuid);
    }

    /** Balance of any player, also offline. Completes on the main thread. */
    public static CompletableFuture<Long> fetchTokens(UUID uuid) {
        return tokens().fetch(uuid);
    }

    public static boolean hasTokens(Player player, long amount) {
        return getTokens(player) >= amount;
    }

    public static CompletableFuture<Long> giveTokens(OfflinePlayer player, long amount) {
        return tokens().give(player.getUniqueId(), player.getName(), amount, "api");
    }

    /** Removes the tokens only if the online player has enough. */
    public static boolean takeTokens(Player player, long amount) {
        return tokens().spend(player, amount, "api");
    }

    /** Removes tokens from anyone (offline too). Result: new balance, -1 if he didn't have enough. */
    public static CompletableFuture<Long> takeTokens(OfflinePlayer player, long amount) {
        return tokens().take(player.getUniqueId(), player.getName(), amount, true, "api");
    }

    public static CompletableFuture<Long> setTokens(OfflinePlayer player, long amount) {
        return tokens().set(player.getUniqueId(), player.getName(), amount, "api");
    }

    public static CompletableFuture<Long> setTokens(UUID uuid, long amount) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return tokens().set(uuid, p.getName(), amount, "api");
    }
}

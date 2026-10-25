package net.flares.flaretokens.API;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Old API of FlareTokens 3.x, kept so plugins made for it keep working.
 * New plugins: use {@link dev.tmdevelopment.flaretokens.api.FlareTokensAPI}.
 */
@Deprecated
public class TokensPlayer {

    private UUID uuid;

    public static TokensPlayer warpPlayer(UUID uuid) {
        TokensPlayer p = new TokensPlayer();
        p.uuid = uuid;
        return p;
    }

    public static TokensPlayer warpPlayer(Player player) {
        return warpPlayer(player.getUniqueId());
    }

    public TokensPlayer() {
    }

    public TokensPlayer(UUID uuid, int tokens) {
        this.uuid = uuid;
    }

    public UUID getPlayer() {
        return uuid;
    }

    public void setPlayer(UUID uuid) {
        this.uuid = uuid;
    }

    public int getTokens() {
        long v = FlareTokens.get().tokens().balanceNow(uuid);
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    public void setTokens(int tokens) {
        FlareTokens.get().tokens().set(uuid, name(), tokens, "api (3.x)");
    }

    public void objectSetTokens(int tokens) {
        setTokens(tokens);
    }

    public void giveTokens(int tokens) {
        FlareTokens.get().tokens().give(uuid, name(), tokens, "api (3.x)");
    }

    public void removeTokens(int tokens) {
        FlareTokens.get().tokens().take(uuid, name(), tokens, false, "api (3.x)");
    }

    private String name() {
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return p.getName();
    }
}

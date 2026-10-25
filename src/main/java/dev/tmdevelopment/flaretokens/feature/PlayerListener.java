package dev.tmdevelopment.flaretokens.feature;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads the balance on join, frees it on quit. */
public final class PlayerListener implements Listener {

    private final FlareTokens plugin;

    public PlayerListener(FlareTokens plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        plugin.tokens().load(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.tokens().unload(e.getPlayer().getUniqueId());
    }
}

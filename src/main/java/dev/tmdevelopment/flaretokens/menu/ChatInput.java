package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Write the value in chat" for the admin menus. "cancel" goes back. */
public final class ChatInput implements Listener {

    private static final class Ask {
        final Consumer<String> answer;
        final Runnable cancel;
        final long until;

        Ask(Consumer<String> answer, Runnable cancel, long until) {
            this.answer = answer;
            this.cancel = cancel;
            this.until = until;
        }
    }

    private final FlareTokens plugin;
    private final Map<UUID, Ask> waiting = new ConcurrentHashMap<>();

    public ChatInput(FlareTokens plugin) {
        this.plugin = plugin;
    }

    /**
     * Closes the menu and waits for a chat line (2 minutes).
     * @param prompt lang path of the question; {current} is the value now (click it to write it in the chat bar)
     */
    public void ask(Player p, String prompt, String current, Consumer<String> answer, Runnable cancel) {
        waiting.put(p.getUniqueId(), new Ask(answer, cancel, System.currentTimeMillis() + 120_000L));
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            plugin.lang().send(p, prompt, "{current}", current == null ? "" : current);
            plugin.lang().send(p, "input.hint", "{current}", current == null ? "" : current);
        });
    }

    public boolean waiting(Player p) {
        return waiting.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Ask a = waiting.remove(e.getPlayer().getUniqueId());
        if (a == null) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!e.getPlayer().isOnline()) return;
            if (System.currentTimeMillis() > a.until || text.equalsIgnoreCase("cancel")) {
                plugin.lang().send(e.getPlayer(), "input.cancelled");
                if (a.cancel != null) a.cancel.run();
                return;
            }
            a.answer.accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }
}

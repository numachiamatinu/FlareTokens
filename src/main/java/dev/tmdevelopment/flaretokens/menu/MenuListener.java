package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Clicks in the menus: items can never be taken out (except in the editor). */
public final class MenuListener implements Listener {

    private final FlareTokens plugin;

    public MenuListener(FlareTokens plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        InventoryHolder h = top.getHolder();
        if (h instanceof EditorSession) {
            ((EditorSession) h).handleClick(e);
            return;
        }
        if (h instanceof Gui) {
            ((Gui) h).handleClick(e);
            return;
        }
        if (!(h instanceof MenuSession)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        MenuSession s = (MenuSession) h;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null || clicked.getHolder() != s) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize()) return;
        String click = e.getClick() == null ? "LEFT" : e.getClick().name();
        try {
            s.click(e.getRawSlot(), click);
        } catch (Throwable t) {
            plugin.getLogger().warning("Error in menu " + s.def().id() + " (slot " + e.getRawSlot() + "): " + t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof EditorSession) return; // free drag and drop
        if (h instanceof Gui) ((Gui) h).handleDrag(e);
        else if (h instanceof MenuSession) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof EditorSession) {
            ((EditorSession) h).handleClose(e);
            return;
        }
        if (h instanceof Gui) {
            ((Gui) h).handleClose(e);
            return;
        }
        if (!(h instanceof MenuSession)) return;
        if (e.getPlayer() instanceof Player) plugin.menus().closed((Player) e.getPlayer(), (MenuSession) h);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        MenuSession s = plugin.menus().session(e.getPlayer());
        if (s != null) plugin.menus().closed(e.getPlayer(), s);
    }
}

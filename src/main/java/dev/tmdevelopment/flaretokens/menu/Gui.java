package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.config.Lang;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Admin menus (settings, editor list, palette ...): texts in lang/en.yml under gui. */
public abstract class Gui implements InventoryHolder {

    protected final FlareTokens plugin;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Gui(FlareTokens plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected abstract String title();

    protected abstract int rows();

    protected abstract void build();

    protected Lang lang() {
        return plugin.lang();
    }

    public void open() {
        String t = Compat.menuTitle(Text.legacy(Placeholders.apply(title(), viewer)));
        inventory = Bukkit.createInventory(this, rows() * 9, t);
        actions.clear();
        build();
        viewer.openInventory(inventory);
    }

    /** Opens on the next tick (safe from click and close handlers). */
    public void openLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) open();
        });
    }

    public void refresh() {
        if (inventory == null) return;
        inventory.clear();
        actions.clear();
        build();
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** A button with name and lore from lang (gui.&lt;path&gt;.name / .lore). */
    protected ItemStack button(String material, String path, String... pairs) {
        return item(material, lang().raw(path + ".name"), lang().lines(path + ".lore"), false, pairs);
    }

    protected ItemStack item(String material, String name, java.util.List<String> lore, boolean glow, String... pairs) {
        ItemSpec spec = ItemSpec.simple(material, name, lore);
        if (glow) spec.glow(true);
        return spec.build(s -> Placeholders.apply(s, viewer, pairs));
    }

    /** Gray glass on the border, like the other Flare plugins. */
    protected void fill() {
        ItemStack glass = item("GRAY_STAINED_GLASS_PANE", " ", null, false);
        int rows = inventory.getSize() / 9;
        for (int i = 0; i < inventory.getSize(); i++) {
            int row = i / 9, col = i % 9;
            boolean border = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (border && inventory.getItem(i) == null) inventory.setItem(i, glass.clone());
        }
    }

    protected Inventory inventory() {
        return inventory;
    }

    // ------------------------------------------------------------------ events

    public void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= inventory.getSize()) return;
        Consumer<InventoryClickEvent> a = actions.get(raw);
        if (a != null && MenuSession.isBuyClick(e.getClick() == null ? "LEFT" : e.getClick().name())) {
            Actions.sound(viewer, "sounds.click");
            a.accept(e);
        }
    }

    public void handleDrag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    public void handleClose(InventoryCloseEvent e) {
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}

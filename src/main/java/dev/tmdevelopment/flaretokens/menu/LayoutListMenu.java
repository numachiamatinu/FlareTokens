package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /tokens layout (or Settings > Menus and shops): pick a menu to edit, or make a new one. */
public final class LayoutListMenu extends Gui {

    private int page;

    public LayoutListMenu(FlareTokens plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return lang().raw("gui.menus.title");
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected void build() {
        // menus of the plugin
        int slot = 10;
        for (String id : MenuManager.BUILTIN) {
            MenuDef d = plugin.menus().builtin(id);
            if (d == null) continue;
            set(slot, button("KNOWLEDGE_BOOK", "gui.menus.builtin", "{menu}", id, "{title}", d.title,
                    "{description}", lang().raw("gui.menus.descriptions." + id),
                    "{state}", lang().raw(d.enabled ? "gui.common.on" : "gui.common.off")), e -> edit(d, e.isRightClick()));
            slot += 2;
        }
        // shops
        List<MenuDef> shops = new ArrayList<>(plugin.menus().shops());
        int perPage = 18;
        int from = page * perPage;
        int[] grid = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40};
        for (int i = 0; i < grid.length && from + i < shops.size(); i++) {
            MenuDef d = shops.get(from + i);
            int products = 0;
            for (MenuItem it : d.items.values()) if (it.product != null) products++;
            boolean main = d.id.equalsIgnoreCase(plugin.menus().mainShop());
            set(grid[i], button(main ? "ENDER_CHEST" : "CHEST", "gui.menus.shop", "{menu}", d.id, "{title}", d.title,
                    "{items}", String.valueOf(d.items.size()), "{products}", String.valueOf(products),
                    "{commands}", d.openCommands.isEmpty() ? "-" : "/" + String.join(", /", d.openCommands),
                    "{state}", lang().raw(d.enabled ? "gui.common.on" : "gui.common.off")), e -> edit(d, e.isRightClick()));
        }
        if (page > 0) set(45, button("ARROW", "gui.common.previous"), e -> {
            page--;
            refresh();
        });
        if (from + perPage < shops.size()) set(53, button("ARROW", "gui.common.next"), e -> {
            page++;
            refresh();
        });
        set(4, button("BOOK", "gui.menus.help"));
        set(42, button("EMERALD", "gui.menus.new"), e -> plugin.chatInput().ask(viewer, "editor.ask-new-menu", "", text -> {
            MenuDef d = plugin.menus().create(text);
            if (d == null) {
                lang().send(viewer, "editor.new-menu-taken", "{menu}", text);
                openLater();
                return;
            }
            lang().send(viewer, "editor.new-menu", "{menu}", d.id);
            new EditorSession(plugin, viewer, d).openLater();
        }, this::openLater));
        set(49, button("ARROW", "gui.common.back"), e -> new SettingsMenu(plugin, viewer).openLater());
        fill();
    }

    /** Left click: edit. Right click: open it like a player. */
    private void edit(MenuDef d, boolean preview) {
        if (preview) {
            if (d.builtin && d.id.equals(Builtins.CONFIRM)) return;
            plugin.menus().openLater(viewer, d.id, null);
            return;
        }
        new EditorSession(plugin, viewer, d).openLater();
    }
}

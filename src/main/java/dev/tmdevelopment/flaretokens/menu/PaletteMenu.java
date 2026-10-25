package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.Num;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shift + right click on an empty slot of the editor: what to put there. */
final class PaletteMenu extends Gui {

    private static final String[] GLASS = {"BLACK", "GRAY", "LIGHT_GRAY", "WHITE", "RED", "ORANGE", "YELLOW", "LIME",
            "LIGHT_BLUE", "PURPLE", "PINK"};

    private final EditorSession editor;
    private final int target;

    PaletteMenu(FlareTokens plugin, Player viewer, EditorSession editor, int target) {
        super(plugin, viewer);
        this.editor = editor;
        this.target = target;
    }

    @Override
    protected String title() {
        return lang().raw("gui.palette.title").replace("{slot}", String.valueOf(target));
    }

    @Override
    protected int rows() {
        return 6;
    }

    private void back() {
        editor.openLater();
    }

    @Override
    protected void build() {
        editor.reloadDef();
        MenuDef def = editor.def;
        // elements of the plugin menus (move them here)
        if (def.builtin) {
            int slot = 10;
            for (MenuItem it : def.items.values()) {
                if (!it.element) continue;
                if (slot == 17) slot = 19;
                if (slot >= 26) break;
                String mat = it.spec.materialUnset() ? "PAPER" : it.spec.material();
                if (mat.contains(":") || mat.startsWith("[")) mat = "PLAYER_HEAD";
                set(slot++, item(mat, "&f" + it.key, lang().lines("gui.palette.element.lore"), it.slots.isEmpty(),
                        "{amount}", String.valueOf(it.slots.size())), e -> {
                    editor.clearSlot(target);
                    ConfigurationSection s = editor.section(it.key);
                    if (s != null) {
                        List<Integer> now = new ArrayList<>(it.slots);
                        now.add(target);
                        s.set("slot", null);
                        s.set("slots", MenuDef.formatSlots(now));
                    }
                    editor.def = plugin.menus().save(editor.def);
                    back();
                });
            }
        }
        // glass panes
        int g = 28;
        for (String color : GLASS) {
            if (g == 35) g = 37;
            String mat = color + "_STAINED_GLASS_PANE";
            set(g++, item(mat, lang().raw("gui.palette.glass.name"), lang().lines("gui.palette.glass.lore"), false), e -> {
                editor.addDecoration(target, deco(mat, " ", new ArrayList<String>(), new ArrayList<String>()));
                back();
            });
        }
        // buttons
        set(46, button("BOOK", "gui.palette.link"), e -> new MenuPicker(plugin, viewer, editor, target).openLater());
        set(47, button("ARROW", "gui.palette.back-button"), e -> {
            editor.addDecoration(target, deco("ARROW", lang().raw("gui.palette.back-button.item"), new ArrayList<String>(),
                    new ArrayList<>(Arrays.asList("[back]"))));
            back();
        });
        set(48, button("BARRIER", "gui.palette.close-button"), e -> {
            editor.addDecoration(target, deco("BARRIER", lang().raw("gui.palette.close-button.item"), new ArrayList<String>(),
                    new ArrayList<>(Arrays.asList("[close]"))));
            back();
        });
        set(50, button("PLAYER_HEAD", "gui.palette.balance"), e -> {
            editor.addDecoration(target, deco("head:{player}", lang().raw("gui.palette.balance.item"),
                    lang().lines("gui.palette.balance.item-lore"), new ArrayList<String>()));
            back();
        });
        if (!def.builtin) {
            set(51, button("EMERALD", "gui.palette.sell-hand"), e -> {
                ItemStack hand = Compat.hand(viewer);
                if (hand == null || hand.getType().name().endsWith("AIR")) {
                    lang().send(viewer, "editor.empty-hand");
                    return;
                }
                ItemStack exact = EditorSession.clean(hand);
                plugin.chatInput().ask(viewer, "editor.ask-price", "", text -> {
                    long price = Num.parse(text);
                    if (price < 0) lang().send(viewer, "general.invalid-amount", "{input}", text);
                    else editor.sell(target, exact, price);
                    back();
                }, this::back);
            });
        }
        set(4, button("BOOK", "gui.palette.help"));
        set(53, button("ARROW", "gui.common.back"), e -> back());
        fill();
    }

    static Map<String, Object> deco(String material, String name, List<String> lore, List<String> actions) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("material", material);
        d.put("name", name);
        d.put("lore", lore);
        d.put("glow", false);
        d.put("admin-only", false);
        d.put("actions", actions);
        return d;
    }

    /** Pick a menu: a button that opens it goes on the slot. */
    static final class MenuPicker extends Gui {

        private final EditorSession editor;
        private final int target;

        MenuPicker(FlareTokens plugin, Player viewer, EditorSession editor, int target) {
            super(plugin, viewer);
            this.editor = editor;
            this.target = target;
        }

        @Override
        protected String title() {
            return lang().raw("gui.picker.title");
        }

        @Override
        protected int rows() {
            return 6;
        }

        @Override
        protected void build() {
            int slot = 0;
            List<MenuDef> all = new ArrayList<>(plugin.menus().shops());
            for (String id : MenuManager.BUILTIN) {
                if (id.equals(Builtins.CONFIRM)) continue;
                MenuDef d = plugin.menus().builtin(id);
                if (d != null) all.add(d);
            }
            for (MenuDef d : all) {
                if (slot >= 45) break;
                if (d.id.equalsIgnoreCase(editor.def.id)) continue;
                String title = d.title;
                set(slot++, item(d.builtin ? "KNOWLEDGE_BOOK" : "CHEST", "&f" + d.id, lang().lines("gui.picker.entry.lore"), false,
                        "{title}", title), e -> {
                    List<String> lore = new ArrayList<>(lang().lines("gui.picker.button-lore"));
                    editor.addDecoration(target, deco(d.builtin ? "KNOWLEDGE_BOOK" : "CHEST", title, lore,
                            new ArrayList<>(Arrays.asList("[open] " + d.id))));
                    editor.openLater();
                });
            }
            set(49, button("ARROW", "gui.common.back"), e -> new PaletteMenu(plugin, viewer, editor, target).openLater());
            fill();
        }
    }
}

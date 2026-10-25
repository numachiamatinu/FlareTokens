package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.Num;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shift + left click on an item in the shop editor: price, confirmation, limit, stock, permission ... */
final class ProductMenu extends Gui {

    private static final String[] RESETS = {"never", "daily", "weekly", "monthly"};

    private final EditorSession editor;
    private final String key;

    ProductMenu(FlareTokens plugin, Player viewer, EditorSession editor, String key) {
        super(plugin, viewer);
        this.editor = editor;
        this.key = key;
    }

    @Override
    protected String title() {
        return lang().raw("gui.product.title").replace("{item}", key);
    }

    @Override
    protected int rows() {
        return 5;
    }

    private ConfigurationSection s() {
        return editor.section(key);
    }

    /**
     * Changes the item in the file as it is now. The editor saves the file when this menu opens (and keeps a new
     * copy of it), so a section read while drawing can be an old copy: always read it again here.
     */
    private void change(java.util.function.Consumer<ConfigurationSection> edit) {
        editor.reloadDef();
        ConfigurationSection now = s();
        if (now == null) {
            editor.openLater();
            return;
        }
        edit.accept(now);
        editor.def = plugin.menus().save(editor.def);
        openLater();
    }

    /** Asks a value in chat, then comes back here. */
    private void ask(String prompt, String current, java.util.function.BiConsumer<ConfigurationSection, String> answer) {
        plugin.chatInput().ask(viewer, prompt, current, text -> change(now -> answer.accept(now, text)), this::openLater);
    }

    private static String next(String now) {
        for (int i = 0; i < RESETS.length; i++) if (RESETS[i].equals(now)) return RESETS[(i + 1) % RESETS.length];
        return RESETS[0];
    }

    private String onOff(boolean v) {
        return lang().raw(v ? "gui.common.on" : "gui.common.off");
    }

    @Override
    protected void build() {
        editor.reloadDef();
        ConfigurationSection s = s();
        if (s == null) {
            editor.openLater();
            return;
        }
        boolean product = s.isSet("price");
        long price = s.getLong("price", 0);
        set(4, button("NAME_TAG", "gui.product.info", "{item}", key, "{name}", String.valueOf(s.getString("name", key)),
                "{state}", lang().raw(product ? "gui.product.for-sale" : "gui.product.not-for-sale")));

        set(10, button("GOLD_INGOT", "gui.product.price", "{value}", product ? Num.commas(price) : "-"), e -> {
            if (e.isRightClick() && product) {
                change(now -> now.set("price", null));
                return;
            }
            ask("editor.ask-price", product ? String.valueOf(price) : "", (now, text) -> {
                long v = Num.parse(text);
                if (v < 0) lang().send(viewer, "general.invalid-amount", "{input}", text);
                else now.set("price", v);
            });
        });
        boolean confirm = s.getBoolean("confirm", false);
        set(11, button(confirm ? "LIME_DYE" : "GRAY_DYE", "gui.product.confirm", "{value}", onOff(confirm)),
                e -> change(now -> now.set("confirm", !now.getBoolean("confirm", false))));
        int limit = s.getInt("limit", 0);
        String limitReset = s.getString("limit-reset", "never").toLowerCase(Locale.ROOT);
        set(12, button("HOPPER", "gui.product.limit", "{value}", limit <= 0 ? "-" : String.valueOf(limit),
                "{reset}", lang().raw("gui.product.resets." + limitReset)), e -> {
            if (e.isRightClick()) {
                change(now -> now.set("limit-reset", next(now.getString("limit-reset", "never").toLowerCase(Locale.ROOT))));
                return;
            }
            ask("editor.ask-limit", String.valueOf(limit), (now, text) -> now.set("limit", (int) Math.max(0, Num.parse(text))));
        });
        int stock = s.getInt("stock", 0);
        String stockReset = s.getString("stock-reset", "never").toLowerCase(Locale.ROOT);
        set(13, button("CHEST", "gui.product.stock", "{value}", stock <= 0 ? "-" : String.valueOf(stock),
                "{reset}", lang().raw("gui.product.resets." + stockReset)), e -> {
            if (e.isRightClick()) {
                change(now -> now.set("stock-reset", next(now.getString("stock-reset", "never").toLowerCase(Locale.ROOT))));
                return;
            }
            ask("editor.ask-stock", String.valueOf(stock), (now, text) -> now.set("stock", (int) Math.max(0, Num.parse(text))));
        });
        String perm = s.getString("permission", "");
        set(14, button("TRIPWIRE_HOOK", "gui.product.permission", "{value}", perm == null || perm.isEmpty() ? "-" : perm), e -> {
            if (e.isRightClick()) {
                change(now -> now.set("permission", ""));
                return;
            }
            ask("editor.ask-permission", perm, (now, text) -> now.set("permission", text.equalsIgnoreCase("none") ? "" : text.trim()));
        });
        boolean sale = s.getBoolean("sale", true);
        set(15, button(sale ? "LIME_DYE" : "GRAY_DYE", "gui.product.sale", "{value}", onOff(sale)),
                e -> change(now -> now.set("sale", !now.getBoolean("sale", true))));
        boolean adminOnly = s.getBoolean("admin-only", false);
        set(16, button(adminOnly ? "REDSTONE_TORCH" : "LEVER", "gui.product.admin-only", "{value}", onOff(adminOnly)),
                e -> change(now -> now.set("admin-only", !now.getBoolean("admin-only", false))));

        set(20, button("OAK_SIGN", "gui.product.name", "{value}", String.valueOf(s.getString("name", ""))),
                e -> ask("editor.ask-name", s.getString("name", ""), (now, text) -> now.set("name", text)));
        List<String> cmds = new ArrayList<>(s.getStringList("commands"));
        set(21, button("COMMAND_BLOCK", "gui.product.commands", "{value}", String.valueOf(cmds.size()),
                "{last}", cmds.isEmpty() ? "-" : cmds.get(cmds.size() - 1)), e -> {
            if (e.isRightClick()) {
                change(now -> {
                    List<String> list = new ArrayList<>(now.getStringList("commands"));
                    if (!list.isEmpty()) list.remove(list.size() - 1);
                    now.set("commands", list);
                });
                return;
            }
            ask("editor.ask-command", "", (now, text) -> {
                String c = text.trim();
                if (c.startsWith("/")) c = c.substring(1);
                List<String> list = new ArrayList<>(now.getStringList("commands"));
                list.add(c);
                now.set("commands", list);
            });
        });
        List<?> give = s.getList("give");
        List<Object> giveList = give == null ? new ArrayList<Object>() : new ArrayList<Object>(give);
        set(22, button("CHEST_MINECART", "gui.product.give", "{value}", String.valueOf(giveList.size())), e -> {
            if (e.isRightClick()) {
                change(now -> {
                    List<Object> list = now.getList("give") == null ? new ArrayList<Object>() : new ArrayList<Object>(now.getList("give"));
                    if (!list.isEmpty()) list.remove(list.size() - 1);
                    now.set("give", list);
                });
                return;
            }
            ItemStack hand = Compat.hand(viewer);
            if (hand == null || hand.getType().name().endsWith("AIR")) {
                lang().send(viewer, "editor.empty-hand");
                return;
            }
            ItemStack item = EditorSession.clean(hand);
            change(now -> {
                List<Object> list = now.getList("give") == null ? new ArrayList<Object>() : new ArrayList<Object>(now.getList("give"));
                list.add(item);
                now.set("give", list);
            });
        });
        set(24, button("BARRIER", "gui.product.delete"), e -> {
            if (!e.isShiftClick()) {
                lang().send(viewer, "editor.delete-shift");
                return;
            }
            editor.reloadDef();
            editor.def.yaml.set("items." + key, null);
            editor.def = plugin.menus().save(editor.def);
            lang().send(viewer, "editor.deleted", "{item}", key);
            editor.openLater();
        });
        set(40, button("ARROW", "gui.common.back"), e -> editor.openLater());
        fill();
    }
}

package dev.tmdevelopment.flaretokens.menu;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.config.ConfigWriter;
import dev.tmdevelopment.flaretokens.feature.Sales;
import dev.tmdevelopment.flaretokens.util.Num;
import org.bukkit.entity.Player;


/** /tokens settings: every option in one menu, like the other Flare plugins. */
public final class SettingsMenu extends Gui {

    public SettingsMenu(FlareTokens plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return lang().raw("gui.settings.title");
    }

    @Override
    protected int rows() {
        return 5;
    }

    private String onOff(boolean v) {
        return lang().raw(v ? "gui.common.on" : "gui.common.off");
    }

    private void toggle(String path) {
        boolean now = plugin.getConfig().getBoolean(path, true);
        if (ConfigWriter.set(plugin, "config.yml", path, !now)) {
            plugin.applyConfig();
            lang().send(viewer, "settings.changed", "{option}", path, "{value}", String.valueOf(!now));
        }
        refresh();
    }

    private void number(String path, String prompt) {
        long now = plugin.getConfig().getLong(path, 0);
        plugin.chatInput().ask(viewer, prompt, String.valueOf(now), text -> {
            long v = Num.parse(text);
            if (v < 0) {
                lang().send(viewer, "general.invalid-amount", "{input}", text);
            } else if (ConfigWriter.set(plugin, "config.yml", path, v)) {
                plugin.applyConfig();
                lang().send(viewer, "settings.changed", "{option}", path, "{value}", String.valueOf(v));
            }
            openLater();
        }, this::openLater);
    }

    @Override
    protected void build() {
        set(4, button("NETHER_STAR", "gui.settings.info", "{version}", plugin.getDescription().getVersion(),
                "{storage}", plugin.tokens().storage().type(), "{accounts}", String.valueOf(plugin.tokens().accounts()),
                "{server}", plugin.tokens().serverName(), "{shops}", String.valueOf(plugin.menus().size())));

        String[][] toggles = {
                {"10", "EMERALD", "pay.enabled", "gui.settings.pay"},
                {"11", "ENDER_PEARL", "pay.allow-offline", "gui.settings.pay-offline"},
                {"12", "PAPER", "cheque.enabled", "gui.settings.cheques"},
                {"13", "CHEST", "shop.enabled", "gui.settings.shop"},
                {"15", "NOTE_BLOCK", "sounds.enabled", "gui.settings.sounds"},
                {"16", "COMPASS", "update-check", "gui.settings.update-check"}};
        for (String[] t : toggles) {
            String path = t[2];
            boolean v = plugin.getConfig().getBoolean(path, true);
            set(Integer.parseInt(t[0]), button(v ? t[1] : "GRAY_DYE", t[3], "{value}", onOff(v)), e -> toggle(path));
        }
        // mob drops live in drops.yml
        boolean drops = plugin.drops().enabled();
        set(14, button(drops ? "ROTTEN_FLESH" : "GRAY_DYE", "gui.settings.drops", "{value}", onOff(drops)), e -> {
            if (ConfigWriter.set(plugin, "drops.yml", "enabled", !drops)) plugin.drops().load();
            refresh();
        });

        set(19, button("GOLD_NUGGET", "gui.settings.first-join", "{value}", Num.commas(plugin.getConfig().getLong("first-join-tokens", 0))),
                e -> number("first-join-tokens", "settings.ask-first-join"));
        set(20, button("GOLD_INGOT", "gui.settings.pay-min", "{value}", Num.commas(plugin.getConfig().getLong("pay.min-amount", 1))),
                e -> number("pay.min-amount", "settings.ask-pay-min"));
        set(21, button("CLOCK", "gui.settings.pay-cooldown", "{value}", String.valueOf(plugin.getConfig().getLong("pay.cooldown-seconds", 0))),
                e -> number("pay.cooldown-seconds", "settings.ask-pay-cooldown"));
        set(22, button("LIME_STAINED_GLASS_PANE", "gui.settings.confirm-above",
                "{value}", Num.commas(plugin.getConfig().getLong("shop.confirm-above", 0))),
                e -> number("shop.confirm-above", "settings.ask-confirm-above"));
        boolean history = plugin.getConfig().getBoolean("history.enabled", true);
        set(23, button(history ? "WRITABLE_BOOK" : "GRAY_DYE", "gui.settings.history", "{value}", onOff(history),
                "{days}", String.valueOf(plugin.getConfig().getInt("history.keep-days", 90))), e -> {
            if (e.isRightClick()) number("history.keep-days", "settings.ask-history-days");
            else toggle("history.enabled");
        });
        Sales sales = plugin.sales();
        set(24, button(sales.active() ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE", "gui.settings.sale",
                "{value}", sales.active() ? "-" + sales.percentNow() + "% (" + sales.timeLeft() + ")" : lang().raw("gui.common.off")), e -> {
            if (e.isRightClick()) {
                sales.end(true);
                refresh();
                return;
            }
            plugin.chatInput().ask(viewer, "sale.ask", "20 2h", text -> {
                String[] p = text.trim().split("\\s+");
                long ms = p.length > 1 ? Sales.duration(p[1]) : -1;
                long pct = p.length > 0 ? Num.parse(p[0].replace("%", "")) : -1;
                if (pct <= 0 || pct > 100 || ms <= 0) lang().send(viewer, "sale.usage", "{label}", "tokens");
                else sales.begin((int) pct, ms, p.length > 2 ? p[2] : null);
                openLater();
            }, this::openLater);
        });
        set(25, button("ENDER_CHEST", "gui.settings.main-shop", "{value}", plugin.menus().mainShop()), e -> {
            plugin.chatInput().ask(viewer, "settings.ask-main-shop", plugin.menus().mainShop(), text -> {
                if (plugin.menus().get(text.trim()) == null) {
                    lang().send(viewer, "shop.not-found", "{shop}", text.trim());
                } else if (ConfigWriter.set(plugin, "config.yml", "shop.main-shop", text.trim())) {
                    plugin.applyConfig();
                    lang().send(viewer, "settings.changed", "{option}", "shop.main-shop", "{value}", text.trim());
                }
                openLater();
            }, this::openLater);
        });

        set(29, button("PAINTING", "gui.settings.menus", "{shops}", String.valueOf(plugin.menus().size())),
                e -> new LayoutListMenu(plugin, viewer).openLater());
        set(31, button("IRON_DOOR", "gui.settings.access"), e -> new AccessMenu(plugin, viewer).openLater());
        set(33, button("KNOWLEDGE_BOOK", "gui.settings.top"), e -> plugin.menus().openLater(viewer, Builtins.TOP, null));
        set(40, button("LIME_DYE", "gui.settings.reload"), e -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            viewer.closeInventory();
            plugin.reloadAll();
            lang().send(viewer, "general.reloaded", "{shops}", String.valueOf(plugin.menus().size()));
        }));
        set(44, button("BARRIER", "gui.common.close"), e -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory()));
        fill();
    }

    /** player-access: what normal players can open. */
    static final class AccessMenu extends Gui {

        private static final String[][] KEYS = {
                {"menu", "CHEST"}, {"shop", "EMERALD"}, {"top", "KNOWLEDGE_BOOK"}, {"history", "WRITABLE_BOOK"},
                {"pay", "GOLD_INGOT"}, {"withdraw", "PAPER"}};

        AccessMenu(FlareTokens plugin, Player viewer) {
            super(plugin, viewer);
        }

        @Override
        protected String title() {
            return lang().raw("gui.access.title");
        }

        @Override
        protected int rows() {
            return 3;
        }

        @Override
        protected void build() {
            int slot = 10;
            for (String[] k : KEYS) {
                String path = "player-access." + k[0];
                boolean v = plugin.getConfig().getBoolean(path, true);
                set(slot++, button(v ? k[1] : "GRAY_DYE", "gui.access." + k[0], "{value}", lang().raw(v ? "gui.common.on" : "gui.common.off")), e -> {
                    if (ConfigWriter.set(plugin, "config.yml", path, !v)) plugin.applyConfig();
                    refresh();
                });
                if (slot == 13) slot = 14;
            }
            set(22, button("ARROW", "gui.common.back"), e -> new SettingsMenu(plugin, viewer).openLater());
            fill();
        }
    }

}

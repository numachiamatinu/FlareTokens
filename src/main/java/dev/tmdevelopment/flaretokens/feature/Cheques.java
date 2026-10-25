package dev.tmdevelopment.flaretokens.feature;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.ItemSpec;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Ver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Token cheques (/tokens withdraw). The value is stored inside the item:
 * 1.14+ in the item data (same key as 3.x, so old cheques still work), 1.8-1.13 in an invisible lore line.
 */
public final class Cheques implements Listener {

    private static final String MARK = "§1§3§3§7§r";

    private final FlareTokens plugin;

    public Cheques(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(Player owner, long amount) {
        ItemSpec spec = ItemSpec.of(plugin.getConfig().getConfigurationSection("cheque.item"), "config.yml > cheque.item");
        String a = String.valueOf(amount);
        ItemStack item = spec.build(s -> Placeholders.apply(s, owner,
                "{amount}", a, "{amount_formatted}", Num.shortFormat(amount), "{amount_commas}", Num.commas(amount)));
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (Ver.atLeast(14)) {
            ChequeData.write(plugin, meta, amount);
        } else {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
            StringBuilder hidden = new StringBuilder(MARK);
            for (char c : a.toCharArray()) hidden.append('§').append(c);
            lore.add(hidden.toString());
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Value of a cheque, 0 if the item is not a cheque. */
    public long read(ItemStack item) {
        if (item == null) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        if (Ver.atLeast(14)) {
            long v = ChequeData.read(plugin, meta);
            if (v > 0) return v;
        }
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (!line.startsWith(MARK)) continue;
                String digits = line.substring(MARK.length()).replace("§", "");
                try {
                    return Long.parseLong(digits);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!Compat.isMainHand(e)) return;
        Player p = e.getPlayer();
        ItemStack hand = Compat.hand(p);
        long value = read(hand);
        if (value <= 0) return;
        e.setCancelled(true);
        if (!p.hasPermission("flaretokens.redeem") && !p.hasPermission("flaretokens.withdraw")) {
            plugin.lang().send(p, "general.no-permission");
            return;
        }
        // creative players can copy any item: no free tokens
        if (isCreative(p) && !p.hasPermission("flaretokens.redeem.creative")) {
            plugin.lang().send(p, "withdraw.creative");
            return;
        }
        // balance still loading (just joined)
        if (!plugin.tokens().isLoaded(p.getUniqueId())) return;
        int count = p.isSneaking() ? hand.getAmount() : 1;
        long total = value * count;
        if (total / count != value) return; // overflow
        // tokens first, then the item: nothing is lost if another plugin blocks the change
        long result = plugin.tokens().give(p.getUniqueId(), p.getName(), total, "cheque").getNow(-1L);
        if (result < 0) return;
        if (hand.getAmount() <= count) Compat.setHand(p, null);
        else {
            hand.setAmount(hand.getAmount() - count);
            Compat.setHand(p, hand);
        }
        plugin.lang().send(p, "withdraw.redeemed", "{amount}", String.valueOf(total), "{amount_commas}", Num.commas(total),
                "{amount_formatted}", Num.shortFormat(total));
        Actions.sound(p, "sounds.redeem");
    }

    private static boolean isCreative(Player p) {
        String mode = String.valueOf(p.getGameMode());
        return mode.equals("CREATIVE");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (read(e.getItemInHand()) > 0) e.setCancelled(true);
    }
}

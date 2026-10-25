package dev.tmdevelopment.flaretokens.feature;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Item data of cheques, 1.14+ only (this class is never loaded on older versions). */
final class ChequeData {

    private ChequeData() {
    }

    static void write(Plugin plugin, ItemMeta meta, long amount) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (amount <= Integer.MAX_VALUE) {
            // same key and type as FlareTokens 3.x
            pdc.set(new NamespacedKey(plugin, "token-cheque"), PersistentDataType.INTEGER, (int) amount);
        } else {
            pdc.set(new NamespacedKey(plugin, "token-cheque-long"), PersistentDataType.LONG, amount);
        }
    }

    static long read(Plugin plugin, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey small = new NamespacedKey(plugin, "token-cheque");
        if (pdc.has(small, PersistentDataType.INTEGER)) {
            Integer v = pdc.get(small, PersistentDataType.INTEGER);
            return v == null ? 0 : v;
        }
        NamespacedKey big = new NamespacedKey(plugin, "token-cheque-long");
        if (pdc.has(big, PersistentDataType.LONG)) {
            Long v = pdc.get(big, PersistentDataType.LONG);
            return v == null ? 0 : v;
        }
        return 0;
    }
}

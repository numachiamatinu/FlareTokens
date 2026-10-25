package dev.tmdevelopment.flaretokens.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** Only loaded when PlaceholderAPI is installed. */
public final class PapiHook {

    private PapiHook() {
    }

    public static String apply(OfflinePlayer player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}

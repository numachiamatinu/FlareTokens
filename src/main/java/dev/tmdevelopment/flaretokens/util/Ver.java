package dev.tmdevelopment.flaretokens.util;

import org.bukkit.Bukkit;

/**
 * Minecraft version of the server, read once.
 * 1.8.8 -> (1, 8), 1.21.4 -> (1, 21), 26.3 -> (26, 3) (new numbering, always newer than any 1.x).
 */
public final class Ver {

    private static int major = 1;
    private static int minor = 21;
    private static int patch = 0;

    static {
        try {
            String v = Bukkit.getBukkitVersion(); // 1.8.8-R0.1-SNAPSHOT, 1.21.4-R0.1-SNAPSHOT, 26.3-R0.1-SNAPSHOT
            int dash = v.indexOf('-');
            if (dash > 0) v = v.substring(0, dash);
            String[] p = v.split("\\.");
            major = Integer.parseInt(p[0].replaceAll("\\D", ""));
            if (p.length > 1) minor = Integer.parseInt(p[1].replaceAll("\\D", ""));
            if (p.length > 2) patch = Integer.parseInt(p[2].replaceAll("\\D", ""));
        } catch (Throwable ignored) {
        }
    }

    private Ver() {
    }

    /** true if the server is 1.{minor} or newer (any 26.x and later counts as newer). */
    public static boolean atLeast(int minor) {
        return major > 1 || Ver.minor >= minor;
    }

    public static boolean atLeast(int minor, int patch) {
        if (major > 1) return true;
        return Ver.minor > minor || (Ver.minor == minor && Ver.patch >= patch);
    }

    /** Hex colors in chat, items and titles: 1.16+. */
    public static boolean hex() {
        return atLeast(16);
    }

    /** Flattened material names (PURPLE_STAINED_GLASS_PANE...): 1.13+. */
    public static boolean flat() {
        return atLeast(13);
    }

    public static String text() {
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }
}

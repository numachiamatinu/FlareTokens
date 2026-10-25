package dev.tmdevelopment.flaretokens.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

/**
 * New options and messages of an update are written into the files as text, with their comments.
 * Bukkit's own save would rewrite the whole file (and drop every comment on 1.8 - 1.17).
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /** Text of a file inside the jar, null if missing. */
    public static String resource(org.bukkit.plugin.java.JavaPlugin plugin, String path) {
        try (java.io.InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Adds the missing keys to the file, copied from the default text.
     * @return false when the file could not be patched (the caller then saves it the old way)
     */
    public static boolean addMissing(File file, String defaults, List<String> keys, Logger log) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            String patched = text;
            for (String key : keys) patched = YamlPatch.addMissing(patched, defaults, key);
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(patched);
            YamlConfiguration before = new YamlConfiguration();
            before.loadFromString(text);
            for (String key : keys) if (!check.isSet(key)) return false;
            for (String key : before.getKeys(true)) {
                if (before.isConfigurationSection(key)) continue;
                Object a = before.get(key), b = check.get(key);
                if (a == null ? b != null : !a.equals(b)) return false;
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), patched.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            if (log != null) log.fine("Could not add the new options to " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}

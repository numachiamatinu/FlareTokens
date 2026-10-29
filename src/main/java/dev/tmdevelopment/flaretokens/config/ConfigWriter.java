package dev.tmdevelopment.flaretokens.config;

import dev.tmdevelopment.flaretokens.util.J8;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Changes one value of a file of the plugin (config.yml, drops.yml ...) keeping its comments. */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    /** @return true if the file now has the value */
    public static boolean set(Plugin plugin, String file, String path, Object value) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String written = value instanceof String ? YamlPatch.quote((String) value) : number(value);
            // a comment at the end of the line stays
            String changed = YamlPatch.setKeepComment(text, path, written);
            if (changed == null) changed = YamlPatch.set(text, path, written);
            if (changed.equals(text)) {
                return text.contains(path.substring(path.lastIndexOf('.') + 1) + ": " + written);
            }
            J8.write(f.toPath(), changed, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** 500 instead of 500.0, 1.5 stays 1.5. */
    static String number(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        }
        return String.valueOf(value);
    }

    public static boolean setList(Plugin plugin, String file, String path, List<String> values) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String changed = YamlPatch.setList(text, path, values);
            if (changed.equals(text)) return false;
            J8.write(f.toPath(), changed, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }
}

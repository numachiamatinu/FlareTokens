package dev.tmdevelopment.flaretokens.config;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Changes values of config.yml / drops.yml from the settings menu, keeping every comment. */
public final class ConfigWriter {

    private ConfigWriter() {
    }

    /** value: Boolean, Number or String. false if the option is not in the file. */
    public static boolean set(Plugin plugin, String file, String path, Object value) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String yaml = value instanceof String ? YamlPatch.quote((String) value) : String.valueOf(value);
            String out = YamlPatch.set(text, path, yaml);
            if (out.equals(text)) return text.contains(path.substring(path.lastIndexOf('.') + 1) + ": " + yaml);
            dev.tmdevelopment.flaretokens.util.J8.write(f.toPath(), out, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean setList(Plugin plugin, String file, String path, List<String> values) {
        File f = new File(plugin.getDataFolder(), file);
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String out = YamlPatch.setList(text, path, values);
            if (out.equals(text)) return false;
            dev.tmdevelopment.flaretokens.util.J8.write(f.toPath(), out, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not change " + path + " in " + file + ": " + e.getMessage());
            return false;
        }
    }
}

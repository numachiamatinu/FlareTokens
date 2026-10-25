package dev.tmdevelopment.flaretokens.util;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the short explanation at the top of every file up to date: the comment block of the
 * file inside the jar replaces the one of the server file, the values are never touched.
 */
public final class Headers {

    private Headers() {}

    /** The leading comment block of a file inside the jar (ends with an empty line). */
    public static String of(Plugin plugin, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return "";
            return split(new String(dev.tmdevelopment.flaretokens.util.J8.readAll(in), StandardCharsets.UTF_8))[0];
        } catch (Exception e) {
            return "";
        }
    }

    /** Rewrites the header of the server file when it differs from the one in the jar. */
    public static void sync(Plugin plugin, File file, String resource) {
        if (!file.exists()) return;
        String header = of(plugin, resource);
        if (header.isEmpty()) return;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] parts = split(text);
            if (parts[0].equals(header)) return;
            dev.tmdevelopment.flaretokens.util.J8.write(file.toPath(), header + parts[1], StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not update the explanations of " + file.getName() + ": " + e.getMessage());
        }
    }

    /** [header incl. trailing empty line, rest of the file]. */
    public static String[] split(String text) {
        String t = text.replace("\r\n", "\n");
        String[] lines = t.split("\n", -1);
        int i = 0;
        while (i < lines.length && (lines[i].startsWith("#") || dev.tmdevelopment.flaretokens.util.J8.blank(lines[i]))) i++;
        // keep the header up to the last comment line, then one empty line
        int last = i - 1;
        while (last >= 0 && dev.tmdevelopment.flaretokens.util.J8.blank(lines[last])) last--;
        StringBuilder head = new StringBuilder();
        for (int k = 0; k <= last; k++) head.append(lines[k]).append('\n');
        if (head.length() > 0) head.append('\n');
        StringBuilder rest = new StringBuilder();
        for (int k = i; k < lines.length; k++) {
            rest.append(lines[k]);
            if (k < lines.length - 1) rest.append('\n');
        }
        return new String[]{head.toString(), rest.toString()};
    }
}

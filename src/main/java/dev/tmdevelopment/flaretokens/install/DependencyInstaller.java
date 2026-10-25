package dev.tmdevelopment.flaretokens.install;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads and loads missing optional dependencies (PlaceholderAPI, Vault...) by itself.
 * Sources (in order, first that works wins): modrinth:&lt;slug&gt;, hangar:&lt;slug&gt;, github:&lt;owner/repo&gt;, url:&lt;direct link&gt;
 */
public final class DependencyInstaller {

    private static final Pattern MODRINTH_URL = Pattern.compile("\"url\"\\s*:\\s*\"(https://cdn\\.modrinth\\.com/[^\"]+?\\.jar)\"");
    private static final Pattern GITHUB_ASSET = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+?\\.jar)\"");
    private static final Pattern YAML_NAME = Pattern.compile("^name\\s*:\\s*['\"]?([^'\"#\\s]+)", Pattern.MULTILINE);

    private final FlareTokens plugin;

    public DependencyInstaller(FlareTokens plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks every configured dependency. Missing ones are downloaded async, then loaded on the main thread.
     * @param onLoaded called on the main thread with the name of every plugin loaded at runtime
     */
    public void run(Consumer<String> onLoaded) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("auto-install");
        if (sec == null || !sec.getBoolean("enabled", true)) return;
        ConfigurationSection plugins = sec.getConfigurationSection("plugins");
        if (plugins == null) return;

        for (String name : plugins.getKeys(false)) {
            ConfigurationSection p = plugins.getConfigurationSection(name);
            if (p == null || !p.getBoolean("enabled", true)) continue;
            if (Bukkit.getPluginManager().getPlugin(name) != null) continue;

            File existing = findJar(name);
            if (existing != null) {
                plugin.getLogger().warning(name + " jar found (" + existing.getName()
                        + ") but the plugin is not loaded. Check the console for its errors.");
                continue;
            }
            List<String> sources = p.getStringList("sources");
            if (sources.isEmpty()) continue;

            plugin.getLogger().info(name + " is missing, downloading it automatically...");
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                File jar = download(name, sources);
                if (jar == null) {
                    plugin.getLogger().warning("Could not download " + name + ". Install it manually or disable it in config.yml > auto-install.");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> load(name, jar, onLoaded));
            });
        }
    }

    // ------------------------------------------------------------------ download

    private File download(String name, List<String> sources) {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        for (String source : sources) {
            int idx = source.indexOf(':');
            if (idx <= 0) continue;
            String type = source.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = source.substring(idx + 1).trim();
            try {
                String url;
                if (type.equals("modrinth")) url = modrinthUrl(value);
                else if (type.equals("hangar")) url = hangarUrl(value);
                else if (type.equals("github")) url = githubUrl(value);
                else if (type.equals("url")) url = value;
                else url = null;
                if (url == null) continue;

                File tmp = new File(pluginsDir, name + ".jar.download");
                HttpURLConnection con = open(url);
                int code = con.getResponseCode();
                if (code != 200) {
                    con.disconnect();
                    plugin.getLogger().info(name + ": " + type + " answered " + code + ", trying next source.");
                    continue;
                }
                try (InputStream in = con.getInputStream()) {
                    Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                String realName = pluginName(tmp);
                if (realName == null || !realName.equalsIgnoreCase(name)) {
                    Files.deleteIfExists(tmp.toPath());
                    plugin.getLogger().info(name + ": file from " + type + " is not a valid " + name + " jar, trying next source.");
                    continue;
                }
                File jar = new File(pluginsDir, name + ".jar");
                Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Downloaded " + name + " from " + type + ".");
                return jar;
            } catch (Exception e) {
                plugin.getLogger().info(name + ": " + type + " failed (" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "), trying next source.");
            }
        }
        return null;
    }

    /** GET with redirects followed (also between different hosts). */
    private HttpURLConnection open(String url) throws Exception {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection con = (HttpURLConnection) new URL(current).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent", "TMDevelopment/FlareTokens/" + plugin.getDescription().getVersion());
            int code = con.getResponseCode();
            if (code >= 300 && code < 400 && con.getHeaderField("Location") != null) {
                current = new URL(new URL(current), con.getHeaderField("Location")).toString();
                con.disconnect();
                continue;
            }
            return con;
        }
        throw new IllegalStateException("too many redirects");
    }

    private String get(String url) throws Exception {
        HttpURLConnection con = open(url);
        if (con.getResponseCode() != 200) {
            con.disconnect();
            return null;
        }
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String modrinthUrl(String slug) throws Exception {
        String loaders = URLEncoder.encode("[\"paper\",\"spigot\",\"bukkit\"]", "UTF-8");
        String body = get("https://api.modrinth.com/v2/project/" + slug + "/version?loaders=" + loaders);
        if (body == null) return null;
        Matcher m = MODRINTH_URL.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String hangarUrl(String slug) throws Exception {
        String version = get("https://hangar.papermc.io/api/v1/projects/" + slug + "/latestrelease");
        if (version == null || version.trim().isEmpty()) return null;
        return "https://hangar.papermc.io/api/v1/projects/" + slug + "/versions/"
                + URLEncoder.encode(version.trim(), "UTF-8").replace("+", "%20") + "/PAPER/download";
    }

    private String githubUrl(String repo) throws Exception {
        String body = get("https://api.github.com/repos/" + repo + "/releases/latest");
        if (body == null) return null;
        Matcher m = GITHUB_ASSET.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Reads name: from plugin.yml / paper-plugin.yml inside the jar. */
    private String pluginName(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry e = zip.getEntry("plugin.yml");
            if (e == null) e = zip.getEntry("paper-plugin.yml");
            if (e == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            Matcher m = YAML_NAME.matcher(sb);
            return m.find() ? m.group(1) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private File findJar(String name) {
        File dir = plugin.getDataFolder().getParentFile();
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return null;
        List<File> candidates = new ArrayList<>();
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) candidates.add(f);
        }
        for (File f : candidates) {
            String n = pluginName(f);
            if (n != null && n.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ load

    private static boolean paperLoadsPlugins() {
        try {
            Class.forName("io.papermc.paper.plugin.bootstrap.PluginBootstrap");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void load(String name, File jar, Consumer<String> onLoaded) {
        if (Bukkit.getPluginManager().getPlugin(name) != null) return;
        try {
            Plugin p = Bukkit.getPluginManager().loadPlugin(jar);
            if (p == null) throw new IllegalStateException("invalid plugin file");
            // Paper 1.19.3+ calls onLoad() by itself, Spigot and older versions don't (PlaceholderAPI needs it)
            if (!paperLoadsPlugins()) p.onLoad();
            Bukkit.getPluginManager().enablePlugin(p);
            if (!p.isEnabled()) throw new IllegalStateException("plugin did not enable");
            plugin.getLogger().info(name + " installed and enabled, no restart needed.");
            onLoaded.accept(name);
        } catch (Throwable t) {
            plugin.getLogger().warning(name + " was downloaded but could not be started now ("
                    + t.getMessage() + "). It will be active after the next restart.");
        }
    }
}

package dev.tmdevelopment.flaretokens.stats;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * TMDStats v1 - anonymous usage statistics for TMDevelopment plugins.
 *
 * <p>Every 30 minutes it sends ONLY this technical data to the TMDevelopment panel:
 * plugin id and version, Minecraft version, server software (Paper, Spigot...),
 * Java version, CPU cores, online player count, online/offline mode
 * and a random server id (generated here, not linked to anything).
 * No player names, no server or player IP addresses, no chat or world content.
 *
 * <p>Turned off for all TMDevelopment plugins in {@code plugins/TMDStats/config.yml} ({@code enabled: false}).
 *
 * <p>Usage (in onEnable):
 * <pre>
 *   stats = new TMDStats(this, "flarereferrals");                 // plugin id in the panel
 *   stats.addValue("storage", () -> getConfig().getString("storage.type")); // optional
 * </pre>
 * and in onDisable: {@code if (stats != null) stats.shutdown();}
 *
 * <p>No external dependencies, Java 8+, Spigot/Paper/Purpur/Folia 1.8+.
 */
public final class TMDStats {

    /** Address of the panel that receives the data. */
    private static final String ENDPOINT = "https://tmstudio.dev/t/v1/ping";
    private static final int PROTOCOL = 1;
    private static final long PERIOD_MINUTES = 30;
    private static final int MAX_CUSTOM = 10;

    private final Plugin plugin;
    private final String pluginId;
    private final Map<String, Supplier<?>> custom = new LinkedHashMap<String, Supplier<?>>();
    private final ScheduledExecutorService scheduler;
    private final String serverUuid;
    private final boolean logErrors;
    private volatile boolean stopped;

    /** Starts the statistics (unless the server owner turned them off). */
    public TMDStats(Plugin plugin, String pluginId) {
        this(plugin, pluginId, true);
    }

    /**
     * @param pluginAllows switch of the plugin itself (e.g. {@code getConfig().getBoolean("stats", true)}):
     *                     if false nothing is sent.
     */
    public TMDStats(Plugin plugin, String pluginId, boolean pluginAllows) {
        if (plugin == null) throw new IllegalArgumentException("plugin");
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw new IllegalArgumentException("pluginId: lowercase letters, numbers and dashes only (e.g. flarereferrals)");
        }
        this.plugin = plugin;
        this.pluginId = pluginId;

        Settings s = Settings.load(new File(plugin.getDataFolder().getParentFile(), "TMDStats"), plugin);
        this.serverUuid = s.serverUuid;
        this.logErrors = s.logErrors;

        if (!s.enabled || !pluginAllows) {
            this.scheduler = null;
            this.stopped = true;
            return;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TMDStats-" + plugin.getName());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // first send after 3-6 minutes (not all servers at once), then every 30 minutes
        long first = TimeUnit.MINUTES.toMillis(3) + ThreadLocalRandom.current().nextLong(TimeUnit.MINUTES.toMillis(3));
        scheduler.scheduleAtFixedRate(this::sendSafely, first, TimeUnit.MINUTES.toMillis(PERIOD_MINUTES), TimeUnit.MILLISECONDS);
    }

    /**
     * An extra value to show in the panel (max 10), e.g. the database type in use.
     * Key: lowercase letters, numbers and _ (max 32). The value is read on every send; null = not sent.
     * NEVER use personal data (names, IPs, messages).
     */
    public TMDStats addValue(String key, Supplier<?> value) {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,31}") || value == null) throw new IllegalArgumentException("key/value");
        synchronized (custom) {
            if (custom.size() >= MAX_CUSTOM && !custom.containsKey(key)) throw new IllegalStateException("max " + MAX_CUSTOM + " values");
            custom.put(key, value);
        }
        return this;
    }

    /** true if this server sends statistics. */
    public boolean isEnabled() {
        return !stopped;
    }

    /** Call in onDisable. */
    public void shutdown() {
        stopped = true;
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ sending

    private void sendSafely() {
        if (stopped) return;
        try {
            send(buildJson());
        } catch (Throwable t) {
            if (logErrors) plugin.getLogger().log(Level.WARNING, "[TMDStats] Send failed: " + t.getMessage());
        }
    }

    String buildJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        field(sb, "protocol", PROTOCOL);
        field(sb, "plugin", pluginId);
        field(sb, "server", serverUuid);
        field(sb, "pluginVersion", plugin.getDescription().getVersion());
        field(sb, "mcVersion", mcVersion());
        field(sb, "software", Bukkit.getName());
        field(sb, "javaVersion", javaVersion());
        field(sb, "cores", Runtime.getRuntime().availableProcessors());
        field(sb, "players", players());
        field(sb, "onlineMode", Bukkit.getOnlineMode());
        List<String> pairs = new ArrayList<String>();
        synchronized (custom) {
            for (Map.Entry<String, Supplier<?>> e : custom.entrySet()) {
                Object v;
                try {
                    v = e.getValue().get();
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) continue;
                String text = String.valueOf(v);
                if (text.isEmpty()) continue;
                if (text.length() > 64) text = text.substring(0, 64);
                pairs.add(quote(e.getKey()) + ':' + (v instanceof Number ? text : quote(text)));
            }
        }
        sb.append("\"custom\":{").append(String.join(",", pairs)).append('}');
        sb.append('}');
        return sb.toString();
    }

    private static void send(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "TMDStats/" + PROTOCOL);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
                if (in != null) while (in.read() != -1) { /* drain the response */ }
            }
            if (code == 404) throw new IOException("unknown plugin or statistics turned off in the panel");
            if (code >= 400) throw new IOException("response " + code);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ data

    private static final java.util.regex.Pattern MC_VERSION = java.util.regex.Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)");

    private static String mcVersion() {
        // e.g. 1.21.1-R0.1-SNAPSHOT -> 1.21.1, 26.3.build.223 -> 26.3
        String v = Bukkit.getBukkitVersion();
        java.util.regex.Matcher m = MC_VERSION.matcher(v);
        if (m.find()) return m.group(1);
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    private static int javaVersion() {
        String v = System.getProperty("java.version", "0"); // 1.8.0_402 or 21.0.2
        try {
            if (v.startsWith("1.")) return Integer.parseInt(v.substring(2, 3));
            int dot = v.indexOf('.');
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v.replaceAll("\\D.*", ""));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int players() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void field(StringBuilder sb, String key, Object value) {
        sb.append(quote(key)).append(':');
        if (value instanceof Number || value instanceof Boolean) sb.append(value);
        else sb.append(quote(String.valueOf(value)));
        sb.append(',');
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ shared configuration

    /** plugins/TMDStats/config.yml, one file for all TMDevelopment plugins on the server. */
    private static final class Settings {
        boolean enabled = true;
        boolean logErrors = false;
        String serverUuid;

        static Settings load(File dir, Plugin plugin) {
            Settings s = new Settings();
            File file = new File(dir, "config.yml");
            try {
                if (!file.exists()) {
                    if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new IOException("folder not created");
                    s.serverUuid = UUID.randomUUID().toString();
                    try {
                        Files.write(file.toPath(), template(s.serverUuid).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        plugin.getLogger().info("[TMDStats] This server sends anonymous usage statistics to TMDevelopment "
                                + "(versions and player count, no personal data). Turn them off in plugins/TMDStats/config.yml");
                        return s;
                    } catch (FileAlreadyExistsException race) {
                        // another TMDevelopment plugin just created it: read that one
                    }
                }
                for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim().replace("\"", "").replace("'", "");
                    if (key.equals("enabled")) s.enabled = !val.equalsIgnoreCase("false");
                    else if (key.equals("log-errors")) s.logErrors = val.equalsIgnoreCase("true");
                    else if (key.equals("server-uuid")) s.serverUuid = val;
                }
                if (s.serverUuid == null || !s.serverUuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
                    s.serverUuid = UUID.randomUUID().toString();
                    Files.write(file.toPath(), template(s.serverUuid, s.enabled, s.logErrors).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // without the file nothing is sent: better off than a new id every time
                s.enabled = false;
                s.serverUuid = UUID.randomUUID().toString();
            }
            return s;
        }

        static String template(String uuid) {
            return template(uuid, true, false);
        }

        static String template(String uuid, boolean enabled, boolean logErrors) {
            return "# TMDStats - anonymous usage statistics for TMDevelopment plugins\n"
                    + "#\n"
                    + "# Every 30 minutes the TMDevelopment plugins on this server send: plugin id and version,\n"
                    + "# Minecraft version, server software, Java version, CPU cores, online player COUNT,\n"
                    + "# online/offline mode and the random id below. No player names, no IP addresses\n"
                    + "# (they are never stored), no chat or world data. It helps us see which versions to support.\n"
                    + "#\n"
                    + "# Set enabled to false to turn it off for ALL TMDevelopment plugins on this server.\n"
                    + "# Restart the server after changing this file.\n"
                    + "\n"
                    + "enabled: " + enabled + "\n"
                    + "\n"
                    + "# Random id of this server (not linked to you). Delete the line to get a new one.\n"
                    + "server-uuid: " + uuid + "\n"
                    + "\n"
                    + "# Write errors in the console when sending fails (useful only for debugging).\n"
                    + "log-errors: " + logErrors + "\n";
        }
    }
}

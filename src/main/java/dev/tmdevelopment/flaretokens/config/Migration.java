package dev.tmdevelopment.flaretokens.config;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.util.J8;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * Update from FlareTokens 3.x: the old files are saved in backup-3.x/, the settings are moved to the new config.yml,
 * the shops go to shops/ (same format), the balances stay where they are (same database / file).
 */
public final class Migration {

    private Migration() {
    }

    public static boolean isOldConfig(File config) {
        if (!config.exists()) return false;
        YamlConfiguration old = YamlConfiguration.loadConfiguration(config);
        if (old.isSet("config-version")) return false;
        return old.isSet("select_locale") || old.isSet("storage.cache_time") || old.isSet("tokens_cheque") || old.isSet("first_join_give_tokens");
    }

    public static void run(FlareTokens plugin) {
        File dir = plugin.getDataFolder();
        File config = new File(dir, "config.yml");
        if (!isOldConfig(config)) return;
        plugin.getLogger().info("FlareTokens 3.x files found: updating them to the new version...");
        try {
            File backup = new File(dir, "backup-3.x");
            if (backup.exists()) backup = new File(dir, "backup-3.x-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            if (!backup.mkdirs()) throw new IOException("can't create " + backup.getName());
            for (String name : new String[]{"config.yml", "drops.yml", "locale", "shop", "data/data.yml", "data/logs.yml"}) {
                File f = new File(dir, name);
                if (f.exists()) copy(f.toPath(), new File(backup, name).toPath());
            }

            YamlConfiguration old = YamlConfiguration.loadConfiguration(config);
            String text;
            try (InputStream in = plugin.getResource("config.yml")) {
                text = new String(J8.readAll(in), StandardCharsets.UTF_8);
            }
            String type = old.getString("storage.type", "sqlite").toLowerCase();
            if (type.equals("file")) type = "yaml";
            if (!type.equals("yaml") && !type.equals("sqlite") && !type.equals("mysql")) type = "sqlite";
            text = YamlPatch.set(text, "storage.type", type);
            text = YamlPatch.set(text, "storage.table", YamlPatch.quote(old.getString("storage.mysql.table", "flaretokens")));
            text = YamlPatch.set(text, "storage.mysql.host", YamlPatch.quote(old.getString("storage.mysql.host", "localhost")));
            text = YamlPatch.set(text, "storage.mysql.port", String.valueOf(old.getInt("storage.mysql.port", 3306)));
            text = YamlPatch.set(text, "storage.mysql.database", YamlPatch.quote(old.getString("storage.mysql.database", "minecraft")));
            text = YamlPatch.set(text, "storage.mysql.username", YamlPatch.quote(old.getString("storage.mysql.username", "root")));
            text = YamlPatch.set(text, "storage.mysql.password", YamlPatch.quote(old.getString("storage.mysql.password", "")));
            text = YamlPatch.set(text, "storage.mysql.flags", YamlPatch.quote(old.getString("storage.mysql.flags", "useSSL=false&characterEncoding=utf8")));
            text = YamlPatch.set(text, "first-join-tokens", String.valueOf(Math.max(0, old.getLong("first_join_give_tokens", 0))));
            text = YamlPatch.set(text, "pay.allow-offline", String.valueOf(old.getBoolean("configuration.allow_pay_to_offline_account", true)));
            if (old.isSet("tokens_cheque.material")) {
                text = YamlPatch.set(text, "cheque.item.material", YamlPatch.quote(old.getString("tokens_cheque.material")));
            }
            if (old.isSet("tokens_cheque.display_name")) {
                text = YamlPatch.set(text, "cheque.item.display_name", YamlPatch.quote(old.getString("tokens_cheque.display_name")));
            }
            if (old.isList("tokens_cheque.lore")) {
                text = YamlPatch.setList(text, "cheque.item.lore", old.getStringList("tokens_cheque.lore"));
            }
            text = YamlPatch.set(text, "shop.enabled", String.valueOf(old.getBoolean("shop.enable", true)));
            text = YamlPatch.set(text, "shop.main-shop", YamlPatch.quote(old.getString("shop.main_shop", "main")));
            String cmd = old.getString("shop.open_command", "tokenshop");
            text = YamlPatch.setList(text, "shop.open-command", cmd == null || cmd.trim().isEmpty()
                    ? Collections.<String>emptyList() : Collections.singletonList(cmd.trim()));
            J8.write(config.toPath(), text, StandardCharsets.UTF_8);

            // shops: same format, new folder
            File oldShops = new File(dir, "shop");
            File shops = new File(dir, "shops");
            int count = 0;
            if (oldShops.isDirectory()) {
                if (!shops.exists() && !shops.mkdirs()) throw new IOException("can't create shops/");
                File[] files = oldShops.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.getName().endsWith(".yml")) continue;
                        Files.copy(f.toPath(), new File(shops, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    }
                }
                delete(oldShops.toPath());
            }
            // old messages and the empty drops file are replaced by the new ones (copies in the backup)
            delete(new File(dir, "locale").toPath());
            Files.deleteIfExists(new File(dir, "drops.yml").toPath());
            Files.deleteIfExists(new File(dir, "data/logs.yml").toPath());

            plugin.getLogger().info("Update done: settings moved to the new config.yml, " + count + " shop(s) moved to shops/, "
                    + "balances kept (" + type + "). Old files saved in " + backup.getName() + "/");
            plugin.getLogger().info("Messages are now in lang/en.yml: if you changed the old locale/en.yml, copy your texts from the backup.");
        } catch (Exception e) {
            plugin.getLogger().severe("Could not update the FlareTokens 3.x files: " + e.getMessage()
                    + ". Nothing was deleted; check the folder plugins/FlareTokens.");
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        if (Files.isDirectory(from)) {
            List<Path> all = new ArrayList<>();
            try (Stream<Path> s = Files.walk(from)) {
                s.forEach(all::add);
            }
            for (Path p : all) {
                Path dst = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dst);
                else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } else {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(Path p) throws IOException {
        if (!Files.exists(p)) return;
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(p)) {
            s.forEach(all::add);
        }
        all.sort(Comparator.reverseOrder());
        for (Path x : all) Files.deleteIfExists(x);
    }
}

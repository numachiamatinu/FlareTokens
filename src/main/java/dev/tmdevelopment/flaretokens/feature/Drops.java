package dev.tmdevelopment.flaretokens.feature;

import dev.tmdevelopment.flaretokens.FlareTokens;
import dev.tmdevelopment.flaretokens.action.Actions;
import dev.tmdevelopment.flaretokens.util.Compat;
import dev.tmdevelopment.flaretokens.util.Headers;
import dev.tmdevelopment.flaretokens.util.Num;
import dev.tmdevelopment.flaretokens.util.Placeholders;
import dev.tmdevelopment.flaretokens.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** drops.yml: tokens for killing mobs. */
public final class Drops implements Listener {

    private static final String SPAWNER = "flaretokens-spawner";

    private final FlareTokens plugin;
    private boolean enabled;
    private boolean ignoreSpawners;
    private Set<String> disabledWorlds = Collections.emptySet();
    private final Map<String, Double> multipliers = new HashMap<>();
    private final Map<String, Drop> drops = new HashMap<>();

    private static final class Drop {
        double chance;
        String amount;
        List<String> actions;
        boolean give;
    }

    public Drops(FlareTokens plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "drops.yml");
        if (!file.exists()) plugin.saveResource("drops.yml", false);
        Headers.sync(plugin, file, "drops.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        enabled = cfg.getBoolean("enabled", false);
        ignoreSpawners = cfg.getBoolean("ignore-spawner-mobs", true);
        disabledWorlds = new HashSet<>();
        for (String w : cfg.getStringList("disabled-worlds")) disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        multipliers.clear();
        ConfigurationSection mult = cfg.getConfigurationSection("multipliers");
        if (mult != null) for (String k : mult.getKeys(true)) if (!mult.isConfigurationSection(k)) multipliers.put(k, mult.getDouble(k, 1));
        drops.clear();
        ConfigurationSection ent = cfg.getConfigurationSection("entity");
        if (ent != null) {
            for (String key : ent.getKeys(false)) {
                ConfigurationSection s = ent.getConfigurationSection(key);
                if (s == null) continue;
                Drop d = new Drop();
                d.chance = s.getDouble("chance", 100);
                d.amount = s.getString("amount", s.getString("drop_value", "1"));
                d.actions = s.isList("actions") ? s.getStringList("actions") : s.getStringList("drop_action");
                d.give = s.getBoolean("give", !s.isSet("drop_action"));
                drops.put(key.toUpperCase(Locale.ROOT), d);
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (enabled && ignoreSpawners && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            e.getEntity().setMetadata(SPAWNER, new FixedMetadataValue(plugin, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!enabled) return;
        LivingEntity entity = e.getEntity();
        if (entity instanceof Player) return;
        Player killer = entity.getKiller(); // also kills with bows, tridents...
        if (killer == null) return;
        if (disabledWorlds.contains(entity.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        if (ignoreSpawners && entity.hasMetadata(SPAWNER)) return;
        Drop d = drops.get(entity.getType().name());
        if (d == null) d = drops.get("DEFAULT");
        if (d == null) return;
        if (d.chance < 100 && ThreadLocalRandom.current().nextDouble(100) >= d.chance) return;
        long amount = Num.roll(d.amount);
        double best = 1;
        for (Map.Entry<String, Double> m : multipliers.entrySet()) {
            if (m.getValue() > best && killer.hasPermission(m.getKey())) best = m.getValue();
        }
        amount = Math.round(amount * best);
        if (amount <= 0) return;
        String a = String.valueOf(amount);
        if (d.give) plugin.tokens().give(killer.getUniqueId(), killer.getName(), amount, "drop " + entity.getType().name());
        if (d.actions != null && !d.actions.isEmpty()) {
            Actions.run(killer, d.actions, new Actions.Context("drop " + entity.getType().name(), null,
                    "{amount}", a, "{mobcoins}", a, "{tokens_dropped}", a, "{mob}", entity.getType().name()));
        } else {
            String msg = plugin.lang().raw("drops.received");
            if (!msg.isEmpty()) Compat.actionBar(killer, Text.legacy(Placeholders.apply(msg, killer, "{amount}", a)));
        }
    }
}

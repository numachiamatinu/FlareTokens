package dev.tmdevelopment.flaretokens.hook;

import dev.tmdevelopment.flaretokens.FlareTokens;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LuckPerms keeps only online players in memory, so placeholders like %luckperms_prefix% are empty for the
 * offline players of the top. This asks LuckPerms to load them first (in the background).
 * Reached by reflection: nothing is needed when LuckPerms is not installed.
 */
public final class LuckPermsHook {

    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

    private LuckPermsHook() {
    }

    public static boolean installed() {
        Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
        return lp != null && lp.isEnabled();
    }

    /**
     * Loads the players that LuckPerms doesn't have in memory. Returns false when there is nothing to load;
     * otherwise {@code done} runs on the main thread once they are ready (at most after 3 seconds).
     */
    public static boolean load(Collection<UUID> ids, Runnable done) {
        if (ids == null || ids.isEmpty() || !installed()) return false;
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
            Class<?> users = Class.forName("net.luckperms.api.model.user.UserManager");
            Object lp = provider.getMethod("get").invoke(null);
            Object manager = api.getMethod("getUserManager").invoke(lp);
            Method isLoaded = users.getMethod("isLoaded", UUID.class);
            Method loadUser = users.getMethod("loadUser", UUID.class);
            List<CompletableFuture<?>> waits = new ArrayList<>();
            for (UUID id : ids) {
                if (id == null || (Boolean) isLoaded.invoke(manager, id) || !LOADING.add(id)) continue;
                CompletableFuture<?> f = (CompletableFuture<?>) loadUser.invoke(manager, id);
                waits.add(f.whenComplete((u, e) -> LOADING.remove(id)));
            }
            if (waits.isEmpty()) return false;
            FlareTokens plugin = FlareTokens.get();
            AtomicBoolean ran = new AtomicBoolean();
            Runnable once = () -> {
                if (ran.compareAndSet(false, true) && plugin.isEnabled()) done.run();
            };
            CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]))
                    .whenComplete((v, e) -> {
                        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, once);
                    });
            Bukkit.getScheduler().runTaskLater(plugin, once, 60L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

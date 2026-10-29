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
 * LuckPerms keeps only online players in memory, and its PlaceholderAPI expansion gives nothing for offline
 * players: %luckperms_prefix% was empty for the offline players of the top. This asks LuckPerms to load them
 * (in the background) and fills their prefix, suffix and primary group itself.
 * Reached by reflection: nothing is needed when LuckPerms is not installed.
 */
public final class LuckPermsHook {

    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

    private LuckPermsHook() {
    }

    private static final String[] KEYS = {"%luckperms_prefix%", "%luckperms_suffix%", "%luckperms_primary_group_name%"};
    private static Object manager;
    private static Method getUser;
    private static Method cachedData;
    private static Method metaData;
    private static Method prefix;
    private static Method suffix;
    private static Method primaryGroup;
    private static Object groups;
    private static Method getGroup;
    private static Method friendlyName;
    private static boolean broken;

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

    /**
     * Fills %luckperms_prefix%, %luckperms_suffix% and %luckperms_primary_group_name% of a player that LuckPerms
     * has in memory (see {@link #load}). Players it doesn't have get them empty until they are loaded.
     */
    public static String fill(UUID id, String text) {
        if (id == null || text == null || text.indexOf("%luckperms_") < 0 || broken || !installed()) return text;
        boolean any = false;
        for (String k : KEYS) any |= text.contains(k);
        if (!any) return text;
        try {
            if (getUser == null) {
                Class<?> api = Class.forName("net.luckperms.api.LuckPerms");
                Class<?> users = Class.forName("net.luckperms.api.model.user.UserManager");
                Class<?> user = Class.forName("net.luckperms.api.model.user.User");
                Class<?> cached = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
                Class<?> meta = Class.forName("net.luckperms.api.cacheddata.CachedMetaData");
                Object lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
                manager = api.getMethod("getUserManager").invoke(lp);
                cachedData = user.getMethod("getCachedData");
                metaData = cached.getMethod("getMetaData");
                prefix = meta.getMethod("getPrefix");
                suffix = meta.getMethod("getSuffix");
                primaryGroup = user.getMethod("getPrimaryGroup");
                try {
                    Class<?> groupManager = Class.forName("net.luckperms.api.model.group.GroupManager");
                    groups = api.getMethod("getGroupManager").invoke(lp);
                    getGroup = groupManager.getMethod("getGroup", String.class);
                    friendlyName = Class.forName("net.luckperms.api.model.PermissionHolder").getMethod("getFriendlyName");
                } catch (Throwable t) {
                    getGroup = null; // the group name is shown as it is
                }
                getUser = users.getMethod("getUser", UUID.class);
            }
            Object user = getUser.invoke(manager, id);
            if (user == null) {
                // not loaded yet (the top draws again when it is): empty for now, not the raw placeholder
                for (String k : KEYS) text = text.replace(k, "");
                return text;
            }
            Object meta = metaData.invoke(cachedData.invoke(user));
            text = text.replace(KEYS[0], text(prefix.invoke(meta)));
            text = text.replace(KEYS[1], text(suffix.invoke(meta)));
            if (text.contains(KEYS[2])) text = text.replace(KEYS[2], groupName(text(primaryGroup.invoke(user))));
            return text;
        } catch (Throwable t) {
            broken = true; // another LuckPerms API: PlaceholderAPI does it as before
            return text;
        }
    }

    /** The display name of the group, like the LuckPerms expansion shows it. */
    private static String groupName(String name) {
        if (name.isEmpty() || getGroup == null) return name;
        try {
            Object group = getGroup.invoke(groups, name);
            if (group != null) {
                Object friendly = friendlyName.invoke(group);
                if (friendly != null) return friendly.toString();
            }
        } catch (Throwable ignored) {
            // the name as it is
        }
        return name;
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    /** LuckPerms was reloaded or restarted: look it up again. */
    public static void reset() {
        manager = null;
        getUser = null;
        broken = false;
    }
}

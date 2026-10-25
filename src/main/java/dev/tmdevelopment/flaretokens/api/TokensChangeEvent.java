package dev.tmdevelopment.flaretokens.api;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Called on the main thread before the balance of an online player changes. Cancel it to block the change.
 * (Changes to offline players happen directly in the storage and don't fire this event.)
 */
public final class TokensChangeEvent extends Event implements Cancellable {

    public enum Type { GIVE, TAKE, SET }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final long before;
    private final long after;
    private final Type type;
    private final String cause;
    private boolean cancelled;

    public TokensChangeEvent(UUID player, long before, long after, Type type, String cause) {
        this.player = player;
        this.before = before;
        this.after = after;
        this.type = type;
        this.cause = cause;
    }

    public UUID getPlayer() {
        return player;
    }

    public long getBefore() {
        return before;
    }

    public long getAfter() {
        return after;
    }

    public long getDifference() {
        return after - before;
    }

    public Type getType() {
        return type;
    }

    /** Who/what made the change, e.g. "command by Steve", "shop main", "api". */
    public String getCause() {
        return cause;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

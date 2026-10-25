package dev.tmdevelopment.flaretokens.data;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Where balances are saved. Every method is blocking and is only called from the database thread of TokenManager.
 * The layout is the same as FlareTokens 3.x (table "flaretokens": player, uuid, tokens / data/data.yml account.&lt;uuid&gt;.tokens),
 * so old data keeps working without conversion. History, purchase counters and shared values live in extra tables / files.
 */
public interface Storage {

    String type();

    void open() throws Exception;

    void close();

    /** Balance, or null when the player has no account. */
    Long get(UUID uuid) throws Exception;

    /** Creates the account if missing. Returns true when it was created now. */
    boolean create(UUID uuid, String name, long start) throws Exception;

    /** Adds (or removes, negative) and returns the new balance. Creates the account with {@code start} first if needed. */
    long add(UUID uuid, String name, long delta, long start) throws Exception;

    /** Removes the amount only if the player has enough. Returns the new balance or -1 if not enough. */
    long take(UUID uuid, long amount) throws Exception;

    void set(UUID uuid, String name, long value) throws Exception;

    void updateName(UUID uuid, String name) throws Exception;

    /** Account by uuid (with the saved name), null if unknown. */
    default Account account(UUID uuid) throws Exception {
        Long v = get(uuid);
        return v == null ? null : new Account(uuid, null, v);
    }

    /** Account by player name (case insensitive), null if unknown. */
    Account byName(String name) throws Exception;

    List<Account> top(int limit) throws Exception;

    List<Account> all() throws Exception;

    int count() throws Exception;

    /** Writes pending changes to disk (file storage). */
    default void flush() throws Exception {
    }

    // ------------------------------------------------------------------ history

    /** Saves one balance change. */
    default void history(Tx tx) throws Exception {
    }

    /** Changes of a player, newest first. */
    default List<Tx> history(UUID uuid, int offset, int limit) throws Exception {
        return Collections.emptyList();
    }

    /** Deletes history older than the given time (milliseconds). */
    default void pruneHistory(long before) throws Exception {
    }

    // ------------------------------------------------------------------ purchase counters (limits and stock)

    /** How many times the key was bought in the period. {@code who} is a uuid, or "*" for the global stock. */
    default int bought(String who, String key, String period) throws Exception {
        return 0;
    }

    /** Adds one purchase only if the count is below {@code max}. Atomic: two servers can't both take the last one. */
    default boolean reserve(String who, String key, String period, int max) throws Exception {
        return true;
    }

    /** Gives back a reserved purchase (the payment failed). */
    default void release(String who, String key, String period) throws Exception {
    }

    // ------------------------------------------------------------------ shared values (sales) and changes of other servers

    default String meta(String key) throws Exception {
        return null;
    }

    default void meta(String key, String value) throws Exception {
    }

    /** Players whose balance was changed by another server since the last call (MySQL only). */
    default List<UUID> changes(String server) throws Exception {
        return Collections.emptyList();
    }

    /** true when several servers can share this storage. */
    default boolean shared() {
        return false;
    }

    final class Account {
        public final UUID uuid;
        public final String name;
        public final long tokens;

        public Account(UUID uuid, String name, long tokens) {
            this.uuid = uuid;
            this.name = name;
            this.tokens = tokens;
        }
    }

    /** One line of the history. */
    final class Tx {
        public final long time;
        public final UUID uuid;
        public final String name;
        public final String action;
        public final long amount;
        public final long balance;
        public final String cause;
        public final String server;

        public Tx(long time, UUID uuid, String name, String action, long amount, long balance, String cause, String server) {
            this.time = time;
            this.uuid = uuid;
            this.name = name;
            this.action = action;
            this.amount = amount;
            this.balance = balance;
            this.cause = cause;
            this.server = server;
        }
    }
}

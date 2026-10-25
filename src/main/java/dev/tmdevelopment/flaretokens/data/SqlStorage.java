package dev.tmdevelopment.flaretokens.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * storage.type: sqlite / mysql / mariadb. Same table as FlareTokens 3.x:
 * {@code flaretokens (player VARCHAR(100), uuid VARCHAR(100), tokens BIGINT)}.
 * Changes are atomic SQL updates (tokens = tokens + ?), so several servers can share one MySQL database.
 * The drivers are the ones already inside the server (Spigot/Paper ship SQLite and MySQL).
 */
public final class SqlStorage implements Storage {

    private final String type;
    private final String url;
    private final Properties props = new Properties();
    private final String table;
    private final Logger log;
    private Connection connection;
    private String server = "";
    /** Random id of this server process in the feed of changes (two servers can have the same name / port). */
    private final String instance = java.util.UUID.randomUUID().toString().substring(0, 12);
    private long lastChange = -1;
    private long lastPrune;
    private int polls;

    private SqlStorage(String type, String url, String table, Logger log) {
        this.type = type;
        this.url = url;
        this.table = table.replaceAll("[^A-Za-z0-9_]", "");
        this.log = log;
    }

    public static SqlStorage sqlite(File file, String table, Logger log) {
        return new SqlStorage("sqlite", "jdbc:sqlite:" + file.getAbsolutePath(), table, log);
    }

    public static SqlStorage mysql(boolean mariadb, String host, int port, String database, String user, String password,
                                   String flags, String table, Logger log) {
        String scheme = mariadb && driverExists("org.mariadb.jdbc.Driver") ? "mariadb" : "mysql";
        String f = flags == null ? "" : flags.trim();
        if (f.startsWith("?")) f = f.substring(1);
        SqlStorage s = new SqlStorage(scheme, "jdbc:" + scheme + "://" + host + ":" + port + "/" + database + (f.isEmpty() ? "" : "?" + f), table, log);
        s.props.setProperty("user", user == null ? "" : user);
        s.props.setProperty("password", password == null ? "" : password);
        return s;
    }

    private static boolean driverExists(String cls) {
        try {
            Class.forName(cls);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public void open() throws Exception {
        if (type.equals("sqlite")) {
            Class.forName("org.sqlite.JDBC");
            File f = new File(url.substring("jdbc:sqlite:".length()));
            if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) throw new IllegalStateException("can't create " + f.getParent());
        } else if (type.equals("mariadb")) {
            Class.forName("org.mariadb.jdbc.Driver");
        } else {
            if (!driverExists("com.mysql.cj.jdbc.Driver") && !driverExists("com.mysql.jdbc.Driver")) {
                throw new IllegalStateException("no MySQL driver in this server");
            }
        }
        Connection c = connection();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (player VARCHAR(100), uuid VARCHAR(100), tokens BIGINT)");
        }
        // index on uuid (fast lookups); old 3.x tables have none
        try (Statement st = c.createStatement()) {
            if (type.equals("sqlite")) st.executeUpdate("CREATE INDEX IF NOT EXISTS " + table + "_uuid ON " + table + " (uuid)");
            else st.executeUpdate("CREATE INDEX " + table + "_uuid ON " + table + " (uuid)");
        } catch (SQLException ignored) {
            // already there
        }
        try (Statement st = c.createStatement()) {
            if (type.equals("sqlite")) st.executeUpdate("CREATE INDEX IF NOT EXISTS " + table + "_player ON " + table + " (player COLLATE NOCASE)");
            else st.executeUpdate("CREATE INDEX " + table + "_player ON " + table + " (player)");
        } catch (SQLException ignored) {
        }
        dedupe(c);
        try {
            createExtraTables(c);
        } catch (SQLException e) {
            if (log != null) log.warning("Could not create the history / purchase tables (balances work anyway): " + e.getMessage());
        }
        // 3.x created "tokens INT(16)": on MySQL that stops at 2.1 billion
        if (!type.equals("sqlite")) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " MODIFY tokens BIGINT");
            } catch (SQLException ignored) {
            }
        }
    }

    private boolean sqlite() {
        return type.equals("sqlite");
    }

    /** History, purchase counters, shared values and (MySQL) the feed of changes for the other servers. */
    private void createExtraTables(Connection c) throws SQLException {
        String id = sqlite() ? "id INTEGER PRIMARY KEY AUTOINCREMENT" : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        historyOk = create(c, "CREATE TABLE IF NOT EXISTS " + table + "_history (" + id + ", at BIGINT, uuid VARCHAR(36), player VARCHAR(100),"
                + " kind VARCHAR(16), amount BIGINT, balance BIGINT, cause VARCHAR(255), server VARCHAR(64))");
        purchasesOk = create(c, "CREATE TABLE IF NOT EXISTS " + table + "_purchases (who VARCHAR(36) NOT NULL, item VARCHAR(100) NOT NULL,"
                + " period VARCHAR(16) NOT NULL, amount INT NOT NULL, PRIMARY KEY (who, item, period))");
        metaOk = create(c, "CREATE TABLE IF NOT EXISTS " + table + "_meta (k VARCHAR(64) NOT NULL PRIMARY KEY, v TEXT)");
        changesOk = !sqlite() && create(c, "CREATE TABLE IF NOT EXISTS " + table + "_changes (" + id + ", uuid VARCHAR(36), server VARCHAR(64), at BIGINT)");
        try (Statement st = c.createStatement()) {
            if (sqlite()) st.executeUpdate("CREATE INDEX IF NOT EXISTS " + table + "_history_uuid ON " + table + "_history (uuid, at)");
            else st.executeUpdate("CREATE INDEX " + table + "_history_uuid ON " + table + "_history (uuid, at)");
        } catch (SQLException ignored) {
        }
    }

    private boolean historyOk, purchasesOk, metaOk, changesOk;

    /** One extra table: a failure is reported once and only turns that feature off. */
    private boolean create(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
            return true;
        } catch (SQLException e) {
            if (log != null) log.warning("Could not create a table (" + e.getMessage() + "): that feature is off, balances work anyway.");
            return false;
        }
    }

    /** Name of this server in the history and in the feed of changes. */
    public void server(String name) {
        this.server = name == null ? "" : name;
    }

    @Override
    public boolean shared() {
        return !sqlite();
    }

    /** Tells the other servers that a balance changed (MySQL only). */
    private void changed(UUID uuid) {
        if (!changesOk) return;
        try {
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + "_changes (uuid, server, at) VALUES (?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, instance);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            if (log != null) log.fine("Could not write the change feed: " + e.getMessage());
        }
    }

    @Override
    public List<UUID> changes(String me) throws Exception {
        if (!changesOk) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        if (now - lastPrune > 300_000L) {
            lastPrune = now;
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + "_changes WHERE at < ?")) {
                    ps.setLong(1, now - 600_000L);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        if (lastChange < 0 || ++polls % 60 == 0) {
            long max = retry(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + table + "_changes")) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            });
            // first call, or the ids started again from 1 (table emptied and MySQL restarted)
            if (lastChange < 0 || max < lastChange) {
                lastChange = max;
                return java.util.Collections.emptyList();
            }
        }
        List<UUID> out = new ArrayList<>();
        long[] max = {lastChange};
        retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, uuid, server FROM " + table + "_changes WHERE id > ? ORDER BY id LIMIT 500")) {
                ps.setLong(1, lastChange);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        max[0] = Math.max(max[0], rs.getLong(1));
                        if (instance.equals(rs.getString(3))) continue;
                        try {
                            UUID u = UUID.fromString(rs.getString(2));
                            if (!out.contains(u)) out.add(u);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return null;
        });
        lastChange = max[0];
        return out;
    }

    @Override
    public void history(Tx tx) throws Exception {
        if (!historyOk) return;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + "_history (at, uuid, player, kind, amount, balance, cause, server)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, tx.time);
                ps.setString(2, tx.uuid.toString());
                ps.setString(3, tx.name == null ? "" : tx.name);
                ps.setString(4, tx.action);
                ps.setLong(5, tx.amount);
                ps.setLong(6, tx.balance);
                ps.setString(7, tx.cause == null ? "" : (tx.cause.length() > 255 ? tx.cause.substring(0, 255) : tx.cause));
                ps.setString(8, tx.server == null ? "" : tx.server);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<Tx> history(UUID uuid, int offset, int limit) throws Exception {
        if (!historyOk) return new ArrayList<>();
        return retry(c -> {
            List<Tx> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT at, player, kind, amount, balance, cause, server FROM " + table
                    + "_history WHERE uuid = ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                ps.setInt(3, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Tx(rs.getLong(1), uuid, rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                                rs.getString(6), rs.getString(7)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void pruneHistory(long before) throws Exception {
        if (!historyOk) return;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + "_history WHERE at < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public int bought(String who, String key, String period) throws Exception {
        if (!purchasesOk) return 0;
        return retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM " + table + "_purchases WHERE who = ? AND item = ? AND period = ?")) {
                ps.setString(1, who);
                ps.setString(2, key);
                ps.setString(3, period);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public boolean reserve(String who, String key, String period, int max) throws Exception {
        if (max <= 0) return false;
        if (!purchasesOk) throw new IllegalStateException("the purchases table is missing");
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement((sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE") + " INTO " + table
                    + "_purchases (who, item, period, amount) VALUES (?, ?, ?, 0)")) {
                ps.setString(1, who);
                ps.setString(2, key);
                ps.setString(3, period);
                ps.executeUpdate();
            }
            return null;
        });
        int rows = write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + "_purchases SET amount = amount + 1"
                    + " WHERE who = ? AND item = ? AND period = ? AND amount < ?")) {
                ps.setString(1, who);
                ps.setString(2, key);
                ps.setString(3, period);
                ps.setInt(4, max);
                return ps.executeUpdate();
            }
        });
        return rows > 0;
    }

    @Override
    public void release(String who, String key, String period) throws Exception {
        if (!purchasesOk) return;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + "_purchases SET amount = amount - 1"
                    + " WHERE who = ? AND item = ? AND period = ? AND amount > 0")) {
                ps.setString(1, who);
                ps.setString(2, key);
                ps.setString(3, period);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public String meta(String key) throws Exception {
        if (!metaOk) return null;
        return retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM " + table + "_meta WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Override
    public void meta(String key, String value) throws Exception {
        if (!metaOk) return;
        write(c -> {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + "_meta WHERE k = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + table + "_meta (k, v) VALUES (?, ?)")) {
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /**
     * 3.x had no unique key: a player could end up with more rows. Keeps one row per player with the value
     * 3.x showed (the first row), so every read and write sees the same balance.
     */
    private void dedupe(Connection c) {
        try {
            List<String> dupes = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid FROM " + table + " GROUP BY uuid HAVING COUNT(*) > 1")) {
                while (rs.next()) dupes.add(rs.getString(1));
            }
            if (dupes.isEmpty()) return;
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (String id : dupes) {
                    String name = null;
                    long tokens = 0;
                    try (PreparedStatement ps = c.prepareStatement("SELECT player, tokens FROM " + table + " WHERE uuid = ? LIMIT 1")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                name = rs.getString(1);
                                tokens = rs.getLong(2);
                            }
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (player, uuid, tokens) VALUES (?, ?, ?)")) {
                        ps.setString(1, name == null ? "" : name);
                        ps.setString(2, id);
                        ps.setLong(3, tokens);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                if (log != null) log.info("Merged the duplicated rows of " + dupes.size() + " player(s) in the " + table + " table.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            if (log != null) log.warning("Could not merge duplicated rows: " + e.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null) {
            boolean ok;
            try {
                ok = !connection.isClosed() && (type.equals("sqlite") || connection.isValid(3));
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) return connection;
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
        connection = type.equals("sqlite") ? DriverManager.getConnection(url) : DriverManager.getConnection(url, props);
        return connection;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            connection = null;
        }
    }

    /**
     * Runs a query. Reads are tried again once with a fresh connection when the database closed it (MySQL timeout);
     * writes are never run twice (the connection is checked before, see connection()).
     */
    private <T> T retry(Sql<T> work) throws Exception {
        return run(work, true);
    }

    private <T> T write(Sql<T> work) throws Exception {
        return run(work, false);
    }

    private <T> T run(Sql<T> work, boolean again) throws Exception {
        try {
            return work.run(connection());
        } catch (SQLException e) {
            if (!again || type.equals("sqlite")) throw e;
            close();
            return work.run(connection());
        }
    }

    private interface Sql<T> {
        T run(Connection c) throws SQLException;
    }

    @Override
    public Long get(UUID uuid) throws Exception {
        return retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT tokens FROM " + table + " WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
    }

    @Override
    public boolean create(UUID uuid, String name, long start) throws Exception {
        if (get(uuid) != null) {
            if (name != null) updateName(uuid, name);
            return false;
        }
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (player, uuid, tokens) VALUES (?, ?, ?)")) {
                ps.setString(1, name == null ? "" : name);
                ps.setString(2, uuid.toString());
                ps.setLong(3, Math.max(0, start));
                ps.executeUpdate();
            }
            return null;
        });
        if (start > 0) changed(uuid);
        return true;
    }

    @Override
    public long add(UUID uuid, String name, long delta, long start) throws Exception {
        create(uuid, name, start);
        write(c -> {
            String sql = delta >= 0
                    ? "UPDATE " + table + " SET tokens = tokens + ? WHERE uuid = ?"
                    : "UPDATE " + table + " SET tokens = CASE WHEN tokens + ? < 0 THEN 0 ELSE tokens + ? END WHERE uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, delta);
                if (delta >= 0) {
                    ps.setString(2, uuid.toString());
                } else {
                    ps.setLong(2, delta);
                    ps.setString(3, uuid.toString());
                }
                ps.executeUpdate();
            }
            return null;
        });
        changed(uuid);
        Long now = get(uuid);
        return now == null ? 0 : now;
    }

    @Override
    public long take(UUID uuid, long amount) throws Exception {
        int rows = write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET tokens = tokens - ? WHERE uuid = ? AND tokens >= ?")) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                ps.setLong(3, amount);
                return ps.executeUpdate();
            }
        });
        if (rows <= 0) return -1;
        changed(uuid);
        Long now = get(uuid);
        return now == null ? 0 : now;
    }

    @Override
    public void set(UUID uuid, String name, long value) throws Exception {
        create(uuid, name, value);
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET tokens = ? WHERE uuid = ?")) {
                ps.setLong(1, Math.max(0, value));
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
        changed(uuid);
    }

    @Override
    public void updateName(UUID uuid, String name) throws Exception {
        if (name == null) return;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET player = ? WHERE uuid = ? AND (player IS NULL OR player <> ?)")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Account account(UUID uuid) throws Exception {
        return retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, tokens FROM " + table + " WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? account(rs) : null;
                }
            }
        });
    }

    @Override
    public Account byName(String name) throws Exception {
        return retry(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, tokens FROM " + table + " WHERE player = ?" + (type.equals("sqlite") ? " COLLATE NOCASE" : "") + " LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return account(rs);
                }
            }
        });
    }

    private Account account(ResultSet rs) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(rs.getString(1));
        } catch (Exception e) {
            return null;
        }
        return new Account(id, rs.getString(2), rs.getLong(3));
    }

    @Override
    public List<Account> top(int limit) throws Exception {
        return retry(c -> {
            List<Account> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, tokens FROM " + table + " ORDER BY tokens DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Account a = account(rs);
                        if (a != null && out.stream().noneMatch(o -> o.uuid.equals(a.uuid))) out.add(a);
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<Account> all() throws Exception {
        return retry(c -> {
            List<Account> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, player, tokens FROM " + table)) {
                while (rs.next()) {
                    Account a = account(rs);
                    if (a != null) out.add(a);
                }
            }
            return out;
        });
    }

    @Override
    public int count() throws Exception {
        return retry(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(DISTINCT uuid) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    @Override
    public String toString() {
        return type + " (" + table + ")";
    }

    Logger log() {
        return log;
    }
}

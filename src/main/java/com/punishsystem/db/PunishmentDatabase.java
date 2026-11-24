package com.punishsystem.db;

import com.punishsystem.model.Punishment;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class PunishmentDatabase implements AutoCloseable {
    private final Connection connection;

    public PunishmentDatabase(File file) throws SQLException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create database directory: " + parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS punishments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    offence TEXT NOT NULL,
                    action TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    issued_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    staff_uuid TEXT NOT NULL,
                    staff_name TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    removal_reason TEXT,
                    removed_by TEXT,
                    removed_at INTEGER DEFAULT 0
                )
                """);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_player_action ON punishments(player_uuid, action, issued_at)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punish_escalation ON punishments(player_uuid, category, offence)");
            // Durable Discord outbox. This is additive and does not alter existing punishment rows.
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS discord_outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT NOT NULL UNIQUE,
                    payload_json TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """);
        }
    }

    public synchronized long issue(UUID player, String playerName, String category, String offence,
                                   String action, long duration, long issuedAt, long expiresAt,
                                   UUID staff, String staffName) throws SQLException {
        String sql = """
            INSERT INTO punishments(player_uuid, player_name, category, offence, action, duration_ms,
                                    issued_at, expires_at, staff_uuid, staff_name)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement p = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, player.toString());
            p.setString(2, playerName);
            p.setString(3, category.toLowerCase(Locale.ROOT));
            p.setString(4, offence);
            p.setString(5, action.toUpperCase(Locale.ROOT));
            p.setLong(6, duration);
            p.setLong(7, issuedAt);
            p.setLong(8, expiresAt);
            p.setString(9, staff.toString());
            p.setString(10, staffName);
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) {
            return r.next() ? r.getLong(1) : -1;
        }
    }

    /** Expired records remain in this count; any revoked/removed record is excluded. */
    public synchronized int offenceCount(UUID player, String category, String offence) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM punishments
            WHERE player_uuid=? AND category=? AND offence=?
              AND status!='REVOKED' AND removal_reason IS NULL
            """;
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, player.toString());
            p.setString(2, category.toLowerCase(Locale.ROOT));
            p.setString(3, offence);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    public synchronized List<Punishment> history(UUID player, String action, int limit, int offset) throws SQLException {
        List<Punishment> result = new ArrayList<>();
        String sql = "SELECT * FROM punishments WHERE player_uuid=? AND action=? ORDER BY issued_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, player.toString());
            p.setString(2, action.toUpperCase(Locale.ROOT));
            p.setInt(3, limit);
            p.setInt(4, offset);
            try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(read(r)); }
        }
        return result;
    }

    public synchronized int historyCount(UUID player, String action) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT COUNT(*) FROM punishments WHERE player_uuid=? AND action=?")) {
            p.setString(1, player.toString());
            p.setString(2, action.toUpperCase(Locale.ROOT));
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    public synchronized Optional<Punishment> get(long id) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT * FROM punishments WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? Optional.of(read(r)) : Optional.empty(); }
        }
    }

    public synchronized boolean revoke(long id, String reason, String staff, boolean appeal) throws SQLException {
        String sql = """
            UPDATE punishments SET status='REVOKED', removal_reason=?, removed_by=?, removed_at=?
            WHERE id=? AND status!='REVOKED'
            """;
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, appeal ? "APPEAL_ACCEPTED" : (reason == null || reason.isBlank() ? "Staff removal" : reason));
            p.setString(2, staff);
            p.setLong(3, System.currentTimeMillis());
            p.setLong(4, id);
            return p.executeUpdate() > 0;
        }
    }

    /** Returns the current active punishment for this exact offence, if one exists. */
    public synchronized Optional<Punishment> activeOffence(UUID player, String category, String offence) throws SQLException {
        String sql = """
            SELECT * FROM punishments
            WHERE player_uuid=? AND category=? AND offence=?
              AND status='ACTIVE' AND action!='WARN'
              AND (duration_ms<0 OR expires_at>?)
            ORDER BY issued_at DESC LIMIT 1
            """;
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setString(1, player.toString());
            p.setString(2, category.toLowerCase(Locale.ROOT));
            p.setString(3, offence);
            p.setLong(4, System.currentTimeMillis());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    public synchronized List<Punishment> activeFor(UUID player, String action) throws SQLException {
        List<Punishment> result = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("""
                SELECT * FROM punishments
                WHERE player_uuid=? AND action=? AND status='ACTIVE'
                ORDER BY issued_at DESC
                """)) {
            p.setString(1, player.toString());
            p.setString(2, action.toUpperCase(Locale.ROOT));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Punishment punishment = read(r);
                    if (punishment.active()) result.add(punishment);
                }
            }
        }
        return result;
    }

    public synchronized void expireOld() throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                UPDATE punishments SET status='EXPIRED'
                WHERE status='ACTIVE' AND duration_ms>=0 AND expires_at<=? AND action!='WARN'
                """)) {
            p.setLong(1, System.currentTimeMillis());
            p.executeUpdate();
        }
    }


    public record OutboxEntry(long id, String payloadJson, int attempts) {}

    public synchronized void queueDiscordEvent(String eventId, String payloadJson) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                INSERT OR IGNORE INTO discord_outbox(event_id, payload_json, attempts, next_attempt_at, created_at)
                VALUES(?,?,0,0,?)
                """)) {
            p.setString(1, eventId);
            p.setString(2, payloadJson);
            p.setLong(3, System.currentTimeMillis());
            p.executeUpdate();
        }
    }

    public synchronized List<OutboxEntry> pendingDiscordEvents(int limit) throws SQLException {
        List<OutboxEntry> entries = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement("""
                SELECT id, payload_json, attempts FROM discord_outbox
                WHERE next_attempt_at<=? ORDER BY id ASC LIMIT ?
                """)) {
            p.setLong(1, System.currentTimeMillis());
            p.setInt(2, Math.max(1, Math.min(25, limit)));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) entries.add(new OutboxEntry(r.getLong(1), r.getString(2), r.getInt(3)));
            }
        }
        return entries;
    }

    public synchronized void completeDiscordEvent(long id) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("DELETE FROM discord_outbox WHERE id=?")) {
            p.setLong(1, id);
            p.executeUpdate();
        }
    }

    public synchronized void retryDiscordEvent(long id, int attempts, long nextAttemptAt) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("""
                UPDATE discord_outbox SET attempts=?, next_attempt_at=? WHERE id=?
                """)) {
            p.setInt(1, attempts);
            p.setLong(2, nextAttemptAt);
            p.setLong(3, id);
            p.executeUpdate();
        }
    }

    private Punishment read(ResultSet r) throws SQLException {
        return new Punishment(
                r.getLong("id"), UUID.fromString(r.getString("player_uuid")), r.getString("player_name"),
                r.getString("category"), r.getString("offence"), r.getString("action"),
                r.getLong("duration_ms"), r.getLong("issued_at"), r.getLong("expires_at"),
                UUID.fromString(r.getString("staff_uuid")), r.getString("staff_name"),
                r.getString("status"), r.getString("removal_reason"), r.getString("removed_by"),
                r.getLong("removed_at")
        );
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
}

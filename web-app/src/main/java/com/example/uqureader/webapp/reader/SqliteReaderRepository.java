package com.example.uqureader.webapp.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class SqliteReaderRepository implements ReaderRepository {
    private static final long SESSION_TTL_MS = Duration.ofDays(30).toMillis();

    private final String jdbcUrl;

    public SqliteReaderRepository(Path databasePath) throws SQLException {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            throw new SQLException("Unable to create SQLite database directory", ex);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        initialise();
    }

    @Override
    public UserSession register(String username, String password) throws SQLException {
        String normalized = normalizeUsername(username);
        if (normalized.isEmpty() || password == null || password.length() < 4) {
            throw new SQLException("Username and password are required");
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO users(username, password_hash, created_at_ms) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalized);
            statement.setString(2, PasswordHasher.hash(password));
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Unable to create user");
                }
                return createSession(connection, keys.getLong(1), normalized);
            }
        }
    }

    @Override
    public UserSession login(String username, String password) throws SQLException {
        String normalized = normalizeUsername(username);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, password_hash FROM users WHERE username=?")) {
            statement.setString(1, normalized);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !PasswordHasher.verify(password, rs.getString(2))) {
                    throw new SQLException("Invalid username or password");
                }
                return createSession(connection, rs.getLong(1), normalized);
            }
        }
    }

    @Override
    public Optional<UserSession> findSession(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT s.user_id, u.username, s.expires_at_ms FROM sessions s JOIN users u ON u.id=s.user_id "
                             + "WHERE s.token=? AND s.expires_at_ms > ?")) {
            statement.setString(1, token);
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserSession(rs.getLong(1), rs.getString(2), token, rs.getLong(3)));
            }
        }
    }

    @Override
    public void logout(String token) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE token=?")) {
            statement.setString(1, token == null ? "" : token);
            statement.executeUpdate();
        }
    }

    @Override
    public void saveReadingState(long userId, ReadingState state) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO reading_state(user_id, work_id, page_index, char_index, updated_at_ms) VALUES (?, ?, ?, ?, ?) "
                             + "ON CONFLICT(user_id, work_id) DO UPDATE SET page_index=excluded.page_index, "
                             + "char_index=excluded.char_index, updated_at_ms=excluded.updated_at_ms")) {
            statement.setLong(1, userId);
            statement.setString(2, state.workId);
            statement.setInt(3, state.pageIndex);
            statement.setInt(4, state.charIndex);
            statement.setLong(5, state.updatedAtMs);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<ReadingState> findReadingState(long userId, String workId) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT work_id, page_index, char_index, updated_at_ms FROM reading_state WHERE user_id=? AND work_id=?")) {
            statement.setLong(1, userId);
            statement.setString(2, workId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReadingState(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
            }
        }
    }

    @Override
    public int recordEvents(long userId, String sessionToken, List<ReadingEvent> events) throws SQLException {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertEvent = connection.prepareStatement(
                    "INSERT OR IGNORE INTO reading_events(user_id, session_token, client_event_id, event_type, work_id, page_index, "
                            + "token_index, lemma, pos, feature_key, char_index, visible_ms, occurred_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement upsertStats = connection.prepareStatement(
                         "INSERT INTO user_lemma_stats(user_id, lemma, pos, exposure_count, committed_count, lookup_count, "
                                 + "tts_count, total_visible_ms, first_seen_at_ms, last_seen_at_ms, last_work_id, last_char_index) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(user_id, lemma, pos) DO UPDATE SET "
                                 + "exposure_count=user_lemma_stats.exposure_count + excluded.exposure_count, "
                                 + "committed_count=user_lemma_stats.committed_count + excluded.committed_count, "
                                 + "lookup_count=user_lemma_stats.lookup_count + excluded.lookup_count, "
                                 + "tts_count=user_lemma_stats.tts_count + excluded.tts_count, "
                                 + "total_visible_ms=user_lemma_stats.total_visible_ms + excluded.total_visible_ms, "
                                 + "last_seen_at_ms=max(user_lemma_stats.last_seen_at_ms, excluded.last_seen_at_ms), "
                                 + "last_work_id=excluded.last_work_id, last_char_index=excluded.last_char_index")) {
                for (ReadingEvent event : events) {
                    bindEvent(insertEvent, userId, sessionToken, event);
                    int inserted = insertEvent.executeUpdate();
                    if (inserted == 0) {
                        continue;
                    }
                    accepted++;
                    if (!event.lemma.isBlank() && !event.pos.isBlank()) {
                        bindStats(upsertStats, userId, event);
                        upsertStats.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
        return accepted;
    }

    @Override
    public List<LemmaStat> listLemmaStats(long userId, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        List<LemmaStat> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT lemma, pos, exposure_count, committed_count, lookup_count, tts_count, "
                             + "total_visible_ms, last_seen_at_ms FROM user_lemma_stats WHERE user_id=? "
                             + "ORDER BY lookup_count DESC, committed_count ASC, lemma ASC LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setInt(2, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new LemmaStat(rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return result;
    }

    @Override
    public List<FeatureStat> listFeatureStats(long userId, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        List<FeatureStat> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT feature_key, "
                             + "SUM(CASE WHEN event_type IN ('token_exposed','token_committed') THEN 1 ELSE 0 END), "
                             + "SUM(CASE WHEN event_type='token_committed' THEN 1 ELSE 0 END), "
                             + "SUM(CASE WHEN event_type='token_lookup' THEN 1 ELSE 0 END), "
                             + "SUM(visible_ms), MAX(occurred_at_ms) "
                             + "FROM reading_events WHERE user_id=? AND feature_key<>'' "
                             + "GROUP BY feature_key ORDER BY 4 DESC, feature_key ASC LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setInt(2, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new FeatureStat(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }
        return result;
    }

    @Override
    public List<ReadingEventRecord> listLemmaEvents(long userId, String lemma, String pos,
                                                    String eventType, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 500 : Math.min(5_000, limit);
        List<ReadingEventRecord> result = new ArrayList<>();
        boolean filterType = eventType != null && !eventType.isBlank();
        String sql = "SELECT event_type, work_id, page_index, token_index, lemma, pos, feature_key, "
                + "char_index, visible_ms, occurred_at_ms FROM reading_events "
                + "WHERE user_id=? AND lemma=? AND pos=?"
                + (filterType ? " AND event_type=?" : "")
                + " ORDER BY occurred_at_ms ASC LIMIT ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, lemma == null ? "" : lemma);
            statement.setString(3, pos == null ? "" : pos);
            if (filterType) {
                statement.setString(4, eventType);
                statement.setInt(5, safeLimit);
            } else {
                statement.setInt(4, safeLimit);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new ReadingEventRecord(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getInt(8), rs.getInt(9), rs.getLong(10)));
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
    }

    private void initialise() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS users("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "username TEXT NOT NULL UNIQUE,"
                    + "password_hash TEXT NOT NULL,"
                    + "created_at_ms INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sessions("
                    + "token TEXT PRIMARY KEY,"
                    + "user_id INTEGER NOT NULL,"
                    + "created_at_ms INTEGER NOT NULL,"
                    + "expires_at_ms INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS reading_state("
                    + "user_id INTEGER NOT NULL,"
                    + "work_id TEXT NOT NULL,"
                    + "page_index INTEGER NOT NULL DEFAULT 0,"
                    + "char_index INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at_ms INTEGER NOT NULL,"
                    + "PRIMARY KEY(user_id, work_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS reading_events("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "user_id INTEGER NOT NULL,"
                    + "session_token TEXT NOT NULL,"
                    + "client_event_id TEXT NOT NULL,"
                    + "event_type TEXT NOT NULL,"
                    + "work_id TEXT NOT NULL,"
                    + "page_index INTEGER NOT NULL DEFAULT -1,"
                    + "token_index INTEGER NOT NULL DEFAULT -1,"
                    + "lemma TEXT NOT NULL DEFAULT '',"
                    + "pos TEXT NOT NULL DEFAULT '',"
                    + "feature_key TEXT NOT NULL DEFAULT '',"
                    + "char_index INTEGER NOT NULL DEFAULT -1,"
                    + "visible_ms INTEGER NOT NULL DEFAULT 0,"
                    + "occurred_at_ms INTEGER NOT NULL,"
                    + "UNIQUE(user_id, client_event_id))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS reading_events_user_time_idx ON reading_events(user_id, occurred_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS reading_events_lemma_time_idx ON reading_events(user_id, lemma, pos, event_type, occurred_at_ms)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_lemma_stats("
                    + "user_id INTEGER NOT NULL,"
                    + "lemma TEXT NOT NULL,"
                    + "pos TEXT NOT NULL,"
                    + "exposure_count INTEGER NOT NULL DEFAULT 0,"
                    + "committed_count INTEGER NOT NULL DEFAULT 0,"
                    + "lookup_count INTEGER NOT NULL DEFAULT 0,"
                    + "tts_count INTEGER NOT NULL DEFAULT 0,"
                    + "total_visible_ms INTEGER NOT NULL DEFAULT 0,"
                    + "first_seen_at_ms INTEGER NOT NULL,"
                    + "last_seen_at_ms INTEGER NOT NULL,"
                    + "last_work_id TEXT NOT NULL DEFAULT '',"
                    + "last_char_index INTEGER NOT NULL DEFAULT -1,"
                    + "PRIMARY KEY(user_id, lemma, pos))");
        }
    }

    private UserSession createSession(Connection connection, long userId, String username) throws SQLException {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expiresAtMs = now + SESSION_TTL_MS;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sessions(token, user_id, created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, token);
            statement.setLong(2, userId);
            statement.setLong(3, now);
            statement.setLong(4, expiresAtMs);
            statement.executeUpdate();
        }
        return new UserSession(userId, username, token, expiresAtMs);
    }

    private void bindEvent(PreparedStatement statement, long userId, String sessionToken, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, sessionToken == null ? "" : sessionToken);
        statement.setString(3, event.clientEventId);
        statement.setString(4, event.eventType);
        statement.setString(5, event.workId);
        statement.setInt(6, event.pageIndex);
        statement.setInt(7, event.tokenIndex);
        statement.setString(8, event.lemma);
        statement.setString(9, event.pos);
        statement.setString(10, event.featureKey);
        statement.setInt(11, event.charIndex);
        statement.setInt(12, event.visibleMs);
        statement.setLong(13, event.occurredAtMs);
    }

    private void bindStats(PreparedStatement statement, long userId, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, event.lemma);
        statement.setString(3, event.pos);
        statement.setLong(4, isExposure(event) ? 1 : 0);
        statement.setLong(5, "token_committed".equals(event.eventType) ? 1 : 0);
        statement.setLong(6, "token_lookup".equals(event.eventType) ? 1 : 0);
        statement.setLong(7, "token_tts_played".equals(event.eventType) ? 1 : 0);
        statement.setLong(8, event.visibleMs);
        statement.setLong(9, event.occurredAtMs);
        statement.setLong(10, event.occurredAtMs);
        statement.setString(11, event.workId);
        statement.setInt(12, event.charIndex);
    }

    private boolean isExposure(ReadingEvent event) {
        return "token_exposed".equals(event.eventType) || "token_committed".equals(event.eventType);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static String normalizeUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

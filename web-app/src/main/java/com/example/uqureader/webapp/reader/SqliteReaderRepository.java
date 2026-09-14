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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                long expiresAtMs = System.currentTimeMillis() + SESSION_TTL_MS;
                refreshSession(connection, token, expiresAtMs);
                return Optional.of(new UserSession(rs.getLong(1), rs.getString(2), token, expiresAtMs));
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
                    "INSERT OR IGNORE INTO reading_events(user_id, session_token, client_event_id, event_type, work_id, language, page_index, "
                            + "token_index, lemma, pos, feature_key, char_index, visible_ms, occurred_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
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
                                 + "last_work_id=excluded.last_work_id, last_char_index=excluded.last_char_index");
                 PreparedStatement upsertScopedLemmaStats = connection.prepareStatement(
                         "INSERT INTO user_lemma_scope_stats(user_id, language, work_id, lemma, pos, exposure_count, committed_count, lookup_count, "
                                 + "tts_count, total_visible_ms, first_seen_at_ms, last_seen_at_ms, last_char_index) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(user_id, language, work_id, lemma, pos) DO UPDATE SET "
                                 + "exposure_count=user_lemma_scope_stats.exposure_count + excluded.exposure_count, "
                                 + "committed_count=user_lemma_scope_stats.committed_count + excluded.committed_count, "
                                 + "lookup_count=user_lemma_scope_stats.lookup_count + excluded.lookup_count, "
                                 + "tts_count=user_lemma_scope_stats.tts_count + excluded.tts_count, "
                                 + "total_visible_ms=user_lemma_scope_stats.total_visible_ms + excluded.total_visible_ms, "
                                 + "last_seen_at_ms=max(user_lemma_scope_stats.last_seen_at_ms, excluded.last_seen_at_ms), "
                                 + "last_char_index=excluded.last_char_index");
                 PreparedStatement upsertScopedFeatureStats = connection.prepareStatement(
                         "INSERT INTO user_feature_scope_stats(user_id, language, work_id, feature_key, exposure_count, committed_count, lookup_count, "
                                 + "total_visible_ms, first_seen_at_ms, last_seen_at_ms) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                 + "ON CONFLICT(user_id, language, work_id, feature_key) DO UPDATE SET "
                                 + "exposure_count=user_feature_scope_stats.exposure_count + excluded.exposure_count, "
                                 + "committed_count=user_feature_scope_stats.committed_count + excluded.committed_count, "
                                 + "lookup_count=user_feature_scope_stats.lookup_count + excluded.lookup_count, "
                                 + "total_visible_ms=user_feature_scope_stats.total_visible_ms + excluded.total_visible_ms, "
                                 + "last_seen_at_ms=max(user_feature_scope_stats.last_seen_at_ms, excluded.last_seen_at_ms)")) {
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
                        bindScopedLemmaStats(upsertScopedLemmaStats, userId, event);
                        upsertScopedLemmaStats.executeUpdate();
                    }
                    if (!event.featureKey.isBlank()) {
                        bindScopedFeatureStats(upsertScopedFeatureStats, userId, event);
                        upsertScopedFeatureStats.executeUpdate();
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
    public List<LemmaStat> listLemmaStats(long userId, String language, String workId, String sort, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String orderBy = lemmaStatsOrderBy(sort);
        List<LemmaStat> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT lemma, pos, SUM(exposure_count), SUM(committed_count), SUM(lookup_count), SUM(tts_count), "
                             + "SUM(total_visible_ms), MAX(last_seen_at_ms) FROM user_lemma_scope_stats "
                             + "WHERE user_id=? AND (?='' OR language=?) AND (?='' OR work_id=?) "
                             + "GROUP BY lemma, pos ORDER BY " + orderBy + " LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setString(2, safeLanguage);
            statement.setString(3, safeLanguage);
            statement.setString(4, safeWorkId);
            statement.setString(5, safeWorkId);
            statement.setInt(6, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new LemmaStat(rs.getString(1), rs.getString(2), safeLanguage, safeWorkId, rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        return result;
    }

    @Override
    public List<FeatureStat> listFeatureStats(long userId, String language, String workId, String sort, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String orderBy = featureStatsOrderBy(sort);
        List<FeatureStat> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT feature_key, SUM(exposure_count), SUM(committed_count), SUM(lookup_count), "
                             + "SUM(total_visible_ms), MAX(last_seen_at_ms) FROM user_feature_scope_stats "
                             + "WHERE user_id=? AND (?='' OR language=?) AND (?='' OR work_id=?) "
                             + "GROUP BY feature_key ORDER BY " + orderBy + " LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setString(2, safeLanguage);
            statement.setString(3, safeLanguage);
            statement.setString(4, safeWorkId);
            statement.setString(5, safeWorkId);
            statement.setInt(6, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new FeatureStat(rs.getString(1), safeLanguage, safeWorkId, rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }
        return result;
    }

    @Override
    public List<ReadingEventRecord> listLemmaEvents(long userId, String lemma, String pos,
                                                    String language, String workId, String eventType, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 500 : Math.min(5_000, limit);
        List<ReadingEventRecord> result = new ArrayList<>();
        boolean filterType = eventType != null && !eventType.isBlank();
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String sql = "SELECT event_type, work_id, page_index, token_index, lemma, pos, feature_key, "
                + "char_index, visible_ms, occurred_at_ms FROM reading_events "
                + "WHERE user_id=? AND lemma=? AND pos=?"
                + " AND (?='' OR language=?) AND (?='' OR work_id=?)"
                + (filterType ? " AND event_type=?" : "")
                + " ORDER BY occurred_at_ms ASC LIMIT ?";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, normalizeLemma(lemma));
            statement.setString(3, pos == null ? "" : pos);
            statement.setString(4, safeLanguage);
            statement.setString(5, safeLanguage);
            statement.setString(6, safeWorkId);
            statement.setString(7, safeWorkId);
            if (filterType) {
                statement.setString(8, eventType);
                statement.setInt(9, safeLimit);
            } else {
                statement.setInt(8, safeLimit);
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

    private static String lemmaStatsOrderBy(String sort) {
        return switch (sort == null ? "" : sort) {
            case "frequent" -> "SUM(exposure_count) DESC, SUM(committed_count) DESC, lemma ASC";
            case "read" -> "SUM(committed_count) DESC, SUM(exposure_count) DESC, lemma ASC";
            case "opened" -> "SUM(lookup_count) DESC, SUM(exposure_count) DESC, lemma ASC";
            default -> "CASE WHEN SUM(committed_count) > 0 THEN CAST(SUM(lookup_count) AS REAL) / SUM(committed_count) "
                    + "ELSE CAST(SUM(lookup_count) AS REAL) END DESC, SUM(lookup_count) DESC, SUM(exposure_count) DESC, lemma ASC";
        };
    }

    private static String featureStatsOrderBy(String sort) {
        return switch (sort == null ? "" : sort) {
            case "frequent" -> "SUM(exposure_count) DESC, SUM(committed_count) DESC, feature_key ASC";
            case "read" -> "SUM(committed_count) DESC, SUM(exposure_count) DESC, feature_key ASC";
            case "opened" -> "SUM(lookup_count) DESC, SUM(exposure_count) DESC, feature_key ASC";
            default -> "CASE WHEN SUM(committed_count) > 0 THEN CAST(SUM(lookup_count) AS REAL) / SUM(committed_count) "
                    + "ELSE CAST(SUM(lookup_count) AS REAL) END DESC, SUM(lookup_count) DESC, SUM(exposure_count) DESC, feature_key ASC";
        };
    }

    @Override
    public List<TimelinePoint> listLemmaTimeline(long userId, String lemma, String pos, String language,
                                                 String workId, String eventType, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 2_000 : Math.min(10_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String safeType = eventType == null ? "" : eventType;
        List<TimelinePoint> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_type, (occurred_at_ms / 3600000) * 3600000 AS bucket_start, COUNT(*), SUM(visible_ms), "
                             + "MIN(occurred_at_ms), MAX(occurred_at_ms) FROM reading_events "
                             + "WHERE user_id=? AND lemma=? AND pos=? AND (?='' OR language=?) AND (?='' OR work_id=?) "
                             + "AND (?='' OR event_type=?) "
                             + "GROUP BY event_type, bucket_start ORDER BY bucket_start ASC, event_type ASC LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setString(2, normalizeLemma(lemma));
            statement.setString(3, pos == null ? "" : pos);
            statement.setString(4, safeLanguage);
            statement.setString(5, safeLanguage);
            statement.setString(6, safeWorkId);
            statement.setString(7, safeWorkId);
            statement.setString(8, safeType);
            statement.setString(9, safeType);
            statement.setInt(10, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new TimelinePoint(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }
        return result;
    }

    @Override
    public List<TimelinePoint> listFeatureTimeline(long userId, String featureKey, String language,
                                                   String workId, String eventType, int limit) throws SQLException {
        int safeLimit = limit <= 0 ? 2_000 : Math.min(10_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String safeType = eventType == null ? "" : eventType;
        List<TimelinePoint> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT event_type, (occurred_at_ms / 3600000) * 3600000 AS bucket_start, COUNT(*), SUM(visible_ms), "
                             + "MIN(occurred_at_ms), MAX(occurred_at_ms) FROM reading_events "
                             + "WHERE user_id=? AND feature_key=? AND (?='' OR language=?) AND (?='' OR work_id=?) "
                             + "AND (?='' OR event_type=?) "
                             + "GROUP BY event_type, bucket_start ORDER BY bucket_start ASC, event_type ASC LIMIT ?")) {
            statement.setLong(1, userId);
            statement.setString(2, featureKey == null ? "" : featureKey);
            statement.setString(3, safeLanguage);
            statement.setString(4, safeLanguage);
            statement.setString(5, safeWorkId);
            statement.setString(6, safeWorkId);
            statement.setString(7, safeType);
            statement.setString(8, safeType);
            statement.setInt(9, safeLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new TimelinePoint(rs.getString(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }
        return result;
    }

    @Override
    public void refreshScopedStats(Map<String, String> workLanguages) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM user_lemma_scope_stats");
                    statement.executeUpdate("INSERT INTO user_lemma_scope_stats(user_id, language, work_id, lemma, pos, "
                            + "exposure_count, committed_count, lookup_count, tts_count, total_visible_ms, first_seen_at_ms, last_seen_at_ms, last_char_index) "
                            + "SELECT user_id, CASE WHEN language<>'' THEN language WHEN work_id LIKE '%elnet%' OR work_id LIKE '%puncheryshte%' THEN 'mhr' ELSE 'tt' END, work_id, lower(lemma), pos, "
                            + "SUM(CASE WHEN event_type IN ('token_exposed','token_committed') THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN event_type='token_committed' THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN event_type='token_lookup' THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN event_type='token_tts_played' THEN 1 ELSE 0 END), "
                            + "SUM(visible_ms), MIN(occurred_at_ms), MAX(occurred_at_ms), MAX(char_index) "
                            + "FROM reading_events WHERE lemma<>'' AND pos<>'' "
                            + "GROUP BY user_id, CASE WHEN language<>'' THEN language WHEN work_id LIKE '%elnet%' OR work_id LIKE '%puncheryshte%' THEN 'mhr' ELSE 'tt' END, work_id, lower(lemma), pos");
                    statement.executeUpdate("DELETE FROM user_feature_scope_stats");
                    statement.executeUpdate("INSERT INTO user_feature_scope_stats(user_id, language, work_id, feature_key, "
                            + "exposure_count, committed_count, lookup_count, total_visible_ms, first_seen_at_ms, last_seen_at_ms) "
                            + "SELECT user_id, CASE WHEN language<>'' THEN language WHEN work_id LIKE '%elnet%' OR work_id LIKE '%puncheryshte%' THEN 'mhr' ELSE 'tt' END, work_id, feature_key, "
                            + "SUM(CASE WHEN event_type IN ('token_exposed','token_committed') THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN event_type='token_committed' THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN event_type='token_lookup' THEN 1 ELSE 0 END), "
                            + "SUM(visible_ms), MIN(occurred_at_ms), MAX(occurred_at_ms) "
                            + "FROM reading_events WHERE feature_key<>'' "
                            + "GROUP BY user_id, CASE WHEN language<>'' THEN language WHEN work_id LIKE '%elnet%' OR work_id LIKE '%puncheryshte%' THEN 'mhr' ELSE 'tt' END, work_id, feature_key");
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
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
                    + "language TEXT NOT NULL DEFAULT '',"
                    + "page_index INTEGER NOT NULL DEFAULT -1,"
                    + "token_index INTEGER NOT NULL DEFAULT -1,"
                    + "lemma TEXT NOT NULL DEFAULT '',"
                    + "pos TEXT NOT NULL DEFAULT '',"
                    + "feature_key TEXT NOT NULL DEFAULT '',"
                    + "char_index INTEGER NOT NULL DEFAULT -1,"
                    + "visible_ms INTEGER NOT NULL DEFAULT 0,"
                    + "occurred_at_ms INTEGER NOT NULL,"
                    + "UNIQUE(user_id, client_event_id))");
            addColumnIfMissing(statement, "ALTER TABLE reading_events ADD COLUMN language TEXT NOT NULL DEFAULT ''");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS reading_events_user_time_idx ON reading_events(user_id, occurred_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS reading_events_lemma_time_idx ON reading_events(user_id, lemma, pos, event_type, occurred_at_ms)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS reading_events_scope_time_idx ON reading_events(user_id, language, work_id, occurred_at_ms)");
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
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_lemma_scope_stats("
                    + "user_id INTEGER NOT NULL,"
                    + "language TEXT NOT NULL,"
                    + "work_id TEXT NOT NULL,"
                    + "lemma TEXT NOT NULL,"
                    + "pos TEXT NOT NULL,"
                    + "exposure_count INTEGER NOT NULL DEFAULT 0,"
                    + "committed_count INTEGER NOT NULL DEFAULT 0,"
                    + "lookup_count INTEGER NOT NULL DEFAULT 0,"
                    + "tts_count INTEGER NOT NULL DEFAULT 0,"
                    + "total_visible_ms INTEGER NOT NULL DEFAULT 0,"
                    + "first_seen_at_ms INTEGER NOT NULL,"
                    + "last_seen_at_ms INTEGER NOT NULL,"
                    + "last_char_index INTEGER NOT NULL DEFAULT -1,"
                    + "PRIMARY KEY(user_id, language, work_id, lemma, pos))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS user_lemma_scope_problem_idx ON user_lemma_scope_stats(user_id, language, work_id, lookup_count DESC, committed_count ASC)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS user_feature_scope_stats("
                    + "user_id INTEGER NOT NULL,"
                    + "language TEXT NOT NULL,"
                    + "work_id TEXT NOT NULL,"
                    + "feature_key TEXT NOT NULL,"
                    + "exposure_count INTEGER NOT NULL DEFAULT 0,"
                    + "committed_count INTEGER NOT NULL DEFAULT 0,"
                    + "lookup_count INTEGER NOT NULL DEFAULT 0,"
                    + "total_visible_ms INTEGER NOT NULL DEFAULT 0,"
                    + "first_seen_at_ms INTEGER NOT NULL,"
                    + "last_seen_at_ms INTEGER NOT NULL,"
                    + "PRIMARY KEY(user_id, language, work_id, feature_key))");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS user_feature_scope_problem_idx ON user_feature_scope_stats(user_id, language, work_id, lookup_count DESC, committed_count ASC)");
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

    private void refreshSession(Connection connection, String token, long expiresAtMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sessions SET expires_at_ms=? WHERE token=?")) {
            statement.setLong(1, expiresAtMs);
            statement.setString(2, token);
            statement.executeUpdate();
        }
    }

    private void bindEvent(PreparedStatement statement, long userId, String sessionToken, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, sessionToken == null ? "" : sessionToken);
        statement.setString(3, event.clientEventId);
        statement.setString(4, event.eventType);
        statement.setString(5, event.workId);
        statement.setString(6, eventLanguage(event));
        statement.setInt(7, event.pageIndex);
        statement.setInt(8, event.tokenIndex);
        statement.setString(9, normalizeLemma(event.lemma));
        statement.setString(10, event.pos);
        statement.setString(11, event.featureKey);
        statement.setInt(12, event.charIndex);
        statement.setInt(13, event.visibleMs);
        statement.setLong(14, event.occurredAtMs);
    }

    private void bindStats(PreparedStatement statement, long userId, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, normalizeLemma(event.lemma));
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

    private void bindScopedLemmaStats(PreparedStatement statement, long userId, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, eventLanguage(event));
        statement.setString(3, event.workId);
        statement.setString(4, normalizeLemma(event.lemma));
        statement.setString(5, event.pos);
        statement.setLong(6, isExposure(event) ? 1 : 0);
        statement.setLong(7, "token_committed".equals(event.eventType) ? 1 : 0);
        statement.setLong(8, "token_lookup".equals(event.eventType) ? 1 : 0);
        statement.setLong(9, "token_tts_played".equals(event.eventType) ? 1 : 0);
        statement.setLong(10, event.visibleMs);
        statement.setLong(11, event.occurredAtMs);
        statement.setLong(12, event.occurredAtMs);
        statement.setInt(13, event.charIndex);
    }

    private void bindScopedFeatureStats(PreparedStatement statement, long userId, ReadingEvent event) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, eventLanguage(event));
        statement.setString(3, event.workId);
        statement.setString(4, event.featureKey);
        statement.setLong(5, isExposure(event) ? 1 : 0);
        statement.setLong(6, "token_committed".equals(event.eventType) ? 1 : 0);
        statement.setLong(7, "token_lookup".equals(event.eventType) ? 1 : 0);
        statement.setLong(8, event.visibleMs);
        statement.setLong(9, event.occurredAtMs);
        statement.setLong(10, event.occurredAtMs);
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

    private static String normalizeLemma(String value) {
        return value == null ? "" : Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String normalizeScope(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String eventLanguage(ReadingEvent event) {
        String language = normalizeScope(event.language);
        if (!language.isBlank()) {
            return language;
        }
        return inferLanguage(event.workId);
    }

    private static String inferLanguage(String workId) {
        String value = workId == null ? "" : workId.toLowerCase(Locale.ROOT);
        if (value.contains("elnet") || value.contains("puncheryshte")) {
            return "mhr";
        }
        return "tt";
    }

    private static void addColumnIfMissing(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException ex) {
            if (!ex.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                throw ex;
            }
        }
    }
}

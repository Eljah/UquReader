package com.example.uqureader.webapp.reader;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryReaderRepository implements ReaderRepository {
    private static final long SESSION_TTL_MS = Duration.ofDays(30).toMillis();

    private final AtomicLong nextUserId = new AtomicLong(1);
    private final Map<String, UserRecord> usersByName = new ConcurrentHashMap<>();
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReadingState> readingStates = new ConcurrentHashMap<>();
    private final Set<String> seenClientEvents = ConcurrentHashMap.newKeySet();
    private final List<StoredEvent> rawEvents = new ArrayList<>();
    private final Map<String, LemmaStats> lemmaStats = new HashMap<>();

    @Override
    public synchronized UserSession register(String username, String password) throws SQLException {
        String normalized = normalizeUsername(username);
        if (normalized.isEmpty() || password == null || password.length() < 4) {
            throw new SQLException("Username and password are required");
        }
        if (usersByName.containsKey(normalized)) {
            throw new SQLException("User already exists");
        }
        UserRecord user = new UserRecord(nextUserId.getAndIncrement(), normalized, PasswordHasher.hash(password));
        usersByName.put(normalized, user);
        return createSession(user);
    }

    @Override
    public synchronized UserSession login(String username, String password) throws SQLException {
        UserRecord user = usersByName.get(normalizeUsername(username));
        if (user == null || !PasswordHasher.verify(password, user.passwordHash)) {
            throw new SQLException("Invalid username or password");
        }
        return createSession(user);
    }

    @Override
    public Optional<UserSession> findSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        UserSession session = sessions.get(token);
        if (session == null || session.expiresAtMs < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    @Override
    public synchronized void saveReadingState(long userId, ReadingState state) {
        readingStates.put(userId + ":" + state.workId, state);
    }

    @Override
    public Optional<ReadingState> findReadingState(long userId, String workId) {
        return Optional.ofNullable(readingStates.get(userId + ":" + workId));
    }

    @Override
    public synchronized int recordEvents(long userId, String sessionToken, List<ReadingEvent> events) {
        int accepted = 0;
        if (events == null) {
            return 0;
        }
        for (ReadingEvent event : events) {
            String clientId = event.clientEventId.isBlank()
                    ? UUID.randomUUID().toString()
                    : event.clientEventId;
            String dedupeKey = userId + ":" + clientId;
            if (!seenClientEvents.add(dedupeKey)) {
                continue;
            }
            rawEvents.add(new StoredEvent(userId, event));
            upsertLemmaStats(userId, event);
            accepted++;
        }
        return accepted;
    }

    @Override
    public synchronized List<LemmaStat> listLemmaStats(long userId, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        List<LemmaStat> result = new ArrayList<>();
        for (LemmaStats stats : lemmaStats.values()) {
            if (stats.userId != userId) {
                continue;
            }
            result.add(new LemmaStat(stats.lemma, stats.pos, stats.exposureCount, stats.committedCount,
                    stats.lookupCount, stats.ttsCount, stats.totalVisibleMs, stats.lastSeenAtMs));
        }
        result.sort(Comparator.comparingLong((LemmaStat stat) -> stat.lookupCount).reversed()
                .thenComparingLong(stat -> stat.committedCount)
                .thenComparing(stat -> stat.lemma));
        return result.subList(0, Math.min(result.size(), safeLimit));
    }

    @Override
    public synchronized List<FeatureStat> listFeatureStats(long userId, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        Map<String, FeatureBucket> buckets = new HashMap<>();
        for (StoredEvent stored : rawEvents) {
            ReadingEvent event = stored.event;
            if (stored.userId != userId || event.featureKey.isBlank()) {
                continue;
            }
            FeatureBucket bucket = buckets.computeIfAbsent(event.featureKey, FeatureBucket::new);
            if ("token_lookup".equals(event.eventType)) {
                bucket.lookupCount++;
            } else if ("token_committed".equals(event.eventType)) {
                bucket.committedCount++;
                bucket.exposureCount++;
            } else if ("token_exposed".equals(event.eventType)) {
                bucket.exposureCount++;
            }
            bucket.totalVisibleMs += event.visibleMs;
            bucket.lastSeenAtMs = Math.max(bucket.lastSeenAtMs, event.occurredAtMs);
        }
        List<FeatureStat> result = new ArrayList<>();
        for (FeatureBucket bucket : buckets.values()) {
            result.add(new FeatureStat(bucket.featureKey, bucket.exposureCount, bucket.committedCount,
                    bucket.lookupCount, bucket.totalVisibleMs, bucket.lastSeenAtMs));
        }
        result.sort(Comparator.comparingLong((FeatureStat stat) -> stat.lookupCount).reversed()
                .thenComparing(stat -> stat.featureKey));
        return result.subList(0, Math.min(result.size(), safeLimit));
    }

    @Override
    public synchronized List<ReadingEventRecord> listLemmaEvents(long userId, String lemma, String pos,
                                                                 String eventType, int limit) {
        int safeLimit = limit <= 0 ? 500 : Math.min(5_000, limit);
        List<ReadingEventRecord> result = new ArrayList<>();
        String safeLemma = lemma == null ? "" : lemma;
        String safePos = pos == null ? "" : pos;
        String safeType = eventType == null ? "" : eventType;
        for (StoredEvent stored : rawEvents) {
            ReadingEvent event = stored.event;
            if (stored.userId != userId) {
                continue;
            }
            if (!safeLemma.equals(event.lemma) || !safePos.equals(event.pos)) {
                continue;
            }
            if (!safeType.isBlank() && !safeType.equals(event.eventType)) {
                continue;
            }
            result.add(toRecord(event));
        }
        result.sort(Comparator.comparingLong(record -> record.occurredAtMs));
        return result.subList(0, Math.min(result.size(), safeLimit));
    }

    public synchronized int rawEventCount() {
        return rawEvents.size();
    }

    public synchronized int lemmaStatsCount() {
        return lemmaStats.size();
    }

    @Override
    public void close() {
        sessions.clear();
    }

    private UserSession createSession(UserRecord user) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        UserSession session = new UserSession(user.id, user.username, token, System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, session);
        return session;
    }

    private void upsertLemmaStats(long userId, ReadingEvent event) {
        if (event.lemma.isBlank() || event.pos.isBlank()) {
            return;
        }
        String key = userId + ":" + event.lemma + ":" + event.pos;
        LemmaStats stats = lemmaStats.computeIfAbsent(key, ignored -> new LemmaStats(userId, event.lemma, event.pos));
        if ("token_lookup".equals(event.eventType)) {
            stats.lookupCount++;
        } else if ("token_committed".equals(event.eventType)) {
            stats.committedCount++;
            stats.exposureCount++;
        } else if ("token_exposed".equals(event.eventType)) {
            stats.exposureCount++;
        } else if ("token_tts_played".equals(event.eventType)) {
            stats.ttsCount++;
        }
        stats.totalVisibleMs += event.visibleMs;
        stats.lastSeenAtMs = Math.max(stats.lastSeenAtMs, event.occurredAtMs);
        if (stats.firstSeenAtMs == 0) {
            stats.firstSeenAtMs = event.occurredAtMs;
        }
    }

    private static String normalizeUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static ReadingEventRecord toRecord(ReadingEvent event) {
        return new ReadingEventRecord(event.eventType, event.workId, event.pageIndex, event.tokenIndex,
                event.lemma, event.pos, event.featureKey, event.charIndex, event.visibleMs, event.occurredAtMs);
    }

    private record UserRecord(long id, String username, String passwordHash) {
    }

    private record StoredEvent(long userId, ReadingEvent event) {
    }

    private static final class FeatureBucket {
        final String featureKey;
        long exposureCount;
        long committedCount;
        long lookupCount;
        long totalVisibleMs;
        long lastSeenAtMs;

        FeatureBucket(String featureKey) {
            this.featureKey = featureKey;
        }
    }

    private static final class LemmaStats {
        final long userId;
        final String lemma;
        final String pos;
        long exposureCount;
        long committedCount;
        long lookupCount;
        long ttsCount;
        long totalVisibleMs;
        long firstSeenAtMs;
        long lastSeenAtMs;

        LemmaStats(long userId, String lemma, String pos) {
            this.userId = userId;
            this.lemma = lemma;
            this.pos = pos;
        }
    }
}

package com.example.uqureader.webapp.reader;

import java.sql.SQLException;
import java.time.Duration;
import java.text.Normalizer;
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
    private final Map<String, FeatureBucket> featureStats = new HashMap<>();

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
        UserSession refreshed = new UserSession(session.userId, session.username, session.sessionToken,
                System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, refreshed);
        return Optional.of(refreshed);
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
            upsertFeatureStats(userId, event);
            accepted++;
        }
        return accepted;
    }

    @Override
    public synchronized List<LemmaStat> listLemmaStats(long userId, String language, String workId, String sort, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        Map<String, LemmaStats> buckets = new HashMap<>();
        for (LemmaStats stats : lemmaStats.values()) {
            if (stats.userId != userId || !matchesScope(stats.language, safeLanguage) || !matchesScope(stats.workId, safeWorkId)) {
                continue;
            }
            String key = stats.lemma + ":" + stats.pos;
            buckets.computeIfAbsent(key, ignored -> new LemmaStats(userId, safeLanguage, safeWorkId, stats.lemma, stats.pos))
                    .merge(stats);
        }
        List<LemmaStat> result = new ArrayList<>();
        for (LemmaStats stats : buckets.values()) {
            result.add(new LemmaStat(stats.lemma, stats.pos, safeLanguage, safeWorkId, stats.exposureCount, stats.committedCount,
                    stats.lookupCount, stats.ttsCount, stats.totalVisibleMs, stats.lastSeenAtMs));
        }
        result.sort(lemmaStatComparator(sort));
        return result.subList(0, Math.min(result.size(), safeLimit));
    }

    @Override
    public synchronized List<FeatureStat> listFeatureStats(long userId, String language, String workId, String sort, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(1_000, limit);
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        Map<String, FeatureBucket> buckets = new HashMap<>();
        for (FeatureBucket stats : featureStats.values()) {
            if (stats.userId != userId || !matchesScope(stats.language, safeLanguage) || !matchesScope(stats.workId, safeWorkId)) {
                continue;
            }
            buckets.computeIfAbsent(stats.featureKey, ignored -> new FeatureBucket(userId, safeLanguage, safeWorkId, stats.featureKey))
                    .merge(stats);
        }
        List<FeatureStat> result = new ArrayList<>();
        for (FeatureBucket bucket : buckets.values()) {
            result.add(new FeatureStat(bucket.featureKey, safeLanguage, safeWorkId, bucket.exposureCount, bucket.committedCount,
                    bucket.lookupCount, bucket.totalVisibleMs, bucket.lastSeenAtMs));
        }
        result.sort(featureStatComparator(sort));
        return result.subList(0, Math.min(result.size(), safeLimit));
    }

    private static Comparator<LemmaStat> lemmaStatComparator(String sort) {
        return switch (sort == null ? "" : sort) {
            case "frequent" -> Comparator.comparingLong((LemmaStat stat) -> stat.exposureCount).reversed()
                    .thenComparing(Comparator.comparingLong((LemmaStat stat) -> stat.committedCount).reversed())
                    .thenComparing(stat -> stat.lemma);
            case "read" -> Comparator.comparingLong((LemmaStat stat) -> stat.committedCount).reversed()
                    .thenComparing(Comparator.comparingLong((LemmaStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.lemma);
            case "opened" -> Comparator.comparingLong((LemmaStat stat) -> stat.lookupCount).reversed()
                    .thenComparing(Comparator.comparingLong((LemmaStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.lemma);
            default -> Comparator.comparingDouble((LemmaStat stat) -> lookupToReadRatio(stat)).reversed()
                    .thenComparing(Comparator.comparingLong((LemmaStat stat) -> stat.lookupCount).reversed())
                    .thenComparing(Comparator.comparingLong((LemmaStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.lemma);
        };
    }

    private static Comparator<FeatureStat> featureStatComparator(String sort) {
        return switch (sort == null ? "" : sort) {
            case "frequent" -> Comparator.comparingLong((FeatureStat stat) -> stat.exposureCount).reversed()
                    .thenComparing(Comparator.comparingLong((FeatureStat stat) -> stat.committedCount).reversed())
                    .thenComparing(stat -> stat.featureKey);
            case "read" -> Comparator.comparingLong((FeatureStat stat) -> stat.committedCount).reversed()
                    .thenComparing(Comparator.comparingLong((FeatureStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.featureKey);
            case "opened" -> Comparator.comparingLong((FeatureStat stat) -> stat.lookupCount).reversed()
                    .thenComparing(Comparator.comparingLong((FeatureStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.featureKey);
            default -> Comparator.comparingDouble((FeatureStat stat) -> lookupToReadRatio(stat)).reversed()
                    .thenComparing(Comparator.comparingLong((FeatureStat stat) -> stat.lookupCount).reversed())
                    .thenComparing(Comparator.comparingLong((FeatureStat stat) -> stat.exposureCount).reversed())
                    .thenComparing(stat -> stat.featureKey);
        };
    }

    private static double lookupToReadRatio(LemmaStat stat) {
        return stat.committedCount > 0 ? (double) stat.lookupCount / stat.committedCount : stat.lookupCount;
    }

    private static double lookupToReadRatio(FeatureStat stat) {
        return stat.committedCount > 0 ? (double) stat.lookupCount / stat.committedCount : stat.lookupCount;
    }

    @Override
    public synchronized List<ReadingEventRecord> listLemmaEvents(long userId, String lemma, String pos,
                                                                 String language, String workId, String eventType, int limit) {
        int safeLimit = limit <= 0 ? 500 : Math.min(5_000, limit);
        List<ReadingEventRecord> result = new ArrayList<>();
        String safeLemma = normalizeLemma(lemma);
        String safePos = pos == null ? "" : pos;
        String safeLanguage = normalizeScope(language);
        String safeWorkId = normalizeScope(workId);
        String safeType = eventType == null ? "" : eventType;
        for (StoredEvent stored : rawEvents) {
            ReadingEvent event = stored.event;
            if (stored.userId != userId) {
                continue;
            }
            if (!safeLemma.equals(normalizeLemma(event.lemma)) || !safePos.equals(event.pos)) {
                continue;
            }
            if (!matchesScope(eventLanguage(event), safeLanguage) || !matchesScope(event.workId, safeWorkId)) {
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

    @Override
    public synchronized List<TimelinePoint> listLemmaTimeline(long userId, String lemma, String pos, String language,
                                                              String workId, String eventType, int limit) {
        return timelineFromRaw(userId, normalizeLemma(lemma), pos == null ? "" : pos, "", normalizeScope(language),
                normalizeScope(workId), eventType == null ? "" : eventType, Math.min(10_000, limit <= 0 ? 2_000 : limit));
    }

    @Override
    public synchronized List<TimelinePoint> listFeatureTimeline(long userId, String featureKey, String language,
                                                                String workId, String eventType, int limit) {
        return timelineFromRaw(userId, "", "", featureKey == null ? "" : featureKey, normalizeScope(language),
                normalizeScope(workId), eventType == null ? "" : eventType, Math.min(10_000, limit <= 0 ? 2_000 : limit));
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
        String language = eventLanguage(event);
        String lemma = normalizeLemma(event.lemma);
        String key = userId + ":" + language + ":" + event.workId + ":" + lemma + ":" + event.pos;
        LemmaStats stats = lemmaStats.computeIfAbsent(key, ignored -> new LemmaStats(userId, language, event.workId, lemma, event.pos));
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

    private void upsertFeatureStats(long userId, ReadingEvent event) {
        if (event.featureKey.isBlank()) {
            return;
        }
        String language = eventLanguage(event);
        String key = userId + ":" + language + ":" + event.workId + ":" + event.featureKey;
        FeatureBucket stats = featureStats.computeIfAbsent(key,
                ignored -> new FeatureBucket(userId, language, event.workId, event.featureKey));
        if ("token_lookup".equals(event.eventType)) {
            stats.lookupCount++;
        } else if ("token_committed".equals(event.eventType)) {
            stats.committedCount++;
            stats.exposureCount++;
        } else if ("token_exposed".equals(event.eventType)) {
            stats.exposureCount++;
        }
        stats.totalVisibleMs += event.visibleMs;
        stats.lastSeenAtMs = Math.max(stats.lastSeenAtMs, event.occurredAtMs);
    }

    private List<TimelinePoint> timelineFromRaw(long userId, String lemma, String pos, String featureKey,
                                                String language, String workId, String eventType, int limit) {
        Map<String, TimelineBucket> buckets = new HashMap<>();
        for (StoredEvent stored : rawEvents) {
            ReadingEvent event = stored.event;
            if (stored.userId != userId || !matchesScope(eventLanguage(event), language) || !matchesScope(event.workId, workId)) {
                continue;
            }
            if (!featureKey.isBlank()) {
                if (!featureKey.equals(event.featureKey)) {
                    continue;
                }
            } else if (!lemma.equals(normalizeLemma(event.lemma)) || !pos.equals(event.pos)) {
                continue;
            }
            if (!eventType.isBlank() && !eventType.equals(event.eventType)) {
                continue;
            }
            long bucketStart = timelineBucketStartMs(event.occurredAtMs);
            String key = event.eventType + ":" + bucketStart;
            buckets.computeIfAbsent(key, ignored -> new TimelineBucket(event.eventType, bucketStart)).add(event);
        }
        List<TimelinePoint> result = buckets.values().stream()
                .map(TimelineBucket::toPoint)
                .sorted(Comparator.comparingLong((TimelinePoint point) -> point.bucketStartMs).thenComparing(point -> point.eventType))
                .toList();
        return result.subList(0, Math.min(result.size(), limit));
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

    private static boolean matchesScope(String actual, String filter) {
        return filter == null || filter.isBlank() || filter.equals(actual == null ? "" : actual);
    }

    private static String eventLanguage(ReadingEvent event) {
        String language = normalizeScope(event.language);
        if (!language.isBlank()) {
            return language;
        }
        String value = event.workId == null ? "" : event.workId.toLowerCase(Locale.ROOT);
        return value.contains("elnet") || value.contains("puncheryshte") ? "mhr" : "tt";
    }

    private static long timelineBucketStartMs(long occurredAtMs) {
        long hourMs = 60L * 60L * 1_000L;
        return Math.floorDiv(occurredAtMs, hourMs) * hourMs;
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
        final long userId;
        final String language;
        final String workId;
        long exposureCount;
        long committedCount;
        long lookupCount;
        long totalVisibleMs;
        long lastSeenAtMs;

        FeatureBucket(long userId, String language, String workId, String featureKey) {
            this.userId = userId;
            this.language = language;
            this.workId = workId;
            this.featureKey = featureKey;
        }

        void merge(FeatureBucket other) {
            exposureCount += other.exposureCount;
            committedCount += other.committedCount;
            lookupCount += other.lookupCount;
            totalVisibleMs += other.totalVisibleMs;
            lastSeenAtMs = Math.max(lastSeenAtMs, other.lastSeenAtMs);
        }
    }

    private static final class TimelineBucket {
        final String eventType;
        final long bucketStartMs;
        long eventCount;
        long totalVisibleMs;
        long firstSeenAtMs;
        long lastSeenAtMs;

        TimelineBucket(String eventType, long bucketStartMs) {
            this.eventType = eventType;
            this.bucketStartMs = bucketStartMs;
        }

        void add(ReadingEvent event) {
            eventCount++;
            totalVisibleMs += event.visibleMs;
            firstSeenAtMs = firstSeenAtMs == 0 ? event.occurredAtMs : Math.min(firstSeenAtMs, event.occurredAtMs);
            lastSeenAtMs = Math.max(lastSeenAtMs, event.occurredAtMs);
        }

        TimelinePoint toPoint() {
            return new TimelinePoint(eventType, bucketStartMs, eventCount, totalVisibleMs, firstSeenAtMs, lastSeenAtMs);
        }
    }

    private static final class LemmaStats {
        final long userId;
        final String language;
        final String workId;
        final String lemma;
        final String pos;
        long exposureCount;
        long committedCount;
        long lookupCount;
        long ttsCount;
        long totalVisibleMs;
        long firstSeenAtMs;
        long lastSeenAtMs;

        LemmaStats(long userId, String language, String workId, String lemma, String pos) {
            this.userId = userId;
            this.language = language;
            this.workId = workId;
            this.lemma = lemma;
            this.pos = pos;
        }

        void merge(LemmaStats other) {
            exposureCount += other.exposureCount;
            committedCount += other.committedCount;
            lookupCount += other.lookupCount;
            ttsCount += other.ttsCount;
            totalVisibleMs += other.totalVisibleMs;
            lastSeenAtMs = Math.max(lastSeenAtMs, other.lastSeenAtMs);
            firstSeenAtMs = firstSeenAtMs == 0 ? other.firstSeenAtMs : Math.min(firstSeenAtMs, other.firstSeenAtMs);
        }
    }
}

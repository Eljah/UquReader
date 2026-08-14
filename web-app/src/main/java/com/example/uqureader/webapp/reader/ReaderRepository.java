package com.example.uqureader.webapp.reader;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface ReaderRepository extends Closeable {
    UserSession register(String username, String password) throws SQLException;

    UserSession login(String username, String password) throws SQLException;

    Optional<UserSession> findSession(String token) throws SQLException;

    void logout(String token) throws SQLException;

    void saveReadingState(long userId, ReadingState state) throws SQLException;

    Optional<ReadingState> findReadingState(long userId, String workId) throws SQLException;

    int recordEvents(long userId, String sessionToken, List<ReadingEvent> events) throws SQLException;

    List<LemmaStat> listLemmaStats(long userId, int limit) throws SQLException;

    List<FeatureStat> listFeatureStats(long userId, int limit) throws SQLException;

    List<ReadingEventRecord> listLemmaEvents(long userId, String lemma, String pos, String eventType, int limit) throws SQLException;
}

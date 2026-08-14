package com.example.uqureader.webapp.reader;

import java.sql.SQLException;
import java.nio.file.Path;

public final class ReaderRepositoryFactory {
    private ReaderRepositoryFactory() {
    }

    public static ReaderRepository createDefault() throws SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            String sqlitePath = System.getenv("UQUREADER_SQLITE_PATH");
            Path path = sqlitePath == null || sqlitePath.isBlank()
                    ? Path.of(".codex", "uqureader-web-dev.db")
                    : Path.of(sqlitePath);
            return new SqliteReaderRepository(path);
        }
        return new PostgresReaderRepository(databaseUrl);
    }
}

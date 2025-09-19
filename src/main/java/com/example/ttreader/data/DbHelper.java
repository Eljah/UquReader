package com.example.ttreader.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DbHelper extends SQLiteOpenHelper {
    public static final String APP_DB_NAME = "appdata.db";
    private static final int APP_DB_VERSION = 3;

    private final Context context;

    public DbHelper(Context ctx) {
        super(ctx, APP_DB_NAME, null, APP_DB_VERSION);
        this.context = ctx.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory(\n" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                " lemma TEXT NOT NULL,\n" +
                " pos TEXT,\n" +
                " feature_key TEXT,\n" +
                " strength REAL NOT NULL DEFAULT 0,\n" +
                " last_seen_ms INTEGER NOT NULL\n" +
                ")");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS memory_idx ON memory(lemma, IFNULL(feature_key,'~'))");
        createUsageTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createUsageStatsV1(db);
        }
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS usage_stats");
            db.execSQL("DROP TABLE IF EXISTS usage_event_log");
            createUsageTables(db);
        }
    }

    public File ensureDictionaryDb() throws IOException {
        File out = new File(context.getDatabasePath("dictionary.db").getAbsolutePath());
        if (!out.getParentFile().exists()) out.getParentFile().mkdirs();
        if (!out.exists()) {
            try (InputStream is = context.getAssets().open("dictionary.db");
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        return out;
    }

    private void createUsageTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_stats(\n" +
                " language_pair TEXT NOT NULL,\n" +
                " work_id TEXT NOT NULL,\n" +
                " lemma TEXT NOT NULL,\n" +
                " pos TEXT NOT NULL,\n" +
                " feature_code TEXT NOT NULL,\n" +
                " event_type TEXT NOT NULL,\n" +
                " count INTEGER NOT NULL DEFAULT 0,\n" +
                " last_seen_ms INTEGER NOT NULL,\n" +
                " last_position INTEGER NOT NULL DEFAULT -1,\n" +
                " PRIMARY KEY(language_pair, work_id, lemma, pos, feature_code, event_type)\n" +
                ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_event_log(\n" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                " language_pair TEXT NOT NULL,\n" +
                " work_id TEXT NOT NULL,\n" +
                " lemma TEXT NOT NULL,\n" +
                " pos TEXT NOT NULL,\n" +
                " feature_code TEXT NOT NULL,\n" +
                " event_type TEXT NOT NULL,\n" +
                " timestamp_ms INTEGER NOT NULL,\n" +
                " char_index INTEGER NOT NULL DEFAULT -1\n" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS usage_event_lookup_idx ON usage_event_log(\n" +
                " language_pair, work_id, lemma, pos, event_type, timestamp_ms\n" +
                ")");
    }

    private void createUsageStatsV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_stats(\n" +
                " lemma TEXT NOT NULL,\n" +
                " pos TEXT NOT NULL,\n" +
                " feature_code TEXT NOT NULL,\n" +
                " event_type TEXT NOT NULL,\n" +
                " count INTEGER NOT NULL DEFAULT 0,\n" +
                " last_seen_ms INTEGER NOT NULL,\n" +
                " PRIMARY KEY(lemma, pos, feature_code, event_type)\n" +
                ")");
    }
}

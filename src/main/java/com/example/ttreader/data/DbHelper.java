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
    private static final int APP_DB_VERSION = 2;

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

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
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
}

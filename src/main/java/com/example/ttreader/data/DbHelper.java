package com.example.UquReader.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.*;

public class DbHelper extends SQLiteOpenHelper {
    public static final String APP_DB_NAME = "appdata.db";
    private static final int APP_DB_VERSION = 1;

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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

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

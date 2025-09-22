package com.example.ttreader.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DbHelper extends SQLiteOpenHelper {
    public static final String APP_DB_NAME = "appdata.db";
    private static final int APP_DB_VERSION = 4;

    private static final String TAG = "DbHelper";
    private static final String PREFS_NAME = "com.example.ttreader.DB_PREFS";
    private static final String PREF_DICT_VERSION = "dictionary.version";
    private static final String DICTIONARY_ASSET = "dictionary.db";

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
        createDeviceStatsTables(db);
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
        if (oldVersion < 4) {
            createDeviceStatsTables(db);
        }
    }

    public File ensureDictionaryDb() throws IOException {
        File out = new File(context.getDatabasePath(DICTIONARY_ASSET).getAbsolutePath());
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int installedVersion = resolveAppVersionCode();
        int installedVersionNameHash = resolveAppVersionNameHash();
        int storedVersion = prefs.getInt(PREF_DICT_VERSION, -1);
        boolean needsRefresh = !out.exists() || storedVersion != installedVersion;
        if (!needsRefresh && installedVersionNameHash != 0) {
            int storedHash = prefs.getInt(PREF_DICT_VERSION + ".nameHash", 0);
            if (storedHash != installedVersionNameHash) {
                needsRefresh = true;
            }
        }

        if (needsRefresh) {
            File tmp = File.createTempFile("dictionary", ".db", context.getCacheDir());
            try {
                copyAssetToFile(DICTIONARY_ASSET, tmp);
                moveDictionary(tmp, out);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(PREF_DICT_VERSION, installedVersion);
                if (installedVersionNameHash != 0) {
                    editor.putInt(PREF_DICT_VERSION + ".nameHash", installedVersionNameHash);
                }
                editor.apply();
            } finally {
                if (tmp.exists() && !tmp.equals(out)) {
                    // Ensure we do not leave temporary files around.
                    // Ignore failures silently as this is a best-effort cleanup.
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }

        return out;
    }

    private void copyAssetToFile(String assetName, File destination) throws IOException {
        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(destination)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private void moveDictionary(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory " + parent);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to delete existing dictionary at " + target);
        }
        if (!source.renameTo(target)) {
            try (InputStream is = new FileInputStream(source);
                 OutputStream os = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
        }
    }

    private int resolveAppVersionCode() {
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return 0;
            }
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), 0);
            if (info == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                long longCode = info.getLongVersionCode();
                return (int) Math.min(Integer.MAX_VALUE, longCode);
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to read app version", e);
            return 0;
        }
    }

    private int resolveAppVersionNameHash() {
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return 0;
            }
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), 0);
            if (info == null || info.versionName == null) {
                return 0;
            }
            return info.versionName.hashCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to read app version name", e);
            return 0;
        }
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

    private void createDeviceStatsTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_pause_events(\n" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                " descriptor TEXT NOT NULL,\n" +
                " display_name TEXT,\n" +
                " vendor_id INTEGER,\n" +
                " product_id INTEGER,\n" +
                " source_flags INTEGER,\n" +
                " pause_offset_ms INTEGER NOT NULL,\n" +
                " target_offset_ms INTEGER NOT NULL,\n" +
                " delta_ms INTEGER NOT NULL,\n" +
                " char_delta INTEGER NOT NULL,\n" +
                " language_pair TEXT,\n" +
                " work_id TEXT,\n" +
                " recorded_at_ms INTEGER NOT NULL\n" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_pause_events_descriptor_idx\n" +
                " ON device_pause_events(descriptor, recorded_at_ms)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_reaction_stats(\n" +
                " descriptor TEXT PRIMARY KEY,\n" +
                " display_name TEXT,\n" +
                " vendor_id INTEGER,\n" +
                " product_id INTEGER,\n" +
                " source_flags INTEGER,\n" +
                " sample_count INTEGER NOT NULL DEFAULT 0,\n" +
                " avg_reaction_delay_ms REAL NOT NULL DEFAULT 0,\n" +
                " last_seen_ms INTEGER NOT NULL DEFAULT 0\n" +
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

package com.example.UquReader.data;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Pair;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DictionaryDao {
    private final SQLiteDatabase dictDb;

    public DictionaryDao(File dictFile) {
        this.dictDb = SQLiteDatabase.openDatabase(dictFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    public List<Pair<String,Double>> translateLemmaToRu(String lemmaTt) {
        List<Pair<String,Double>> out = new ArrayList<>();
        try (Cursor c = dictDb.rawQuery("SELECT lemma_ru, COALESCE(score,1.0) FROM tt_ru WHERE lemma_tt=? ORDER BY score DESC LIMIT 5", new String[]{lemmaTt})) {
            while (c.moveToNext()) out.add(new Pair<>(c.getString(0), c.getDouble(1)));
        }
        return out;
    }

    public void close() { if (dictDb != null) dictDb.close(); }
}

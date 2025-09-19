package com.example.UquReader;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.example.UquReader.data.DbHelper;
import com.example.UquReader.data.MemoryDao;
import com.example.UquReader.data.UsageStatsDao;
import com.example.UquReader.reader.ReaderView;
import com.example.UquReader.reader.TokenSpan;
import com.example.UquReader.ui.TokenInfoBottomSheet;

import java.util.List;

public class MainActivity extends Activity implements ReaderView.TokenInfoProvider {
    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);

        ReaderView reader = findViewById(R.id.readerView);
        reader.setup(dbHelper, memoryDao, usageStatsDao, this);
        reader.loadFromJsonlAsset("sample_book.ttmorph.jsonl");

        Button statsButton = findViewById(R.id.btnStats);
        statsButton.setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        String ruCsv = ruLemmas.isEmpty()? "—" : String.join(", ", ruLemmas);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        sheet.show(getFragmentManager(), "token-info");
    }
}

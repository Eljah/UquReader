package com.example.ttreader;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.Button;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.MemoryDao;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.reader.ReaderView;
import com.example.ttreader.reader.TokenSpan;
import com.example.ttreader.ui.TokenInfoBottomSheet;
import com.example.ttreader.util.GrammarResources;

import java.util.List;

public class MainActivity extends Activity implements ReaderView.TokenInfoProvider {
    private static final String LANGUAGE_PAIR_TT_RU = "tt-ru";
    private static final String SAMPLE_ASSET = "sample_book.ttmorph.jsonl";
    private static final String SAMPLE_WORK_ID = "sample_book.ttmorph";

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_main);

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);

        ReaderView reader = findViewById(R.id.readerView);
        reader.setup(dbHelper, memoryDao, usageStatsDao, this);
        reader.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID);
        reader.loadFromJsonlAsset(SAMPLE_ASSET);

        Button languageStatsButton = findViewById(R.id.btnLanguageStats);
        languageStatsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, StatsActivity.class);
            intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_LANGUAGE);
            intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, LANGUAGE_PAIR_TT_RU);
            startActivity(intent);
        });

        Button workStatsButton = findViewById(R.id.btnWorkStats);
        workStatsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, StatsActivity.class);
            intent.putExtra(StatsActivity.EXTRA_MODE, StatsActivity.MODE_WORK);
            intent.putExtra(StatsActivity.EXTRA_LANGUAGE_PAIR, LANGUAGE_PAIR_TT_RU);
            intent.putExtra(StatsActivity.EXTRA_WORK_ID, SAMPLE_WORK_ID);
            startActivity(intent);
        });
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        String ruCsv = ruLemmas.isEmpty()? "—" : String.join(", ", ruLemmas);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        sheet.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID, span.getStartIndex());
        sheet.show(getFragmentManager(), "token-info");
    }
}

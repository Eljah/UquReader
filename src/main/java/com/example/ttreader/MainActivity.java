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
    private static final String CURRENT_BOOK_ASSET = "sample_book.ttmorph.jsonl";
    private static final String CURRENT_BOOK_ID = "sample_book";

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;
    private String currentBookTitle;

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
        currentBookTitle = getString(R.string.sample_book_title);
        reader.loadFromJsonlAsset(CURRENT_BOOK_ASSET, CURRENT_BOOK_ID);

        Button statsButton = findViewById(R.id.btnStats);
        statsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, StatsActivity.class);
            intent.putExtra(StatsActivity.EXTRA_BOOK_ID, CURRENT_BOOK_ID);
            intent.putExtra(StatsActivity.EXTRA_BOOK_TITLE, currentBookTitle);
            startActivity(intent);
        });
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        String ruCsv = ruLemmas.isEmpty()? "—" : String.join(", ", ruLemmas);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        sheet.setBookId(CURRENT_BOOK_ID);
        sheet.show(getFragmentManager(), "token-info");
    }
}

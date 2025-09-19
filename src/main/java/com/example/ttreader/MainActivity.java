package com.example.ttreader;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ScrollView;

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

    public static final String EXTRA_TARGET_CHAR_INDEX = "com.example.ttreader.TARGET_CHAR_INDEX";

    private DbHelper dbHelper;
    private MemoryDao memoryDao;
    private UsageStatsDao usageStatsDao;
    private ScrollView readerScrollView;
    private ReaderView readerView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_main);

        dbHelper = new DbHelper(this);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        memoryDao = new MemoryDao(db);
        usageStatsDao = new UsageStatsDao(db);

        readerScrollView = findViewById(R.id.readerScrollView);
        readerView = findViewById(R.id.readerView);
        readerView.setup(dbHelper, memoryDao, usageStatsDao, this);
        readerView.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID);
        readerView.loadFromJsonlAsset(SAMPLE_ASSET);

        if (readerScrollView != null) {
            ViewTreeObserver observer = readerScrollView.getViewTreeObserver();
            observer.addOnScrollChangedListener(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
            readerScrollView.post(() ->
                    readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
        }

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

        handleNavigationIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
    }

    @Override public void onTokenLongPress(TokenSpan span, List<String> ruLemmas) {
        showTokenSheet(span, ruLemmas);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null) return;
        int targetIndex = intent.getIntExtra(EXTRA_TARGET_CHAR_INDEX, -1);
        if (targetIndex < 0) return;
        intent.removeExtra(EXTRA_TARGET_CHAR_INDEX);
        if (readerView == null) return;
        readerView.post(() -> navigateToCharIndex(targetIndex, 0));
    }

    private void navigateToCharIndex(int charIndex, int attempt) {
        if (readerView == null) return;
        TokenSpan span = readerView.findSpanForCharIndex(charIndex);
        if (span == null) {
            if (attempt < 5) {
                readerView.postDelayed(() -> navigateToCharIndex(charIndex, attempt + 1), 50);
            }
            return;
        }
        readerView.ensureExposureLogged(span);
        scrollToSpan(span, attempt);
        readerView.postDelayed(() -> readerView.showTokenInfo(span), 150);
    }

    private void scrollToSpan(TokenSpan span, int attempt) {
        if (readerView == null || readerScrollView == null || span == null) return;
        android.text.Layout layout = readerView.getLayout();
        CharSequence text = readerView.getText();
        if ((layout == null || text == null) && attempt < 5) {
            readerView.postDelayed(() -> scrollToSpan(span, attempt + 1), 50);
            return;
        } else if (layout == null || text == null) {
            return;
        }
        int textLength = text.length();
        int start = Math.max(0, Math.min(span.getStartIndex(), textLength));
        int line = layout.getLineForOffset(start);
        int y = readerView.getTotalPaddingTop() + layout.getLineTop(line);
        readerScrollView.smoothScrollTo(0, y);
        readerScrollView.post(() ->
                readerView.onViewportChanged(readerScrollView.getScrollY(), readerScrollView.getHeight()));
    }

    private void showTokenSheet(TokenSpan span, List<String> ruLemmas) {
        if (span == null || span.token == null || span.token.analysis == null) return;
        List<String> safeRu = ruLemmas == null ? new java.util.ArrayList<>() : ruLemmas;
        String ruCsv = safeRu.isEmpty()? "—" : String.join(", ", safeRu);
        TokenInfoBottomSheet sheet = TokenInfoBottomSheet.newInstance(span.token.surface, span.token.analysis, ruCsv);
        sheet.setUsageStatsDao(usageStatsDao);
        sheet.setUsageContext(LANGUAGE_PAIR_TT_RU, SAMPLE_WORK_ID, span.getStartIndex());
        sheet.show(getFragmentManager(), "token-info");
    }
}

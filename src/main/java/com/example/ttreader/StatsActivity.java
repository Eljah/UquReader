package com.example.ttreader;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.UsageStatsDao;
import com.example.ttreader.model.FeatureMetadata;
import com.example.ttreader.model.UsageEvent;
import com.example.ttreader.ui.TimelineView;
import com.example.ttreader.util.GrammarResources;

import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StatsActivity extends Activity {
    public static final String EXTRA_BOOK_ID = "extra_book_id";
    public static final String EXTRA_BOOK_TITLE = "extra_book_title";

    private UsageStatsDao usageStatsDao;
    private DateFormat dateFormat;
    private LayoutInflater inflater;
    private Collator collator;
    private String bookId;
    private String bookTitle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_stats);
        setTitle(R.string.stats_title);

        usageStatsDao = new UsageStatsDao(new DbHelper(this).getWritableDatabase());
        dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());
        inflater = LayoutInflater.from(this);
        try {
            collator = Collator.getInstance(new Locale("tt"));
            collator.setStrength(Collator.PRIMARY);
        } catch (Exception e) {
            collator = Collator.getInstance();
        }

        bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        bookTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);

        TextView bookTitleView = findViewById(R.id.bookSectionTitle);
        if (bookTitleView != null) {
            if (!TextUtils.isEmpty(bookTitle)) {
                bookTitleView.setText(getString(R.string.stats_book_section_title, bookTitle));
            } else {
                bookTitleView.setText(R.string.stats_book_section_title_fallback);
            }
        }

        LinearLayout languageLemmaContainer = findViewById(R.id.languageLemmaStatsContainer);
        LinearLayout languageFeatureContainer = findViewById(R.id.languageFeatureStatsContainer);
        LinearLayout bookLemmaContainer = findViewById(R.id.bookLemmaStatsContainer);
        LinearLayout bookFeatureContainer = findViewById(R.id.bookFeatureStatsContainer);

        populateLemmaStats(languageLemmaContainer, usageStatsDao.getLemmaEvents(null));
        populateFeatureStats(languageFeatureContainer, usageStatsDao.getFeatureEvents(null));

        if (!TextUtils.isEmpty(bookId)) {
            populateLemmaStats(bookLemmaContainer, usageStatsDao.getLemmaEvents(bookId));
            populateFeatureStats(bookFeatureContainer, usageStatsDao.getFeatureEvents(bookId));
        } else {
            showEmptyState(bookLemmaContainer);
            showEmptyState(bookFeatureContainer);
        }
    }

    private void populateLemmaStats(LinearLayout container, List<UsageEvent> events) {
        container.removeAllViews();
        if (events == null || events.isEmpty()) {
            showEmptyState(container);
            return;
        }
        Map<String, LemmaStats> aggregated = new HashMap<>();
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (UsageEvent event : events) {
            minTime = Math.min(minTime, event.timestampMs);
            maxTime = Math.max(maxTime, event.timestampMs);
            String key = safe(event.lemma) + "|" + safe(event.pos);
            LemmaStats stats = aggregated.get(key);
            if (stats == null) {
                stats = new LemmaStats(event.lemma, event.pos);
                aggregated.put(key, stats);
            }
            stats.addEvent(event);
        }
        if (aggregated.isEmpty()) {
            showEmptyState(container);
            return;
        }
        List<LemmaStats> entries = new ArrayList<>(aggregated.values());
        Collections.sort(entries, this::compareLemma);
        long rangeStart = minTime == Long.MAX_VALUE ? 0 : minTime;
        long rangeEnd = maxTime == Long.MIN_VALUE ? rangeStart : maxTime;
        for (LemmaStats entry : entries) {
            Collections.sort(entry.events, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
            View item = inflater.inflate(R.layout.item_stats_entry, container, false);
            TextView title = item.findViewById(R.id.statsTitle);
            TextView exposure = item.findViewById(R.id.statsExposure);
            TextView lookup = item.findViewById(R.id.statsLookup);
            TimelineView timeline = item.findViewById(R.id.statsTimeline);
            title.setText(formatLemmaTitle(entry));
            exposure.setText(getString(R.string.stats_exposure_label, entry.exposureCount, formatTime(entry.lastExposure)));
            lookup.setText(getString(R.string.stats_lookup_label, entry.lookupCount, formatTime(entry.lastLookup)));
            timeline.setEvents(entry.events, rangeStart, rangeEnd);
            container.addView(item);
        }
    }

    private void populateFeatureStats(LinearLayout container, List<UsageEvent> events) {
        container.removeAllViews();
        if (events == null || events.isEmpty()) {
            showEmptyState(container);
            return;
        }
        Map<String, FeatureStats> aggregated = new HashMap<>();
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (UsageEvent event : events) {
            minTime = Math.min(minTime, event.timestampMs);
            maxTime = Math.max(maxTime, event.timestampMs);
            String code = safe(event.featureCode);
            if (TextUtils.isEmpty(code)) continue;
            FeatureStats stats = aggregated.get(code);
            if (stats == null) {
                stats = new FeatureStats(code);
                aggregated.put(code, stats);
            }
            stats.addEvent(event);
        }
        if (aggregated.isEmpty()) {
            showEmptyState(container);
            return;
        }
        List<FeatureStats> entries = new ArrayList<>(aggregated.values());
        Collections.sort(entries, this::compareFeature);
        long rangeStart = minTime == Long.MAX_VALUE ? 0 : minTime;
        long rangeEnd = maxTime == Long.MIN_VALUE ? rangeStart : maxTime;
        for (FeatureStats entry : entries) {
            Collections.sort(entry.events, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
            View item = inflater.inflate(R.layout.item_stats_entry, container, false);
            TextView title = item.findViewById(R.id.statsTitle);
            TextView exposure = item.findViewById(R.id.statsExposure);
            TextView lookup = item.findViewById(R.id.statsLookup);
            TimelineView timeline = item.findViewById(R.id.statsTimeline);
            title.setText(formatFeatureTitle(entry.code));
            exposure.setText(getString(R.string.stats_exposure_label, entry.exposureCount, formatTime(entry.lastExposure)));
            lookup.setText(getString(R.string.stats_lookup_label, entry.lookupCount, formatTime(entry.lastLookup)));
            timeline.setEvents(entry.events, rangeStart, rangeEnd);
            container.addView(item);
        }
    }

    private int compareLemma(LemmaStats a, LemmaStats b) {
        String la = safe(a.lemma);
        String lb = safe(b.lemma);
        int cmp = collator != null ? collator.compare(la, lb) : la.compareToIgnoreCase(lb);
        if (cmp != 0) return cmp;
        String pa = safe(a.pos);
        String pb = safe(b.pos);
        return collator != null ? collator.compare(pa, pb) : pa.compareToIgnoreCase(pb);
    }

    private int compareFeature(FeatureStats a, FeatureStats b) {
        String ta = featureSortKey(a.code);
        String tb = featureSortKey(b.code);
        return collator != null ? collator.compare(ta, tb) : ta.compareToIgnoreCase(tb);
    }

    private void showEmptyState(LinearLayout container) {
        TextView tv = new TextView(this);
        tv.setText(R.string.stats_time_never);
        container.addView(tv);
    }

    private String formatLemmaTitle(LemmaStats entry) {
        String pos = GrammarResources.formatPos(entry.pos);
        if (TextUtils.isEmpty(entry.lemma)) return pos;
        return entry.lemma + " — " + pos;
    }

    private String formatFeatureTitle(String code) {
        FeatureMetadata meta = GrammarResources.getFeatureMetadata(code);
        if (meta == null) {
            return code;
        }
        String tt = safe(meta.titleTt);
        String ru = safe(meta.titleRu);
        StringBuilder name = new StringBuilder();
        if (!TextUtils.isEmpty(tt)) {
            name.append(tt);
        }
        if (!TextUtils.isEmpty(ru) && !ru.equals(tt)) {
            if (name.length() > 0) {
                name.append(" / ");
            }
            name.append(ru);
        }
        if (name.length() == 0) {
            name.append(code);
        }
        return code + " · " + name;
    }

    private String featureSortKey(String code) {
        FeatureMetadata meta = GrammarResources.getFeatureMetadata(code);
        if (meta == null) {
            return code;
        }
        String tt = safe(meta.titleTt);
        if (!TextUtils.isEmpty(tt)) {
            return tt;
        }
        String ru = safe(meta.titleRu);
        if (!TextUtils.isEmpty(ru)) {
            return ru;
        }
        return code;
    }

    private String formatTime(long ms) {
        if (ms <= 0) return getString(R.string.stats_time_never);
        return dateFormat.format(ms);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class LemmaStats {
        final String lemma;
        final String pos;
        final List<UsageEvent> events = new ArrayList<>();
        int exposureCount = 0;
        int lookupCount = 0;
        long lastExposure = 0;
        long lastLookup = 0;

        LemmaStats(String lemma, String pos) {
            this.lemma = lemma;
            this.pos = pos;
        }

        void addEvent(UsageEvent event) {
            events.add(event);
            if (UsageStatsDao.EVENT_EXPOSURE.equals(event.eventType)) {
                exposureCount += 1;
                if (event.timestampMs > lastExposure) lastExposure = event.timestampMs;
            } else if (UsageStatsDao.EVENT_LOOKUP.equals(event.eventType)
                    || UsageStatsDao.EVENT_FEATURE.equals(event.eventType)) {
                lookupCount += 1;
                if (event.timestampMs > lastLookup) lastLookup = event.timestampMs;
            }
        }
    }

    private static class FeatureStats {
        final String code;
        final List<UsageEvent> events = new ArrayList<>();
        int exposureCount = 0;
        int lookupCount = 0;
        long lastExposure = 0;
        long lastLookup = 0;

        FeatureStats(String code) {
            this.code = code;
        }

        void addEvent(UsageEvent event) {
            events.add(event);
            if (UsageStatsDao.EVENT_EXPOSURE.equals(event.eventType)) {
                exposureCount += 1;
                if (event.timestampMs > lastExposure) lastExposure = event.timestampMs;
            } else if (UsageStatsDao.EVENT_LOOKUP.equals(event.eventType)
                    || UsageStatsDao.EVENT_FEATURE.equals(event.eventType)) {
                lookupCount += 1;
                if (event.timestampMs > lastLookup) lastLookup = event.timestampMs;
            }
        }
    }
}

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
import com.example.ttreader.model.UsageStat;
import com.example.ttreader.util.GrammarResources;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StatsActivity extends Activity {
    private UsageStatsDao usageStatsDao;
    private DateFormat dateFormat;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        setTitle(R.string.stats_title);

        usageStatsDao = new UsageStatsDao(new DbHelper(this).getWritableDatabase());
        dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());

        LinearLayout lemmaContainer = findViewById(R.id.lemmaStatsContainer);
        LinearLayout featureContainer = findViewById(R.id.featureStatsContainer);
        populateLemmaStats(lemmaContainer);
        populateFeatureStats(featureContainer);
    }

    private void populateLemmaStats(LinearLayout container) {
        container.removeAllViews();
        List<UsageStat> stats = usageStatsDao.getLemmaStats();
        Map<String, LemmaStats> aggregated = new HashMap<>();
        for (UsageStat stat : stats) {
            String key = (stat.lemma == null ? "" : stat.lemma) + "|" + (stat.pos == null ? "" : stat.pos);
            LemmaStats bucket = aggregated.get(key);
            if (bucket == null) {
                bucket = new LemmaStats(stat.lemma, stat.pos);
                aggregated.put(key, bucket);
            }
            bucket.update(stat);
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        if (aggregated.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.stats_time_never);
            container.addView(tv);
            return;
        }
        List<LemmaStats> lemmaStats = new ArrayList<>(aggregated.values());
        Collections.sort(lemmaStats, (a, b) -> {
            String la = a.lemma == null ? "" : a.lemma;
            String lb = b.lemma == null ? "" : b.lemma;
            int cmp = la.compareToIgnoreCase(lb);
            if (cmp != 0) return cmp;
            String pa = a.pos == null ? "" : a.pos;
            String pb = b.pos == null ? "" : b.pos;
            return pa.compareToIgnoreCase(pb);
        });
        for (LemmaStats entry : lemmaStats) {
            View item = inflater.inflate(R.layout.item_stats_entry, container, false);
            TextView title = item.findViewById(R.id.statsTitle);
            TextView exposure = item.findViewById(R.id.statsExposure);
            TextView lookup = item.findViewById(R.id.statsLookup);
            title.setText(formatLemmaTitle(entry));
            exposure.setText(getString(R.string.stats_exposure_label, entry.exposureCount, formatTime(entry.lastExposure)));
            lookup.setText(getString(R.string.stats_lookup_label, entry.lookupCount, formatTime(entry.lastLookup)));
            container.addView(item);
        }
    }

    private void populateFeatureStats(LinearLayout container) {
        container.removeAllViews();
        List<UsageStat> stats = usageStatsDao.getFeatureStats();
        Map<String, FeatureStats> aggregated = new HashMap<>();
        for (UsageStat stat : stats) {
            String code = stat.featureCode == null ? "" : stat.featureCode;
            FeatureStats bucket = aggregated.get(code);
            if (bucket == null) {
                bucket = new FeatureStats(code);
                aggregated.put(code, bucket);
            }
            bucket.count += stat.count;
            if (stat.lastSeenMs > bucket.lastSeen) bucket.lastSeen = stat.lastSeenMs;
        }
        if (aggregated.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(R.string.stats_time_never);
            container.addView(tv);
            return;
        }
        List<FeatureStats> featureStats = new ArrayList<>(aggregated.values());
        Collections.sort(featureStats, (a, b) -> a.code.compareToIgnoreCase(b.code));
        for (FeatureStats entry : featureStats) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.stats_feature_label,
                    formatFeatureTitle(entry.code), entry.count, formatTime(entry.lastSeen)));
            container.addView(tv);
        }
    }

    private String formatLemmaTitle(LemmaStats entry) {
        String pos = GrammarResources.formatPos(entry.pos);
        if (TextUtils.isEmpty(entry.lemma)) return pos;
        return entry.lemma + " — " + pos;
    }

    private String formatFeatureTitle(String code) {
        FeatureMetadata meta = GrammarResources.getFeatureMetadata(code);
        if (meta != null) {
            return code + " · " + meta.titleRu;
        }
        return code;
    }

    private String formatTime(long ms) {
        if (ms <= 0) return getString(R.string.stats_time_never);
        return dateFormat.format(ms);
    }

    private static class LemmaStats {
        final String lemma;
        final String pos;
        int exposureCount = 0;
        int lookupCount = 0;
        long lastExposure = 0;
        long lastLookup = 0;

        LemmaStats(String lemma, String pos) {
            this.lemma = lemma;
            this.pos = pos;
        }

        void update(UsageStat stat) {
            if (UsageStatsDao.EVENT_EXPOSURE.equals(stat.eventType)) {
                exposureCount += stat.count;
                if (stat.lastSeenMs > lastExposure) lastExposure = stat.lastSeenMs;
            } else if (UsageStatsDao.EVENT_LOOKUP.equals(stat.eventType)) {
                lookupCount += stat.count;
                if (stat.lastSeenMs > lastLookup) lastLookup = stat.lastSeenMs;
            }
        }
    }

    private static class FeatureStats {
        final String code;
        int count = 0;
        long lastSeen = 0;

        FeatureStats(String code) { this.code = code; }
    }
}

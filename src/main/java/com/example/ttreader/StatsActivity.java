package com.example.ttreader;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
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
import com.example.ttreader.model.UsageStat;
import com.example.ttreader.util.GrammarResources;
import com.example.ttreader.widget.UsageTimelineView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StatsActivity extends Activity {
    public static final String EXTRA_MODE = "com.example.ttreader.stats.MODE";
    public static final String EXTRA_LANGUAGE_PAIR = "com.example.ttreader.stats.LANGUAGE";
    public static final String EXTRA_WORK_ID = "com.example.ttreader.stats.WORK";
    public static final int MODE_LANGUAGE = 1;
    public static final int MODE_WORK = 2;

    private UsageStatsDao usageStatsDao;
    private DateFormat dateFormat;
    private int mode = MODE_LANGUAGE;
    private String languagePair = "";
    private String workId = "";
    private UsageStatsDao.TimeBounds timeBounds;
    private UsageStatsDao.PositionBounds positionBounds;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrammarResources.initialize(this);
        setContentView(R.layout.activity_stats);

        usageStatsDao = new UsageStatsDao(new DbHelper(this).getWritableDatabase());
        dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());

        Intent intent = getIntent();
        mode = intent.getIntExtra(EXTRA_MODE, MODE_LANGUAGE);
        languagePair = safeString(intent.getStringExtra(EXTRA_LANGUAGE_PAIR));
        if (mode == MODE_WORK) {
            workId = safeString(intent.getStringExtra(EXTRA_WORK_ID));
        } else {
            workId = "";
        }

        if (mode == MODE_WORK) {
            setTitle(R.string.stats_work_title);
        } else {
            setTitle(R.string.stats_language_title);
        }

        timeBounds = usageStatsDao.getTimeBounds(languagePair);
        positionBounds = mode == MODE_WORK
                ? usageStatsDao.getCharBounds(languagePair, workId)
                : new UsageStatsDao.PositionBounds(0, 0);

        TextView axisDescription = findViewById(R.id.statsAxisDescription);
        updateAxisDescription(axisDescription);

        LinearLayout lemmaContainer = findViewById(R.id.lemmaStatsContainer);
        LinearLayout featureContainer = findViewById(R.id.featureStatsContainer);
        populateLemmaStats(lemmaContainer);
        populateFeatureStats(featureContainer);
    }

    private void populateLemmaStats(LinearLayout container) {
        container.removeAllViews();
        String workScope = mode == MODE_WORK ? workId : null;
        List<UsageStat> stats = usageStatsDao.getLemmaStats(languagePair, workScope);
        Map<String, LemmaStats> aggregated = new HashMap<>();
        for (UsageStat stat : stats) {
            String key = safeString(stat.lemma) + "|" + safeString(stat.pos);
            LemmaStats bucket = aggregated.get(key);
            if (bucket == null) {
                bucket = new LemmaStats(stat.lemma, stat.pos);
                aggregated.put(key, bucket);
            }
            if (UsageStatsDao.EVENT_EXPOSURE.equals(stat.eventType)) {
                bucket.exposureCount = stat.count;
                bucket.lastExposure = stat.lastSeenMs;
                bucket.exposurePositions = normalizeEvents(
                        usageStatsDao.getEvents(languagePair, workScope, stat.lemma, stat.pos, UsageStatsDao.EVENT_EXPOSURE));
            } else if (UsageStatsDao.EVENT_LOOKUP.equals(stat.eventType)) {
                bucket.lookupCount = stat.count;
                bucket.lastLookup = stat.lastSeenMs;
                bucket.lookupPositions = normalizeEvents(
                        usageStatsDao.getEvents(languagePair, workScope, stat.lemma, stat.pos, UsageStatsDao.EVENT_LOOKUP));
            }
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
            String la = safeString(a.lemma);
            String lb = safeString(b.lemma);
            int cmp = la.compareToIgnoreCase(lb);
            if (cmp != 0) return cmp;
            String pa = safeString(a.pos);
            String pb = safeString(b.pos);
            return pa.compareToIgnoreCase(pb);
        });
        for (LemmaStats entry : lemmaStats) {
            View item = inflater.inflate(R.layout.item_stats_entry, container, false);
            TextView title = item.findViewById(R.id.statsTitle);
            TextView exposure = item.findViewById(R.id.statsExposure);
            TextView lookup = item.findViewById(R.id.statsLookup);
            UsageTimelineView exposureTimeline = item.findViewById(R.id.statsExposureTimeline);
            UsageTimelineView lookupTimeline = item.findViewById(R.id.statsLookupTimeline);
            title.setText(formatLemmaTitle(entry));
            exposure.setText(getString(R.string.stats_exposure_label, entry.exposureCount, formatTime(entry.lastExposure)));
            lookup.setText(getString(R.string.stats_lookup_label, entry.lookupCount, formatTime(entry.lastLookup)));
            bindTimeline(exposureTimeline, entry.exposureCount, entry.exposurePositions,
                    resolveColor(android.R.color.holo_green_dark));
            bindTimeline(lookupTimeline, entry.lookupCount, entry.lookupPositions,
                    resolveColor(android.R.color.holo_blue_dark));
            container.addView(item);
        }
    }

    private void populateFeatureStats(LinearLayout container) {
        container.removeAllViews();
        String workScope = mode == MODE_WORK ? workId : null;
        List<UsageStat> stats = usageStatsDao.getFeatureStats(languagePair, workScope);
        Map<String, FeatureStats> aggregated = new HashMap<>();
        for (UsageStat stat : stats) {
            String code = safeString(stat.featureCode);
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
        Collections.sort(featureStats, (a, b) -> safeString(a.code).compareToIgnoreCase(safeString(b.code)));
        for (FeatureStats entry : featureStats) {
            TextView tv = new TextView(this);
            tv.setText(getString(R.string.stats_feature_label,
                    formatFeatureTitle(entry.code), entry.count, formatTime(entry.lastSeen)));
            container.addView(tv);
        }
    }

    private void updateAxisDescription(TextView view) {
        if (view == null) return;
        if (mode == MODE_LANGUAGE) {
            String start = formatAxisTime(timeBounds != null ? timeBounds.start : 0L);
            String end = formatAxisTime(timeBounds != null ? timeBounds.end : 0L);
            view.setText(getString(R.string.stats_axis_time, start, end));
        } else {
            int max = positionBounds != null ? positionBounds.max : -1;
            int displayMax = max < 0 ? 0 : max + 1;
            view.setText(getString(R.string.stats_axis_text, displayMax));
        }
    }

    private void bindTimeline(UsageTimelineView view, int count, List<Float> positions, int color) {
        if (view == null) return;
        if (count <= 0 || positions == null || positions.isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setColor(color);
        view.setEvents(positions);
    }

    private List<Float> normalizeEvents(List<UsageEvent> events) {
        List<Float> normalized = new ArrayList<>();
        if (events == null || events.isEmpty()) {
            return normalized;
        }
        if (mode == MODE_LANGUAGE) {
            long start = timeBounds != null ? timeBounds.start : 0L;
            long end = timeBounds != null ? timeBounds.end : 0L;
            long span = end - start;
            if (span <= 0L) {
                for (UsageEvent event : events) {
                    if (event.timestampMs <= 0L) continue;
                    normalized.add(0.5f);
                }
            } else {
                for (UsageEvent event : events) {
                    if (event.timestampMs <= 0L) continue;
                    float value = (float) (event.timestampMs - start) / (float) span;
                    normalized.add(clamp01(value));
                }
            }
        } else {
            int max = positionBounds != null ? positionBounds.max : 0;
            int range = Math.max(max, 1);
            for (UsageEvent event : events) {
                if (event.charIndex < 0) continue;
                int position = Math.max(0, Math.min(event.charIndex, range));
                float value = (float) position / (float) range;
                normalized.add(clamp01(value));
            }
        }
        if (normalized.isEmpty()) {
            for (int i = 0; i < events.size(); i++) {
                normalized.add(0.5f);
            }
        }
        return normalized;
    }

    private float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
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

    private String formatAxisTime(long ms) {
        if (ms <= 0) return getString(R.string.stats_time_never);
        return dateFormat.format(ms);
    }

    private int resolveColor(int colorResId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(colorResId, getTheme());
        }
        return getResources().getColor(colorResId);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private static class LemmaStats {
        final String lemma;
        final String pos;
        int exposureCount = 0;
        int lookupCount = 0;
        long lastExposure = 0;
        long lastLookup = 0;
        List<Float> exposurePositions = new ArrayList<>();
        List<Float> lookupPositions = new ArrayList<>();

        LemmaStats(String lemma, String pos) {
            this.lemma = lemma;
            this.pos = pos;
        }
    }

    private static class FeatureStats {
        final String code;
        int count = 0;
        long lastSeen = 0;

        FeatureStats(String code) { this.code = code; }
    }
}

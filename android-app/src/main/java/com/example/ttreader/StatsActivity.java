package com.example.ttreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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
import java.util.Comparator;
import java.util.EnumMap;
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
    private LayoutInflater layoutInflater;
    private LinearLayout lemmaStatsContainer;
    private final List<LemmaStats> lemmaStatsSource = new ArrayList<>();
    private boolean lemmaStatsLoaded = false;
    private String lemmaFilterLower = "";
    private LemmaSortMode lemmaSortMode = LemmaSortMode.ALPHABET_ASC;
    private final Map<LemmaSortMode, ImageButton> sortButtons = new EnumMap<>(LemmaSortMode.class);

    private static final Map<Character, Integer> TATAR_ALPHABET_INDEX = new HashMap<>();
    private static final int TATAR_UNKNOWN_BASE = 1000;

    static {
        String alphabet = "АӘБВГДЕЁЖҖЗИЙКЛМНҢОӨПРСТУҮФХҺЦЧШЩЪЫЬЭЮЯ";
        for (int i = 0; i < alphabet.length(); i++) {
            char upper = alphabet.charAt(i);
            char lower = Character.toLowerCase(upper);
            TATAR_ALPHABET_INDEX.put(upper, i);
            TATAR_ALPHABET_INDEX.put(lower, i);
        }
    }

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

        layoutInflater = LayoutInflater.from(this);

        TextView axisDescription = findViewById(R.id.statsAxisDescription);
        updateAxisDescription(axisDescription);

        setupSortControls();

        LinearLayout lemmaContainer = findViewById(R.id.lemmaStatsContainer);
        LinearLayout featureContainer = findViewById(R.id.featureStatsContainer);
        populateLemmaStats(lemmaContainer);
        populateFeatureStats(featureContainer);
    }

    private void setupSortControls() {
        EditText filterInput = findViewById(R.id.statsFilterInput);
        if (filterInput != null) {
            filterInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable s) {
                    String value = s != null ? s.toString() : "";
                    String normalized = value.trim();
                    lemmaFilterLower = normalized.toLowerCase(Locale.getDefault());
                    if (lemmaStatsLoaded) {
                        refreshLemmaStats();
                    }
                }
            });
        }
        sortButtons.clear();
        addSortButton(LemmaSortMode.ALPHABET_ASC, R.id.buttonSortAlphaAsc);
        addSortButton(LemmaSortMode.ALPHABET_DESC, R.id.buttonSortAlphaDesc);
        addSortButton(LemmaSortMode.LOOKUP_COUNT_DESC, R.id.buttonSortLookupCountDesc);
        addSortButton(LemmaSortMode.LOOKUP_TIME_DESC, R.id.buttonSortLookupTimeDesc);
        addSortButton(LemmaSortMode.LOOKUP_TIME_ASC, R.id.buttonSortLookupTimeAsc);
        addSortButton(LemmaSortMode.LOOKUP_COUNT_ASC, R.id.buttonSortLookupCountAsc);
        addSortButton(LemmaSortMode.EXPOSURE_COUNT_DESC, R.id.buttonSortExposureCountDesc);
        addSortButton(LemmaSortMode.EXPOSURE_COUNT_ASC, R.id.buttonSortExposureCountAsc);
        updateSortButtonState();
    }

    private void addSortButton(LemmaSortMode mode, int viewId) {
        ImageButton button = findViewById(viewId);
        if (button != null) {
            sortButtons.put(mode, button);
            button.setOnClickListener(v -> setLemmaSortMode(mode));
        }
    }

    private void setLemmaSortMode(LemmaSortMode mode) {
        if (mode == null) return;
        if (lemmaSortMode != mode) {
            lemmaSortMode = mode;
            if (lemmaStatsLoaded) {
                refreshLemmaStats();
            }
        }
        updateSortButtonState();
    }

    private void updateSortButtonState() {
        int activeColor = resolveColor(android.R.color.holo_blue_dark);
        int inactiveColor = resolveColor(android.R.color.darker_gray);
        for (Map.Entry<LemmaSortMode, ImageButton> entry : sortButtons.entrySet()) {
            ImageButton button = entry.getValue();
            if (button == null) continue;
            if (entry.getKey() == lemmaSortMode) {
                button.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
            } else {
                button.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private void populateLemmaStats(LinearLayout container) {
        lemmaStatsContainer = container;
        if (container == null) return;
        container.removeAllViews();
        lemmaStatsSource.clear();
        lemmaStatsLoaded = false;

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
                TimelineEvents events = normalizeEvents(
                        usageStatsDao.getEvents(languagePair, workScope, stat.lemma, stat.pos, UsageStatsDao.EVENT_EXPOSURE));
                bucket.exposurePositions = events.positions;
                bucket.exposureEvents = events.events;
            } else if (UsageStatsDao.EVENT_LOOKUP.equals(stat.eventType)) {
                bucket.lookupCount = stat.count;
                bucket.lastLookup = stat.lastSeenMs;
                TimelineEvents events = normalizeEvents(
                        usageStatsDao.getEvents(languagePair, workScope, stat.lemma, stat.pos, UsageStatsDao.EVENT_LOOKUP));
                bucket.lookupPositions = events.positions;
                bucket.lookupEvents = events.events;
            }
        }
        lemmaStatsSource.addAll(aggregated.values());
        lemmaStatsLoaded = true;
        refreshLemmaStats();
    }

    private void refreshLemmaStats() {
        if (lemmaStatsContainer == null) return;
        lemmaStatsContainer.removeAllViews();
        if (!lemmaStatsLoaded) return;

        List<LemmaStats> filtered = new ArrayList<>();
        for (LemmaStats entry : lemmaStatsSource) {
            if (matchesFilter(entry)) {
                filtered.add(entry);
            }
        }

        if (filtered.isEmpty()) {
            TextView tv = new TextView(this);
            if (lemmaStatsSource.isEmpty()) {
                tv.setText(R.string.stats_time_never);
            } else {
                tv.setText(R.string.stats_filter_empty);
            }
            lemmaStatsContainer.addView(tv);
            return;
        }

        sortLemmaStats(filtered);

        LayoutInflater inflater = layoutInflater != null ? layoutInflater : LayoutInflater.from(this);
        for (LemmaStats entry : filtered) {
            View item = inflater.inflate(R.layout.item_stats_entry, lemmaStatsContainer, false);
            TextView title = item.findViewById(R.id.statsTitle);
            TextView exposure = item.findViewById(R.id.statsExposure);
            TextView lookup = item.findViewById(R.id.statsLookup);
            UsageTimelineView exposureTimeline = item.findViewById(R.id.statsExposureTimeline);
            UsageTimelineView lookupTimeline = item.findViewById(R.id.statsLookupTimeline);
            View exposureLabels = item.findViewById(R.id.statsExposureTimelineLabels);
            TextView exposureStartLabel = item.findViewById(R.id.statsExposureTimelineStart);
            TextView exposureEndLabel = item.findViewById(R.id.statsExposureTimelineEnd);
            View lookupLabels = item.findViewById(R.id.statsLookupTimelineLabels);
            TextView lookupStartLabel = item.findViewById(R.id.statsLookupTimelineStart);
            TextView lookupEndLabel = item.findViewById(R.id.statsLookupTimelineEnd);
            title.setText(formatLemmaTitle(entry));
            exposure.setText(getString(R.string.stats_exposure_label, entry.exposureCount, formatTime(entry.lastExposure)));
            lookup.setText(getString(R.string.stats_lookup_label, entry.lookupCount, formatTime(entry.lastLookup)));
            bindTimeline(exposureTimeline, exposureLabels, exposureStartLabel, exposureEndLabel,
                    entry.exposureCount, entry.exposurePositions, entry.exposureEvents,
                    resolveColor(android.R.color.holo_green_dark));
            bindTimeline(lookupTimeline, lookupLabels, lookupStartLabel, lookupEndLabel,
                    entry.lookupCount, entry.lookupPositions, entry.lookupEvents,
                    resolveColor(android.R.color.holo_blue_dark));
            lemmaStatsContainer.addView(item);
        }
    }

    private boolean matchesFilter(LemmaStats entry) {
        if (TextUtils.isEmpty(lemmaFilterLower)) return true;
        String lemma = safeString(entry.lemma);
        return lemma.toLowerCase(Locale.getDefault()).contains(lemmaFilterLower);
    }

    private void sortLemmaStats(List<LemmaStats> stats) {
        Comparator<LemmaStats> comparator;
        switch (lemmaSortMode) {
            case ALPHABET_DESC:
                comparator = (a, b) -> compareLemma(b, a);
                break;
            case LOOKUP_COUNT_DESC:
                comparator = (a, b) -> {
                    int cmp = Integer.compare(b.lookupCount, a.lookupCount);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case LOOKUP_TIME_DESC:
                comparator = (a, b) -> {
                    long ta = a.lastLookup > 0 ? a.lastLookup : Long.MIN_VALUE;
                    long tb = b.lastLookup > 0 ? b.lastLookup : Long.MIN_VALUE;
                    int cmp = Long.compare(tb, ta);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case LOOKUP_TIME_ASC:
                comparator = (a, b) -> {
                    long ta = a.lastLookup > 0 ? a.lastLookup : Long.MAX_VALUE;
                    long tb = b.lastLookup > 0 ? b.lastLookup : Long.MAX_VALUE;
                    int cmp = Long.compare(ta, tb);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case LOOKUP_COUNT_ASC:
                comparator = (a, b) -> {
                    int cmp = Integer.compare(a.lookupCount, b.lookupCount);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case EXPOSURE_COUNT_DESC:
                comparator = (a, b) -> {
                    int cmp = Integer.compare(b.exposureCount, a.exposureCount);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case EXPOSURE_COUNT_ASC:
                comparator = (a, b) -> {
                    int cmp = Integer.compare(a.exposureCount, b.exposureCount);
                    if (cmp != 0) return cmp;
                    return compareLemma(a, b);
                };
                break;
            case ALPHABET_ASC:
            default:
                comparator = this::compareLemma;
                break;
        }
        Collections.sort(stats, comparator);
    }

    private int compareLemma(LemmaStats a, LemmaStats b) {
        int cmp = compareTatar(safeString(a.lemma), safeString(b.lemma));
        if (cmp != 0) return cmp;
        return safeString(a.pos).compareToIgnoreCase(safeString(b.pos));
    }

    private int compareTatar(String first, String second) {
        String a = safeString(first);
        String b = safeString(second);
        int length = Math.min(a.length(), b.length());
        for (int i = 0; i < length; i++) {
            char ca = a.charAt(i);
            char cb = b.charAt(i);
            int oa = getTatarOrder(ca);
            int ob = getTatarOrder(cb);
            if (oa != ob) {
                return oa - ob;
            }
        }
        if (a.length() != b.length()) {
            return a.length() - b.length();
        }
        return a.compareToIgnoreCase(b);
    }

    private int getTatarOrder(char ch) {
        Integer value = TATAR_ALPHABET_INDEX.get(ch);
        if (value != null) return value;
        char upper = Character.toUpperCase(ch);
        value = TATAR_ALPHABET_INDEX.get(upper);
        if (value != null) return value;
        char lower = Character.toLowerCase(ch);
        value = TATAR_ALPHABET_INDEX.get(lower);
        if (value != null) return value;
        return TATAR_UNKNOWN_BASE + ch;
    }

    private enum LemmaSortMode {
        ALPHABET_ASC,
        ALPHABET_DESC,
        LOOKUP_COUNT_DESC,
        LOOKUP_TIME_DESC,
        LOOKUP_TIME_ASC,
        LOOKUP_COUNT_ASC,
        EXPOSURE_COUNT_DESC,
        EXPOSURE_COUNT_ASC
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
            String end = formatAxisTime(System.currentTimeMillis());
            view.setText(getString(R.string.stats_axis_time, start, end));
        } else {
            int max = positionBounds != null ? positionBounds.max : -1;
            int displayMax = max < 0 ? 0 : max + 1;
            view.setText(getString(R.string.stats_axis_text, displayMax));
        }
    }

    private void bindTimeline(UsageTimelineView view, View labelsContainer, TextView startLabel, TextView endLabel,
                              int count, List<Float> positions, List<UsageEvent> events, int color) {
        if (view == null) return;
        if (count <= 0 || positions == null || positions.isEmpty()) {
            view.setVisibility(View.GONE);
            if (labelsContainer != null) labelsContainer.setVisibility(View.GONE);
            view.setOnEventClickListener(null);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setColor(color);
        view.setEvents(positions);
        if (mode == MODE_LANGUAGE) {
            if (labelsContainer != null) {
                labelsContainer.setVisibility(View.VISIBLE);
                if (startLabel != null) startLabel.setText(formatAxisTime(timeBounds != null ? timeBounds.start : 0L));
                if (endLabel != null) endLabel.setText(formatAxisTime(System.currentTimeMillis()));
            }
            view.setOnEventClickListener(null);
        } else {
            if (labelsContainer != null) labelsContainer.setVisibility(View.GONE);
            if (events != null && !events.isEmpty()) {
                view.setOnEventClickListener(index -> {
                    if (index >= 0 && index < events.size()) {
                        handleTimelineEventClick(events.get(index));
                    }
                });
            } else {
                view.setOnEventClickListener(null);
            }
        }
    }

    private void handleTimelineEventClick(UsageEvent event) {
        if (mode != MODE_WORK || event == null) return;
        if (event.charIndex < 0) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_TARGET_CHAR_INDEX, event.charIndex);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private TimelineEvents normalizeEvents(List<UsageEvent> events) {
        List<Float> normalized = new ArrayList<>();
        List<UsageEvent> filtered = new ArrayList<>();
        if (events != null && !events.isEmpty()) {
            if (mode == MODE_LANGUAGE) {
                long start = timeBounds != null ? timeBounds.start : 0L;
                long end = Math.max(start, System.currentTimeMillis());
                long span = end - start;
                if (span <= 0L) {
                    for (UsageEvent event : events) {
                        if (event.timestampMs <= 0L) continue;
                        normalized.add(0.5f);
                        filtered.add(event);
                    }
                } else {
                    for (UsageEvent event : events) {
                        if (event.timestampMs <= 0L) continue;
                        float value = (float) (event.timestampMs - start) / (float) span;
                        normalized.add(clamp01(value));
                        filtered.add(event);
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
                    filtered.add(event);
                }
            }
        }
        if (normalized.isEmpty() && events != null) {
            for (UsageEvent event : events) {
                if (mode == MODE_WORK && event.charIndex < 0) continue;
                normalized.add(0.5f);
                filtered.add(event);
            }
        }
        return new TimelineEvents(normalized, filtered);
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
        List<UsageEvent> exposureEvents = new ArrayList<>();
        List<Float> lookupPositions = new ArrayList<>();
        List<UsageEvent> lookupEvents = new ArrayList<>();

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

    private static class TimelineEvents {
        final List<Float> positions;
        final List<UsageEvent> events;

        TimelineEvents(List<Float> positions, List<UsageEvent> events) {
            this.positions = positions;
            this.events = events;
        }
    }
}

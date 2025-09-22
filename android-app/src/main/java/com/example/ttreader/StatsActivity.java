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
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsActivity extends Activity {
    public static final String EXTRA_MODE = "com.example.ttreader.stats.MODE";
    public static final String EXTRA_LANGUAGE_PAIR = "com.example.ttreader.stats.LANGUAGE";
    public static final String EXTRA_WORK_ID = "com.example.ttreader.stats.WORK";
    public static final int MODE_LANGUAGE = 1;
    public static final int MODE_WORK = 2;

    private static final int PAGE_SIZE = 20;
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

    private UsageStatsDao usageStatsDao;
    private DateFormat dateFormat;
    private int mode = MODE_LANGUAGE;
    private String languagePair = "";
    private String workId = "";
    private UsageStatsDao.TimeBounds timeBounds;
    private UsageStatsDao.PositionBounds positionBounds;
    private RecyclerView statsRecycler;
    private StatsAdapter statsAdapter;
    private ExecutorService backgroundExecutor;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<LemmaStats> lemmaStatsSource = new ArrayList<>();
    private final List<LemmaStats> filteredLemmaStats = new ArrayList<>();
    private final List<FeatureStats> featureStats = new ArrayList<>();
    private boolean lemmaStatsLoaded = false;
    private boolean lemmaStatsLoading = false;
    private String lemmaFilterLower = "";
    private String lemmaFilterText = "";
    private LemmaSortMode lemmaSortMode = LemmaSortMode.ALPHABET_ASC;
    private int nextLemmaIndex = 0;
    private CharSequence axisDescriptionText = "";

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

        statsRecycler = findViewById(R.id.statsRecycler);
        statsRecycler.setLayoutManager(new LinearLayoutManager(this));
        statsAdapter = new StatsAdapter();
        statsRecycler.setAdapter(statsAdapter);
        statsRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0) return;
                if (!lemmaStatsLoaded || lemmaStatsLoading) return;
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int total = statsAdapter.getLemmaItemCount();
                int lastVisible = lm.findLastVisibleItemPosition();
                if (total > 0 && lastVisible >= statsAdapter.getLemmaItemStart() + total - 5) {
                    loadNextLemmaPage();
                }
            }
        });

        backgroundExecutor = Executors.newSingleThreadExecutor();

        updateAxisDescriptionText();
        loadLemmaStats();
        loadFeatureStats();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
            backgroundExecutor = null;
        }
    }
    private void loadLemmaStats() {
        lemmaStatsLoaded = false;
        lemmaStatsLoading = true;
        backgroundExecutor.submit(() -> {
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
            List<LemmaStats> result = new ArrayList<>(aggregated.values());
            mainHandler.post(() -> {
                lemmaStatsSource.clear();
                lemmaStatsSource.addAll(result);
                lemmaStatsLoaded = true;
                lemmaStatsLoading = false;
                rebuildFilteredLemmaStats();
            });
        });
    }

    private void rebuildFilteredLemmaStats() {
        if (!lemmaStatsLoaded) return;
        lemmaStatsLoading = true;
        backgroundExecutor.submit(() -> {
            List<LemmaStats> filtered = new ArrayList<>();
            for (LemmaStats entry : lemmaStatsSource) {
                if (matchesFilter(entry)) {
                    filtered.add(entry);
                }
            }
            sortLemmaStats(filtered);
            mainHandler.post(() -> applyFilteredLemmaStats(filtered));
        });
    }

    private void applyFilteredLemmaStats(List<LemmaStats> filtered) {
        filteredLemmaStats.clear();
        filteredLemmaStats.addAll(filtered);
        nextLemmaIndex = 0;
        lemmaStatsLoading = false;
        boolean noData = lemmaStatsSource.isEmpty();
        boolean filterEmpty = filteredLemmaStats.isEmpty();
        loadNextLemmaPage(true, filterEmpty, noData);
    }

    private void loadNextLemmaPage() {
        loadNextLemmaPage(false, filteredLemmaStats.isEmpty(), lemmaStatsSource.isEmpty());
    }

    private void loadNextLemmaPage(boolean reset, boolean filterEmpty, boolean noData) {
        if (!lemmaStatsLoaded) return;
        if (reset) {
            statsAdapter.setLemmaItems(Collections.emptyList(), filterEmpty, noData);
        }
        if (filteredLemmaStats.isEmpty()) {
            statsAdapter.setLemmaItems(Collections.emptyList(), filterEmpty, noData);
            return;
        }
        if (nextLemmaIndex >= filteredLemmaStats.size()) {
            if (reset) {
                statsAdapter.setLemmaItems(Collections.emptyList(), false, false);
            }
            return;
        }
        int end = Math.min(filteredLemmaStats.size(), nextLemmaIndex + PAGE_SIZE);
        List<LemmaStats> page = new ArrayList<>(filteredLemmaStats.subList(nextLemmaIndex, end));
        nextLemmaIndex = end;
        if (reset) {
            statsAdapter.setLemmaItems(page, false, false);
        } else {
            statsAdapter.appendLemmaItems(page);
        }
    }

    private void loadFeatureStats() {
        backgroundExecutor.submit(() -> {
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
            List<FeatureStats> result = new ArrayList<>(aggregated.values());
            Collections.sort(result, (a, b) -> safeString(a.code).compareToIgnoreCase(safeString(b.code)));
            mainHandler.post(() -> {
                featureStats.clear();
                featureStats.addAll(result);
                statsAdapter.setFeatureItems(featureStats);
            });
        });
    }

    private void updateAxisDescriptionText() {
        if (mode == MODE_LANGUAGE) {
            String start = formatAxisTime(timeBounds != null ? timeBounds.start : 0L);
            String end = formatAxisTime(System.currentTimeMillis());
            axisDescriptionText = getString(R.string.stats_axis_time, start, end);
        } else {
            int max = positionBounds != null ? positionBounds.max : -1;
            int displayMax = max < 0 ? 0 : max + 1;
            axisDescriptionText = getString(R.string.stats_axis_text, displayMax);
        }
        statsAdapter.notifyHeaderChanged();
    }

    private void setLemmaSortMode(LemmaSortMode mode) {
        if (mode == null || lemmaSortMode == mode) {
            return;
        }
        lemmaSortMode = mode;
        rebuildFilteredLemmaStats();
        statsAdapter.notifyHeaderChanged();
    }

    private void onFilterTextChanged(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.equals(lemmaFilterText)) {
            return;
        }
        lemmaFilterText = normalized;
        lemmaFilterLower = normalized.toLowerCase(Locale.getDefault());
        rebuildFilteredLemmaStats();
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
        if (TextUtils.isEmpty(code)) return getString(R.string.stats_feature_unknown);
        FeatureMetadata metadata = GrammarResources.getFeatureMetadata(code);
        if (metadata != null) {
            if (!TextUtils.isEmpty(metadata.titleRu)) {
                return metadata.titleRu;
            }
            if (!TextUtils.isEmpty(metadata.titleTt)) {
                return metadata.titleTt;
            }
        }
        return code;
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0L) return getString(R.string.stats_time_never);
        return dateFormat.format(new java.util.Date(timestamp));
    }

    private String formatAxisTime(long timestamp) {
        if (timestamp <= 0L) return getString(R.string.stats_time_unknown);
        return dateFormat.format(new java.util.Date(timestamp));
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private int resolveColor(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(resId, getTheme());
        }
        //noinspection deprecation
        return getResources().getColor(resId);
    }
    private class StatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_HEADER = 1;
        private static final int VIEW_TYPE_LEMMA = 2;
        private static final int VIEW_TYPE_FEATURE_HEADER = 3;
        private static final int VIEW_TYPE_FEATURE = 4;
        private static final int VIEW_TYPE_EMPTY = 5;
        private static final int VIEW_TYPE_FEATURE_EMPTY = 6;

        private final List<LemmaStats> visibleLemmaStats = new ArrayList<>();
        private final List<FeatureStats> visibleFeatureStats = new ArrayList<>();
        private boolean showLemmaEmpty = false;
        private boolean showLemmaNever = false;
        private boolean showFeatureEmpty = true;

        @Override public int getItemCount() {
            int count = 1; // header
            if (showLemmaEmpty) {
                count += 1;
            } else {
                count += visibleLemmaStats.size();
            }
            count += 1; // feature header
            if (showFeatureEmpty) {
                count += 1;
            } else {
                count += visibleFeatureStats.size();
            }
            return count;
        }

        @Override public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_HEADER;
            }
            position -= 1;
            if (showLemmaEmpty) {
                if (position == 0) {
                    return VIEW_TYPE_EMPTY;
                }
                position -= 1;
            } else if (position < visibleLemmaStats.size()) {
                return VIEW_TYPE_LEMMA;
            } else {
                position -= visibleLemmaStats.size();
            }
            if (position == 0) {
                return VIEW_TYPE_FEATURE_HEADER;
            }
            position -= 1;
            if (showFeatureEmpty) {
                return VIEW_TYPE_FEATURE_EMPTY;
            }
            if (position >= 0 && position < visibleFeatureStats.size()) {
                return VIEW_TYPE_FEATURE;
            }
            return VIEW_TYPE_FEATURE;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                View view = inflater.inflate(R.layout.item_stats_header, parent, false);
                return new HeaderViewHolder(view);
            } else if (viewType == VIEW_TYPE_LEMMA) {
                View view = inflater.inflate(R.layout.item_stats_entry, parent, false);
                return new LemmaViewHolder(view);
            } else if (viewType == VIEW_TYPE_FEATURE_HEADER) {
                View view = inflater.inflate(R.layout.item_stats_feature_header, parent, false);
                return new FeatureHeaderViewHolder(view);
            } else if (viewType == VIEW_TYPE_FEATURE_EMPTY) {
                View view = inflater.inflate(R.layout.item_stats_empty, parent, false);
                return new EmptyViewHolder(view);
            } else if (viewType == VIEW_TYPE_FEATURE) {
                View view = inflater.inflate(R.layout.item_stats_feature_entry, parent, false);
                return new FeatureViewHolder(view);
            } else {
                View view = inflater.inflate(R.layout.item_stats_empty, parent, false);
                return new EmptyViewHolder(view);
            }
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);
            if (viewType == VIEW_TYPE_HEADER) {
                ((HeaderViewHolder) holder).bind();
            } else if (viewType == VIEW_TYPE_LEMMA) {
                int index = position - getLemmaItemStart();
                LemmaStats entry = visibleLemmaStats.get(Math.max(0, Math.min(index, visibleLemmaStats.size() - 1)));
                ((LemmaViewHolder) holder).bind(entry);
            } else if (viewType == VIEW_TYPE_EMPTY) {
                String message = showLemmaNever
                        ? getString(R.string.stats_time_never)
                        : getString(R.string.stats_filter_empty);
                ((EmptyViewHolder) holder).bind(message);
            } else if (viewType == VIEW_TYPE_FEATURE_HEADER) {
                ((FeatureHeaderViewHolder) holder).bind();
            } else if (viewType == VIEW_TYPE_FEATURE_EMPTY) {
                ((EmptyViewHolder) holder).bind(getString(R.string.stats_time_never));
            } else if (viewType == VIEW_TYPE_FEATURE) {
                int index = position - getFeatureItemStart();
                FeatureStats entry = visibleFeatureStats.get(Math.max(0, Math.min(index, visibleFeatureStats.size() - 1)));
                ((FeatureViewHolder) holder).bind(entry);
            }
        }

        void setLemmaItems(List<LemmaStats> items, boolean filterEmpty, boolean noData) {
            visibleLemmaStats.clear();
            if (items != null) {
                visibleLemmaStats.addAll(items);
            }
            showLemmaEmpty = filterEmpty && visibleLemmaStats.isEmpty();
            showLemmaNever = noData && showLemmaEmpty;
            notifyDataSetChanged();
        }

        void appendLemmaItems(List<LemmaStats> items) {
            if (items == null || items.isEmpty()) return;
            showLemmaEmpty = false;
            showLemmaNever = false;
            int start = getLemmaItemStart() + visibleLemmaStats.size();
            visibleLemmaStats.addAll(items);
            notifyItemRangeInserted(start, items.size());
        }

        void setFeatureItems(List<FeatureStats> items) {
            visibleFeatureStats.clear();
            if (items != null) {
                visibleFeatureStats.addAll(items);
            }
            showFeatureEmpty = visibleFeatureStats.isEmpty();
            notifyDataSetChanged();
        }

        void notifyHeaderChanged() {
            notifyItemChanged(0);
        }

        int getLemmaItemStart() {
            return 1;
        }

        int getLemmaItemCount() {
            return showLemmaEmpty ? 0 : visibleLemmaStats.size();
        }

        int getFeatureItemStart() {
            int start = 1; // header
            start += showLemmaEmpty ? 1 : visibleLemmaStats.size();
            start += 1; // feature header
            return start;
        }
    }

    private class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final EditText filterInput;
        private final TextView axisDescription;
        private final TextView lemmaHeader;
        private final Map<LemmaSortMode, ImageButton> sortButtons = new EnumMap<>(LemmaSortMode.class);
        private final TextWatcher watcher = new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                onFilterTextChanged(s != null ? s.toString() : "");
            }
        };
        private boolean initialized = false;

        HeaderViewHolder(View itemView) {
            super(itemView);
            filterInput = itemView.findViewById(R.id.statsFilterInput);
            axisDescription = itemView.findViewById(R.id.statsAxisDescription);
            lemmaHeader = itemView.findViewById(R.id.statsLemmaHeader);
            registerSortButton(LemmaSortMode.ALPHABET_ASC, R.id.buttonSortAlphaAsc);
            registerSortButton(LemmaSortMode.ALPHABET_DESC, R.id.buttonSortAlphaDesc);
            registerSortButton(LemmaSortMode.LOOKUP_COUNT_DESC, R.id.buttonSortLookupCountDesc);
            registerSortButton(LemmaSortMode.LOOKUP_TIME_DESC, R.id.buttonSortLookupTimeDesc);
            registerSortButton(LemmaSortMode.LOOKUP_TIME_ASC, R.id.buttonSortLookupTimeAsc);
            registerSortButton(LemmaSortMode.LOOKUP_COUNT_ASC, R.id.buttonSortLookupCountAsc);
            registerSortButton(LemmaSortMode.EXPOSURE_COUNT_DESC, R.id.buttonSortExposureCountDesc);
            registerSortButton(LemmaSortMode.EXPOSURE_COUNT_ASC, R.id.buttonSortExposureCountAsc);
        }

        void bind() {
            if (!initialized) {
                initialized = true;
                if (filterInput != null) {
                    filterInput.addTextChangedListener(watcher);
                }
            }
            if (filterInput != null) {
                String current = filterInput.getText() != null ? filterInput.getText().toString() : "";
                if (!TextUtils.equals(current, lemmaFilterText)) {
                    filterInput.setText(lemmaFilterText);
                    filterInput.setSelection(filterInput.getText().length());
                }
            }
            if (axisDescription != null) {
                axisDescription.setText(axisDescriptionText);
            }
            if (lemmaHeader != null) {
                lemmaHeader.setText(R.string.stats_lemma_header);
            }
            updateSortButtons();
        }

        private void registerSortButton(LemmaSortMode mode, int viewId) {
            ImageButton button = itemView.findViewById(viewId);
            if (button == null) return;
            sortButtons.put(mode, button);
            button.setOnClickListener(v -> setLemmaSortMode(mode));
        }

        private void updateSortButtons() {
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
    }

    private class LemmaViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView exposure;
        private final TextView lookup;
        private final UsageTimelineView exposureTimeline;
        private final UsageTimelineView lookupTimeline;
        private final View exposureLabels;
        private final TextView exposureStartLabel;
        private final TextView exposureEndLabel;
        private final View lookupLabels;
        private final TextView lookupStartLabel;
        private final TextView lookupEndLabel;

        LemmaViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.statsTitle);
            exposure = itemView.findViewById(R.id.statsExposure);
            lookup = itemView.findViewById(R.id.statsLookup);
            exposureTimeline = itemView.findViewById(R.id.statsExposureTimeline);
            lookupTimeline = itemView.findViewById(R.id.statsLookupTimeline);
            exposureLabels = itemView.findViewById(R.id.statsExposureTimelineLabels);
            exposureStartLabel = itemView.findViewById(R.id.statsExposureTimelineStart);
            exposureEndLabel = itemView.findViewById(R.id.statsExposureTimelineEnd);
            lookupLabels = itemView.findViewById(R.id.statsLookupTimelineLabels);
            lookupStartLabel = itemView.findViewById(R.id.statsLookupTimelineStart);
            lookupEndLabel = itemView.findViewById(R.id.statsLookupTimelineEnd);
        }

        void bind(LemmaStats entry) {
            if (title != null) title.setText(formatLemmaTitle(entry));
            if (exposure != null) {
                exposure.setText(getString(R.string.stats_exposure_label,
                        entry.exposureCount, formatTime(entry.lastExposure)));
            }
            if (lookup != null) {
                lookup.setText(getString(R.string.stats_lookup_label,
                        entry.lookupCount, formatTime(entry.lastLookup)));
            }
            bindTimeline(exposureTimeline, exposureLabels, exposureStartLabel, exposureEndLabel,
                    entry.exposureCount, entry.exposurePositions, entry.exposureEvents,
                    resolveColor(android.R.color.holo_green_dark));
            bindTimeline(lookupTimeline, lookupLabels, lookupStartLabel, lookupEndLabel,
                    entry.lookupCount, entry.lookupPositions, entry.lookupEvents,
                    resolveColor(android.R.color.holo_blue_dark));
        }
    }

    private static class FeatureHeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView header;

        FeatureHeaderViewHolder(View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.statsFeatureHeaderText);
        }

        void bind() {
            if (header != null) {
                header.setText(R.string.stats_feature_header);
            }
        }
    }

    private class FeatureViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView details;

        FeatureViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.statsFeatureTitle);
            details = itemView.findViewById(R.id.statsFeatureDetails);
        }

        void bind(FeatureStats entry) {
            if (title != null) {
                title.setText(formatFeatureTitle(entry.code));
            }
            if (details != null) {
                details.setText(getString(R.string.stats_feature_label,
                        formatFeatureTitle(entry.code), entry.count, formatTime(entry.lastSeen)));
            }
        }
    }

    private static class EmptyViewHolder extends RecyclerView.ViewHolder {
        private final TextView textView;

        EmptyViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.statsEmptyText);
        }

        void bind(String message) {
            if (textView != null) {
                textView.setText(message);
            }
        }
    }

    private static class LemmaStats {
        final String lemma;
        final String pos;
        int exposureCount;
        int lookupCount;
        long lastExposure;
        long lastLookup;
        List<Float> exposurePositions;
        List<UsageEvent> exposureEvents;
        List<Float> lookupPositions;
        List<UsageEvent> lookupEvents;

        LemmaStats(String lemma, String pos) {
            this.lemma = lemma;
            this.pos = pos;
        }
    }

    private static class FeatureStats {
        final String code;
        int count = 0;
        long lastSeen = 0L;

        FeatureStats(String code) {
            this.code = code;
        }
    }

    private static class TimelineEvents {
        final List<Float> positions;
        final List<UsageEvent> events;

        TimelineEvents(List<Float> positions, List<UsageEvent> events) {
            this.positions = positions != null ? positions : Collections.emptyList();
            this.events = events != null ? events : Collections.emptyList();
        }
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
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
}

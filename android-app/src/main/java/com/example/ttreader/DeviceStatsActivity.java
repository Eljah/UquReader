package com.example.ttreader;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ttreader.data.DbHelper;
import com.example.ttreader.data.DeviceStatsDao;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceStatsActivity extends Activity {
    private DeviceStatsDao deviceStatsDao;
    private RecyclerView statsRecycler;
    private View emptyView;
    private DeviceStatsAdapter adapter;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DateFormat dateFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_stats);
        setTitle(R.string.device_stats_title);

        statsRecycler = findViewById(R.id.deviceStatsRecycler);
        emptyView = findViewById(R.id.deviceStatsEmpty);
        statsRecycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceStatsAdapter();
        statsRecycler.setAdapter(adapter);

        deviceStatsDao = new DeviceStatsDao(new DbHelper(this).getReadableDatabase());
        executor = Executors.newSingleThreadExecutor();
        dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());

        loadStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void loadStats() {
        if (executor == null) {
            return;
        }
        executor.submit(() -> {
            List<DeviceStatsDao.DeviceReactionStats> allStats = deviceStatsDao.getAllStats();
            List<DeviceStatsDao.DeviceReactionStats> filtered = new ArrayList<>();
            if (allStats != null) {
                for (DeviceStatsDao.DeviceReactionStats entry : allStats) {
                    if (entry == null) continue;
                    if (!entry.bluetoothLikely && !looksLikeBluetooth(entry)) {
                        continue;
                    }
                    if (entry.sampleCount <= 0) {
                        continue;
                    }
                    filtered.add(entry);
                }
            }
            mainHandler.post(() -> applyStats(filtered));
        });
    }

    private boolean looksLikeBluetooth(DeviceStatsDao.DeviceReactionStats stats) {
        if (stats == null) {
            return false;
        }
        String descriptor = stats.descriptor == null ? "" : stats.descriptor.toLowerCase(Locale.ROOT);
        String name = stats.displayName == null ? "" : stats.displayName.toLowerCase(Locale.ROOT);
        return descriptor.contains("bluetooth") || name.contains("bluetooth");
    }

    private void applyStats(List<DeviceStatsDao.DeviceReactionStats> stats) {
        adapter.setItems(stats, dateFormat);
        boolean empty = stats == null || stats.isEmpty();
        statsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private static final class DeviceStatsAdapter extends RecyclerView.Adapter<DeviceStatsAdapter.ViewHolder> {
        private final List<DeviceStatsDao.DeviceReactionStats> items = new ArrayList<>();
        private DateFormat dateFormat;

        void setItems(List<DeviceStatsDao.DeviceReactionStats> stats, DateFormat format) {
            items.clear();
            if (stats != null) {
                items.addAll(stats);
            }
            dateFormat = format;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device_stat, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position), dateFormat);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private static final class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView nameView;
            private final TextView averageView;
            private final TextView lastSeenView;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                nameView = itemView.findViewById(R.id.deviceStatName);
                averageView = itemView.findViewById(R.id.deviceStatAverage);
                lastSeenView = itemView.findViewById(R.id.deviceStatLastSeen);
            }

            void bind(DeviceStatsDao.DeviceReactionStats stats, DateFormat dateFormat) {
                Context context = itemView.getContext();
                String name;
                if (!TextUtils.isEmpty(stats.displayName)) {
                    name = stats.displayName;
                } else if (!TextUtils.isEmpty(stats.descriptor)) {
                    name = stats.descriptor;
                } else {
                    name = context.getString(R.string.device_stats_unknown_device);
                }
                nameView.setText(name);

                double seconds = Math.max(0d, stats.averageDelayMs) / 1000d;
                String averageText = stats.sampleCount > 0
                        ? context.getString(R.string.device_stats_average_with_samples, seconds, stats.sampleCount)
                        : context.getString(R.string.device_stats_average_only, seconds);
                averageView.setText(averageText);

                if (stats.lastSeenMs > 0 && dateFormat != null) {
                    String formatted = dateFormat.format(new Date(stats.lastSeenMs));
                    lastSeenView.setText(context.getString(R.string.device_stats_last_seen, formatted));
                    lastSeenView.setVisibility(View.VISIBLE);
                } else {
                    lastSeenView.setVisibility(View.GONE);
                }
            }
        }
    }
}

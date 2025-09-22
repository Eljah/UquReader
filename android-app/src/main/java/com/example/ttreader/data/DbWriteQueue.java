package com.example.ttreader.data;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes database write operations on a background thread and batches them to avoid UI stalls.
 */
public final class DbWriteQueue {
    private static final String TAG = "DbWriteQueue";
    private static final int MAX_BATCH_SIZE = 32;
    private static final long BATCH_DELAY_MS = 24L;

    private static final DbWriteQueue INSTANCE = new DbWriteQueue();

    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final Object lock = new Object();
    private boolean drainScheduled = false;

    private final Runnable drainRunnable = new Runnable() {
        @Override
        public void run() {
            List<Runnable> batch = new ArrayList<>(MAX_BATCH_SIZE);
            while (true) {
                boolean scheduleMore;
                batch.clear();
                synchronized (lock) {
                    while (!pending.isEmpty() && batch.size() < MAX_BATCH_SIZE) {
                        batch.add(pending.removeFirst());
                    }
                    scheduleMore = !pending.isEmpty();
                    if (scheduleMore) {
                        workerHandler.postDelayed(this, BATCH_DELAY_MS);
                    } else {
                        drainScheduled = false;
                    }
                }
                if (batch.isEmpty()) {
                    break;
                }
                for (Runnable task : batch) {
                    try {
                        task.run();
                    } catch (Exception e) {
                        Log.e(TAG, "DB write task failed", e);
                    }
                }
                if (!scheduleMore) {
                    break;
                }
                // When more work remains we break here; the re-post above will pick it up shortly.
                break;
            }
        }
    };

    private DbWriteQueue() {
        workerThread = new HandlerThread("DbWriteQueue");
        workerThread.start();
        Looper looper = workerThread.getLooper();
        workerHandler = new Handler(looper);
    }

    public static DbWriteQueue getInstance() {
        return INSTANCE;
    }

    public void enqueue(Runnable task) {
        if (task == null) {
            return;
        }
        synchronized (lock) {
            pending.addLast(task);
            if (!drainScheduled) {
                drainScheduled = true;
                workerHandler.post(drainRunnable);
            }
        }
    }
}

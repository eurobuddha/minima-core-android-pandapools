package com.eurobuddha.pandapools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layer 5 background upkeep: while the owner is away, periodically re-publish the announce beacon for any
 * owned pool whose beacon has faded — so the pool stays discoverable to strangers' fresh nodes even if the
 * app is never opened. Best-effort: if the node is stopped/unpaired or nothing has faded, it's a no-op.
 *
 * Scheduled as WorkManager periodic work (Doze-aware, survives reboot on its own — no boot receiver, no
 * foreground service, no exact alarms). The cadence is loose on purpose: a beacon lasts ~a day, so
 * refreshing every few hours keeps it live. The heavy lifting is {@link ReAnnouncer#refreshFadedFromStore};
 * here we just spin up a NodeApi on the main looper, run it, and wait (bounded) for completion.
 */
public class ReAnnounceWorker extends Worker {

    public ReAnnounceWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull @Override public Result doWork() {
        final Context app = getApplicationContext();
        if (OwnPoolStore.all(app).isEmpty()) return Result.success();   // nothing to keep alive

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<NodeApi> nodeRef = new AtomicReference<>();
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());
        // Destroy the node ON THE MAIN THREAD (where it was created + its receiver registered), exactly once.
        final Runnable destroy = () -> {
            NodeApi n = nodeRef.get();
            if (n != null && destroyed.compareAndSet(false, true)) n.onDestroy();
        };
        main.post(() -> {
            try {
                NodeApi node = new NodeApi(app, null);   // applicationContext → callbacks always deliver (no Activity)
                nodeRef.set(node);
                new ReAnnouncer(node).refreshFadedFromStore(app, posted -> { destroy.run(); latch.countDown(); });
            } catch (Throwable t) {
                destroy.run();
                latch.countDown();
            }
        });
        // Budget comfortably above the 180s write timeout so a genuinely-slow re-announce isn't abandoned.
        try { latch.await(200, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        main.post(destroy);   // timeout path cleanup — idempotent, no-op if the callback already destroyed it
        return Result.success();   // always success — best-effort upkeep, never a retry storm
    }
}

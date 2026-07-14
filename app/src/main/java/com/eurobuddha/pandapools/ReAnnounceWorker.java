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
 * Layer 5 background upkeep (GOSSIP): while the app is closed, periodically re-publish the announce beacon
 * for any funded pool this node knows (its own + any it has discovered/tracked) whose beacon has faded — so
 * pools stay discoverable to strangers' fresh nodes even if their creators are offline. Best-effort: if the
 * node is stopped/unpaired or nothing has faded, it's a no-op.
 *
 * Gated to LP nodes (own ≥ 1 pool): an owner's worker re-announces the FULL discovered set (so other people's
 * offline pools stay alive), but a pure-swapper phone runs NO unattended background PoW / tracked-script
 * growth. Foreground sessions (any active user) and always-on desktop/MDS nodes cover the rest of the mesh.
 *
 * Scheduled as WorkManager periodic work (Doze-aware, survives reboot on its own — no boot receiver, no
 * foreground service, no exact alarms). The cadence is loose on purpose: a beacon lasts ~a day, so
 * refreshing every few hours keeps it live. The heavy lifting is {@link ReAnnouncer#refreshFadedFromScan};
 * here we just spin up a NodeApi on the main looper, run it, and wait (bounded) for completion.
 */
public class ReAnnounceWorker extends Worker {

    public ReAnnounceWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull @Override public Result doWork() {
        final Context app = getApplicationContext();
        // Gossip in the background only for LP nodes — keeps unattended 6-hourly PoW + tracked-script growth
        // off pure-swapper phones. Owners still gossip the FULL discovered set (refreshFadedFromScan below).
        if (OwnPoolStore.all(app).isEmpty()) return Result.success();

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
                new ReAnnouncer(node).refreshFadedFromScan(posted -> { destroy.run(); latch.countDown(); });
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

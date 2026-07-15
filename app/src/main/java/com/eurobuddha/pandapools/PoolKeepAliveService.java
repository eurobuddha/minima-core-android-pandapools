package com.eurobuddha.pandapools;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Resident host for the pool keep-alive (ported from minimaSwap's SwapService liveness pattern). PandaPools'
 * keep-fresh (recreate MY reserves before the ~1700-block cascade edge) + beacon re-announce are correct, but
 * their EXECUTION used to ride a single WorkManager job that Samsung Doze throttled — so overnight the keep-alive
 * didn't run and pools went dark. This foreground service keeps the process resident; the Doze-proof
 * {@link HeartbeatReceiver} exact-alarm wakes it every ~15 min to run one keep-alive pass; {@link PoolWatchWorker}
 * + {@link BootReceiver} + onTaskRemoved relaunch it if the OS kills it / after reboot / on app-swipe. LP nodes
 * only (owns ≥1 pool) — a pure swapper runs no unattended PoW.
 *
 * The keep-alive pass IS the reconciliation belt: it re-reads the ACTUAL on-chain reserve age each pass and
 * refreshes any pool still aging (a refresh that posted but didn't land leaves the pool aging → refreshed next
 * pass, after the PoolRefresher TTL), so a "succeeded but never confirmed" refresh self-heals — no separate
 * ok-stamp reconciler needed (unlike minimaSwap, whose 30-min stamp can falsely claim success).
 */
public class PoolKeepAliveService extends Service {

    private static final String CH_FG = "pp_keepalive";
    private static final int FG_ID = 7301;
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.pandapools.HEARTBEAT";
    private static final long PASS_GAP_MS = 5 * 60_000;   // don't run the pass more than once per 5 min (guards a heartbeat/relaunch overlap; never blocks a real 15-min heartbeat)

    private NodeApi node;
    private boolean started = false;   // true only after a successful startForeground + node create
    private long lastPassMs = 0;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // If the OS refuses the foreground service (e.g. a residual dataSync time-budget on Android 14), bail
        // gracefully instead of crashing — the worker/alarm relaunch will retry.
        if (!startForegroundCompat()) { stopSelf(); return; }
        node = new NodeApi(getApplicationContext(), null);   // applicationContext → callbacks always deliver (no Activity)
        HeartbeatReceiver.schedule(this);
        started = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started || node == null) return START_STICKY;   // onCreate bailed (FGS not allowed) → no-op; relaunch retries
        keepAlivePass();   // any start (heartbeat OR relaunch) drives a pass; throttled + LP-gated + wakelock inside
        return START_STICKY;
    }

    /** One keep-alive pass: recreate MY aging reserves (before the cascade edge), then gossip faded beacons.
     *  Async + fire-and-forget — the FGS stays resident so the node callbacks complete. */
    private void keepAlivePass() {
        long now = System.currentTimeMillis();
        if (now - lastPassMs < PASS_GAP_MS) return;                        // overlap guard
        if (OwnPoolStore.all(getApplicationContext()).isEmpty()) return;   // LP nodes only (own ≥1 pool) — no wakelock/work otherwise
        lastPassMs = now;
        acquireTimedWakelock();   // only now that we're actually doing work — let a Doze-woken pass finish before re-suspend
        try {
            final NodeApi n = node;
            new PoolRefresher(n).refreshAgingFromScan(getApplicationContext(), r ->
                    new ReAnnouncer(n).refreshFadedFromScan(p -> {}));
        } catch (Throwable ignored) {}
    }

    /** Timed partial wakelock so a Doze-woken heartbeat can finish one keep-alive pipeline; auto-releases, can't leak. */
    private void acquireTimedWakelock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pandapools:heartbeat");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60_000);
        } catch (Exception ignored) {}
    }

    /** The user swiped the app off recents — keep watching: reschedule the worker + heartbeat + a Doze-proof relaunch. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { PoolWatchWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try { HeartbeatReceiver.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getForegroundService(getApplicationContext(), 21,
                    new Intent(getApplicationContext(), PoolKeepAliveService.class),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (node != null) { try { node.onDestroy(); } catch (Exception ignored) {} node = null; }
    }

    // ----- foreground notification -----
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CH_FG, "Pool keep-alive", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification fgNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 24,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("PandaPools")
                .setContentText("Keeping your pools discoverable to other devices")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private boolean startForegroundCompat() {
        try {
            Notification n = fgNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse is uncapped, unlike dataSync's ~6h/day Android-14 budget.
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;   // ForegroundServiceStartNotAllowedException etc. — don't crash; relaunch retries
        }
    }
}

package com.eurobuddha.pandapools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Doze-proof keep-alive heartbeat. A foreground service keeps the process resident but does NOT exempt
 * its {@code postDelayed} loop from Doze, so overnight a Handler-only loop collapses to Doze maintenance
 * windows. An exact allow-while-idle alarm is the only wake mechanism that fires reliably — each firing
 * chains the next one (~15 min) and drives a single {@link PoolService} tick pass. The frequent heartbeat
 * is for Doze safety; the per-pool 18h gate inside the tick decides when a pool is actually re-broadcast.
 *
 * All {@code PendingIntent}s are {@code FLAG_IMMUTABLE} (required on API 31+).
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60_000;
    private static final int REQUEST_CODE = 22;   // fixed + FLAG_UPDATE_CURRENT → rescheduling is idempotent

    @Override public void onReceive(Context ctx, Intent intent) {
        schedule(ctx);   // chain the next one FIRST — a failure below must not end the heartbeat
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, PoolService.class).setAction(PoolService.ACTION_HEARTBEAT));
        } catch (Exception ignored) {}   // boot/onTaskRemoved relaunch retries
    }

    /** (Re)schedule the next heartbeat ~15 min out. Safe to call from anywhere, any number of times. */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE,
                    new Intent(ctx, HeartbeatReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + INTERVAL_MS;
            // USE_EXACT_ALARM (API 33+) is auto-granted, so canScheduleExactAlarms() is normally true;
            // fall back to inexact allow-while-idle (still fires under Doze, just batched) if not.
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {}
    }
}

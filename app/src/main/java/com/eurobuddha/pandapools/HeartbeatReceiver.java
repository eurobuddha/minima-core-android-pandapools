package com.eurobuddha.pandapools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Doze-proof keep-alive heartbeat (ported from minimaSwap's proven stack). A foreground service keeps the
 * process resident but does NOT exempt a Handler loop from Doze, so overnight the pool keep-alive would collapse
 * to Doze maintenance windows — which is exactly why a plain 6h/4h WorkManager let PandaPools' beacons/reserves
 * go dark. An exact allow-while-idle alarm is the only wake mechanism that fires reliably under Doze; each firing
 * chains the next one and drives one {@link PoolKeepAliveService} keep-alive pass (refresh aging reserves +
 * re-announce faded beacons).
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60_000;   // 15 min — well inside the ~1700-block (~24h) cascade window
    private static final int REQUEST_CODE = 71;            // fixed + FLAG_UPDATE_CURRENT → rescheduling is idempotent

    @Override public void onReceive(Context ctx, Intent intent) {
        schedule(ctx);   // chain the next one FIRST — a crash below must not end the heartbeat
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, PoolKeepAliveService.class).setAction(PoolKeepAliveService.ACTION_HEARTBEAT));
        } catch (Exception ignored) { /* worker/alarm relaunch retries */ }
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
            // USE_EXACT_ALARM (API 33+) is auto-granted, so canScheduleExactAlarms() is normally true; fall back to
            // inexact allow-while-idle (still fires under Doze, just batched) if not.
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {}
    }
}

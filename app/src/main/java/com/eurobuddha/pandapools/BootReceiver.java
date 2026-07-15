package com.eurobuddha.pandapools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Re-arm the pool keep-alive the moment the device (or, in Secure Folder, the folder's user) comes up after a
 * reboot — and after an APK update — so pools keep getting refreshed even if the app is never opened again.
 * Ported from minimaSwap. Starting a specialUse FGS from BOOT_COMPLETED is legal — Android 15's boot-time FGS
 * restriction list covers dataSync/camera/media/mic/phoneCall, not specialUse (which the service uses on API 34+).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        try { PoolWatchWorker.schedule(ctx); } catch (Exception ignored) {}
        HeartbeatReceiver.schedule(ctx);
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, PoolKeepAliveService.class));
        } catch (Exception ignored) { /* worker/heartbeat retry shortly */ }
    }
}

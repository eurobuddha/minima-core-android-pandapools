package com.eurobuddha.pandapools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Restart the pool keep-alive keeper after a reboot — and after an APK update ({@code MY_PACKAGE_REPLACED})
 * — so an owner's pools keep being re-broadcast without the user having to reopen the app.
 *
 * Starting a specialUse FGS from BOOT_COMPLETED is legal — Android 15's boot-time FGS restriction list
 * covers dataSync, camera, media playback/projection, microphone and phoneCall, not specialUse (which
 * {@link PoolService} uses on API 34+).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context ctx, Intent intent) {
        HeartbeatReceiver.schedule(ctx);
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, PoolService.class));
        } catch (Exception ignored) {}   // heartbeat alarm retries shortly
    }
}

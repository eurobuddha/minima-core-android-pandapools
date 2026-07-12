package com.eurobuddha.pandapools;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreground "keep-alive" service. A pool owner's Minima {@code -isclient -mobile} node keeps only a
 * recent-block window + its tracked coins, so a pool (its announce beacon at the shared sentinel and its
 * reserve coins at the covenant address) ages out of every freshly-synced node's view past Minima's
 * ~24h cascade horizon. This service periodically RE-BROADCASTS the owner's pools into the recent chain
 * — net-zero owner-signed transactions that recreate the reserves and post a fresh beacon — so every live
 * node keeps seeing them (the fix proven in minimaSwap). Mirrors {@code SwapService} / {@code LimitService}:
 * a persistent foreground notification, a Doze-proof AlarmManager heartbeat ({@link HeartbeatReceiver}),
 * and reboot restart ({@link BootReceiver}). The per-pool 18h gate — not the 15-min heartbeat — decides
 * when a given pool is actually re-broadcast.
 */
public class PoolService extends Service {

    private static final String CH_FG = "pandapools_fg";
    private static final int FG_ID = 7101;
    /** Heartbeat intent: one Doze-proof tick pass (a Handler loop stalls when the CPU suspends). */
    public static final String ACTION_HEARTBEAT = "com.eurobuddha.pandapools.HEARTBEAT";

    /** Re-broadcast a given pool at most this often. Minima's cascade horizon is ~24h; 18h leaves margin. */
    private static final long KEEPALIVE_INTERVAL_MS = 18L * 60 * 60 * 1000;
    /** Gap between sequential per-pool keep-alives so their wallet-coin selection can't race. */
    private static final long POOL_GAP_MS = 8_000;
    /** Don't start a fresh full tick more often than this even if pokes overlap (the 18h gate does the real work). */
    private static final long TICK_MIN_GAP_MS = 10 * 60_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private NodeApi node;
    private PoolBook book;
    private PoolManager mgr;
    private final Set<String> myKeys = new HashSet<>();
    private boolean ticking = false;
    private long lastTickMs = 0;
    private String fgText;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        // If the OS refuses the foreground service (e.g. a residual dataSync time budget on Android 14),
        // bail gracefully instead of crashing — the heartbeat/boot relaunch will retry.
        if (!startForegroundCompat()) { stopSelf(); return; }
        prefs = getSharedPreferences("pandapools_keepalive", MODE_PRIVATE);
        node = new NodeApi(this, enabled -> {});
        book = new PoolBook(node);
        mgr = new PoolManager(node);
        HeartbeatReceiver.schedule(this);
        h.postDelayed(this::tick, 5_000);   // first pass shortly after start (let pairing settle)
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HEARTBEAT.equals(intent.getAction()) && node != null) {
            acquireTimedWakelock();
            tick();
        }
        return START_STICKY;
    }

    /** The user swiped the app off recents — keep the keeper alive: reschedule the heartbeat + relaunch. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { HeartbeatReceiver.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    getApplicationContext(), 21, new Intent(getApplicationContext(), PoolService.class),
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (node != null) node.onDestroy();
    }

    /** Timed partial wakelock so a Doze-woken heartbeat can finish one keep-alive before the CPU re-suspends. */
    private void acquireTimedWakelock() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK, "pandapools:heartbeat");
            wl.setReferenceCounted(false);
            wl.acquire(3 * 60_000);   // timed — auto-releases, can never leak
        } catch (Exception ignored) {}
    }

    // ----- the tick: load keys → scan pools → keep alive owned+funded+due pools sequentially -----

    private void tick() {
        if (node == null || prefs == null) return;   // onCreate bailed (FGS refused) — nothing is wired
        long now = System.currentTimeMillis();
        if (ticking) return;
        if (now - lastTickMs < TICK_MIN_GAP_MS) return;
        lastTickMs = now;
        ticking = true;
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { parseKeys(j); scanAndKeepAlive(); }
            @Override public void onError(String m) { ticking = false; }   // node down/unpaired — retry next heartbeat
        });
    }

    /** Load this node's local public keys — a pool is OWNED iff its $OPK is one of them (same test as MyLpView). */
    private void parseKeys(JSONObject j) {
        myKeys.clear();
        Object resp = j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) {
                String pk = k.optString("publickey", k.optString("miniaddress", ""));
                if (!pk.isEmpty()) myKeys.add(pk.toLowerCase());
            }
        }
    }

    private void scanAndKeepAlive() {
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) {
                List<Pool> due = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Pool p : pools) {
                    if (!isOwned(p) || !p.funded()) continue;
                    long last = prefs.getLong(prefKey(p), 0);
                    if (now - last > KEEPALIVE_INTERVAL_MS) due.add(p);
                }
                if (due.isEmpty()) { updateFg(idleText()); ticking = false; return; }
                processNext(due, 0);
            }
            @Override public void onError(String msg) { ticking = false; }
        });
    }

    private boolean isOwned(Pool p) { return p.opk != null && myKeys.contains(p.opk.toLowerCase()); }

    private static String prefKey(Pool p) {
        return "ka_" + (p.address == null ? "" : p.address.toLowerCase());
    }

    /** Keep each owned+due pool alive ONE AT A TIME with a small gap, so their wallet-coin selection can't race. */
    private void processNext(final List<Pool> due, final int i) {
        if (i >= due.size()) { updateFg(idleText()); ticking = false; return; }
        final Pool p = due.get(i);
        updateFg("Refreshing pool " + (i + 1) + " of " + due.size() + "…");
        mgr.keepAlive(p, new PoolManager.Result() {
            @Override public void onPosted(String txpowid) {
                prefs.edit().putLong(prefKey(p), System.currentTimeMillis()).apply();
                h.postDelayed(() -> processNext(due, i + 1), POOL_GAP_MS);
            }
            @Override public void onFailed(String message) {
                // Leave the timestamp untouched → this pool is retried on a later heartbeat.
                h.postDelayed(() -> processNext(due, i + 1), POOL_GAP_MS);
            }
        });
    }

    // ----- foreground notification -----

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CH_FG, "Pool keep-alive", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private String idleText() { return "Keeping your pools alive on-chain"; }

    private Notification fgNotification(String text) {
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 20,
                new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH_FG)
                .setContentTitle("PandaPools")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** Re-render the persistent FGS notification only when its text actually changes. */
    private void updateFg(String text) {
        if (text == null || text.equals(fgText)) return;
        fgText = text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(FG_ID, fgNotification(text));
    }

    private boolean startForegroundCompat() {
        fgText = idleText();
        Notification n = fgNotification(fgText);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse is uncapped, unlike dataSync's ~6h/day Android-14 budget that killed overnight keepers.
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

    /** Android 14+: the OS tells a time-limited FGS to stop. Stop gracefully instead of crashing.
     *  (specialUse isn't time-limited, but this is belt-and-braces for the dataSync fallback path.) */
    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int fgsType) { stopGracefully(); }
    private void stopGracefully() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }
}

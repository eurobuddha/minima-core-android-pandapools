package com.eurobuddha.pandapools;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

/**
 * PandaPools — a native client for the Minima constant-product AMM. Talks to the local Minima Core node
 * over the broadcast-Intent IPC ({@link NodeApi}); discovers pools from the on-chain registry
 * ({@link PoolBook}); quotes + (later) routes swaps across them ({@link VirtualCurve}). Pure Minima —
 * no ETH, no comms crypto, no HTLC.
 *
 * M1: node pairing + a Pools tab that discovers and lists live pools.
 */
public class MainActivity extends AppCompatActivity {

    // Tab indices (order of the views array below) — used for goToTab / currentTab.
    public static final int TAB_SWAP = 0, TAB_POOLS = 1, TAB_MYLP = 2, TAB_WALLET = 3, TAB_ACTIVITY = 4;

    private NodeApi node;
    private HistoryDb history;
    private PoolRepository poolRepo;   // ONE shared pool scanner for all tabs (kills the per-tab scan herd)
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View pairingBanner;
    private TextView blockNo;
    private int chainBlock = 0;
    private int currentTab = 0;
    private BaseView[] views;
    private ViewPager pager;
    // The 30s chain-block poll self-reschedules, so gate the reschedule on this flag and cancel the pending
    // run in onStop/onDestroy — otherwise a backgrounded app keeps polling and fanning onNewBlock() out to
    // every tab (battery + node load) while hidden. Started in onResume, stopped in onStop.
    private boolean blockPollActive = false;
    private final Runnable pollTask = this::pollBlock;
    private boolean retrackedOwn = false;   // Layer-2 re-track runs once per session
    private boolean untrackedSentinel = false;   // one-time sentinel-untrack cleanup runs once per session
    // Storage Access Framework pickers for the pool backup file (Increment 2): no storage permission and
    // no FileProvider — the user chooses where to save / which file to restore.
    private ActivityResultLauncher<String> saveDocLauncher;
    private ActivityResultLauncher<String[]> openDocLauncher;
    private Consumer<Uri> pendingSave, pendingOpen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Design.load(this);
        setContentView(R.layout.activity_main);

        // Register the SAF pickers early — registerForActivityResult must run before the Activity is started.
        saveDocLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
            Consumer<Uri> cb = pendingSave; pendingSave = null;
            if (cb != null) cb.accept(uri);
        });
        openDocLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            Consumer<Uri> cb = pendingOpen; pendingOpen = null;
            if (cb != null) cb.accept(uri);
        });

        ((TextView) findViewById(R.id.brandTitle)).setText("PandaPools");
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignore) {}
        // version on the shared header → visible on every tab, so it's always clear which build is installed
        ((TextView) findViewById(R.id.brandSub)).setText("LIQUIDITY POOLS   ·   v" + ver);
        blockNo = findViewById(R.id.blockNo);
        pairingBanner = findViewById(R.id.pairingBanner);
        pairingBanner.setVisibility(View.GONE);

        findViewById(R.id.designToggle).setOnClickListener(v -> {
            // Don't recreate() (which tears down NodeApi) while a tx is building/posting — it would strand
            // the in-flight CmdChain (no callback, no txndelete). Ask the user to finish first.
            if (anyViewBusy()) { android.widget.Toast.makeText(this, "Finish your transaction first, then switch theme.", android.widget.Toast.LENGTH_SHORT).show(); return; }
            Design.next(); Design.set(this, Design.mode()); recreate();
        });
        Button openNode = findViewById(R.id.openNodeBtn);
        if (openNode != null) openNode.setOnClickListener(v -> openMinimaCore());

        applyChrome();
        applyInsets();

        // Create the node + history store BEFORE the views — each view captures act.node()/act.history()
        // in its constructor (PoolBook/PoolManager/PoolTxn/ActivityView hold the reference), so they must
        // already exist.
        node = new NodeApi(this, enabled -> ui.post(() -> setPaired(enabled)));
        history = new HistoryDb(this);
        poolRepo = new PoolRepository(node);   // must exist before the views (they subscribe in their ctors)

        // tabs — Swap · Pools · My LP · Wallet (available balances) · Activity (personal + global history)
        views = new BaseView[]{ new SwapView(this), new PoolsView(this), new MyLpView(this),
                new WalletView(this), new ActivityView(this) };
        String[] titles = { "SWAP", "POOLS", "MY LP", "WALLET", "ACTIVITY" };
        pager = findViewById(R.id.pager);
        pager.setAdapter(new MainPager(views, titles));
        pager.setOffscreenPageLimit(views.length);   // keep all tabs alive so background sync/polls run
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.setupWithViewPager(pager);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { currentTab = position; views[position].onShown(); }
        });

        // The block poll is started from onResume (and cancelled in onStop), so it never runs while hidden.

        ensureKeepAlive();   // keep pools discoverable while the app is closed/away — Doze-proof (ported from minimaSwap)
    }

    /**
     * Bring up the Doze-proof keep-alive stack (ported from minimaSwap, which runs its market with 0 overnight
     * offline windows). A plain WorkManager job is throttled by Samsung Doze and doesn't run overnight — which is
     * why PandaPools' beacons/reserves went dark. Instead: a foreground service ({@link PoolKeepAliveService}) hosts
     * the process, an exact-alarm {@link HeartbeatReceiver} wakes it every ~15 min under Doze to run the keep-fresh
     * + re-announce, {@link PoolWatchWorker} relaunches it if killed, {@link BootReceiver} re-arms after reboot, and
     * we ask for a battery-optimisation exemption (the single biggest cause of overnight death). All idempotent.
     *
     * Gated to LP nodes (owns ≥1 pool): a pure swapper has nothing to keep alive, so it gets NO foreground service,
     * notification, wakelocks or battery-nag. Public + idempotent so {@link MyLpView} can (re)invoke it the moment
     * the first pool is created — before that, OwnPoolStore is empty and this is a no-op.
     */
    public void ensureKeepAlive() {
        if (OwnPoolStore.all(this).isEmpty()) return;   // not an LP node → nothing to keep alive
        ensureNotificationPermission();
        try { androidx.core.content.ContextCompat.startForegroundService(this, new Intent(this, PoolKeepAliveService.class)); } catch (Exception ignored) {}
        try { PoolWatchWorker.schedule(this); } catch (Exception ignored) {}
        try { HeartbeatReceiver.schedule(this); } catch (Exception ignored) {}
        requestBatteryExemption();
    }

    /** API 33+: the ongoing keep-alive FGS notification is suppressed unless POST_NOTIFICATIONS is granted, so the
     *  user gets no indication the keeper is running. Request it (mirrors minimaSwap). */
    private void ensureNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 1);
            }
        } catch (Exception ignored) {}
    }

    /** Ask to be exempt from battery optimisation so keep-alive runs while the app is closed. Re-nag at most weekly
     *  until granted (asking once and giving up is a leading cause of the overnight market disappearing). */
    private void requestBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            android.content.SharedPreferences sp = getSharedPreferences("pandapools", MODE_PRIVATE);
            long last = sp.getLong("batt_asked_at", 0), now = System.currentTimeMillis();
            if (last != 0 && now - last < 7L * 24 * 60 * 60_000L) return;   // asked recently — don't nag every launch
            sp.edit().putLong("batt_asked_at", now).apply();
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    /**
     * Edge-to-edge safe-area padding. On Android 15 (targetSdk 35) the system draws content under the
     * status bar and navigation bar by default; without this the header is obscured at the top and content
     * runs under the nav bar at the bottom. We pad the root (which carries the app background, so it draws
     * across the bar areas too) by the system-bar + display-cutout insets, and set the bar icon contrast
     * to match the current theme.
     */
    private void applyInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final View main = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(main, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), main);
        // light background → dark icons; dark background → light icons
        wic.setAppearanceLightStatusBars(!Design.isDark());
        wic.setAppearanceLightNavigationBars(!Design.isDark());
    }

    private void applyChrome() {
        findViewById(R.id.main).setBackgroundColor(Design.bg());
        pairingBanner.setBackgroundColor(Design.surface2());
        blockNo.setTextColor(Design.dim());
        ((TextView) findViewById(R.id.brandTitle)).setTextColor(Design.accent());
        ((TextView) findViewById(R.id.brandSub)).setTextColor(Design.dim());
        ((TextView) findViewById(R.id.designToggle)).setTextColor(Design.accent());
    }

    private void setPaired(boolean paired) {
        pairingBanner.setVisibility(paired ? View.GONE : View.VISIBLE);
        if (paired) {
            untrackSentinelOnce();   // clean up any legacy sentinel tracking BEFORE the first scan runs
            retrackOwnPools();   // Layer 2: re-track own pools so a wiped/re-synced node rediscovers them
            if (views != null) for (BaseView v : views) v.refresh();
        }
    }

    /**
     * One-time cleanup: stop tracking the unspendable PANDAPOOLS sentinel. Builds up to 0.9.13 ran
     * {@code coinnotify action:add} on it every scan; the sentinel's dust beacons never spend, so a busy node's
     * returned set for {@code coins address:SENTINEL} grew until the RESPONSE broadcast overflowed the Binder
     * limit and the OS killed the app (the uncatchable "can't deliver broadcast" IPC crash). Discovery no longer
     * needs it — a plain depth-bounded scan of the recent chain finds live pools. Idempotent + harmless if the
     * sentinel was never tracked; runs once per session.
     */
    private void untrackSentinelOnce() {
        if (untrackedSentinel || node == null) return;
        untrackedSentinel = true;
        node.cmd("coinnotify action:remove address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {}
            @Override public void onError(String m) {}
        });
    }

    /**
     * Layer 2 (recovery): re-register every OwnPoolStore recipe's covenant with the node, so a re-synced or
     * freshly-wiped node starts tracking the owner's pools again — after which the normal Source-1 scan
     * (tracked contracts) finds them and the reserves become spendable (close/migrate). {@code newscript}
     * re-add is idempotent, so this is harmless on a node that already tracks them; runs once per session.
     * Each completion kicks a shared refresh so a recovered pool repaints promptly.
     */
    private void retrackOwnPools() {
        if (retrackedOwn || node == null) return;
        retrackedOwn = true;
        final List<Pool> own = OwnPoolStore.all(this);

        // Fund-safety launch sweep: forward any funds stranded at owner addresses ($OADR) onward to default-64
        // wallet addresses. No-op on a healthy node with nothing stranded; on a healthy node it also rescues
        // funds a pre-fix close left at $OADR. (Owner-KEY regeneration is NOT done here — it would mint keys
        // every session on a re-paired/different-seed node; it runs only on an explicit Restore instead.)
        final List<String> oadrs = new ArrayList<>();
        for (Pool p : own) if (p.oadr != null && !p.oadr.isEmpty()) oadrs.add(p.oadr);
        new PoolManager(node).sweepOwnerFunds(oadrs, (addressesForwarded, coins) -> {
            if (addressesForwarded > 0 && poolRepo != null) poolRepo.refresh();
        });

        for (Pool p : own) {
            if (p.covenantScript == null || p.covenantScript.isEmpty()) continue;
            node.cmd("newscript trackall:true script:" + Util.scriptArg(p.covenantScript), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { if (poolRepo != null) poolRepo.refresh(); }
                @Override public void onError(String m) {}
            });
        }
    }

    private void pollBlock() {
        if (node == null) return;
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                try {
                    int blk = j.getJSONObject("response").getInt("block");
                    blockNo.setText("#" + blk);
                    if (blk != chainBlock) {
                        chainBlock = blk;
                        // ONE shared registry scan per block (single-flight) — its result is multicast to
                        // every tab. Drives the pool refresh centrally so it doesn't depend on any one view.
                        if (poolRepo != null) poolRepo.refresh();
                        if (views != null) for (BaseView v : views) v.onNewBlock();
                    }
                } catch (Exception ignore) {}
                if (blockPollActive) ui.postDelayed(pollTask, 30000);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) setPaired(false);
                if (blockPollActive) ui.postDelayed(pollTask, 30000);
            }
        });
    }

    /** Begin the 30s block poll (idempotent — a second call while already active is a no-op). */
    private void startPolling() {
        if (blockPollActive || node == null) return;
        blockPollActive = true;
        pollBlock();
    }

    /** Cancel the block poll and any pending reschedule (call when backgrounded/destroyed). */
    private void stopPolling() {
        blockPollActive = false;
        ui.removeCallbacks(pollTask);
    }

    /** True while any tab has a transaction in flight — gates the theme recreate() (see designToggle). */
    private boolean anyViewBusy() {
        if (views != null) for (BaseView v : views) if (v.isBusy()) return true;
        return false;
    }

    private void openMinimaCore() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("org.minimarex.minimacore");
            if (i != null) startActivity(i);
        } catch (Exception ignore) {}
    }

    public NodeApi node() { return node; }
    public HistoryDb history() { return history; }
    public PoolRepository pools() { return poolRepo; }

    /** SAF "create document" picker to SAVE a backup; the callback gets the chosen URI (null if cancelled). */
    public void pickSaveFile(String suggestedName, Consumer<Uri> onPicked) {
        pendingSave = onPicked;
        try { saveDocLauncher.launch(suggestedName); }
        catch (Exception e) { pendingSave = null; if (onPicked != null) onPicked.accept(null); }
    }
    /** SAF "open document" picker to RESTORE a backup; the callback gets the chosen URI (null if cancelled). */
    public void pickOpenFile(Consumer<Uri> onPicked) {
        pendingOpen = onPicked;
        try { openDocLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}); }
        catch (Exception e) { pendingOpen = null; if (onPicked != null) onPicked.accept(null); }
    }
    public Handler ui() { return ui; }
    public int chainBlock() { return chainBlock; }
    public int currentTab() { return currentTab; }
    /** Public hook so HistorySync can flip the pairing banner from the actual command result. */
    public void markPaired(boolean enabled) { setPaired(enabled); }
    /** Jump to a tab by index (e.g. the "See all → Activity" link under the Swap window). */
    public void goToTab(int index) { if (pager != null && index >= 0 && index < views.length) pager.setCurrentItem(index); }

    @Override protected void onStop() {
        super.onStop();
        // Backgrounded: stop the block poll and let each view cancel its repeating polls so we don't drain
        // battery/network or hammer the node while hidden.
        stopPolling();
        if (views != null) for (BaseView v : views) v.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        // Foregrounded: (re)start the block poll and re-activate the visible tab (restarts its poll + refreshes).
        startPolling();
        if (views != null && currentTab >= 0 && currentTab < views.length) views[currentTab].onShown();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // Stop the block poll and view callbacks BEFORE closing the node/DB so no straggler poll reopens a closed HistoryDb.
        stopPolling();
        if (views != null) for (BaseView v : views) v.onDestroy();
        if (node != null) node.onDestroy();
        if (history != null) history.close();
    }
}

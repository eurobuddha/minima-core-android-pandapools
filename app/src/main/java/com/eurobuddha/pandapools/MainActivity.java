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

        findViewById(R.id.designToggle).setOnClickListener(v -> { Design.next(); Design.set(this, Design.mode()); recreate(); });
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
            retrackOwnPools();   // Layer 2: re-track own pools so a wiped/re-synced node rediscovers them
            if (views != null) for (BaseView v : views) v.refresh();
        }
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
        for (Pool p : OwnPoolStore.all(this)) {
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

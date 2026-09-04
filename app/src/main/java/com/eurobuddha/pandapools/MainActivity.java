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
import androidx.appcompat.app.AppCompatDelegate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
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
    private boolean cleanedForeign = false;   // foreign-pool tracking hygiene sweep runs once per session
    // Storage Access Framework pickers for the pool backup file (Increment 2): no storage permission and
    // no FileProvider — the user chooses where to save / which file to restore.
    private ActivityResultLauncher<String> saveDocLauncher;
    private ActivityResultLauncher<String[]> openDocLauncher;
    // A CreateDocument contract's mime type is fixed when it is registered, so the accounting export
    // (a CSV) needs its own launcher rather than reusing the JSON backup one.
    private ActivityResultLauncher<String> saveCsvLauncher;
    private Consumer<Uri> pendingSave, pendingOpen, pendingCsv;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Design.load(this);
        // The palette is a runtime Design.Mode toggle, decoupled from the system day/night. AlertDialog
        // windows, however, draw their background from Theme.Material3.DayNight, which follows the SYSTEM
        // setting — so on a light-mode phone a dialog paints white while Design (dark) hands it white text,
        // leaving inputs invisible (white-on-white). Bind this Activity's framework day/night to Design so
        // dialog chrome always matches the palette. Use the per-Activity LOCAL night mode (applied here at
        // creation time) rather than the global AppCompatDelegate.setDefaultNightMode: the global variant
        // fires its own destructive recreate that drops this (briefly translucent) task to the background.
        getDelegate().setLocalNightMode(
                Design.isDark() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
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
        saveCsvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            Consumer<Uri> cb = pendingCsv; pendingCsv = null;
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
            // Design.next() RETURNS the next mode; it does not mutate state. The old code
            // (Design.next(); Design.set(this, Design.mode())) discarded that return and re-set the
            // CURRENT mode — so the toggle silently did nothing. Persist next()'s result directly.
            Design.set(this, Design.next());
            // Plain recreate: onCreate re-applies the local night mode to match the new Design mode,
            // so the dialog chrome follows the toggle without the destructive global-night-mode recreate.
            recreate();
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
            cleanForeignTracking();   // AFTER re-track: demote foreign pool covenants out of the balance
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

    /**
     * Wallet-balance hygiene sweep (once per session, AFTER the Layer-2 re-track): demote every tracked
     * FOREIGN pool covenant to {@code trackall:false} and drop its already-relevant coins, so a stranger's
     * pool reserves stop counting into this wallet's balance. Old builds' swap path ran
     * {@code newscript trackall:true} on every routed pool and nothing ever untracked it; the swap path now
     * registers foreign pools track:false, and this sweep heals nodes the old builds polluted. Fail-safe:
     * if {@code keys} can't be read (or returns none) ownership can't be proven and NOTHING is written.
     * Re-running each launch is deliberate — core keeps a demoted address in its in-memory relevance cache
     * until the node restarts, so new pool coins can keep turning relevant in between.
     */
    private void cleanForeignTracking() {
        if (cleanedForeign || node == null) return;
        cleanedForeign = true;
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                final Set<String> myKeys = parseKeysLower(j);
                if (myKeys.isEmpty()) return;   // can't prove ownership → zero writes
                final Set<String> ownAddrs = new HashSet<>(), ownOpks = new HashSet<>();
                for (Pool p : OwnPoolStore.all(MainActivity.this)) {
                    if (p.address != null && !p.address.isEmpty()) ownAddrs.add(p.address.toLowerCase());
                    if (p.opk != null && !p.opk.isEmpty()) ownOpks.add(p.opk.toLowerCase());
                }
                node.cmd("scripts", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject sj) {
                        // a failed read (e.g. the >256K IPC stub on a massively-polluted node) parses to no
                        // rows → zero writes. Log it: those are exactly the nodes this sweep exists to heal.
                        if (!TxPost.truthy(sj, "status"))
                            android.util.Log.w("PandaPools", "hygiene sweep: scripts read failed — sweep skipped");
                        List<TrackHygiene.Row> foreign = TrackHygiene.classifyForeign(
                                sj.optJSONArray("response"), myKeys, ownAddrs, ownOpks);
                        if (!foreign.isEmpty()) demoteForeign(foreign, 0);
                    }
                    @Override public void onError(String m) {
                        android.util.Log.w("PandaPools", "hygiene sweep: scripts read failed — sweep skipped: " + m);
                    }
                });
            }
            @Override public void onError(String m) {}
        });
    }

    /** Tolerant {@code keys} parse (array or {"keys":[…]} shapes) → lowercase public keys. */
    private static Set<String> parseKeysLower(JSONObject j) {
        Set<String> out = new HashSet<>();
        Object resp = j == null ? null : j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) {
                String pk = k.optString("publickey", "");
                if (!pk.isEmpty()) out.add(pk.toLowerCase());
            }
        }
        return out;
    }

    /** Sequential, best-effort: re-register each still-track:true foreign row with its VERBATIM script
     *  (reconstruction could differ for a legacy-fee covenant, and {@code newscript} REPLACES the row). */
    private void demoteForeign(List<TrackHygiene.Row> foreign, int i) {
        if (i >= foreign.size()) { untrackForeignCoins(foreign); return; }
        TrackHygiene.Row r = foreign.get(i);
        if (!r.track) { demoteForeign(foreign, i + 1); return; }
        node.cmd("newscript trackall:false script:" + Util.scriptArg(r.script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { demoteForeign(foreign, i + 1); }
            @Override public void onError(String m) { demoteForeign(foreign, i + 1); }
        });
    }

    /**
     * Drop lingering relevant coins at ALL foreign covenant addresses (not just still-track:true rows) —
     * this also heals a partial prior sweep and in-session re-pollution from the stale relevance cache.
     * ONE BOUNDED QUERY PER ADDRESS, sequential — never an unbounded `coins relevant:true`: on a pre-1.6.9
     * node an inline reply of 128K–256K chars overflows the broadcast parcel and kills the app uncatchably,
     * and the polluted, coin-heavy nodes this sweep targets are the likeliest to hit it.
     * Known residual: a recipe-only backup (no coin exports) restored AFTER this sweep ran leaves the old
     * reserve coins non-relevant until the next refresh/swap recreates them — display-only; close/migrate
     * read coins via `coins address:` and are relevance-independent.
     */
    private void untrackForeignCoins(List<TrackHygiene.Row> foreign) {
        untrackNextAddress(foreign, 0, false);
    }

    private void untrackNextAddress(List<TrackHygiene.Row> foreign, int i, boolean touched) {
        if (i >= foreign.size()) {
            if (touched && poolRepo != null) poolRepo.refresh();   // repaint once the balance is clean
            return;
        }
        final String addr = foreign.get(i).address;
        final Set<String> one = new HashSet<>();
        one.add(addr.toLowerCase());
        node.cmd("coins relevant:true address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String> ids = TrackHygiene.coinsToUntrack(j.optJSONArray("response"), one);
                untrackNextCoin(ids, 0, () -> untrackNextAddress(foreign, i + 1, touched || !ids.isEmpty()));
            }
            @Override public void onError(String m) { untrackNextAddress(foreign, i + 1, touched); }
        });
    }

    private void untrackNextCoin(List<String> coinids, int i, Runnable then) {
        if (i >= coinids.size()) { then.run(); return; }
        // {"status":false} (already spent / ADMIN denied) arrives via the SUCCESS callback — keep going
        node.cmd("cointrack enable:false coinid:" + coinids.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { untrackNextCoin(coinids, i + 1, then); }
            @Override public void onError(String m) { untrackNextCoin(coinids, i + 1, then); }
        });
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
    /** SAF "create document" picker to SAVE the pool statement CSV (null URI if cancelled). */
    public void pickSaveCsv(String suggestedName, Consumer<Uri> onPicked) {
        pendingCsv = onPicked;
        try { saveCsvLauncher.launch(suggestedName); }
        catch (Exception e) { pendingCsv = null; if (onPicked != null) onPicked.accept(null); }
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

package com.eurobuddha.pandapools;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Activity tab — two scopes (borrowed from MinimaSwap's my-swaps / market toggle):
 *   • MY ACTIVITY — this device's actions with a live lifecycle (in-flight {@link ActivityLog} entries that
 *     say "Confirming n/3…" the moment you post, then the CONFIRMED on-chain transactions from the node's
 *     own history, persisted permanently in {@link HistoryDb} and synced via {@link HistorySync}).
 *   • ALL POOLS — a live feed of every swap hitting the pools (incl. other people's), from {@link GlobalFeed}.
 *
 * Rows + tap-detail mirror the standalone Minima History app. Re-renders instantly from local stores; a
 * foreground poll keeps the "Confirming n/3" countdown + global feed fresh without a tab-switch.
 */
public class ActivityView extends BaseView {

    private static final int CAP = 150;            // confirmed rows rendered (whole history stays in HistoryDb)
    private static final long FAIL_SHOW_MS = 6 * 3600_000L;   // keep a failed action visible this long

    private final LinearLayout container;
    private final TextView personalTab, globalTab, refreshTv, statusTv;
    private final PoolBook book;
    private final HistorySync sync;
    private boolean showGlobal = false;
    private boolean scanning = false;
    private boolean polling = false;

    public ActivityView(MainActivity a) {
        super(a, R.layout.view_activity);
        container   = find(R.id.actContainer);
        personalTab = find(R.id.actPersonal);
        globalTab   = find(R.id.actGlobal);
        refreshTv   = find(R.id.actRefresh);
        statusTv    = find(R.id.actStatus);
        book = new PoolBook(a, a.node());
        sync = new HistorySync(a, a.history(), syncListener);

        refreshTv.setTextColor(Design.accent());
        statusTv.setTextColor(Design.dim());
        personalTab.setOnClickListener(v -> setScope(false));
        globalTab.setOnClickListener(v -> setScope(true));
        refreshTv.setOnClickListener(v -> { syncPersonal(); scanGlobal(); });
        styleTabs();
        render();
    }

    private void setScope(boolean global) {
        if (showGlobal == global) return;
        showGlobal = global; styleTabs(); render();
        if (global) scanGlobal(); else syncPersonal();
    }

    private void styleTabs() {
        Ui.chip(personalTab, !showGlobal);
        Ui.chip(globalTab, showGlobal);
    }

    @Override public void refresh() { render(); }
    @Override public void onShown() { syncPersonal(); scanGlobal(); render(); startPoll(); }
    @Override public void onNewBlock() { syncPersonal(); scanGlobal(); if (visible()) scheduleRender(); }
    @Override public void onStop() { stopPoll(); }
    @Override public void onDestroy() { stopPoll(); }

    private boolean visible() { return act.currentTab() == MainActivity.TAB_ACTIVITY; }
    /** Coalesce bursty re-renders (per history page, per block) into one, and only while visible. */
    private final Runnable renderTask = this::render;
    private void scheduleRender() { container.removeCallbacks(renderTask); container.postDelayed(renderTask, 150); }

    // ---- data refresh ----

    private void syncPersonal() { if (act.node() != null && !sync.isRunning()) sync.start(); }

    private void scanGlobal() {
        if (scanning || act.node() == null) return;
        scanning = true;
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) {
                scanning = false; GlobalFeed.ingest(act, pools);
                if (showGlobal && visible()) scheduleRender();
            }
            @Override public void onError(String msg) { scanning = false; }
        });
    }

    private final HistorySync.Listener syncListener = new HistorySync.Listener() {
        @Override public void onProgress(int totalNew) { act.runOnUiThread(() -> { if (!showGlobal && visible()) scheduleRender(); }); }
        @Override public void onDone(int totalNew, boolean ok) { act.runOnUiThread(() -> { if (!showGlobal && visible()) scheduleRender(); }); }
    };

    /** Foreground fast-poll so the "Confirming n/3" countdown + global feed self-update. Stops when the
     *  tab isn't visible or the Activity is stopped/destroyed (so it can't leak or reopen a closed DB). */
    private void startPoll() {
        if (polling) return;
        polling = true;
        act.ui().postDelayed(pollTask, 10000);
    }
    private void stopPoll() {
        polling = false;
        act.ui().removeCallbacks(pollTask);
        container.removeCallbacks(renderTask);
    }
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            polling = false;
            if (!visible()) return;   // left the tab / backgrounded → stop (onShown restarts it)
            scanGlobal(); render();
            startPoll();
        }
    };

    // ---- render ----

    private void render() {
        root.setBackgroundColor(Design.bg());
        container.setBackgroundColor(Design.bg());
        container.removeAllViews();
        if (showGlobal) renderGlobal(); else renderPersonal();
    }

    private void renderPersonal() {
        int cb = act.chainBlock();
        long now = System.currentTimeMillis();
        List<ActivityLog.Entry> log = ActivityLog.list(act);
        List<HistoryEntry> hist = act.history().list(CAP, 0, null);

        // Split the local ActivityLog: still in-flight (or recent failure) vs. safely confirmed. Confirmed
        // entries KEEP their card (with both amounts) instead of vanishing the instant they confirm.
        List<ActivityLog.Entry> inflight = new ArrayList<>();
        List<ActivityLog.Entry> confirmed = new ArrayList<>();
        Set<String> shownTx = new HashSet<>();
        for (ActivityLog.Entry e : log) {
            if (showInFlight(e, cb, now)) inflight.add(e);
            else if (!e.failed && e.confirmed(cb)) confirmed.add(e);
        }

        if (inflight.isEmpty() && confirmed.isEmpty() && hist.isEmpty()) {
            empty(sync.isRunning() ? "Loading your history…"
                    : "No activity yet.\nYour swaps, pool creates and withdrawals will appear here.");
            return;
        }
        if (!inflight.isEmpty()) {
            container.addView(header("IN FLIGHT"));
            for (ActivityLog.Entry e : inflight) {
                container.addView(pendingCard(e, cb));
                if (e.txpowid != null && !e.txpowid.isEmpty()) shownTx.add(e.txpowid.toLowerCase());
            }
        }
        if (!confirmed.isEmpty()) {
            container.addView(header("CONFIRMED"));
            for (ActivityLog.Entry e : confirmed) {
                container.addView(pendingCard(e, cb));
                if (e.txpowid != null && !e.txpowid.isEmpty()) shownTx.add(e.txpowid.toLowerCase());
            }
        }
        if (!hist.isEmpty()) {
            boolean headerShown = false;
            for (HistoryEntry n : hist) {
                // dedupe: skip an on-chain row already shown above as a local ActivityLog entry
                if (n.txpowid != null && shownTx.contains(n.txpowid.toLowerCase())) continue;
                if (!headerShown) { container.addView(header("CONFIRMED ON-CHAIN")); headerShown = true; }
                container.addView(historyRow(n));
            }
        }
    }

    private boolean showInFlight(ActivityLog.Entry e, int cb, long now) {
        if (e.failed) return now - e.ts < FAIL_SHOW_MS;   // keep a failure visible a while
        return !e.confirmed(cb);                          // in-flight until safely confirmed
    }

    private void renderGlobal() {
        List<GlobalFeed.Event> events = GlobalFeed.list(act);
        if (events.isEmpty()) {
            empty("No pool swaps seen yet.\nSwaps across all pools — including other people's — appear here as they happen.");
            return;
        }
        container.addView(header("LIVE POOL SWAPS"));
        for (GlobalFeed.Event ev : events) container.addView(globalRow(ev));
    }

    // ---- rows ----

    /** An in-flight / just-posted action (from the local ActivityLog). */
    private View pendingCard(ActivityLog.Entry e, int cb) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8); card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = line(prettyType(e.type), Design.heading(), 14f, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);
        top.addView(statusChip(e.statusText(cb), e.failed ? Design.red() : e.confirmed(cb) ? Design.success() : Design.amber()));
        card.addView(top);

        card.addView(line(e.summary, Design.text(), 13f, false));
        String meta = relative(e.ts);
        if (e.failed && e.failMsg != null && !e.failMsg.isEmpty()) meta = e.failMsg;
        else if (e.txpowid != null && !e.txpowid.isEmpty()) meta = Util.shorten(e.txpowid) + "  ·  " + meta;
        TextView sub = line(meta, Design.dim(), 12f, false);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);
        return card;
    }

    /** A confirmed on-chain transaction (from the node history) — identical shape to the History app. */
    private View historyRow(final HistoryEntry n) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        int color = "received".equals(n.direction) ? 0xFF1EA85A
                : "sent".equals(n.direction) ? Design.red() : Design.dim();
        String g = "received".equals(n.direction) ? "↓" : "sent".equals(n.direction) ? "↑" : "⟲";
        String sign = n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "";

        TextView glyph = new TextView(act);
        glyph.setText(g); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean reshuffle = n.isReshuffle();
        String swapLegs = n.swapLegsDisplay();   // both legs when this tx is a two-token swap
        TextView line1 = new TextView(act);
        line1.setText(swapLegs != null ? swapLegs
                : reshuffle ? n.grossDisplay()
                : (sign + Util.tidyAmount(n.amount) + "  " + n.tokenName));
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Design.typefaceBold());
        TextView line2 = new TextView(act);
        String cp = (n.counterparty == null || n.counterparty.isEmpty()) ? "" : Util.shorten(n.counterparty) + "  ·  ";
        line2.setText((reshuffle ? n.reshuffleLabel() + "  ·  " : cp) + relative(n.timemilli));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        right.setText("#" + n.block); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> showDetail(n));
        return row;
    }

    /** A global (any-trader) swap detected from reserve movement. */
    private View globalRow(GlobalFeed.Event ev) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        TextView glyph = new TextView(act);
        glyph.setText("⇄"); glyph.setTextColor(Design.accent()); glyph.setTextSize(17f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String m = trim(ev.minimaAmt), t = trim8(ev.tokenAmt), tl = ev.tokenLabel;
        String desc = ev.minimaIn ? ("Sold " + m + " MINIMA  →  " + t + " " + tl)
                                   : ("Bought " + m + " MINIMA  ←  " + t + " " + tl);
        TextView line1 = new TextView(act);
        line1.setText(desc); line1.setTextColor(Design.text()); line1.setTextSize(14f); line1.setTypeface(Design.typefaceBold());
        TextView line2 = new TextView(act);
        line2.setText(trim8(ev.price) + " " + tl + "/MINIMA  ·  " + Util.shorten(ev.pool) + "  ·  " + relative(ev.ts));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);
        return row;
    }

    // ---- detail dialog (mirrors the History app) ----

    private void showDetail(HistoryEntry n) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        kv(box, "Direction", n.direction);
        kv(box, "Amount", (n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "") + Util.tidyAmount(n.amount) + " " + n.tokenName);
        kv(box, "Block", String.valueOf(n.block));
        kv(box, "Time", new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.ENGLISH).format(new Date(n.timemilli)));
        copyRow(box, "Txpow id", n.txpowid);
        if (n.counterparty != null && !n.counterparty.isEmpty()) copyRow(box, n.incoming ? "From" : "To", n.counterparty);
        addBreakdown(box, "Inputs", n.inputs);
        addBreakdown(box, "Outputs", n.outputs);
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        new AlertDialog.Builder(act).setTitle("Transaction").setView(sv).setPositiveButton("Close", null).show();
    }

    private void kv(LinearLayout p, String k, String v) {
        TextView t = new TextView(act);
        t.setText(k + ":  " + v);
        t.setTextColor(Design.text()); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
        p.addView(t);
    }

    private void copyRow(LinearLayout p, String k, final String v) {
        TextView t = new TextView(act);
        t.setText(k + ":  " + v + "   (tap to copy)");
        t.setTextColor(Design.dim()); t.setTextSize(12f); t.setTypeface(Typeface.MONOSPACE); t.setPadding(0, dp(4), 0, dp(4));
        t.setOnClickListener(view -> {
            ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show();
        });
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, String title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            TextView h = new TextView(act);
            h.setText(title);
            h.setTextColor(Design.accent()); h.setTextSize(12f); h.setTypeface(Design.typefaceBold()); h.setPadding(0, dp(8), 0, dp(2));
            p.addView(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                String tok = Util.isMinima(tid) ? "Minima" : Util.shorten(tid);
                TextView t = new TextView(act);
                t.setText("• " + Util.tidyAmount(c.optString("amount", "")) + " " + tok + "  →  " + Util.shorten(c.optString("addr", "")));
                t.setTextColor(Design.dim()); t.setTextSize(12f); t.setPadding(dp(6), dp(1), 0, dp(1));
                p.addView(t);
            }
        } catch (Exception ignored) {}
    }

    // ---- helpers ----

    private TextView header(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setAllCaps(true); t.setTextColor(Design.dim()); t.setTextSize(11f);
        t.setLetterSpacing(0.1f); t.setPadding(dp(2), dp(12), 0, dp(4));
        return t;
    }

    private void empty(String s) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(Design.dim()); t.setGravity(Gravity.CENTER); t.setPadding(dp(8), dp(48), dp(8), 0);
        container.addView(t);
    }

    private TextView statusChip(String s, int color) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(11f); t.setTypeface(Design.typefaceBold());
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.withAlpha(color, 30)); g.setCornerRadius(dp(100)); g.setStroke(Math.max(1, dp(1)), Ui.withAlpha(color, 120));
        t.setBackground(g);
        return t;
    }

    private TextView line(String s, int color, float sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private static String prettyType(String type) {
        switch (type) {
            case ActivityLog.CREATE:  return "Create pool";
            case ActivityLog.SWAP:    return "Swap";
            case ActivityLog.DEPOSIT: return "Add liquidity";
            case ActivityLog.MIGRATE: return "Migrate pool";
            case ActivityLog.CLOSE:   return "Withdraw / close";
            default:                  return type;
        }
    }

    private static String relative(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "just now";
        if (d < 3600000) return (d / 60000) + "m ago";
        if (d < 86400000) return (d / 3600000) + "h ago";
        if (d < 7 * 86400000L) return (d / 86400000) + "d ago";
        return new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(new Date(ms));
    }

    private static String trim(BigDecimal b) { return b == null ? "—" : b.stripTrailingZeros().toPlainString(); }
    private static String trim8(BigDecimal b) { return b == null ? "—" : b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
}

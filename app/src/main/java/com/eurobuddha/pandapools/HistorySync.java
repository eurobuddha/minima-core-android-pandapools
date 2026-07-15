package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded, ADAPTIVE, IPC-safe sync of the node's relevant history into {@link HistoryDb} — a verbatim port
 * of the standalone History app's sync.
 *
 * Contract-heavy txpows (pool swaps included) are large — ~40 KB each was measured on-device — so even a small
 * page can blow past the IPC broadcast limit. CRITICAL: over the native broadcast IPC an oversized reply is NOT
 * a catchable "too long" stub — the node broadcasts the raw parcel, the Binder transaction fails, and the OS
 * force-kills the app ("can't deliver broadcast") BEFORE any onError/onResult runs. So the adaptive shrink below
 * can only rescue a CATCHABLE empty/errored page; it can NOT rescue an over-limit first page. The load-bearing
 * safety is therefore the FIRST page size: it must be small enough never to overflow. A single txpow (max:1) is
 * ~40 KB — a large margin under the ~256 KB danger — so we START at 1 (a fixed max:8 first page ≈ 340 KB was the
 * 0.9.14 crash-on-open; see [[minima-ipc-gotchas]]). Two modes: a one-time BACKFILL (pages to the end of what the
 * node retains) and the steady-state INCREMENTAL sync (pages only until the first already-stored txpowid). Both
 * stop on a short page (end of history).
 */
public class HistorySync {

    public interface Listener {
        void onProgress(int totalNew);
        void onDone(int totalNew, boolean ok);
    }

    // ONE txpow per page. The overflow is uncatchable (see class note), so the first page must never exceed the
    // IPC limit — a single ~40 KB txpow is safe; a multi-txpow first page is not. Do NOT raise this.
    private static final int  START_MAX = 1;
    private static final long PAGE_DELAY_MS = 450;
    private static final int  MAX_FETCHES = 600;   // safety cap across pages + retries
    private static final int  MAX_SKIP = 3;        // consecutive max:1 failures before giving up

    private final MainActivity act;
    private final HistoryDb db;
    private final Listener listener;

    private boolean running = false;
    private boolean backfill = false;
    private int pageMax = START_MAX;
    private int totalNew = 0;
    private int fetches = 0;
    private int skipFails = 0;

    public HistorySync(MainActivity act, HistoryDb db, Listener l) { this.act = act; this.db = db; this.listener = l; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        backfill = !"true".equals(db.getMeta("backfill_done", ""));
        pageMax = START_MAX; totalNew = 0; fetches = 0; skipFails = 0;
        fetchPage(0);
    }

    private void fetchPage(final int offset) {
        if (++fetches > MAX_FETCHES) { finish(true); return; }
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                act.markPaired(true);                     // reached the node — hide the pairing banner
                JSONObject r = j.optJSONObject("response");
                JSONArray txpows = r != null ? r.optJSONArray("txpows") : null;
                if (r == null || txpows == null || !j.optBoolean("status", true)) {
                    overLimit(offset);                    // dropped/over-256KB reply → shrink + retry
                    return;
                }
                skipFails = 0;
                JSONArray details = r.optJSONArray("details");
                int got = txpows.length();
                boolean hitKnown = false;
                int pageNew = 0;
                for (int i = 0; i < got; i++) {
                    JSONObject tx = txpows.optJSONObject(i);
                    JSONObject det = (details != null && i < details.length()) ? details.optJSONObject(i) : null;
                    if (tx == null) continue;
                    HistoryEntry e = HistoryEntry.from(tx, det);
                    if (e.txpowid.isEmpty()) continue;
                    if (db.insert(e)) pageNew++; else hitKnown = true;
                }
                totalNew += pageNew;
                if (pageNew > 0 && listener != null) listener.onProgress(totalNew);

                if (got < pageMax) { markBackfillDone(); finish(true); return; }   // short page = end of history
                if (!backfill && hitKnown) { finish(true); return; }                // steady state: caught up
                act.ui().postDelayed(() -> fetchPage(offset + got), PAGE_DELAY_MS); // keep paging
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { act.markPaired(false); finish(false); return; }
                overLimit(offset);                        // dropped oversized reply / timeout → shrink + retry
            }
        });
    }

    /** Page was too big (or dropped): halve and retry the same offset; at max:1, skip one giant txpow. */
    private void overLimit(final int offset) {
        if (pageMax > 1) {
            pageMax = Math.max(1, pageMax / 2);
            act.ui().postDelayed(() -> fetchPage(offset), PAGE_DELAY_MS);
        } else if (++skipFails <= MAX_SKIP) {
            // even a single txpow exceeds 256 KB — skip it so it can't stall the whole sync
            act.ui().postDelayed(() -> fetchPage(offset + 1), PAGE_DELAY_MS);
        } else {
            finish(false);
        }
    }

    private void markBackfillDone() { if (backfill) db.setMeta("backfill_done", "true"); }

    private void finish(boolean ok) {
        running = false;
        if (db.getMeta("first_sync_ts", "").isEmpty())
            db.setMeta("first_sync_ts", String.valueOf(System.currentTimeMillis()));
        db.setMeta("synced_tip_block", String.valueOf(act.chainBlock()));
        db.setMeta("last_sync_ts", String.valueOf(System.currentTimeMillis()));
        if (listener != null) listener.onDone(totalNew, ok);
    }
}

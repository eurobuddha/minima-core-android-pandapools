package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A local, persisted log of THIS device's PandaPools actions (create / swap / add / migrate / close) with a
 * lifecycle the UI can watch the MOMENT an action is posted: SUBMITTED (confirming on-chain) → CONFIRMED
 * (derived from block height) or FAILED. This is the immediate-feedback layer that closes the "did it
 * work?" gap during the ~1–3 block wait BEFORE the transaction shows up in the node's own history
 * ({@link HistoryDb}). Read by the inline recent-activity strip under the Swap window and by the Activity
 * tab's "in flight" section. Purely local + best-effort, capped.
 */
public final class ActivityLog {

    public static final String CREATE="CREATE", SWAP="SWAP", DEPOSIT="DEPOSIT", MIGRATE="MIGRATE", CLOSE="CLOSE";
    /** Blocks after submit at which a posted action is treated as safely confirmed + discoverable. */
    public static final int CONFIRM_BLOCKS = 3;

    private static final String PREFS = "pandapools_activity";
    private static final String KEY = "log";
    private static final int MAX = 60;

    private ActivityLog() {}

    public static final class Entry {
        public final String type, summary, txpowid, failMsg;
        public final int submitBlock;
        public final long ts;
        public final boolean failed;
        Entry(String type, String summary, String txpowid, int submitBlock, long ts, boolean failed, String failMsg) {
            this.type=type; this.summary=summary; this.txpowid=txpowid; this.submitBlock=submitBlock;
            this.ts=ts; this.failed=failed; this.failMsg=failMsg;
        }
        /** True once enough blocks have passed since submit (only meaningful when !failed). */
        public boolean confirmed(int chainBlock) {
            if (failed) return false;
            if (submitBlock > 0 && chainBlock > 0) return (chainBlock - submitBlock) >= CONFIRM_BLOCKS;
            // block height was unknown at submit (posted before the first block poll) → fall back to elapsed time
            return System.currentTimeMillis() - ts > 4 * 60_000L;
        }
        /** Blocks elapsed since submit (clamped ≥0), or -1 if unknown. */
        public int elapsed(int chainBlock) {
            if (chainBlock <= 0 || submitBlock <= 0) return -1;
            return Math.max(0, chainBlock - submitBlock);
        }
        /** Short human status for a chip. */
        public String statusText(int chainBlock) {
            if (failed) return "Failed";
            if (confirmed(chainBlock)) return "Confirmed";
            int el = elapsed(chainBlock);
            if (el < 0) return "Submitted";
            return "Confirming " + Math.min(el, CONFIRM_BLOCKS) + "/" + CONFIRM_BLOCKS;
        }
    }

    public static void record(Context c, String type, String summary, String txpowid, int submitBlock) {
        add(c, new Entry(type, summary, txpowid, submitBlock, System.currentTimeMillis(), false, null));
    }
    public static void recordFailed(Context c, String type, String summary, String failMsg) {
        add(c, new Entry(type, summary, null, 0, System.currentTimeMillis(), true, failMsg));
    }

    private static synchronized void add(Context c, Entry e) {
        if (c == null) return;
        List<Entry> all = list(c);
        all.add(0, e);   // newest first
        while (all.size() > MAX) all.remove(all.size() - 1);
        save(c, all);
    }

    public static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        if (c == null) return out;
        String s = prefs(c).getString(KEY, null);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(
                        o.optString("ty", ""), o.optString("s", ""), o.has("tx") ? o.optString("tx") : null,
                        o.optInt("b", 0), o.optLong("t", 0), o.optBoolean("f", false),
                        o.has("fm") ? o.optString("fm") : null));
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** The most recent {@code n} entries (newest first) — for the inline strip. */
    public static List<Entry> recent(Context c, int n) {
        List<Entry> all = list(c);
        return all.size() <= n ? all : new ArrayList<>(all.subList(0, n));
    }

    /** True if any non-failed entry has not yet reached CONFIRM_BLOCKS — drives the fast self-poll. */
    public static boolean hasPending(Context c, int chainBlock) {
        for (Entry e : list(c)) if (!e.failed && !e.confirmed(chainBlock)) return true;
        return false;
    }

    private static void save(Context c, List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("ty", e.type); o.put("s", e.summary);
                if (e.txpowid != null) o.put("tx", e.txpowid);
                o.put("b", e.submitBlock); o.put("t", e.ts); o.put("f", e.failed);
                if (e.failMsg != null) o.put("fm", e.failMsg);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}

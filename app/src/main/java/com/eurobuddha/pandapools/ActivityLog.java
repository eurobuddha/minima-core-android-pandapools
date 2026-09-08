package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Local submission receipts. Confirmation requires a successful node `txpow onchain` lookup;
 * neither elapsed blocks since submission nor wall-clock time establishes inclusion. */
public final class ActivityLog {

    public static final String CREATE="CREATE", SWAP="SWAP", DEPOSIT="DEPOSIT", MIGRATE="MIGRATE", CLOSE="CLOSE", CONSOLIDATE="CONSOLIDATE";
    /** Node-reported confirmation depth required for a confirmed receipt. */
    public static final int CONFIRM_BLOCKS = 3;

    private static final String PREFS = "pandapools_activity";
    private static final String KEY = "log";
    private static final int MAX = 60;

    private ActivityLog() {}

    public static final class Entry {
        public final String type, summary, failMsg;
        public String txpowid;
        String transactionId = "";
        public final int submitBlock;
        public final long ts;
        public final boolean failed;
        int verifiedDepth = -1;
        Entry(String type, String summary, String txpowid, int submitBlock, long ts, boolean failed, String failMsg) {
            this.type=type; this.summary=summary; this.txpowid=txpowid; this.submitBlock=submitBlock;
            this.ts=ts; this.failed=failed && !NodeApi.ERR_WRITE_UNCERTAIN.equals(failMsg); this.failMsg=failMsg;
        }
        /** A local submission is never chain-confirmation evidence. The node history is displayed separately. */
        public boolean confirmed(int chainBlock) { return !failed && verifiedDepth >= CONFIRM_BLOCKS; }
        /** Blocks elapsed since submit (clamped ≥0), or -1 if unknown. */
        public int elapsed(int chainBlock) {
            if (chainBlock <= 0 || submitBlock <= 0) return -1;
            return Math.max(0, chainBlock - submitBlock);
        }
        /** Short human status for a chip. */
        public String statusText(int chainBlock) {
            if (NodeApi.ERR_WRITE_UNCERTAIN.equals(failMsg)) return "Outcome unknown · check node history";
            if (failed) return "Failed";
            if (confirmed(chainBlock)) return "Confirmed · verified by node";
            if (verifiedDepth >= 0) return "On-chain · " + verifiedDepth + "/" + CONFIRM_BLOCKS + " confirmations";
            return "Submitted · confirmation unverified";
        }
    }

    public static void record(Context c, String type, String summary, String txpowid, int submitBlock) {
        Entry e = new Entry(type, summary, txpowid, submitBlock, System.currentTimeMillis(), false, null);
        e.transactionId = transactionFor(c, txpowid);
        add(c, e);
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
                out.get(out.size() - 1).verifiedDepth = o.optInt("vd", -1);
                out.get(out.size() - 1).transactionId = o.optString("tn", "");
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
        for (Entry e : list(c)) if (!e.failed && !e.confirmed(chainBlock)
                && System.currentTimeMillis() - e.ts < 10 * 60_000L) return true;
        return false;
    }

    private static void save(Context c, List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("ty", e.type); o.put("s", e.summary);
                if (e.txpowid != null) o.put("tx", e.txpowid);
                o.put("vd", e.verifiedDepth); o.put("tn", e.transactionId);
                o.put("b", e.submitBlock); o.put("t", e.ts); o.put("f", e.failed);
                if (e.failMsg != null) o.put("fm", e.failMsg);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    /** The async miner changes TxPoW id. Match the immutable transaction id from the node's JSON,
     * then verify the mined TxPoW on-chain. UTXO wallet's input/output resolver supplied the workflow;
     * exact transaction-id equality also rules out a conflicting spend to the same output address. */
    static String transactionId(JSONObject txpow) {
        JSONObject body = txpow == null ? null : txpow.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        String id = txn == null ? "" : txn.optString("transactionid", "");
        return FundingCoins.hex(id) ? id : "";
    }
    static synchronized void rememberSubmission(Context ctx, JSONObject reply, String postedId) {
        JSONObject txpow = reply == null ? null : reply.optJSONObject("response");
        if (txpow != null && txpow.optJSONObject("txpow") != null) txpow = txpow.optJSONObject("txpow");
        String id = transactionId(txpow);
        if (id.isEmpty()) return;
        try {
            JSONArray previous = new JSONArray(prefs(ctx).getString("postids", "[]"));
            JSONArray next = new JSONArray().put(new JSONObject().put("p", postedId).put("t", id));
            for (int i = 0; i < previous.length() && i < MAX - 1; i++) next.put(previous.get(i));
            prefs(ctx).edit().putString("postids", next.toString()).commit();
        } catch (Exception ignored) { }
    }
    private static String transactionFor(Context ctx, String postedId) {
        if (ctx == null || postedId == null) return "";
        try {
            JSONArray ids = new JSONArray(prefs(ctx).getString("postids", "[]"));
            for (int i = 0; i < ids.length(); i++) {
                JSONObject row = ids.getJSONObject(i);
                if (postedId.equals(row.optString("p"))) return row.optString("t", "");
            }
        } catch (Exception ignored) { }
        return "";
    }
    static synchronized void observeHistory(Context ctx, JSONObject txpow) {
        String transaction = transactionId(txpow);
        String minedId = txpow == null ? "" : txpow.optString("txpowid", "");
        if (transaction.isEmpty() || !FundingCoins.hex(minedId)) return;
        List<Entry> entries = list(ctx); boolean changed = false;
        for (Entry e : entries) if (!e.failed && transaction.equalsIgnoreCase(e.transactionId)
                && !minedId.equalsIgnoreCase(e.txpowid)) {
            e.txpowid = minedId; e.verifiedDepth = -1; changed = true;
        }
        if (changed) save(ctx, entries);
    }

    private static boolean checking;
    private static int cursor;
    private static long lastCheck;

    /** Compact, serial lookups; at most eight per refresh, rotating through the bounded local log. */
    static void verify(Context ctx, NodeApi node, Runnable done) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (checking || now - lastCheck < 30_000) { done.run(); return; }
        List<Entry> eligible = new ArrayList<>();
        for (Entry e : list(ctx)) if (!e.failed && FundingCoins.hex(e.txpowid)) eligible.add(e);
        if (eligible.isEmpty()) { done.run(); return; }
        List<Entry> batch = new ArrayList<>();
        for (int i = 0; i < Math.min(8, eligible.size()); i++)
            batch.add(eligible.get((cursor + i) % eligible.size()));
        cursor = (cursor + batch.size()) % eligible.size();
        checking = true; lastCheck = now;
        verifyNext(ctx.getApplicationContext(), node, batch, 0, () -> { checking = false; done.run(); });
    }
    private static void verifyNext(Context ctx, NodeApi node, List<Entry> batch, int i, Runnable done) {
        if (i == batch.size()) { done.run(); return; }
        Entry e = batch.get(i);
        node.cmd("txpow onchain:" + e.txpowid, new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                // A failed/unsupported lookup cannot erase previous evidence or invent confirmation.
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (TxPost.truthy(reply, "status") && r != null && r.has("found"))
                    setDepth(ctx, e.txpowid, confirmationDepth(reply));
                verifyNext(ctx, node, batch, i + 1, done);
            }
            public void onError(String message) { verifyNext(ctx, node, batch, i + 1, done); }
        });
    }
    static int confirmationDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (!TxPost.truthy(reply, "status") || !TxPost.truthy(r, "found")) return -1;
        try {
            int depth = new java.math.BigDecimal(r.get("confirmations").toString()).intValueExact();
            return depth < 0 ? -1 : depth;
        } catch (Exception invalid) { return -1; }
    }
    private static synchronized void setDepth(Context ctx, String id, int depth) {
        List<Entry> entries = list(ctx);
        for (Entry e : entries) if (id.equals(e.txpowid)) e.verifiedDepth = depth;
        save(ctx, entries);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}

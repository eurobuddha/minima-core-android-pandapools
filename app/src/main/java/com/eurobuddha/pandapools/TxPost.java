package com.eurobuddha.pandapools;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared "validate then post" for every fund-moving transaction. It appends {@code txncheck} to the
 * built command list and only posts if the covenant scripts pass AND the amounts balance — because
 * {@code txnpost} returns status:true even for a transaction the covenant will reject at mining, so
 * gating on the check is what lets us report success honestly (never a false "✓" while funds quietly
 * stayed put) and catches a moved-pool race BEFORE broadcasting a doomed tx. Our covenants use no
 * @COINAGE/@BLKNUM globals, so txncheck's script verdict is authoritative here. All failure modes fail
 * closed (a missing/renamed check field blocks the post rather than mis-reporting).
 */
public final class TxPost {

    public interface Done { void ok(String txpowid); void fail(String message); }

    private TxPost() {}

    static final NodeApi.Cb NOOP = new NodeApi.Cb() {
        @Override public void onResult(JSONObject j) {}
        @Override public void onError(String m) {}
    };

    // ---- the signing gate -------------------------------------------------------------------------

    /**
     * SERIAL SIGNING. Only one build→sign→post chain from this app may be in flight at a time.
     *
     * Minima signatures are stateful: each key is a tree of one-time signatures and the node picks the
     * next leaf by reading, incrementing and writing a per-key `uses` counter. Two transactions signing
     * the same key at once both read the same value and both sign the SAME leaf over DIFFERENT data —
     * which is a reused Winternitz signature, and reusing a leaf leaks its private key. This was
     * observed for real: 7 of 64 default keys on a live node flagged as re-used.
     *
     * We hit it because several paths fan out deliberately — {@link PoolRefresher} posts up to 8
     * refreshes in one loop, {@link ReAnnouncer} the same, {@code sweepOwnerFunds} one per owner
     * address — and {@link PoolManager#selectCoins} sorts largest-first, so every one of those parallel
     * builders picks the SAME funding coin, at the same address, owned by the same key.
     *
     * The node has since been fixed to synchronize its own signing, but this gate stays: our apps also
     * run against nodes we don't control, and serialising is correct anyway — the 8 parallel refreshes
     * were double-spending one coin and 7 of them were always going to fail.
     *
     * Everything here runs on the main thread ({@link NodeApi} funnels every node callback back to it),
     * so the queue needs no locking.
     */
    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static boolean signing = false;
    private static Runnable watchdog = null;

    /** Longer than NodeApi's 180s write timeout, so the watchdog only ever fires for a chain whose
     *  callback was genuinely lost — never for one that is merely slow. */
    private static final long MAX_HOLD_MS = 200_000;

    /** Lazily resolved so the queue logic works without a Looper. Off-device android.jar's stub THROWS
     *  rather than returning null, so catch broadly; without a Looper the watchdog is simply absent. */
    private static Handler main;
    private static boolean mainResolved = false;
    private static Handler main() {
        if (!mainResolved) {
            mainResolved = true;
            try { Looper l = Looper.getMainLooper(); if (l != null) main = new Handler(l); }
            catch (Throwable noAndroidRuntime) { main = null; }
        }
        return main;
    }

    private static void submit(Runnable chain) {
        QUEUE.add(chain);
        if (!signing) startNext();
    }

    private static void startNext() {
        Runnable next = QUEUE.poll();
        if (next == null) { signing = false; return; }
        signing = true;
        Handler h = main();
        if (h != null) {
            watchdog = () -> { watchdog = null; startNext(); };   // a dropped callback must not wedge the app
            h.postDelayed(watchdog, MAX_HOLD_MS);
        }
        next.run();
    }

    private static void release() {
        if (watchdog != null) { Handler h = main(); if (h != null) h.removeCallbacks(watchdog); watchdog = null; }
        startNext();
    }

    /** Releases the gate exactly once, however the chain ends. */
    private static Done gated(Done done) {
        return new Done() {
            private boolean released = false;
            private void free() { if (!released) { released = true; release(); } }
            @Override public void ok(String txpowid) { free(); done.ok(txpowid); }
            @Override public void fail(String message) { free(); done.fail(message); }
        };
    }

    public static void checkThenPost(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done) {
        final Done gatedDone = gated(done);
        submit(() -> runChain(node, txid, cmdsThroughBasics, gatedDone));
    }

    private static void runChain(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done) {
        List<String> cmds = new ArrayList<>(cmdsThroughBasics);
        cmds.add("txncheck id:" + txid);
        CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                JSONObject resp = last != null ? last.optJSONObject("response") : null;
                // txncheck's TOP-LEVEL `scripts` is the COUNT of distinct input scripts (not a verdict).
                // The real covenant verdict is the boolean `response.valid.scripts`; `validamounts` is a
                // top-level boolean. Read both truthily (handles bool/int/string).
                JSONObject valid = resp != null ? resp.optJSONObject("valid") : null;
                boolean scriptsOk = truthy(valid, "scripts");
                boolean amountsOk = truthy(resp, "validamounts");
                boolean mmrOk = truthy(valid, "mmrproofs");   // false ⇒ an input was already spent (pool moved)
                if (!scriptsOk || !amountsOk || !mmrOk) {
                    node.cmd("txndelete id:" + txid, NOOP);
                    done.fail(!mmrOk
                            ? "an input coin was already spent (the pool moved) — nothing was posted"
                            : !scriptsOk
                            ? "the pool covenant rejects this transaction — nothing was posted"
                            : "the amounts don't balance — nothing was posted");
                    return;
                }
                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        if (j.optBoolean("status", false)) done.ok(Util.extractTxpowid(j, txid));
                        else { node.cmd("txndelete id:" + txid, NOOP); done.fail("post rejected" + err(j)); }
                    }
                    @Override public void onError(String m) { node.cmd("txndelete id:" + txid, NOOP); done.fail(m); }
                });
            }
            @Override public void fail(String message) { done.fail(message); }
        });
    }

    static String err(JSONObject j) { String e = j.optString("error", ""); return e.isEmpty() ? "" : " : " + e; }

    /** True if the node returned this flag as boolean true, the integer 1, or the string "true"/"1".
     *  txncheck is inconsistent — `scripts` is an int while `validamounts`/`validtransaction` are booleans. */
    static boolean truthy(JSONObject o, String key) {
        Object v = (o == null) ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() == 1;
        if (v instanceof String) { String s = ((String) v).trim(); return s.equals("1") || s.equalsIgnoreCase("true"); }
        return false;
    }
}

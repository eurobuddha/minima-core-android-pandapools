package com.eurobuddha.pandapools;

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
    static void submit(Runnable chain) {
        QUEUE.add(chain);
        if (!signing) startNext();
    }

    private static void startNext() {
        Runnable next = QUEUE.poll();
        if (next == null) { signing = false; return; }
        signing = true;
        // Never release on elapsed time: the node may still be signing. NodeApi owns command
        // timeouts, and quarantines ambiguous writes before another chain can reach signing.
        next.run();
    }

    private static void release() {
        startNext();
    }

    /** Releases the gate exactly once, however the chain ends. */
    static Done gated(Done done) {
        return new Done() {
            private boolean released = false;
            @Override public void ok(String txpowid) {
                if (released) return; released = true;
                try { done.ok(txpowid); } finally { release(); }
            }
            @Override public void fail(String message) {
                if (released) return; released = true;
                try { done.fail(message); } finally { release(); }
            }
        };
    }

    public static void checkThenPost(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done) {
        List<String> inputIds = new ArrayList<>();
        for (String command : cmdsThroughBasics) if (command.startsWith("txninput ")) {
            String id = "";
            for (String part : command.split("\\s+")) if (part.startsWith("coinid:")) id = part.substring(7);
            if (!FundingCoins.hex(id)) { done.fail("Invalid transaction input. Nothing was posted."); return; }
            inputIds.add(id);
        }
        int inputs = inputIds.size();
        if (inputs > FundingCoins.MAX_INPUTS) {
            done.fail("This transaction needs " + inputs + " inputs; the mobile limit is "
                    + FundingCoins.MAX_INPUTS + ". Consolidate coins or use fewer pools. Nothing was posted.");
            return;
        }
        if (!CoinLock.claimInputs(inputIds)) {
            done.fail("An input is already in another queued transaction, or appears twice. Wait for Activity to update and retry.");
            return;
        }
        final Done gatedDone = gated(new Done() {
            public void ok(String id) { CoinLock.finishInputs(inputIds); done.ok(id); }
            public void fail(String message) { CoinLock.finishInputs(inputIds); done.fail(message); }
        });
        submit(() -> runChain(node, txid, cmdsThroughBasics, gatedDone));
    }

    private static void runChain(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done) {
        List<String> cmds = new ArrayList<>(cmdsThroughBasics);
        cmds.add("txncheck id:" + txid);
        CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                String invalid = checkFailure(last);
                if (invalid != null) {
                    node.cmd("txndelete id:" + txid, NOOP);
                    done.fail(invalid); return;
                }
                node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        if (j.optBoolean("status", false)) {
                            node.cmd("txndelete id:" + txid, NOOP);
                            String postedId = Util.extractTxpowid(j, txid);
                            ActivityLog.rememberSubmission(node.context(), j, postedId);
                            done.ok(postedId);
                        }
                        else { node.cmd("txndelete id:" + txid, NOOP); done.fail(postError(j)); }
                    }
                    @Override public void onError(String m) {
                        if (!NodeApi.ERR_WRITE_UNCERTAIN.equals(m)) node.cmd("txndelete id:" + txid, NOOP);
                        done.fail(m);
                    }
                });
            }
            @Override public void fail(String message) { done.fail(message); }
        });
    }

    static String checkFailure(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        JSONObject valid = r == null ? null : r.optJSONObject("valid");
        if (!truthy(reply, "status")) return "The node could not check the transaction. Nothing was posted.";
        if (!truthy(valid, "mmrproofs")) return "An input coin was already spent or its proof is invalid. Refresh and retry; nothing was posted.";
        if (!truthy(r, "validamounts")) return "The transaction amounts do not balance. Nothing was posted.";
        if (!truthy(valid, "scripts")) return "The pool covenant rejects this transaction. Nothing was posted.";
        if (!truthy(valid, "basic") || !truthy(r, "allsignaturesvalid") || !truthy(r, "validtransaction"))
            return "The node did not validate the complete transaction and signatures. Nothing was posted.";
        return null;
    }

    static String postError(JSONObject reply) {
        String error = reply == null ? "" : reply.optString("error", "");
        if (error.contains("TxPoW size too large"))
            return "Minima rejected the transaction at txnpost: " + error
                    + ". These are serialized TxPoW bytes / the chain maximum. Nothing was broadcast. "
                    + "Use Wallet → Consolidate to combine fewer coins, wait for confirmation, then retry. "
                    + "A coin-count limit does not guarantee that proofs and signatures fit.";
        return "Node rejected txnpost" + (error.isEmpty() ? "." : ": " + error);
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

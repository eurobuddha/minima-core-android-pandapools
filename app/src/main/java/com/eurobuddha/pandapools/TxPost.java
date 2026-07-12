package com.eurobuddha.pandapools;

import org.json.JSONObject;

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

    public static void checkThenPost(NodeApi node, String txid, List<String> cmdsThroughBasics, Done done) {
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

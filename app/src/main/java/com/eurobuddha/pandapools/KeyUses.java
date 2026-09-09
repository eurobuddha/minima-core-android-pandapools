package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads and advances a key's one-time-signature use counter.
 *
 * WHY THIS EXISTS. Minima signatures are stateful: each key is a 64-ary, 3-level Winternitz tree and
 * every signature must consume the NEXT leaf. Signing one leaf twice, over two different messages, leaks
 * that leaf's private key.
 *
 * A pool's owner key ($OPK) is minted with {@code newaddress}, so a seed-only re-sync does not bring it
 * back — {@code initDefaultKeys} rebuilds only the 64 default keys. {@link OwnerKeyRecovery} re-mints it
 * by re-issuing {@code newaddress} (keys derive as {@code hash(seed, index)}, so the same seed reproduces
 * the same keys in order) — but the node inserts EVERY new key at {@code uses = 0}:
 *
 * <pre>Wallet.createNewKey:  SQL_CREATE_PUBLIC_KEY.setInt(3, treekey.getUses());   // a fresh TreeKey → 0</pre>
 *
 * The regenerated key is byte-identical to the one that already signed, so without this class the next
 * owner action would sign a leaf that was already spent on a different transaction. The node's own
 * restore margins don't help: {@code restore.java} (+256) and {@code vault.java} (=100) both run BEFORE
 * the owner key is re-minted, and only touch keys that exist at that moment.
 *
 * HOW IT ADVANCES. There is no command to set a key's counter — {@code keys} offers only
 * {@code list / genkey / checkkeys / new / createallkeys}, and {@code updateAllKeyUses} rewrites all 64
 * default keys at once. And the private key can't be fetched to sign locally: {@code KeyRow.toJSON()}
 * has {@code privatekey} commented out.
 *
 * So we advance the counter the only way the node exposes — by actually spending leaves. Each
 * {@code sign} call runs {@code Wallet.signData}, which increments and persists {@code uses} by one. The
 * leaves burned here are precisely the ones we are skipping over, so nothing of value is lost.
 *
 * This works on ANY node. It needs no forked build and no new command.
 */
public final class KeyUses {

    /** Data signed while burning a leaf. Constant and meaningless — the signature is discarded; only the
     *  counter increment matters. Deliberately NOT a transaction id, so it can never be a valid spend. */
    private static final String BURN_DATA = "0x00";

    /** Re-read the node's real counter every this many burns instead of trusting our own arithmetic. A
     *  concurrent signature from elsewhere would otherwise make us overshoot or undershoot silently. */
    private static final int RECHECK_EVERY = 25;

    /** Stop rather than spin: a target this far above the current count means something is wrong with the
     *  backup, not with the key. Well beyond any real pool's lifetime usage (~2/day of keep-fresh). */
    private static final int MAX_BURN = 20_000;

    public interface UsesCb { void onUses(int uses); void onError(String message); }
    public interface AdvanceCb { void onProgress(int done, int target); void onDone(int finalUses); void onError(String message); }

    private KeyUses() {}

    /** Current use count for one public key, straight from the node. */
    public static void read(NodeApi node, final String publickey, final UsesCb cb) {
        if (publickey == null || publickey.isEmpty()) { cb.onError("no public key"); return; }
        node.cmd("keys action:list publickey:" + publickey, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Integer u = extractUses(j, publickey);
                if (u == null) cb.onError("the node doesn't hold that key");
                else cb.onUses(u);
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    /**
     * Advance {@code publickey} until its counter is at least {@code target}, by burning leaves.
     *
     * NEVER LOWERS — a key already at or above target is left untouched and reports success. That
     * invariant is the whole point: a counter that can go backwards is a key-leak primitive.
     *
     * Fails loudly rather than silently falling short. A caller must treat {@code onError} as "do not
     * sign with this key", because signing below target is exactly the bug being fixed.
     */
    public static void advanceTo(final NodeApi node, final String publickey, final int target, final AdvanceCb cb) {
        if (publickey == null || publickey.isEmpty()) { cb.onError("no public key"); return; }
        if (target <= 0) { cb.onDone(0); return; }
        read(node, publickey, new UsesCb() {
            @Override public void onUses(int uses) {
                if (uses >= target) { cb.onDone(uses); return; }        // already past it — nothing to do
                if (target - uses > MAX_BURN) {
                    cb.onError("that backup asks for " + (target - uses) + " more key uses, which looks wrong — "
                            + "not touching the key");
                    return;
                }
                burn(node, publickey, target, uses, cb);
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    private static void burn(final NodeApi node, final String publickey, final int target,
                             final int uses, final AdvanceCb cb) {
        if (uses >= target) { cb.onDone(uses); return; }
        cb.onProgress(uses, target);
        node.cmd("sign publickey:" + publickey + " data:" + BURN_DATA, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { cb.onError("the node refused to sign — key not advanced"); return; }
                int next = uses + 1;
                // Trust the node over our own count periodically: another app on this node may also be
                // signing, and the counter is shared.
                if (next % RECHECK_EVERY == 0) {
                    read(node, publickey, new UsesCb() {
                        @Override public void onUses(int real) { burn(node, publickey, target, Math.max(real, next), cb); }
                        @Override public void onError(String m) { burn(node, publickey, target, next, cb); }
                    });
                } else {
                    burn(node, publickey, target, next, cb);
                }
            }
            @Override public void onError(String m) { cb.onError(m); }
        });
    }

    /** Pull one key's `uses` out of a `keys action:list` reply. The response nests the rows under
     *  `response.keys`; a publickey-filtered call returns just the one. */
    static Integer extractUses(JSONObject j, String publickey) {
        if (!TxPost.truthy(j, "status") || publickey == null) return null;
        Object resp = j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr == null) return null;
        String want = publickey.toLowerCase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null) continue;
            if (want.equals(k.optString("publickey", "").toLowerCase()) && k.has("uses")) {
                try {
                    int uses = new java.math.BigDecimal(k.get("uses").toString()).intValueExact();
                    return uses >= 0 && uses <= 262144 ? uses : null;
                } catch (Exception invalid) { return null; }
            }
        }
        return null;
    }

    /**
     * The count an owner key should be restored to.
     *
     * <pre>
     *   target = usesAtBackup + refreshes since the backup + slack
     * </pre>
     *
     * Every term is measured or derived, none invented. The gap term is the keep-fresh cadence: the
     * owner key signs once per pool refresh, and refreshes happen when reserves age past
     * {@link PoolRefresher#REFRESH_BLOCKS}, so elapsed blocks ÷ that is how many could have occurred.
     *
     * {@code slack} covers the non-refresh owner actions (deposits, migrates, collects) that could also
     * have signed since the backup. Erring high costs a leaf out of 262,144; erring low leaks the key, so
     * the asymmetry is entirely one way.
     */
    public static int restoreTarget(int usesAtBackup, int blockAtBackup, int currentBlock, int slack) {
        int gap = 0;
        if (currentBlock > blockAtBackup && blockAtBackup > 0) {
            int blocks = currentBlock - blockAtBackup;
            gap = (blocks + PoolRefresher.REFRESH_BLOCKS - 1) / PoolRefresher.REFRESH_BLOCKS;   // round up
        }
        return Math.max(0, usesAtBackup) + gap + Math.max(0, slack);
    }
}

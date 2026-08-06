package com.eurobuddha.pandapools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owner-key recovery. A pool's owner key ($OPK) is minted with {@code newaddress} — i.e. a key at index
 * ≥ 64 with a sequential {@code modifier}. A SEED-ONLY restore regenerates ONLY the 64 default keys
 * ({@code vault.java} → {@code initDefaultKeys(64)}), so it does NOT bring $OPK back — and without it the
 * node can neither close its own open pools nor spend withdrawn funds sitting at $OADR (both need
 * {@code SIGNEDBY($OPK)} / the $OADR key).
 *
 * But every Minima key is derived deterministically as {@code privseed = hash(seed, keyIndex)} with a
 * sequential index ({@code Wallet.createNewKey}). So re-issuing {@code newaddress} on the SAME seed
 * reproduces the exact historical keys IN ORDER. To recover a missing owner key we simply create new
 * addresses until the wanted pubkey reappears. This is a no-op on a node that already holds the keys (the
 * normal case: the {@code keys} check finds them all and returns immediately).
 *
 * THE HUNT IS BUDGETED — see {@link HuntBudget}. An $OPK minted under a DIFFERENT seed (a backup restored
 * from another node, a recipe carried across devices) can never be re-derived here, and every minted key
 * is a permanent wallet row. So each (seed, opk) pair gets a lifetime budget of
 * {@link HuntBudget#MAX_NEW_KEYS} mints, persisted per mint in {@link HuntLedger}; once it's spent the
 * pair is reported UNREACHABLE — "this key belongs to a different seed" — and never hunted again on this
 * seed. When the key's own derivation index is known (kidx, stamped into recipes/backups since backup
 * v3), the hunt is exact and a foreign seed is PROVEN with zero mints.
 *
 * SERIAL, like {@link TxPost}: only one hunt is ever in flight, so overlapping callers (a restore and a
 * Collect) can't interleave mints and double-charge the ledger. Queued callers re-run their own
 * {@code keys} check when their turn comes. A stall watchdog frees the gate if a callback is ever
 * dropped (NodeApi discards callbacks once its Activity is finishing), so it cannot wedge.
 */
public final class OwnerKeyRecovery {

    public interface Cb {
        /** @param regenerated how many wanted keys were re-minted (0 = node already had them all)
         *  @param unreachable wanted opks (lowercase) this seed provably or budget-exhaustedly cannot
         *                     produce — "created under a different seed"; never null */
        void done(int regenerated, List<String> unreachable);
    }

    private OwnerKeyRecovery() {}

    // ---- the hunt gate (mirrors TxPost's signing gate) ---------------------------------------------

    /** Everything runs on the main thread ({@link NodeApi} funnels every callback back to it), so the
     *  queue needs no locking. */
    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static boolean running = false;
    private static Runnable watchdog = null;

    /** Reset on every hunt step. NodeApi's read timeout is 30s, so more than this much SILENCE means the
     *  callback was dropped (dead Activity), never that the node is merely slow. */
    private static final long STALL_MS = 60_000;

    /** Lazily resolved so the queue logic works without a Looper (JVM tests). Off-device android.jar's
     *  stub THROWS rather than returning null, so catch broadly. */
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

    private static void submit(Runnable job) {
        QUEUE.add(job);
        if (!running) startNext();
    }

    private static void startNext() {
        clearWatchdog();
        Runnable next = QUEUE.poll();
        if (next == null) { running = false; return; }
        running = true;
        touch();
        next.run();
    }

    /** Re-arm the stall watchdog — called on every step so a long, healthy hunt never trips it. */
    private static void touch() {
        Handler h = main();
        if (h == null) return;
        if (watchdog != null) h.removeCallbacks(watchdog);
        watchdog = () -> { watchdog = null; startNext(); };   // a dropped callback must not wedge the gate
        h.postDelayed(watchdog, STALL_MS);
    }

    private static void clearWatchdog() {
        if (watchdog != null) { Handler h = main(); if (h != null) h.removeCallbacks(watchdog); watchdog = null; }
    }

    /** Frees the gate first (like TxPost), then reports — so a callback that itself calls ensure()
     *  simply queues behind whatever was already waiting. */
    private static void finish(Cb cb, int regenerated, List<String> unreachable) {
        startNext();
        cb.done(regenerated, unreachable);
    }

    // ---- entry points ------------------------------------------------------------------------------

    /** Ensure the node holds every owner pubkey in {@code wantedOpks}, regenerating missing ones via
     *  ordered {@code newaddress} within the {@link HuntBudget} rules. kidx (each key's derivation index,
     *  when a local recipe knows it) is read from {@link OwnPoolStore}. */
    public static void ensure(Context ctx, NodeApi node, List<String> wantedOpks, Cb cb) {
        ensure(ctx, node, wantedOpks, kidxFromStore(ctx), cb);
    }

    /** As above with an explicit kidx map (lowercase opk → derivation index ≥ 0). */
    public static void ensure(final Context ctx, final NodeApi node, final List<String> wantedOpks,
                              final Map<String, Integer> kidxByOpk, final Cb cb) {
        final Set<String> wanted = new LinkedHashSet<>();
        if (wantedOpks != null) for (String o : wantedOpks) if (o != null && !o.isEmpty()) wanted.add(o.toLowerCase());
        if (wanted.isEmpty()) { cb.done(0, new ArrayList<>()); return; }
        submit(() -> begin(ctx, node, wanted, kidxByOpk, cb));
    }

    /** The kidx map (lowercase opk → derivation index) every local recipe collectively knows. */
    static Map<String, Integer> kidxFromStore(Context ctx) {
        Map<String, Integer> m = new HashMap<>();
        if (ctx == null) return m;
        for (Pool p : OwnPoolStore.all(ctx)) {
            if (p.opk == null || p.opk.isEmpty() || p.kidx < 0) continue;
            String k = p.opk.toLowerCase();
            Integer prev = m.get(k);
            // duplicates (migrate shares one opk) should agree; if they don't, the LARGER index only
            // delays a foreign-proof — the smaller could fake one, which must never happen
            if (prev == null || p.kidx > prev) m.put(k, p.kidx);
        }
        return m;
    }

    // ---- the hunt ------------------------------------------------------------------------------------

    private static void begin(final Context ctx, final NodeApi node, final Set<String> wanted,
                              final Map<String, Integer> kidxByOpk, final Cb cb) {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                // A status:false stub arrives via onResult, not onError. Without a readable key list we
                // don't know what's held — prove nothing, mint nothing. The rows check matters as much
                // as the status check: a malformed status:true reply would make every wanted key "look
                // missing" and could falsely brand a HELD kidx key as another seed's.
                if (!j.optBoolean("status", false) || HuntBudget.rows(j) == null) { finish(cb, 0, new ArrayList<>()); return; }
                wanted.removeAll(pubkeys(j));
                if (wanted.isEmpty()) { finish(cb, 0, new ArrayList<>()); return; }   // node holds every owner key — no-op

                final String fp = HuntBudget.fingerprint(j);
                final int total = HuntBudget.totalOf(j);

                // Partition: counting = still inside its budget (drives the hunt and is charged per
                // mint); watch = budget spent, matched for FREE if a mint happens to produce it;
                // unreachable = reported as another seed's key. A kidx opk the wallet is already past
                // is proven foreign right here — zero mints.
                final Set<String> counting = new LinkedHashSet<>();
                final Set<String> watch = new LinkedHashSet<>();
                final LinkedHashSet<String> unreachable = new LinkedHashSet<>();
                final Map<String, Integer> kidx = new HashMap<>();   // usable exact targets only
                for (String opk : wanted) {
                    Integer kiBoxed = kidxByOpk == null ? null : kidxByOpk.get(opk);
                    int ki = kiBoxed == null ? -1 : kiBoxed;
                    int spent = HuntLedger.get(ctx, fp, opk);
                    switch (HuntBudget.disposition(ki, total, spent)) {
                        case HuntBudget.FOREIGN_PROVEN:
                            HuntLedger.put(ctx, fp, opk, Math.max(spent, HuntBudget.MAX_NEW_KEYS));
                            unreachable.add(opk);   // proven — not even watched; it cannot appear
                            break;
                        case HuntBudget.WATCH:
                            unreachable.add(opk); watch.add(opk);
                            break;
                        default:
                            counting.add(opk);
                            if (HuntBudget.kidxUsable(ki, total)) kidx.put(opk, ki);
                    }
                }
                if (counting.isEmpty()) { finish(cb, 0, new ArrayList<>(unreachable)); return; }
                new Hunt(ctx, node, cb, fp, counting, watch, kidx, unreachable, total).step();
            }
            @Override public void onError(String m) { finish(cb, 0, new ArrayList<>()); }
        });
    }

    /** One bounded hunt run. State lives here so the recursive steps stay readable. */
    private static final class Hunt {
        private final Context ctx; private final NodeApi node; private final Cb cb;
        private final String fp;
        private final Set<String> counting;               // charged per mint; drives the loop
        private final Set<String> watch;                  // budget spent; matched for free, never charged
        private final Map<String, Integer> kidx;          // exact derivation targets (subset of counting)
        private final LinkedHashSet<String> unreachable;  // reported "different seed"
        private final Map<String, Integer> charges = new HashMap<>();   // this run's in-memory charge counts
        private int total;                                // wallet key count = the NEXT mint's index
        private int mints = 0;                            // keys actually minted by THIS run
        private int regenerated = 0;
        private boolean done = false;

        Hunt(Context ctx, NodeApi node, Cb cb, String fp, Set<String> counting, Set<String> watch,
             Map<String, Integer> kidx, LinkedHashSet<String> unreachable, int total) {
            this.ctx = ctx; this.node = node; this.cb = cb; this.fp = fp;
            this.counting = counting; this.watch = watch; this.kidx = kidx;
            this.unreachable = unreachable; this.total = total;
        }

        void step() {
            // The per-run cap is a hard backstop, NOT the budget: termination normally comes from the
            // per-key counts, but those must not be the only thing standing between us and unbounded
            // minting (HuntLedger deliberately no-ops on a null Context). No single run may ever
            // out-mint the deepest legitimate exact target.
            if (counting.isEmpty() || mints >= HuntBudget.MAX_KIDX_MINT) { end(); return; }
            touch();
            node.cmd("newaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { minted(j); }
                // Charges are already persisted per mint, so a resumed hunt keeps only its remainder.
                // Nothing is PROVEN about the leftovers, so they are not reported unreachable.
                @Override public void onError(String m) { end(); }
            });
        }

        private void minted(JSONObject j) {
            JSONObject r = j.optJSONObject("response");
            String pk = r != null ? r.optString("publickey", "").toLowerCase() : "";
            // A FAILED mint ({"status":false} — locked vault, denied write permission, node error)
            // arrives via onResult, not onError. It created NO key, so it must not charge the ledger:
            // charging here would burn a legitimate key's whole budget on 256 failed calls and then
            // falsely brand it another seed's. Stop instead — charges so far are already persisted.
            if (!j.optBoolean("status", false) || pk.isEmpty()) { end(); return; }
            // the reply's `total` is the count AFTER creation → the minted key's index is total-1;
            // fall back to our own running count on nodes that don't report it
            int replyTotal = r != null ? r.optInt("total", -1) : -1;
            int newIdx = replyTotal > 0 ? replyTotal - 1 : Math.max(total, 0);
            total = newIdx + 1;
            mints++;

            if (counting.remove(pk) || watch.remove(pk)) {
                regenerated++;
                unreachable.remove(pk);
                HuntLedger.clear(ctx, fp, pk);   // found — its count must not linger
                rememberKidx(ctx, pk, newIdx);   // free exactness if this key is ever wiped again
            }

            for (Iterator<String> it = counting.iterator(); it.hasNext(); ) {
                String opk = it.next();
                // In-memory count first, ledger as the durable mirror — the run's TERMINATION must never
                // depend on the ledger actually persisting (it deliberately no-ops without a Context).
                Integer c = charges.get(opk);
                int spent = (c != null ? c : HuntLedger.get(ctx, fp, opk)) + 1;   // this mint "passed by" every still-wanted key
                charges.put(opk, spent);
                HuntLedger.put(ctx, fp, opk, spent);
                switch (HuntBudget.afterCharge(kidx.get(opk), newIdx, spent)) {
                    case HuntBudget.FOREIGN_PAST_KIDX:
                        // walked past its exact index without a match — foreign seed, never hunt again
                        HuntLedger.put(ctx, fp, opk, Math.max(spent, HuntBudget.MAX_NEW_KEYS));
                        unreachable.add(opk);
                        it.remove();
                        break;
                    case HuntBudget.EXHAUSTED:
                        unreachable.add(opk);
                        it.remove();
                        break;
                    default:   // KEEP
                }
            }
            step();
        }

        private void end() {
            if (done) return;
            done = true;
            finish(cb, regenerated, new ArrayList<>(unreachable));
        }
    }

    /** A found key's derivation index is knowledge worth keeping: stamp it into every recipe using this
     *  opk, so a hunt after a FUTURE wipe is exact instead of blind. Larger wins (a smaller index could
     *  fake a foreign-proof; a larger one only delays it). */
    private static void rememberKidx(Context c, String opk, int kidx) {
        if (c == null || opk == null || opk.isEmpty() || kidx < 0) return;
        for (Pool p : OwnPoolStore.all(c)) {
            if (p.opk != null && p.opk.equalsIgnoreCase(opk) && p.kidx < kidx) {
                p.kidx = kidx;
                OwnPoolStore.record(c, p);
            }
        }
    }

    private static Set<String> pubkeys(JSONObject j) {
        Set<String> set = new HashSet<>();
        JSONArray arr = HuntBudget.rows(j);
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) set.add(pk.toLowerCase()); }
        }
        return set;
    }
}

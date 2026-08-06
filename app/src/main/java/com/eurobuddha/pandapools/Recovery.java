package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layers 3 + 4 of pool recovery: a portable BACKUP of the owner's pools (recipe params + a fresh
 * {@code coinexport} of each reserve coin, i.e. coin + MMR proof) that can be RESTORED onto any node —
 * even a brand-new light node where the coins aged out and the beacon pruned — plus an archive-node
 * "does this pool still exist on-chain" CHECK.
 *
 * This class only issues node commands that add KNOWLEDGE of coins ({@code coinexport}/{@code coinimport}/
 * {@code newscript}) or read an archive ({@code archive addresscheck}). It never builds a spend — every
 * withdrawal still goes through the covenant's {@code SIGNEDBY($OPK)} owner branch. All callbacks land on
 * the main thread (via {@link NodeApi}).
 */
public class Recovery {

    /** Bumped if the backup wire format ever changes.
     *  v2 adds {@code opkuses} + {@code atblock} per pool — the owner key's one-time-signature counter and
     *  the height it was read at. Without them a restore has no way to know where the key left off and
     *  resumes at leaf 0, re-signing leaves the pre-restore node already spent (see {@link KeyUses}).
     *  v3 adds {@code kidx} per pool — the owner key's derivation index ({@code modifier}), so a restore's
     *  key hunt is exact and a backup restored onto the WRONG seed is proven foreign with zero minted
     *  keys (see {@link HuntBudget}). */
    static final int BACKUP_VERSION = 3;

    public interface BackupCb { void onBackup(String json); void onError(String msg); }
    public interface RestoreCb { void onProgress(String line); void onDone(int restored, int total); }

    private final NodeApi node;

    public Recovery(NodeApi node) { this.node = node; }

    // ================================================================= BACKUP

    /**
     * Assemble a backup for every pool in {@link OwnPoolStore}: always the recipe (params + covenant
     * script), plus — for pools currently funded/tracked (passed in with live coinids) — a fresh
     * {@code coinexport} of both reserve coins so a fresh node can re-import them directly.
     */
    public void backup(Context ctx, List<Pool> fundedMine, BackupCb cb) {
        backup(ctx, fundedMine, 0, cb);
    }

    /** @param chainBlock the current tip, stamped alongside each owner key's use count so a later restore
     *                    can work out how many keep-fresh signatures could have happened since. */
    public void backup(Context ctx, List<Pool> fundedMine, final int chainBlock, BackupCb cb) {
        final List<Pool> recipes = OwnPoolStore.all(ctx);
        if (recipes.isEmpty()) { cb.onError("No pools to back up yet — create a pool first."); return; }

        final Map<String, Pool> funded = new HashMap<>();
        for (Pool p : fundedMine) if (p != null && p.address != null) funded.put(p.address.toLowerCase(), p);

        final JSONArray out = new JSONArray();
        final AtomicInteger pending = new AtomicInteger(recipes.size());
        for (Pool r : recipes) {
            final JSONObject e = baseEntry(r);
            out.put(e);
            final Pool f = funded.get(r.address == null ? "" : r.address.toLowerCase());
            final Runnable afterUses = () -> {
                if (f != null && notEmpty(f.coinidM) && notEmpty(f.coinidT)) {
                    exportPair(f, e, () -> { if (pending.decrementAndGet() == 0) finishBackup(out, cb); });
                } else {
                    if (pending.decrementAndGet() == 0) finishBackup(out, cb);
                }
            };
            recordUses(ctx, r, e, chainBlock, afterUses);
        }
    }

    /**
     * Stamp this pool's owner-key use count into the backup entry.
     *
     * This is the number that makes a restore safe: it is what the key had actually spent at backup time,
     * measured from the node rather than guessed. Best-effort — a key the node no longer holds (already
     * restored elsewhere) simply gets no count, and the restore falls back to asking the user.
     */
    private void recordUses(final Context ctx, final Pool r, final JSONObject e,
                            final int chainBlock, final Runnable done) {
        if (r.opk == null || r.opk.isEmpty()) { done.run(); return; }
        node.cmd("keys action:list publickey:" + r.opk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                try {
                    Integer uses = KeyUses.extractUses(j, r.opk);
                    if (uses != null) { e.put("opkuses", uses); if (chainBlock > 0) e.put("atblock", chainBlock); }
                    // the same row carries the key's derivation index — the node's answer beats the recipe's
                    int kidx = HuntBudget.modifierOf(j, r.opk);
                    if (kidx >= 0) {
                        e.put("kidx", kidx);
                        // Backfill the local recipe too: a pre-v3 pool only reveals its index while the
                        // node still HOLDS the key — this backup is exactly that moment. With it stored,
                        // a later hunt on this device is exact instead of blind.
                        if (r.kidx != kidx) { r.kidx = kidx; OwnPoolStore.record(ctx, r); }
                    }
                } catch (Exception ignore) {}
                done.run();
            }
            @Override public void onError(String m) { done.run(); }
        });
    }

    private void exportPair(final Pool f, final JSONObject e, final Runnable done) {
        node.cmd("coinexport coinid:" + f.coinidM, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                putData(e, "cm", j);
                node.cmd("coinexport coinid:" + f.coinidT, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) { putData(e, "ct", j2); done.run(); }
                    @Override public void onError(String m) { done.run(); }   // best-effort; recipe still backs up
                });
            }
            @Override public void onError(String m) { done.run(); }
        });
    }

    private static void putData(JSONObject e, String key, JSONObject exportResp) {
        try {
            JSONObject r = exportResp.optJSONObject("response");
            String data = r != null ? r.optString("data", "") : "";
            if (!data.isEmpty()) e.put(key, data);
        } catch (Exception ignore) {}
    }

    private void finishBackup(JSONArray pools, BackupCb cb) {
        try {
            JSONObject root = new JSONObject();
            root.put("pandapools_backup", BACKUP_VERSION);
            root.put("pools", pools);
            cb.onBackup(root.toString(2));
        } catch (Exception e) { cb.onError("Could not assemble the backup."); }
    }

    private static JSONObject baseEntry(Pool r) {
        JSONObject e = new JSONObject();
        try {
            e.put("addr", nz(r.address));
            e.put("mx", nz(r.mxaddress));
            e.put("opk", nz(r.opk));
            e.put("oadr", nz(r.oadr));
            e.put("tok", nz(r.tok));
            e.put("dec", r.tokDecimals);
            e.put("kmin", nz(r.kmin));
            e.put("script", nz(r.covenantScript));
            if (r.kidx >= 0) e.put("kidx", r.kidx);   // recipe's copy; recordUses overwrites with the node's
        } catch (Exception ignore) {}
        return e;
    }

    // ================================================================= RESTORE

    /**
     * Restore every pool in a backup onto THIS node: re-track the covenant ({@code newscript trackall} —
     * always, so discovery finds it), then best-effort {@code coinimport} each saved reserve coin (so it's
     * immediately spendable even if aged-out), and persist the recipe locally. Per-pool best-effort: a
     * failed coin import still leaves the pool re-tracked + recorded (it recovers via normal discovery /
     * archive once the node has the coins). Reports progress per pool.
     */
    public void restore(Context ctx, String json, RestoreCb cb) {
        restore(ctx, json, 0, cb);
    }

    /** @param chainBlock current tip — used with each entry's stamped height to work out how many
     *                    keep-fresh signatures could have happened since the backup was taken. */
    public void restore(Context ctx, String json, final int chainBlock, RestoreCb cb) {
        final JSONArray pools;
        try {
            JSONObject root = new JSONObject(json);
            pools = root.optJSONArray("pools");
            if (pools == null || pools.length() == 0) { cb.onProgress("No pools in that backup file."); cb.onDone(0, 0); return; }
        } catch (Exception e) { cb.onProgress("That doesn't look like a PandaPools backup."); cb.onDone(0, 0); return; }

        final int total = pools.length();
        // Owner keys ($OPK) are newaddress keys a seed-only restore doesn't bring back — regenerate them so
        // restored pools are actually closeable/collectable, not just re-tracked. Gather them up front.
        final List<String> opks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            JSONObject e = pools.optJSONObject(i);
            if (e != null) { String o = e.optString("opk", ""); if (!o.isEmpty()) opks.add(o); }
        }
        final AtomicInteger pending = new AtomicInteger(total);
        final AtomicInteger okCount = new AtomicInteger(0);
        for (int i = 0; i < total; i++) {
            final JSONObject e = pools.optJSONObject(i);
            restoreOne(ctx, e, cb, () -> { okCount.incrementAndGet(); }, () -> {
                if (pending.decrementAndGet() == 0) {
                    // Recipes (incl. kidx) are already recorded by restoreOne — but a degenerate entry
                    // (no covenant script → no recipe) can still carry opk + kidx, so merge the backup's
                    // own indexes on top of the store's. Larger wins, same rule as kidxFromStore.
                    final Map<String, Integer> kidx = OwnerKeyRecovery.kidxFromStore(ctx);
                    for (int k = 0; k < pools.length(); k++) {
                        JSONObject en = pools.optJSONObject(k);
                        if (en == null) continue;
                        String o = en.optString("opk", "").toLowerCase();
                        int ki = en.optInt("kidx", -1);
                        if (o.isEmpty() || ki < 0) continue;
                        Integer prev = kidx.get(o);
                        if (prev == null || prev < ki) kidx.put(o, ki);
                    }
                    OwnerKeyRecovery.ensure(ctx, node, opks, kidx, (regenerated, unreachable) -> {
                        if (regenerated > 0) cb.onProgress("Regenerated " + regenerated
                                + " owner key" + (regenerated == 1 ? "" : "s") + " so your pools are spendable.");
                        if (!unreachable.isEmpty()) cb.onProgress("! " + unreachable.size() + " owner key"
                                + (unreachable.size() == 1 ? " was" : "s were") + " created under a DIFFERENT seed — "
                                + "this node cannot sign for those pools. Restore them on the device/seed that created them.");
                        // A regenerated key comes back at uses=0. Wind it forward to where it actually left
                        // off BEFORE anything can sign with it — that ordering is the entire fix.
                        advanceAll(pools, 0, chainBlock, new HashSet<>(unreachable), cb, () -> cb.onDone(okCount.get(), total));
                    });
                }
            });
        }
    }

    /**
     * Walk the backup entries, winding each owner key forward to the count it had reached.
     *
     * Sequential on purpose: each pass burns real signatures, and firing them concurrently is exactly the
     * pattern that caused the original reuse. Best-effort per pool — one key that can't be advanced must
     * not abandon the others — but every failure is REPORTED, never swallowed, because a key left short
     * is a key that must not be signed with.
     */
    private void advanceAll(final JSONArray pools, final int i, final int chainBlock,
                            final Set<String> unreachable, final RestoreCb cb, final Runnable done) {
        if (i >= pools.length()) { done.run(); return; }
        final JSONObject e = pools.optJSONObject(i);
        final Runnable next = () -> advanceAll(pools, i + 1, chainBlock, unreachable, cb, done);
        if (e == null) { next.run(); return; }

        final String opk = e.optString("opk", "");
        final String label = Util.shorten(e.optString("addr", "pool"));
        if (!opk.isEmpty() && unreachable.contains(opk.toLowerCase())) {
            // Not this seed's key — there is nothing to advance and never will be. The honest message
            // here, not the misleading "could not restore the owner key's usage".
            cb.onProgress("! " + label + ": this pool's owner key belongs to a different seed — "
                    + "this node cannot sign for it.");
            next.run();
            return;
        }
        if (opk.isEmpty() || !e.has("opkuses")) {
            // A pre-v2 backup carries no count. Say so rather than quietly resuming at leaf 0 — the user
            // needs to know this key may re-sign, and can set it from their own resync keyuses value.
            if (!opk.isEmpty()) cb.onProgress("! " + label + ": this backup predates key-use tracking, so the "
                    + "owner key's signature count is unknown. Re-back-up now, and avoid reusing this pool.");
            next.run();
            return;
        }

        final int target = KeyUses.restoreTarget(e.optInt("opkuses", 0), e.optInt("atblock", 0), chainBlock, USES_SLACK);
        KeyUses.advanceTo(node, opk, target, new KeyUses.AdvanceCb() {
            @Override public void onProgress(int at, int tgt) {
                if (at % 50 == 0) cb.onProgress("Restoring " + label + " key usage… " + at + "/" + tgt);
            }
            @Override public void onDone(int finalUses) {
                cb.onProgress("Recovered " + label + " owner key (usage restored to " + finalUses + ").");
                next.run();
            }
            @Override public void onError(String m) {
                cb.onProgress("! " + label + ": could not restore the owner key's usage (" + m + "). "
                        + "Do not use this pool until that succeeds — signing now could expose the key.");
                next.run();
            }
        });
    }

    /** Owner actions other than keep-fresh (deposit / migrate / close / collect) that could also have
     *  signed between the backup and the restore. A leaf costs nothing against 262,144; falling short
     *  leaks the key — so this errs high deliberately. */
    private static final int USES_SLACK = 50;

    private void restoreOne(final Context ctx, final JSONObject e, final RestoreCb cb,
                            final Runnable onOk, final Runnable done) {
        if (e == null) { done.run(); return; }
        final String script = e.optString("script", "");
        final String addr = e.optString("addr", "");
        final String label = addr.isEmpty() ? "pool" : Util.shorten(addr);
        if (script.isEmpty()) { cb.onProgress("Skipped " + label + " (no covenant in backup)."); done.run(); return; }

        // persist the recipe first so the app knows this pool regardless of what the node does
        OwnPoolStore.record(ctx, poolFrom(e));

        node.cmd("newscript trackall:true script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { importCoins(); }
            @Override public void onError(String m) { importCoins(); }   // recipe kept; discovery/archive can still find it
            private void importCoins() {
                importCoin(e.optString("cm", ""), () ->
                    importCoin(e.optString("ct", ""), () -> {
                        cb.onProgress("Recovered " + label + ".");
                        onOk.run(); done.run();
                    }));
            }
        });
    }

    private void importCoin(String data, final Runnable next) {
        if (data == null || data.isEmpty()) { next.run(); return; }
        node.cmd("coinimport track:true data:" + data, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { next.run(); }
            @Override public void onError(String m) { next.run(); }   // best-effort (proof may be stale / coin spent)
        });
    }

    // ---- rebuild a Pool (recipe) from a backup entry ----
    static Pool poolFrom(JSONObject e) {
        Pool p = new Pool();
        p.address = e.optString("addr", "");
        p.mxaddress = e.optString("mx", "");
        p.opk = e.optString("opk", "");
        p.oadr = e.optString("oadr", "");
        p.tok = e.optString("tok", "");
        p.tokDecimals = e.optInt("dec", 8);
        p.kmin = e.optString("kmin", "");
        p.covenantScript = e.optString("script", "");
        p.kidx = e.optInt("kidx", -1);
        return p;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
}

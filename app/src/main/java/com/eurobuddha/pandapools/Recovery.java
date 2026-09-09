package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
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
     *  v3 adds {@code kidx}, the owner key's derivation index. These fields describe backup-time state;
     *  they cannot establish the latest signing state and are never used to regenerate signing keys. */
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

    /** @param chainBlock current tip, retained as backup provenance, never a signing-counter estimate. */
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
                PoolRefresher.readLiveReserves(node, r, ok -> {
                    if (ok) exportPair(r, e, () -> { if (pending.decrementAndGet() == 0) finishBackup(out, cb); });
                    else {
                        backupWarning(e, "Reserve proofs unavailable on this node. Recipe saved; archived reserves need a fresh MegaMMR lookup.");
                        if (pending.decrementAndGet() == 0) finishBackup(out, cb);
                    }
                });
            };
            recordUses(ctx, r, e, chainBlock, afterUses);
        }
    }

    /**
     * Stamp this pool's owner-key use count into the backup entry.
     *
     * Records what the key had spent at backup time. Later signatures are not represented here;
     * safe key restoration requires the matching, current MinimaCore wallet backup.
     */
    private void recordUses(final Context ctx, final Pool r, final JSONObject e,
                            final int chainBlock, final Runnable done) {
        if (r.opk == null || r.opk.isEmpty()) { done.run(); return; }
        node.cmd("keys action:list publickey:" + r.opk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                try {
                    Integer uses = KeyUses.extractUses(j, r.opk);
                    if (uses != null) {
                        e.put("opkuses", Math.max(uses, r.minimumOwnerUses));
                        if (chainBlock > 0) e.put("atblock", chainBlock);
                        if (uses < r.minimumOwnerUses) backupWarning(e, "Owner-key counter is below a previously recorded count. Restore current wallet signing state before spending.");
                        r.minimumOwnerUses = Math.max(uses, r.minimumOwnerUses);
                    }
                    // the same row carries the key's derivation index — the node's answer beats the recipe's
                    int kidx = HuntBudget.modifierOf(j, r.opk);
                    if (kidx >= 0) {
                        e.put("kidx", kidx);
                        // Backfill the local recipe too: a pre-v3 pool only reveals its index while the
                        // node still HOLDS the key — this backup is exactly that moment. With it stored,
                        // backup retains the node's derivation metadata.
                        r.kidx = kidx;
                    }
                    if (!OwnPoolStore.recordDurably(ctx, r)) backupWarning(e, "Could not save the observed owner-key count on this device.");
                } catch (Exception ignore) {}
                done.run();
            }
            @Override public void onError(String m) { done.run(); }
        });
    }

    private void exportPair(final Pool f, final JSONObject e, final Runnable done) {
        final String expectedM = f.coinidM, expectedT = f.coinidT;
        final Runnable finish = () -> PoolRefresher.readLiveReserves(node, f, ok -> {
            if (!ok || !expectedM.equals(f.coinidM) || !expectedT.equals(f.coinidT)) {
                e.remove("cm"); e.remove("ct");
                backupWarning(e, "The pool moved during backup. Recipe saved; reserve proofs omitted. Back up again for current proofs.");
            } else if (!e.has("cm") || !e.has("ct")) {
                backupWarning(e, "One or both reserve exports failed. Recipe saved; recovery may require a MegaMMR archive.");
            }
            done.run();
        });
        node.cmd("coinexport coinid:" + f.coinidM, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                putData(e, "cm", expectedM, "0x00", j);
                node.cmd("coinexport coinid:" + expectedT, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) { putData(e, "ct", expectedT, f.tok, j2); finish.run(); }
                    @Override public void onError(String m) { finish.run(); }
                });
            }
            @Override public void onError(String m) { finish.run(); }
        });
    }

    static void putData(JSONObject e, String key, String coinid, String token, JSONObject exportResp) {
        try {
            if (!TxPost.truthy(exportResp, "status")) return;
            JSONObject r = exportResp.optJSONObject("response");
            String data = r != null ? r.optString("data", "") : "";
            JSONObject cp = r == null ? null : r.optJSONObject("coinproof");
            JSONObject coin = cp == null ? null : cp.optJSONObject("coin");
            if (!ReserveRecovery.validProofData(data) || !PoolRefresher.reserveCoin(poolFrom(e), coin)
                    || !coinid.equalsIgnoreCase(coin.optString("coinid"))
                    || !token.equalsIgnoreCase(coin.optString("tokenid"))) return;
            e.put(key, data);
            e.put(key + "_created", coin.optInt("created", 0));
            JSONObject proof = cp.optJSONObject("proof");
            if (proof != null) e.put(key + "_proofblock", proof.optInt("blocktime", 0));
        } catch (Exception ignore) {}
    }

    private static void backupWarning(JSONObject e, String warning) {
        try { e.put("proof_warning", warning); } catch (Exception ignore) { }
    }

    private void finishBackup(JSONArray pools, BackupCb cb) {
        try {
            JSONObject root = new JSONObject();
            root.put("pandapools_backup", BACKUP_VERSION);
            root.put("pools", pools);
            root.put("recovery_notice", "Recipes are durable; embedded coin proofs expire. Restore looks up live coins and can fetch fresh proofs from a configured MegaMMR archive. Keep the matching current MinimaCore wallet backup for keys and signing state.");
            cb.onBackup(root.toString(2));
        } catch (Exception e) { cb.onError("Could not assemble the backup."); }
    }

    static JSONObject baseEntry(Pool r) {
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
            if (r.minimumOwnerUses >= 0) e.put("opkuses", r.minimumOwnerUses);
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

    /** @param chainBlock retained for caller compatibility; never used to guess signing state. */
    public void restore(Context ctx, String json, final int chainBlock, RestoreCb cb) {
        final JSONArray pools;
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("pandapools_backup", 0);
            if (version < 1 || version > BACKUP_VERSION) throw new IllegalArgumentException("Unsupported backup version");
            pools = root.optJSONArray("pools");
            if (pools != null && pools.length() > 500) throw new IllegalArgumentException("Too many pool recipes");
            if (pools == null || pools.length() == 0) { cb.onProgress("No pools in that backup file."); cb.onDone(0, 0); return; }
        } catch (Exception e) { cb.onProgress("That doesn't look like a PandaPools backup."); cb.onDone(0, 0); return; }

        final int total = pools.length();
        // Check ownership after import. Never regenerate keys or guess their historic signing counts.
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
                        if (!unreachable.isEmpty()) cb.onProgress("! " + unreachable.size()
                                + " owner key(s) need signing-state verification. Restore the latest matching MinimaCore wallet backup "
                                + "and stop other nodes using that wallet, then confirm its signing state in Recovery. Pool recipes do not restore signing state.");
                        cb.onDone(okCount.get(), total);
                    });
                }
            });
        }
    }

    private void restoreOne(final Context ctx, final JSONObject e, final RestoreCb cb,
                            final Runnable onOk, final Runnable done) {
        if (e == null) { done.run(); return; }
        final String script = e.optString("script", "");
        final String addr = e.optString("addr", "");
        final String label = addr.isEmpty() ? "pool" : addr;
        if (script.isEmpty()) { cb.onProgress("Skipped " + label + " (no covenant in backup)."); done.run(); return; }

        if (!validRecipe(e)) { cb.onProgress("Skipped " + label + " (invalid recipe fields)."); done.run(); return; }
        // Derive the supplied script's address before persisting or importing anything.
        node.cmd("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                JSONObject sc = r == null ? null : r.optJSONObject("script");
                if (!TxPost.truthy(reply, "status") || !TxPost.truthy(r, "parseok") || sc == null
                        || !addr.equalsIgnoreCase(sc.optString("address", ""))) {
                    cb.onProgress("Skipped " + label + " (covenant address did not verify)."); done.run(); return;
                }
                registerRecipe(ctx, e, cb, onOk, done);
            }
            public void onError(String error) { cb.onProgress("Could not verify " + label + ". Nothing imported."); done.run(); }
        });
    }

    private void registerRecipe(Context ctx, JSONObject e, RestoreCb cb, Runnable onOk, Runnable done) {
        String label = e.optString("addr", "pool");
        String script = e.optString("script", "");
        Pool restored = poolFrom(e);
        restored.signingStateUnverified = true;
        if (!OwnPoolStore.recordDurably(ctx, restored)) {
            cb.onProgress("Could not save " + label + ". Nothing imported."); done.run(); return;
        }
        for (Pool saved : OwnPoolStore.all(ctx)) if (restored.address.equalsIgnoreCase(saved.address)) {
            restored.coinidM = saved.coinidM; restored.coinidT = saved.coinidT;
            restored.minimumOwnerUses = saved.minimumOwnerUses; break;
        }
        node.cmd("newscript trackall:true script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (TxPost.truthy(j, "status")) importCoins();
                else { cb.onProgress("! Could not track " + label + "; recipe saved."); done.run(); }
            }
            @Override public void onError(String m) { cb.onProgress("! Could not track " + label + "; recipe saved."); done.run(); }   // recipe kept; discovery/archive can still find it
            private void importCoins() {
                new ReserveRecovery(node::cmd, ArchiveNode.configured(ctx), restored, e, (verified, detail) -> {
                    cb.onProgress(detail);
                    if (verified && OwnPoolStore.recordDurably(ctx, restored)) onOk.run();
                    else if (verified) cb.onProgress("Could not save the verified reserve IDs. Recovery needs attention.");
                    done.run();
                }).start();
            }
        });
    }

    static boolean validRecipe(JSONObject e) {
        if (e == null) return false;
        for (String field : new String[]{"addr", "opk", "oadr", "tok"})
            if (!FundingCoins.hex(e.optString(field, ""))) return false;
        // Optional snapshot proofs may be stale or corrupt. Validate them at coincheck time; a
        // broken snapshot must not prevent recovery by the verified covenant address.
        int dec;
        try { dec = new BigDecimal(e.get("dec").toString()).intValueExact(); }
        catch (Exception invalid) { return false; }
        if (e.has("opkuses")) {
            try {
                int uses = new BigDecimal(e.get("opkuses").toString()).intValueExact();
                if (uses < 0 || uses > 262144) return false;
            } catch (Exception invalid) { return false; }
        }
        return dec >= 0 && dec <= 44 && PoolCovenant.matches(e.optString("script", ""),
                e.optString("opk", ""), e.optString("oadr", ""), e.optString("tok", ""), e.optString("kmin", ""));
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
        p.minimumOwnerUses = e.optInt("opkuses", -1);
        return p;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
}

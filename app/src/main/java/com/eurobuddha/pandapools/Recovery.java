package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Bumped if the backup wire format ever changes. */
    static final int BACKUP_VERSION = 1;

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
        final List<Pool> recipes = OwnPoolStore.all(ctx);
        if (recipes.isEmpty()) { cb.onError("No pools to back up yet — create a pool first."); return; }

        final Map<String, Pool> funded = new HashMap<>();
        for (Pool p : fundedMine) if (p != null && p.address != null) funded.put(p.address.toLowerCase(), p);

        final JSONArray out = new JSONArray();
        final AtomicInteger pending = new AtomicInteger(recipes.size());
        for (Pool r : recipes) {
            final JSONObject e = baseEntry(r);
            out.put(e);
            Pool f = funded.get(r.address == null ? "" : r.address.toLowerCase());
            if (f != null && notEmpty(f.coinidM) && notEmpty(f.coinidT)) {
                exportPair(f, e, () -> { if (pending.decrementAndGet() == 0) finishBackup(out, cb); });
            } else {
                if (pending.decrementAndGet() == 0) finishBackup(out, cb);
            }
        }
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
        final JSONArray pools;
        try {
            JSONObject root = new JSONObject(json);
            pools = root.optJSONArray("pools");
            if (pools == null || pools.length() == 0) { cb.onProgress("No pools in that backup file."); cb.onDone(0, 0); return; }
        } catch (Exception e) { cb.onProgress("That doesn't look like a PandaPools backup."); cb.onDone(0, 0); return; }

        final int total = pools.length();
        final AtomicInteger pending = new AtomicInteger(total);
        final AtomicInteger okCount = new AtomicInteger(0);
        for (int i = 0; i < total; i++) {
            final JSONObject e = pools.optJSONObject(i);
            restoreOne(ctx, e, cb, () -> { okCount.incrementAndGet(); }, () -> {
                if (pending.decrementAndGet() == 0) cb.onDone(okCount.get(), total);
            });
        }
    }

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
        return p;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
}

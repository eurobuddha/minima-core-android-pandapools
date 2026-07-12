package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 5 (network discoverability): keep a live announce beacon in the recent sentinel window for each
 * owned pool, so STRANGERS' fresh nodes keep rediscovering it after the original beacon pruned.
 *
 * It re-announces ONLY a pool whose beacon has actually FADED from the recent window — so beacons never
 * pile up (the beacon is unspendable dust that can't be replaced, only re-posted when gone). Each
 * re-announce is one small owner-wallet send (see {@link PoolManager#reannounce}); it spends no covenant
 * coin and adds no spend authority.
 *
 * HARD GUARD: the sentinel scan here NEVER uses {@code megammr:true} — that query (pulling the whole
 * accumulated beacon pile) was the 0.8.10 crash, not the re-announce itself. Recent unpruned window only.
 */
public class ReAnnouncer {

    public interface Listener { void onAnnounced(int posted); }

    // Keys re-announced during THIS process — so a config-change recreate (e.g. theme toggle) can't
    // double-post a beacon before the first one confirms into the sentinel window. Cleared on process death,
    // by which point the beacon has long confirmed.
    private static final Set<String> ANNOUNCED = java.util.Collections.synchronizedSet(new HashSet<>());

    private final NodeApi node;
    private final PoolManager mgr;

    public ReAnnouncer(NodeApi node) {
        this.node = node;
        this.mgr = new PoolManager(node);
    }

    /**
     * For each currently-funded owned pool whose beacon is absent from the recent sentinel window, post one
     * fresh beacon. {@code fundedOwn} are pools with live reserves + full params (from discovery).
     */
    public void refreshFaded(final List<Pool> fundedOwn, final Listener cb) {
        if (fundedOwn == null || fundedOwn.isEmpty()) { cb.onAnnounced(0); return; }
        // recent-window sentinel scan — NEVER megammr:true (see class note)
        node.cmd("coins simplestate:true order:desc address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { announce(present(j), fundedOwn, cb); }
            @Override public void onError(String m) { cb.onAnnounced(0); }   // can't tell what's live → do nothing
        });
    }

    /**
     * Background entry (no UI state): build the funded owned-pool list straight from {@link OwnPoolStore}
     * — a reserve scan per recipe, no ownership/keys lookup needed (a stored recipe IS ours) — then
     * re-announce the faded ones. Skips recipes with no live reserves (a closed pool must not be announced).
     */
    public void refreshFadedFromStore(final Context ctx, final Listener cb) {
        final List<Pool> recipes = OwnPoolStore.all(ctx);
        if (recipes.isEmpty()) { cb.onAnnounced(0); return; }
        final List<Pool> funded = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger pending = new AtomicInteger(recipes.size());
        for (final Pool r : recipes) {
            node.cmd("coins address:" + r.address, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { fillReserves(r, j); if (r.funded()) funded.add(r); tick(); }
                @Override public void onError(String m) { tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) refreshFaded(new ArrayList<>(funded), cb); }
            });
        }
    }

    /** Largest coin per leg at the covenant address = the true reserve (dust can't masquerade). Mirrors
     *  PoolBook.fund so a store-built pool matches a discovery-built one. */
    private static void fillReserves(Pool p, JSONObject j) {
        Object resp = j.opt("response");
        JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null || c.optBoolean("spent", false)) continue;
            String tid = c.optString("tokenid", "");
            if ("0x00".equals(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                if (p.reserveM == null || amt.compareTo(p.reserveM) > 0) { p.reserveM = amt; p.coinidM = c.optString("coinid", ""); }
            } else if (p.tok != null && p.tok.equalsIgnoreCase(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                if (p.reserveT == null || amt.compareTo(p.reserveT) > 0) { p.reserveT = amt; p.coinidT = c.optString("coinid", ""); }
            }
        }
    }

    /** The set of pool keys (opk|tok|kmin) that currently have a live beacon in the recent window. */
    private static Set<String> present(JSONObject j) {
        Set<String> keys = new HashSet<>();
        Object resp = j.opt("response");
        JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null) continue;
            // full beacon identity incl. oadr (port 3) — a forged beacon with your public opk/tok/kmin but a
            // bogus oadr must NOT be mistaken for your live beacon (it would suppress your re-announce and
            // point fresh nodes at a wrong-address covenant). Strict parity with PoolBook.gatherRegistry.
            String tok = PoolBook.state(c, 2), oadr = PoolBook.state(c, 3), opk = PoolBook.state(c, 4), kmin = PoolBook.state(c, 5);
            if (tok != null && oadr != null && opk != null && kmin != null) keys.add(key(opk, oadr, tok, kmin));
        }
        return keys;
    }

    private void announce(Set<String> present, List<Pool> fundedOwn, final Listener cb) {
        List<Pool> faded = new ArrayList<>();
        for (Pool p : fundedOwn) {
            if (p == null || p.opk == null || p.oadr == null || p.tok == null || p.kmin == null) continue;
            String k = key(p.opk, p.oadr, p.tok, p.kmin);
            if (present.contains(k) || ANNOUNCED.contains(k)) continue;   // live beacon OR already re-posted this process
            faded.add(p);
        }
        if (faded.isEmpty()) { cb.onAnnounced(0); return; }

        final AtomicInteger pending = new AtomicInteger(faded.size());
        final AtomicInteger posted = new AtomicInteger(0);
        for (Pool p : faded) {
            final String k = key(p.opk, p.oadr, p.tok, p.kmin);
            mgr.reannounce(p, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) { ANNOUNCED.add(k); posted.incrementAndGet(); tick(); }
                @Override public void onFailed(String message) { tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) cb.onAnnounced(posted.get()); }
            });
        }
    }

    private static String key(String opk, String oadr, String tok, String kmin) {
        return (opk + "|" + oadr + "|" + tok + "|" + kmin).toLowerCase();
    }
}

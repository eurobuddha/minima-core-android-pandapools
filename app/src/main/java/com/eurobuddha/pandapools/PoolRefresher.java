package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KEEP-FRESH scheduler. A pool's reserve coins fall out of the ~1700-block cascade into the megammr-only archive
 * after ~a day; a light (non-megammr) node then can't see them, so cross-device discovery drops the pool. This
 * periodically RECREATES its own pools' reserves in place (owner grow-in-place, same amounts, {@link PoolManager#refresh})
 * BEFORE they age out — new young coin IDs → back in the cascade → every light node sees them again. Fully
 * decentralized: each owner keeps its own pools alive; no server, no megammr.
 *
 * OWNER-ONLY (unlike the node-wide beacon re-announce): refresh spends the covenant coins and needs {@code $OPK},
 * so it only ever touches pools this node owns. It re-announces the beacon too (a refresh posts one), so it
 * subsumes re-announce for own pools; the {@link ReAnnouncer} still gossips OTHER pools' faded beacons.
 */
public class PoolRefresher {

    public interface Listener { void onRefreshed(int posted); }

    /** Refresh a reserve coin once it is older than this, so its reserves stay YOUNG enough to sit inside every
     *  node's unpruned window — not just the ~1700-block cascade edge. Lowered 1200 → 900 because a freshly-resynced
     *  node's window can be well under 1200 blocks (~1059 observed), so at 1200 a pool's reserves aged out on that
     *  node BEFORE keep-fresh ever refreshed them → it vanished from that node (cross-device divergence). 900 keeps
     *  reserves young enough for a short window with margin, still leaves a big gap to the cascade edge, and a fresh
     *  refresh also re-posts the beacon so a maintained pool's beacon stays well inside REANNOUNCE_DEPTH too. */
    public static final int REFRESH_BLOCKS = 900;
    /** Per-run cap so a node with many pools doesn't fire a burst of PoW at once; later runs cover the rest. */
    private static final int MAX_PER_RUN = 8;
    /** A refresh takes ~a block or two to confirm; until then the reserves still read as old, so a second trigger
     *  (foreground + background overlapping) would refresh the SAME pool again — one wins at consensus, the other
     *  wastes PoW. Skip a pool refreshed within this window. Far shorter than the next legitimate refresh (~16h), so
     *  it never blocks a real re-refresh. Process-scoped (cleared on death, by when the refresh has long confirmed). */
    private static final long RECENT_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();

    private final NodeApi node;
    private final PoolManager mgr;

    public PoolRefresher(NodeApi node) { this.node = node; this.mgr = new PoolManager(node); }

    /**
     * Foreground: given the funded pools this node OWNS (already scanned, so {@link Pool#reserveBlock} is set) and
     * the current chain tip, recreate the ones whose reserves are aging past {@link #REFRESH_BLOCKS}. An unknown
     * reserve age ({@code reserveBlock<=0}, e.g. a coin JSON without {@code created}) is treated as aging — better
     * to refresh than to let a pool silently fall out of the cascade.
     */
    public void refreshAging(List<Pool> ownFunded, int chainBlock, Listener cb) {
        long now = System.currentTimeMillis();
        List<Pool> aging = new ArrayList<>();
        if (ownFunded != null)
            for (Pool p : ownFunded)
                if (p != null && p.funded() && p.address != null && !refreshedRecently(p.address, now)
                        && (p.reserveBlock <= 0 || p.reserveAge(chainBlock) > REFRESH_BLOCKS)) aging.add(p);
        post(aging, cb);
    }

    private static boolean refreshedRecently(String address, long now) {
        Long t = RECENT.get(address.toLowerCase());
        return t != null && (now - t) < RECENT_TTL_MS;
    }

    /**
     * Background (no UI): read the tip, then build the owned funded-pool list straight from {@link OwnPoolStore}
     * — a per-covenant {@code coins address:<pool>} reserve scan per recipe (~1.6 KB each, safe on any node) —
     * and refresh the aging ones. We deliberately do NOT run a full {@link PoolBook#scan} here: keep-fresh only
     * ever touches OUR pools (it spends covenant coins + needs $OPK), and the sentinel scan is the IPC-overflow
     * risk this release fixes. A stored recipe IS ours, so no sentinel/scripts/keys lookup is needed.
     */
    public void refreshAgingFromScan(final Context ctx, final Listener cb) {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject bj) {
                final int tip = parseBlock(bj);
                final List<Pool> recipes = OwnPoolStore.all(ctx);
                if (tip <= 0 || recipes.isEmpty()) { cb.onRefreshed(0); return; }
                final List<Pool> mine = Collections.synchronizedList(new ArrayList<>());
                final AtomicInteger pending = new AtomicInteger(recipes.size());
                for (final Pool r : recipes) {
                    if (r == null || r.address == null) {
                        if (pending.decrementAndGet() == 0) refreshAging(new ArrayList<>(mine), tip, cb);
                        continue;
                    }
                    node.cmd("coins address:" + r.address, new NodeApi.Cb() {
                        @Override public void onResult(JSONObject j) { fillReserves(r, j); if (r.funded()) mine.add(r); tick(); }
                        @Override public void onError(String m) { tick(); }
                        private void tick() { if (pending.decrementAndGet() == 0) refreshAging(new ArrayList<>(mine), tip, cb); }
                    });
                }
            }
            @Override public void onError(String m) { cb.onRefreshed(0); }
        });
    }

    /** Largest coin per leg at the covenant address = the true reserve (a forged dust coin can't masquerade); also
     *  record the newest kept-coin block so {@link Pool#reserveAge} is right. Mirrors {@link PoolBook}'s fund logic. */
    private static void fillReserves(Pool p, JSONObject j) {
        Object resp = j.opt("response");
        JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
        int mBlk = 0, tBlk = 0;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null || c.optBoolean("spent", false)) continue;
            String tid = c.optString("tokenid", "");
            if ("0x00".equals(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                if (p.reserveM == null || amt.compareTo(p.reserveM) > 0) { p.reserveM = amt; p.coinidM = c.optString("coinid", ""); mBlk = c.optInt("created", 0); }
            } else if (p.tok != null && p.tok.equalsIgnoreCase(tid)) {
                BigDecimal amt = new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                if (p.reserveT == null || amt.compareTo(p.reserveT) > 0) { p.reserveT = amt; p.coinidT = c.optString("coinid", ""); tBlk = c.optInt("created", 0); }
            }
        }
        p.reserveBlock = Math.max(mBlk, tBlk);
    }

    private void post(List<Pool> aging, final Listener cb) {
        if (aging.isEmpty()) { cb.onRefreshed(0); return; }
        if (aging.size() > MAX_PER_RUN) { Collections.shuffle(aging); aging = new ArrayList<>(aging.subList(0, MAX_PER_RUN)); }
        final AtomicInteger pending = new AtomicInteger(aging.size());
        final AtomicInteger posted = new AtomicInteger(0);
        for (final Pool p : aging) {
            RECENT.put(p.address.toLowerCase(), System.currentTimeMillis());   // reserve the slot before posting (blocks a concurrent double-fire)
            mgr.refresh(p, new PoolManager.Result() {
                @Override public void onPosted(String txpowid) { posted.incrementAndGet(); tick(); }
                @Override public void onFailed(String message) { RECENT.remove(p.address.toLowerCase()); tick(); }   // failed → allow a retry next cycle
                private void tick() { if (pending.decrementAndGet() == 0) cb.onRefreshed(posted.get()); }
            });
        }
    }

    private static int parseBlock(JSONObject j) {
        try { return Integer.parseInt(j.getJSONObject("response").getString("block")); }
        catch (Exception e) { return 0; }
    }
}

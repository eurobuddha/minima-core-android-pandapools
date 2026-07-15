package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Refresh a reserve coin once it is older than this, so it never reaches the ~1700-block cascade edge. The
     *  margin (1700 - REFRESH_BLOCKS ≈ 500) must exceed the worst-case gap between refresh checks (a Doze-delayed
     *  ~4h background run ≈ 288 blocks) so a fully-idle owner phone still catches it in time. */
    public static final int REFRESH_BLOCKS = 1200;
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
     * Background (no UI): read the tip, discover funded pools via a full scan ({@link PoolBook#scan} populates
     * {@code reserveBlock}), keep only the ones this node OWNS ({@link OwnPoolStore}), and refresh the aging ones.
     */
    public void refreshAgingFromScan(final Context ctx, final Listener cb) {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject bj) {
                final int tip = parseBlock(bj);
                final Set<String> own = ownAddresses(ctx);
                if (tip <= 0 || own.isEmpty()) { cb.onRefreshed(0); return; }
                new PoolBook(node).scan(new PoolBook.Listener() {
                    @Override public void onPools(List<Pool> all) {
                        List<Pool> mine = new ArrayList<>();
                        for (Pool p : all)
                            if (p != null && p.address != null && own.contains(p.address.toLowerCase())) mine.add(p);
                        refreshAging(mine, tip, cb);
                    }
                    @Override public void onError(String m) { cb.onRefreshed(0); }
                });
            }
            @Override public void onError(String m) { cb.onRefreshed(0); }
        });
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

    private static Set<String> ownAddresses(Context ctx) {
        Set<String> s = new HashSet<>();
        for (Pool r : OwnPoolStore.all(ctx)) if (r != null && r.address != null) s.add(r.address.toLowerCase());
        return s;
    }

    private static int parseBlock(JSONObject j) {
        try { return Integer.parseInt(j.getJSONObject("response").getString("block")); }
        catch (Exception e) { return 0; }
    }
}

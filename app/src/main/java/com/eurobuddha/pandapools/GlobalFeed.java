package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A LIVE, tracking-independent feed of ALL swaps happening across the discovered pools — including other
 * people's trades. It works by snapshotting each pool's reserves each scan and detecting the constant-product
 * SWAP signature: exactly one reserve up and the other down. (A create shows a brand-new address; a balanced
 * add moves BOTH reserves the same way; a close removes the address — so only a genuine swap flips the two
 * legs in opposite directions.) Lets an LP watch volume + fees accrue even from swaps they didn't make.
 *
 * Captures swaps from when the app first sees a pool onward (no deep historical backfill — the node's
 * `history` is relevant-only). Purely local, capped, best-effort.
 */
public final class GlobalFeed {

    private static final String PREFS = "pandapools_global";
    private static final String KEY_SNAP = "snap";     // address -> "m|t"
    private static final String KEY_FEED = "feed";     // recent events
    private static final int MAX = 100;
    private static final MathContext MC = new MathContext(20, RoundingMode.DOWN);
    // false until the first ingest of THIS process — the first scan reseeds silently so we never diff live
    // reserves against a stale pre-restart snapshot and emit a phantom aggregate "swap". Resets on restart.
    private static boolean primed = false;

    private GlobalFeed() {}

    public static final class Event {
        public final String pool, tokenLabel;
        public final boolean minimaIn;   // true = someone sold MINIMA (MINIMA in, token out)
        public final BigDecimal minimaAmt, tokenAmt, price;
        public final long ts;
        Event(String pool, String tokenLabel, boolean minimaIn, BigDecimal m, BigDecimal t, BigDecimal price, long ts) {
            this.pool=pool; this.tokenLabel=tokenLabel; this.minimaIn=minimaIn;
            this.minimaAmt=m; this.tokenAmt=t; this.price=price; this.ts=ts;
        }
    }

    /** Ingest a fresh scan: detect swaps vs the last snapshot, append events, update the snapshot. */
    public static synchronized void ingest(Context c, List<Pool> pools) {
        if (c == null || pools == null) return;
        boolean firstScanThisSession = !primed;
        primed = true;
        Map<String, String> snap = loadSnap(c);
        List<Event> feed = loadFeed(c);
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();

        for (Pool p : pools) {
            if (p == null || p.address == null || !p.funded()) continue;
            String addr = p.address.toLowerCase();
            seen.add(addr);
            String prev = snap.get(addr);
            snap.put(addr, p.reserveM.toPlainString() + "|" + p.reserveT.toPlainString());
            // brand-new pool, OR the first scan of this session (reseed vs the stale snapshot): seed only, don't emit
            if (prev == null || firstScanThisSession) continue;
            int bar = prev.indexOf('|');
            if (bar < 0) continue;
            BigDecimal oldM, oldT;
            try { oldM = new BigDecimal(prev.substring(0, bar)); oldT = new BigDecimal(prev.substring(bar + 1)); }
            catch (Exception e) { continue; }
            int cm = p.reserveM.compareTo(oldM), ct = p.reserveT.compareTo(oldT);
            if (cm > 0 && ct < 0) {                         // MINIMA in, token out
                BigDecimal dm = p.reserveM.subtract(oldM), dt = oldT.subtract(p.reserveT);
                feed.add(0, new Event(addr, p.tokenLabel(), true, dm, dt, price(dt, dm), now));
            } else if (cm < 0 && ct > 0) {                  // token in, MINIMA out
                BigDecimal dm = oldM.subtract(p.reserveM), dt = p.reserveT.subtract(oldT);
                feed.add(0, new Event(addr, p.tokenLabel(), false, dm, dt, price(dt, dm), now));
            }
            // both-up (deposit/migrate) or both-down: not a swap → ignore
        }
        // drop snapshots for pools that vanished (closed) so a later re-created address counts as new
        snap.keySet().retainAll(seen);
        while (feed.size() > MAX) feed.remove(feed.size() - 1);
        saveSnap(c, snap);
        saveFeed(c, feed);
    }

    private static BigDecimal price(BigDecimal tok, BigDecimal minima) {
        if (minima == null || minima.signum() == 0) return BigDecimal.ZERO;
        return tok.divide(minima, MC);
    }

    public static synchronized List<Event> list(Context c) { return loadFeed(c); }

    // ---- persistence ----

    private static Map<String, String> loadSnap(Context c) {
        Map<String, String> m = new HashMap<>();
        String s = prefs(c).getString(KEY_SNAP, null);
        if (s == null) return m;
        try {
            JSONObject o = new JSONObject(s);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.optString(k)); }
        } catch (Exception ignore) {}
        return m;
    }

    private static void saveSnap(Context c, Map<String, String> m) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            prefs(c).edit().putString(KEY_SNAP, o.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static List<Event> loadFeed(Context c) {
        List<Event> out = new ArrayList<>();
        String s = prefs(c).getString(KEY_FEED, null);
        if (s == null) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Event(o.optString("p"), o.optString("tl"), o.optBoolean("mi"),
                        bd(o.optString("m")), bd(o.optString("t")), bd(o.optString("pr")), o.optLong("ts")));
            }
        } catch (Exception ignore) {}
        return out;
    }

    private static void saveFeed(Context c, List<Event> feed) {
        try {
            JSONArray arr = new JSONArray();
            for (Event e : feed) {
                JSONObject o = new JSONObject();
                o.put("p", e.pool); o.put("tl", e.tokenLabel); o.put("mi", e.minimaIn);
                o.put("m", e.minimaAmt.toPlainString()); o.put("t", e.tokenAmt.toPlainString());
                o.put("pr", e.price.toPlainString()); o.put("ts", e.ts);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_FEED, arr.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}

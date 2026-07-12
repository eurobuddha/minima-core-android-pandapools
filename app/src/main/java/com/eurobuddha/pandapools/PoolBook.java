package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers pools from the shared registry (trust-nothing): scan the PANDAPOOLS sentinel for announce
 * coins, re-derive each pool's covenant script + address from its announce params (never trusting the
 * announce blindly), then scan that derived address for the two reserve coins. A forged announce can at
 * worst point at a real-but-bad pool, which the reserve/price filters drop.
 *
 * Mirrors the SwapOrderBook coinnotify-add + bounded-depth scan pattern, with the Ed25519 order-verify
 * replaced by address re-derivation (the covenant address IS the proof).
 */
public class PoolBook {

    public interface Listener {
        void onPools(List<Pool> pools);
        void onError(String msg);
    }

    private final NodeApi node;
    private final Context ctx;   // for LpStore (this device's OWN pool addresses)

    // Bounded scan window for the SHARED sentinel (Source 2). The sentinel accumulates a beacon on EVERY
    // re-anchor of EVERY pool, so an unbounded `coins address:SENTINEL` returns the node's entire tracked
    // beacon history (~317KB on an established node) and overflows the IPC Binder → crash. Beacons re-announce
    // every 18h (KEEPALIVE_INTERVAL), so ~2000 blocks (≈28h at 50s/block) surfaces every live pool's latest
    // beacon with margin for a slightly-late keep-alive, while excluding the stale history. Own pools are also
    // found locally via Source 1 (LpStore), so this bound never hides a pool this node can re-anchor.
    private static final int SCAN_DEPTH = 256;

    public PoolBook(Context ctx, NodeApi node) { this.ctx = ctx; this.node = node; }

    // Literal extractors for a PandaPools covenant — so a pool can be recovered from the node's TRACKED
    // contract scripts. A contract is spendable, so it is NEVER MMR-pruned (unlike the announce beacon,
    // a dust coin at the made-up sentinel address, which is effectively unspendable and does prune). The
    // covenant is registered with `newscript trackall` at creation, so the creator's pools stay enumerable
    // here FOREVER (good-til-cancelled), independent of the beacon's age.
    private static final Pattern P_OPK  = Pattern.compile("SIGNEDBY\\((0x[0-9A-Fa-f]+)\\)");
    private static final Pattern P_OADR = Pattern.compile("VERIFYOUT\\(@INPUT (0x[0-9A-Fa-f]+) @AMOUNT");
    private static final Pattern P_TOK  = Pattern.compile("GETINTOK\\(s\\) EQ (0x[0-9A-Fa-f]+)");
    private static final Pattern P_KMIN = Pattern.compile("GTE MAX\\(x\\*y ([0-9.]+)\\)");

    // Single-flight discovery. All five tabs call scan() on refresh/new-block; we run ONE shared scan and
    // fan its result out to every caller. The node serves the FIRST `coins address:SENTINEL` cleanly but
    // returns a bloated retained set to CONCURRENT duplicates of it — four parallel scans overflowed the IPC
    // Binder (exactly 1 of 4 came back small, 3 huge); one serialized scan does not.
    private static boolean sScanning = false;
    private static final java.util.List<Listener> sWaiters = new java.util.ArrayList<>();

    /** Scan the registry → discover + fund every pool → callback on the UI thread. Single-flight. */
    public void scan(Listener cb) {
        synchronized (sWaiters) {
            if (cb != null) sWaiters.add(cb);
            if (sScanning) return;   // a scan is already running; this caller is served when it finishes
            sScanning = true;
        }
        final Listener fanout = new Listener() {
            @Override public void onPools(List<Pool> pools) { finishFanout(pools, null); }
            @Override public void onError(String msg) { finishFanout(null, msg); }
        };
        // Un-track the shared sentinel first: a long-tracked address makes the node return its whole retained
        // set for `coins address:` (hundreds of KB) instead of just the recent unpruned beacons.
        node.cmd("coinnotify action:remove address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { gatherOwned(fanout); }
            @Override public void onError(String m) { gatherOwned(fanout); }
        });
    }

    /** Deliver the single-flight result to every queued caller and release the guard. */
    private static void finishFanout(List<Pool> pools, String err) {
        java.util.List<Listener> ws;
        synchronized (sWaiters) {
            ws = new java.util.ArrayList<>(sWaiters);
            sWaiters.clear();
            sScanning = false;
        }
        for (Listener w : ws) {
            if (pools != null) w.onPools(pools); else w.onError(err);
        }
    }

    /** Source 1 (GTC): this node's OWN pools — rebuilt PURELY LOCALLY from LpStore's backfilled covenant params
     *  (opk/oadr/tok/kmin), so it stays visible independent of beacon age with ZERO node calls. We do NOT ask
     *  the node for scripts: the bare {@code scripts} returns EVERY tracked contract (~317KB on an established
     *  node) and even {@code scripts address:<addr>} returns the whole set on a node whose address filter is
     *  broken — either overflows the IPC Binder and crash-loops the app on open. A pool created before params
     *  were backfilled is still found via Source 2 (its live beacon), and derivePools then backfills its params
     *  so Source 1 picks it up next scan. LpStore is exactly the set keep-alive can re-anchor — nothing lost. */
    private void gatherOwned(final Listener cb) {
        final java.util.Set<String> addrs = LpStore.addresses(ctx);
        if (addrs.isEmpty()) { cb.onPools(new ArrayList<>()); return; }
        final List<Pool> pools = new ArrayList<>();
        final AtomicInteger pending = new AtomicInteger(addrs.size());
        for (final String addr : addrs) {
            final String[] p = LpStore.params(ctx, addr);   // {opk,oadr,tok,kmin} for re-anchor, or null (legacy)
            // BOUNDED per-pool read — ~1.6KB even on a MegaMMR node. We already KNOW the address (it is the
            // LpStore key), so no shared-sentinel scan and no runscript re-derivation is needed.
            node.cmd("coins address:" + addr, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Pool pool = new Pool();
                    pool.address = addr;
                    if (p != null) { pool.opk = p[0]; pool.oadr = p[1]; pool.tok = p[2]; pool.kmin = p[3]; }
                    Object resp = j.opt("response");
                    JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                    for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.optJSONObject(i);
                        if (c == null || c.optBoolean("spent", false)) continue;
                        String tid = c.optString("tokenid", "");
                        if ("0x00".equals(tid)) {
                            // largest MINIMA coin = the real reserve (a dust coin must never be mistaken for it)
                            BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                            if (pool.reserveM == null || amt.compareTo(pool.reserveM) > 0) {
                                pool.reserveM = amt; pool.coinidM = c.optString("coinid", "");
                            }
                        } else if (pool.tok == null || pool.tok.isEmpty() || pool.tok.equalsIgnoreCase(tid)) {
                            // a legacy pool (no stored params) adopts its token leg from the reserve coin
                            BigDecimal amt = new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                            if (pool.reserveT == null || amt.compareTo(pool.reserveT) > 0) {
                                pool.reserveT = amt; pool.coinidT = c.optString("coinid", "");
                                pool.tok = tid;
                                pool.tokName = Util.tokenName(c.opt("token"), tid);
                                pool.tokDecimals = Util.tokenDecimals(c.opt("token"));
                            }
                        }
                    }
                    synchronized (pools) { if (pool.funded()) pools.add(pool); }
                    if (pending.decrementAndGet() == 0) gatherRegistry(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) gatherRegistry(pools, cb); }
            });
        }
    }

    private void parseScripts(JSONObject j, Map<String, String[]> params) {
        JSONArray arr = j.optJSONArray("response");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            String sc = s == null ? "" : s.optString("script", "");
            if (!sc.contains("VERIFYOUT(@INPUT @ADDRESS") || !sc.contains("GTE MAX(x*y")) continue;   // a PandaPools covenant
            String opk = group(P_OPK, sc), oadr = group(P_OADR, sc), tok = group(P_TOK, sc), kmin = group(P_KMIN, sc);
            if (opk == null || oadr == null || tok == null || kmin == null) continue;
            // carry the ACTUAL tracked covenant script (any fee) — derivePools runscripts it to (a) confirm it
            // compiles and (b) get its address. A non-parsing script (an old JSONObject.quote '\/' corruption)
            // is filtered out there so a permanently-unspendable pool can't masquerade as live.
            params.putIfAbsent(opk + "|" + tok + "|" + kmin, new String[]{opk, oadr, tok, kmin, sc});
        }
    }

    /** Source 2: the shared registry (announce beacons) — discovers OTHER creators' fresh pools. Scans the
     *  recent chaintree window only. NOT `megammr:true`: on a MegaMMR node that returns the ENTIRE historical
     *  beacon set — a ~290KB reply that overflows the IPC Binder limit and crash-loops the app on open. The
     *  keep-alive service re-announces live pools into the recent window, so a bounded scan still finds every
     *  actively-maintained pool. */
    /** Source 2: discover OTHER creators' pools from the shared registry. {@code megammr:false} forces the
     *  RECENT-chain search only, EXCLUDING the MegaMMR unspent set — where the announce beacons (dust at the
     *  made-up, unspendable sentinel address) accumulate forever and return hundreds of KB that overflow the
     *  IPC Binder on a MegaMMR node. Own pools are already built from LpStore, so an empty/failed scan here
     *  never hides your own pools. */
    private void gatherRegistry(final List<Pool> own, final Listener cb) {
        node.cmd("coins simplestate:true order:desc megammr:false depth:" + SCAN_DEPTH + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Map<String, String[]> params = new LinkedHashMap<>();
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String tok = state(c, 2), oadr = state(c, 3), opk = state(c, 4), kmin = state(c, 5);
                    if (tok == null || oadr == null || opk == null || kmin == null) continue;
                    params.putIfAbsent(opk + "|" + tok + "|" + kmin, new String[]{opk, oadr, tok, kmin});
                }
                if (params.isEmpty()) { cb.onPools(own); return; }
                derivePools(own, new ArrayList<>(params.values()), cb);
            }
            @Override public void onError(String m) { cb.onPools(own); }   // registry unreachable → own pools only
        });
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** For each beacon, re-derive the pool address via runscript, skip ones already shown as OWN pools, then
     *  scan the rest for reserves. */
    private void derivePools(final List<Pool> own, List<String[]> params, final Listener cb) {
        final java.util.Set<String> have = new java.util.HashSet<>();
        for (Pool p : own) if (p.address != null) have.add(p.address.toLowerCase());
        final List<Pool> others = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(params.size());
        for (String[] p : params) {
            final String opk = p[0], oadr = p[1], tok = p[2], kmin = p[3];
            String script;
            try { script = PoolCovenant.script(opk, oadr, tok, kmin); }
            catch (Exception e) { if (pending.decrementAndGet() == 0) fund(own, others, cb); continue; }
            final String fscript = script;
            node.cmd("runscript script:" + jsonStr(script), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    try {
                        JSONObject resp = j.getJSONObject("response");
                        // ONLY surface a covenant that actually compiles. A non-parsing script can never
                        // execute → its coins are permanently unspendable, so it must not appear as a live pool.
                        if (resp.optBoolean("parseok", false)) {
                            JSONObject sc = resp.getJSONObject("script");
                            String addr = sc.getString("address");
                            if (!have.contains(addr.toLowerCase())) {   // not one of our own pools already shown
                                Pool pool = new Pool();
                                pool.opk = opk; pool.oadr = oadr; pool.tok = tok; pool.kmin = kmin;
                                pool.address = addr;
                                pool.mxaddress = sc.optString("mxaddress", "");
                                pool.covenantScript = fscript;   // remember for track-on-discovery in done()
                                synchronized (others) { others.add(pool); }
                            }
                        }
                    } catch (Exception ignore) {}
                    if (pending.decrementAndGet() == 0) fund(own, others, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) fund(own, others, cb); }
            });
        }
    }

    /** Scan each derived pool address for its two reserve coins (recent chaintree window; NOT `megammr:true`
     *  — see gatherRegistry for why the MegaMMR pass overflows the IPC Binder). A discovered pool is
     *  `newscript trackall`-ed in done(), so later scans see it via the tracked-contract source (Source 1). */
    private void fund(final List<Pool> own, final List<Pool> others, final Listener cb) {
        if (others.isEmpty()) { cb.onPools(own); return; }
        AtomicInteger pending = new AtomicInteger(others.size());
        for (Pool pool : others) {
            node.cmd("coins address:" + pool.address, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Object resp = j.opt("response");
                    JSONArray cs = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                    for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.optJSONObject(i);
                        if (c == null || c.optBoolean("spent", false)) continue;
                        // keep the LARGEST coin per leg — the real reserve. If the pool address is polluted
                        // with a dust coin (the forged-dust attack the KMIN floor defends against), the dust
                        // must never be mistaken for the reserve, so the quote is built on the true amounts.
                        String tid = c.optString("tokenid", "");
                        if ("0x00".equals(tid)) {
                            BigDecimal amt = new BigDecimal(c.optString("amount", "0"));
                            if (pool.reserveM == null || amt.compareTo(pool.reserveM) > 0) {
                                pool.reserveM = amt;
                                pool.coinidM = c.optString("coinid", "");
                            }
                        } else if (pool.tok.equalsIgnoreCase(tid)) {
                            BigDecimal amt = new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                            if (pool.reserveT == null || amt.compareTo(pool.reserveT) > 0) {
                                pool.reserveT = amt;
                                pool.coinidT = c.optString("coinid", "");
                                pool.tokName = Util.tokenName(c.opt("token"), tid);
                                pool.tokDecimals = Util.tokenDecimals(c.opt("token"));
                            }
                        }
                    }
                    if (pending.decrementAndGet() == 0) done(own, others, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) done(own, others, cb); }
            });
        }
    }

    private void done(List<Pool> own, List<Pool> others, Listener cb) {
        List<Pool> merged = new ArrayList<>(own);   // own pools (from LpStore) always kept
        for (Pool p : others) {
            if (!p.funded()) continue;
            merged.add(p);
            // Track-on-discovery: permanently track a newly-seen registry pool's contract so it stays
            // GTC-visible + swappable on THIS node forever, like our own pools (the shared beacon can lapse;
            // a tracked contract never prunes). Fire-and-forget + idempotent; the next scan then finds it via
            // the tracked-contract source, so this fires only once per newly-discovered pool.
            if (p.covenantScript != null && !p.covenantScript.isEmpty()) {
                node.cmd("newscript trackall:true script:" + jsonStr(p.covenantScript), new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {}
                    @Override public void onError(String m) {}
                });
                p.covenantScript = null;
            }
        }
        cb.onPools(merged);
    }

    // ---- helpers ----
    private static String state(JSONObject coin, int port) {
        if (coin == null) return null;
        JSONObject sm = coin.optJSONObject("state");
        if (sm != null) { String v = sm.optString(String.valueOf(port), ""); return v.isEmpty() ? null : v; }
        JSONArray sa = coin.optJSONArray("state");
        if (sa != null) for (int i = 0; i < sa.length(); i++) {
            JSONObject e = sa.optJSONObject(i);
            if (e != null && e.optInt("port", -1) == port) {
                String d = e.optString("data", ""); return d.isEmpty() ? null : d;
            }
        }
        return null;
    }

    /** JSON-quote a script for the runscript command param. */
    static String jsonStr(String s) {
        return Util.scriptArg(s);
    }
}

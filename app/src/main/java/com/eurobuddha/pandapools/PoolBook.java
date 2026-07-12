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

    /** Scan the registry → discover + fund every pool → callback on the UI thread. */
    public void scan(Listener cb) {
        // idempotent: ensure the node notifies on sentinel coins, then gather pools from both sources.
        node.cmd("coinnotify action:add address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { gatherOwned(cb); }
            @Override public void onError(String m) { gatherOwned(cb); }
        });
    }

    /** Source 1 (GTC): this node's OWN pools — rebuilt PURELY LOCALLY from LpStore's backfilled covenant params
     *  (opk/oadr/tok/kmin), so it stays visible independent of beacon age with ZERO node calls. We do NOT ask
     *  the node for scripts: the bare {@code scripts} returns EVERY tracked contract (~317KB on an established
     *  node) and even {@code scripts address:<addr>} returns the whole set on a node whose address filter is
     *  broken — either overflows the IPC Binder and crash-loops the app on open. A pool created before params
     *  were backfilled is still found via Source 2 (its live beacon), and derivePools then backfills its params
     *  so Source 1 picks it up next scan. LpStore is exactly the set keep-alive can re-anchor — nothing lost. */
    private void gatherOwned(final Listener cb) {
        final Map<String, String[]> params = new LinkedHashMap<>();   // key opk|tok|kmin -> {opk,oadr,tok,kmin}
        for (String addr : LpStore.addresses(ctx)) {
            String[] p = LpStore.params(ctx, addr);   // null until backfilled → Source 2 covers it meanwhile
            if (p != null) params.putIfAbsent(p[0] + "|" + p[2] + "|" + p[3], p);
        }
        gatherRegistry(params, cb);
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
    private void gatherRegistry(final Map<String, String[]> params, final Listener cb) {
        node.cmd("coins simplestate:true order:desc depth:" + SCAN_DEPTH + " address:" + PoolCovenant.SENTINEL, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                Object resp = j.opt("response");
                JSONArray coins = resp instanceof JSONArray ? (JSONArray) resp : new JSONArray();
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject c = coins.optJSONObject(i);
                    String tok = state(c, 2), oadr = state(c, 3), opk = state(c, 4), kmin = state(c, 5);
                    if (tok == null || oadr == null || opk == null || kmin == null) continue;
                    params.putIfAbsent(opk + "|" + tok + "|" + kmin, new String[]{opk, oadr, tok, kmin});
                }
                finishScan(params, cb);
            }
            @Override public void onError(String m) { finishScan(params, cb); }   // still show owned pools
        });
    }

    private void finishScan(Map<String, String[]> params, Listener cb) {
        if (params.isEmpty()) { cb.onPools(new ArrayList<>()); return; }
        derivePools(new ArrayList<>(params.values()), cb);
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** For each announce, re-derive the pool address via runscript, then scan its reserves. */
    private void derivePools(List<String[]> params, Listener cb) {
        List<Pool> pools = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(params.size());
        for (String[] p : params) {
            final String opk = p[0], oadr = p[1], tok = p[2], kmin = p[3];
            final String tracked = p.length > 4 ? p[4] : null;   // the ACTUAL tracked covenant script (any fee), if known
            String script;
            if (tracked != null && !tracked.isEmpty()) {
                script = tracked;
            } else {
                try { script = PoolCovenant.script(opk, oadr, tok, kmin); }
                catch (Exception e) { if (pending.decrementAndGet() == 0) fund(pools, cb); continue; }
            }
            final String fscript = script;
            node.cmd("runscript script:" + jsonStr(script), new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    try {
                        JSONObject resp = j.getJSONObject("response");
                        // ONLY surface a covenant that actually compiles. A non-parsing script can never
                        // execute → its coins are permanently unspendable, so it must not appear as a live,
                        // closeable or routable pool (it would just make every swap through it fail).
                        if (resp.optBoolean("parseok", false)) {
                            JSONObject sc = resp.getJSONObject("script");
                            Pool pool = new Pool();
                            pool.opk = opk; pool.oadr = oadr; pool.tok = tok; pool.kmin = kmin;
                            pool.address = sc.getString("address");
                            pool.mxaddress = sc.optString("mxaddress", "");
                            // registry-discovered (not already one of our tracked contracts) → remember its
                            // covenant so done() can track it once it's confirmed funded (track-on-discovery).
                            if (tracked == null) pool.covenantScript = fscript;
                            synchronized (pools) { pools.add(pool); }
                            // Self-heal: the first time we see an OWNED pool via ANY source, backfill its covenant
                            // params so Source 1 can rebuild it locally (GTC) on the next scan without a node dump.
                            if (LpStore.get(ctx, pool.address) != null && LpStore.params(ctx, pool.address) == null)
                                LpStore.putParams(ctx, pool.address, opk, oadr, tok, kmin);
                        }
                    } catch (Exception ignore) {}
                    if (pending.decrementAndGet() == 0) fund(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) fund(pools, cb); }
            });
        }
    }

    /** Scan each derived pool address for its two reserve coins (recent chaintree window; NOT `megammr:true`
     *  — see gatherRegistry for why the MegaMMR pass overflows the IPC Binder). A discovered pool is
     *  `newscript trackall`-ed in done(), so later scans see it via the tracked-contract source (Source 1). */
    private void fund(List<Pool> pools, Listener cb) {
        if (pools.isEmpty()) { cb.onPools(pools); return; }
        AtomicInteger pending = new AtomicInteger(pools.size());
        for (Pool pool : pools) {
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
                    if (pending.decrementAndGet() == 0) done(pools, cb);
                }
                @Override public void onError(String m) { if (pending.decrementAndGet() == 0) done(pools, cb); }
            });
        }
    }

    private void done(List<Pool> pools, Listener cb) {
        List<Pool> funded = new ArrayList<>();
        for (Pool p : pools) {
            if (!p.funded()) continue;
            funded.add(p);
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
        cb.onPools(funded);
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

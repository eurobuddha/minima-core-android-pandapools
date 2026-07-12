package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds, signs and posts a constant-product swap against a single {@link Pool}, mirroring the proven
 * mainnet {@code phaseC_swap.py} command-for-command so on-chain behaviour is identical.
 *
 * The pool is a PAIR of coins at the same covenant address: the MINIMA leg and the token leg. The
 * covenant's swap branch keys everything off input parity — MINIMA leg at an EVEN input, token leg at
 * the ODD sibling — and pins each recreated reserve with {@code VERIFYOUT(@INPUT ...)}. So the txn
 * layout is fixed:
 *   input  0 = pool MINIMA coin (even)      output 0 = recreated MINIMA reserve (nx) @ pool addr
 *   input  1 = pool token coin  (odd)       output 1 = recreated token reserve   (ny) @ pool addr
 *   input 2+ = taker funding (plain wallet) output 2 = taker's proceeds; output 3 = taker's change
 * Amounts conserve exactly per token (zero burn), matching the mainnet run. The covenant needs no
 * signature (anyone-can-spend swap branch); {@code txnsign auto} only signs the taker's funding coins.
 */
public class PoolTxn {

    public interface Result { void onPosted(String txpowid); void onFailed(String message); }

    /** Below this a MINIMA change output is dropped (dust not worth a UTXO; MINIMA may be burned). */
    private static final BigDecimal DUST = new BigDecimal("0.000000001");

    /** A funding reservation self-expires after this long (~3–4 blocks) so a swap that posted but never
     *  confirmed (lost a race / the pool moved) can't strand its funding coin as permanently unusable. */
    private static final long INFLIGHT_TTL_MS = 3 * 60 * 1000L;

    private final NodeApi node;
    // funding coins reserved by an in-flight swap (coinid -> reservedAt millis), so a concurrent swap
    // can't reuse them; entries expire (TTL) and are pruned once the coin is actually spent.
    private final Map<String, Long> inflight = new ConcurrentHashMap<>();

    public PoolTxn(NodeApi node) { this.node = node; }

    private boolean isReserved(String coinid) {
        Long t = inflight.get(coinid);
        return t != null && (System.currentTimeMillis() - t) < INFLIGHT_TTL_MS;
    }
    private void reserve(List<Coin> funds) {
        long now = System.currentTimeMillis();
        for (Coin f : funds) inflight.put(f.coinid, now);
    }
    private void release(List<Coin> funds) {
        for (Coin f : funds) inflight.remove(f.coinid);
    }

    /**
     * Swap against one pool. {@code minimaToToken=true}: put in {@code amountIn} MINIMA, receive token.
     * {@code false}: put in {@code amountIn} token, receive MINIMA. The quote is (re)computed here against
     * the pool's scanned reserves; if the reserves have since moved on-chain the txn simply fails the
     * invariant and is rejected — harmless, the caller retries after a rescan.
     */
    /**
     * Execute a routed swap: ONE transaction that spends + recreates every pool in the route (each pool's
     * MINIMA leg at an even input, token leg at the odd sibling, outputs mirrored — the covenant pins each
     * pool by @INPUT arithmetic, so N pools coexist in one tx; proven in the phase-B 3-pool routed swap),
     * funded from the wallet, with the aggregate proceeds paid to the taker and change returned.
     */
    public void swap(final PoolRouter.Route route, final boolean minimaToToken, final Result cb) {
        if (route == null || !route.ok || route.allocs.isEmpty()) { cb.onFailed("no route — trade too small for the pools"); return; }
        final String tok = route.allocs.get(0).pool.tok;
        final String fundTok = minimaToToken ? Util.MINIMA_TOKENID : tok;

        // exclude EVERY pool address for the pair (not just the routed ones) so a same-token pool leg can
        // never be pulled in as funding — belt-and-braces on top of sendable:true. ALSO exclude each
        // routed pool's OWNER payout address (OADR): funding a swap from an owner coin makes txnsign:auto
        // sign with $OPK, which flips the covenant into its owner branch and rejects the (reserve-shrinking)
        // swap. Critical for an LP swapping against their own pool from the same node.
        Set<String> exclude = new HashSet<>();
        for (String addr : route.pairAddresses) if (addr != null) exclude.add(addr.toLowerCase());
        for (PoolRouter.Alloc a : route.allocs) {
            if (a.pool.address != null) exclude.add(a.pool.address.toLowerCase());
            if (a.pool.oadr != null) exclude.add(a.pool.oadr.toLowerCase());
        }

        findFunding(fundTok, route.totalIn, exclude, new FundCb() {
            @Override public void onFunds(List<Coin> funds, BigDecimal sum) {
                reserve(funds);
                ensureTrackedAll(route.allocs, 0, () -> buildRouted(route, minimaToToken, tok, funds, sum, cb));
            }
            @Override public void onNone() {
                cb.onFailed("insufficient " + (minimaToToken ? "MINIMA" : "token") + " in the wallet to fund this swap");
            }
        });
    }

    // ---- build + post ------------------------------------------------------

    private void buildRouted(PoolRouter.Route route, boolean minimaToToken, String tok,
                             List<Coin> funds, BigDecimal sum, Result cb) {
        final String txid = "ppswap_" + tag();
        final String tokArg = " tokenid:" + tok;
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String taddr = j.optJSONObject("response") != null
                        ? j.optJSONObject("response").optString("address", "") : "";
                if (taddr.isEmpty()) { release(funds); cb.onFailed("could not get a payout address"); return; }

                List<String> cmds = new ArrayList<>();
                cmds.add("txncreate id:" + txid);
                // interleaved pool inputs: pool k's MINIMA leg at index 2k (even), token leg at 2k+1 (odd)
                for (PoolRouter.Alloc a : route.allocs) {
                    cmds.add("txninput id:" + txid + " coinid:" + a.pool.coinidM);
                    cmds.add("txninput id:" + txid + " coinid:" + a.pool.coinidT);
                }
                for (Coin f : funds) cmds.add("txninput id:" + txid + " coinid:" + f.coinid);

                // interleaved recreated reserves, index-matched to the pool inputs (covenant VERIFYOUT @INPUT)
                for (PoolRouter.Alloc a : route.allocs) {
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newX) + " address:" + a.pool.address + " storestate:false");
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(a.quote.newY) + " address:" + a.pool.address + tokArg + " storestate:false");
                }

                BigDecimal change = sum.subtract(route.totalIn);
                if (minimaToToken) {
                    // aggregate token proceeds to the taker; MINIMA change back
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(route.totalOut) + " address:" + taddr + tokArg + " storestate:false");
                    if (change.compareTo(DUST) > 0)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + taddr + " storestate:false");
                } else {
                    // aggregate MINIMA proceeds to the taker; token change back (a token can't be burned →
                    // output ANY positive change; it is grain-aligned so never sub-grain)
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(route.totalOut) + " address:" + taddr + " storestate:false");
                    if (change.signum() > 0)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + taddr + tokArg + " storestate:false");
                }

                cmds.add("txnsign id:" + txid + " publickey:auto");   // signs the funding coins; pool legs spend via covenant
                cmds.add("txnbasics id:" + txid);
                TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                    @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
                    @Override public void fail(String message) { release(funds); cb.onFailed(message); }
                });
            }
            @Override public void onError(String m) {
                release(funds);   // release reservation if we can't even get a payout address
                cb.onFailed(m);
            }
        });
    }

    /** Register every pool's covenant on the node before spending (sequential, best-effort), then continue. */
    private void ensureTrackedAll(List<PoolRouter.Alloc> allocs, int i, Runnable then) {
        if (i >= allocs.size()) { then.run(); return; }
        ensureTracked(allocs.get(i).pool, () -> ensureTrackedAll(allocs, i + 1, then));
    }

    /**
     * Register the pool's covenant on the node before spending so {@code txnbasics} can attach the script
     * + MMR proof (the "Script Missing" trap for untracked script addresses). Best-effort: newscript is
     * idempotent, and if it genuinely can't run the later txnbasics surfaces a clear error anyway.
     */
    private void ensureTracked(Pool p, Runnable then) {
        String script = PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin);
        node.cmd("newscript trackall:true script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { then.run(); }
            @Override public void onError(String m) { then.run(); }
        });
    }

    // ---- funding coin selection (port of the limit app's findCoins) --------

    private interface FundCb { void onFunds(List<Coin> funds, BigDecimal sum); void onNone(); }

    private void findFunding(String tokenid, BigDecimal need, Set<String> excludeAddrsLower, FundCb cb) {
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.onNone(); return; }
                List<Coin> avail = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jc = arr.optJSONObject(i);
                    if (jc == null) continue;
                    Coin c = Coin.from(jc);
                    if (c.address != null && excludeAddrsLower.contains(c.address.toLowerCase())) continue; // never a pool coin
                    if (isReserved(c.coinid)) continue;                                          // held by an in-flight swap
                    avail.add(c);
                }
                // prune expired reservations by TTL (token-agnostic — this scan only sees one token's coins,
                // so pruning against `present` would wrongly drop the other side's live reservations)
                long now = System.currentTimeMillis();
                inflight.entrySet().removeIf(e -> now - e.getValue() >= INFLIGHT_TTL_MS);
                if (avail.isEmpty()) { cb.onNone(); return; }
                avail.sort((a, b) -> new BigDecimal(b.amount).compareTo(new BigDecimal(a.amount)));
                List<Coin> sel = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (Coin c : avail) {
                    sel.add(c);
                    sum = sum.add(new BigDecimal(c.amount));
                    if (sum.compareTo(need) >= 0) { cb.onFunds(sel, sum); return; }
                }
                cb.onNone();   // not enough across all available coins
            }
            @Override public void onError(String message) { cb.onNone(); }
        });
    }

    // ---- helpers -----------------------------------------------------------

    /** Plain-decimal string the node accepts (never scientific notation). */
    private static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }
}

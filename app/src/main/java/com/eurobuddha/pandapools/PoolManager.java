package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The liquidity-provider (owner) transactions: CREATE a pool, ADD liquidity (grow-in-place), MIGRATE
 * (reset the KMIN floor at a fresh address), and CLOSE (sweep reserves back to the owner). All owner
 * paths are gated by the covenant's {@code SIGNEDBY($OPK)} branch, so only the node that minted the
 * pool's owner key can manage it. Transaction shapes mirror the proven {@code phaseB.py} lifecycle and
 * {@code phaseC_close.py} exactly.
 *
 * Create is ONE atomic transaction: it funds both reserve coins at the covenant address AND posts the
 * registry announce beacon (a dust coin at the shared sentinel carrying the pool params in state) so the
 * pool is discoverable, with change returned — no coin-lock races between separate sends.
 */
public class PoolManager {

    public interface Result { void onPosted(String txpowid); void onFailed(String message); }
    public interface CreateResult { void onCreated(Pool pool, String txpowid); void onFailed(String message); }
    public interface ForwardResult { void onForwarded(String txpowid, int coins); void onNothing(); void onFailed(String message); }
    public interface SweepResult { void onSwept(int addressesForwarded, int coins); }

    /** The discovery beacon carries only dust — it is a rendezvous coin, not spendable liquidity. */
    private static final BigDecimal ANNOUNCE_DUST = new BigDecimal("0.000000001");
    /** Grow-in-place is capped at 2×KMIN: beyond that a forged-dust raid could in theory profit, so we
     *  force a MIGRATE (which resets KMIN to the new product) instead. */
    private static final BigDecimal GROW_CAP_MULT = new BigDecimal("2");

    private final NodeApi node;

    public PoolManager(NodeApi node) { this.node = node; }

    // ===================================================================== CREATE

    /** Create a MINIMA/token pool seeded with x0 MINIMA and y0 token. Mints a fresh owner key+address. */
    public void createPool(String tokenid, int tokDecimals, BigDecimal x0, BigDecimal y0, CreateResult cb) {
        if (x0 == null || y0 == null || x0.signum() <= 0 || y0.signum() <= 0) {
            cb.onFailed("both reserves must be greater than zero"); return;
        }
        final BigDecimal y0c = y0.setScale(tokDecimals, RoundingMode.DOWN);   // reserve must be an achievable coin amount
        if (y0c.signum() <= 0) { cb.onFailed("token amount is below the token's smallest unit"); return; }
        if (!PoolCovenant.sizeOk(x0, y0c)) {
            cb.onFailed("x0 × y0 must stay under 2^64 — use smaller reserves (or split into several pools)"); return;
        }
        final String kmin = PoolCovenant.kmin(x0, y0c);

        // 1. mint the owner identity (payout address + its public key = $OADR / $OPK)
        node.cmd("newaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String oadr = r != null ? r.optString("address", "") : "";
                String opk  = r != null ? r.optString("publickey", "") : "";
                if (oadr.isEmpty() || opk.isEmpty()) { cb.onFailed("could not mint an owner key"); return; }

                final String script = PoolCovenant.script(opk, oadr, tokenid, kmin);
                // 2. derive the canonical covenant address the same way discovery will (node runscript)
                deriveAddress(script, new AddrCb() {
                    @Override public void ok(String address, String mx) {
                        // 3. register so the node tracks + can later spend the pool coins
                        node.cmd("newscript trackall:true script:" + Util.scriptArg(script), new NodeApi.Cb() {
                            @Override public void onResult(JSONObject nj) { fundAndPost(); }
                            @Override public void onError(String m) { fundAndPost(); }   // best-effort; post still validates
                            private void fundAndPost() {
                                Pool p = new Pool();
                                p.address = address; p.mxaddress = mx; p.opk = opk; p.oadr = oadr;
                                p.tok = tokenid; p.kmin = kmin; p.tokDecimals = tokDecimals;
                                p.covenantScript = script;   // exact covenant → OwnPoolStore recipe for recovery
                                p.reserveM = x0; p.reserveT = y0c;
                                buildCreate(p, x0, y0c, tokenid, cb);
                            }
                        });
                    }
                    @Override public void fail(String m) { cb.onFailed("address derivation failed: " + m); }
                });
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    private void buildCreate(Pool p, BigDecimal x0, BigDecimal y0, String tokenid, CreateResult cb) {
        final BigDecimal minimaNeed = x0.add(ANNOUNCE_DUST);
        // select MINIMA (reserve + beacon dust), then token (reserve)
        selectCoins(Util.MINIMA_TOKENID, minimaNeed, p.address, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                selectCoins(tokenid, y0, p.address, new SelCb() {
                    @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                        String txid = "ppcreate_" + tag();
                        String tokArg = " tokenid:" + tokenid;
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        // reserves at the covenant address (no state — Variant U)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(x0) + " address:" + p.address + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(y0) + " address:" + p.address + tokArg + " storestate:false");
                        // the discovery beacon — dust at the sentinel, params in state (storestate:true)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                        // change goes to the funding coins' own (non-owner) address, NOT $OADR — otherwise the
                        // owner's spare MINIMA piles up at $OADR, and funding a later self-swap from there would
                        // sign with $OPK and trip the covenant's owner branch.
                        String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                        String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                        BigDecimal mchange = msum.subtract(minimaNeed);
                        if (mchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                        BigDecimal tchange = tsum.subtract(y0);
                        if (tchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                        addAnnounceState(cmds, txid, p);
                        cmds.add("txnsign id:" + txid + " publickey:auto");
                        cmds.add("txnbasics id:" + txid);
                        TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                            @Override public void ok(String txpowid) { cb.onCreated(p, txpowid); }
                            @Override public void fail(String message) { cb.onFailed(message); }
                        });
                    }
                    @Override public void none() { cb.onFailed("insufficient token balance to seed the pool"); }
                });
            }
            @Override public void none() { cb.onFailed("insufficient MINIMA to seed the pool"); }
        });
    }

    // ===================================================================== ADD LIQUIDITY (grow-in-place)

    /** Owner-signed deposit: reserves grow at the SAME address. Capped at 2×KMIN (else migrate). */
    public void deposit(Pool p, BigDecimal addM, BigDecimal addT, Result cb) {
        if (!p.funded()) { cb.onFailed("pool has no live reserves"); return; }
        if (addM == null || addT == null || addM.signum() < 0 || addT.signum() < 0
                || (addM.signum() == 0 && addT.signum() == 0)) { cb.onFailed("enter an amount to add"); return; }
        final BigDecimal addTc = addT.setScale(p.tokDecimals, RoundingMode.DOWN);
        final BigDecimal newX = p.reserveM.add(addM);
        final BigDecimal newY = p.reserveT.add(addTc);
        BigDecimal cap = Util.decOr(p.kmin, BigDecimal.ZERO).multiply(GROW_CAP_MULT);
        if (cap.signum() > 0 && newX.multiply(newY).compareTo(cap) > 0) {
            cb.onFailed("this deposit would push K past 2×KMIN — use Migrate to add liquidity and reset the floor");
            return;
        }
        final String tok = p.tok, tokArg = " tokenid:" + tok;
        selectCoins(Util.MINIMA_TOKENID, addM, p.address, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                selectCoins(tok, addTc, p.address, new SelCb() {
                    @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                        String txid = "ppdep_" + tag();
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 pool MINIMA
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 pool token
                        for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        // outputs 0/1 recreate the grown reserves at the SAME address (owner grow branch)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newX) + " address:" + p.address + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newY) + " address:" + p.address + tokArg + " storestate:false");
                        String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                        String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                        BigDecimal mchange = msum.subtract(addM);
                        if (mchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                        BigDecimal tchange = tsum.subtract(addTc);
                        if (tchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                        ownerSignPost(txid, p.opk, cmds, cb);
                    }
                    @Override public void none() { cb.onFailed("insufficient token balance to add"); }
                });
            }
            @Override public void none() { cb.onFailed("insufficient MINIMA to add"); }
        });
    }

    // ===================================================================== MIGRATE (reset floor, new addr)

    /** Owner-signed migrate: old reserves sweep to $OADR, a fresh pool of (newX,newY) is created at a new
     *  address with KMIN reset to the new product, and a new announce beacon is posted. */
    public void migrate(Pool p, BigDecimal newX, BigDecimal newY, CreateResult cb) {
        if (!p.funded()) { cb.onFailed("pool has no live reserves"); return; }
        if (newX == null || newY == null || newX.signum() <= 0 || newY.signum() <= 0) { cb.onFailed("enter the new reserves"); return; }
        final BigDecimal newYc = newY.setScale(p.tokDecimals, RoundingMode.DOWN);
        if (newYc.signum() <= 0) { cb.onFailed("new token reserve below the token grain"); return; }
        if (!PoolCovenant.sizeOk(newX, newYc)) { cb.onFailed("new reserves too large — x×y must stay under 2^64"); return; }
        final String kmin2 = PoolCovenant.kmin(newX, newYc);
        final String script2 = PoolCovenant.script(p.opk, p.oadr, p.tok, kmin2);
        deriveAddress(script2, new AddrCb() {
            @Override public void ok(String a2, String mx2) {
                if (a2.equalsIgnoreCase(p.address)) {
                    cb.onFailed("these reserves give the same pool — change the amounts, or use Add to grow in place");
                    return;
                }
                node.cmd("newscript trackall:true script:" + Util.scriptArg(script2), new NodeApi.Cb() {
                    @Override public void onResult(JSONObject nj) { go(); }
                    @Override public void onError(String m) { go(); }
                    private void go() {
                        Pool np = new Pool();
                        np.address = a2; np.mxaddress = mx2; np.opk = p.opk; np.oadr = p.oadr;
                        np.tok = p.tok; np.kmin = kmin2; np.tokDecimals = p.tokDecimals;
                        np.covenantScript = script2;   // exact covenant → OwnPoolStore recipe for recovery
                        np.reserveM = newX; np.reserveT = newYc;
                        buildMigrate(p, np, newX, newYc, cb);
                    }
                });
            }
            @Override public void fail(String m) { cb.onFailed("new address derivation failed: " + m); }
        });
    }

    private void buildMigrate(Pool p, Pool np, BigDecimal newX, BigDecimal newY, CreateResult cb) {
        final String tok = p.tok, tokArg = " tokenid:" + tok;
        final BigDecimal oldX = p.reserveM, oldY = p.reserveT;
        final BigDecimal minimaNeed = newX.add(ANNOUNCE_DUST);   // new reserve + the new beacon dust
        selectCoins(Util.MINIMA_TOKENID, minimaNeed, p.address, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                selectCoins(tok, newY, p.address, new SelCb() {
                    @Override public void ok(List<Coin> tfunds, BigDecimal tsum) {
                        String txid = "ppmig_" + tag();
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 old pool MINIMA -> owner exit
                        cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 old pool token  -> owner exit
                        for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        for (Coin c : tfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                        // outputs 0/1 = owner exit of the OLD reserves to $OADR (pinned by VERIFYOUT @INPUT)
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(oldX) + " address:" + p.oadr + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(oldY) + " address:" + p.oadr + tokArg + " storestate:false");
                        // outputs 2/3 = the NEW pool reserves at the new address
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newX) + " address:" + np.address + " storestate:false");
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(newY) + " address:" + np.address + tokArg + " storestate:false");
                        // the new discovery beacon
                        cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                        String mChg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                        String tChg = tfunds.isEmpty() ? p.oadr : tfunds.get(0).address;
                        BigDecimal mchange = msum.subtract(minimaNeed);
                        if (mchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(mchange) + " address:" + mChg + " storestate:false");
                        BigDecimal tchange = tsum.subtract(newY);
                        if (tchange.signum() > 0)
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(tchange) + " address:" + tChg + tokArg + " storestate:false");
                        addAnnounceState(cmds, txid, np);
                        cmds.add("txnsign id:" + txid + " publickey:auto");
                        cmds.add("txnsign id:" + txid + " publickey:" + p.opk);   // owner branch
                        cmds.add("txnbasics id:" + txid);
                        TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                            @Override public void ok(String txpowid) { cb.onCreated(np, txpowid); }
                            @Override public void fail(String message) { cb.onFailed(message); }
                        });
                    }
                    @Override public void none() { cb.onFailed("insufficient token balance for the new pool"); }
                });
            }
            @Override public void none() { cb.onFailed("insufficient MINIMA for the new pool"); }
        });
    }

    // ===================================================================== CLOSE (owner sweep)

    /** Owner-signed close: sweep both reserves back to $OADR. */
    public void close(Pool p, Result cb) {
        if (!p.funded()) { cb.onFailed("pool has no live reserves to withdraw"); return; }
        final String tokArg = " tokenid:" + p.tok;
        String txid = "ppclose_" + tag();
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + p.coinidM);   // 0 -> owner exit
        cmds.add("txninput id:" + txid + " coinid:" + p.coinidT);   // 1 -> owner exit
        cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveM) + " address:" + p.oadr + " storestate:false");
        cmds.add("txnoutput id:" + txid + " amount:" + amt(p.reserveT) + " address:" + p.oadr + tokArg + " storestate:false");
        ownerSignPost(txid, p.opk, cmds, cb);
    }

    // ===================================================================== FORWARD TO DEFAULT WALLET

    /**
     * Forward every sendable coin sitting at a pool owner address ($OADR) onward to a fresh DEFAULT-64
     * wallet address ({@code getaddress}). The covenant pins the owner exit to $OADR, so a close can only
     * land funds there; this second hop moves them into the 64 seed-derived default addresses that a
     * seed-only restore always regenerates — so withdrawn funds are never stranded at a {@code newaddress}
     * ($OADR) that a bare seed restore wouldn't reproduce. $OADR is {@code RETURN SIGNEDBY($OPK)} and the
     * node holds $OPK, so {@code txnsign auto} covers it. Full amounts (zero burn, like the app's other txns);
     * whole coins move, so token grain is exact. No-op ({@code onNothing}) if nothing sendable is there yet
     * (e.g. a just-posted close still confirming) — callers retry.
     */
    public void forwardOwnerFunds(final String oadr, final ForwardResult cb) {
        if (isEmpty(oadr)) { cb.onFailed("no owner address"); return; }
        node.cmd("coins relevant:true sendable:true address:" + oadr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.onNothing(); return; }
                final List<String> coinids = new ArrayList<>();
                final Map<String, BigDecimal> byTok = new LinkedHashMap<>();   // tokenid -> summed human amount
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || c.optBoolean("spent", false)) continue;
                    String tid = c.optString("tokenid", "");
                    String cid = c.optString("coinid", "");
                    if (tid.isEmpty() || cid.isEmpty()) continue;
                    BigDecimal a = "0x00".equals(tid)
                            ? new BigDecimal(c.optString("amount", "0"))
                            : new BigDecimal(c.optString("tokenamount", c.optString("amount", "0")));
                    if (a.signum() <= 0) continue;
                    coinids.add(cid);
                    BigDecimal prev = byTok.get(tid);
                    byTok.put(tid, prev == null ? a : prev.add(a));
                }
                if (coinids.isEmpty()) { cb.onNothing(); return; }
                // fresh DEFAULT-64 wallet address to receive (getaddress, not newaddress)
                node.cmd("getaddress", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject aj) {
                        JSONObject r = aj.optJSONObject("response");
                        String dest = r != null ? r.optString("address", "") : "";
                        if (isEmpty(dest)) { cb.onFailed("could not get a wallet address"); return; }
                        String txid = "ppfwd_" + tag();
                        List<String> cmds = new ArrayList<>();
                        cmds.add("txncreate id:" + txid);
                        for (String cid : coinids) cmds.add("txninput id:" + txid + " coinid:" + cid);
                        for (Map.Entry<String, BigDecimal> en : byTok.entrySet()) {
                            String tokArg = "0x00".equals(en.getKey()) ? "" : " tokenid:" + en.getKey();
                            cmds.add("txnoutput id:" + txid + " amount:" + amt(en.getValue()) + " address:" + dest + tokArg + " storestate:false");
                        }
                        cmds.add("txnsign id:" + txid + " publickey:auto");
                        cmds.add("txnbasics id:" + txid);
                        final int n = coinids.size();
                        TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                            @Override public void ok(String txpowid) { cb.onForwarded(txpowid, n); }
                            @Override public void fail(String message) { cb.onFailed(message); }
                        });
                    }
                    @Override public void onError(String m) { cb.onFailed(m); }
                });
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    /**
     * "Collect to wallet" sweep: forward stranded funds from a set of owner addresses to default-64 wallet
     * addresses. Rescues funds that closed pools left at $OADR before this fix (and any close whose forward
     * didn't complete). Best-effort per address; reports totals. Dedups addresses (migrate shares $OADR).
     */
    public void sweepOwnerFunds(final List<String> oadrs, final SweepResult cb) {
        final LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String a : oadrs) if (a != null && !a.isEmpty()) uniq.add(a);
        if (uniq.isEmpty()) { cb.onSwept(0, 0); return; }
        final AtomicInteger pending = new AtomicInteger(uniq.size());
        final AtomicInteger addrs = new AtomicInteger(0);
        final AtomicInteger coins = new AtomicInteger(0);
        for (String oadr : uniq) {
            forwardOwnerFunds(oadr, new ForwardResult() {
                @Override public void onForwarded(String txpowid, int n) { addrs.incrementAndGet(); coins.addAndGet(n); tick(); }
                @Override public void onNothing() { tick(); }
                @Override public void onFailed(String message) { tick(); }
                private void tick() { if (pending.decrementAndGet() == 0) cb.onSwept(addrs.get(), coins.get()); }
            });
        }
    }

    // ===================================================================== RE-ANNOUNCE (Layer 5)

    /**
     * Post ONE fresh announce beacon for an existing pool so fresh nodes keep rediscovering it after the
     * original beacon pruned. This spends NO covenant coin — it's a plain owner-wallet send of the dust
     * beacon to the SENTINEL (with the pool params in state), change back. No owner signature needed (the
     * covenant isn't touched); adds no spend authority. Callers only fire this for a pool whose beacon has
     * actually FADED from the recent sentinel window, so beacons don't pile up.
     */
    public void reannounce(final Pool p, final Result cb) {
        if (p == null || p.address == null || isEmpty(p.opk) || isEmpty(p.tok) || isEmpty(p.oadr) || isEmpty(p.kmin)) {
            cb.onFailed("incomplete pool record"); return;
        }
        selectCoins(Util.MINIMA_TOKENID, ANNOUNCE_DUST, p.address, new SelCb() {
            @Override public void ok(List<Coin> mfunds, BigDecimal msum) {
                String txid = "ppann_" + tag();
                List<String> cmds = new ArrayList<>();
                cmds.add("txncreate id:" + txid);
                for (Coin c : mfunds) cmds.add("txninput id:" + txid + " coinid:" + c.coinid);
                cmds.add("txnoutput id:" + txid + " amount:" + amt(ANNOUNCE_DUST) + " address:" + PoolCovenant.SENTINEL + " storestate:true");
                String chg = mfunds.isEmpty() ? p.oadr : mfunds.get(0).address;
                BigDecimal change = msum.subtract(ANNOUNCE_DUST);
                if (change.signum() > 0)
                    cmds.add("txnoutput id:" + txid + " amount:" + amt(change) + " address:" + chg + " storestate:false");
                addAnnounceState(cmds, txid, p);
                cmds.add("txnsign id:" + txid + " publickey:auto");   // only the funding coins are signed; no covenant coin, no $OPK
                cmds.add("txnbasics id:" + txid);
                TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
                    @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
                    @Override public void fail(String message) { cb.onFailed(message); }
                });
            }
            @Override public void none() { cb.onFailed("no spare MINIMA to re-announce"); }
        });
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    // ===================================================================== helpers

    private void ownerSignPost(String txid, String opk, List<String> cmds, Result cb) {
        cmds.add("txnsign id:" + txid + " publickey:auto");        // any wallet funding coins
        cmds.add("txnsign id:" + txid + " publickey:" + opk);      // the owner signature the covenant requires
        cmds.add("txnbasics id:" + txid);
        TxPost.checkThenPost(node, txid, cmds, new TxPost.Done() {
            @Override public void ok(String txpowid) { cb.onPosted(txpowid); }
            @Override public void fail(String message) { cb.onFailed(message); }
        });
    }

    /** Append the registry announce beacon's state ports (matches phaseB: 0 reclaim pk · 1 version ·
     *  2 tokenid · 3 $OADR · 4 $OPK · 5 $KMIN). The node's {@code txnstate} sets ONE port at a time
     *  ({@code txnstate id: port: value:}) — it does NOT accept a state JSON blob (that's only the
     *  {@code send} command). Version "PP1" is sent as hex, not a bare string. All values are hex or a
     *  plain number, so no quoting is needed. */
    private static void addAnnounceState(List<String> cmds, String txid, Pool p) {
        String ppver = "0x" + toHex("PP1");
        cmds.add("txnstate id:" + txid + " port:0 value:" + p.opk);    // reclaim key (the owner)
        cmds.add("txnstate id:" + txid + " port:1 value:" + ppver);    // version
        cmds.add("txnstate id:" + txid + " port:2 value:" + p.tok);    // tokenid
        cmds.add("txnstate id:" + txid + " port:3 value:" + p.oadr);   // owner payout addr
        cmds.add("txnstate id:" + txid + " port:4 value:" + p.opk);    // owner pubkey
        cmds.add("txnstate id:" + txid + " port:5 value:" + p.kmin);   // product floor
    }

    private static String toHex(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes()) b.append(String.format("%02X", c));
        return b.toString();
    }

    private interface AddrCb { void ok(String address, String mx); void fail(String message); }

    /**
     * Derive the covenant address via the node's own runscript AND enforce the fund-safety pre-flight
     * guard: the covenant MUST parse ({@code parseok=true}). If it doesn't (e.g. a bad quoting/escaping
     * regression), we ABORT here — before any funds are committed — because a coin at a non-parsing
     * script's address is unspendable by anyone, forever. Because a parsing covenant always has a working
     * owner-close branch, passing this guard means the pool is always owner-recoverable even if discovery
     * were to mismatch. This is the structural guarantee that a create/migrate can never strand funds.
     */
    private void deriveAddress(String script, AddrCb cb) {
        node.cmd("runscript script:" + Util.scriptArg(script), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject resp = j.optJSONObject("response");
                boolean parseok = TxPost.truthy(resp, "parseok");
                JSONObject sc = resp != null ? resp.optJSONObject("script") : null;
                String a = sc != null ? sc.optString("address", "") : "";
                String mx = sc != null ? sc.optString("mxaddress", "") : "";
                if (a.isEmpty()) { cb.fail("could not derive the pool address"); return; }
                if (!parseok) { cb.fail("the pool covenant failed to compile (parse error) — aborted before "
                        + "any funds moved, to protect your coins"); return; }
                cb.ok(a, mx);
            }
            @Override public void onError(String m) { cb.fail(m); }
        });
    }

    private interface SelCb { void ok(List<Coin> coins, BigDecimal sum); void none(); }

    /** Plain sendable wallet coins for a token, largest-first, summing to at least {@code need}. Coins at
     *  {@code excludeAddress} (the pool's own covenant address) are never selected — belt-and-braces on top
     *  of {@code sendable:true}, so a pool leg can never be pulled in twice as "funding". */
    private void selectCoins(String tokenid, BigDecimal need, String excludeAddress, SelCb cb) {
        if (need.signum() <= 0) { cb.ok(new ArrayList<>(), BigDecimal.ZERO); return; }
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONArray arr = json.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.none(); return; }
                List<Coin> avail = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject jc = arr.optJSONObject(i);
                    if (jc == null) continue;
                    Coin c = Coin.from(jc);
                    if (excludeAddress != null && c.address != null && c.address.equalsIgnoreCase(excludeAddress)) continue;
                    avail.add(c);
                }
                avail.sort((a, b) -> new BigDecimal(b.amount).compareTo(new BigDecimal(a.amount)));
                List<Coin> sel = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (Coin c : avail) {
                    sel.add(c);
                    sum = sum.add(new BigDecimal(c.amount));
                    if (sum.compareTo(need) >= 0) { cb.ok(sel, sum); return; }
                }
                cb.none();
            }
            @Override public void onError(String message) { cb.none(); }
        });
    }

    private static String amt(BigDecimal b) { return b.stripTrailingZeros().toPlainString(); }

    private static String tag() {
        return System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }
}

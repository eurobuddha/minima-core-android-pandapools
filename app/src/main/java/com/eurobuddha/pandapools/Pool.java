package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A discovered AMM pool: its derived covenant address + params (from the registry announce) + its two live
 * reserve coins. Reserves are the coin amounts (no state). Price/K are derived, not stored on-chain.
 */
public class Pool {

    public String address;      // derived covenant address (0x)
    public String mxaddress;    // Mx form
    public String opk, oadr, tok, kmin;   // announce params (owner pk, payout addr, tokenid, floor)
    public boolean owned;                 // this node's wallet owns the payout addr (checkaddress) — survives app reinstall
    /** For a pool DISCOVERED via the shared registry (not one this node created): its covenant script, so
     *  discovery can `newscript trackall` it once it's confirmed funded — track-on-discovery, which makes a
     *  seen-but-not-created pool stay GTC-visible + swappable on this node forever. Null for own pools. */
    public String covenantScript;
    public String tokName;      // display name of the token side (resolved when reserves are scanned)
    public int tokDecimals = 8; // token's on-chain decimal grain (resolved when reserves are scanned)

    /** Short, human display symbol for the token side (its name, or a truncated tokenid). */
    public String tokenLabel() {
        if (tokName != null && !tokName.isEmpty() && !tokName.equalsIgnoreCase(tok)) return tokName;
        String h = tok != null && tok.startsWith("0x") ? tok.substring(2) : (tok == null ? "" : tok);
        return h.length() > 8 ? h.substring(0, 8) + "…" : h;
    }

    // live reserves (null until scanned)
    public BigDecimal reserveM;    // MINIMA reserve (x)
    public String coinidM;
    public BigDecimal reserveT;    // token reserve (y, scaled token units)
    public String coinidT;

    public boolean funded() { return reserveM != null && reserveT != null
            && reserveM.signum() > 0 && reserveT.signum() > 0; }

    /** Constant product K = x*y (the current invariant value; grows with each swap's fee). */
    public BigDecimal k() { return funded() ? reserveM.multiply(reserveT) : BigDecimal.ZERO; }

    /** Spot price = y/x = token per MINIMA (the marginal price at the current reserves). */
    public BigDecimal spotPrice() {
        if (!funded()) return BigDecimal.ZERO;
        return reserveT.divide(reserveM, new MathContext(20, RoundingMode.DOWN));
    }

    /** Accrued-fee proxy: K/KMIN - 1 (0 at creation, grows as fees accrue). */
    public BigDecimal feeGrowth() {
        BigDecimal km = Util.decOr(kmin, BigDecimal.ZERO);
        if (km.signum() == 0) return BigDecimal.ZERO;
        return k().divide(km, new MathContext(20, RoundingMode.DOWN)).subtract(BigDecimal.ONE);
    }
}

package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The PandaPools constant-product AMM covenant (KISS script) + its parameterisation — the Java port of
 * the reference genpool.py. Each pool's params ($OPK owner pubkey, $OADR owner payout addr, $TOK paired
 * tokenid, $KMIN product floor) are hardcoded literals, so every pool has a UNIQUE address = its script
 * hash. Reserves are the two pool coins' amounts (no state). 0.5% fee stays in the pool = the LP reward.
 *
 * Address derivation is a node call (runscript → response.script.address); do it via PoolBook.
 */
public final class PoolCovenant {

    /** MiniNumber magnitude ceiling — a KMIN literal >= this won't compile (script overflow at parse). */
    public static final BigDecimal MININUMBER_MAX = new BigDecimal("18446744073709551615"); // 2^64-1

    /** The covenant template, one line, with $OPK/$OADR/$TOK/$KMIN placeholders. Byte-identical to poolscript.tpl. */
    private static final String TEMPLATE =
        "IF SIGNEDBY($OPK) THEN "
      + "IF VERIFYOUT(@INPUT $OADR @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF "
      + "RETURN GETOUTADDR(@INPUT) EQ @ADDRESS AND GETOUTTOK(@INPUT) EQ @TOKENID AND GETOUTAMT(@INPUT) GTE @AMOUNT "
      + "ENDIF "
      + "IF @TOKENID EQ 0x00 THEN "
      + "ASSERT @INPUT % 2 EQ 0 LET s=@INPUT+1 "
      + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ $TOK "
      + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ $TOK "
      + "LET x=@AMOUNT LET y=GETINAMT(s) LET nx=GETOUTAMT(@INPUT) LET ny=GETOUTAMT(s) "
      + "ASSERT VERIFYOUT(@INPUT @ADDRESS nx 0x00 FALSE) "
      + "ELSE "
      + "ASSERT @TOKENID EQ $TOK AND @INPUT % 2 EQ 1 LET s=@INPUT-1 "
      + "ASSERT GETINADDR(s) EQ @ADDRESS AND GETINTOK(s) EQ 0x00 "
      + "ASSERT GETOUTADDR(s) EQ @ADDRESS AND GETOUTTOK(s) EQ 0x00 "
      + "LET y=@AMOUNT LET x=GETINAMT(s) LET ny=GETOUTAMT(@INPUT) LET nx=GETOUTAMT(s) "
      + "ASSERT VERIFYOUT(@INPUT @ADDRESS ny $TOK FALSE) "
      + "ENDIF "
      + "LET dx=nx-x LET dy=ny-y LET fx=MAX(dx 0)*5/1000 LET fy=MAX(dy 0)*5/1000 "
      + "RETURN (nx-fx)*(ny-fy) GTE MAX(x*y $KMIN)";

    /** "PANDAPOOLS" in hex — the shared registry sentinel address. */
    public static final String SENTINEL = "0x50414E4441504F4F4C53";

    private PoolCovenant() {}

    /** Fill the template with a pool's four literals. Returns the one-line KISS script. */
    public static String script(String opk, String oadr, String tok, String kmin) {
        return TEMPLATE
            .replace("$OPK", opk)
            .replace("$OADR", oadr)
            .replace("$TOK", tok)
            .replace("$KMIN", kmin);
    }

    /** KMIN = SIGDIG(20, x0*y0) rounded DOWN — the creation product to 20 sig-figs (matches genpool.py).
     *  stripTrailingZeros is CRITICAL: BigDecimal multiply keeps the operands' scale (a grain-clamped
     *  8-dp token gives e.g. 716.2041200000000), but the node normalizes the announce's stored KMIN to
     *  716.20412. If the covenant literal kept the trailing zeros it would hash to a different address
     *  than discovery re-derives from the announce → the pool becomes invisible. Canonicalise so the
     *  covenant, the announce state, and discovery all agree. */
    public static String kmin(BigDecimal x0, BigDecimal y0) {
        BigDecimal p = x0.multiply(y0);
        if (p.signum() == 0) return "0";
        if (p.compareTo(MININUMBER_MAX) >= 0)
            throw new IllegalArgumentException("x0*y0 >= 2^64; reserves too large for a single pool");
        BigDecimal k = p.round(new MathContext(20, RoundingMode.DOWN)).stripTrailingZeros();
        return k.signum() == 0 ? "0" : k.toPlainString();
    }

    /** True if a pool with these reserves is buildable (KMIN literal compiles). */
    public static boolean sizeOk(BigDecimal x0, BigDecimal y0) {
        return x0.multiply(y0).compareTo(MININUMBER_MAX) < 0;
    }
}

package com.eurobuddha.pandapools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;

/** Pure helpers, with emphasis on the fund-critical {@link Util#scriptArg} (the {@code \/} bug strands funds). */
public class UtilTest {

    @Test public void scriptArgNeverEscapesForwardSlash() {
        // THE bug: JSONObject.quote turns "*5/1000" into "*5\/1000" → KISS parse fails → covenant unspendable.
        String covenant = PoolCovenant.script(
                "0xAA", "0xBB", "0xCC", "1.5");   // contains the *5/1000 fee
        String arg = Util.scriptArg(covenant);
        assertFalse("must NOT contain an escaped slash", arg.contains("\\/"));
        assertTrue("keeps the literal /1000", arg.contains("/1000"));
    }

    @Test public void scriptArgIsQuoteWrappedAndEscapesQuotesAndBackslashes() {
        assertEquals("\"abc\"", Util.scriptArg("abc"));
        assertEquals("\"a\\\"b\"", Util.scriptArg("a\"b"));    // " -> \"
        assertEquals("\"a\\\\b\"", Util.scriptArg("a\\b"));    // \ -> \\
        String out = Util.scriptArg("x/y");
        assertTrue(out.startsWith("\"") && out.endsWith("\""));
        assertEquals("\"x/y\"", out);
    }

    @Test public void decOrFallsBackAndNeverThrows() {
        BigDecimal fb = new BigDecimal("7");
        assertEquals(new BigDecimal("3.14"), Util.decOr("3.14", fb));
        assertEquals(fb, Util.decOr(null, fb));
        assertEquals(fb, Util.decOr("   ", fb));
        assertEquals(fb, Util.decOr("not-a-number", fb));
        assertEquals(new BigDecimal("42"), Util.decOr("  42  ", fb));
    }

    @Test public void isValidAddress() {
        String hex64 = "0x" + repeat("A", 64);
        assertTrue(Util.isValidAddress(hex64));
        assertFalse("63 hex is invalid", Util.isValidAddress("0x" + repeat("A", 63)));
        assertFalse(Util.isValidAddress(null));
        assertFalse(Util.isValidAddress("garbage"));
        assertTrue(Util.isValidAddress("Mx" + repeat("A", 50)));
    }

    @Test public void isMinima() {
        assertTrue(Util.isMinima(null));
        assertTrue(Util.isMinima("0x00"));
        assertFalse(Util.isMinima("0x7D39"));
    }

    @Test public void decimalPlaces() {
        assertEquals(0, Util.decimalPlaces(new BigDecimal("100")));
        assertEquals(0, Util.decimalPlaces(new BigDecimal("2.0")));      // trailing zero stripped
        assertEquals(2, Util.decimalPlaces(new BigDecimal("2.05")));
        assertEquals(8, Util.decimalPlaces(new BigDecimal("0.00000001")));
    }

    @Test public void tidyAmount() {
        assertEquals("2", Util.tidyAmount("2.000000"));
        assertEquals("2.5", Util.tidyAmount("2.50"));
        assertEquals("100", Util.tidyAmount("100"));
        assertEquals("0", Util.tidyAmount(""));
        assertEquals("0", Util.tidyAmount(null));
    }

    @Test public void tokenDecimalsClampsNegativeToZero() throws JSONException {
        // a malicious/garbage token claiming negative decimals must not produce a negative grain (setScale would throw)
        assertEquals(0, Util.tokenDecimals(new JSONObject().put("decimals", -5)));
    }

    @Test public void shorten() {
        assertEquals("", Util.shorten(null));
        assertEquals("short", Util.shorten("short"));                 // <= 16 chars: unchanged
        String s = "0x1234567890ABCDEF1234";                          // 22 chars
        assertEquals("0x123456…EF1234", Util.shorten(s));             // first 8 + … + last 6
    }

    @Test public void tokenDecimalsFromMetadata() throws JSONException {
        assertEquals(8, Util.tokenDecimals(new JSONObject().put("decimals", 8)));
        assertEquals(0, Util.tokenDecimals(new JSONObject().put("decimals", 0)));
        assertEquals(44, Util.tokenDecimals(new JSONObject().put("decimals", 99)));   // clamped to 44
        assertEquals(8, Util.tokenDecimals(new JSONObject()));                         // missing -> default 8
        JSONObject nested = new JSONObject().put("token", new JSONObject().put("decimals", 6));
        assertEquals(6, Util.tokenDecimals(nested));
    }

    @Test public void extractTxpowid() throws JSONException {
        JSONObject direct = new JSONObject().put("response", new JSONObject().put("txpowid", "0xDEAD"));
        assertEquals("0xDEAD", Util.extractTxpowid(direct, "fb"));
        JSONObject nested = new JSONObject().put("response",
                new JSONObject().put("txpow", new JSONObject().put("txpowid", "0xBEEF")));
        assertEquals("0xBEEF", Util.extractTxpowid(nested, "fb"));
        assertEquals("fb", Util.extractTxpowid(new JSONObject(), "fb"));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}

package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules that keep the owner-key hunt bounded.
 *
 * Getting these wrong in one direction re-burns 256 permanent wallet keys per owner action, forever
 * (the runaway that put 166 junk keys on a live node). Getting them wrong in the other direction locks a
 * legitimately recoverable pool out of its own owner key. Every rule here is the REAL production rule —
 * {@link OwnerKeyRecovery} calls these exact functions.
 */
public class HuntBudgetTest {

    // ---- helpers to build `keys` replies -----------------------------------------------------------

    private static JSONObject keyRow(String publickey, String modifier) throws Exception {
        JSONObject k = new JSONObject();
        k.put("publickey", publickey);
        if (modifier != null) k.put("modifier", modifier);
        k.put("uses", 0);
        return k;
    }

    /** The modern reply shape: {"response":{"keys":[...],"total":n}}. */
    private static JSONObject nestedReply(int total, JSONObject... rows) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject r : rows) arr.put(r);
        JSONObject resp = new JSONObject().put("keys", arr);
        if (total >= 0) resp.put("total", total);
        return new JSONObject().put("response", resp);
    }

    /** The bare-array reply shape: {"response":[...]}. */
    private static JSONObject arrayReply(JSONObject... rows) throws Exception {
        JSONArray arr = new JSONArray();
        for (JSONObject r : rows) arr.put(r);
        return new JSONObject().put("response", arr);
    }

    // ---- fingerprint --------------------------------------------------------------------------------

    @Test public void fingerprintIsTheLowestModifierKeyRegardlessOfReplyOrder() throws Exception {
        // the node's key list is an unordered SELECT — the reply order must not matter
        JSONObject reply = nestedReply(3,
                keyRow("0xCCC", "0x41"),    // index 65
                keyRow("0xAAA", "0x00"),    // index 0 — the seed's first default key
                keyRow("0xBBB", "0x05"));   // index 5
        assertEquals("0xaaa", HuntBudget.fingerprint(reply));
    }

    @Test public void fingerprintComparesModifiersNumericallyNotLexicographically() throws Exception {
        // as STRINGS "0x0100" < "0xFF"; as NUMBERS 0xFF (255) < 0x0100 (256). Numeric must win.
        JSONObject reply = nestedReply(2,
                keyRow("0xDEEP", "0x0100"),
                keyRow("0xFIRST", "0xFF"));
        assertEquals("0xfirst", HuntBudget.fingerprint(reply));
    }

    @Test public void fingerprintReadsBothReplyShapes() throws Exception {
        assertEquals("0xaaa", HuntBudget.fingerprint(nestedReply(1, keyRow("0xAAA", "0x00"))));
        assertEquals("0xaaa", HuntBudget.fingerprint(arrayReply(keyRow("0xAAA", "0x00"))));
    }

    @Test public void fingerprintOfAnUnreadableReplyIsTheConstantFallback() throws Exception {
        assertEquals(HuntBudget.NO_FP, HuntBudget.fingerprint(new JSONObject()));
        assertEquals(HuntBudget.NO_FP, HuntBudget.fingerprint(null));
        // rows without a parsable modifier can't fingerprint either
        assertEquals(HuntBudget.NO_FP, HuntBudget.fingerprint(nestedReply(1, keyRow("0xAAA", "zzz"))));
    }

    @Test public void reseedingChangesTheLedgerKeyAndSoReopensTheBudget() {
        // the ledger is keyed (fingerprint | opk): a different seed's fingerprint is a fresh entry —
        // load-bearing, because even the CORRECT seed's restore rebuilds only the 64 defaults and
        // still needs its hunt allowed
        assertNotEquals(HuntBudget.key("0xseed1", "0xOPK"), HuntBudget.key("0xseed2", "0xOPK"));
        assertEquals("0xseed1|0xopk", HuntBudget.key("0xSEED1", "0xOPK"));   // both halves lowercased
    }

    // ---- total / modifier extraction ----------------------------------------------------------------

    @Test public void totalPrefersTheReplysOwnCountAndFallsBackToRowCount() throws Exception {
        assertEquals(230, HuntBudget.totalOf(nestedReply(230, keyRow("0xAAA", "0x00"))));   // reply says 230
        assertEquals(1, HuntBudget.totalOf(arrayReply(keyRow("0xAAA", "0x00"))));           // no total field
        assertEquals(-1, HuntBudget.totalOf(new JSONObject()));                             // unreadable
    }

    @Test public void modifierOfFindsAKeyCaseInsensitively() throws Exception {
        JSONObject reply = nestedReply(2, keyRow("0xAbCd", "0x41"), keyRow("0xEEEE", "0x00"));
        assertEquals(65, HuntBudget.modifierOf(reply, "0xABCD"));
        assertEquals(-1, HuntBudget.modifierOf(reply, "0x9999"));   // not held
    }

    @Test public void unparsableModifiersAreNull() {
        assertNull(HuntBudget.parseModifier(null));
        assertNull(HuntBudget.parseModifier(""));
        assertNull(HuntBudget.parseModifier("0x"));
        assertNull(HuntBudget.parseModifier("not-hex"));
        assertEquals(65, HuntBudget.parseModifier("0x41").intValue());
    }

    // ---- kidx arithmetic ------------------------------------------------------------------------------

    @Test public void mintsNeededIsExact() {
        // wallet holds indexes 0..63 (total 64); the key sits at index 100 → mint 37 more
        assertEquals(37, HuntBudget.mintsNeeded(100, 64));
        assertEquals(1, HuntBudget.mintsNeeded(64, 64));    // the very next mint
        assertEquals(0, HuntBudget.mintsNeeded(63, 64));    // already minted (held or foreign — not a mint problem)
    }

    @Test public void aWalletPastTheKnownIndexWithoutTheKeyProvesAForeignSeed() {
        // this seed re-derives its keys IN ORDER, so if index 100 has been minted and the wanted key
        // is not held, this seed's index-100 key is a DIFFERENT key: proof, with zero mints
        assertTrue(HuntBudget.provenForeign(100, 230));
        assertFalse(HuntBudget.provenForeign(100, 64));     // not there yet — still reachable
        assertFalse(HuntBudget.provenForeign(100, 100));    // index 100 not minted yet (0..99)
        assertFalse(HuntBudget.provenForeign(-1, 230));     // unknown index proves nothing
        assertFalse(HuntBudget.provenForeign(100, -1));     // unknown total proves nothing
    }

    // ---- disposition at hunt start --------------------------------------------------------------------

    @Test public void aFreshKeyIsHunted() {
        assertEquals(HuntBudget.HUNT, HuntBudget.disposition(-1, 230, 0));
    }

    @Test public void aBudgetExhaustedKeyIsOnlyWatchedNeverHuntedAgain() {
        // the headline fix: the poisoned Collect that used to re-mint 256 keys per tap now mints ZERO
        assertEquals(HuntBudget.WATCH, HuntBudget.disposition(-1, 230, HuntBudget.MAX_NEW_KEYS));
        assertEquals(HuntBudget.WATCH, HuntBudget.disposition(-1, 230, 999));
    }

    @Test public void anInterruptedHuntResumesWithOnlyItsRemainder() {
        // the observed node: an IPC error stopped the hunt at 166 of 256 — the next hunt may spend
        // only the remaining 90, not a fresh 256
        assertEquals(HuntBudget.HUNT, HuntBudget.disposition(-1, 230, 166));
        assertEquals(HuntBudget.KEEP, HuntBudget.afterCharge(null, 300, 255));
        assertEquals(HuntBudget.EXHAUSTED, HuntBudget.afterCharge(null, 300, 256));   // 166 + 90 = exactly 256
    }

    @Test public void eachOpkCarriesItsOwnBudget() {
        // key A exhausted, key B fresh — B's hunt must not be blocked by A's spent budget
        assertEquals(HuntBudget.WATCH, HuntBudget.disposition(-1, 500, HuntBudget.MAX_NEW_KEYS));
        assertEquals(HuntBudget.HUNT, HuntBudget.disposition(-1, 500, 0));
    }

    @Test public void aKnownIndexAlreadyPassedIsForeignProvenBeforeAnyMint() {
        assertEquals(HuntBudget.FOREIGN_PROVEN, HuntBudget.disposition(100, 230, 0));
    }

    @Test public void aKnownReachableIndexIsHuntedEvenIfThePlainBudgetIsSpent() {
        // kidx is an exact, provable target — the flat 256 cap exists for BLIND hunts only. A ledger
        // exhausted under pre-v3 blind hunting must not lock out a now-provable recovery.
        assertEquals(HuntBudget.HUNT, HuntBudget.disposition(300, 230, HuntBudget.MAX_NEW_KEYS));
        assertTrue(HuntBudget.kidxUsable(300, 230));
    }

    @Test public void aMaliciousMaxIntKidxCannotOverflowIntoAFreeTarget() {
        // kidx is user-importable via backup files: (MAX_VALUE + 1 - total) must saturate, not wrap
        // negative — a wrap would make the unreachable target look like it needs 0 mints and pass
        // kidxUsable, and kidx-driven hunts have no flat cap.
        assertEquals(Integer.MAX_VALUE, HuntBudget.mintsNeeded(Integer.MAX_VALUE, 0));
        assertFalse(HuntBudget.kidxUsable(Integer.MAX_VALUE, 0));
        assertFalse(HuntBudget.kidxUsable(Integer.MAX_VALUE, 64));
    }

    @Test public void anAbsurdlyDeepIndexIsDistrustedAndTheFlatBudgetApplies() {
        int kidx = 230 + HuntBudget.MAX_KIDX_MINT + 1;   // a corrupt recipe must not budget a mint marathon
        assertFalse(HuntBudget.kidxUsable(kidx, 230));
        assertEquals(HuntBudget.HUNT, HuntBudget.disposition(kidx, 230, 0));                          // plain budget
        assertEquals(HuntBudget.WATCH, HuntBudget.disposition(kidx, 230, HuntBudget.MAX_NEW_KEYS));   // capped as usual
    }

    // ---- verdicts during the hunt ----------------------------------------------------------------------

    @Test public void anExactTargetHuntEndsTheMomentTheIndexIsPassedWithoutAMatch() {
        // kidx 300: minting index 299 keeps hunting, minting index 300 without a match is proof
        assertEquals(HuntBudget.KEEP, HuntBudget.afterCharge(300, 299, 5));
        assertEquals(HuntBudget.FOREIGN_PAST_KIDX, HuntBudget.afterCharge(300, 300, 6));
    }

    @Test public void anExactTargetHuntIsNotStoppedByTheFlatCap() {
        // 437 mints to reach a deep-but-provable index must not die at 256
        assertEquals(HuntBudget.KEEP, HuntBudget.afterCharge(500, 320, HuntBudget.MAX_NEW_KEYS + 1));
    }

    @Test public void theFlatCapStopsAtExactlyTheCapNeverPastIt() {
        assertEquals(HuntBudget.KEEP, HuntBudget.afterCharge(null, 100, HuntBudget.MAX_NEW_KEYS - 1));
        assertEquals(HuntBudget.EXHAUSTED, HuntBudget.afterCharge(null, 100, HuntBudget.MAX_NEW_KEYS));
    }
}

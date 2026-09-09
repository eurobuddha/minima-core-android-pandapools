package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The arithmetic that decides where a restored owner key resumes signing.
 *
 * Getting this wrong in one direction wastes a leaf out of 262,144. Getting it wrong in the other
 * re-signs a leaf the pre-restore node already spent, which leaks that leaf's private key. So every
 * term here errs high on purpose.
 */
public class KeyUsesTest {

    private static final int REFRESH = PoolRefresher.REFRESH_BLOCKS;   // 900

    // ---- restoreTarget ----

    @Test public void targetCarriesTheCountRecordedAtBackup() {
        // no time passed, no slack → resume exactly where the key left off
        assertEquals(347, KeyUses.restoreTarget(347, 412_006, 412_006, 0));
    }

    @Test public void elapsedBlocksBecomeKeepFreshSignatures() {
        // the owner key signs once per pool refresh, and a refresh happens every REFRESH_BLOCKS
        int backupBlock = 412_006;
        int now = backupBlock + (96 * REFRESH);
        assertEquals(347 + 96, KeyUses.restoreTarget(347, backupBlock, now, 0));
    }

    @Test public void aPartialRefreshIntervalStillCountsAsOne() {
        // rounding DOWN here would leave the key one leaf short — the exact failure being prevented
        assertEquals(10 + 1, KeyUses.restoreTarget(10, 1000, 1000 + 1, 0));
        assertEquals(10 + 1, KeyUses.restoreTarget(10, 1000, 1000 + REFRESH, 0));
        assertEquals(10 + 2, KeyUses.restoreTarget(10, 1000, 1000 + REFRESH + 1, 0));
    }

    @Test public void slackIsAddedOnTop() {
        assertEquals(347 + 50, KeyUses.restoreTarget(347, 412_006, 412_006, 50));
    }

    @Test public void aMissingOrStaleBlockStampNeverSubtracts() {
        // an unstamped backup (atblock 0) contributes no gap rather than a negative one
        assertEquals(347, KeyUses.restoreTarget(347, 0, 500_000, 0));
        // a node that has somehow gone backwards must not reduce the target either
        assertEquals(347, KeyUses.restoreTarget(347, 500_000, 400_000, 0));
    }

    @Test public void negativeInputsAreClamped() {
        assertEquals(0, KeyUses.restoreTarget(-5, 0, 0, 0));
        assertEquals(7, KeyUses.restoreTarget(0, 0, 0, 7));
    }

    @Test public void theTargetIsNeverBelowWhatWasRecorded() {
        // the load-bearing invariant, across a spread of inputs
        int[] uses = {0, 1, 58, 347, 5000};
        int[] backupBlocks = {0, 1, 412_006};
        int[] nows = {0, 1, 412_006, 998_110};
        for (int u : uses) for (int b : backupBlocks) for (int n : nows) {
            assertTrue("target must never fall below the recorded count",
                    KeyUses.restoreTarget(u, b, n, 0) >= u);
        }
    }

    // ---- reading a key's count out of the node's reply ----

    private static JSONObject keysReply(String pubkey, int uses) {
        try {
            JSONObject k = new JSONObject().put("publickey", pubkey).put("uses", uses).put("maxuses", 262144);
            JSONObject resp = new JSONObject().put("keys", new JSONArray().put(k)).put("total", 1);
            return new JSONObject().put("status", true).put("response", resp);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void readsTheCountForTheRequestedKey() {
        assertEquals(Integer.valueOf(347), KeyUses.extractUses(keysReply("0xABC", 347), "0xABC"));
    }

    @Test public void matchesTheKeyCaseInsensitively() {
        assertEquals(Integer.valueOf(12), KeyUses.extractUses(keysReply("0xABC", 12), "0xabc"));
    }

    @Test public void reportsUnknownRatherThanZeroWhenTheKeyIsAbsent() {
        // a node that doesn't hold the key must NOT read as "0 uses" — that would resume at leaf 0
        assertNull(KeyUses.extractUses(keysReply("0xOTHER", 99), "0xABC"));
        assertNull(KeyUses.extractUses(new JSONObject(), "0xABC"));
    }

    @Test public void handlesTheBareArrayResponseShape() {
        try {
            JSONObject k = new JSONObject().put("publickey", "0xABC").put("uses", 5);
            JSONObject j = new JSONObject().put("status", true).put("response", new JSONArray().put(k));
            assertEquals(Integer.valueOf(5), KeyUses.extractUses(j, "0xABC"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * "Finish before upgrading": the Trading Account is offered only when nothing tied to the NODE's keys is left.
 *
 * THE BUG THIS PINS (review, 10 Nov). The gate looked at ACTIVE recipes and at a close/migrate posted in the last
 * 10 minutes. A recipe is retired when its close is POSTED, not when it lands — so a close that never landed was
 * invisible after 10 minutes, the account was offered, and from then on the node could never be asked to sign
 * with that pool's owner key again: the pool was stranded. A retired node-key recipe now holds the gate until
 * {@link ClosedPoolCheck} proves it empty on the node AND the unpruned MegaMMR.
 */
public class LegacyPoolGateTest {

    private static Pool pool(char c, boolean retired, boolean drained, boolean accountOwned) {
        Pool p = new Pool();
        p.address = "0x" + String.join("", Collections.nCopies(64, String.valueOf(c)));
        p.retired = retired; p.drained = drained; p.accountOwned = accountOwned;
        return p;
    }

    @Test public void aRetiredPoolWhoseCloseMayNotHaveLandedHoldsTheGate() {
        List<Pool> recipes = Collections.singletonList(pool('a', true, false, false));
        // no close "just posted" any more (the 10-minute window is long gone) — the recipe alone must hold the gate
        assertTrue(PandaPoolsAccount.nodeKeyWork(recipes, false, false, false));
    }

    @Test public void aRetiredPoolProvenEmptyReleasesTheGate() {
        assertFalse(PandaPoolsAccount.nodeKeyWork(Collections.singletonList(pool('a', true, true, false)), false, false, false));
    }

    @Test public void aLivePoolHoldsTheGateEvenIfOnceMarkedDrained() {
        // drained only means anything while retired (un-retiring clears it on disk; the rule must not trust it either)
        assertTrue(PandaPoolsAccount.nodeKeyWork(Collections.singletonList(pool('a', false, true, false)), false, false, false));
    }

    @Test public void anAccountOwnedRecipeIsNeverNodeKeyWork() {
        // a device transfer carries recipes but not the account: they must not shut the new phone out of the account
        List<Pool> recipes = Arrays.asList(pool('a', false, false, true), pool('b', true, false, true));
        assertFalse(PandaPoolsAccount.nodeKeyWork(recipes, false, false, false));
    }

    @Test public void undrainedListsOnlyNodeKeyRecipesNotProvenEmpty() {
        List<Pool> recipes = Arrays.asList(pool('a', false, false, false), pool('b', true, false, false),
                pool('c', true, true, false), pool('d', true, false, true));
        List<Pool> u = PandaPoolsAccount.undrained(recipes);
        assertEquals(2, u.size());
        assertEquals(recipes.get(0).address, u.get(0).address);
        assertEquals(recipes.get(1).address, u.get(1).address);
    }

    @Test public void otherOpenNodeWorkStillHoldsTheGate() {
        List<Pool> none = Collections.emptyList();
        assertFalse(PandaPoolsAccount.nodeKeyWork(none, false, false, false));
        assertTrue(PandaPoolsAccount.nodeKeyWork(none, true, false, false));
        assertTrue(PandaPoolsAccount.nodeKeyWork(none, false, true, false));
        assertTrue(PandaPoolsAccount.nodeKeyWork(none, false, false, true));
    }

    // ---- the marks survive a re-record exactly as "retired" does ----

    private static Pool recipe() {
        Pool p = pool('a', false, false, false);
        p.opk = "0x" + String.join("", Collections.nCopies(64, "b"));
        p.oadr = "0x" + String.join("", Collections.nCopies(64, "c"));
        p.tok = "0x" + String.join("", Collections.nCopies(64, "d"));
        p.kmin = "100";
        p.covenantScript = "RETURN TRUE";
        return p;
    }

    @Test public void drainedAndAccountMarksSurviveAReRecord() throws Exception {
        String previous = new JSONObject().put("retired", true).put("drained", true).put("acct", true).toString();
        JSONObject o = OwnPoolStore.mergeRecord(recipe(), previous);   // a routine refresh carries neither flag
        assertTrue(o.optBoolean("retired"));
        assertTrue(o.optBoolean("drained"));
        assertTrue(o.optBoolean("acct"));
    }

    @Test public void drainedIsNeverStoredOnALiveRecipe() throws Exception {
        Pool p = recipe(); p.drained = true;   // not retired
        assertFalse(OwnPoolStore.mergeRecord(p, "").optBoolean("drained"));
    }

    @Test public void theOwningAccountIsCarriedAcrossARerecord() throws Exception {
        String previous = new JSONObject().put("acct", true).put("acct_fp", "C3BA14CD8AF95045").put("acct_mode", "phrase").toString();
        JSONObject o = OwnPoolStore.mergeRecord(recipe(), previous);
        assertEquals("C3BA14CD8AF95045", o.optString("acct_fp"));
        assertEquals("phrase", o.optString("acct_mode"));
    }
}

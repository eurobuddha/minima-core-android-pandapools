package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Dexicon hand-over refuses whenever uninstalling PandaPools could lose a pool, a withdrawal or a payout. */
public class HandoverTest {

    private static String h(String c) { return "0x" + String.join("", Collections.nCopies(64, c)); }

    @Test public void onlyDexiconMayAsk() {
        assertTrue(Handover.callerAllowed("com.eurobuddha.dexicon"));
        assertFalse(Handover.callerAllowed(null));
        assertFalse(Handover.callerAllowed("com.eurobuddha.dexicon.evil"));
        assertFalse(Handover.callerAllowed("com.eurobuddha.pandapools"));
    }

    @Test public void aLivePoolRefusesWithTheAgreedWordingAndTheFullAddress() {
        Pool p = new Pool(); p.address = h("4");
        Handover.Check c = Handover.pools(Collections.singletonList(p));
        assertFalse(c.ok);
        assertEquals("Dexicon can't take over a live pool yet. Keep PandaPools until the Dexicon update that brings pools; "
                + "your pool keeps running here.", c.reason);
        assertTrue(c.detail.contains(h("4")));
        assertTrue(Handover.pools(Collections.emptyList()).ok);
    }

    @Test public void onlyTerminalWithdrawalsPass() {
        for (ExitStore.State s : ExitStore.State.values()) {
            ExitStore.Record r = new ExitStore.Record(); r.address = h("5"); r.state = s.name();
            boolean settled = s == ExitStore.State.DONE || s == ExitStore.State.SKIPPED || s == ExitStore.State.FAILED;
            Handover.Check c = Handover.withdrawals(Collections.singletonList(r));
            assertEquals(s.name(), settled, c.ok);
            if (!settled) assertTrue(c.detail.contains(h("5")));
        }
    }

    @Test public void anyTrackedPayoutAddressRefuses() {
        PendingCollect.Entry e = new PendingCollect.Entry(); e.oadr = h("6"); e.status = "RETRYING";
        Handover.Check c = Handover.payouts(Collections.singletonList(e));
        assertFalse(c.ok);
        assertTrue(c.detail.contains(h("6")));
        assertTrue(Handover.payouts(Collections.emptyList()).ok);
    }

    @Test public void aSwapInFlightOrConfirmingRefuses() {
        assertTrue(Handover.swaps(false, false).ok);
        assertFalse(Handover.swaps(true, false).ok);
        assertFalse(Handover.swaps(false, true).ok);
    }

    @Test public void refusalPutsTheLivePoolReasonFirstAndOnce() {
        Pool p = new Pool(); p.address = h("4");
        String reason = Handover.refusal(Arrays.asList(Handover.pools(Collections.singletonList(p)),
                Handover.swaps(true, false), Handover.pools(Collections.singletonList(p))));
        assertTrue(reason.startsWith(Handover.LIVE_POOL));
        assertEquals(reason.indexOf(Handover.LIVE_POOL), reason.lastIndexOf(Handover.LIVE_POOL));
        assertTrue(reason.contains("swap"));
    }

    private static Context ctx(boolean handedOver) {
        Context c = mock(Context.class);
        SharedPreferences sp = mock(SharedPreferences.class);
        when(c.getSharedPreferences(anyString(), anyInt())).thenReturn(sp);
        when(sp.getBoolean(anyString(), anyBoolean())).thenReturn(handedOver);
        return c;
    }

    @Test public void dexiconMustAlsoCarryOurSigner() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.checkSignatures("com.eurobuddha.dexicon", "com.eurobuddha.pandapools")).thenReturn(PackageManager.SIGNATURE_MATCH);
        assertTrue(Handover.callerAllowed("com.eurobuddha.dexicon", pm, "com.eurobuddha.pandapools"));
        when(pm.checkSignatures("com.eurobuddha.dexicon", "com.eurobuddha.pandapools")).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        assertFalse(Handover.callerAllowed("com.eurobuddha.dexicon", pm, "com.eurobuddha.pandapools"));
        assertFalse(Handover.callerAllowed("com.eurobuddha.evil", pm, "com.eurobuddha.pandapools"));
        assertFalse(Handover.callerAllowed("com.eurobuddha.dexicon", null, "com.eurobuddha.pandapools"));
    }

    /** The race the confirm re-check closes: a create that started before the tap is seen as running. */
    @Test public void aCreateOrSwapStartedBeforeTheTapBlocksTheConfirm() {
        assertFalse(Handover.busy());
        assertTrue(Handover.begin(ctx(false)));            // create/swap started while not handed over
        try {
            assertTrue(Handover.busy());
            assertFalse(Handover.running(Handover.busy()).ok);
            // the tap sets the flag, then re-checks: the running create fails the run → refuse, never OK
            assertEquals(Handover.Outcome.REFUSE, Handover.outcome(true, true, false));
        } finally { Handover.end(); }
        assertFalse(Handover.busy());
        assertTrue(Handover.running(false).ok);
    }

    @Test public void nothingStartsOnceHandedOver() {
        assertFalse(Handover.begin(ctx(true)));
        assertFalse(Handover.busy());                      // a refused begin leaves nothing marked
    }

    @Test public void confirmAnswersOkOnlyOnAFreshPassingRun() {
        assertEquals(Handover.Outcome.OK, Handover.outcome(true, true, true));
        assertEquals(Handover.Outcome.REFUSE, Handover.outcome(true, true, false));
        // not handed over and not confirming: just show the screen, even when everything passes
        assertEquals(Handover.Outcome.SHOW, Handover.outcome(false, false, true));
        assertEquals(Handover.Outcome.SHOW, Handover.outcome(false, false, false));
    }

    /** Dexicon asks again before uninstalling: already handed over + every check passing = OK without a tap. */
    @Test public void alreadyHandedOverReAnswersOkOnlyIfChecksStillPass() {
        assertEquals(Handover.Outcome.OK, Handover.outcome(false, true, true));
        assertEquals(Handover.Outcome.SHOW, Handover.outcome(false, true, false));
    }
}

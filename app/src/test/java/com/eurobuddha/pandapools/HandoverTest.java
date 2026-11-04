package com.eurobuddha.pandapools;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}

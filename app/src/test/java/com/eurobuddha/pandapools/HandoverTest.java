package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private static Pool pool(String c) { Pool p = new Pool(); p.address = h(c); return p; }

    @Test public void aLivePoolWithoutDexiconPoolsRefusesWithTheAgreedWordingAndTheFullAddress() {
        Handover.Check c = Handover.pools(Collections.singletonList(pool("4")), null, true);
        assertFalse(c.ok);
        assertEquals("Restore your PandaPools backup in Dexicon first, then hand over again.", c.reason);
        assertTrue(c.detail.contains(h("4")));
        assertTrue(Handover.pools(Collections.emptyList(), null, false).ok);
    }

    @Test public void livePoolsAllHeldByDexiconWithACoveringBackupPass() {
        // case-insensitive: Dexicon may send upper-case hex
        String[] dex = { h("4").toUpperCase().replace("0X", "0x"), h("7"), h("8") };
        assertTrue(Handover.pools(Arrays.asList(pool("4"), pool("7")), dex, true).ok);
        // a verified backup covering every pool is still required
        Handover.Check noBackup = Handover.pools(Arrays.asList(pool("4"), pool("7")), dex, false);
        assertFalse(noBackup.ok);
        assertEquals(Handover.NO_BACKUP, noBackup.reason);
    }

    @Test public void aPoolMissingFromDexiconRefusesWithItsFullAddress() {
        Handover.Check c = Handover.pools(Arrays.asList(pool("4"), pool("9")), new String[]{ h("4") }, true);
        assertFalse(c.ok);
        assertTrue(c.reason.startsWith("Restore your PandaPools backup in Dexicon first"));
        assertTrue(c.reason.contains(h("9")));            // the full 66-char address, in the reason Dexicon receives
        assertFalse(c.reason.contains(h("4")));
        assertTrue(c.detail.contains(h("9")));
        assertFalse(Handover.pools(Collections.singletonList(pool("4")), new String[0], true).ok);
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
        String reason = Handover.refusal(Arrays.asList(Handover.pools(Collections.singletonList(p), null, true),
                Handover.swaps(true, false), Handover.pools(Collections.singletonList(p), null, true)));
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

    /** A real-enough prefs store: the hand-over flag and the handed-over pool set, as committed. */
    private static Context store() {
        Map<String, Object> m = new HashMap<>();
        Context c = mock(Context.class);
        SharedPreferences sp = mock(SharedPreferences.class);
        SharedPreferences.Editor e = mock(SharedPreferences.Editor.class);
        when(c.getSharedPreferences(anyString(), anyInt())).thenReturn(sp);
        when(sp.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> m.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(sp.getLong(anyString(), anyLong())).thenAnswer(i -> m.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(sp.getStringSet(anyString(), any())).thenAnswer(i -> m.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(sp.edit()).thenReturn(e);
        when(e.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return e; });
        when(e.putLong(anyString(), anyLong())).thenAnswer(i -> { m.put(i.getArgument(0), i.getArgument(1)); return e; });
        when(e.putStringSet(anyString(), any())).thenAnswer(i -> { m.put(i.getArgument(0), new HashSet<String>(i.getArgument(1))); return e; });
        when(e.remove(anyString())).thenAnswer(i -> { m.remove(i.getArgument(0)); return e; });
        when(e.commit()).thenReturn(true);
        return c;
    }

    @Test public void okStopsOurKeeperForTheHandedOverPools() {
        Context c = store();
        assertTrue(Handover.set(c, true, 1L));
        String[] out = Handover.stopKeeping(c, Arrays.asList(pool("4"), pool("7")));
        assertEquals(new HashSet<>(Arrays.asList(h("4"), h("7"))), new HashSet<>(Arrays.asList(out)));
        assertTrue(Handover.keptByDexicon(c, h("4").toUpperCase().replace("0X", "0x")));
        assertFalse(Handover.keptByDexicon(c, h("5")));
        // the keeper's pool list drops them
        List<Pool> left = Handover.keepable(c, Arrays.asList(pool("4"), pool("5"), pool("7")));
        assertEquals(1, left.size());
        assertEquals(h("5"), left.get(0).address);
    }

    @Test public void keeperNeverRefreshesOrReannouncesAHandedOverPool() {
        Context c = store();
        Handover.stopKeeping(c, Collections.singletonList(pool("4")));
        NodeApi node = mock(NodeApi.class);
        when(node.context()).thenReturn(c);
        PoolManager mgr = new PoolManager(node);
        String[] failed = new String[2];
        mgr.refresh(pool("4"), new PoolManager.Result() {
            @Override public void onPosted(String txpowid) { throw new AssertionError("refreshed a pool Dexicon keeps"); }
            @Override public void onFailed(String message) { failed[0] = message; }
        });
        mgr.reannounce(pool("4"), new PoolManager.Result() {
            @Override public void onPosted(String txpowid) { throw new AssertionError("re-announced a pool Dexicon keeps"); }
            @Override public void onFailed(String message) { failed[1] = message; }
        });
        assertEquals(PoolManager.KEPT_BY_DEXICON, failed[0]);
        assertEquals(PoolManager.KEPT_BY_DEXICON, failed[1]);
        verify(node, never()).cmd(anyString(), any());
    }

    @Test public void takeBackWithHandedOverPoolsNeedsTheSecondConfirmAndResumesKeeping() {
        Context c = store();
        Handover.set(c, true, 1L);
        Handover.stopKeeping(c, Collections.singletonList(pool("4")));
        assertEquals(2, Handover.takeBackConfirms(c));
        assertFalse(Handover.takeBack(c, 0));
        assertFalse(Handover.takeBack(c, 1));             // the warning alone is not enough
        assertTrue(Handover.active(c));
        assertTrue(Handover.keptByDexicon(c, h("4")));
        assertTrue(Handover.takeBack(c, 2));              // explicit second confirmation
        assertFalse(Handover.active(c));
        assertFalse(Handover.keptByDexicon(c, h("4")));   // keeping resumes
        assertEquals(1, Handover.keepable(c, Collections.singletonList(pool("4"))).size());
        // no pools handed over: take back stays one tap
        Handover.set(c, true, 1L);
        assertEquals(0, Handover.takeBackConfirms(c));
        assertTrue(Handover.takeBack(c, 0));
    }

    /** Already handed over: Dexicon's re-ask answers OK only while every live pool is still in dexicon_pools. */
    @Test public void reAnswerWithPoolsStillRequiresEveryPoolInDexicon() {
        List<Pool> live = Arrays.asList(pool("4"), pool("7"));
        boolean pass = Handover.pools(live, new String[]{ h("4"), h("7") }, true).ok;
        assertEquals(Handover.Outcome.OK, Handover.outcome(false, true, pass));
        boolean missing = Handover.pools(live, new String[]{ h("4") }, true).ok;
        assertEquals(Handover.Outcome.SHOW, Handover.outcome(false, true, missing));
        boolean none = Handover.pools(live, null, true).ok;
        assertEquals(Handover.Outcome.SHOW, Handover.outcome(false, true, none));
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

    /** Fix 4: only a raw stored DONE / SKIPPED / FAILED is settled; missing, unknown or unreadable rows block, address in full. */
    @Test public void anUnknownOrMissingExitStateBlocksTheHandOver() {
        for (String raw : new String[]{ null, "", "BOGUS", "done", " DONE" }) {
            ExitStore.Record r = new ExitStore.Record(); r.address = h("5"); r.state = raw;
            Handover.Check c = Handover.withdrawals(Collections.singletonList(r));
            assertFalse(String.valueOf(raw), c.ok);
            assertTrue(c.detail.contains(h("5")));
        }
        // An unreadable stored row is listed (never dropped) and blocks; a row without a state stays stateless.
        Map<String, Object> rows = new HashMap<>();
        rows.put(h("5"), "not json");
        rows.put(h("6"), "{\"addr\":\"" + h("6") + "\"}");
        Context c = mock(Context.class);
        SharedPreferences sp = mock(SharedPreferences.class);
        when(c.getSharedPreferences(anyString(), anyInt())).thenReturn(sp);
        when(sp.getAll()).thenAnswer(i -> rows);
        when(sp.getString(anyString(), any())).thenAnswer(i -> (String) rows.get(i.getArgument(0)));
        List<ExitStore.Record> all = ExitStore.all(c);
        assertEquals(2, all.size());
        Handover.Check chk = Handover.withdrawals(all);
        assertFalse(chk.ok);
        assertTrue(chk.detail.contains(h("5")));
        assertTrue(chk.detail.contains(h("6")));
        assertEquals(ExitStore.State.FAILED, ExitStore.get(c, h("6")).stateOf());   // display mapping unchanged
    }

    /** Fix 2: a pool Dexicon holds that has no recipe here is kept by Dexicon; recipes here are not. */
    @Test public void keptByDexiconIsHandedOverPlusPoolsWithNoRecipeHere() {
        Set<String> handed = new HashSet<>(Collections.singletonList(h("4")));
        String[] dex = { h("4"), h("7").toUpperCase().replace("0X", "0x"), h("9"), null, "  " };
        String[] out = Handover.keptByDexicon(handed, dex, Arrays.asList(pool("4"), pool("9")));
        assertEquals(new HashSet<>(Arrays.asList(h("4"), h("7"))), new HashSet<>(Arrays.asList(out)));
        assertEquals(0, Handover.keptByDexicon(new HashSet<>(), null, Collections.emptyList()).length);
    }

    @Test public void stateProviderAnswersOnlyDexiconWithOurSigner() {
        PackageManager pm = mock(PackageManager.class);
        String self = "com.eurobuddha.pandapools";
        when(pm.checkSignatures("com.eurobuddha.dexicon", self)).thenReturn(PackageManager.SIGNATURE_MATCH);
        assertTrue(HandoverStateProvider.allowed(new String[]{ "com.eurobuddha.dexicon" }, pm, self));
        assertTrue(HandoverStateProvider.allowed(new String[]{ "x.shared", "com.eurobuddha.dexicon" }, pm, self));
        assertFalse(HandoverStateProvider.allowed(null, pm, self));
        assertFalse(HandoverStateProvider.allowed(new String[]{ "com.eurobuddha.evil" }, pm, self));
        assertFalse(HandoverStateProvider.allowed(new String[]{ self }, pm, self));
        when(pm.checkSignatures("com.eurobuddha.dexicon", self)).thenReturn(PackageManager.SIGNATURE_NO_MATCH);
        assertFalse(HandoverStateProvider.allowed(new String[]{ "com.eurobuddha.dexicon" }, pm, self));
        assertFalse(HandoverStateProvider.allowed(new String[]{ "com.eurobuddha.dexicon" }, null, self));
    }

    @Test public void stateProviderRowsNameEveryActivePoolInFull() {
        Pool upper = pool("a"); upper.address = h("a").toUpperCase().replace("0X", "0x");
        List<String[]> rows = HandoverStateProvider.rows(Arrays.asList(pool("4"), upper, pool("4"), new Pool()),
                new HashSet<>(Collections.singletonList(h("a"))));
        assertEquals(2, rows.size());
        assertEquals(h("4"), rows.get(0)[0]);
        assertEquals("kept_here", rows.get(0)[1]);
        assertEquals(h("a"), rows.get(1)[0]);                 // lower-case, all 66 characters
        assertEquals(66, rows.get(1)[0].length());
        assertEquals("handed_over", rows.get(1)[1]);
        assertEquals("com.eurobuddha.pandapools.handover", HandoverStateProvider.AUTHORITY);
        assertEquals(Arrays.asList("address", "state"), Arrays.asList(HandoverStateProvider.COLUMNS));
    }

    /** Fix 1: migrate creates a new pool, so it is refused once handed over; close/deposit are marked, never refused. */
    @Test public void migrateIsRefusedHandedOverAndOwnerMovesAreMarkedUntilTheyAnswer() {
        Context c = store();
        Handover.set(c, true, 1L);
        NodeApi node = mock(NodeApi.class);
        when(node.context()).thenReturn(c);
        PoolManager mgr = new PoolManager(node);
        String[] got = new String[3];
        mgr.migrate(pool("4"), java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, new PoolManager.CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) { throw new AssertionError("migrated while handed over"); }
            @Override public void onFailed(String message) { got[0] = message; }
        });
        assertEquals(Handover.BLOCKED, got[0]);
        // close / deposit still run handed over (own funds): an unfunded pool fails on its own terms, and the mark ends
        mgr.close(pool("4"), new PoolManager.Result() {
            @Override public void onPosted(String txpowid) { throw new AssertionError(); }
            @Override public void onFailed(String message) { got[1] = message; }
        });
        mgr.deposit(pool("4"), java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, new PoolManager.Result() {
            @Override public void onPosted(String txpowid) { throw new AssertionError(); }
            @Override public void onFailed(String message) { got[2] = message; }
        });
        assertFalse(Handover.BLOCKED.equals(got[1]));
        assertFalse(Handover.BLOCKED.equals(got[2]));
        verify(node, never()).cmd(anyString(), any());
        // a marked move holds the hand-over off until its result, and ends exactly once
        Handover.mark();
        boolean[] before = { Handover.busy() };
        PoolManager.Result r = PoolManager.ending(new PoolManager.Result() {
            @Override public void onPosted(String txpowid) {}
            @Override public void onFailed(String message) {}
        });
        assertTrue(before[0]);
        assertFalse(Handover.running(Handover.busy()).ok);
        r.onFailed("x");
        r.onFailed("again");                                  // a second answer must not end someone else's mark
        assertTrue(Handover.begin(ctx(false)));
        assertTrue(Handover.busy());
        Handover.end();
        assertFalse(Handover.busy());
    }

    /** Review fix 1: a retired recipe (close merely posted, or put away) is still listed, kept_here unless handed over. */
    @Test public void stateProviderListsRetiredRecipesToo() {
        Map<String, Object> m = new HashMap<>();
        m.put(h("4"), "{\"addr\":\"" + h("4") + "\",\"script\":\"RETURN TRUE\",\"retired\":true}");
        m.put(h("7"), "{\"addr\":\"" + h("7") + "\",\"script\":\"RETURN TRUE\"}");
        Context c = mock(Context.class);
        SharedPreferences sp = mock(SharedPreferences.class);
        when(c.getSharedPreferences(anyString(), anyInt())).thenReturn(sp);
        when(sp.getAll()).thenAnswer(i -> m);
        when(sp.getStringSet(anyString(), any())).thenReturn(new HashSet<>(Collections.singletonList(h("7"))));
        assertEquals(1, OwnPoolStore.active(c).size());
        Map<String, String> got = new HashMap<>();
        for (String[] r : HandoverStateProvider.rows(c)) got.put(r[0], r[1]);
        assertEquals(2, got.size());
        assertEquals("kept_here", got.get(h("4")));            // retired, but PandaPools' keeper still keeps it
        assertEquals("handed_over", got.get(h("7")));
    }

    /** Review fix 2: a RuntimeException before the callback answers once, plainly, and never leaks the RUNNING mark. */
    @Test public void aThrowBeforeTheAnswerEndsTheMarkExactlyOnce() {
        Context c = store();
        NodeApi node = mock(NodeApi.class);
        when(node.context()).thenReturn(c);
        org.mockito.Mockito.doThrow(new IllegalStateException("node gone")).when(node).cmd(anyString(), any());
        PoolManager mgr = new PoolManager(node);
        int[] answers = new int[1];
        String[] last = new String[1];
        PoolManager.Result r = new PoolManager.Result() {
            @Override public void onPosted(String txpowid) { throw new AssertionError(); }
            @Override public void onFailed(String message) { answers[0]++; last[0] = message; }
        };
        PoolManager.CreateResult cr = new PoolManager.CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) { throw new AssertionError(); }
            @Override public void onFailed(String message) { answers[0]++; last[0] = message; }
        };
        assertFalse(Handover.busy());
        mgr.deposit(null, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, r);
        mgr.close(null, r);
        mgr.migrate(null, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, cr);
        mgr.createPool(h("b"), 8, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, cr);   // newaddress throws
        assertEquals(4, answers[0]);
        assertTrue(last[0], last[0].contains("node gone"));
        assertFalse(Handover.busy());
        assertTrue(Handover.running(Handover.busy()).ok);
    }
}

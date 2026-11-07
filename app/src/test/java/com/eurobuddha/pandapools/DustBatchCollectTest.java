package com.eurobuddha.pandapools;

import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/** Review 7 Nov (D-3): dust at the public payout address no longer pauses collection for good. */
public class DustBatchCollectTest {
    @Test public void aSmallPayoutAddressIsListedWhole() { assertEquals("", PoolManager.ownerCoinsLimit(8, false)); }
    @Test public void aDustFloodedAddressIsCollectedInBoundedBatches() {
        assertEquals(" max:8", PoolManager.ownerCoinsLimit(9, false));
        assertEquals(" max:8", PoolManager.ownerCoinsLimit(5000, false));
    }
    @Test public void theRecoveryContinuationStillPausesBecauseItNeedsEveryCoin() { assertNull(PoolManager.ownerCoinsLimit(9, true)); }

    /** D-3-1: each batch asks for ONE owned token (MINIMA, then each own pool's token at this payout address, in turn), so
     *  foreign-token junk never fills the max:8 window. */
    @Test public void batchesTakeTheOwnedTokensInTurn() {
        CollectConfirmationTest f = new CollectConfirmationTest(); f.setup();
        String tok = "0x" + "ab".repeat(32), other = "0x" + "cd".repeat(32);
        Pool mine = new Pool(); mine.oadr = CollectConfirmationTest.ADDRESS; mine.tok = tok;
        Pool elsewhere = new Pool(); elsewhere.oadr = "0x" + "99".repeat(32); elsewhere.tok = other;
        try (MockedStatic<OwnPoolStore> store = mockStatic(OwnPoolStore.class)) {
            store.when(() -> OwnPoolStore.all(f.context)).thenReturn(Arrays.asList(mine, elsewhere));
            PoolManager pm = new PoolManager(f.node);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 4; i++) seen.add(pm.batchToken(CollectConfirmationTest.ADDRESS));
            assertEquals(new HashSet<>(Arrays.asList("0x00", tok)), seen);
        }
    }
}

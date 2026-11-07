package com.eurobuddha.pandapools;

import org.junit.Test;
import static org.junit.Assert.*;

/** Review 7 Nov (D-3): dust at the public payout address no longer pauses collection for good. */
public class DustBatchCollectTest {
    @Test public void aSmallPayoutAddressIsListedWhole() { assertEquals("", PoolManager.ownerCoinsLimit(8, false)); }
    @Test public void aDustFloodedAddressIsCollectedInBoundedBatches() {
        assertEquals(" max:8", PoolManager.ownerCoinsLimit(9, false));
        assertEquals(" max:8", PoolManager.ownerCoinsLimit(5000, false));
    }
    @Test public void theRecoveryContinuationStillPausesBecauseItNeedsEveryCoin() { assertNull(PoolManager.ownerCoinsLimit(9, true)); }
}

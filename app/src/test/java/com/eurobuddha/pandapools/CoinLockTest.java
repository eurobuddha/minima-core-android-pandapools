package com.eurobuddha.pandapools;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The reservation that stops parallel builders selecting the same coin.
 *
 * This is the second half of the key-reuse fix. PoolRefresher posts up to 8 refreshes in one loop and
 * PoolManager.selectCoins sorted largest-first, stopping at the first coin that covered the amount
 * needed — which for beacon dust is always the single largest wallet coin. So every parallel builder
 * picked the same coin, at the same address, owned by the same key, and signed simultaneously.
 */
public class CoinLockTest {

    private static Coin coin(String id, String amount) {
        JSONObject o = new JSONObject();
        try {
            o.put("coinid", id);
            o.put("address", "0xADDR");
            o.put("tokenid", "0x00");
            o.put("amount", amount);
        } catch (Exception e) { throw new RuntimeException(e); }
        return Coin.from(o);
    }

    @Before public void clean() {
        // no reset hook by design (a static reservation store outlives any one view); release explicitly
        CoinLock.release(Arrays.asList(coin("0xA", "1"), coin("0xB", "2"), coin("0xC", "3")));
    }

    @Test public void anUnreservedCoinIsFree() {
        assertFalse(CoinLock.isReserved("0xA"));
        assertFalse("null must not blow up", CoinLock.isReserved(null));
    }

    @Test public void reservingHidesACoinFromEveryOtherBuilder() {
        CoinLock.reserve(Arrays.asList(coin("0xA", "100")));
        assertTrue(CoinLock.isReserved("0xA"));
        assertFalse("a different coin is unaffected", CoinLock.isReserved("0xB"));
    }

    @Test public void releasingFreesItAgain() {
        List<Coin> c = Arrays.asList(coin("0xA", "100"));
        CoinLock.reserve(c);
        CoinLock.release(c);
        assertFalse(CoinLock.isReserved("0xA"));
    }

    /**
     * The regression test for the actual bug: N builders each take the largest coin that isn't already
     * claimed. Before the reservation they all took the same one.
     */
    @Test public void parallelBuildersEachGetTheirOwnCoin() {
        List<Coin> wallet = new ArrayList<>(Arrays.asList(
                coin("0xBIG", "1000"), coin("0xMID", "500"), coin("0xSMALL", "10")));

        List<String> picked = new ArrayList<>();
        for (int builder = 0; builder < 3; builder++) {
            for (Coin c : wallet) {                       // largest-first, as selectCoins sorts them
                if (CoinLock.isReserved(c.coinid)) continue;
                CoinLock.reserve(Arrays.asList(c));
                picked.add(c.coinid);
                break;
            }
        }

        assertEquals(3, picked.size());
        assertEquals("no two builders may hold the same coin",
                3, new java.util.HashSet<>(picked).size());
        assertEquals(Arrays.asList("0xBIG", "0xMID", "0xSMALL"), picked);

        CoinLock.release(wallet);
    }

    @Test public void aBuilderFindsNothingWhenEveryCoinIsClaimed() {
        List<Coin> wallet = Arrays.asList(coin("0xA", "5"), coin("0xB", "4"));
        CoinLock.reserve(wallet);
        boolean found = false;
        for (Coin c : wallet) if (!CoinLock.isReserved(c.coinid)) found = true;
        assertFalse("better to find no funding than to double-spend and double-sign", found);
        CoinLock.release(wallet);
    }
}

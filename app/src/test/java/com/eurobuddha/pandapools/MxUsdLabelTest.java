package com.eurobuddha.pandapools;

import static org.junit.Assert.*;

import org.junit.Test;

/** The Minima dollar token is MxUSD everywhere it is shown, though its on-chain name (and old stored rows) say USDT. */
public class MxUsdLabelTest {
    @Test public void oldActivityRowsShowMxUsd() {
        ActivityLog.Entry e = new ActivityLog.Entry(ActivityLog.SWAP, "−100 MINIMA  ·  +0.30614242 USDT", "0x01", 1, 0L, false, null);
        assertEquals("−100 MINIMA  ·  +0.30614242 MxUSD", e.display());
        assertEquals("+1 MxUSD", new ActivityLog.Entry(ActivityLog.SWAP, "+1 mxUSDT", "0x01", 1, 0L, false, null).display());
        assertEquals("unchanged", "−5 MINIMA  ·  +1 FOO", new ActivityLog.Entry(ActivityLog.SWAP, "−5 MINIMA  ·  +1 FOO", "0x01", 1, 0L, false, null).display());
    }

    @Test public void historyRowNamesTheTokenById() {
        HistoryEntry h = new HistoryEntry();
        h.tokenid = Util.MXUSD_TOKENID; h.tokenName = "USDT";   // what the node's token metadata stored
        assertEquals("MxUSD", h.tokenLabel());
        h.tokenid = "0x00"; assertEquals("Minima", h.tokenLabel());
    }
}

package com.eurobuddha.pandapools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Review 7 Nov (R2-4): a pool CLOSED on another wallet (e.g. on PandaBear before moving to BlackBear) must not make every
 * backup on this wallet unverifiable forever — while every LIVE pool's owner key must still be held here.
 */
public class BackupOwnersTest {
    static String h(String c) { StringBuilder s = new StringBuilder("0x"); for (int i = 0; i < 64; i++) s.append(c); return s.toString(); }
    static final String MINE = h("1"), OTHER = h("2");

    static JSONObject keys() throws Exception {
        return new JSONObject().put("status", true).put("response", new JSONArray()
                .put(new JSONObject().put("publickey", MINE).put("uses", 10).put("size", 128).put("depth", 4)));
    }
    static String bundle(JSONObject... pools) throws Exception {
        JSONArray a = new JSONArray(); for (JSONObject p : pools) a.put(p);
        return new JSONObject().put("pools", a).toString();
    }
    static JSONObject pool(String opk, boolean retired) throws Exception {
        JSONObject p = new JSONObject().put("opk", opk).put("opkuses", 5);
        if (retired) p.put("retired", true);
        return p;
    }

    @Test public void aClosedPoolOfAnotherWalletDoesNotBlockTheBackup() throws Exception {
        assertTrue(BlockOwnerRecovery.backupOwnersMatch(bundle(pool(MINE, false), pool(OTHER, true)), keys(), "block"));
    }

    @Test public void aLivePoolOfAnotherWalletStillBlocksIt() throws Exception {
        assertFalse(BlockOwnerRecovery.backupOwnersMatch(bundle(pool(MINE, false), pool(OTHER, false)), keys(), "block"));
    }

    @Test public void myOwnClosedPoolIsStillChecked() throws Exception {
        JSONObject stale = pool(MINE, true).put("opkuses", 50);   // my key is here but behind the recipe's floor
        assertFalse(BlockOwnerRecovery.backupOwnersMatch(bundle(stale), keys(), "block"));
    }
}

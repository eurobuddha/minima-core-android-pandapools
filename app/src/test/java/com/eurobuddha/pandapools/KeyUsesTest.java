package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Reading an owner key's one-time-signature counter out of a node reply.
 *
 * The only thing that matters here is that an UNKNOWN count never reads as zero. A key the node does not
 * hold, or a reply we cannot parse, must come back null: treating it as "0 uses" would resume signing at
 * the first leaf, re-signing leaves the pre-restore node already spent and leaking their private keys.
 *
 * The restoreTarget arithmetic these tests used to cover was deleted in 0.9.48 along with the burn path it
 * fed. Estimating where a restored key should resume cannot be made safe -- see the KeyUses class comment.
 */
public class KeyUsesTest {

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

package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owner-key recovery. A pool's owner key ($OPK) is minted with {@code newaddress} — i.e. a key at index
 * ≥ 64 with {@code default:false}. A SEED-ONLY restore regenerates ONLY the 64 default keys
 * ({@code vault.java} → {@code initDefaultKeys(64)}), so it does NOT bring $OPK back — and without it the
 * node can neither close its own open pools nor spend withdrawn funds sitting at $OADR (both need
 * {@code SIGNEDBY($OPK)} / the $OADR key).
 *
 * But every Minima key is derived deterministically as {@code privseed = hash(seed, keyIndex)} with a
 * sequential index ({@code Wallet.createNewKey}). So re-issuing {@code newaddress} on the SAME seed
 * reproduces the exact historical keys IN ORDER. To recover a missing owner key we simply create new
 * addresses until the wanted pubkey reappears. This is a no-op on a node that already holds the keys (the
 * normal case: the {@code keys} check finds them all and returns immediately) — new keys are only ever
 * created on a genuinely key-missing (restored/wiped) node, which is exactly when we want them back.
 */
public final class OwnerKeyRecovery {

    public interface Cb { void done(int regenerated); }

    /** Safety backstop: cap how many keys we'll create hunting for the wanted owner keys, so a lost/corrupt
     *  recipe can't spin {@code newaddress} forever. Comfortably above any realistic per-node pool count. */
    private static final int MAX_NEW_KEYS = 256;

    private OwnerKeyRecovery() {}

    /** Ensure the node holds every owner pubkey in {@code wantedOpks}, regenerating the missing ones via
     *  ordered {@code newaddress}. Calls back with how many were regenerated (0 = node already had them). */
    public static void ensure(final NodeApi node, final List<String> wantedOpks, final Cb cb) {
        final Set<String> wanted = new LinkedHashSet<>();
        for (String o : wantedOpks) if (o != null && !o.isEmpty()) wanted.add(o.toLowerCase());
        if (wanted.isEmpty()) { cb.done(0); return; }
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                wanted.removeAll(pubkeys(j));
                if (wanted.isEmpty()) { cb.done(0); return; }   // node already holds every owner key — no-op
                hunt(node, wanted, 0, 0, cb);
            }
            @Override public void onError(String m) { cb.done(0); }
        });
    }

    private static void hunt(final NodeApi node, final Set<String> wanted,
                             final int created, final int regenerated, final Cb cb) {
        if (wanted.isEmpty() || created >= MAX_NEW_KEYS) { cb.done(regenerated); return; }
        node.cmd("newaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String pk = r != null ? r.optString("publickey", "").toLowerCase() : "";
                int reg = regenerated + (!pk.isEmpty() && wanted.remove(pk) ? 1 : 0);
                hunt(node, wanted, created + 1, reg, cb);
            }
            @Override public void onError(String m) { cb.done(regenerated); }
        });
    }

    private static Set<String> pubkeys(JSONObject j) {
        Set<String> set = new HashSet<>();
        Object resp = j.opt("response");
        JSONArray arr = null;
        if (resp instanceof JSONArray) arr = (JSONArray) resp;
        else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) set.add(pk.toLowerCase()); }
        }
        return set;
    }
}

package com.eurobuddha.pandapools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Read-only ownership check. Reuses the former hunt's key reader, but never recreates keys.
 * Recreating an old WOTS key at use zero can disclose its signing material. A recipe and an elapsed
 * block estimate cannot establish the highest previously used leaf. Restore the matching node wallet
 * backup, including its signing state, before spending with a missing owner key. */
public final class OwnerKeyRecovery {
    private OwnerKeyRecovery() {}
    /** unavailable contains missing keys AND keys whose presence could not be checked. */
    public interface Cb { void done(int regenerated, List<String> unavailable); }
    public static void ensure(Context ctx, NodeApi node, List<String> wantedOpks, Cb cb) {
        ensure(ctx, node, wantedOpks, kidxFromStore(ctx), cb);
    }
    public static void ensure(Context ctx, NodeApi node, List<String> wantedOpks,
                              Map<String, Integer> kidxByOpk, Cb cb) {
        Set<String> wanted = new LinkedHashSet<>();
        if (wantedOpks != null) for (String key : wantedOpks)
            if (key != null && !key.isEmpty()) wanted.add(key.toLowerCase(Locale.ROOT));
        if (wanted.isEmpty()) { cb.done(0, new ArrayList<>()); return; }
        if (node == null) { cb.done(0, new ArrayList<>(wanted)); return; }
        node.cmd("keys", new NodeApi.Cb() {
            public void onResult(JSONObject reply) { cb.done(0, unavailableKeys(reply, wanted)); }
            public void onError(String message) { cb.done(0, new ArrayList<>(wanted)); }
        });
    }
    static List<String> unavailableKeys(JSONObject reply, Set<String> wanted) {
        Set<String> missing = new LinkedHashSet<>(wanted);
        if (TxPost.truthy(reply, "status") && HuntBudget.rows(reply) != null) missing.removeAll(pubkeys(reply));
        return new ArrayList<>(missing);
    }
    static Map<String, Integer> kidxFromStore(Context ctx) {
        Map<String, Integer> m = new HashMap<>();
        if (ctx == null) return m;
        for (Pool p : OwnPoolStore.all(ctx)) {
            if (p.opk == null || p.opk.isEmpty() || p.kidx < 0) continue;
            String k = p.opk.toLowerCase();
            Integer prev = m.get(k);
            // duplicates (migrate shares one opk) should agree; if they don't, the LARGER index only
            // delays a foreign-proof — the smaller could fake one, which must never happen
            if (prev == null || p.kidx > prev) m.put(k, p.kidx);
        }
        return m;
    }

    private static Set<String> pubkeys(JSONObject j) {
        Set<String> set = new HashSet<>();
        JSONArray arr = HuntBudget.rows(j);
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) set.add(pk.toLowerCase()); }
        }
        return set;
    }
}

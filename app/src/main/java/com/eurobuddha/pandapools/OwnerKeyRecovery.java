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
            public void onResult(JSONObject reply) {
                List<String> unavailable = unavailableKeys(reply, wanted);
                if (ctx != null) for (Pool p : OwnPoolStore.all(ctx)) {
                    String key = p.opk.toLowerCase(Locale.ROOT);
                    if (!wanted.contains(key)) continue;
                    Integer uses = KeyUses.extractUses(reply, p.opk);
                    if (!signingAllowed(p, uses) && !unavailable.contains(key)) unavailable.add(key);
                }
                cb.done(0, unavailable);
            }
            public void onError(String message) { cb.done(0, new ArrayList<>(wanted)); }
        });
    }
    /** Core consolidation chooses inputs itself, so every currently stored owner must be safe. */
    static void checkConsolidation(FundingCoins.Command node, java.util.function.Supplier<List<Pool>> recipes,
                                   java.util.function.Consumer<String> cb) {
        node.run("keys", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                if (!TxPost.truthy(reply, "status") || HuntBudget.rows(reply) == null) { cb.accept("Could not verify current wallet keys. Nothing consolidated."); return; }
                for (Pool p : recipes.get()) if (!signingAllowed(p, KeyUses.extractUses(reply, p.opk))) {
                    cb.accept("Consolidation is paused until all saved pool owner keys have verified current signing state. Use Pool recovery."); return;
                }
                cb.accept(null);
            }
            public void onError(String error) { cb.accept(error); }
        });
    }

    static List<String> unavailableKeys(JSONObject reply, Set<String> wanted) {
        Set<String> missing = new LinkedHashSet<>(wanted);
        if (TxPost.truthy(reply, "status") && HuntBudget.rows(reply) != null) for (String key : pubkeys(reply)) {
            Integer uses = KeyUses.extractUses(reply, key);
            if (uses != null && uses >= 0 && uses < 262144) missing.remove(key);
        }
        return new ArrayList<>(missing);
    }
    static boolean signingAllowed(Pool p, Integer uses) {
        return p != null && !p.signingStateUnverified && !OwnPoolStore.confirmationFailed(p.opk) && uses != null && uses >= 0
                && uses < 262144 && uses >= p.minimumOwnerUses;
    }

    /** Final guard at the signature boundary, including keys selected by publickey:auto.
     * Reads each input by ID (one coin per IPC reply), then the current keys and saved holds.
     * Recipes are supplied lazily so a restore during transaction construction cannot evade this check. */
    static void checkSignature(FundingCoins.Command node, java.util.function.Supplier<List<Pool>> recipes,
                               List<String> ids, String signer, java.util.function.Consumer<String> cb) {
        if ((!"auto".equals(signer) && !FundingCoins.hex(signer)) || ids.isEmpty()
                || ids.size() > FundingCoins.MAX_INPUTS) { cb.accept("Invalid signing inputs."); return; }
        readSigningInputs(node, recipes, ids, signer, 0, new HashSet<>(), cb);
    }

    private static void readSigningInputs(FundingCoins.Command node, java.util.function.Supplier<List<Pool>> recipes,
                                         List<String> ids, String signer, int i, Set<String> addresses,
                                         java.util.function.Consumer<String> cb) {
        if (i == ids.size()) {
            Set<String> signers = new HashSet<>();
            if (!"auto".equals(signer)) signers.add(signer.toLowerCase(Locale.ROOT));
            readScriptKeys(node, recipes, "auto".equals(signer) ? new ArrayList<>(addresses) : Collections.emptyList(), 0, signers, cb);
            return;
        }
        String id = ids.get(i);
        if (!FundingCoins.hex(id)) { cb.accept("Invalid signing input."); return; }
        node.run("coins coinid:" + id, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONArray rows = FundingCoins.rows(j);
                JSONObject c = rows != null && rows.length() == 1 ? rows.optJSONObject(0) : null;
                if (c == null || !id.equalsIgnoreCase(c.optString("coinid")) || !Boolean.FALSE.equals(c.opt("spent"))
                        || !FundingCoins.hex(c.optString("address"))) { cb.accept("Could not verify a current signing input. Nothing signed."); return; }
                addresses.add(c.optString("address").toLowerCase(Locale.ROOT));
                readSigningInputs(node, recipes, ids, signer, i + 1, addresses, cb);
            }
            public void onError(String m) { cb.accept("Could not verify a current signing input. Nothing signed."); }
        });
    }
    private static void readScriptKeys(FundingCoins.Command node, java.util.function.Supplier<List<Pool>> recipes,
                                       List<String> addresses, int i, Set<String> wanted, java.util.function.Consumer<String> cb) {
        if (i == addresses.size()) {
            node.run("keys", new NodeApi.Cb() {
                public void onResult(JSONObject reply) {
                    List<Pool> current = recipes.get();
                    if (!TxPost.truthy(reply, "status") || HuntBudget.rows(reply) == null
                            || !unavailableKeys(reply, wanted).isEmpty()) { cb.accept("Could not verify signing keys. Nothing signed."); return; }
                    for (Pool p : current) if (wanted.contains(p.opk.toLowerCase(Locale.ROOT))
                            && !signingAllowed(p, KeyUses.extractUses(reply, p.opk))) {
                        cb.accept("Owner signing is paused. Verify current wallet signing state in Pool recovery."); return;
                    }
                    cb.accept(null);
                }
                public void onError(String m) { cb.accept("Could not verify signing keys. Nothing signed."); }
            });
            return;
        }
        String address = addresses.get(i);
        node.run("scripts address:" + address, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (TrackHygiene.isNotFound(j)) { readScriptKeys(node, recipes, addresses, i + 1, wanted, cb); return; }
                JSONObject r = j == null ? null : j.optJSONObject("response");
                if (!TxPost.truthy(j, "status") || r == null || !address.equalsIgnoreCase(r.optString("address"))
                        || !(r.opt("simple") instanceof Boolean)) { cb.accept("Could not identify the input signing key. Nothing signed."); return; }
                if (r.optBoolean("simple")) {
                    String pk = r.optString("publickey");
                    if (!FundingCoins.hex(pk)) { cb.accept("Invalid input signing key. Nothing signed."); return; }
                    wanted.add(pk.toLowerCase(Locale.ROOT));
                }
                readScriptKeys(node, recipes, addresses, i + 1, wanted, cb);
            }
            public void onError(String m) { cb.accept("Could not identify the input signing key. Nothing signed."); }
        });
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

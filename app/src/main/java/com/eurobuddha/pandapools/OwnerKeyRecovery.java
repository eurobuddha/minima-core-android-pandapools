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

    /**
     * Why one owner key cannot sign right now. Six genuinely different situations used to share a single
     * message that named only the first — so a user whose recipe was merely quarantined was told to "restore
     * the matching MinimaCore wallet backup", which is the seed restore that CAUSES the key-reuse problem.
     * Each reason now carries its own wording and, where they exist, the actual numbers.
     */
    public enum Reason {
        /** The node's key list was readable and this key is not in it: it belongs to a different wallet. */
        KEY_ABSENT,
        /** The node did not answer, or its reply could not be parsed. We know NOTHING — never advise a restore. */
        NODE_UNREADABLE,
        /** All 262,144 one-time signatures are spent. This key can never sign again. */
        KEY_EXHAUSTED,
        /** The node's counter is BELOW the recipe's recorded floor: signing here would reuse a leaf. */
        COUNTER_REGRESSED,
        /** A previous signing-state confirmation could not be written to disk, so the hold stands. */
        CONFIRMATION_UNSAVED,
        /** An imported recipe whose current signing state has never been confirmed by the owner. */
        SIGNING_QUARANTINED;

        /**
         * How loudly this deserves to be the one line shown when several keys are blocked at once. Deliberately
         * NOT the declaration order, and deliberately not the same as {@link #classify}'s precedence: classify
         * asks "what is true of THIS key" (where an unreadable reply must win, because it means we know
         * nothing), while this asks "of these several keys, which finding must the user act on first".
         * A counter that went backwards is the one that can cost them a key.
         */
        int severity() {
            switch (this) {
                case COUNTER_REGRESSED: return 0;
                case KEY_EXHAUSTED: return 1;
                case KEY_ABSENT: return 2;
                case NODE_UNREADABLE: return 3;
                case CONFIRMATION_UNSAVED: return 4;
                default: return 5;
            }
        }
    }

    /** One blocked owner key, with the evidence needed to explain it without a second lookup. */
    public static final class Blocked {
        public final String opk;
        public final Reason reason;
        /** The node's counter, or null when it could not be read. Null NEVER means zero. */
        public final Integer nodeUses;
        /** The recipe's recorded floor, or -1 when no count was ever recorded. */
        public final int recordedFloor;
        /** The pool's covenant address, when this key was blocked via a stored recipe. */
        public final String address;

        Blocked(String opk, Reason reason, Integer nodeUses, int recordedFloor, String address) {
            this.opk = opk; this.reason = reason; this.nodeUses = nodeUses;
            this.recordedFloor = recordedFloor; this.address = address;
        }

        /** Full identifiers throughout: these strings are what a user copies into a support request. */
        public String message() {
            switch (reason) {
                case KEY_ABSENT:
                    return "This node does not hold this pool's owner key, so the pool belongs to a different "
                            + "wallet. Restore the MinimaCore wallet backup that created this pool, including its "
                            + "signing state. Nothing was posted.\n\nOwner key: " + opk;
                case NODE_UNREADABLE:
                    return "PandaPools could not read this node's keys, so it cannot check this key's signing "
                            + "state at all. Check MinimaCore is running and paired, then try again. Nothing was "
                            + "posted. This says nothing about the key itself — do not change anything about your "
                            + "wallet on the strength of this message.";
                case KEY_EXHAUSTED:
                    return "This owner key has used all 262,144 of its one-time signatures and can never sign "
                            + "again. Nothing was posted.\n\nOwner key: " + opk
                            + (address == null ? "" : "\nPool: " + address);
                case COUNTER_REGRESSED:
                    return "This node says the pool's owner key has used " + nodeUses + " one-time signatures, but "
                            + "your saved recipe recorded " + recordedFloor + ". Signing here would reuse a "
                            + "signature and could expose the key, so PandaPools posted nothing. Restore the "
                            + "newest matching MinimaCore wallet backup — the one from the device that used this "
                            + "pool most recently.\n\nOwner key: " + opk
                            + (address == null ? "" : "\nPool: " + address);
                case CONFIRMATION_UNSAVED:
                    return "Your last signing-state confirmation could not be saved on this device, so owner "
                            + "signing stays paused. Free some storage and confirm it again in Pool recovery.";
                case SIGNING_QUARANTINED:
                default:
                    return "This pool's recipe was imported, so PandaPools cannot tell how many of the owner key's "
                            + "one-time signatures the original device already used. Signing the wrong one could "
                            + "expose the key, so nothing was posted. Restore the matching MinimaCore wallet "
                            + "backup — the complete one, including signing state — and confirm it in Pool "
                            + "recovery. A seed phrase on its own is NOT enough: it rebuilds the key with its "
                            + "counter reset, which is exactly what causes this.\n\nOwner key: " + opk
                            + (address == null ? "" : "\nPool: " + address);
            }
        }
    }

    /** blocked maps a lowercased owner key to why it cannot sign. Empty means every wanted key may sign. */
    public interface Cb { void done(Map<String, Blocked> blocked); }

    /**
     * Which reason applies, as a pure function so every branch is testable. Order matters: the most specific
     * and most dangerous finding wins, because that is the one whose advice the user must follow.
     *
     * An unreadable reply outranks everything — we know nothing, and must not tell the user to restore anything.
     * A regressed counter outranks a quarantine, because "your counter went backwards, get the newer wallet
     * backup" is strictly more actionable than "this recipe was imported".
     */
    static Reason classify(boolean replyReadable, boolean rowPresent, Integer uses, Pool p, boolean confirmationFailed) {
        if (!replyReadable) return Reason.NODE_UNREADABLE;
        if (!rowPresent) return Reason.KEY_ABSENT;
        if (uses == null) return Reason.NODE_UNREADABLE;     // present but unparsable is still "we don't know"
        if (uses >= 262144) return Reason.KEY_EXHAUSTED;
        if (p != null && p.minimumOwnerUses >= 0 && uses < p.minimumOwnerUses) return Reason.COUNTER_REGRESSED;
        if (confirmationFailed) return Reason.CONFIRMATION_UNSAVED;
        return Reason.SIGNING_QUARANTINED;
    }

    /** The single most important reason in a set, for one summary line. */
    public static Blocked worst(Map<String, Blocked> blocked) {
        Blocked out = null;
        if (blocked != null) for (Blocked b : blocked.values())
            if (out == null || b.reason.severity() < out.reason.severity()) out = b;
        return out;
    }
    public static void ensure(Context ctx, NodeApi node, List<String> wantedOpks, Cb cb) {
        ensure(ctx, node, wantedOpks, kidxFromStore(ctx), cb);
    }
    public static void ensure(Context ctx, NodeApi node, List<String> wantedOpks,
                              Map<String, Integer> kidxByOpk, Cb cb) {
        Set<String> wanted = new LinkedHashSet<>();
        if (wantedOpks != null) for (String key : wantedOpks)
            if (key != null && !key.isEmpty()) wanted.add(key.toLowerCase(Locale.ROOT));
        if (wanted.isEmpty()) { cb.done(new LinkedHashMap<>()); return; }
        if (node == null) { cb.done(allUnreadable(wanted)); return; }
        node.cmd("keys", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                cb.done(classifyAll(ctx, reply, wanted));
            }
            public void onError(String message) { cb.done(allUnreadable(wanted)); }
        });
    }

    /** No node at all: every wanted key is unknown, not absent. Callers without a NodeApi use this. */
    public static Map<String, Blocked> nodeUnavailable(Collection<String> opks) {
        Set<String> wanted = new LinkedHashSet<>();
        if (opks != null) for (String key : opks)
            if (key != null && !key.isEmpty()) wanted.add(key.toLowerCase(Locale.ROOT));
        return allUnreadable(wanted);
    }

    /** No usable reply: every wanted key is unknown, not absent. Fail closed without misdiagnosing. */
    private static Map<String, Blocked> allUnreadable(Set<String> wanted) {
        Map<String, Blocked> out = new LinkedHashMap<>();
        for (String key : wanted) out.put(key, new Blocked(key, Reason.NODE_UNREADABLE, null, -1, null));
        return out;
    }

    /** Classify every wanted key against one `keys` reply plus the stored recipes. */
    static Map<String, Blocked> classifyAll(Context ctx, JSONObject reply, Set<String> wanted) {
        boolean readable = TxPost.truthy(reply, "status") && KeyUses.rows(reply) != null;
        Set<String> held = readable ? pubkeys(reply) : Collections.emptySet();

        // Index the stored recipes by owner key. One key can carry several pools (migrate shares an $OPK), and
        // ANY of them being blocked blocks the key — so keep the most severe finding across that group.
        Map<String, Blocked> out = new LinkedHashMap<>();
        if (ctx != null) for (Pool p : OwnPoolStore.all(ctx)) {
            if (p.opk == null) continue;
            String key = p.opk.toLowerCase(Locale.ROOT);
            if (!wanted.contains(key)) continue;
            Integer uses = readable ? KeyUses.extractUses(reply, p.opk) : null;
            if (signingAllowed(p, uses)) continue;
            Blocked b = new Blocked(key, classify(readable, held.contains(key), uses, p,
                    OwnPoolStore.confirmationFailed(p.opk)), uses, p.minimumOwnerUses, p.address);
            Blocked prev = out.get(key);
            if (prev == null || b.reason.severity() < prev.reason.severity()) out.put(key, b);
        }
        // Wanted keys with no stored recipe (a bare wallet key selected by publickey:auto) are judged on the
        // node's reply alone.
        for (String key : wanted) {
            if (out.containsKey(key)) continue;
            Integer uses = readable ? KeyUses.extractUses(reply, key) : null;
            if (readable && held.contains(key) && uses != null && uses >= 0 && uses < 262144) continue;   // fine
            out.put(key, new Blocked(key, classify(readable, held.contains(key), uses, null, false), uses, -1, null));
        }
        return out;
    }
    /** Core consolidation chooses inputs itself, so every currently stored owner must be safe. */
    static void checkConsolidation(FundingCoins.Command node, java.util.function.Supplier<List<Pool>> recipes,
                                   java.util.function.Consumer<String> cb) {
        node.run("keys", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                boolean readable = TxPost.truthy(reply, "status") && KeyUses.rows(reply) != null;
                if (!readable) { cb.accept("Could not verify current wallet keys. Nothing consolidated."); return; }
                Set<String> held = pubkeys(reply);
                for (Pool p : recipes.get()) {
                    Integer uses = KeyUses.extractUses(reply, p.opk);
                    if (signingAllowed(p, uses)) continue;
                    // Core consolidation chooses its own inputs, so it could pick a coin belonging to THIS key.
                    // Name the actual reason: "consolidation is paused" alone sent users hunting the wrong fix.
                    String key = p.opk == null ? "" : p.opk.toLowerCase(Locale.ROOT);
                    Reason r = classify(true, held.contains(key), uses, p, OwnPoolStore.confirmationFailed(p.opk));
                    cb.accept("Nothing consolidated: your wallet holds a pool owner key that cannot sign.\n\n"
                            + new Blocked(key, r, uses, p.minimumOwnerUses, p.address).message());
                    return;
                }
                cb.accept(null);
            }
            public void onError(String error) { cb.accept(error); }
        });
    }

    static List<String> unavailableKeys(JSONObject reply, Set<String> wanted) {
        Set<String> missing = new LinkedHashSet<>(wanted);
        if (TxPost.truthy(reply, "status") && KeyUses.rows(reply) != null) for (String key : pubkeys(reply)) {
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
                    boolean readable = TxPost.truthy(reply, "status") && KeyUses.rows(reply) != null;
                    if (!readable || !unavailableKeys(reply, wanted).isEmpty()) { cb.accept("Could not verify signing keys. Nothing signed."); return; }
                    Set<String> held = pubkeys(reply);
                    for (Pool p : current) {
                        if (p.opk == null) continue;
                        String key = p.opk.toLowerCase(Locale.ROOT);
                        if (!wanted.contains(key)) continue;
                        Integer uses = KeyUses.extractUses(reply, p.opk);
                        if (signingAllowed(p, uses)) continue;
                        Reason r = classify(true, held.contains(key), uses, p, OwnPoolStore.confirmationFailed(p.opk));
                        cb.accept("Nothing signed.\n\n" + new Blocked(key, r, uses, p.minimumOwnerUses, p.address).message());
                        return;
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
        JSONArray arr = KeyUses.rows(j);
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) set.add(pk.toLowerCase()); }
        }
        return set;
    }
}

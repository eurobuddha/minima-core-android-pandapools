package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;

/**
 * Pure budget arithmetic for the owner-key hunt (see {@link OwnerKeyRecovery}) — extracted so the rules
 * are JVM-testable, the same pattern as {@link KeyUses#restoreTarget}.
 *
 * WHY A BUDGET. The hunt re-mints a missing $OPK by re-issuing {@code newaddress}: deterministic
 * derivation ({@code privseed = hash(seed, modifier)}, sequential modifier) reproduces the SAME keys in
 * order — but only under the seed that minted them. A recipe or backup restored onto a DIFFERENT seed
 * carries an $OPK this node can never derive, and before this ledger every owner action re-burned up to
 * {@link #MAX_NEW_KEYS} fresh, PERMANENT keys re-hunting it: there is no key-delete command, and a live
 * node was found carrying 166 of them.
 *
 * THE RULES.
 *  - Per (seed, opk): at most {@link #MAX_NEW_KEYS} keys are ever minted hunting one owner key on one
 *    seed, across ALL hunts — counts persist in {@link HuntLedger}, charged per mint, so an interrupted
 *    hunt resumes with only its remainder.
 *  - Seed fingerprint: the ledger key's seed half is the publickey of the wallet's lowest-{@code modifier}
 *    key ({@code modifier} IS the derivation index; index 0 is the seed's first default key — always
 *    present, deterministic per seed). Re-seeding the node changes the fingerprint and re-opens the
 *    budget. That is load-bearing: even the CORRECT seed's restore rebuilds only the 64 defaults, so the
 *    hunt must still be allowed to run there.
 *  - kidx: when the wanted key's own derivation index is known (stamped into recipes and backups since
 *    backup v3), the hunt is exact — mint precisely to that index — and a wallet already PAST that index
 *    without holding the key proves a foreign seed with ZERO mints.
 */
final class HuntBudget {

    /** Lifetime cap on keys minted hunting one owner key on one seed. Comfortably above any realistic
     *  per-node pool count, and small enough that even a fully-burned budget leaves the wallet usable. */
    static final int MAX_NEW_KEYS = 256;

    /** Sanity bound on kidx-driven minting: a recipe pointing deeper than this looks corrupt, not busy —
     *  fall back to the plain {@link #MAX_NEW_KEYS} ledger budget instead of trusting it. */
    static final int MAX_KIDX_MINT = 2048;

    /** Fingerprint when no key row carries a parsable modifier (never seen on a real node). The budget
     *  still caps; only the reopen-on-reseed nicety degrades. */
    static final String NO_FP = "nofp";

    private HuntBudget() {}

    /** Ledger key for one (seed, opk) pair — both halves lowercase, joined with '|'. */
    static String key(String fp, String opk) {
        return (fp == null ? NO_FP : fp.toLowerCase()) + "|" + (opk == null ? "" : opk.toLowerCase());
    }

    /**
     * Seed fingerprint from a {@code keys} reply: the lowercase publickey of the row whose
     * {@code modifier} is NUMERICALLY smallest. Numeric on purpose — MiniData hex is variable-length, so
     * as strings "0xFF" sorts after "0x0100" — and the reply itself has no guaranteed order (the node's
     * key list is an unordered SELECT).
     */
    static String fingerprint(JSONObject keysReply) {
        JSONArray arr = rows(keysReply);
        if (arr == null) return NO_FP;
        BigInteger best = null;
        String pk = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null) continue;
            String p = k.optString("publickey", "");
            if (p.isEmpty()) continue;
            BigInteger m = parseModifier(k.optString("modifier", ""));
            if (m == null) continue;
            if (best == null || m.compareTo(best) < 0) { best = m; pk = p.toLowerCase(); }
        }
        return pk == null ? NO_FP : pk;
    }

    /** Wallet key count from a {@code keys} reply ({@code response.total}, else the row count); -1 if
     *  neither is readable. The count is the NEXT mint's derivation index (indexes run 0..total-1). */
    static int totalOf(JSONObject keysReply) {
        Object resp = keysReply == null ? null : keysReply.opt("response");
        if (resp instanceof JSONObject && ((JSONObject) resp).has("total")) {
            return ((JSONObject) resp).optInt("total", -1);
        }
        JSONArray arr = rows(keysReply);
        return arr == null ? -1 : arr.length();
    }

    /** The derivation index ({@code modifier}) of one held key in a {@code keys} reply, or -1. */
    static int modifierOf(JSONObject keysReply, String publickey) {
        JSONArray arr = rows(keysReply);
        if (arr == null || publickey == null || publickey.isEmpty()) return -1;
        String want = publickey.toLowerCase();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.optJSONObject(i);
            if (k == null || !want.equals(k.optString("publickey", "").toLowerCase())) continue;
            BigInteger m = parseModifier(k.optString("modifier", ""));
            return (m != null && m.signum() >= 0 && m.bitLength() < 31) ? m.intValue() : -1;
        }
        return -1;
    }

    /** Mints needed to reach derivation index {@code kidx} when the wallet holds {@code total} keys.
     *  Long arithmetic on purpose: {@code kidx} is user-importable via backup files, and an int overflow
     *  here (kidx = MAX_VALUE wrapping negative) would make an unreachable target look free — saturate
     *  instead, so an absurd index reads as absurd and fails {@link #kidxUsable}. */
    static int mintsNeeded(int kidx, int total) {
        long need = (long) kidx + 1L - (long) total;
        if (need <= 0) return 0;
        return need > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) need;
    }

    /** A known-index key the wallet is already PAST, yet does not hold, provably belongs to another
     *  seed — the same seed would have reproduced it at exactly that index. */
    static boolean provenForeign(int kidx, int total) {
        return kidx >= 0 && total >= 0 && total > kidx;
    }

    // ---- the decision rules the hunt runs on (pure, so the tests exercise the REAL rules) ----------

    /** Hunt this key: it is inside its budget (or has a trustworthy exact target). */
    static final int HUNT = 0;
    /** Budget spent: match it for free if a mint happens to produce it, but never charge or drive. */
    static final int WATCH = 1;
    /** The wallet is provably past this key's index without holding it — another seed's key, zero mints. */
    static final int FOREIGN_PROVEN = 2;

    /** Disposition of one wanted-but-not-held key at hunt start.
     *  @param kidx  the key's derivation index, -1 unknown
     *  @param total wallet key count, -1 unknown
     *  @param spent keys already minted hunting this (seed, opk) — from {@link HuntLedger} */
    static int disposition(int kidx, int total, int spent) {
        if (kidxUsable(kidx, total)) return HUNT;                    // exact target — the flat cap does not bind
        if (provenForeign(kidx, total)) return FOREIGN_PROVEN;
        return spent >= MAX_NEW_KEYS ? WATCH : HUNT;                 // unknown/corrupt index — plain budget
    }

    /** A kidx trustworthy enough to drive an exact hunt from {@code total}: known, not already passed,
     *  and not absurdly deep ({@link #MAX_KIDX_MINT} — a corrupt index must not budget a mint marathon). */
    static boolean kidxUsable(int kidx, int total) {
        return kidx >= 0 && total >= 0 && total <= kidx && mintsNeeded(kidx, total) <= MAX_KIDX_MINT;
    }

    /** The hunted key survives this round. */
    static final int KEEP = 0;
    /** Plain-budget key: its {@link #MAX_NEW_KEYS} charges are spent — stop hunting it, report it. */
    static final int EXHAUSTED = 1;
    /** Exact-target key: the mint walked past its index without matching — foreign seed PROVEN. */
    static final int FOREIGN_PAST_KIDX = 2;

    /** Verdict on one still-wanted key after charging it for a mint at index {@code newIdx}.
     *  @param usableKidx its exact target, or null when hunting on the plain budget
     *  @param newIdx     the derivation index of the key just minted
     *  @param spent      its ledger count INCLUDING this mint's charge */
    static int afterCharge(Integer usableKidx, int newIdx, int spent) {
        if (usableKidx != null) return newIdx >= usableKidx ? FOREIGN_PAST_KIDX : KEEP;
        return spent >= MAX_NEW_KEYS ? EXHAUSTED : KEEP;
    }

    /** The key rows of a {@code keys} reply — the response is either the array itself or nests it
     *  under {@code keys} (both shapes exist in the wild). */
    static JSONArray rows(JSONObject reply) {
        Object resp = reply == null ? null : reply.opt("response");
        if (resp instanceof JSONArray) return (JSONArray) resp;
        if (resp instanceof JSONObject) return ((JSONObject) resp).optJSONArray("keys");
        return null;
    }

    /** A MiniData modifier ("0x00", "0x41", …) as a number, or null if unparsable. */
    static BigInteger parseModifier(String s) {
        if (s == null) return null;
        String h = s.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.isEmpty()) return null;
        try { return new BigInteger(h, 16); } catch (NumberFormatException e) { return null; }
    }
}

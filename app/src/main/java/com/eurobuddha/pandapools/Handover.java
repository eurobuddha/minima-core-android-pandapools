package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hand-over to Dexicon. Dexicon embeds PandaPools, and two apps keeping the same pool would rebuild its
 * reserves against each other — so the two must never both be installed. Dexicon only unlocks its uninstall
 * button after THIS app confirms that nothing lives only here: uninstalling deletes the pool recipes and
 * recovery state below. A live pool may go only once Dexicon holds its recipe (restored from the verified
 * backup) — and PandaPools then stops keeping it ({@link #KEY_POOLS}) before it answers OK.
 *
 * Every check reads an existing store; none invents a rule. The flag only blocks NEW pools and NEW swaps —
 * everything that protects funds keeps running.
 */
final class Handover {

    static final String DEXICON = "com.eurobuddha.dexicon";
    static final String ACTION = "com.eurobuddha.dexicon.action.HANDOVER";

    /** Exact wording agreed with Dexicon: it restores the PandaPools backup, then asks again with "dexicon_pools". */
    static final String RESTORE_FIRST = "Restore your PandaPools backup in Dexicon first";
    static final String LIVE_POOL = RESTORE_FIRST + ", then hand over again.";
    static final String NO_BACKUP = "No verified PandaPools backup covers every pool on this install. Save and verify "
            + "a backup on the My Pools tab, restore it in Dexicon, then hand over again.";
    static final String CLOSED_HOLDS_FUNDS = "A closed pool still holds funds. Withdraw it on the My Pools tab first.";
    /** Extras: Dexicon's covenant addresses in, the handed-over addresses out. */
    static final String EXTRA_DEXICON_POOLS = "dexicon_pools", EXTRA_POOLS = "pools";
    /** On every refusal: the pools PandaPools has stopped keeping because Dexicon keeps them (lower-case covenant addresses). */
    static final String EXTRA_KEPT_BY_DEXICON = "kept_by_dexicon";
    static final String BLOCKED =
            "PandaPools has been handed over to Dexicon. Tap \"Take back\" on the main screen to use it here again.";

    private static final String PREFS = "pandapools_handover";
    static final String KEY = "handed_over_to_dexicon", KEY_TS = "handed_over_to_dexicon_ts";
    /** Pools Dexicon keeps now. PandaPools' keeper (keep-fresh, re-announce, stranding) leaves them alone. */
    static final String KEY_POOLS = "handed_over_pools";

    private Handover() {}

    static boolean callerAllowed(String callingPackage) { return DEXICON.equals(callingPackage); }

    /**
     * Dexicon by name AND by signer: both apps release-sign with the Minima family key
     * (MINIMA_FAMILY_RELEASE_* in both app/build.gradle files; debug builds share the machine's debug key),
     * so a look-alike package with another key is refused.
     */
    static boolean callerAllowed(String callingPackage, PackageManager pm, String self) {
        if (!callerAllowed(callingPackage) || pm == null) return false;
        try { return pm.checkSignatures(callingPackage, self) == PackageManager.SIGNATURE_MATCH; }
        catch (RuntimeException unknown) { return false; }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean active(Context c) {
        try { return c != null && prefs(c).getBoolean(KEY, false); }
        catch (RuntimeException unreadable) { return false; }
    }

    static long ts(Context c) {
        try { return prefs(c).getLong(KEY_TS, 0); } catch (RuntimeException unreadable) { return 0; }
    }

    /** Pool creates and swaps running right now (process-wide). The hand-over refuses while any runs. */
    private static final AtomicInteger RUNNING = new AtomicInteger();

    /**
     * Start a pool create or swap. Marks it running BEFORE reading the flag, and the hand-over sets the flag
     * BEFORE reading {@link #busy}, so one of the two always sees the other. False (nothing marked) once
     * handed over. A true return must be paired with exactly one {@link #end}.
     */
    static boolean begin(Context c) {
        RUNNING.incrementAndGet();
        if (active(c)) { RUNNING.decrementAndGet(); return false; }
        return true;
    }

    /**
     * Mark the owner's own fund move (close, deposit) running WITHOUT the flag refusal: those are the owner's own
     * funds and must work handed over or not, but a hand-over must still wait for one in flight. Pair with exactly
     * one {@link #end}.
     */
    static void mark() { RUNNING.incrementAndGet(); }

    static void end() { RUNNING.decrementAndGet(); }

    static boolean busy() { return RUNNING.get() > 0; }

    /** What a finished check run does. */
    enum Outcome { OK, REFUSE, SHOW }

    /**
     * {@code confirming}: the user tapped Hand over (the flag was set first). {@code handedOver}: the flag was on
     * when the run began. OK only on a fresh, fully passing run; a failed confirm refuses (and drops the flag).
     */
    static Outcome outcome(boolean confirming, boolean handedOver, boolean allPass) {
        if (!allPass) return confirming ? Outcome.REFUSE : Outcome.SHOW;
        return confirming || handedOver ? Outcome.OK : Outcome.SHOW;
    }

    /** commit(): Dexicon is told OK only once the flag is on disk. Off also resumes keeping every pool. */
    static boolean set(Context c, boolean on, long ts) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY, on);
        if (on) e.putLong(KEY_TS, ts); else e.remove(KEY_TS).remove(KEY_POOLS);
        return e.commit();
    }

    /** Lower-cased addresses Dexicon keeps now (empty if none or unreadable). */
    static Set<String> handedOverPools(Context c) {
        try {
            Set<String> s = c == null ? null : prefs(c).getStringSet(KEY_POOLS, null);
            return s == null ? new HashSet<>() : new HashSet<>(s);
        } catch (RuntimeException unreadable) { return new HashSet<>(); }
    }

    /** True when Dexicon keeps this pool: PandaPools must not refresh or re-announce it. */
    static boolean keptByDexicon(Context c, String address) {
        return address != null && handedOverPools(c).contains(address.toLowerCase(Locale.ROOT));
    }

    /** {@code pools} minus the ones Dexicon keeps. */
    static List<Pool> keepable(Context c, List<Pool> pools) {
        Set<String> gone = handedOverPools(c);
        List<Pool> out = new ArrayList<>();
        for (Pool p : pools) if (p != null && (p.address == null || !gone.contains(p.address.toLowerCase(Locale.ROOT)))) out.add(p);
        return out;
    }

    /**
     * Stop keeping {@code active} (added to any pools already handed over) — durably, BEFORE Dexicon hears OK.
     * Returns the full handed-over set, or null if it could not be saved.
     */
    static String[] stopKeeping(Context c, List<Pool> active) {
        Set<String> s = handedOverPools(c);
        for (Pool p : active) if (p.address != null) s.add(p.address.toLowerCase(Locale.ROOT));
        if (s.isEmpty()) return new String[0];
        try { if (!prefs(c).edit().putStringSet(KEY_POOLS, s).commit()) return null; }
        catch (RuntimeException unwritable) { return null; }
        return s.toArray(new String[0]);
    }

    /**
     * The {@link #EXTRA_KEPT_BY_DEXICON} list: the pools handed over, plus every pool Dexicon says it holds that has
     * no recipe here at all — PandaPools cannot keep a pool it holds no recipe for, so Dexicon keeps it. Lower-case.
     */
    static String[] keptByDexicon(Set<String> handedOver, String[] dexiconPools, List<Pool> recipes) {
        Set<String> out = new java.util.TreeSet<>(handedOver);
        Set<String> here = new HashSet<>();
        for (Pool p : recipes) if (p != null && p.address != null) here.add(p.address.toLowerCase(Locale.ROOT));
        if (dexiconPools != null) for (String a : dexiconPools) {
            String k = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !here.contains(k)) out.add(k);
        }
        return out.toArray(new String[0]);
    }

    /** Confirmations "Take back" needs: two once Dexicon keeps pools (two keepers fight over the reserves). */
    static int takeBackConfirms(Context c) { return handedOverPools(c).isEmpty() ? 0 : 2; }

    /** Take back after {@code confirms} explicit confirmations; false (nothing changed) if that is too few. */
    static boolean takeBack(Context c, int confirms) {
        return confirms >= takeBackConfirms(c) && set(c, false, 0);
    }

    /** One line on the hand-over screen. {@code detail} carries full identifiers, never abbreviated. */
    static final class Check {
        final String label, reason, detail;
        final boolean ok;
        Check(String label, boolean ok, String reason, String detail) {
            this.label = label; this.ok = ok; this.reason = reason; this.detail = detail;
        }
    }

    /**
     * (1) Every live pool is already held by Dexicon ({@code dexiconPools}, null when Dexicon sent none) AND a
     * verified backup covers every pool here. Retired pools are checked on-chain separately (they must be empty).
     */
    static Check pools(List<Pool> active, String[] dexiconPools, boolean backupCovers) { return pools(active, dexiconPools, backupCovers, false); }

    /** {@code account}: PandaPools holds the shared Trading Account. Dexicon cannot open that account while PandaPools is
     *  installed (one holder per phone), so a live pool moves with the 24 WORDS + the verified recipe backup instead of
     *  being held by Dexicon first: restore the words in Dexicon, then open the backup file there (Recover pools). */
    static Check pools(List<Pool> active, String[] dexiconPools, boolean backupCovers, boolean account) {
        String label = account ? "A verified backup covers every live pool" : "Every live pool is restored in Dexicon";
        if (active.isEmpty()) return new Check(label, true, "", "");
        if (account) return backupCovers ? new Check(label, true, "", "") : new Check(label, false, NO_BACKUP, "");
        Set<String> held = new HashSet<>();
        if (dexiconPools != null) for (String a : dexiconPools) if (a != null) held.add(a.trim().toLowerCase(Locale.ROOT));
        StringBuilder missing = new StringBuilder();
        for (Pool p : active)
            if (p.address == null || !held.contains(p.address.toLowerCase(Locale.ROOT)))
                missing.append("Pool: ").append(p.address).append('\n');
        String m = missing.toString().trim();
        if (dexiconPools == null) return new Check(label, false, LIVE_POOL, m);
        if (!m.isEmpty()) return new Check(label, false,
                RESTORE_FIRST + ". Dexicon does not hold these pools yet:\n" + m, m);
        if (!backupCovers) return new Check(label, false, NO_BACKUP, "");
        return new Check(label, true, "", "");
    }

    /**
     * Terminal exit states, read from the RAW stored string: a missing, unreadable or unknown state is NOT settled
     * (ExitStore maps those to FAILED for display; the hand-over must not treat them as finished).
     */
    static boolean exitSettled(String rawState) {
        return ExitStore.State.DONE.name().equals(rawState) || ExitStore.State.SKIPPED.name().equals(rawState)
                || ExitStore.State.FAILED.name().equals(rawState);
    }

    /** (2) No interrupted or in-progress withdrawal. */
    /** PandaPools holds the Trading Account: uninstalling it deletes this phone's copy of the keys. OK only once the user
     *  has re-typed the 24 words and they match this account (a node-seed account is restored from the node instead). */
    static Check account(boolean holds, boolean nodeSeed, boolean wordsConfirmed) {
        String label = "Your Trading Account words are confirmed";
        if (!holds || nodeSeed || wordsConfirmed) return new Check(label, true, "", "");
        return new Check(label, false, "PandaPools holds your Trading Account. Removing PandaPools deletes this phone's copy of "
                + "its keys: tap Confirm words and type your 24 words, then restore them in Dexicon.", "");
    }

    static Check withdrawals(List<ExitStore.Record> records) {
        StringBuilder d = new StringBuilder();
        for (ExitStore.Record r : records) if (!exitSettled(r.state))
            d.append("Pool: ").append(r.address).append(" — ")
                    .append(r.state == null || r.state.isEmpty() ? "unreadable record" : r.state).append('\n');
        boolean ok = d.length() == 0;
        return new Check("No withdrawal interrupted or in progress", ok,
                ok ? "" : "A pool withdrawal is interrupted or still in progress. Resolve it on the My Pools tab first; "
                        + "its recovery record exists only in PandaPools.", d.toString().trim());
    }

    /** (3) No owner payout address still holding funds (stranded or awaiting collection). */
    static Check payouts(List<PendingCollect.Entry> entries) {
        StringBuilder d = new StringBuilder();
        for (PendingCollect.Entry e : entries)
            d.append("Payout address: ").append(e.oadr).append(" — ").append(e.statusOf().name()).append('\n');
        boolean ok = entries.isEmpty();
        return new Check("No owner payout address holding funds", ok,
                ok ? "" : "An owner payout address still holds funds. PandaPools is the only app tracking it — "
                        + "collect it to your wallet on the My Pools tab first.", d.toString().trim());
    }

    /** (4) No swap from the Swap tab still building, posting or confirming. */
    static Check swaps(boolean inFlight, boolean pendingInActivity) {
        boolean ok = !inFlight && !pendingInActivity;
        return new Check("No swap in progress", ok,
                ok ? "" : "A swap is still in progress. Wait until it confirms on the Activity tab, then try again.", "");
    }

    /** (5) No pool create or swap running right now (it may be about to record a recipe or post). */
    static Check running(boolean busy) {
        return new Check("No pool creation or swap running", !busy,
                busy ? "A pool is being created or a swap is being sent. Wait until it finishes, then try again." : "", "");
    }

    /** The {@code reason} extra for Dexicon: every failing check's reason, live-pool first. */
    static String refusal(List<Check> checks) {
        List<String> out = new ArrayList<>();
        for (Check c : checks) if (!c.ok && !out.contains(c.reason)) out.add(c.reason);
        return String.join("\n\n", out);
    }
}

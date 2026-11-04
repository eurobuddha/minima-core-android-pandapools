package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hand-over to Dexicon. Dexicon embeds PandaPools, and two apps keeping the same pool would rebuild its
 * reserves against each other — so the two must never both be installed. Dexicon only unlocks its uninstall
 * button after THIS app confirms that nothing lives only here: uninstalling deletes the pool recipes and
 * recovery state below.
 *
 * Every check reads an existing store; none invents a rule. The flag only blocks NEW pools and NEW swaps —
 * everything that protects funds keeps running.
 */
final class Handover {

    static final String DEXICON = "com.eurobuddha.dexicon";
    static final String ACTION = "com.eurobuddha.dexicon.action.HANDOVER";

    /** Exact wording agreed with Dexicon. Dexicon cannot host pools until a later update. */
    static final String LIVE_POOL =
            "Dexicon can't take over a live pool yet. Keep PandaPools until the Dexicon update that brings pools; "
                    + "your pool keeps running here.";
    static final String BLOCKED =
            "PandaPools has been handed over to Dexicon. Tap \"Take back\" on the main screen to use it here again.";

    private static final String PREFS = "pandapools_handover";
    static final String KEY = "handed_over_to_dexicon", KEY_TS = "handed_over_to_dexicon_ts";

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

    /** commit(): Dexicon is told OK only once the flag is on disk. */
    static boolean set(Context c, boolean on, long ts) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY, on);
        if (on) e.putLong(KEY_TS, ts); else e.remove(KEY_TS);
        return e.commit();
    }

    /** One line on the hand-over screen. {@code detail} carries full identifiers, never abbreviated. */
    static final class Check {
        final String label, reason, detail;
        final boolean ok;
        Check(String label, boolean ok, String reason, String detail) {
            this.label = label; this.ok = ok; this.reason = reason; this.detail = detail;
        }
    }

    /** (1) No pool still running here. Retired pools are checked on-chain separately (they must be empty). */
    static Check pools(List<Pool> active) {
        StringBuilder d = new StringBuilder();
        for (Pool p : active) d.append("Pool: ").append(p.address).append('\n');
        return new Check("No live pool on this install", active.isEmpty(), active.isEmpty() ? "" : LIVE_POOL,
                d.toString().trim());
    }

    /** Terminal exit states. Everything else is interrupted or still moving funds. */
    static boolean exitSettled(ExitStore.State s) {
        return s == ExitStore.State.DONE || s == ExitStore.State.SKIPPED || s == ExitStore.State.FAILED;
    }

    /** (2) No interrupted or in-progress withdrawal. */
    static Check withdrawals(List<ExitStore.Record> records) {
        StringBuilder d = new StringBuilder();
        for (ExitStore.Record r : records) if (!exitSettled(r.stateOf()))
            d.append("Pool: ").append(r.address).append(" — ").append(r.stateOf().name()).append('\n');
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

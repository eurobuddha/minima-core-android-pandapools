package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

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

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean active(Context c) {
        try { return c != null && prefs(c).getBoolean(KEY, false); }
        catch (RuntimeException unreadable) { return false; }
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

    /** The {@code reason} extra for Dexicon: every failing check's reason, live-pool first. */
    static String refusal(List<Check> checks) {
        List<String> out = new ArrayList<>();
        for (Check c : checks) if (!c.ok && !out.contains(c.reason)) out.add(c.reason);
        return String.join("\n\n", out);
    }
}

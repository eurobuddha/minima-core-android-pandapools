package com.eurobuddha.pandapools;

import android.content.Context;

import com.eurobuddha.account.Account;
import com.eurobuddha.account.AccountVault;

import java.util.List;

/**
 * PandaPools' settings for the shared Trading Account (vendored from Dexicon, com.eurobuddha.account). The same 24 words
 * open the same account in Dexicon, AtomiX and PandaPools; one of them holds it at a time.
 */
final class PandaPoolsAccount {
    private PandaPoolsAccount() {}

    /** No HTLC contracts here (pool coins are tracked by their own script), no wallet-history rows, platform dialogs. */
    static Account.Config config() { return new Account.Config("PandaPools", null, null, null, null); }

    /** Pools an earlier PandaPools created with the NODE's keys: until they are closed, PandaPools keeps the node-key
     *  path (and does not offer the Trading Account) — "finish before upgrading", as Dexicon does. Never once an
     *  account exists on this phone. */
    static boolean legacyPools(Context c) {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 30_000L) return cached;
        boolean v = scan(c.getApplicationContext());
        cached = v; cachedAt = now;
        return v;
    }
    private static volatile boolean cached;
    private static volatile long cachedAt;

    /** Anything still tied to the NODE's keys, as the hand-over judges it: a node-key pool not yet PROVEN empty (a live
     *  one, or a retired one whose close / migrate may never have landed — {@link ClosedPoolCheck} clears it), a close /
     *  migrate just posted, a payout the node can still settle, a withdrawal still running. Never once an account exists. */
    private static boolean scan(Context c) {
        if (new AccountVault(c).everCreated()) return false;
        try { return !undrained(OwnPoolStore.all(c)).isEmpty() || scanWithout(c); }
        catch (RuntimeException unreadable) { return true; }
    }

    /** The rule, pure. A recipe recorded under an account ({@link Pool#accountOwned}, e.g. carried over by a device
     *  transfer) is never node-key work. A STRANDED payout is left out by the caller: the node cannot settle it either,
     *  so it must not hold the account back forever. */
    static boolean nodeKeyWork(List<Pool> recipes, boolean closeOrMigratePosted, boolean payoutsOpen, boolean exitsOpen) {
        return !undrained(recipes).isEmpty() || closeOrMigratePosted || payoutsOpen || exitsOpen;
    }

    /** Node-key recipes not proven empty: every live one, and every retired one not yet {@link Pool#drained}. */
    static List<Pool> undrained(List<Pool> recipes) {
        List<Pool> out = new java.util.ArrayList<>();
        for (Pool p : recipes) if (!p.accountOwned && (!p.retired || !p.drained)) out.add(p);
        return out;
    }

    /** Retired node-key recipes still to prove empty, when they are the ONLY thing holding the account back (the offer
     *  then runs {@link ClosedPoolCheck} on them); empty otherwise. */
    static List<Pool> closedToProve(Context c) {
        List<Pool> out = new java.util.ArrayList<>();
        if (new AccountVault(c).everCreated()) return out;
        try {
            for (Pool p : undrained(OwnPoolStore.all(c))) { if (!p.retired) return new java.util.ArrayList<>(); out.add(p); }
            return scanWithout(c) ? new java.util.ArrayList<>() : out;
        } catch (RuntimeException unreadable) { return new java.util.ArrayList<>(); }
    }

    /** Everything except the recipes: a close / migrate just posted, a payout the node can settle, a running withdrawal. */
    private static boolean scanWithout(Context c) {
        boolean exits = false;
        for (ExitStore.Record r : ExitStore.all(c)) if (!Handover.exitSettled(r.state)) exits = true;
        boolean payouts = false;
        for (PendingCollect.Entry e : PendingCollect.all(c)) if (e.statusOf() != PendingCollect.Status.STRANDED) payouts = true;
        return nodeKeyWork(new java.util.ArrayList<>(),
                ActivityLog.hasPending(c, 0, ActivityLog.CLOSE) || ActivityLog.hasPending(c, 0, ActivityLog.MIGRATE), payouts, exits);
    }

    /** Forget the cached answer (a closed pool was just proven empty). */
    static void invalidate() { cachedAt = 0; }
}

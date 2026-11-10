package com.eurobuddha.pandapools;

import android.content.Context;

import com.eurobuddha.account.Account;
import com.eurobuddha.account.AccountVault;

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

    /** Anything still tied to the NODE's keys, as the hand-over judges it: a live pool, a close / migrate not yet
     *  confirmed on chain (a retired recipe whose close may never land), a payout or withdrawal still running. */
    private static boolean scan(Context c) {
        if (new AccountVault(c).everCreated()) return false;
        try {
            if (!OwnPoolStore.active(c).isEmpty()) return true;
            if (ActivityLog.hasPending(c, 0, ActivityLog.CLOSE) || ActivityLog.hasPending(c, 0, ActivityLog.MIGRATE)) return true;
            if (!PendingCollect.all(c).isEmpty()) return true;
            for (ExitStore.Record r : ExitStore.all(c)) if (!Handover.exitSettled(r.state)) return true;
            return false;
        } catch (RuntimeException unreadable) { return true; }
    }
}

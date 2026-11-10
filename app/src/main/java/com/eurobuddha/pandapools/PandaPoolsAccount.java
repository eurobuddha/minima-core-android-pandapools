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
        if (new AccountVault(c).everCreated()) return false;
        try { return !OwnPoolStore.active(c).isEmpty(); } catch (RuntimeException unreadable) { return true; }
    }
}

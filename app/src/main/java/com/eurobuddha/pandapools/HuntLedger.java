package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The persistent memory of the owner-key hunt: how many keys have EVER been minted hunting each
 * (seed, opk) pair — see {@link HuntBudget} for the rules it enforces. One int per pair, keyed
 * {@code "<seed-fingerprint>|<opk>"} (both lowercase). Written per mint, so an interrupted hunt
 * resumes with only its remaining budget instead of a fresh one.
 *
 * Entries under old fingerprints are kept forever — they are tiny, and returning to a previously-tried
 * seed must keep that seed's spent budget.
 */
final class HuntLedger {

    private static final String PREFS = "pandapools_huntledger";

    private HuntLedger() {}

    /** Keys minted so far hunting {@code opk} under seed {@code fp} (0 = never hunted). */
    static int get(Context c, String fp, String opk) {
        return c == null ? 0 : prefs(c).getInt(HuntBudget.key(fp, opk), 0);
    }

    static void put(Context c, String fp, String opk, int count) {
        if (c != null) prefs(c).edit().putInt(HuntBudget.key(fp, opk), count).apply();
    }

    /** Drop a pair's count — used when its key is actually FOUND, so the entry can't linger. */
    static void clear(Context c, String fp, String opk) {
        if (c != null) prefs(c).edit().remove(HuntBudget.key(fp, opk)).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

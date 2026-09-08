package com.eurobuddha.pandapools;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide reservation of the wallet coins an in-flight transaction intends to spend.
 *
 * Several paths fan out on purpose — {@link PoolRefresher} posts up to 8 refreshes in one loop,
 * {@link ReAnnouncer} the same, {@code PoolManager.sweepOwnerFunds} one per owner address — and
 * {@link PoolManager#selectCoins} sorts largest-first and stops at the first coin that covers the
 * amount needed. For a refresh that amount is beacon dust, so the single largest wallet coin always
 * satisfies it and EVERY parallel builder picked the same coin, at the same address, owned by the same
 * key. Seven of them then double-spent and failed, and all of them signed.
 *
 * {@link PoolTxn} already had this idea, but as an instance field on a class only the Swap tab
 * constructs — so it protected swaps from each other and nothing else. This is the shared version that
 * {@link PoolManager} consults too.
 *
 * Reservations self-expire: a transaction that was posted but never confirmed (lost a race, node went
 * away) must not strand its funding coin as permanently unusable.
 */
public final class CoinLock {

    /** ~3–4 blocks. Long enough to cover build + sign + post, short enough that a lost transaction
     *  frees its coin within a few minutes. */
    private static final long TTL_MS = 3 * 60 * 1000L;

    private static final Map<String, Long> RESERVED = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED = new HashSet<>();

    private CoinLock() {}

    public static synchronized boolean isReserved(String coinid) {
        if (coinid == null) return false;
        if (QUEUED.contains(key(coinid))) return true;
        Long at = RESERVED.get(key(coinid));
        return at != null && (System.currentTimeMillis() - at) < TTL_MS;
    }

    public static void reserve(List<Coin> coins) {
        long now = System.currentTimeMillis();
        for (Coin c : coins) if (c != null && c.coinid != null) RESERVED.put(key(c.coinid), now);
    }

    public static void release(List<Coin> coins) {
        for (Coin c : coins) if (c != null && c.coinid != null) RESERVED.remove(key(c.coinid));
    }

    /** Claim all inputs atomically before queuing. Selection reservations may expire; a queued
     * or signing chain must retain its inputs until its callback completes. */
    static synchronized boolean claimInputs(List<String> ids) {
        Set<String> unique = new HashSet<>();
        for (String id : ids)
            if (id == null || !unique.add(key(id)) || QUEUED.contains(key(id))) return false;
        QUEUED.addAll(unique);
        return true;
    }

    static synchronized void finishInputs(List<String> ids) {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            QUEUED.remove(key(id));
            // Give the node's mempool view time to catch up after a successful/ambiguous post.
            RESERVED.put(key(id), now);
        }
    }

    private static String key(String id) { return id.toLowerCase(Locale.ROOT); }

    /** Drop entries past their TTL. Token-agnostic on purpose: a scan only sees one token's coins, so
     *  pruning against "what I can see" would wrongly free the other side's live reservations. */
    public static void prune() {
        long now = System.currentTimeMillis();
        RESERVED.entrySet().removeIf(e -> now - e.getValue() >= TTL_MS);
    }
}

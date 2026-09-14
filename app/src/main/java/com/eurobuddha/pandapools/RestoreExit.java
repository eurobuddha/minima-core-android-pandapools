package com.eurobuddha.pandapools;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Withdraws restored pools automatically, one at a time, and is the ONLY thing that can issue an exit exemption.
 *
 * WHY THIS EXISTS AT ALL. A restored recipe is signing-quarantined, and {@link PoolRefresher} excludes
 * quarantined pools from keep-fresh. So a restored pool cannot refresh, drifts past the ~1700-block cascade, and
 * strands — the safe state is a slow death. Before this, the only ways out were the owner attestation, which
 * unlocks unlimited signing forever, or losing the pool. Every unsophisticated user was funnelled into the
 * dangerous button under time pressure. This is the bounded exit: two signatures, then the pool is gone and so is
 * the exposure.
 *
 * WHAT IT DOES NOT CLAIM. This is not safe. A signature the original node made that never reached the chain, or
 * made after the last backup, is invisible to every check here, and in that case the close reuses a leaf. What it
 * does is refuse every case where a reuse is DETECTABLE and cap the exposure at two leaves of 262,144 where it is
 * not. Strictly better than unbounded signing on an unproven key; not a guarantee, and nothing it prints says
 * otherwise.
 *
 * WHY IT IS SERIAL. Two concurrent exits would mean two chains signing with owner keys at once, which is the
 * hazard the whole signing gate exists to prevent. One at a time also keeps the progress log truthful and bounds
 * how much can go wrong before a person sees it.
 */
final class RestoreExit {

    /** Per run, so a restore of many pools cannot fire a long unattended burst of signing. */
    static final int MAX_PER_RUN = 8;

    interface Cb {
        void onProgress(String line);
        void onDone(int withdrawn, int skipped);
    }

    private final Context ctx;
    private final NodeApi node;
    private final PoolManager mgr;
    private int chainBlock;

    RestoreExit(Context ctx, NodeApi node) {
        this.ctx = ctx; this.node = node; this.mgr = new PoolManager(node);
    }

    /** Why a pool will not be withdrawn automatically, or null when it will be. */
    static String refusal(Context ctx, Pool p, Integer nodeUses, int chainBlock) {
        if (p == null) return "no pool";
        if (!ExitStore.withinLifetimeCap(ctx, p.address))
            return "this pool has already used its automatic-withdrawal attempts. Withdraw it yourself from MY LP.";
        if (!OwnerKeyRecovery.baseSigningAllowed(p, nodeUses)) {
            if (nodeUses == null) return "this node could not read the owner key's signature counter.";
            if (nodeUses >= 262144) return "the owner key has spent all 262,144 of its one-time signatures.";
            if (nodeUses < p.minimumOwnerUses)
                return "this node reports " + nodeUses + " signatures used but the recipe recorded "
                        + p.minimumOwnerUses + ". Signing would reuse one. Restore the newest matching wallet backup.";
            return "the owner key is unavailable on this node.";
        }
        if (p.minimumOwnerUses < 0)
            return "this backup recorded no signature count, so nothing can detect a counter that has gone "
                    + "backwards. PandaPools will not sign for it automatically.";
        int age = p.reserveAge(chainBlock);
        if (age <= 0)
            return "the age of this pool's reserves is unknown, so PandaPools cannot tell whether another device "
                    + "is still running this wallet.";
        if (age <= PoolRefresher.REFRESH_BLOCKS)
            return "these reserves were recreated " + age + " blocks ago. Only this pool's owner key can do that, "
                    + "so another device is still running this wallet. Nothing was posted — signing here while that "
                    + "device is also signing could expose the key. Stop the other device, then use Withdraw on "
                    + "this pool's card.";
        return null;
    }

    /**
     * Withdraw each verified pool, in series. Pools past {@link #MAX_PER_RUN} keep their recipes and are reported,
     * never silently dropped.
     */
    void run(List<Pool> verified, int chainBlock, Cb cb) {
        this.chainBlock = chainBlock;
        List<Pool> queue = new ArrayList<>();
        if (verified != null) for (Pool p : verified) if (p != null && p.address != null) queue.add(p);
        if (queue.isEmpty()) { cb.onDone(0, 0); return; }
        if (queue.size() > MAX_PER_RUN) {
            cb.onProgress("Withdrawing the first " + MAX_PER_RUN + " of " + queue.size()
                    + " pools now; the rest keep their recipes and can be withdrawn from MY LP.");
            queue = new ArrayList<>(queue.subList(0, MAX_PER_RUN));
        }
        step(queue, 0, new int[]{0, 0}, cb);
    }

    private void step(List<Pool> queue, int i, int[] tally, Cb cb) {
        if (i >= queue.size()) { cb.onDone(tally[0], tally[1]); return; }
        Pool p = queue.get(i);
        // Read the counter fresh for THIS pool, immediately before deciding. A counter read minutes ago is not
        // evidence about the key now.
        node.cmd("keys action:list publickey:" + p.opk, new NodeApi.Cb() {
            @Override public void onResult(JSONObject reply) {
                Integer uses = KeyUses.extractUses(reply, p.opk);
                decide(queue, i, p, uses, tally, cb);
            }
            @Override public void onError(String error) {
                skip(queue, i, p, "the owner key could not be read: " + error, tally, cb);
            }
        });
    }

    private void decide(List<Pool> queue, int i, Pool p, Integer uses, int[] tally, Cb cb) {
        String why = refusal(ctx, p, uses, currentBlock());
        if (why != null) { skip(queue, i, p, why, tally, cb); return; }

        // Re-read the LIVE covenant coins before building, the same rule every other owner transaction follows:
        // a swap (or this node's own keep-fresh) can spend and recreate the reserves between the reserve
        // verification and here, and a close built on a stale snapshot dies at txncheck. Doing this before the
        // ticket is issued also means the ticket pins the coins that will actually be spent.
        PoolRefresher.readLiveReserves(node, p, fresh -> {
            if (!fresh) {
                skip(queue, i, p, "this pool's current reserves could not be read, so nothing was posted.", tally, cb);
                return;
            }
            // The pool may have moved under us, which changes what "withdraw everything" means. Re-check.
            String again = refusal(ctx, p, uses, currentBlock());
            if (again != null) { skip(queue, i, p, again, tally, cb); return; }
            post(queue, i, p, uses, tally, cb);
        });
    }

    private void post(List<Pool> queue, int i, Pool p, Integer uses, int[] tally, Cb cb) {

        // Record the observed count BEFORE signing, so a crash between signing and posting still leaves the floor
        // at or above what was spent. Erring high costs one leaf and blocks future signing until a proper wallet
        // restore; erring low permits a reuse.
        if (!OwnPoolStore.raiseUseFloor(ctx, p.opk, uses)) {
            skip(queue, i, p, "could not record the owner key's signature count on this device.", tally, cb); return;
        }
        // Mark POSTING before the post, and treat a failed write as a refusal: without a durable marker a crash
        // mid-post would look untried on relaunch, and the retry would double-post against the same coins.
        if (!ExitStore.set(ctx, p.address, p.opk, ExitStore.State.POSTING, "", "", ExitTicket.SIGNATURES_PER_EXIT)) {
            skip(queue, i, p, "could not record the withdrawal on this device, so nothing was posted.", tally, cb); return;
        }

        cb.onProgress("Withdrawing " + p.address + " …");
        ExitAuthority.grant(new ExitTicket(p.opk, p.address, p.oadr, p.tok,
                p.coinidM, p.coinidT, PoolManager.amt(p.reserveM), PoolManager.amt(p.reserveT),
                ExitTicket.Stage.CLOSE));
        mgr.close(p, new PoolManager.Result() {
            @Override public void onPosted(String txpowid) {
                ExitAuthority.revoke();
                ExitStore.set(ctx, p.address, p.opk, ExitStore.State.POSTED, "", txpowid, 0);
                LpStore.remove(ctx, p.address);
                ActivityLog.record(ctx, ActivityLog.CLOSE,
                        "Automatic withdrawal after restore  ·  " + p.address, txpowid, currentBlock());
                tally[0]++;
                cb.onProgress("Withdrawn ✓ " + txpowid);
                forward(p, () -> step(queue, i + 1, tally, cb));
            }
            @Override public void onFailed(String message) {
                ExitAuthority.revoke();
                boolean uncertain = message != null && message.contains(NodeApi.ERR_WRITE_UNCERTAIN);
                ExitStore.set(ctx, p.address, p.opk,
                        uncertain ? ExitStore.State.UNCERTAIN : ExitStore.State.FAILED, message, "", 0);
                tally[1]++;
                cb.onProgress((uncertain ? "Withdrawal status UNKNOWN for " : "Could not withdraw ")
                        + p.address + ": " + message);
                step(queue, i + 1, tally, cb);
            }
        });
    }

    /**
     * Second hop: move what the close landed at $OADR onward to a default-64 wallet address.
     *
     * Covered by the exception on purpose. The covenant pins the owner exit to $OADR, which is a
     * {@code newaddress} key a seed-only restore does not reproduce — so stopping after the close would swap a
     * stranded pool for stranded funds, which is worse: a pool at least has a durable recipe and a covenant an
     * archive can prove. Two leaves, and the user has nothing left to do.
     */
    private void forward(Pool p, Runnable next) {
        ExitAuthority.grant(new ExitTicket(p.opk, p.address, p.oadr, p.tok,
                p.coinidM, p.coinidT, PoolManager.amt(p.reserveM), PoolManager.amt(p.reserveT),
                ExitTicket.Stage.FORWARD));
        mgr.forwardOwnerFunds(p.oadr, new PoolManager.ForwardResult() {
            @Override public void onForwarded(String txpowid, int coins) {
                ExitAuthority.revoke();
                ExitStore.set(ctx, p.address, p.opk, ExitStore.State.DONE, "", "", 0);
                next.run();
            }
            @Override public void onNothing() {
                ExitAuthority.revoke();
                // The close is still confirming, so there is nothing at $OADR yet. Leave it POSTED: the funds are
                // out of the covenant and "Collect withdrawn funds to my wallet" finishes the job.
                next.run();
            }
            @Override public void onFailed(String message) {
                ExitAuthority.revoke();
                ExitStore.set(ctx, p.address, p.opk, ExitStore.State.POSTED,
                        "withdrawn to the owner address; the transfer to your wallet did not complete: " + message,
                        "", 0);
                next.run();
            }
        });
    }

    private void skip(List<Pool> queue, int i, Pool p, String why, int[] tally, Cb cb) {
        ExitStore.set(ctx, p.address, p.opk, ExitStore.State.SKIPPED, why, "", 0);
        tally[1]++;
        cb.onProgress("NOT withdrawn — " + p.address + ": " + why);
        step(queue, i + 1, tally, cb);
    }

    private int currentBlock() { return chainBlock; }
}

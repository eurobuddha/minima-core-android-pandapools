package com.eurobuddha.pandapools;

import java.util.List;
import java.util.Locale;

/**
 * Authority to make the two signatures that empty ONE pool back to its owner, and nothing else.
 *
 * THE PROBLEM THIS SOLVES. A restored recipe is signing-quarantined ({@code signingStateUnverified}), and
 * {@link PoolRefresher} excludes quarantined pools from keep-fresh. So a restored pool cannot refresh, ages past
 * the ~1700-block cascade, and strands — the safe state is a slow death. Its only exits today are the owner
 * attestation, which unlocks UNLIMITED signing forever, or losing the pool. This is the third door: exactly two
 * signatures, then the pool is gone and so is the exposure.
 *
 * WHY A SHAPE AND NOT A MODE. Nothing in the call chain carries authority — no boolean, no flag, no parameter.
 * Each guard looks the ticket up for itself and re-verifies it against the ACTUAL command list it is about to
 * run. So a caller cannot assert "this is a permitted close"; it can only build a transaction that either is or
 * is not one. A widened exception is then not a matter of trust, it is a matter of the command list failing to
 * match, which is what the adversarial tests pin.
 *
 * WHAT IT DELIBERATELY DOES NOT WAIVE. Only {@code signingStateUnverified} is set aside.
 * {@link OwnerKeyRecovery#baseSigningAllowed} still applies in full, and this class adds two refusals of its
 * own that ordinary signing does not need:
 *   - the recipe must carry a KNOWN use floor. {@code minimumOwnerUses} defaults to -1, and {@code uses >= -1}
 *     is trivially true, so a backup that recorded no counter gives no regression signal at all. That is the
 *     incident shape — a seed-restored wallet reading 0 against a -1 floor — and it must refuse, not pass.
 *   - the reserves must be aged and their age KNOWN. Young reserves mean only $OPK could have recreated them,
 *     so another live copy of the wallet is signing right now; two nodes signing one key is a guaranteed reuse.
 *     PoolRefresher treats an unknown age as "aging" because a needless refresh is harmless, but for a SPEND the
 *     asymmetry reverses, so unknown fails closed here.
 *
 * None of this makes signing safe. It makes the exposure bounded and terminal, and refuses every case where a
 * reuse is detectable. A signature the original node made that never reached the chain is invisible to all of it.
 */
final class ExitTicket {

    /** Which of the two permitted signatures this ticket is for. */
    enum Stage {
        /** Spend both covenant reserve coins to the recipe's $OADR. */
        CLOSE,
        /** Move what landed at $OADR onward to a node-supplied default-64 wallet address. */
        FORWARD
    }

    /** Two leaves per exit: the close, plus the forward hop ($OADR is RETURN SIGNEDBY($OPK)). */
    static final int SIGNATURES_PER_EXIT = 2;

    /** Hard lifetime cap per covenant address, so re-issuing after failures can never become an open unlock. */
    static final int MAX_LIFETIME_SIGNATURES = 4;

    final String opk;
    final String address;      // covenant address
    final String oadr;         // owner payout address the covenant pins
    final String tok;          // token id of the non-MINIMA leg
    final String coinidM;
    final String coinidT;
    final String amountM;      // exact amt() strings, as the close will emit them
    final String amountT;
    final Stage stage;

    ExitTicket(String opk, String address, String oadr, String tok,
               String coinidM, String coinidT, String amountM, String amountT, Stage stage) {
        this.opk = lower(opk); this.address = lower(address); this.oadr = lower(oadr); this.tok = lower(tok);
        this.coinidM = lower(coinidM); this.coinidT = lower(coinidT);
        this.amountM = amountM; this.amountT = amountT; this.stage = stage;
    }

    /**
     * Whether a pool is even eligible for an exit ticket, independent of any command list. Pure so the whole
     * decision is testable. {@code nodeUses} is the counter the node reports, null when unreadable.
     */
    static boolean eligible(Pool p, boolean reservesVerified, Integer nodeUses, int chainBlock) {
        if (p == null || !reservesVerified) return false;
        if (!OwnerKeyRecovery.baseSigningAllowed(p, nodeUses)) return false;
        if (p.minimumOwnerUses < 0) return false;                     // no recorded floor ⇒ no regression signal
        int age = p.reserveAge(chainBlock);
        if (age <= 0) return false;                                   // unknown age fails closed for a spend
        return age > PoolRefresher.REFRESH_BLOCKS;                     // young ⇒ another node is signing this key
    }

    /** Whether this ticket authorises exactly the transaction {@code cmds} builds, for pool {@code p}. */
    boolean permits(List<String> cmds, Pool p) {
        if (cmds == null || cmds.isEmpty() || p == null) return false;
        if (!lower(p.address).equals(address) || !lower(p.opk).equals(opk)) return false;
        return stage == Stage.CLOSE ? permitsClose(cmds) : false;      // FORWARD shape lands with its issuer
    }

    /**
     * The close shape, verified against the command list itself:
     * one {@code ppclose_} transaction, exactly the two reserve coins in, exactly two outputs to $OADR with the
     * exact amounts, one owner signature, no state output, and nothing else of any kind.
     */
    boolean permitsClose(List<String> cmds) {
        String txid = null;
        int inputs = 0, outputs = 0, ownerSigns = 0, autoSigns = 0, creates = 0, basics = 0, checks = 0;
        boolean sawM = false, sawT = false, outM = false, outT = false;

        for (String c : cmds) {
            String verb = Cmd.verb(c);
            if (verb == null) return false;
            String id = Cmd.param(c, "id");
            if (id == null) return false;                             // every command must name exactly one txn
            if (txid == null) txid = id;
            else if (!txid.equals(id)) return false;                   // no splicing across two open transactions

            switch (verb) {
                case "txncreate":
                    creates++;
                    if (!id.startsWith("ppclose_")) return false;
                    break;
                case "txninput": {
                    inputs++;
                    String coin = lower(Cmd.param(c, "coinid"));
                    if (coin == null) return false;
                    if (coin.equals(coinidM) && !sawM) sawM = true;
                    else if (coin.equals(coinidT) && !sawT) sawT = true;
                    else return false;                                 // a third input, or a duplicate
                    break;
                }
                case "txnoutput": {
                    outputs++;
                    String addr = lower(Cmd.param(c, "address"));
                    String amount = Cmd.param(c, "amount");
                    String tokenid = Cmd.param(c, "tokenid");
                    String storestate = Cmd.param(c, "storestate");
                    if (addr == null || amount == null) return false;
                    if (!oadr.equals(addr)) return false;              // the covenant pins this; so do we
                    if (!"false".equals(storestate)) return false;     // no state riding along
                    if (tokenid == null) {                             // the MINIMA leg
                        if (outM || !amount.equals(amountM)) return false;
                        outM = true;
                    } else {                                           // the token leg
                        if (outT || !tok.equals(lower(tokenid)) || !amount.equals(amountT)) return false;
                        outT = true;
                    }
                    break;
                }
                case "txnsign": {
                    String key = Cmd.param(c, "publickey");
                    if (key == null) return false;
                    if ("auto".equals(key)) autoSigns++;
                    else if (opk.equals(lower(key))) ownerSigns++;
                    else return false;                                 // never a third party's key
                    break;
                }
                case "txnbasics": basics++; break;
                case "txncheck": checks++; break;
                default: return false;                                 // txnstate, txnpost, send, anything else
            }
        }
        return creates == 1 && inputs == 2 && sawM && sawT
                && outputs == 2 && outM && outT
                && ownerSigns == 1 && autoSigns <= 1
                && basics <= 1 && checks <= 1;
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
}

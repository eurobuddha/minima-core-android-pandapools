package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the accounting export: a full transaction history, per-position liquidity figures, and a balance
 * reconciliation.
 *
 * Pure logic — no Android, no file I/O, no node call — so it is unit-testable on the JVM and an export is
 * deterministic: the same stored history always produces the same file.
 *
 * Everything is denominated in the assets themselves. MINIMA/mxUSDT is the pair, so the token leg IS the
 * unit of account and every price in here is the one the transaction actually executed at, computed from
 * the two on-chain legs. Nothing is fetched from a market and nothing is estimated.
 *
 * Four artefacts:
 *   • {@code pandapools-transactions.csv} — one row per PandaPools transaction (pool creates showing BOTH
 *     legs, every trade, deposits, withdrawals, maintenance).
 *   • {@code wallet-ledger.csv}           — one row per token movement across the WHOLE wallet, oldest
 *     first, with a running balance. This is the file that reconciles.
 *   • {@code lp-positions.csv}            — one row per pool, aggregated from the ledger.
 *   • {@code summary.txt}                 — totals, the reconciliation, and provenance.
 *
 * On P/L: this deliberately does NOT pick a cost-basis method (FIFO / LIFO / average). That is an
 * accounting policy decision, not ours to invent. What it gives instead is every trade with its exact
 * executed price plus unambiguous gross totals and volume-weighted averages, which is the raw material any
 * basis method needs. See the TRADING section of {@code summary.txt}.
 */
public final class AccountingExport {

    public static final String FILE_TX      = "pandapools-transactions.csv";
    public static final String FILE_LEDGER  = "wallet-ledger.csv";
    public static final String FILE_LP      = "lp-positions.csv";
    public static final String FILE_SUMMARY = "summary.txt";

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    /** Amounts are reported exactly as they moved on-chain, but a DERIVED figure (a price, a computed
     *  round-trip P/L) is a division and would otherwise carry 20+ meaningless significant figures into a
     *  spreadsheet. These are the scales those two are rounded to. */
    private static final int PRICE_DP = 12;
    private static final int PL_DP = 8;
    private static final String EOL = "\r\n";     // Excel
    private static final String BOM = "﻿";   // ...and it needs this to read UTF-8

    private AccountingExport() {}

    /** A pool's opening reserves, when {@link LpStore} still holds the snapshot (it is dropped on close). */
    public static final class LpOpening {
        public final BigDecimal minima, token, price;
        public final int block;
        public LpOpening(BigDecimal minima, BigDecimal token, BigDecimal price, int block) {
            this.minima = minima; this.token = token; this.price = price; this.block = block;
        }
    }

    /** Everything the report needs, gathered by the caller so this class stays free of Android. */
    public static final class Params {
        public List<HistoryEntry> rows = new ArrayList<>();          // chronological, oldest first
        public TxClassifier.Addrs addrs;
        public Map<String, String> symbols = new LinkedHashMap<>();  // tokenid -> display symbol
        /** Live node balances at export time. SENDABLE is funds free outside any contract — pool reserves
         *  are excluded from it, which is what the wallet ledger tracks. CONFIRMED additionally includes
         *  coins locked in tracked contracts (i.e. our live pool reserves). Both are reported so the
         *  reconciliation is unambiguous about which side any difference sits on. */
        public Map<String, BigDecimal> nodeSendable = new LinkedHashMap<>();
        public Map<String, BigDecimal> nodeConfirmed = new LinkedHashMap<>();
        public Map<String, LpOpening> openings = new LinkedHashMap<>();       // lowercased pool addr
        public String appVersion = "?";
        public long exportedAtMs;
        public boolean backfillDone;
        public String firstSyncTs = "", lastSyncTs = "", syncedTipBlock = "";
        public int dbRowCount;
        public long dbMinBlock, dbMaxBlock;
        public long fromMs = 0, toMs = Long.MAX_VALUE;               // the requested window
        public String windowLabel = "All time";
    }

    public static final class Report {
        public String transactionsCsv, ledgerCsv, lpCsv, summaryTxt;
        public int pandaCount, ledgerRows, poolCount;
        /** True when every token's ledger closing balance matches what the node reports. */
        public boolean reconciles;
    }

    // ---- build ----

    public static Report build(Params p) {
        List<TxClassifier.Tx> all = new ArrayList<>();
        for (HistoryEntry e : p.rows) all.add(TxClassifier.classify(e, p.addrs));

        Report r = new Report();
        r.transactionsCsv = transactions(all, p);
        Ledger led = ledger(all, p);
        r.ledgerCsv = led.csv;
        r.ledgerRows = led.rows;
        Pools pools = lpPositions(all, p);
        r.lpCsv = pools.csv;
        r.poolCount = pools.count;
        for (TxClassifier.Tx t : all) if (t.isPandaPools() && t.movedOurFunds()) r.pandaCount++;
        r.summaryTxt = summary(all, led, p);
        r.reconciles = led.reconciles(p.nodeSendable);
        return r;
    }

    // ---- pandapools-transactions.csv ----

    private static String transactions(List<TxClassifier.Tx> all, Params p) {
        StringBuilder b = new StringBuilder(BOM);
        header(b, "date_utc", "block", "txpowid", "type", "pool_address",
                "minima_in", "minima_out", "token_symbol", "tokenid", "token_in", "token_out",
                "implied_price_token_per_minima", "burn_minima", "counterparty");

        for (TxClassifier.Tx t : all) {
            // Someone else's trade against a pool we track is in the node's relevant history but moved none
            // of our funds — it is not our transaction and must not appear in our P/L.
            if (!t.isPandaPools() || !t.movedOurFunds()) continue;
            HistoryEntry e = t.entry;
            row(b,
                text(date(e.timemilli)),
                num(e.block),
                text(e.txpowid),
                text(t.type),
                text(t.poolAddress),
                num(positive(t.minimaDelta)),
                num(negative(t.minimaDelta)),
                text(symbol(p, t.tokenid)),
                text(t.tokenid),
                num(positive(t.tokenDelta)),
                num(negative(t.tokenDelta)),
                price(t.impliedPrice),
                num(t.burn),
                text(e.counterparty));
        }
        return b.toString();
    }

    // ---- wallet-ledger.csv ----

    private static final class Ledger {
        String csv;
        int rows;
        final Map<String, BigDecimal> closing = new LinkedHashMap<>();
        final Map<String, BigDecimal> totalIn = new LinkedHashMap<>();
        final Map<String, BigDecimal> totalOut = new LinkedHashMap<>();

        boolean reconciles(Map<String, BigDecimal> nodeBalances) {
            for (Map.Entry<String, BigDecimal> en : closing.entrySet()) {
                BigDecimal node = nodeBalances.get(en.getKey());
                if (node == null || node.compareTo(en.getValue()) != 0) return false;
            }
            return !closing.isEmpty();
        }
    }

    private static Ledger ledger(List<TxClassifier.Tx> all, Params p) {
        Ledger l = new Ledger();
        StringBuilder b = new StringBuilder(BOM);
        header(b, "date_utc", "block", "txpowid", "tokenid", "symbol",
                "delta", "running_balance", "type", "is_pandapools", "pool_address");

        for (TxClassifier.Tx t : all) {
            HistoryEntry e = t.entry;
            for (Map.Entry<String, BigDecimal> d : e.deltaMap().entrySet()) {
                BigDecimal delta = d.getValue();
                if (delta.signum() == 0) continue;          // no movement, nothing to book
                String tid = d.getKey();
                BigDecimal run = l.closing.getOrDefault(tid, BigDecimal.ZERO).add(delta);
                l.closing.put(tid, run);
                if (delta.signum() > 0) l.totalIn.merge(tid, delta, BigDecimal::add);
                else l.totalOut.merge(tid, delta.abs(), BigDecimal::add);
                l.rows++;
                row(b,
                    text(date(e.timemilli)),
                    num(e.block),
                    text(e.txpowid),
                    text(tid),
                    text(symbol(p, tid)),
                    num(delta),
                    num(run),
                    text(t.type),
                    text(t.isPandaPools() ? "yes" : "no"),
                    text(t.poolAddress));
            }
        }
        l.csv = b.toString();
        return l;
    }

    // ---- lp-positions.csv ----

    private static final class Pos {
        BigDecimal mDep = BigDecimal.ZERO, mWd = BigDecimal.ZERO;
        BigDecimal tDep = BigDecimal.ZERO, tWd = BigDecimal.ZERO;
        long firstBlock = Long.MAX_VALUE, lastBlock = 0;
        String tokenid, lastAction;
        BigDecimal exitPrice;
    }

    private static final class Pools { String csv; int count; }

    private static Pools lpPositions(List<TxClassifier.Tx> all, Params p) {
        Map<String, Pos> byPool = new LinkedHashMap<>();
        for (TxClassifier.Tx t : all) {
            if (!t.isLiquidityMove() || !t.movedOurFunds() || t.poolAddress == null) continue;
            Pos pos = byPool.computeIfAbsent(t.poolAddress.toLowerCase(), k -> new Pos());
            if (t.tokenid != null) pos.tokenid = t.tokenid;
            pos.firstBlock = Math.min(pos.firstBlock, t.entry.block);
            pos.lastBlock = Math.max(pos.lastBlock, t.entry.block);
            pos.lastAction = t.type;
            // Wallet-signed deltas, restated from the POSITION's point of view: money leaving the wallet is
            // money deposited into the pool, and vice versa.
            if (t.minimaDelta.signum() < 0) pos.mDep = pos.mDep.add(t.minimaDelta.abs());
            else pos.mWd = pos.mWd.add(t.minimaDelta);
            if (t.tokenDelta.signum() < 0) pos.tDep = pos.tDep.add(t.tokenDelta.abs());
            else pos.tWd = pos.tWd.add(t.tokenDelta);
            if (TxClassifier.POOL_WITHDRAW.equals(t.type) && t.impliedPrice != null) pos.exitPrice = t.impliedPrice;
        }

        StringBuilder b = new StringBuilder(BOM);
        header(b, "pool_address", "token_symbol", "tokenid", "first_block", "last_block", "status",
                "last_action", "minima_deposited", "minima_withdrawn", "token_deposited", "token_withdrawn",
                "net_minima", "net_token", "realized_pl_in_token", "exit_price_token_per_minima",
                "opening_reserve_minima", "opening_reserve_token", "opening_price");

        for (Map.Entry<String, Pos> en : byPool.entrySet()) {
            Pos q = en.getValue();
            boolean closed = TxClassifier.POOL_WITHDRAW.equals(q.lastAction)
                          || TxClassifier.POOL_MIGRATE.equals(q.lastAction);
            BigDecimal netM = q.mWd.subtract(q.mDep);
            BigDecimal netT = q.tWd.subtract(q.tDep);
            // Realized only once the position is actually closed: while it is open the remaining value sits
            // in live reserves this export cannot see, so any figure would be a guess.
            BigDecimal pl = (closed && q.exitPrice != null)
                    ? netT.add(netM.multiply(q.exitPrice, MC)) : null;
            LpOpening op = p.openings.get(en.getKey());
            row(b,
                text(en.getKey()),
                text(symbol(p, q.tokenid)),
                text(q.tokenid),
                num(q.firstBlock == Long.MAX_VALUE ? 0 : q.firstBlock),
                num(q.lastBlock),
                text(closed ? "closed" : "open"),
                text(q.lastAction),
                num(q.mDep), num(q.mWd), num(q.tDep), num(q.tWd),
                num(netM), num(netT),
                pl(pl),
                price(q.exitPrice),
                num(op == null ? null : op.minima),
                num(op == null ? null : op.token),
                num(op == null ? null : op.price));
        }
        Pools out = new Pools();
        out.csv = b.toString();
        out.count = byPool.size();
        return out;
    }

    // ---- summary.txt ----

    private static String summary(List<TxClassifier.Tx> all, Ledger l, Params p) {
        StringBuilder b = new StringBuilder();
        b.append("PandaPools — accounting export").append('\n');
        b.append("======================================================================").append('\n');
        b.append("Generated      : ").append(date(p.exportedAtMs)).append('\n');
        b.append("App version    : ").append(p.appVersion).append('\n');
        b.append("Window         : ").append(p.windowLabel).append('\n');
        b.append("Transactions   : ").append(p.rows.size()).append(" in window, ")
         .append(p.dbRowCount).append(" stored in total").append('\n');
        b.append("Block range    : ").append(p.dbMinBlock).append(" – ").append(p.dbMaxBlock).append('\n');
        b.append('\n');

        // ---- reconciliation ----
        b.append("BALANCE RECONCILIATION").append('\n');
        b.append("----------------------------------------------------------------------").append('\n');
        b.append("The ledger closing balance is every movement this app has recorded, summed.").append('\n');
        b.append("Where it differs from the node's balance, the difference is holdings that").append('\n');
        b.append("predate the local history — an OPENING BALANCE, not a missing transaction.").append('\n');
        b.append('\n');
        b.append("Two node figures are shown because they measure different things:").append('\n');
        b.append("  SENDABLE  — funds free outside any contract. Liquidity sitting in a live").append('\n');
        b.append("              pool is NOT counted here; it is in ").append(FILE_LP).append('\n');
        b.append("  CONFIRMED — additionally includes coins locked in tracked contracts,").append('\n');
        b.append("              i.e. your live pool reserves.").append('\n');
        b.append('\n');

        for (Map.Entry<String, BigDecimal> en : l.closing.entrySet()) {
            String tid = en.getKey();
            BigDecimal ledgerClose = en.getValue();
            BigDecimal sendable = p.nodeSendable.get(tid);
            BigDecimal confirmed = p.nodeConfirmed.get(tid);
            b.append("  ").append(symbol(p, tid)).append("  (").append(tid).append(')').append('\n');
            b.append("    total in                 : ").append(plain(l.totalIn.get(tid))).append('\n');
            b.append("    total out                : ").append(plain(l.totalOut.get(tid))).append('\n');
            b.append("    ledger closing balance   : ").append(plain(ledgerClose)).append('\n');
            if (sendable == null && confirmed == null) {
                b.append("    node reported balance    : (unavailable — node not reachable at export)").append('\n');
            } else {
                b.append("    node sendable            : ").append(plain(sendable)).append('\n');
                b.append("    node confirmed           : ").append(plain(confirmed)).append('\n');
                if (sendable != null) {
                    BigDecimal diff = sendable.subtract(ledgerClose);
                    b.append("    difference vs sendable   : ").append(plain(diff));
                    b.append(diff.signum() == 0 ? "   <- reconciles exactly" : "   <- opening balance / pre-sync history").append('\n');
                }
                if (confirmed != null) {
                    b.append("    difference vs confirmed  : ").append(plain(confirmed.subtract(ledgerClose))).append('\n');
                }
            }
            b.append('\n');
        }

        if (!p.backfillDone) {
            b.append("  ! History backfill has NOT finished. Transactions older than the range").append('\n');
            b.append("    above are not yet in the local store, so an unaccounted opening").append('\n');
            b.append("    balance here is expected. Re-export once the Activity tab has").append('\n');
            b.append("    finished syncing for a complete picture.").append('\n').append('\n');
        }

        // ---- trading ----
        b.append("TRADING").append('\n');
        b.append("----------------------------------------------------------------------").append('\n');
        b.append("No cost-basis method (FIFO / LIFO / average) is applied here — that is an").append('\n');
        b.append("accounting policy choice. Every trade with its exact executed price is in").append('\n');
        b.append(FILE_TX).append(" so any method can be applied to it.").append('\n').append('\n');

        BigDecimal mSold = BigDecimal.ZERO, tGot = BigDecimal.ZERO;   // sold MINIMA for token
        BigDecimal mBought = BigDecimal.ZERO, tSpent = BigDecimal.ZERO;
        int swaps = 0;
        for (TxClassifier.Tx t : all) {
            if (!TxClassifier.SWAP.equals(t.type) || !t.movedOurFunds()) continue;
            swaps++;
            if (t.minimaDelta.signum() < 0) { mSold = mSold.add(t.minimaDelta.abs()); tGot = tGot.add(positiveOrZero(t.tokenDelta)); }
            else { mBought = mBought.add(t.minimaDelta); tSpent = tSpent.add(t.tokenDelta.abs()); }
        }
        b.append("  swaps                      : ").append(swaps).append('\n');
        b.append("  MINIMA sold                : ").append(plain(mSold)).append('\n');
        b.append("  received for it            : ").append(plain(tGot)).append('\n');
        b.append("  avg sell price (tok/MINIMA): ").append(plain(ratio(tGot, mSold))).append('\n');
        b.append("  MINIMA bought              : ").append(plain(mBought)).append('\n');
        b.append("  paid for it                : ").append(plain(tSpent)).append('\n');
        b.append("  avg buy price  (tok/MINIMA): ").append(plain(ratio(tSpent, mBought))).append('\n');
        b.append("  net MINIMA from trading    : ").append(plain(mBought.subtract(mSold))).append('\n');
        b.append("  net token   from trading   : ").append(plain(tGot.subtract(tSpent))).append('\n');
        b.append('\n');

        // ---- costs ----
        BigDecimal burn = BigDecimal.ZERO;
        int keepalive = 0;
        for (TxClassifier.Tx t : all) {
            burn = burn.add(t.burn);
            if (TxClassifier.POOL_KEEPALIVE.equals(t.type)) keepalive++;
        }
        b.append("COSTS").append('\n');
        b.append("----------------------------------------------------------------------").append('\n');
        b.append("  MINIMA burned (all txns)   : ").append(plain(burn)).append('\n');
        b.append("  keep-alive transactions    : ").append(keepalive)
         .append("  (pool maintenance — beacon dust, not liquidity)").append('\n');
        b.append('\n');

        // ---- provenance ----
        b.append("PROVENANCE").append('\n');
        b.append("----------------------------------------------------------------------").append('\n');
        b.append("  Source: this device's local mirror of the Minima node's `history relevant:true`,").append('\n');
        b.append("  keyed by txpowid and never pruned. All amounts are on-chain values as recorded;").append('\n');
        b.append("  all prices are computed from the two legs of the transaction itself.").append('\n');
        b.append("  backfill complete        : ").append(p.backfillDone ? "yes" : "NO — still syncing").append('\n');
        b.append("  first sync               : ").append(tsOrDash(p.firstSyncTs)).append('\n');
        b.append("  last sync                : ").append(tsOrDash(p.lastSyncTs)).append('\n');
        b.append("  node tip at last sync    : ").append(dash(p.syncedTipBlock)).append('\n');
        return b.toString();
    }

    // ---- formatting helpers ----

    private static String symbol(Params p, String tokenid) {
        if (tokenid == null) return "";
        if (Util.isMinima(tokenid)) return "MINIMA";
        String s = p.symbols.get(tokenid);
        if (s != null && !s.isEmpty()) return s;
        s = Util.tokenNameCached(tokenid);
        return (s != null && !s.isEmpty()) ? s : Util.shorten(tokenid);
    }

    private static BigDecimal positive(BigDecimal d) { return d != null && d.signum() > 0 ? d : null; }
    private static BigDecimal negative(BigDecimal d) { return d != null && d.signum() < 0 ? d.abs() : null; }
    private static BigDecimal positiveOrZero(BigDecimal d) { return d != null && d.signum() > 0 ? d : BigDecimal.ZERO; }

    private static BigDecimal ratio(BigDecimal num, BigDecimal den) {
        if (num == null || den == null || den.signum() == 0) return null;
        return num.divide(den, MC);
    }

    /** Plain decimal, never scientific notation — a spreadsheet must read these as numbers. */
    private static String plain(BigDecimal d) {
        if (d == null) return "—";
        return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
    }

    private static String date(long ms) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String tsOrDash(String ms) {
        try { return date(Long.parseLong(ms)); } catch (Exception e) { return "—"; }
    }

    private static String dash(String s) { return (s == null || s.isEmpty()) ? "—" : s; }

    // ---- CSV ----

    private static void header(StringBuilder b, String... cols) { row(b, quoteAll(cols)); }

    private static String[] quoteAll(String[] cols) {
        String[] out = new String[cols.length];
        for (int i = 0; i < cols.length; i++) out[i] = text(cols[i]);
        return out;
    }

    private static void row(StringBuilder b, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(',');
            b.append(cells[i]);
        }
        b.append(EOL);
    }

    /**
     * A quoted TEXT cell. Token names come from on-chain metadata and are attacker-controllable, so a
     * leading =, +, - or @ is prefixed with an apostrophe: without it a spreadsheet would evaluate the cell
     * as a formula on open.
     */
    static String text(String s) {
        if (s == null) s = "";
        if (!s.isEmpty()) {
            char c = s.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@') s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** A quoted NUMERIC cell. Never formula-escaped — a negative number must stay a negative number.
     *  Null renders as empty so a blank means "not applicable", never zero. */
    static String num(BigDecimal d) {
        return d == null ? "\"\"" : "\"" + d.stripTrailingZeros().toPlainString() + "\"";
    }

    static String num(long v) { return "\"" + v + "\""; }

    /** A derived price, rounded to a scale a human can read. */
    static String price(BigDecimal d) { return scaled(d, PRICE_DP); }

    /** A derived P/L figure, rounded to the token grain. */
    static String pl(BigDecimal d) { return scaled(d, PL_DP); }

    private static String scaled(BigDecimal d, int dp) {
        if (d == null) return "\"\"";
        BigDecimal r = d.setScale(dp, RoundingMode.HALF_UP).stripTrailingZeros();
        return "\"" + (r.signum() == 0 ? "0" : r.toPlainString()) + "\"";
    }
}

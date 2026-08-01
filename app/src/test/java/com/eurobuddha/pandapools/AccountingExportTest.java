package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The export is only worth anything if the numbers tie out, so the load-bearing claims are pinned here:
 * the running balance is the sum of movements, a shortfall against the node is disclosed as an opening
 * balance rather than hidden, and nothing a token can be named can turn into a spreadsheet formula.
 */
public class AccountingExportTest {

    private static final String TOK  = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    private static final String POOL = "0xAAA1";
    private static final String MINE = "MxMINE";

    private static TxClassifier.Addrs addrs(final String... known) {
        final Set<String> set = new HashSet<>();
        for (String k : known) set.add(k.toLowerCase());
        return coinsJson -> {
            if (coinsJson == null || coinsJson.isEmpty()) return null;
            try {
                JSONArray a = new JSONArray(coinsJson);
                for (int i = 0; i < a.length(); i++) {
                    String addr = a.getJSONObject(i).optString("addr", "");
                    if (!addr.isEmpty() && set.contains(addr.toLowerCase())) return addr;
                }
            } catch (Exception ignore) {}
            return null;
        };
    }

    private static String coins(String... triples) {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < triples.length; i += 3) {
                JSONObject o = new JSONObject();
                o.put("addr", triples[i]); o.put("amount", triples[i + 1]); o.put("tokenid", triples[i + 2]);
                a.put(o);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return a.toString();
    }

    private static String deltas(String... pairs) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new RuntimeException(e); }
        return o.toString();
    }

    private static HistoryEntry entry(long block, String dir, String d, String in, String out) {
        HistoryEntry e = new HistoryEntry();
        e.txpowid = "0xTX" + block; e.block = block;
        e.timemilli = 1_700_000_000_000L + block * 60_000L;
        e.direction = dir; e.incoming = "received".equals(dir);
        e.deltas = d; e.inputs = in; e.outputs = out;
        e.tokenid = "0x00"; e.tokenName = "Minima"; e.amount = "0";
        return e;
    }

    private static AccountingExport.Params params() {
        AccountingExport.Params p = new AccountingExport.Params();
        p.addrs = addrs(POOL);
        p.symbols.put("0x00", "MINIMA");
        p.symbols.put(TOK, "mxUSDT");
        p.exportedAtMs = 1_700_000_000_000L;
        p.backfillDone = true;
        return p;
    }

    // ---- reconciliation ----

    @Test public void runningBalanceIsTheSumOfEveryMovement() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "received", deltas("0x00", "+1000"),
                coins("MxOTHER", "1000", "0x00"), coins(MINE, "1000", "0x00")));
        p.rows.add(entry(2, "sent", deltas("0x00", "-250"),
                coins(MINE, "1000", "0x00"), coins("MxOTHER", "250", "0x00", MINE, "750", "0x00")));
        p.rows.add(entry(3, "received", deltas("0x00", "+30"),
                coins("MxOTHER", "30", "0x00"), coins(MINE, "30", "0x00")));
        p.nodeSendable.put("0x00", new BigDecimal("780"));
        p.nodeConfirmed.put("0x00", new BigDecimal("780"));

        AccountingExport.Report r = AccountingExport.build(p);

        assertEquals(3, r.ledgerRows);
        assertTrue("1000 - 250 + 30 = 780 must tie to the node", r.reconciles);
        assertTrue(r.summaryTxt.contains("ledger closing balance   : 780"));
        assertTrue(r.summaryTxt.contains("reconciles exactly"));
        // the ledger itself carries the cumulative column
        assertTrue(r.ledgerCsv.contains("\"780\""));
    }

    @Test public void aShortfallIsDisclosedAsAnOpeningBalanceNotHidden() {
        AccountingExport.Params p = params();
        p.backfillDone = false;
        p.rows.add(entry(1, "sent", deltas("0x00", "-100"),
                coins(MINE, "500", "0x00"), coins("MxOTHER", "100", "0x00", MINE, "400", "0x00")));
        // the node holds far more than our history explains — that surplus predates the local sync
        p.nodeSendable.put("0x00", new BigDecimal("400"));

        AccountingExport.Report r = AccountingExport.build(p);

        assertFalse(r.reconciles);
        assertTrue(r.summaryTxt.contains("ledger closing balance   : -100"));
        assertTrue(r.summaryTxt.contains("difference vs sendable   : 500"));
        assertTrue(r.summaryTxt.contains("opening balance"));
        assertTrue("an unfinished backfill must be stated up front",
                r.summaryTxt.contains("backfill has NOT finished"));
    }

    @Test public void anUnreachableNodeSaysSoRatherThanOmittingTheCheck() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "received", deltas("0x00", "+10"),
                coins("MxOTHER", "10", "0x00"), coins(MINE, "10", "0x00")));

        AccountingExport.Report r = AccountingExport.build(p);

        assertFalse(r.reconciles);
        assertTrue(r.summaryTxt.contains("node not reachable"));
    }

    // ---- the transactions file ----

    @Test public void createAndSwapAppearWithBothLegs() {
        AccountingExport.Params p = params();
        // open a pool with 168,000 MINIMA + 800 mxUSDT
        p.rows.add(entry(1, "sent", deltas("0x00", "-168000", TOK, "-800"),
                coins(MINE, "168000", "0x00", MINE, "800", TOK),
                coins(POOL, "168000", "0x00", POOL, "800", TOK)));
        // then sell 1,000 MINIMA into it
        p.rows.add(entry(2, "sent", deltas("0x00", "-1000", TOK, "+4.7"),
                coins(POOL, "168000", "0x00", POOL, "800", TOK, MINE, "1000", "0x00"),
                coins(POOL, "169000", "0x00", POOL, "795.3", TOK, MINE, "4.7", TOK)));

        AccountingExport.Report r = AccountingExport.build(p);

        assertEquals(2, r.pandaCount);
        String[] lines = r.transactionsCsv.split("\r\n");
        assertEquals(3, lines.length);                       // header + 2

        // the create: both legs out, priced at 800/168000
        assertTrue(lines[1].contains("\"POOL_CREATE\""));
        assertTrue(lines[1].contains("\"168000\""));
        assertTrue(lines[1].contains("\"800\""));
        assertTrue(lines[1].contains("\"mxUSDT\""));

        // the swap: MINIMA out, token in, executed at 0.0047
        assertTrue(lines[2].contains("\"SWAP\""));
        assertTrue(lines[2].contains("\"1000\""));
        assertTrue(lines[2].contains("\"4.7\""));
        assertTrue(lines[2].contains("\"0.0047\""));
    }

    @Test public void otherPeoplesTradesAreKeptOutOfTheTransactionsFile() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "self", deltas("0x00", "0", TOK, "0"),
                coins(POOL, "1000", "0x00", POOL, "5", TOK, "MxTHEM", "10", "0x00"),
                coins(POOL, "1010", "0x00", POOL, "4.9", TOK, "MxTHEM", "0.1", TOK)));

        AccountingExport.Report r = AccountingExport.build(p);

        assertEquals(0, r.pandaCount);
        assertEquals(1, r.transactionsCsv.split("\r\n").length);   // header only
        assertEquals("nothing of ours moved, so nothing to book", 0, r.ledgerRows);
    }

    // ---- positions ----

    @Test public void closedPositionRealizesItsRoundTrip() {
        AccountingExport.Params p = params();
        // in: 1,000 MINIMA + 5 token.  out: 900 MINIMA + 6 token, exiting at 6/900
        p.rows.add(entry(1, "sent", deltas("0x00", "-1000", TOK, "-5"),
                coins(MINE, "1000", "0x00", MINE, "5", TOK),
                coins(POOL, "1000", "0x00", POOL, "5", TOK)));
        p.rows.add(entry(9, "received", deltas("0x00", "+900", TOK, "+6"),
                coins(POOL, "900", "0x00", POOL, "6", TOK),
                coins(MINE, "900", "0x00", MINE, "6", TOK)));

        AccountingExport.Report r = AccountingExport.build(p);

        assertEquals(1, r.poolCount);
        String row = r.lpCsv.split("\r\n")[1];
        assertTrue(row.contains("\"closed\""));
        assertTrue(row.contains("\"1000\""));      // minima deposited
        assertTrue(row.contains("\"900\""));       // minima withdrawn
        assertTrue(row.contains("\"-100\""));      // net minima
        assertTrue(row.contains("\"1\""));         // net token: 6 - 5
    }

    @Test public void openPositionLeavesRealizedPlBlankRatherThanGuessing() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "sent", deltas("0x00", "-1000", TOK, "-5"),
                coins(MINE, "1000", "0x00", MINE, "5", TOK),
                coins(POOL, "1000", "0x00", POOL, "5", TOK)));

        AccountingExport.Report r = AccountingExport.build(p);
        String row = r.lpCsv.split("\r\n")[1];
        assertTrue(row.contains("\"open\""));
        // realized_pl_in_token is the 14th column and must be empty while value sits in live reserves
        assertEquals("", cell(row, 13));
    }

    // ---- trading aggregates ----

    @Test public void tradingSectionGivesGrossFlowsWithoutInventingACostBasis() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "sent", deltas("0x00", "-1000", TOK, "+5"),
                coins(POOL, "1", "0x00", MINE, "1000", "0x00"), coins(POOL, "2", "0x00", MINE, "5", TOK)));
        p.rows.add(entry(2, "received", deltas("0x00", "+500", TOK, "-3"),
                coins(POOL, "2", "0x00", MINE, "3", TOK), coins(POOL, "1", "0x00", MINE, "500", "0x00")));

        AccountingExport.Report r = AccountingExport.build(p);

        assertTrue(r.summaryTxt.contains("swaps                      : 2"));
        assertTrue(r.summaryTxt.contains("MINIMA sold                : 1000"));
        assertTrue(r.summaryTxt.contains("received for it            : 5"));
        assertTrue(r.summaryTxt.contains("avg sell price (tok/MINIMA): 0.005"));
        assertTrue(r.summaryTxt.contains("MINIMA bought              : 500"));
        assertTrue(r.summaryTxt.contains("avg buy price  (tok/MINIMA): 0.006"));
        assertTrue(r.summaryTxt.contains("net MINIMA from trading    : -500"));
        assertTrue("must not silently pick a basis method",
                r.summaryTxt.contains("No cost-basis method"));
    }

    // ---- CSV safety ----

    @Test public void textCellsAreQuotedEscapedAndDefused() {
        assertEquals("\"plain\"", AccountingExport.text("plain"));
        assertEquals("\"say \"\"hi\"\"\"", AccountingExport.text("say \"hi\""));
        assertEquals("\"a,b\"", AccountingExport.text("a,b"));
        assertEquals("\"line1\nline2\"", AccountingExport.text("line1\nline2"));
        assertEquals("\"\"", AccountingExport.text(null));
        // a token whose name is a formula must not execute when the sheet opens
        assertEquals("\"'=cmd|'/c calc'!A1\"", AccountingExport.text("=cmd|'/c calc'!A1"));
        assertEquals("\"'+1\"", AccountingExport.text("+1"));
        assertEquals("\"'@x\"", AccountingExport.text("@x"));
    }

    @Test public void numericCellsStayNumericAndNeverGetFormulaEscaped() {
        // the defusal must NOT touch a negative number — it would stop being a number
        assertEquals("\"-250\"", AccountingExport.num(new BigDecimal("-250")));
        assertEquals("\"0.0047\"", AccountingExport.num(new BigDecimal("0.00470")));
        // never scientific notation, whatever the scale
        assertEquals("\"1000\"", AccountingExport.num(new BigDecimal("1E+3")));
        assertEquals("\"0.000000001\"", AccountingExport.num(new BigDecimal("1E-9")));
        // blank means "not applicable", never zero
        assertEquals("\"\"", AccountingExport.num((BigDecimal) null));
    }

    @Test public void derivedFiguresAreRoundedButOnChainAmountsAreNot() {
        AccountingExport.Params p = params();
        // 800 / 168000.000000001 is a non-terminating division — it must not reach the sheet at 20 s.f.
        p.rows.add(entry(1, "sent", deltas("0x00", "-168000.000000001", TOK, "-800"),
                coins(MINE, "168000.000000001", "0x00", MINE, "800", TOK),
                coins(POOL, "168000", "0x00", POOL, "800", TOK)));

        String row = AccountingExport.build(p).transactionsCsv.split("\r\n")[1];

        assertTrue("price rounded to 12dp", row.contains("\"0.004761904762\""));
        assertTrue("the amount itself stays exact", row.contains("\"168000.000000001\""));
    }

    @Test public void ledgerRowsAreOrderedMinimaFirstSoTheFileIsStable() {
        AccountingExport.Params p = params();
        p.rows.add(entry(1, "sent", deltas(TOK, "-800", "0x00", "-1000"),
                coins(MINE, "1000", "0x00"), coins(POOL, "1000", "0x00", POOL, "800", TOK)));

        String[] lines = AccountingExport.build(p).ledgerCsv.split("\r\n");
        assertTrue("MINIMA leg first", lines[1].contains("\"MINIMA\""));
        assertTrue("token leg second", lines[2].contains("\"mxUSDT\""));
    }

    @Test public void everyFileStartsWithTheExcelByteOrderMark() {
        AccountingExport.Report r = AccountingExport.build(params());
        assertTrue(r.transactionsCsv.startsWith("﻿"));
        assertTrue(r.ledgerCsv.startsWith("﻿"));
        assertTrue(r.lpCsv.startsWith("﻿"));
    }

    /** nth comma-separated cell of a CSV row, unquoted. */
    private static String cell(String row, int n) {
        String[] parts = row.split("\",\"", -1);
        String s = parts[n];
        return s.replaceAll("^\"", "").replaceAll("\"$", "");
    }
}

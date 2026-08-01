package com.eurobuddha.pandapools;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs the accounting export end to end: asks the node for a live balance, reads the whole local history
 * off the UI thread, builds the reports via {@link AccountingExport}, and packs them into one ZIP.
 *
 * The database read and the report build never touch the main thread — a full history is thousands of rows
 * and would jank the UI. The node call runs first (on the main thread, as {@link NodeApi} requires) and its
 * result is handed to the background pass. A node that is unreachable is NOT fatal: the export still runs,
 * and the summary says the reconciliation figures were unavailable rather than silently omitting them.
 */
public final class ExportWriter {

    public interface Cb {
        void onDone(byte[] zip, String filename, AccountingExport.Report report);
        void onError(String message);
    }

    /** A time window to export. */
    public static final class Window {
        public final long fromMs, toMs;
        public final String label;
        public Window(long fromMs, long toMs, String label) { this.fromMs = fromMs; this.toMs = toMs; this.label = label; }
        public static Window allTime() { return new Window(0, Long.MAX_VALUE, "All time"); }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandapools-export"); t.setDaemon(true); return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ExportWriter() {}

    /** Build the export. The callback fires on the UI thread. */
    public static void run(final MainActivity act, final PandaAddrBook addrs, final Window win, final Cb cb) {
        if (act.node() == null) { cb.onError("No node connection."); return; }
        // Live balance first (main thread, per NodeApi's contract); the heavy work follows in the background.
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { background(act, addrs, win, j.optJSONArray("response"), cb); }
            @Override public void onError(String m) { background(act, addrs, win, null, cb); }
        });
    }

    private static void background(final MainActivity act, final PandaAddrBook addrs, final Window win,
                                   final JSONArray balances, final Cb cb) {
        EXEC.execute(() -> {
            try {
                AccountingExport.Params p = new AccountingExport.Params();
                p.addrs = addrs;
                p.exportedAtMs = System.currentTimeMillis();
                p.fromMs = win.fromMs; p.toMs = win.toMs; p.windowLabel = win.label;
                try {
                    p.appVersion = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
                } catch (Exception ignore) {}

                if (balances != null) {
                    for (int i = 0; i < balances.length(); i++) {
                        JSONObject b = balances.optJSONObject(i);
                        if (b == null) continue;
                        TokenBalance tb = TokenBalance.from(b);
                        if (tb.tokenid == null || tb.tokenid.isEmpty()) continue;
                        p.symbols.put(tb.tokenid, label(tb));
                        BigDecimal s = Util.decOr(tb.sendable, null);
                        BigDecimal c = Util.decOr(tb.confirmed, null);
                        if (s != null) p.nodeSendable.put(tb.tokenid, s);
                        if (c != null) p.nodeConfirmed.put(tb.tokenid, c);
                    }
                }

                HistoryDb db = act.history();
                p.rows = db.listChronological(win.fromMs, win.toMs);
                p.dbRowCount = db.count();
                long[] range = db.blockRange();
                p.dbMinBlock = range[0]; p.dbMaxBlock = range[1];
                p.backfillDone = "true".equals(db.getMeta("backfill_done", ""));
                p.firstSyncTs = db.getMeta("first_sync_ts", "");
                p.lastSyncTs = db.getMeta("last_sync_ts", "");
                p.syncedTipBlock = db.getMeta("synced_tip_block", "");

                // Opening reserves for any pool whose LpStore snapshot still exists (it is dropped on close,
                // so a closed pool simply has none — the position is still fully described by the ledger).
                for (Pool pool : OwnPoolStore.all(act)) {
                    if (pool.address == null) continue;
                    LpStore.Snapshot s = LpStore.get(act, pool.address);
                    if (s == null) continue;
                    p.openings.put(pool.address.toLowerCase(),
                            new AccountingExport.LpOpening(s.initM, s.initT, s.initPrice, s.block));
                }

                AccountingExport.Report rep = AccountingExport.build(p);
                byte[] zip = zip(rep);
                String name = filename(p.exportedAtMs);
                UI.post(() -> cb.onDone(zip, name, rep));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                UI.post(() -> cb.onError(msg));
            }
        });
    }

    private static String label(TokenBalance tb) {
        if (tb.isMinima()) return "MINIMA";
        if (tb.meta != null) {
            if (tb.meta.ticker != null && !tb.meta.ticker.isEmpty()) return tb.meta.ticker;
            if (tb.meta.name != null && !tb.meta.name.isEmpty()) return tb.meta.name;
        }
        return Util.shorten(tb.tokenid);
    }

    static String filename(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "pandapools-accounts-" + f.format(new Date(ms)) + ".zip";
    }

    private static byte[] zip(AccountingExport.Report r) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, AccountingExport.FILE_SUMMARY, r.summaryTxt);
            put(z, AccountingExport.FILE_TX, r.transactionsCsv);
            put(z, AccountingExport.FILE_LEDGER, r.ledgerCsv);
            put(z, AccountingExport.FILE_LP, r.lpCsv);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    /** Write the finished ZIP to a SAF-picked destination. Returns false if the stream couldn't be opened. */
    public static boolean writeTo(MainActivity act, Uri uri, byte[] data) {
        try (OutputStream os = act.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) return false;
            os.write(data);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Stage the ZIP in the app's cache so it can be handed to another app via FileProvider. */
    public static java.io.File stageForShare(MainActivity act, String name, byte[] data) {
        try {
            java.io.File dir = new java.io.File(act.getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) return null;
            // one staged export at a time — don't leave old financial data lying in the cache
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            java.io.File out = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) { fos.write(data); }
            return out;
        } catch (Exception e) { return null; }
    }

    /** Convenience for the UI: a one-line description of what was produced. */
    public static String describe(AccountingExport.Report r) {
        return r.pandaCount + " PandaPools transaction" + (r.pandaCount == 1 ? "" : "s")
                + " · " + r.ledgerRows + " ledger rows · "
                + r.poolCount + " pool" + (r.poolCount == 1 ? "" : "s");
    }
}

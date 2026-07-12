package com.eurobuddha.pandapools;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MEXC MINIMA/USDT price oracle — a one-shot async fetch of the live mid (USDT per MINIMA), used
 * to force a market-correct opening price when creating a MINIMA/USDT pool. Ported from minimaSwap's
 * PriceOracle (the {@code httpGet} pattern) but stripped to just the fetch — no peg/ladder/tombstone
 * machinery. The last-good price is cached with a timestamp so a dialog can show it immediately and treat
 * it as usable only while {@link #fresh()}.
 */
public final class PriceOracle {

    public static final String SOURCE = "MEXC";
    private static final String BOOK_URL = "https://api.mexc.com/api/v3/ticker/bookTicker?symbol=MINIMAUSDT";
    /** A price older than this is treated as stale — a create must not proceed on a stale price. */
    public static final long FRESH_MS = 5 * 60_000L;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal TWO = new BigDecimal("2");

    public interface Cb { void onPrice(BigDecimal mid); void onError(String message); }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pandapools-price"); t.setDaemon(true); return t;
    });
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile BigDecimal lastMid = null;
    private static volatile long lastMs = 0;

    private PriceOracle() {}

    /** Last-good cached mid (may be null if never fetched; may be stale — check {@link #fresh()}). */
    public static BigDecimal cachedMid() { return lastMid; }

    public static boolean fresh() {
        return lastMid != null && lastMid.signum() > 0 && (System.currentTimeMillis() - lastMs) <= FRESH_MS;
    }

    /** One-shot async fetch of the MINIMA/USDT mid; the callback fires on the UI thread. */
    public static void fetch(Cb cb) {
        EXEC.execute(() -> {
            try {
                JSONObject j = httpGet(BOOK_URL);
                BigDecimal bid = new BigDecimal(j.getString("bidPrice"));
                BigDecimal ask = new BigDecimal(j.getString("askPrice"));
                if (bid.signum() <= 0 || ask.signum() <= 0) throw new Exception("no market price");
                BigDecimal mid = bid.add(ask).divide(TWO, MC);
                lastMid = mid; lastMs = System.currentTimeMillis();
                UI.post(() -> cb.onPrice(mid));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "price unavailable" : e.getMessage();
                UI.post(() -> cb.onError(msg));
            }
        });
    }

    private static JSONObject httpGet(String u) throws Exception {
        if (ImageLoader.isBlockedHost(new URL(u).getHost())) throw new Exception("blocked host");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "PandaPools");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); total += n; if (total > 16384) break; }
            }
            String body = bos.toString("UTF-8");
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return new JSONObject(body);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

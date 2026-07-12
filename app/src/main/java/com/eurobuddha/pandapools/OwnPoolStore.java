package com.eurobuddha.pandapools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The durable, node-INDEPENDENT record of the pools this install OWNS — the "recipe" recovery needs to
 * regenerate the covenant, re-track it, and reclaim funds on ANY node (a re-synced node, a factory-reset
 * phone, a brand-new device). {@link LpStore} keeps only display stats (opening reserves / fee baseline);
 * this keeps what actually matters for getting a pool back.
 *
 * Persisted per pool (keyed by covenant address): the covenant SCRIPT (authoritative — the node re-tracks
 * with {@code newscript trackall script:<this>}) plus its params (opk/oadr/tok/decimals/kmin) and the
 * derived addresses. The owner key (opk) is seed-derived and the covenant embeds {@code SIGNEDBY(opk)}, so
 * seed + this recipe = always reclaimable. Purely local best-effort; a missing recipe just means that pool
 * falls back to normal beacon/tracked-contract discovery.
 */
public final class OwnPoolStore {

    private static final String PREFS = "pandapools_ownpools";

    private OwnPoolStore() {}

    /** Persist (or refresh) the recipe for an owned pool. Idempotent by covenant address. */
    public static void record(Context c, Pool p) {
        if (c == null || p == null || p.address == null || p.address.isEmpty()) return;
        // need at least the params to be able to reconstruct/verify the covenant
        if (isEmpty(p.opk) || isEmpty(p.oadr) || isEmpty(p.tok) || isEmpty(p.kmin)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("addr", p.address);                 // original-case derived address (hex is case-insensitive)
            o.put("mx", nz(p.mxaddress));
            o.put("opk", p.opk);
            o.put("oadr", p.oadr);
            o.put("tok", p.tok);
            o.put("dec", p.tokDecimals);
            o.put("kmin", p.kmin);
            // authoritative covenant script for re-tracking; reconstruct from params if the caller didn't
            // carry it (exact only for this app's fee — legacy-fee pools should always carry their own script)
            String script = (!isEmpty(p.covenantScript)) ? p.covenantScript : reconstruct(p);
            o.put("script", nz(script));
            prefs(c).edit().putString(key(p.address), o.toString()).apply();
        } catch (Exception ignore) {}
    }

    public static void remove(Context c, String address) {
        if (c == null || address == null) return;
        prefs(c).edit().remove(key(address)).apply();
    }

    /** Every owned pool as a reconstructed {@link Pool} (address + params + covenantScript set; reserves
     *  null until a scan fills them). Order is not significant. */
    public static List<Pool> all(Context c) {
        List<Pool> out = new ArrayList<>();
        if (c == null) return out;
        Map<String, ?> m = prefs(c).getAll();
        for (Map.Entry<String, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) v);
                Pool p = new Pool();
                p.address = o.optString("addr", e.getKey());
                p.mxaddress = o.optString("mx", "");
                p.opk = o.optString("opk", "");
                p.oadr = o.optString("oadr", "");
                p.tok = o.optString("tok", "");
                p.tokDecimals = o.optInt("dec", 8);
                p.kmin = o.optString("kmin", "");
                p.covenantScript = o.optString("script", "");
                if (!isEmpty(p.address) && !isEmpty(p.covenantScript)) out.add(p);
            } catch (Exception ignore) {}
        }
        return out;
    }

    /** The stored covenant script for an address (for a targeted re-track), or null. */
    public static String script(Context c, String address) {
        if (c == null || address == null) return null;
        String s = prefs(c).getString(key(address), null);
        if (s == null) return null;
        try {
            String sc = new JSONObject(s).optString("script", "");
            return sc.isEmpty() ? null : sc;
        } catch (Exception e) { return null; }
    }

    // ---- helpers ----

    private static String reconstruct(Pool p) {
        try { return PoolCovenant.script(p.opk, p.oadr, p.tok, p.kmin); } catch (Exception e) { return ""; }
    }
    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String key(String address) { return address.toLowerCase(); }
}

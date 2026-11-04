package com.eurobuddha.pandapools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The custody signal for Dexicon: which live pools PandaPools keeps right now, positively, per pool.
 * {@code content://com.eurobuddha.pandapools.handover/state} → columns {@code address, state}: one row per pool
 * recipe (retired ones too), {@code kept_here} (PandaPools keeps it) or {@code handed_over} (Dexicon keeps it). Addresses lower-case, in
 * full. Read-only, and only Dexicon signed with our key may read it; anyone else gets null.
 */
public class HandoverStateProvider extends ContentProvider {

    static final String AUTHORITY = "com.eurobuddha.pandapools.handover";
    static final String[] COLUMNS = { "address", "state" };
    static final String KEPT_HERE = "kept_here", HANDED_OVER = "handed_over";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        if (c == null || uri == null || !"state".equals(uri.getLastPathSegment())) return null;
        PackageManager pm = c.getPackageManager();
        String[] callers;
        try { callers = pm.getPackagesForUid(Binder.getCallingUid()); } catch (RuntimeException unknown) { return null; }
        if (!allowed(callers, pm, c.getPackageName())) return null;
        MatrixCursor out = new MatrixCursor(COLUMNS);
        for (String[] row : rows(c)) out.addRow(row);
        return out;
    }

    /** The caller's uid carries Dexicon AND Dexicon is signed with our key. */
    static boolean allowed(String[] callerPackages, PackageManager pm, String self) {
        if (callerPackages == null) return false;
        for (String p : callerPackages) if (Handover.DEXICON.equals(p)) return Handover.callerAllowed(p, pm, self);
        return false;
    }

    /**
     * Every recipe here, retired included: a retire only means a close/migrate was POSTED (or the user put the card
     * away), and our keeper keeps retired recipes too, so a dropped close leaves a funded pool PandaPools keeps.
     */
    static List<String[]> rows(Context c) { return rows(OwnPoolStore.all(c), Handover.handedOverPools(c)); }

    /** One row per pool recipe (deduplicated, lower-case, full address). */
    static List<String[]> rows(List<Pool> active, Set<String> handedOver) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Pool p : active) {
            if (p == null || p.address == null || p.address.isEmpty()) continue;
            String a = p.address.toLowerCase(Locale.ROOT);
            m.put(a, handedOver.contains(a) ? HANDED_OVER : KEPT_HERE);
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) out.add(new String[]{ e.getKey(), e.getValue() });
        return out;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}

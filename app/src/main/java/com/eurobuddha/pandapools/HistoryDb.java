package com.eurobuddha.pandapools;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent, txpowid-keyed mirror of the node's relevant history — a verbatim port of the standalone
 * Minima History app's store. It ACCUMULATES forever (never pruned), so a transaction stays in the local
 * record even after the node drops it from its own `history`, and the Activity tab shows instantly + offline.
 */
public class HistoryDb extends SQLiteOpenHelper {

    private static final String DB = "pandapools_history.db";
    private static final int VERSION = 1;
    private static final String TX = "tx";
    private static final String META = "meta";

    public HistoryDb(Context ctx) { super(ctx, DB, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TX + " (" +
                "txpowid TEXT PRIMARY KEY," +
                "block INTEGER, timemilli INTEGER," +
                "direction TEXT, incoming INTEGER," +
                "tokenid TEXT, tokenname TEXT, amount TEXT," +
                "deltas TEXT, counterparty TEXT, inputs TEXT, outputs TEXT," +
                "synced_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_block ON " + TX + "(block)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dir ON " + TX + "(direction)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tok ON " + TX + "(tokenid)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    // Never drop the table (permanence). Future versions add columns via ALTER here.
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { onCreate(db); }

    /** Insert a row; returns true if it was NEW, false if this txpowid was already stored. Idempotent. */
    public boolean insert(HistoryEntry e) {
        ContentValues v = new ContentValues();
        v.put("txpowid", e.txpowid);
        v.put("block", e.block);
        v.put("timemilli", e.timemilli);
        v.put("direction", e.direction);
        v.put("incoming", e.incoming ? 1 : 0);
        v.put("tokenid", e.tokenid);
        v.put("tokenname", e.tokenName);
        v.put("amount", e.amount);
        v.put("deltas", e.deltas);
        v.put("counterparty", e.counterparty);
        v.put("inputs", e.inputs);
        v.put("outputs", e.outputs);
        v.put("synced_at", e.syncedAt);
        long rid = getWritableDatabase().insertWithOnConflict(TX, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return rid != -1;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TX, null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Newest-first page, optionally free-text filtered across address / id / token / amount / direction. */
    public List<HistoryEntry> list(int limit, int offset, String search) {
        List<HistoryEntry> out = new ArrayList<>();
        String where = "";
        String[] args = null;
        if (search != null && !search.trim().isEmpty()) {
            String s = "%" + search.trim() + "%";
            where = " WHERE counterparty LIKE ? OR txpowid LIKE ? OR tokenname LIKE ? OR amount LIKE ? OR direction LIKE ?";
            args = new String[]{s, s, s, s, s};
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT txpowid,block,timemilli,direction,incoming,tokenid,tokenname,amount,deltas,counterparty,inputs,outputs,synced_at FROM "
                        + TX + where + " ORDER BY block DESC, timemilli DESC LIMIT " + limit + " OFFSET " + offset, args);
        try {
            while (c.moveToNext()) {
                HistoryEntry e = new HistoryEntry();
                e.txpowid = c.getString(0); e.block = c.getLong(1); e.timemilli = c.getLong(2);
                e.direction = c.getString(3); e.incoming = c.getInt(4) == 1;
                e.tokenid = c.getString(5); e.tokenName = c.getString(6); e.amount = c.getString(7);
                e.deltas = c.getString(8); e.counterparty = c.getString(9);
                e.inputs = c.getString(10); e.outputs = c.getString(11); e.syncedAt = c.getLong(12);
                out.add(e);
            }
        } finally { c.close(); }
        return out;
    }

    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + META + " WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }
}

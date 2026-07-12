package com.eurobuddha.pandapools;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIListener;

/**
 * Thin wrapper around the Minima Core native IPC SDK.
 *
 * - Holds the single {@link MinimaAPI} instance (which auto-registers this app with the node).
 * - Runs node commands and delivers the result back ON THE MAIN THREAD (the raw SDK callback
 *   arrives on the broadcast-receiver thread), with a timeout so a missing/stopped node
 *   surfaces an error instead of hanging.
 * - Detects the "app not enabled in Minima Core" reply and routes it to a pairing listener
 *   so the UI can show the approve-me banner.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Returned as the error message when the node says we are not enabled yet. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";

    /** Returned as the error message when the node dropped an over-256KB reply (see {@link #cmd}). */
    public static final String ERR_TOO_LONG = "TOO_LONG";

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    /** Transaction/PoW commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    private final MinimaAPI mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    // Pending timeout Runnables (main-thread only) so they can be cancelled on destroy.
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false;

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        // Constructing MinimaAPI auto-sends the REGISTER broadcast; the reply tells us
        // whether the user has enabled this app in Minima Core -> Apps yet.
        mApi = new MinimaAPI(ctx, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (dead()) return;
                    if (mPairing != null) mPairing.onEnabled(enabled);
                });
            }
        });
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    /** The node's over-limit stub: {@code status:false} with a String {@code response} containing "too long".
     *  The node discards any command reply over 256,000 bytes and returns this instead of the data. */
    private static boolean isTooLong(JSONObject j) {
        if (j == null || j.optBoolean("status", true)) return false;
        Object r = j.opt("response");
        if (!(r instanceof String)) return false;
        String s = ((String) r).toLowerCase();
        // match the current wording ("Result too long!") and the size cap itself, so a reworded message
        // still trips the guard as long as the byte-cap marker is present.
        return s.contains("too long") || s.contains("max(256000)");
    }

    public void cmd(String command, Cb cb) {
        if (mReleased) return;
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0] || dead()) return;
            done[0] = true;
            if (cb != null) cb.onError("Minima Core didn't respond. Is it installed, running and enabled?");
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (dead()) return;   // cleaned up above; just don't touch dead views

                    // Contain any exception a callback throws (most often JSON parsing of a large/odd reply)
                    // so it can't reach the main Looper and crash the whole app. On failure route to onError
                    // so the caller resets its loading state / shows a message instead of dying.
                    try {
                        // "enabled":false only appears on the gating reply; real command
                        // responses omit the key, so default true.
                        if (!zResponse.optBoolean("enabled", true)) {
                            if (mPairing != null) mPairing.onEnabled(false);
                            if (cb != null) cb.onError(ERR_NOT_ENABLED);
                            return;
                        }
                        // The node CAPS any reply at 256,000 bytes: an over-limit command returns a
                        // {"status":false,"response":"Result too long! MAX(256000)"} stub with NO data. Route it to
                        // onError(ERR_TOO_LONG) so a reader can shrink/retry or message — instead of parsing the
                        // stub's String `response` as an empty array and silently showing nothing.
                        if (isTooLong(zResponse)) { if (cb != null) cb.onError(ERR_TOO_LONG); return; }
                        if (cb != null) cb.onResult(zResponse);
                    } catch (Throwable t) {
                        if (cb != null) { try { cb.onError("Bad node reply"); } catch (Throwable ignore) {} }
                    }
                });
            }
        });
    }

    public void onDestroy() {
        mReleased = true;
        for (Runnable r : mPending) mMain.removeCallbacks(r);
        mPending.clear();
        if (mApi != null) mApi.onDestroy();
    }
}

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

    public static final String ERR_WRITE_UNCERTAIN = "The node has not confirmed the outcome of a previous write. "
            + "It may still complete. New signing is paused. Check Activity and use Wallet → Resolve interrupted write.";
    private static final String WRITE_PREFS = "pandapools_write_safety";
    private boolean destroyRequested;
    private static String activeWrite = "";
    Context context() { return mContext.getApplicationContext(); }

    static boolean writesFunds(String command) {
        String verb = command == null ? "" : command.trim().split("\\s+", 2)[0];
        return verb.equals("sign") || verb.equals("txnsign") || verb.equals("txnpost") || verb.equals("send")
                || verb.equals("consolidate") || verb.equals("tokencreate");
    }
    private android.content.SharedPreferences writePrefs() {
        return mContext.getSharedPreferences(WRITE_PREFS, Context.MODE_PRIVATE);
    }
    private boolean hasPendingWrite() { return !writePrefs().getString("pending", "").isEmpty(); }
    public boolean hasInterruptedWrite() { return hasPendingWrite() && activeWrite.isEmpty(); }
    /** Only invoked after the user's explicit restart-and-reconcile confirmation. */
    public boolean acknowledgeInterruptedWrite() {
        return activeWrite.isEmpty() && writePrefs().edit().remove("pending").commit();
    }

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    /** Transaction/PoW commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (writesFunds(c) || c.startsWith("txnsign")
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
    // Shared by Activity/service SDK instances. Large history replies must not arrive in the same
    // burst as every pool's coin query. This reduces Binder pressure; it is not a per-reply byte cap.
    private static final SerialQueue IPC_QUEUE = new SerialQueue();
    private int queuedCommands;

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
     *  The node discards any command reply over 256,000 characters and returns this instead of the data. */
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
        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, cb)); return; }
        if (command == null || command.trim().isEmpty()) { if (cb != null) cb.onError("Empty node command."); return; }
        if (mReleased) { if (cb != null) cb.onError("Node connection closed."); return; }
        queuedCommands++;
        IPC_QUEUE.submit(finish -> dispatch(command, new Cb() {
            private boolean delivered;
            public void onResult(JSONObject reply) { complete(reply, null); }
            public void onError(String message) { complete(null, message); }
            private void complete(JSONObject reply, String error) {
                if (delivered) return;
                delivered = true;
                try {
                    if (cb != null) {
                        if (error == null) cb.onResult(reply); else cb.onError(error);
                    }
                } catch (RuntimeException callbackFailure) {
                    // Preserve the previous wrapper's parser-error callback, while releasing this
                    // request only once. A posted write must never become a safe-to-retry failure.
                    boolean funds = writesFunds(command) && !command.contains("dryrun:true");
                    if (funds) writePrefs().edit().putString("pending", java.util.UUID.randomUUID().toString()).commit();
                    if (error == null && cb != null) {
                        try { cb.onError(funds ? ERR_WRITE_UNCERTAIN : "Bad node reply"); }
                        catch (RuntimeException ignored) { }
                    }
                } finally {
                    queuedCommands--;
                    // Yield to the Looper before starting another IPC request; finish consuming the
                    // current broadcast first. Child commands from the callback remain queued.
                    mMain.post(finish);
                    finishDestroy();
                }
            }
        }));
    }

    private void dispatch(String command, Cb cb) {
        if (mReleased) { cb.onError("Node connection closed."); return; }
        final boolean funds = writesFunds(command) && !command.contains("dryrun:true");
        final String writeId = java.util.UUID.randomUUID().toString();
        final String verb = command.trim().split("\\s+", 2)[0];
        android.util.Log.d("PandaPoolsIPC", writeId + " begin " + verb);
        if (funds) {
            if (hasPendingWrite()) { if (cb != null) cb.onError(ERR_WRITE_UNCERTAIN); return; }
            // Persist BEFORE dispatch: process death must not silently permit a second signing request.
            if (!writePrefs().edit().putString("pending", writeId).commit()) {
                if (cb != null) cb.onError("Could not save the transaction safety marker. Nothing was sent.");
                return;
            }
        }
        if (funds) activeWrite = writeId;
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0]) return;
            done[0] = true;
            android.util.Log.w("PandaPoolsIPC", writeId + " timeout " + verb);
            if (funds && writeId.equals(activeWrite)) activeWrite = "";
            if (cb != null) cb.onError(funds ? ERR_WRITE_UNCERTAIN
                    : "Minima Core didn't respond. Is it installed, running and enabled?");
            finishDestroy();
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        try { mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    android.util.Log.d("PandaPoolsIPC", writeId + " reply " + verb + " status="
                            + (zResponse != null && zResponse.optBoolean("status", false))
                            + " chars=" + (zResponse == null ? 0 : zResponse.toString().length()));
                    // Even a late reply proves this command has returned from the node. Clear only
                    // its own marker; never clear a newer command's marker.
                    if (funds && writeId.equals(activeWrite)) activeWrite = "";
                    if (funds && isCompleteReply(zResponse) && !isTooLong(zResponse) && writeId.equals(writePrefs().getString("pending", "")))
                        writePrefs().edit().remove("pending").commit();
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    // Finish an existing transaction chain even if its Activity was destroyed.

                    // Contain any exception a callback throws (most often JSON parsing of a large/odd reply)
                    // so it can't reach the main Looper and crash the whole app. On failure route to onError
                    // so the caller resets its loading state / shows a message instead of dying.
                    try {
                        if (!isCompleteReply(zResponse)) {
                            if (cb != null) cb.onError(funds ? ERR_WRITE_UNCERTAIN : "Incomplete node reply.");
                            return;
                        }
                        // "enabled":false only appears on the gating reply; real command
                        // responses omit the key, so default true.
                        if (!zResponse.optBoolean("enabled", true)) {
                            if (mPairing != null) mPairing.onEnabled(false);
                            if (cb != null) cb.onError(ERR_NOT_ENABLED);
                            return;
                        }
                        // The node CAPS any reply at 256,000 characters: an over-limit command returns a
                        // {"status":false,"response":"Result too long! MAX(256000)"} stub with NO data. Route it to
                        // onError(ERR_TOO_LONG) so a reader can shrink/retry or message — instead of parsing the
                        // stub's String `response` as an empty array and silently showing nothing.
                        if (isTooLong(zResponse)) { if (cb != null) cb.onError(funds ? ERR_WRITE_UNCERTAIN : ERR_TOO_LONG); return; }
                        if (cb != null) cb.onResult(zResponse);
                    } catch (Throwable t) {
                        if (cb != null) { try { cb.onError("Bad node reply"); } catch (Throwable ignore) {} }
                    } finally { finishDestroy(); }
                });
            }
        }); } catch (RuntimeException dispatchFailure) {
            mMain.removeCallbacks(timeout);
            timeout.run();
        }
    }

    public void onDestroy() {
        destroyRequested = true;
        finishDestroy();
    }

    static boolean isCompleteReply(JSONObject reply) {
        return reply != null && (reply.opt("status") instanceof Boolean
                || Boolean.FALSE.equals(reply.opt("enabled")));
    }

    private void finishDestroy() {
        // An in-flight chain still owns the SDK until its final callback; destroying it mid-sign
        // previously dropped callbacks and left the process-wide queue stuck.
        if (!destroyRequested || queuedCommands != 0 || !mPending.isEmpty() || mReleased) return;
        mReleased = true;
        if (mApi != null) mApi.onDestroy();
    }
}

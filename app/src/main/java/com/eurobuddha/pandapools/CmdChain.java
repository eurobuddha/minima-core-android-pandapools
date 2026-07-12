package com.eurobuddha.pandapools;

import org.json.JSONObject;

import java.util.List;

/**
 * Runs a list of node commands sequentially over {@link NodeApi}, aborting on the first failure
 * (status:false or transport error). The native equivalent of the dapp's nested MDS.cmd callbacks.
 * On failure it fires an optional cleanup command (e.g. txndelete) so a half-built transaction
 * doesn't leave its input coins locked. (Ported verbatim from the limit app.)
 */
public final class CmdChain {

    public interface Done {
        void ok(JSONObject last);
        void fail(String message);
    }

    private CmdChain() {}

    public static void run(NodeApi node, List<String> cmds, String cleanupOnFail, Done done) {
        step(node, cmds, 0, cleanupOnFail, done);
    }

    private static void step(NodeApi node, List<String> cmds, int i, String cleanup, Done done) {
        if (i >= cmds.size()) { done.ok(null); return; }
        final boolean last = (i == cmds.size() - 1);
        node.cmd(cmds.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    fail(node, cleanup, done, shortCmd(cmds.get(i)) + " failed" + errSuffix(json));
                    return;
                }
                if (last) { done.ok(json); return; }
                step(node, cmds, i + 1, cleanup, done);
            }
            @Override public void onError(String message) {
                fail(node, cleanup, done, message);
            }
        });
    }

    private static void fail(NodeApi node, String cleanup, Done done, String msg) {
        if (cleanup != null && !cleanup.isEmpty()) {
            node.cmd(cleanup, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {}
                @Override public void onError(String message) {}
            });
        }
        done.fail(msg);
    }

    private static String errSuffix(JSONObject json) {
        String e = json.optString("error", "");
        return e.isEmpty() ? "" : " : " + e;
    }

    private static String shortCmd(String c) {
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }
}

package com.eurobuddha.pandapools;

import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Adapted from utxo/WalletTools: preview node selection, then merge those exact inputs via TxPost.
 * Running consolidate again after preview could select different coins; building the reviewed merge
 * also ensures its signing, covenant validation and cleanup use the same gate as every pool action. */
final class WalletTools {
    private final MainActivity act;
    WalletTools(MainActivity act) { this.act = act; }

    void checkNode() {
        act.node().cmd("magic", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                try {
                    if (!TxPost.truthy(reply, "status")) { checkLegacyNode(); return; }
                    String limit = reply.getJSONObject("response").getJSONObject("lastblock")
                            .getString("currentmaxtxpowsize");
                    long bytes = new BigDecimal(limit).longValueExact();
                    if (bytes <= 0) throw new IllegalArgumentException();
                    message("MinimaCore replied. Current chain transaction limit: " + bytes
                            + " bytes per TxPoW.\n\nThe node checks the final transaction, including proofs and signatures, "
                            + "before posting. This is separate from Android's reply-delivery limit. No transaction was sent.");
                } catch (Exception invalid) { message("The node replied, but its chain size limit could not be read."); }
            }
            public void onError(String error) { message(error); }
        });
    }

    private void checkLegacyNode() {
        act.node().cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject r = reply == null ? null : reply.optJSONObject("response");
                if (!TxPost.truthy(reply, "status") || r == null) { message("Could not read the node's chain tip."); return; }
                message("MinimaCore is connected at block " + r.optString("block", "unknown")
                        + ". This node does not expose a separate transaction-size query. "
                        + "It checks the complete TxPoW against the live chain limit when posting. "
                        + "If rejected, PandaPools reports the node's actual size and limit. No transaction was sent.");
            }
            public void onError(String error) { message(error); }
        });
    }

    void showConsolidate(String token, String name) {
        android.content.Context ctx = new android.view.ContextThemeWrapper(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert);
        LinearLayout box = new LinearLayout(ctx); box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView note = new TextView(ctx);
        note.setText("Combine wallet coins to make future transactions smaller. Pool reserves stay in their contracts. "
                + "No burn fee. You review the exact coins before signing. Wait for this transaction to confirm before repeating.");
        note.setTextColor(Design.text()); box.addView(note);
        EditText count = new EditText(ctx); count.setText("8"); count.setHint("Coins to combine (3–8)");
        count.setTextColor(Design.text()); count.setInputType(InputType.TYPE_CLASS_NUMBER); box.addView(count);
        new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Consolidate " + name).setView(box)
                .setNegativeButton("Cancel", null).setPositiveButton("Preview", (d, w) -> {
                    int n;
                    try { n = Integer.parseInt(count.getText().toString().trim()); }
                    catch (Exception e) { message("Enter a coin count from 3 to 8."); return; }
                    if (n < 3 || n > 8 || !FundingCoins.hex(token)) { message("Choose 3 to 8 coins."); return; }
                    preview(token, name, n);
                }).show();
    }
    private void preview(String token, String name, int max) {
        act.node().cmd("consolidate tokenid:" + token + " maxcoins:" + max
                + " maxsigs:1 coinage:3 burn:0 dryrun:true", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                try {
                    if (!TxPost.truthy(j, "status")) { message(nodeError(j)); return; }
                    JSONObject p = j.getJSONObject("response");
                    JSONObject txn = p.getJSONObject("body").getJSONObject("txn");
                    JSONArray ins = txn.getJSONArray("inputs"), outs = txn.getJSONArray("outputs");
                    List<Coin> coins = previewCoins(ins, token, max);
                    String dest = outs.getJSONObject(0).getString("address");
                    if (!FundingCoins.hex(dest)) throw new IllegalArgumentException("Invalid destination.");
                    BigDecimal total = BigDecimal.ZERO;
                    for (Coin c : coins) total = total.add(new BigDecimal(c.amount));
                    final BigDecimal amount = total;
                    new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Review consolidation")
                            .setMessage(coins.size() + " wallet coins → 1 coin\n" + total.toPlainString() + " " + name
                                    + "\nBurn fee: 0 MINIMA\n\nYour wallet address:\n" + dest)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Combine coins", (d, w) -> merge(coins, token, name, dest, amount)).show();
                } catch (Exception invalid) { message("Could not safely preview consolidation: " + invalid.getMessage()); }
            }
            public void onError(String m) { message(m); }
        });
    }
    static List<Coin> previewCoins(JSONArray ins, String token, int max) {
        if (ins == null || ins.length() < 3 || ins.length() > max)
            throw new IllegalArgumentException("The node did not select 3–" + max + " coins to combine.");
        List<Coin> result = new ArrayList<>(); java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < ins.length(); i++) {
            JSONObject raw = ins.optJSONObject(i);
            if (raw == null) throw new IllegalArgumentException("Invalid input coin.");
            Coin c = FundingCoins.fundingCoin(raw);
            Object state = raw.opt("state");
            boolean hasState = state instanceof JSONArray ? ((JSONArray) state).length() > 0
                    : state instanceof JSONObject && ((JSONObject) state).length() > 0;
            if (!token.equalsIgnoreCase(c.tokenid) || !FundingCoins.hex(c.coinid) || !FundingCoins.hex(c.address)
                    || hasState || raw.optBoolean("spent", false) || !ids.add(c.coinid.toLowerCase(java.util.Locale.ROOT))
                    || new BigDecimal(c.amount).signum() <= 0)
                throw new IllegalArgumentException("Only distinct, stateless wallet coins can be combined.");
            result.add(c);
        }
        return result;
    }
    private void merge(List<Coin> coins, String token, String name, String dest, BigDecimal amount) {
        for (Coin c : coins) if (CoinLock.isReserved(c.coinid)) {
            message("A selected coin is in use by another transaction. Wait, then preview again."); return;
        }
        CoinLock.reserve(coins);
        String id = "ppmerge_" + java.util.UUID.randomUUID().toString().replace("-", "");
        List<String> cmds = new ArrayList<>(); cmds.add("txncreate id:" + id);
        for (Coin c : coins) cmds.add("txninput id:" + id + " coinid:" + c.coinid);
        cmds.add("txnoutput id:" + id + " amount:" + amount.toPlainString() + " address:" + dest
                + " tokenid:" + token + " storestate:false");
        cmds.add("txnsign id:" + id + " publickey:auto"); cmds.add("txnbasics id:" + id);
        TxPost.checkThenPost(act.node(), id, cmds, new TxPost.Done() {
            public void ok(String txpowid) {
                ActivityLog.record(act, ActivityLog.CONSOLIDATE, "Combine " + coins.size() + " " + name + " coins", txpowid, act.chainBlock());
                message("Consolidation submitted. Check Activity for its outcome before repeating.");
            }
            public void fail(String m) {
                if (!NodeApi.ERR_WRITE_UNCERTAIN.equals(m)) CoinLock.release(coins);
                message(m);
            }
        });
    }
    void resolveInterruptedWrite() {
        if (!act.node().hasInterruptedWrite()) { message("There is no interrupted write to resolve."); return; }
        new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Resolve interrupted write")
                .setMessage("A node write lost its reply. It may have completed. First restart MinimaCore to stop any old command, "
                        + "then check your transactions and balances. Clearing this safety pause does not undo or retry any transaction.")
                .setNegativeButton("Keep paused", null)
                .setPositiveButton("I restarted and checked", (d, w) -> { message(act.node().acknowledgeInterruptedWrite() ? "Safety pause cleared. Review your balances before starting a new transaction." : "A write is still active or the safety state could not be saved. Keep paused."); }).show();
    }
    private static String nodeError(JSONObject j) { return j.optString("error", j.optString("message", "No coins could be combined.")); }
    private void message(String text) {
        if (!act.isFinishing() && !act.isDestroyed()) new androidx.appcompat.app.AlertDialog.Builder(act, Design.isDark() ? androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert : androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert).setTitle("Wallet").setMessage(text).setPositiveButton("OK", null).show();
    }
}

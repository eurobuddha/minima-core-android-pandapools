package com.eurobuddha.pandapools;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Dexicon's "hand over" request ({@link Handover#ACTION}). Only Dexicon may ask. Shows every check with its
 * exact reason; RESULT_OK only after all pass AND the user confirms AND the flag reached disk.
 */
public class HandoverActivity extends AppCompatActivity {

    private NodeApi node;
    private LinearLayout checksBox;
    private Button confirm;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Handover.callerAllowed(getCallingPackage())) { setResult(RESULT_CANCELED); finish(); return; }
        refuse("Hand-over not confirmed.");            // back / dismiss
        Design.load(this);
        getDelegate().setLocalNightMode(Design.isDark() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        int pad = Ui.dp(this, 20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);
        col.addView(text("Hand over to Dexicon", Design.accent(), 22, true));
        col.addView(text("Dexicon contains PandaPools. Two apps keeping the same pool would rebuild its reserves "
                + "against each other, so only one of them may be installed.\n\n"
                + "This step confirms that nothing of yours lives only in PandaPools — no pool, no withdrawal, no "
                + "payout and no swap. Then Dexicon lets you uninstall PandaPools.", Design.text(), 15, false));
        checksBox = new LinearLayout(this);
        checksBox.setOrientation(LinearLayout.VERTICAL);
        checksBox.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        Ui.card(checksBox);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = pad;
        col.addView(checksBox, lp);
        checksBox.addView(text("Checking…", Design.dim(), 14, false));

        confirm = new Button(this);
        confirm.setText("Hand over to Dexicon");
        confirm.setEnabled(false);
        Ui.primaryButton(confirm);
        confirm.setOnClickListener(v -> handOver());
        col.addView(confirm, lp);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        Ui.outlineButton(cancel);
        cancel.setOnClickListener(v -> finish());
        col.addView(cancel, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Design.bg());
        sv.addView(col);
        setContentView(sv);

        node = new NodeApi(this, null);
        runChecks();
    }

    private void runChecks() {
        List<Handover.Check> checks = new ArrayList<>();
        checks.add(Handover.pools(OwnPoolStore.active(this)));
        checks.add(Handover.withdrawals(ExitStore.all(this)));
        checks.add(Handover.payouts(PendingCollect.all(this)));
        checks.add(Handover.swaps(SwapView.posting, ActivityLog.hasPending(this, 0, ActivityLog.SWAP)));
        // Retired ("closed") pools keep their recipe here. A close can be posted and never land, so each one
        // must be seen EMPTY on the node before its recipe may be deleted with the app.
        retiredEmpty(OwnPoolStore.retired(this), 0, checks);
    }

    private void retiredEmpty(List<Pool> retired, int i, List<Handover.Check> checks) {
        if (i == retired.size()) { runOnUiThread(() -> show(checks)); return; }
        Pool p = retired.get(i);
        String label = "Closed pool is empty";
        // No coin-id hints: a pool with more than SAFE_COINS coins must read as "unverified", never as empty.
        ReserveRecovery.readCoins(node::cmd, p.address, false, FundingCoins.SAFE_COINS, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray rows = FundingCoins.rows(j);
                if (rows == null) checks.add(new Handover.Check(label, false, unverified("unreadable reply"), "Pool: " + p.address));
                else if (rows.length() > 0) checks.add(new Handover.Check(label, false, Handover.LIVE_POOL, "Pool: " + p.address));
                retiredEmpty(retired, i + 1, checks);
            }
            @Override public void onError(String m) {
                checks.add(new Handover.Check(label, false, unverified(m), "Pool: " + p.address));
                retiredEmpty(retired, i + 1, checks);
            }
        });
    }

    private static String unverified(String why) {
        return "Couldn't confirm on your node that a closed pool is empty (" + why + "). Make sure Minima Core is "
                + "running and PandaPools is enabled in it, then try again.";
    }

    private void show(List<Handover.Check> checks) {
        if (isFinishing() || isDestroyed()) return;
        checksBox.removeAllViews();
        boolean all = true;
        for (Handover.Check c : checks) {
            all &= c.ok;
            checksBox.addView(text((c.ok ? "✓  " : "✗  ") + c.label, c.ok ? Design.success() : Design.red(), 15, true));
            if (!c.ok) checksBox.addView(text(c.reason, Design.text(), 13, false));
            if (!c.ok && !c.detail.isEmpty()) {
                TextView ids = text(c.detail, Design.dim(), 12, false);
                ids.setTextIsSelectable(true);   // full identifiers, copyable
                checksBox.addView(ids);
            }
        }
        if (!all) refuse(Handover.refusal(checks));
        confirm.setEnabled(all);
        Ui.primaryButton(confirm);
    }

    private void handOver() {
        long ts = System.currentTimeMillis();
        if (!Handover.set(this, true, ts)) { refuse("PandaPools could not save the hand-over. Nothing changed."); finish(); return; }
        setResult(RESULT_OK, new Intent()
                .putExtra("handover", true)
                .putExtra("app", BuildConfig.APPLICATION_ID)
                .putExtra("version", BuildConfig.VERSION_CODE)
                .putExtra("ts", ts));
        finish();
    }

    private void refuse(String reason) { setResult(RESULT_CANCELED, new Intent().putExtra("reason", reason)); }

    private TextView text(String s, int color, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        t.setGravity(Gravity.START);
        t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return t;
    }

    @Override protected void onDestroy() {
        if (node != null) { try { node.onDestroy(); } catch (Exception ignored) {} node = null; }
        super.onDestroy();
    }
}

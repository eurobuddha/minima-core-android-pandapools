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
 * Dexicon's "hand over" request ({@link Handover#ACTION}). Only Dexicon (same signer) may ask. Shows every check
 * with its exact reason; RESULT_OK only after all pass AND the user confirms AND the flag reached disk. Dexicon
 * keeps no OK of its own: it asks again before uninstalling, and an already handed-over PandaPools answers OK
 * at once — without a tap — only if a fresh run of every check still passes.
 */
public class HandoverActivity extends AppCompatActivity {

    private NodeApi node;
    private LinearLayout checksBox;
    private Button confirm;
    private int run;               // newest check run; results of older runs are dropped
    private boolean confirming;    // a confirm run owns the screen until it answers

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Handover.callerAllowed(getCallingPackage(), getPackageManager(), getPackageName())) {
            setResult(RESULT_CANCELED); finish(); return;
        }
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
        // Checks run in onResume: every return to this screen re-reads everything.
    }

    @Override protected void onResume() {
        super.onResume();
        if (!confirming) runChecks(false);
    }

    /**
     * One full check run. {@code confirm}: the user tapped Hand over and the flag is already on disk, so no new
     * create or swap can start while it runs.
     */
    private void runChecks(boolean confirm) {
        final int my = ++run;
        final boolean handedOver = Handover.active(this);
        List<Handover.Check> checks = new ArrayList<>();
        checks.add(Handover.pools(OwnPoolStore.active(this)));
        checks.add(Handover.withdrawals(ExitStore.all(this)));
        checks.add(Handover.payouts(PendingCollect.all(this)));
        checks.add(Handover.swaps(SwapView.posting, ActivityLog.hasPending(this, 0, ActivityLog.SWAP)));
        checks.add(Handover.running(Handover.busy()));
        Runnable done = () -> runOnUiThread(() -> answer(my, confirm, handedOver, checks));
        // A confirm that already fails answers at once: the flag must not hold back a running create longer than needed.
        if (confirm && !allOk(checks)) { done.run(); return; }
        // Retired ("closed") pools keep their recipe here. A close can be posted and never land, so each one
        // must be seen EMPTY on the node before its recipe may be deleted with the app.
        retiredEmpty(OwnPoolStore.retired(this), 0, checks, done);
    }

    private static boolean allOk(List<Handover.Check> checks) {
        for (Handover.Check c : checks) if (!c.ok) return false;
        return true;
    }

    private void answer(int my, boolean confirm, boolean handedOver, List<Handover.Check> checks) {
        if (my != run || isFinishing() || isDestroyed()) return;
        switch (Handover.outcome(confirm, handedOver, allOk(checks))) {
            case OK:
                confirming = false;
                ok(Handover.ts(this));
                return;
            case REFUSE:
                confirming = false;
                if (!wasHandedOver) Handover.set(this, false, 0);   // only undo what this tap set
                show(checks);
                return;
            default:
                show(checks);
        }
    }

    private void retiredEmpty(List<Pool> retired, int i, List<Handover.Check> checks, Runnable done) {
        if (i == retired.size() || node == null) {
            if (i < retired.size()) checks.add(new Handover.Check("Closed pool is empty", false, unverified("screen closed"), ""));
            done.run(); return;
        }
        Pool p = retired.get(i);
        String label = "Closed pool is empty", detail = "Pool: " + p.address;
        Runnable next = () -> retiredEmpty(retired, i + 1, checks, done);
        if (p.covenantScript == null || p.covenantScript.isEmpty()) {
            checks.add(new Handover.Check(label, false, unverified("no covenant script to re-track"), detail));
            next.run(); return;
        }
        // Re-track first (as the main screen does once per session): after a node resync the node only lists
        // coins of scripts it tracks, so an untracked pool would read empty.
        PoolManager.retrack(node, p, new NodeApi.Cb() {
            @Override public void onResult(JSONObject t) {
                if (!TxPost.truthy(t, "status") || node == null) {
                    checks.add(new Handover.Check(label, false, unverified("the node refused to re-track it"), detail));
                    next.run(); return;
                }
                // No coin-id hints: a pool with more than SAFE_COINS coins must read as "unverified", never as empty.
                ReserveRecovery.readCoins(node::cmd, p.address, false, FundingCoins.SAFE_COINS, null, null, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        JSONArray rows = FundingCoins.rows(j);
                        if (rows == null) checks.add(new Handover.Check(label, false, unverified("unreadable reply"), detail));
                        else if (rows.length() > 0) checks.add(new Handover.Check(label, false, Handover.LIVE_POOL, detail));
                        next.run();
                    }
                    @Override public void onError(String m) {
                        checks.add(new Handover.Check(label, false, unverified(m), detail));
                        next.run();
                    }
                });
            }
            @Override public void onError(String m) {
                checks.add(new Handover.Check(label, false, unverified("re-track failed: " + m), detail));
                next.run();
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

    private boolean wasHandedOver;

    /**
     * Flag FIRST (no new create or swap can start), then every check again — a pool may have been created or a
     * swap sent since the screen was shown. OK only if the fresh run passes; otherwise the flag is dropped again.
     */
    private void handOver() {
        if (confirming) return;
        wasHandedOver = Handover.active(this);
        if (!Handover.set(this, true, System.currentTimeMillis())) {
            refuse("PandaPools could not save the hand-over. Nothing changed."); finish(); return;
        }
        confirming = true;
        confirm.setEnabled(false);
        Ui.primaryButton(confirm);
        checksBox.removeAllViews();
        checksBox.addView(text("Checking again…", Design.dim(), 14, false));
        runChecks(true);
    }

    private void ok(long ts) {
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
        // Left mid-confirm (back/cancel) without an OK: undo the flag this tap set.
        if (confirming && !wasHandedOver && isFinishing()) Handover.set(this, false, 0);
        if (node != null) { try { node.onDestroy(); } catch (Exception ignored) {} node = null; }
        super.onDestroy();
    }
}

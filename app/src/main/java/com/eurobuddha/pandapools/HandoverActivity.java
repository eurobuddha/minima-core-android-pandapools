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
    private String[] dexiconPools; // covenant addresses whose recipes Dexicon already holds (null: none sent)

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Handover.callerAllowed(getCallingPackage(), getPackageManager(), getPackageName())) {
            setResult(RESULT_CANCELED); finish(); return;
        }
        dexiconPools = getIntent().getStringArrayExtra(Handover.EXTRA_DEXICON_POOLS);   // before refuse(): kept_by_dexicon reads it
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
                + "This step confirms that nothing of yours lives only in PandaPools: every live pool is restored "
                + "in Dexicon from your verified backup, and no withdrawal, payout or swap is open. PandaPools then "
                + "stops keeping those pools, Dexicon keeps them, and Dexicon lets you uninstall PandaPools.",
                Design.text(), 15, false));
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
        List<Handover.Check> checks = syncChecks(OwnPoolStore.active(this));
        final List<Pool> retired = OwnPoolStore.retired(this);
        Runnable done = () -> runOnUiThread(() -> answer(my, confirm, handedOver, checks, retired));
        // A confirm that already fails answers at once: the flag must not hold back a running create longer than needed.
        if (confirm && !allOk(checks)) { done.run(); return; }
        // Retired ("closed") pools keep their recipe here. A close can be posted and never land, so each one
        // must be seen EMPTY on the node before its recipe may be deleted with the app.
        retiredEmpty(retired, 0, checks, done);
    }

    /** Every check that reads a local store (no node, no network): run at the start AND again just before OK. */
    private List<Handover.Check> syncChecks(List<Pool> active) {
        List<Handover.Check> checks = new ArrayList<>();
        boolean account = com.eurobuddha.account.Account.everCreated(this);
        boolean nodeSeed = com.eurobuddha.account.AccountVault.MODE_NODE.equals(new com.eurobuddha.account.AccountVault(this).mode());
        checks.add(Handover.pools(active, dexiconPools, BackupState.combinedUpToDate(this), account));
        checks.add(Handover.account(account, nodeSeed, wordsConfirmed));
        checks.add(Handover.withdrawals(ExitStore.all(this)));
        checks.add(Handover.payouts(PendingCollect.all(this)));
        checks.add(Handover.swaps(SwapView.posting, ActivityLog.hasPending(this, 0, ActivityLog.SWAP)));
        checks.add(Handover.running(Handover.busy()));
        return checks;
    }

    private static boolean allOk(List<Handover.Check> checks) {
        for (Handover.Check c : checks) if (!c.ok) return false;
        return true;
    }

    private void answer(int my, boolean confirm, boolean handedOver, List<Handover.Check> checks, List<Pool> retiredChecked) {
        if (my != run || isFinishing() || isDestroyed()) return;
        switch (Handover.outcome(confirm, handedOver, allOk(checks))) {
            case OK: {
                // The run above waited on the node and the MegaMMR: a pool, withdrawal, payout or swap may have
                // appeared meanwhile, or the hand-over been taken back. Re-read every local store now, and hand
                // over exactly the pool list that was just checked.
                List<Pool> active = OwnPoolStore.active(this);
                List<Handover.Check> again = syncChecks(active);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Pool p : retiredChecked) if (p.address != null) seen.add(p.address.toLowerCase(java.util.Locale.ROOT));
                for (Pool p : OwnPoolStore.retired(this))
                    if (p.address == null || !seen.contains(p.address.toLowerCase(java.util.Locale.ROOT)))
                        again.add(new Handover.Check("Closed pool is empty", false,
                                "A pool was closed while checking, so it was not checked on-chain. Try again.", "Pool: " + p.address));
                if (!Handover.active(this)) again.add(new Handover.Check("PandaPools is handed over", false,
                        "The hand-over was taken back while checking. Hand over again.", ""));
                if (!allOk(again)) {
                    confirming = false;
                    if (confirm && !wasHandedOver) Handover.set(this, false, 0);   // only undo what this tap set
                    show(again);
                    return;
                }
                confirming = false;
                // Stop our own keeper for these pools on disk BEFORE Dexicon hears OK: two keepers never run.
                String[] pools = Handover.stopKeeping(this, active);
                if (pools == null) {
                    if (!wasHandedOver && confirm) Handover.set(this, false, 0);
                    refuse("PandaPools could not save which pools Dexicon now keeps. Nothing changed.");
                    finish(); return;
                }
                ok(Handover.ts(this), pools);
                return;
            }
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
        ClosedPoolCheck.run(this, node, retired, (l, r, d) -> checks.add(new Handover.Check(l, false, r, d)), done);
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
        if (!wordsConfirmed && com.eurobuddha.account.Account.everCreated(this)
                && !com.eurobuddha.account.AccountVault.MODE_NODE.equals(new com.eurobuddha.account.AccountVault(this).mode())) {
            TextView words = text("Confirm words", Design.accent(), 15, true);
            words.setPadding(0, 24, 0, 24);
            words.setOnClickListener(v -> confirmWords());
            checksBox.addView(words);
        }
        if (!all) refuse(Handover.refusal(checks));
        confirm.setEnabled(all);
        Ui.primaryButton(confirm);
    }

    /** Process-wide: a re-run of the checks (or a rotated screen) keeps the confirmation. */
    private static volatile boolean wordsConfirmed;

    /** The 24 words, re-typed, must be THIS account's (fingerprint): proof the user can restore it in Dexicon. */
    private void confirmWords() {
        // One box per word (the account module's WordEntry, as in every 24-word box in Dexicon, AtomiX and PandaPools).
        com.eurobuddha.account.WordEntry in = new com.eurobuddha.account.WordEntry(this, 0, null, null).enableAnyPhrase(false);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        scroll.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(in.view);
        androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Confirm your 24 words")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Confirm", null).create();
        if (d.getWindow() != null) {
            d.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.setOnShowListener(x -> d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String typed = in.phrase();
                if (typed == null) { android.widget.Toast.makeText(this, in.problem(), android.widget.Toast.LENGTH_LONG).show(); return; }
                String fp = in.isAnyPhrase() ? com.eurobuddha.account.Account.fingerprintOfAnyPhrase(typed)
                        : com.eurobuddha.account.Account.fingerprintOfPhrase(com.eurobuddha.account.AccountVault.cleanPhrase(typed));
                String mine = new com.eurobuddha.account.AccountVault(this).lostFingerprint();   // the recorded identity of this phone's account
                if (mine.isEmpty() || !mine.equalsIgnoreCase(fp)) { android.widget.Toast.makeText(this, "Those are not this account's words.", android.widget.Toast.LENGTH_LONG).show(); return; }
                wordsConfirmed = true; d.dismiss(); runChecks(false);
            } catch (Exception e) { android.widget.Toast.makeText(this, String.valueOf(e.getMessage()), android.widget.Toast.LENGTH_LONG).show(); }
        }));
        d.show();
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

    private void ok(long ts, String[] pools) {
        setResult(RESULT_OK, new Intent()
                .putExtra("handover", true)
                .putExtra(Handover.EXTRA_POOLS, pools)
                .putExtra("app", BuildConfig.APPLICATION_ID)
                .putExtra("version", BuildConfig.VERSION_CODE)
                .putExtra("ts", ts));
        finish();
    }

    /** Every refusal also says which pools PandaPools does NOT keep (Dexicon keeps them): the handed-over pools plus any
     *  pool Dexicon holds that has no recipe here. Dexicon may stop keeping a pool only once it is absent from this
     *  list, i.e. PandaPools really keeps it — never on a mere refusal (a failed MegaMMR read, a swap in progress),
     *  or nobody would keep it. */
    private void refuse(String reason) {
        setResult(RESULT_CANCELED, new Intent().putExtra("reason", reason)
                .putExtra(Handover.EXTRA_KEPT_BY_DEXICON,
                        Handover.keptByDexicon(Handover.handedOverPools(this), dexiconPools, OwnPoolStore.all(this))));
    }

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

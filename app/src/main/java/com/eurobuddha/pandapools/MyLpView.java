package com.eurobuddha.pandapools;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "My LP" tab: the liquidity-provider cockpit. Discovers every pool, keeps the ones this node OWNS
 * (its owner key is in the node keystore), and for each shows live reserves, holdings value, accrued-fee
 * growth and a KMIN-health meter — with one-tap Add / Migrate / Close and a Create-pool flow.
 */
public class MyLpView extends BaseView {

    private static final MathContext MC = new MathContext(30, RoundingMode.DOWN);
    private static final BigDecimal TWO = new BigDecimal("2");
    /** mxUSDT — the one pair with a live MEXC market feed, so its create price is forced to the market. */
    private static final String USDT_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    private final PoolManager mgr;
    private final Recovery recovery;
    private final ReAnnouncer reannouncer;
    private final PoolRefresher refresher;
    private final Set<String> myKeys = new HashSet<>();
    private final List<Pool> myPools = new ArrayList<>();   // currently-owned funded pools (for fresh coin export)
    private boolean busy = false;
    private boolean reannounceChecked = false;              // Layer 5 faded-beacon check runs once per session
    private boolean refreshChecked = false;                 // keep-fresh reserve check runs once per session
    private BigDecimal usdtAnchor;                          // tier-2 create anchor: aggregate live USDT-pool spot (Σt/Σm), null if none

    // Receives the shared PoolRepository's scan result (one scan for the whole app). render() filters to the
    // pools this node owns and drives the pending-create lifecycle.
    private final PoolBook.Listener poolListener = new PoolBook.Listener() {
        // Skip the per-block repaint WHILE a create/deposit/migrate/close is in flight — otherwise a block
        // landing mid-transaction wipes the "Creating…" status (and can flash "No pools yet" before
        // pendingCreate is armed), the worry-and-resubmit loop the pending-create lifecycle prevents. Each
        // transaction completion re-scans with busy=false, so no update is lost.
        @Override public void onPools(List<Pool> pools) { if (busy) return; render(pools); }
        @Override public void onError(String msg) { status("Scan error: " + msg); }
    };
    // A create that's been posted but not yet discovered on-chain — drives the persistent "confirming…"
    // card (so we never flash "No pools yet"), the duplicate-create guard, and a fast self-poll so the
    // pool paints itself the moment it's live (no tab-switch needed).
    private Pool pendingCreate;
    private int pendingBlock;
    private String pendingLabel = "";
    private boolean pendingPolling = false;
    private boolean stopped = false;   // true while backgrounded — blocks the fast pending-poll from re-arming

    public MyLpView(MainActivity a) {
        super(a, R.layout.view_mylp);
        a.pools().subscribe(poolListener);
        mgr = new PoolManager(a.node());
        recovery = new Recovery(a.node());
        reannouncer = new ReAnnouncer(a.node());
        refresher = new PoolRefresher(a.node());

        Ui.card(find(R.id.lpSummaryCard));
        TextView create = find(R.id.lpCreateBtn);
        Ui.primaryButton(create);
        create.setOnClickListener(v -> showCreateDialog());
        TextView recoveryBtn = find(R.id.lpRecoveryBtn);
        Ui.outlineButton(recoveryBtn);
        recoveryBtn.setTextColor(Design.accent());
        recoveryBtn.setOnClickListener(v -> showRecoveryDialog());

        ((TextView) find(R.id.lpSummaryLabel)).setTextColor(Design.dim());
        ((TextView) find(R.id.lpSummaryValue)).setTextColor(Design.heading());
        ((TextView) find(R.id.lpSummarySub)).setTextColor(Design.dim());
        ((TextView) find(R.id.lpStatus)).setTextColor(Design.dim());

        loadKeys();
    }

    @Override public void refresh() { act.pools().requestNow(poolListener); }
    @Override public void onShown() { stopped = false; loadKeys(); act.pools().requestNow(poolListener); if (pendingCreate != null) startPendingPoll(); }
    @Override public void onStop() { stopped = true; stopPendingPoll(); }
    @Override public boolean isBusy() { return busy; }
    @Override public void onDestroy() { stopPendingPoll(); act.pools().unsubscribe(poolListener); }

    // ---- ownership ----

    private void loadKeys() {
        if (act.node() == null) return;
        act.node().cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                myKeys.clear();
                Object resp = j.opt("response");
                JSONArray arr = null;
                if (resp instanceof JSONArray) arr = (JSONArray) resp;
                else if (resp instanceof JSONObject) arr = ((JSONObject) resp).optJSONArray("keys");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.optJSONObject(i);
                    if (k != null) {
                        String pk = k.optString("publickey", k.optString("miniaddress", ""));
                        if (!pk.isEmpty()) myKeys.add(pk.toLowerCase());
                    }
                }
                act.pools().requestNow(poolListener);
            }
            @Override public void onError(String m) { act.pools().requestNow(poolListener); }
        });
    }

    private boolean mine(Pool p) { return p.opk != null && myKeys.contains(p.opk.toLowerCase()); }

    // ---- discovery (shared scan — see poolListener) ----

    private void render(List<Pool> all) {
        List<Pool> mine = new ArrayList<>();
        for (Pool p : all) if (mine(p)) {
            mine.add(p);
            // backfill a recovery recipe for an owned pool the first time we see it (e.g. one created
            // before this feature). Only when missing, so the exact create/migrate script is never
            // clobbered by a reconstructed one.
            if (OwnPoolStore.script(act, p.address) == null) OwnPoolStore.record(act, p);
        }
        myPools.clear(); myPools.addAll(mine);   // for a backup's fresh coinexport (these carry live coinids)
        maybeReannounce(all);                     // Layer 5 gossip: refresh faded beacons for ALL funded pools (once/session)
        maybeRefresh(mine);                       // keep-fresh: recreate MY aging reserves before they leave the cascade

        // tier-2 create anchor: the aggregate spot of ALL live USDT pools (Σ reserveT / Σ reserveM). Arbitrage
        // keeps this near true market, so a new pool opened at it sits in line with the on-chain market. Used
        // to price a create when MEXC is unreachable.
        BigDecimal aggM = BigDecimal.ZERO, aggT = BigDecimal.ZERO;
        for (Pool p : all) if (p != null && p.funded() && USDT_TOKENID.equalsIgnoreCase(p.tok)) {
            aggM = aggM.add(p.reserveM); aggT = aggT.add(p.reserveT);
        }
        usdtAnchor = aggM.signum() > 0 ? aggT.divide(aggM, MC) : null;

        // Has a pending create just gone live (discovered with funded reserves)? Announce it + stop tracking.
        boolean pendingLive = false;
        if (pendingCreate != null) {
            for (Pool p : mine) if (p.address != null && p.address.equalsIgnoreCase(pendingCreate.address)) { pendingLive = true; break; }
            if (pendingLive) {
                status("Pool live ✓   MINIMA / " + pendingLabel + " is now earning fees.");
                pendingCreate = null; stopPendingPoll();
            } else if (act.chainBlock() > 0 && act.chainBlock() - pendingBlock > 20) {
                // Safety release: never let a stuck pending-create block re-creates forever (funds are safe —
                // the covenant parse-guard means nothing moved unless it compiled; a real pool shows via normal
                // discovery). Drop the tracking so the create-guard frees up.
                pendingCreate = null; stopPendingPoll();
                status("Your pool hasn't appeared after ~20 blocks — it may just be slow. Switch tabs to refresh, "
                        + "or check the Activity tab. You can create again if needed.");
            }
        }

        LinearLayout list = find(R.id.lpList);
        list.removeAllViews();
        TextView value = find(R.id.lpSummaryValue), sub = find(R.id.lpSummarySub);

        // A create still confirming → a persistent amber card at the top (never "No pools yet" while pending).
        if (pendingCreate != null) list.addView(confirmingCard());

        if (mine.isEmpty()) {
            if (pendingCreate != null) {
                value.setText("Confirming…");
                sub.setText("Your new pool is being written on-chain.");
                // keep the "Submitted ✓ … confirming" status — do NOT wipe it
            } else {
                value.setText("No pools yet");
                sub.setText(myKeys.isEmpty() ? "Pair the node to see your liquidity."
                        : "Create a pool to start earning the swap fee.");
                if (!pendingLive) status("");
            }
            return;
        }
        // portfolio value ≈ 2 × MINIMA-side depth (both legs are equal value at the pool price)
        BigDecimal totalMinima = BigDecimal.ZERO;
        for (Pool p : mine) totalMinima = totalMinima.add(p.reserveM);
        BigDecimal portfolio = totalMinima.multiply(TWO);
        value.setText(trim(portfolio) + " MINIMA");
        sub.setText(mine.size() + (mine.size() == 1 ? " pool" : " pools") + "  ·  ≈ value of both legs");
        if (pendingCreate == null && !pendingLive) status("");
        for (Pool p : mine) list.addView(lpCard(p));
    }

    /** A persistent card for a create that's posted but not yet discovered — reassures during the ~3-block
     *  wait so the user never assumes it failed and re-submits (the loop that stranded funds before). */
    private View confirmingCard() {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = Ui.dp(act, 15);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 12);
        card.setLayoutParams(lp);

        int el = Math.max(0, act.chainBlock() - pendingBlock);
        card.addView(text("MINIMA / " + pendingLabel + "   ·   confirming…", Design.amber(), 16, true));
        String st = el <= 3 ? ("Writing on-chain — block " + el + " of ~3")
                            : ("Writing on-chain — " + el + " blocks (taking a little longer than usual)");
        card.addView(kv("Status", st));
        if (pendingCreate.reserveM != null && pendingCreate.reserveT != null)
            card.addView(kv("Seeding", trim(pendingCreate.reserveM) + " MINIMA  +  " + trim(pendingCreate.reserveT) + " " + pendingLabel));
        card.addView(kv("", "It will appear here automatically — no need to resubmit.  (Tap to dismiss.)"));
        // Escape hatch: dismissing clears the create-guard so a stalled node can never wedge new creates.
        card.setOnClickListener(v -> { pendingCreate = null; stopPendingPoll(); status(""); act.pools().requestNow(poolListener); });
        return card;
    }

    private void startPendingPoll() {
        if (pendingPolling || stopped) return;   // never arm while backgrounded (chainBlock is frozen → no escape)
        pendingPolling = true;
        act.ui().postDelayed(pendingPollTask, 9000);
    }
    private void stopPendingPoll() {
        pendingPolling = false;
        act.ui().removeCallbacks(pendingPollTask);
    }
    private final Runnable pendingPollTask = new Runnable() {
        @Override public void run() {
            pendingPolling = false;
            if (stopped || pendingCreate == null) return;       // backgrounded → stop (onShown re-arms)
            if (act.chainBlock() - pendingBlock > 12) return;   // give up the FAST poll; the block-poll keeps trying
            if (!busy) act.pools().refresh();                   // shared scan → render resolves + repaints the pool
            if (pendingCreate != null && !stopped) { pendingPolling = true; act.ui().postDelayed(this, 9000); }
        }
    };

    // ---- a single owned-pool card ----

    private View lpCard(Pool p) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = Ui.dp(act, 15);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(act, 12);
        card.setLayoutParams(lp);

        card.addView(text("MINIMA / " + p.tokenLabel(), Design.heading(), 16, true));
        card.addView(kv("Your liquidity", trim(p.reserveM) + " MINIMA  +  " + trim(p.reserveT) + " " + p.tokenLabel()));

        BigDecimal value = p.reserveM.multiply(TWO);
        card.addView(kv("Value now", "≈ " + trim(value) + " MINIMA"));
        card.addView(kv("Pool price", trim8(p.spotPrice()) + " " + p.tokenLabel() + " / MINIMA"));

        // fees earned: LP value grows ∝ √(K) at constant price, and K grows only from fees, so the fee share
        // of the position is (1 − 1/√(K/Kbase)). Kbase is the product at the LAST liquidity change (from the
        // snapshot, reset on each deposit) so a deposit isn't mistaken for earnings; falls back to KMIN when
        // no snapshot exists (a pool this install didn't open).
        LpStore.Snapshot snap = LpStore.get(act, p.address);
        BigDecimal feeBase = (snap != null && snap.feeBaseK.signum() > 0) ? snap.feeBaseK : Util.decOr(p.kmin, BigDecimal.ZERO);
        double growth = 0;
        if (feeBase.signum() > 0) growth = Math.max(0, p.k().divide(feeBase, MC).subtract(BigDecimal.ONE).doubleValue());
        double feeMult = Math.sqrt(1.0 + growth);
        double feesMinima = value.doubleValue() * (1.0 - 1.0 / feeMult);
        card.addView(kvColored("Fees earned", "≈ " + fmt(feesMinima) + " MINIMA  (+" + fmt(growth * 100.0) + "%)", Design.success()));

        // impermanent loss + price move vs the opening snapshot (if this app opened the pool)
        if (snap != null && snap.initPrice.signum() > 0 && p.spotPrice().signum() > 0) {
            double p0 = snap.initPrice.doubleValue(), pnow = p.spotPrice().doubleValue();
            double r = pnow / p0;
            double priceMove = (r - 1.0) * 100.0;
            double il = (2.0 * Math.sqrt(r) / (1.0 + r) - 1.0) * 100.0;   // ≤ 0, price-divergence loss vs holding
            card.addView(kv("Price since open", (priceMove >= 0 ? "+" : "") + fmt(priceMove) + "%"));
            card.addView(kvColored("Impermanent loss", fmt(il) + "%  vs holding", il < -0.01 ? Design.amber() : Design.dim()));
            int ageBlocks = act.chainBlock() - snap.block;
            if (ageBlocks > 0) card.addView(kv("Age", ageBlocks + " blocks  (~" + fmt(ageBlocks * 50.0 / 3600.0) + " h)"));
        }

        // KMIN health: ratio K / KMIN, 1.0 fresh → 2.0 = must migrate
        BigDecimal kmin = Util.decOr(p.kmin, BigDecimal.ZERO);
        BigDecimal ratio = kmin.signum() > 0 ? p.k().divide(kmin, MC) : BigDecimal.ONE;
        card.addView(healthBar(ratio));

        card.addView(actionRow(p, ratio));
        return card;
    }

    private View healthBar(BigDecimal ratio) {
        // map ratio 1.0..2.0 -> 0..1 fill
        double frac = Math.max(0, Math.min(1, (ratio.doubleValue() - 1.0)));
        boolean warn = frac >= 0.85;
        int fillColor = warn ? Design.amber() : Design.accent();

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, Ui.dp(act, 10), 0, 0);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = text("Floor headroom", Design.dim(), 12, false);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pct = text((warn ? "migrate soon" : "healthy"), warn ? Design.amber() : Design.dim(), 12, false);
        row.addView(label); row.addView(pct);
        wrap.addView(row);

        // track
        LinearLayout track = new LinearLayout(act);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 8));
        tlp.topMargin = Ui.dp(act, 5);
        track.setLayoutParams(tlp);
        Ui.banner(track, Ui.withAlpha(Design.dim2(), 60), Ui.withAlpha(Design.dim2(), 60));
        View fill = new View(act);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) Math.max(0.02, frac)));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fillColor); g.setCornerRadius(Ui.dp(act, 8));
        fill.setBackground(g);
        View spacer = new View(act);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (1.0 - Math.max(0.02, frac))));
        track.addView(fill); track.addView(spacer);
        wrap.addView(track);
        return wrap;
    }

    private View actionRow(Pool p, BigDecimal ratio) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 14), 0, 0);
        boolean mustMigrate = ratio.doubleValue() >= 1.85;
        row.addView(actionBtn(mustMigrate ? "Add (full)" : "Add", () -> showAddDialog(p), false));
        row.addView(gap());
        row.addView(actionBtn("Migrate", () -> showMigrateDialog(p), mustMigrate));
        row.addView(gap());
        row.addView(actionBtn("Close", () -> confirmClose(p), false));
        return row;
    }

    private TextView actionBtn(String label, Runnable onClick, boolean highlight) {
        TextView t = new TextView(act);
        t.setText(label); t.setTextSize(13); t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(act, 10), Ui.dp(act, 11), Ui.dp(act, 10), Ui.dp(act, 11));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (highlight) Ui.primaryButton(t); else Ui.outlineButton(t);
        t.setOnClickListener(v -> { if (!busy) onClick.run(); });
        return t;
    }

    private View gap() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 8), 1));
        return v;
    }

    // ---- create pool ----

    /** Tokens with a live external market feed (currently only mxUSDT). Only these can be pooled, so a pool
     *  can never be opened at a mispriceable ratio while any reference price exists. */
    private static boolean isMarketFed(String tokenid) {
        return tokenid != null && tokenid.equalsIgnoreCase(USDT_TOKENID);
    }

    private static final class Anchor { final BigDecimal price; final String source; Anchor(BigDecimal p, String s) { price = p; source = s; } }

    /** The opening-price anchor, best-first: (1) a fresh MEXC mid; (2) the aggregate spot of live USDT pools;
     *  else null (no reference → tier-3 manual bootstrap). */
    private Anchor resolveAnchor() {
        BigDecimal m = PriceOracle.cachedMid();
        if (PriceOracle.fresh() && m != null && m.signum() > 0) return new Anchor(m, "MEXC market");
        if (usdtAnchor != null && usdtAnchor.signum() > 0) return new Anchor(usdtAnchor, "live pools");
        return null;
    }

    /** Route a chosen (market-fed) token to the priced form when an anchor exists, else try a fresh MEXC fetch,
     *  and only fall to the manual bootstrap form when there is genuinely no reference price at all. */
    private void dispatchCreate(String tokenid, int decimals, String tokenName, BigDecimal tokAvail, BigDecimal minimaAvail) {
        Anchor a = resolveAnchor();
        if (a != null) { createFormPriced(tokenid, decimals, tokenName, tokAvail, minimaAvail, a.price, a.source); return; }
        // no cached MEXC and no live pool → try a one-shot MEXC fetch before deciding priced-vs-manual
        status("Checking market price…");
        PriceOracle.fetch(new PriceOracle.Cb() {
            @Override public void onPrice(BigDecimal mid) {
                if (act.isFinishing() || act.isDestroyed()) return;
                status("");
                createFormPriced(tokenid, decimals, tokenName, tokAvail, minimaAvail, mid, "MEXC market");
            }
            @Override public void onError(String msg) {
                if (act.isFinishing() || act.isDestroyed()) return;
                status("");
                createFormManual(tokenid, decimals, tokenName);   // tier-3: no MEXC AND no live pool — user sets the first price
            }
        });
    }

    private void showCreateDialog() {
        // Guard: don't let a second create start while one is still confirming — that's the worry-and-resubmit
        // loop that stranded funds before.
        if (pendingCreate != null) {
            int el = Math.max(0, act.chainBlock() - pendingBlock);
            status("Your MINIMA / " + pendingLabel + " pool is still confirming (block " + Math.min(el, 3)
                    + " of ~3) — wait for it to go live before creating another.");
            return;
        }
        // gather the wallet's tokens (name + tokenid + decimals) to pick from
        act.node().cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                List<String[]> toks = new ArrayList<>();   // {tokenid, label, decimals, name, tokenSendable}
                BigDecimal[] minimaAvail = { BigDecimal.ZERO };
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String tid = t.optString("tokenid", "");
                    BigDecimal sendable = Util.decOr(t.optString("sendable", "0"), BigDecimal.ZERO);
                    if (Util.isMinima(tid)) { minimaAvail[0] = sendable; continue; }   // MINIMA is the other leg
                    if (tid.isEmpty() || sendable.signum() <= 0) continue;
                    if (!isMarketFed(tid)) continue;   // ONLY market-fed pairs (mxUSDT) — no mispriceable pools
                    String name = Util.tokenName(t.opt("token"), tid);
                    int dec = Util.tokenDecimals(t.opt("token"));
                    toks.add(new String[]{tid, name + "  ·  " + trim(sendable) + " avail", String.valueOf(dec),
                            name, sendable.toPlainString()});
                }
                if (toks.isEmpty()) { info("Get mxUSDT first", "PandaPools creates MINIMA / USDT pools — the pair with a live market price, so a pool always opens at the true rate. Your wallet holds no mxUSDT; receive some first, then create a pool."); return; }
                String[] labels = new String[toks.size()];
                for (int i = 0; i < toks.size(); i++) labels[i] = toks.get(i)[1];
                final BigDecimal mAvail = minimaAvail[0];
                // One market-fed token → skip the picker and go straight in; otherwise let the user choose.
                if (toks.size() == 1) {
                    String[] t = toks.get(0);
                    dispatchCreate(t[0], Integer.parseInt(t[2]), t[3], Util.decOr(t[4], BigDecimal.ZERO), mAvail);
                    return;
                }
                new AlertDialog.Builder(act)
                        .setTitle("Pool MINIMA with…")
                        .setItems(labels, (d, w) -> {
                            String[] t = toks.get(w);
                            dispatchCreate(t[0], Integer.parseInt(t[2]), t[3], Util.decOr(t[4], BigDecimal.ZERO), mAvail);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            @Override public void onError(String m) { info("Error", "Could not read your balance: " + m); }
        });
    }

    /**
     * Price-ANCHORED create for the MINIMA/USDT pair: the user enters only the USDT (dollar) amount, and the
     * required MINIMA is DERIVED from the anchor price ({@code initPrice}/{@code initSource} = tier-1 MEXC or
     * tier-2 live-pool aggregate) so the pool always opens at the true market — no lopsided, no mispriced pool.
     * The ↻ button upgrades the anchor to a fresh MEXC mid; a MEXC hiccup never blocks (the pool-price anchor
     * carries it). Confirms before posting.
     */
    private void createFormPriced(String tokenid, int decimals, String tokenName, BigDecimal tokAvail,
                                  BigDecimal minimaAvail, BigDecimal initPrice, String initSource) {
        LinearLayout box = dialogBox();
        EditText usdtAmt = field(box, tokenName + " to provide  (≈ US$)", "e.g. 1.90");
        TextView info = new TextView(act);
        info.setTextColor(Design.dim()); info.setTextSize(13);
        info.setPadding(0, Ui.dp(act, 12), 0, 0);
        box.addView(info);

        final BigDecimal[] price = { initPrice };
        final String[] source = { initSource };

        final Runnable update = () -> {
            BigDecimal p = price[0];
            if (p == null || p.signum() <= 0) { info.setText("Fetching MINIMA/USDT market price…"); return; }
            // most USDT the MINIMA balance can balance at the anchor price, and the smaller of that vs USDT held
            BigDecimal usdtCap = minimaAvail.multiply(p).min(tokAvail).setScale(decimals, RoundingMode.DOWN);
            StringBuilder sb = new StringBuilder();
            sb.append("Price: ").append(trim8(p)).append(" USDT/MINIMA (").append(source[0]).append(")\n");
            sb.append("You have: ").append(trim(minimaAvail)).append(" MINIMA · ").append(trim(tokAvail)).append(" ").append(tokenName).append("\n");
            BigDecimal u = parse(usdtAmt.getText().toString());
            if (u == null || u.signum() <= 0) {
                sb.append("Enter the USDT amount to provide (max ≈ ").append(trim(usdtCap)).append(").");
            } else {
                BigDecimal minima = u.divide(p, 6, RoundingMode.UP);
                sb.append("MINIMA required:  ").append(trim(minima)).append("\n");
                sb.append("Total pool value:  ≈ US$ ").append(trim2(u.multiply(TWO)));
                if (minima.compareTo(minimaAvail) > 0)
                    sb.append("\n⚠ Not enough MINIMA — reduce USDT to ≤ ").append(trim(usdtCap)).append(".");
                if (u.compareTo(tokAvail) > 0)
                    sb.append("\n⚠ You only have ").append(trim(tokAvail)).append(" ").append(tokenName).append(".");
            }
            info.setText(sb.toString());
        };
        watch(usdtAmt, update);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Create MINIMA / " + tokenName + " pool")
                .setView(box)
                .setPositiveButton("Create", null)     // wired in onShow so invalid input doesn't dismiss
                .setNeutralButton("↻ Price", null)
                .setNegativeButton("Cancel", null)
                .create();

        final PriceOracle.Cb fetchCb = new PriceOracle.Cb() {
            @Override public void onPrice(BigDecimal mid) {
                if (act.isFinishing() || act.isDestroyed()) return;   // dialog/activity gone — don't touch views
                price[0] = mid; source[0] = "MEXC market"; update.run();
            }
            @Override public void onError(String msg) {
                if (act.isFinishing() || act.isDestroyed()) return;
                update.run();   // keep the current anchor (pool/prior) — a MEXC hiccup must not block a create
            }
        };

        dlg.setOnShowListener(d -> {
            Button create = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            Button refresh = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
            create.setOnClickListener(v -> {
                BigDecimal p = price[0];
                if (p == null || p.signum() <= 0) { toast("No price yet — tap ↻ and retry."); return; }
                BigDecimal u = parse(usdtAmt.getText().toString());
                if (u == null || u.signum() <= 0) { toast("Enter the USDT amount."); return; }
                BigDecimal minima = u.divide(p, 6, RoundingMode.UP);
                if (minima.compareTo(minimaAvail) > 0 || u.compareTo(tokAvail) > 0) { toast("Not enough balance for that amount."); return; }
                dlg.dismiss();
                confirmCreate(tokenid, decimals, tokenName, minima, u, p, source[0], false);
            });
            refresh.setOnClickListener(v -> { info.setText("Refreshing price…"); PriceOracle.fetch(fetchCb); });
            update.run();
        });
        dlg.show();
        PriceOracle.fetch(fetchCb);   // try to upgrade to a live MEXC mid
    }

    /** Final confirmation before posting — states the amounts, the opening price, and which anchor set it
     *  (so a fat-fingered USD amount or a bootstrap price can't slip through un-noticed). */
    private void confirmCreate(String tokenid, int decimals, String tokenName, BigDecimal minima, BigDecimal usdt,
                               BigDecimal price, String source, boolean bootstrap) {
        String priceLine = bootstrap
                ? "⚠ No market price available — YOU are setting the opening price."
                : "Opens at " + trim8(price) + " USDT/MINIMA  (matches " + source + " ✓)";
        String msg = "MINIMA / " + tokenName + " pool\n\n"
                + trim(minima) + " MINIMA  +  " + trim(usdt) + " " + tokenName + "\n"
                + priceLine + "\n\n"
                + "The 0.5% swap fee goes to liquidity providers.";
        new AlertDialog.Builder(act)
                .setTitle("Create this pool?")
                .setMessage(msg)
                .setPositiveButton("Create", (d, w) -> doCreate(tokenid, decimals, tokenName, minima, usdt))
                .setNegativeButton("Back", null)
                .show();
    }

    /** Tier-3 BOOTSTRAP create — reached ONLY when there is no MEXC price AND no live MINIMA/USDT pool to anchor
     *  to (the very first pool). The creator sets the opening price; gated behind an explicit acknowledgement +
     *  the confirm step, because nothing can check the ratio. This is the only free-ratio path in the app. */
    private void createFormManual(String tokenid, int decimals, String tokenName) {
        LinearLayout box = dialogBox();
        TextView intro = text("No market price is available (MEXC is unreachable and there are no live MINIMA / "
                + tokenName + " pools to match). You are setting the OPENING PRICE yourself — set it to the true "
                + "market rate, or arbitrageurs will correct it at your expense.", Design.dim(), 12, false);
        intro.setPadding(0, 0, 0, Ui.dp(act, 4));
        box.addView(intro);
        EditText mAmt = field(box, "MINIMA to deposit", "e.g. 100");
        EditText tAmt = field(box, tokenName + " to deposit", "e.g. 0.56");
        TextView preview = new TextView(act);
        preview.setTextColor(Design.dim()); preview.setTextSize(13);
        preview.setPadding(0, Ui.dp(act, 10), 0, 0);
        box.addView(preview);
        final android.widget.CheckBox ack = new android.widget.CheckBox(act);
        ack.setText("I understand I'm setting the opening price with no market to check it against.");
        ack.setTextColor(Design.dim()); ack.setTextSize(12);
        box.addView(ack);

        Runnable update = () -> {
            BigDecimal x = parse(mAmt.getText().toString());
            BigDecimal y = parse(tAmt.getText().toString());
            if (x == null || y == null || x.signum() <= 0 || y.signum() <= 0) { preview.setText("Enter both amounts."); return; }
            if (!PoolCovenant.sizeOk(x, y)) { preview.setText("Amounts too large (x × y must be < 2^64)."); return; }
            BigDecimal yc = y.setScale(decimals, RoundingMode.DOWN);   // the actual on-grain reserve
            BigDecimal price = yc.divide(x, MC);
            preview.setText("Opening price:  " + trim8(price) + " " + tokenName + " / MINIMA\n"
                    + "Product floor (KMIN):  " + PoolCovenant.kmin(x, yc));
        };
        watch(mAmt, update); watch(tAmt, update); update.run();

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Create MINIMA / " + tokenName + " pool")
                .setView(box)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            BigDecimal x = parse(mAmt.getText().toString());
            BigDecimal y = parse(tAmt.getText().toString());
            if (x == null || y == null || x.signum() <= 0 || y.signum() <= 0) { toast("Enter both amounts."); return; }
            if (!PoolCovenant.sizeOk(x, y)) { toast("Amounts too large (x × y must be < 2^64)."); return; }
            if (!ack.isChecked()) { toast("Tick the box to confirm you're setting the price."); return; }
            BigDecimal yc = y.setScale(decimals, RoundingMode.DOWN);
            BigDecimal price = yc.divide(x, MC);
            dlg.dismiss();
            confirmCreate(tokenid, decimals, tokenName, x, y, price, "manual", true);
        }));
        dlg.show();
    }

    private void doCreate(String tokenid, int decimals, String tokenName, BigDecimal x, BigDecimal y) {
        busy = true; status("Creating your MINIMA / " + tokenName + " pool…");
        mgr.createPool(tokenid, decimals, x, y, new PoolManager.CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) {
                pool.tokName = tokenName;   // nice label for the confirming card
                LpStore.record(act, pool.address, pool.reserveM, pool.reserveT, act.chainBlock());
                OwnPoolStore.record(act, pool);   // durable recovery recipe (exact covenant script)
                act.ensureKeepAlive();            // first pool → bring up the Doze-proof keep-alive (idempotent)
                ActivityLog.record(act, ActivityLog.CREATE, "Create MINIMA / " + tokenName + " pool  ·  "
                        + trim(pool.reserveM) + " MINIMA + " + trim(pool.reserveT) + " " + tokenName, txpowid, act.chainBlock());
                act.runOnUiThread(() -> {
                    busy = false;
                    pendingCreate = pool; pendingBlock = act.chainBlock(); pendingLabel = tokenName;
                    status("Submitted ✓ " + Util.shorten(txpowid) + " — your pool is confirming on-chain (usually 1–3 blocks). "
                            + "It will appear below automatically; no need to resubmit.");
                    startPendingPoll();
                    act.pools().refresh();
                });
            }
            @Override public void onFailed(String message) {
                ActivityLog.recordFailed(act, ActivityLog.CREATE, "Create MINIMA / " + tokenName + " pool", message);
                act.runOnUiThread(() -> { busy = false; status("Create failed: " + message); });
            }
        });
    }

    // ---- add liquidity ----

    private void showAddDialog(Pool p) {
        LinearLayout box = dialogBox();
        // Deposits must be in the pool's ratio so they never move the price — the user enters ONLY the
        // MINIMA side and the token side is dictated (read-only) by the pool price. No lopsided adds.
        EditText mAmt = field(box, "MINIMA to add", "e.g. 50");
        TextView note = new TextView(act);
        note.setTextColor(Design.dim()); note.setTextSize(13);
        note.setPadding(0, Ui.dp(act, 10), 0, 0);
        box.addView(note);
        final BigDecimal price = p.spotPrice();
        final BigDecimal[] tSide = { BigDecimal.ZERO };
        watch(mAmt, () -> {
            BigDecimal m = parse(mAmt.getText().toString());
            if (m != null && m.signum() > 0) {
                BigDecimal t = m.multiply(price).setScale(p.tokDecimals, RoundingMode.DOWN);
                tSide[0] = t;
                note.setText("Balanced at the pool price: this also adds " + trim(t) + " " + p.tokenLabel()
                        + ".\nBoth sides are added in the pool's ratio so the price doesn't move.");
            } else {
                tSide[0] = BigDecimal.ZERO;
                note.setText("Enter the MINIMA to add; the " + p.tokenLabel()
                        + " side is set automatically at the pool price so the deposit stays balanced.");
            }
        });
        new AlertDialog.Builder(act)
                .setTitle("Add liquidity")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    BigDecimal m = parse(mAmt.getText().toString());
                    if (m == null || m.signum() <= 0) { toast("Enter the MINIMA amount to add."); return; }
                    doDeposit(p, m, tSide[0]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDeposit(Pool p, BigDecimal m, BigDecimal t) {
        busy = true; status("Adding liquidity…");
        final BigDecimal fm = m, ft = t;
        mgr.deposit(p, m, t, new PoolManager.Result() {
            @Override public void onPosted(String txpowid) {
                // reset the fee baseline to the grown product so the added capital isn't counted as fees
                LpStore.updateFeeBase(act, p.address, p.reserveM.add(fm), p.reserveT.add(ft));
                ActivityLog.record(act, ActivityLog.DEPOSIT, "Add to MINIMA / " + p.tokenLabel() + "  ·  "
                        + trim(fm) + " MINIMA + " + trim(ft) + " " + p.tokenLabel(), txpowid, act.chainBlock());
                act.runOnUiThread(() -> { busy = false; status("Liquidity added ✓ " + Util.shorten(txpowid) + " — confirming on-chain."); act.pools().refresh(); });
            }
            @Override public void onFailed(String message) {
                ActivityLog.recordFailed(act, ActivityLog.DEPOSIT, "Add to MINIMA / " + p.tokenLabel(), message);
                act.runOnUiThread(() -> { busy = false; status("Add failed: " + message); });
            }
        });
    }

    // ---- migrate ----

    private void showMigrateDialog(Pool p) {
        LinearLayout box = dialogBox();
        TextView blurb = new TextView(act);
        blurb.setTextColor(Design.dim()); blurb.setTextSize(13);
        blurb.setText("Migrate resets the pool at a fresh address with the KMIN floor recomputed for the new "
                + "size. Your current reserves return to you and the new reserves are taken from your wallet. "
                + "Defaults keep the same size.");
        blurb.setPadding(0, 0, 0, Ui.dp(act, 10));
        box.addView(blurb);
        EditText mAmt = field(box, "New MINIMA reserve", trim(p.reserveM));
        EditText tAmt = field(box, "New " + p.tokenLabel() + " reserve", trim(p.reserveT));
        mAmt.setText(trim(p.reserveM)); tAmt.setText(trim(p.reserveT));
        new AlertDialog.Builder(act)
                .setTitle("Migrate pool")
                .setView(box)
                .setPositiveButton("Migrate", (d, w) -> {
                    BigDecimal x = parse(mAmt.getText().toString());
                    BigDecimal y = parse(tAmt.getText().toString());
                    if (x == null || y == null) { toast("Enter the new reserves."); return; }
                    doMigrate(p, x, y);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doMigrate(Pool p, BigDecimal x, BigDecimal y) {
        busy = true; status("Migrating your pool…");
        mgr.migrate(p, x, y, new PoolManager.CreateResult() {
            @Override public void onCreated(Pool pool, String txpowid) {
                pool.tokName = p.tokName;
                LpStore.record(act, pool.address, pool.reserveM, pool.reserveT, act.chainBlock());
                OwnPoolStore.record(act, pool);   // recipe for the new pool
                act.ensureKeepAlive();            // ensure the keep-alive is up (idempotent)
                LpStore.remove(act, p.address);   // the old pool's display snapshot is stale — drop it
                // KEEP the old pool's recovery recipe until the migrate CONFIRMS: if the tx never lands the
                // old pool is still live and must stay recoverable. Harmless once it does (emptied covenant).
                ActivityLog.record(act, ActivityLog.MIGRATE, "Migrate MINIMA / " + p.tokenLabel() + " pool  ·  new size "
                        + trim(pool.reserveM) + " MINIMA + " + trim(pool.reserveT) + " " + p.tokenLabel(), txpowid, act.chainBlock());
                act.runOnUiThread(() -> { busy = false; status("Migrated ✓ " + Util.shorten(txpowid) + " — confirming on-chain."); act.pools().refresh(); });
            }
            @Override public void onFailed(String message) {
                ActivityLog.recordFailed(act, ActivityLog.MIGRATE, "Migrate MINIMA / " + p.tokenLabel() + " pool", message);
                act.runOnUiThread(() -> { busy = false; status("Migrate failed: " + message); });
            }
        });
    }

    // ---- close ----

    private void confirmClose(Pool p) {
        new AlertDialog.Builder(act)
                .setTitle("Withdraw all liquidity?")
                .setMessage("This closes the MINIMA / " + p.tokenLabel() + " pool and sweeps "
                        + trim(p.reserveM) + " MINIMA + " + trim(p.reserveT) + " " + p.tokenLabel()
                        + " (including earned fees) back to your wallet.")
                .setPositiveButton("Withdraw", (d, w) -> {
                    busy = true; status("Closing pool…");
                    final String closeSummary = "Withdraw MINIMA / " + p.tokenLabel() + "  ·  "
                            + trim(p.reserveM) + " MINIMA + " + trim(p.reserveT) + " " + p.tokenLabel() + " back to wallet";
                    final String closeOadr = p.oadr;
                    mgr.close(p, new PoolManager.Result() {
                        @Override public void onPosted(String txpowid) {
                            LpStore.remove(act, p.address);   // pool closed — drop its display snapshot
                            // KEEP the OwnPoolStore recovery recipe here: a posted-but-unconfirmed close that
                            // never lands would otherwise strip the recipe from a STILL-LIVE pool (exactly the
                            // wiped-node case this feature backstops). A stale recipe is a harmless no-op —
                            // re-track on an emptied covenant tracks nothing, and only funded() pools render.
                            ActivityLog.record(act, ActivityLog.CLOSE, closeSummary, txpowid, act.chainBlock());
                            act.runOnUiThread(() -> {
                                busy = false;
                                status("Pool closed ✓ " + Util.shorten(txpowid) + " — moving funds to a wallet address (confirming on-chain)…");
                                act.pools().refresh();
                                // Second hop: once the covenant→$OADR sweep confirms, forward it to a default-64
                                // wallet address so the withdrawn funds survive a seed-only restore.
                                collectAfterClose(closeOadr, 8);
                            });
                        }
                        @Override public void onFailed(String message) {
                            ActivityLog.recordFailed(act, ActivityLog.CLOSE, closeSummary, message);
                            act.runOnUiThread(() -> { busy = false; status("Close failed: " + message); });
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** After a close, the covenant→$OADR sweep is unconfirmed for ~a block; once it lands, forward those
     *  funds onward to a default-64 wallet address (so they survive a seed-only restore). Retries on the UI
     *  handler until the coins are sendable, or we run out of tries — the launch-time sweep is the backstop. */
    private void collectAfterClose(final String oadr, final int triesLeft) {
        if (oadr == null || oadr.isEmpty() || triesLeft <= 0 || act.node() == null) return;
        act.ui().postDelayed(() -> {
            if (act.node() == null) return;
            mgr.forwardOwnerFunds(oadr, new PoolManager.ForwardResult() {
                @Override public void onForwarded(String txpowid, int coins) {
                    act.runOnUiThread(() -> { status("Withdrawn funds moved to your wallet ✓ " + Util.shorten(txpowid)); act.pools().refresh(); });
                }
                @Override public void onNothing() { collectAfterClose(oadr, triesLeft - 1); }   // close not confirmed yet — retry
                @Override public void onFailed(String message) { collectAfterClose(oadr, triesLeft - 1); }
            });
        }, 20000);
    }

    /** Manual "Collect to wallet": forward any funds still sitting at this node's pool owner addresses
     *  ($OADR) onward to default-64 wallet addresses. Rescues funds a pre-fix close left stranded. */
    private void doCollect() {
        List<String> oadrs = new ArrayList<>();
        for (Pool p : OwnPoolStore.all(act)) if (p.oadr != null && !p.oadr.isEmpty()) oadrs.add(p.oadr);
        if (oadrs.isEmpty()) { status("No pools on record to collect from."); return; }
        status("Collecting withdrawn funds to your wallet…");
        mgr.sweepOwnerFunds(oadrs, (addressesForwarded, coins) -> act.runOnUiThread(() -> {
            if (addressesForwarded > 0) {
                status("Moved withdrawn funds from " + addressesForwarded + " pool"
                        + (addressesForwarded == 1 ? "" : "s") + " to your wallet ✓");
                act.pools().refresh();
            } else status("Nothing to collect — no spendable funds are waiting at your pool payout addresses.");
        }));
    }

    // ---- network discoverability (Layer 5) ----

    /** Once per session, GOSSIP: re-publish the announce beacon for ANY funded pool this node knows (its own
     *  + any it has discovered) whose beacon has faded from the recent registry window — so pools created on
     *  now-offline nodes stay findable by fresh nodes. One tiny dust tx per faded pool (no covenant coin
     *  spent, no owner signature); shuffled + capped in {@link ReAnnouncer}; silently does nothing if nothing
     *  faded or there's no spare MINIMA. {@code allFunded} is the full deduped funded scan result. */
    private void maybeReannounce(List<Pool> allFunded) {
        if (reannounceChecked || allFunded == null || allFunded.isEmpty() || act.node() == null) return;
        reannounceChecked = true;
        reannouncer.refreshFaded(new ArrayList<>(allFunded), posted -> {
            if (posted > 0) status("Re-published " + posted + " pool" + (posted == 1 ? "" : "s")
                    + " to the registry so fresh nodes can find " + (posted == 1 ? "it" : "them") + ".");
        });
    }

    /** Once per session, KEEP-FRESH: for each of MY pools whose reserves are aging toward the ~1700-block cascade
     *  edge, recreate them in place (owner grow-in-place, same amounts + a fresh beacon) so they stay young and
     *  every light node keeps seeing them. Owner-signed, no fund movement, capped in {@link PoolRefresher}. Age is
     *  unaffected (My LP age reads LpStore, not the coin). */
    private void maybeRefresh(List<Pool> mine) {
        if (refreshChecked || mine == null || mine.isEmpty() || act.node() == null || act.chainBlock() <= 0) return;
        refreshChecked = true;
        refresher.refreshAging(new ArrayList<>(mine), act.chainBlock(), posted -> {
            if (posted > 0) status("Refreshed " + posted + " pool" + (posted == 1 ? "" : "s")
                    + " so " + (posted == 1 ? "it stays" : "they stay") + " visible to other devices.");
        });
    }

    // ---- backup & restore (recovery) ----

    private void showRecoveryDialog() {
        String[] items = { "Collect withdrawn funds to my wallet", "Back up my pools to a file", "Restore pools from a file", "How recovery works" };
        new AlertDialog.Builder(act)
                .setTitle("Pool recovery")
                .setItems(items, (d, w) -> {
                    if (w == 0) doCollect(); else if (w == 1) doBackup(); else if (w == 2) doRestore(); else showRecoveryGuide();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void doBackup() {
        status("Preparing backup…");
        recovery.backup(act, new ArrayList<>(myPools), new Recovery.BackupCb() {
            @Override public void onBackup(String json) {
                act.pickSaveFile("pandapools-backup.json", uri -> {
                    if (uri == null) { status("Backup cancelled."); return; }
                    if (writeUri(uri, json)) {
                        status("Backup saved ✓");
                        info("Backup saved", "Your pool recovery file is saved. Keep it somewhere safe (a cloud drive, "
                                + "another device). To recover on a new or wiped node: open PandaPools there → My LP → "
                                + "Back up / Restore → Restore.");
                    } else status("Could not write the backup file.");
                });
            }
            @Override public void onError(String msg) { status(msg); }
        });
    }

    private void doRestore() {
        act.pickOpenFile(uri -> {
            if (uri == null) { status("Restore cancelled."); return; }
            String json = readUri(uri);
            if (json == null || json.isEmpty()) { status("Could not read that file."); return; }
            status("Restoring pools…");
            final StringBuilder log = new StringBuilder();
            recovery.restore(act, json, new Recovery.RestoreCb() {
                @Override public void onProgress(String line) { log.append(line).append('\n'); status("Restoring… " + line); }
                @Override public void onDone(int restored, int total) {
                    if (total == 0) {
                        status("That file isn't a PandaPools backup.");
                        info("Nothing restored", "That file isn't a readable PandaPools backup — pick the "
                                + "pandapools-backup.json you saved from Back up.");
                        return;
                    }
                    status("Restore complete ✓  " + restored + " of " + total + " pool(s).");
                    info("Restore complete", restored + " of " + total + " pool(s) recovered and re-tracked.\n\n"
                            + log.toString().trim() + "\n\nThey'll appear below as the node confirms their coins.");
                    loadKeys();              // ownership set may now include restored pools
                    act.pools().refresh();   // pull them into discovery
                }
            });
        });
    }

    private void showRecoveryGuide() {
        info("How pool recovery works",
                "Your pools live on-chain and are controlled by your seed. This app keeps a local recipe for every "
                + "pool you own and re-tracks them on every launch, so a re-synced node rediscovers them automatically.\n\n"
                + "• BELT & BRACES — nothing to do: your pools reappear after a node re-sync.\n\n"
                + "• SUSPENDERS — tap ‘Back up my pools’ and keep the file safe (a cloud drive, another device). On a NEW "
                + "or WIPED device, install PandaPools, pair your node, then ‘Restore pools from a file’ — it re-tracks each "
                + "pool and re-imports its coins.\n\n"
                + "• STRING (last resort) — even with only your seed: reinstall Minima and restore your seed (or resync from "
                + "an archive node), then Restore this backup. The covenant is signed by your seed key, so your funds are "
                + "always reclaimable.");
    }

    private boolean writeUri(android.net.Uri uri, String content) {
        try (java.io.OutputStream os = act.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) return false;
            os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) { return false; }
    }

    private String readUri(android.net.Uri uri) {
        try (java.io.InputStream is = act.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    // ---- small view helpers ----

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        box.setPadding(pad, Ui.dp(act, 8), pad, 0);
        return box;
    }

    private EditText field(LinearLayout box, String label, String hint) {
        TextView l = text(label, Design.dim(), 12, false);
        l.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        box.addView(l);
        EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setHint(hint); e.setTextColor(Design.heading()); e.setHintTextColor(Design.dim2());
        e.setTextSize(16);
        box.addView(e);
        return e;
    }

    private void watch(EditText e, Runnable r) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable ed) { r.run(); }
        });
    }

    private TextView text(String s, int color, int sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private View kv(String k, String v) { return kvColored(k, v, Design.text()); }

    private View kvColored(String k, String v, int valueColor) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(act, 4), 0, 0);
        TextView kk = text(k, Design.dim(), 12, false);
        kk.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = text(v, valueColor, 12, false);
        vv.setGravity(Gravity.END);
        row.addView(kk); row.addView(vv);
        return row;
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "—";
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private void status(String s) {
        TextView st = find(R.id.lpStatus);
        st.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        st.setText(s);
    }
    private void toast(String s) { status(s); }
    private void info(String title, String msg) {
        new AlertDialog.Builder(act).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private static BigDecimal parse(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }
    private static String trim(BigDecimal b) { return b == null ? "—" : b.stripTrailingZeros().toPlainString(); }
    private static String trim8(BigDecimal b) { return b == null ? "—" : b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    private static String trim2(BigDecimal b) { return b == null ? "—" : b.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
}

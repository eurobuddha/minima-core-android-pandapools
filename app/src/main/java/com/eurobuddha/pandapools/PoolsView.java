package com.eurobuddha.pandapools;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** The Pools tab: discovers every live pool from the registry and lists each with its reserves, spot
 *  price, and product growth. Read-only discovery — swapping lives in the Swap tab, LP in My LP. */
public class PoolsView extends BaseView {

    private final PoolBook book;
    private boolean scanning = false;

    public PoolsView(MainActivity a) {
        super(a, R.layout.view_pools);
        book = new PoolBook(a.node());
        TextView refresh = find(R.id.poolsRefresh);
        refresh.setTextColor(Design.accent());
        refresh.setOnClickListener(v -> scan());
        ((TextView) find(R.id.poolsSummary)).setTextColor(Design.dim());
        ((TextView) find(R.id.poolsStatus)).setTextColor(Design.dim());
    }

    @Override public void refresh() { scan(); }
    @Override public void onShown() { scan(); }
    @Override public void onNewBlock() { scan(); }

    private void scan() {
        if (scanning || act.node() == null) return;
        scanning = true;
        status("Scanning the pool registry…");
        book.scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) { scanning = false; render(pools); }
            @Override public void onError(String msg) { scanning = false; status("Scan error: " + msg); }
        });
    }

    private void render(List<Pool> pools) {
        LinearLayout list = find(R.id.poolList);
        list.removeAllViews();
        TextView summary = find(R.id.poolsSummary);
        if (pools.isEmpty()) {
            summary.setText("no active pools");
            status("No live pools found in the registry yet. Create one to seed liquidity.");
            return;
        }
        BigDecimal aggMinima = VirtualCurve.totalMinima(pools);
        summary.setText(pools.size() + (pools.size() == 1 ? " pool" : " pools")
                + " · " + trim(aggMinima) + " MINIMA aggregate depth");
        status("");
        find(R.id.poolsStatus).setVisibility(View.GONE);
        for (Pool p : pools) list.addView(poolCard(p));
    }

    private View poolCard(Pool p) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        String tl = p.tokenLabel();
        card.addView(line("MINIMA / " + tl, Design.heading(), 15, true));
        card.addView(kv("reserves", trim(p.reserveM) + " MINIMA  ·  " + trim(p.reserveT) + " " + tl));
        card.addView(kv("spot price", trim8(p.spotPrice()) + " " + tl + " / MINIMA"));
        BigDecimal fg = p.feeGrowth().multiply(new BigDecimal("100"));
        card.addView(kv("fees accrued (K/KMIN−1)", trim8(fg) + " %"));
        card.addView(kv("pool", p.address.substring(0, 14) + "…"));
        return card;
    }

    private TextView line(String s, int color, int sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private View kv(String k, String v) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, 0);
        TextView kk = new TextView(act);
        kk.setText(k); kk.setTextColor(Design.dim()); kk.setTextSize(12);
        kk.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = new TextView(act);
        vv.setText(v); vv.setTextColor(Design.text()); vv.setTextSize(12);
        vv.setTypeface(Typeface.MONOSPACE);
        vv.setGravity(Gravity.END);
        row.addView(kk); row.addView(vv);
        return row;
    }

    private void status(String s) {
        TextView st = find(R.id.poolsStatus);
        st.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        st.setText(s);
    }

    private static String trim(BigDecimal b) {
        if (b == null) return "—";
        return b.stripTrailingZeros().toPlainString();
    }
    private static String trim8(BigDecimal b) {
        if (b == null) return "—";
        return b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
}

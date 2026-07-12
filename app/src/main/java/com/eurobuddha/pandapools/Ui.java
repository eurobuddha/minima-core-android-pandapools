package com.eurobuddha.pandapools;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Small, theme-aware view-styling helpers so every screen shares one polished visual language
 * (rounded surfaces, an accent-filled primary CTA, soft chips) instead of flat rectangles. Everything
 * reads its colours + corner radius from {@link Design}, so the design toggle restyles it for free.
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    private static float radiusPx(Context c) { return dp(c, Math.max(Design.radiusDp(), 10f)); }

    /** A raised content card: surface fill, rounded, hairline border. */
    public static void card(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.surface());
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(Design.border2(), Design.isOriginal() ? 255 : 90));
        v.setBackground(g);
    }

    /** A slightly recessed inner panel (e.g. the from/to swap fields). */
    public static void panel(View v) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Design.surface2());
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(Design.border2(), Design.isOriginal() ? 255 : 70));
        v.setBackground(g);
    }

    /** The primary call-to-action: accent fill, on-accent text, ripple, rounded. */
    public static void primaryButton(TextView t) {
        Context c = t.getContext();
        boolean enabled = t.isEnabled();
        GradientDrawable g = new GradientDrawable();
        g.setColor(enabled ? Design.accent() : withAlpha(Design.accent(), 60));
        g.setCornerRadius(radiusPx(c));
        t.setBackground(ripple(c, g, Design.onAccent()));
        t.setTextColor(enabled ? Design.onAccent() : withAlpha(Design.onAccent(), 120));
        t.setTypeface(Design.typefaceBold());
        t.setClickable(enabled);
    }

    /** A secondary / outline button: transparent fill, accent border + text. */
    public static void outlineButton(TextView t) {
        Context c = t.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), Design.accent());
        t.setBackground(ripple(c, g, Design.accent()));
        t.setTextColor(Design.accent());
        t.setTypeface(Design.typefaceBold());
    }

    /** A small rounded chip (token tag / toggle). {@code on} = accent-tinted, else neutral. */
    public static void chip(TextView t, boolean on) {
        Context c = t.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? Design.accentSoft() : withAlpha(Design.surface2(), 255));
        g.setCornerRadius(dp(c, 100));
        g.setStroke(dp(c, 1), on ? Design.accent() : withAlpha(Design.border2(), 120));
        t.setBackground(g);
        t.setTextColor(on ? Design.accent() : Design.dim());
        t.setTypeface(Design.typefaceBold());
    }

    /** A coloured status banner background (info / warn / error), soft-tinted + rounded. */
    public static void banner(View v, int softColor, int strokeColor) {
        Context c = v.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setColor(softColor);
        g.setCornerRadius(radiusPx(c));
        g.setStroke(dp(c, 1), withAlpha(strokeColor, 120));
        v.setBackground(g);
    }

    private static RippleDrawable ripple(Context c, GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 60)), content, null);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.min(255, Math.max(0, alpha)) << 24);
    }
}

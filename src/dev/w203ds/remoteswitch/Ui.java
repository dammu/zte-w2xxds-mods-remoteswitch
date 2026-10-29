package dev.w203ds.remoteswitch;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colour tokens (same values as the canvas mock-ups) and small view helpers. */
final class Ui {
    private Ui() {}

    /** cubic-bezier(0.16, 1.15, 0.3, 1): panel / list entrance */
    static PathInterpolator spring() { return new PathInterpolator(0.16f, 1.15f, 0.3f, 1f); }
    /** cubic-bezier(0.16, 1.25, 0.3, 1): tile entrance, a bit more bounce */
    static PathInterpolator springTile() { return new PathInterpolator(0.16f, 1.25f, 0.3f, 1f); }
    /** cubic-bezier(0.2, 1.5, 0.4, 1): selection pop */
    static PathInterpolator pop() { return new PathInterpolator(0.2f, 1.5f, 0.4f, 1f); }

    static final class Theme {
        int text, text2, glass, glassOpaque, glassEdge, tile, tileEdge, tileSel, chip, divider, field, rowSel;
        int bg, surface, surface2, border, pressed, code, codeText;
        int accent, accentSoft, onAccent, ok, okSoft, err, errSoft, muted;
        int key, keyText, keyEdge, badgeEdge;
        int[] profile;

        static Theme of(boolean dark) {
            Theme t = new Theme();
            if (dark) {
                t.text = 0xFFECEEF1; t.text2 = 0xFFA9AFB7;
                t.bg = 0xFF111316; t.surface = 0xFF1C1F24; t.surface2 = 0xFF262A30; t.border = 0xFF33373D;
                t.pressed = 0x1FFFFFFF; t.code = 0xFF0B0D10; t.codeText = 0xFFC9D1D9;
                t.glass = 0xA81E2126; t.glassOpaque = 0xF21E2126; t.glassEdge = 0x1AFFFFFF;
                t.tile = 0x0DFFFFFF; t.tileEdge = 0x12FFFFFF; t.tileSel = 0x338AB4F8;
                t.chip = 0x14FFFFFF; t.divider = 0x14FFFFFF; t.field = 0x12FFFFFF; t.rowSel = 0x2E8AB4F8;
                t.accent = 0xFF8AB4F8; t.accentSoft = 0x2E8AB4F8; t.onAccent = 0xFF0B1F3A;
                t.ok = 0xFF81C995; t.okSoft = 0x2981C995; t.err = 0xFFF28B82; t.errSoft = 0x29F28B82; t.muted = 0xFF9AA0A6;
                t.key = 0x14FFFFFF; t.keyText = 0xFFDADCE0; t.keyEdge = 0x2EFFFFFF; t.badgeEdge = 0xFF2A2D33;
                t.profile = new int[] {0xFF9AA0A6, 0xFF4C8DF6, 0xFF26A69A, 0xFFE8891C, 0xFFA66BEA, 0xFFD9895F, 0xFF34A853, 0xFFF06292};
            } else {
                t.text = 0xFF1A1C20; t.text2 = 0xFF565B62;
                t.bg = 0xFFF4F5F7; t.surface = 0xFFFFFFFF; t.surface2 = 0xFFF1F3F4; t.border = 0xFFDADCE0;
                t.pressed = 0x14000000; t.code = 0xFF1F2328; t.codeText = 0xFFE6EDF3;
                t.glass = 0xB8F8F9FB; t.glassOpaque = 0xF5F8F9FB; t.glassEdge = 0xA6FFFFFF;
                t.tile = 0x9EFFFFFF; t.tileEdge = 0x0D000000; t.tileSel = 0xFFFFFFFF;
                t.chip = 0x0D000000; t.divider = 0x12000000; t.field = 0x0D000000; t.rowSel = 0x1A1967D2;
                t.accent = 0xFF1967D2; t.accentSoft = 0x1F1967D2; t.onAccent = 0xFFFFFFFF;
                t.ok = 0xFF137333; t.okSoft = 0x1F137333; t.err = 0xFFC5221F; t.errSoft = 0x1AC5221F; t.muted = 0xFF6B7078;
                t.key = 0xCCFFFFFF; t.keyText = 0xFF3C4043; t.keyEdge = 0x29000000; t.badgeEdge = 0xFFFFFFFF;
                // gray, blue, teal, orange, purple, sienna, green, pink — TargetStore.COLOR_SLOTS
                t.profile = new int[] {0xFF6B7078, 0xFF1967D2, 0xFF00897B, 0xFFC26401, 0xFF8E44D6, 0xFFA0522D, 0xFF188038, 0xFFC2185B};
            }
            return t;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusPx);
        g.setColor(color);
        return g;
    }

    /**
     * Background for anything clickable: keyboard focus draws a 2dp accent ring, press darkens.
     * strokeColor 0 = no resting border.
     */
    static Drawable pressable(Context c, Theme t, int fill, int strokeColor, float radiusDp) {
        float r = dp(c, radiusDp);
        GradientDrawable rest = round(fill, r);
        if (strokeColor != 0) rest.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        GradientDrawable focused = round(fill, r);
        focused.setStroke(dp(c, 2), t.accent);
        LayerDrawable pressed = new LayerDrawable(new Drawable[] {round(fill, r), round(t.pressed, r)});
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, pressed);
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[0], rest);
        s.setEnterFadeDuration(90);
        s.setExitFadeDuration(140);
        return s;
    }

    static GradientDrawable oval(int color, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    static View dot(Context c, int sizeDp, int color, int strokeDp, int strokeColor) {
        View v = new View(c);
        v.setBackground(oval(color, dp(c, strokeDp), strokeColor));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Keyboard keycap: rounded, 1dp edge with a thicker bottom edge. */
    static Drawable keycapBg(Context c, int fill, int edge) {
        float r = dp(c, 5);
        LayerDrawable ld = new LayerDrawable(new Drawable[] {round(edge, r), round(fill, r)});
        ld.setLayerInset(1, dp(c, 1), dp(c, 1), dp(c, 1), dp(c, 2));
        return ld;
    }

    static TextView keycap(Context c, String label, Theme t, int sizeDp) {
        TextView k = text(c, label, sizeDp <= 18 ? 10 : 11, t.keyText, true);
        k.setGravity(Gravity.CENTER);
        k.setMinWidth(dp(c, sizeDp));
        k.setHeight(dp(c, sizeDp));
        k.setPadding(dp(c, 5), 0, dp(c, 5), dp(c, 1));
        k.setBackground(keycapBg(c, t.key, t.keyEdge));
        return k;
    }

    static void setKeycap(Context c, TextView k, int fill, int edge, int textColor) {
        k.setBackground(keycapBg(c, fill, edge));
        k.setTextColor(textColor);
    }

    static LinearLayout row(Context c, int gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (gapDp > 0) {
            GradientDrawable gap = new GradientDrawable();
            gap.setSize(dp(c, gapDp), 1);
            l.setDividerDrawable(gap);
            l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        }
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Profile chip: coloured dot + name in a soft pill. */
    static LinearLayout chip(Context c, String name, int dotColor, int bg, int fg) {
        LinearLayout l = row(c, 5);
        l.setPadding(dp(c, 6), 0, dp(c, 7), 0);
        l.setMinimumHeight(dp(c, 20));
        l.setBackground(round(bg, dp(c, 10)));
        l.addView(dot(c, 6, dotColor, 0, 0));
        TextView tv = text(c, name, 11, fg, true);
        l.addView(tv, wrap());
        return l;
    }

    /** "last used" mini pill. */
    /** "open": the client still has a session (a task) somewhere */
    static TextView runningPill(Context c, Theme t) {
        TextView p = text(c, c.getString(R.string.ov_running), 10, t.ok, true);
        p.setGravity(Gravity.CENTER);
        p.setHeight(dp(c, 18));
        p.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        p.setBackground(round(t.okSoft, dp(c, 9)));
        return p;
    }

    static TextView lastPill(Context c, Theme t) {
        TextView p = text(c, c.getString(R.string.last_used), 10, t.accent, true);
        p.setGravity(Gravity.CENTER);
        p.setHeight(dp(c, 18));
        p.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        p.setBackground(round(t.accentSoft, dp(c, 9)));
        return p;
    }
}

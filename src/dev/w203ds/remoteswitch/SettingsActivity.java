package dev.w203ds.remoteswitch;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Two-pane settings: 状态 / F9 按键 / 选择层 / 远程目标 / 关于与回滚.
 * Key bindings live in the hook's Settings.Secure copy (pulled on open, pushed on every change);
 * chooser look and targets are app-private.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_TAB = "tab";
    /** with TAB_TARGETS: open the add-app picker right away (the chooser's empty state) */
    static final String EXTRA_ADD_APP = "add_app";
    static final String TAB_STATUS = "status", TAB_TARGETS = "targets";
    private static final String TAB_KEYS = "keys", TAB_CHOOSER = "chooser", TAB_PROFILES = "profiles", TAB_ABOUT = "about";

    private TargetStore store;
    private SharedPreferences prefs;
    private SharedPreferences ui;
    private Ui.Theme t;
    private String[] labels, values, descs;

    private String tab;
    private final List<View> navItems = new ArrayList<>();
    private final List<String> navTabs = new ArrayList<>();
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout targetList;
    private TextView targetSummary;
    private TextView snack;
    private LinearLayout snackBox;
    private final Handler handler = new Handler();
    private boolean diagOpen;
    /** F9 tab: the four cells are shown (always, when they match no preset) */
    private boolean keysAdvanced;

    private Target removed;
    private int removedIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        t = Ui.Theme.of(night);
        Window w = getWindow();
        w.setStatusBarColor(t.surface);
        w.setNavigationBarColor(t.bg);
        if (!night) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        store = new TargetStore(this);
        prefs = KeyBindings.prefs(this);
        ui = getSharedPreferences(OverlayActivity.PREFS, MODE_PRIVATE);
        pullBindings();
        labels = getResources().getStringArray(R.array.action_labels);
        values = getResources().getStringArray(R.array.action_values);
        descs = getResources().getStringArray(R.array.action_descs);

        setContentView(buildFrame());
        String want = savedInstanceState != null ? savedInstanceState.getString(EXTRA_TAB) : getIntent().getStringExtra(EXTRA_TAB);
        show(want != null ? want : TAB_STATUS);
        if (savedInstanceState == null && TAB_TARGETS.equals(want) && getIntent().getBooleanExtra(EXTRA_ADD_APP, false)) {
            content.post(new Runnable() {
                @Override public void run() { addAppDialog(); }
            });
        }
    }

    /** Esc closes, without relying on the keyboard's Esc→Back fallback (not every input device has it). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_TAB, tab);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------ frame

    private View buildFrame() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);

        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(panes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(14), dp(24), dp(14), dp(24));
        nav.setBackgroundColor(t.surface);
        LinearLayout brand = Ui.row(this, 12);
        brand.setPadding(dp(12), 0, dp(12), dp(20));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        bt.addView(Ui.text(this, getString(R.string.app_name), 17, t.text, true));
        TextView ver = Ui.text(this, getString(R.string.settings), 12, t.text2, false);
        ver.setPadding(0, dp(3), 0, 0);
        bt.addView(ver);
        brand.addView(bt, Ui.wrap());
        nav.addView(brand);
        addNav(nav, TAB_STATUS, R.drawable.ic_status, getString(R.string.nav_status));
        addNav(nav, TAB_KEYS, R.drawable.ic_keyboard, getString(R.string.nav_keys));
        addNav(nav, TAB_CHOOSER, R.drawable.ic_layers, getString(R.string.chooser_section));
        addNav(nav, TAB_TARGETS, R.drawable.ic_monitor, getString(R.string.nav_targets));
        addNav(nav, TAB_PROFILES, R.drawable.ic_users, getString(R.string.nav_profiles));
        addNav(nav, TAB_ABOUT, R.drawable.ic_info, getString(R.string.nav_about));
        panes.addView(nav, new LinearLayout.LayoutParams(dp(248), ViewGroup.LayoutParams.MATCH_PARENT));

        View rule = new View(this);
        rule.setBackgroundColor(t.border);
        panes.addView(rule, new LinearLayout.LayoutParams(Math.max(1, dp(0.6f)), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(36), dp(28), dp(36), dp(96));
        scroll.addView(content);
        panes.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        snackBox = Ui.row(this, 12);
        snackBox.setPadding(dp(20), dp(6), dp(8), dp(6));
        snackBox.setMinimumHeight(dp(44));
        snackBox.setBackground(Ui.round(0xF0202124, dp(12)));
        snackBox.setElevation(dp(8));
        snack = Ui.text(this, "", 15, 0xFFFFFFFF, false);
        snack.setSingleLine(false);
        snackBox.addView(snack, Ui.wrap());
        snackBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(28);
        sl.leftMargin = dp(248);
        root.addView(snackBox, sl);
        return root;
    }

    private void addNav(LinearLayout nav, final String key, int icon, String label) {
        LinearLayout item = Ui.row(this, 12);
        item.setPadding(dp(12), 0, dp(12), 0);
        item.setMinimumHeight(dp(44));
        item.setFocusable(true);
        item.setClickable(true);
        item.addView(icon(icon, t.text, 20));
        item.addView(Ui.text(this, label, 15, t.text, false), Ui.weight1());
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { show(key); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        nav.addView(item, lp);
        navItems.add(item);
        navTabs.add(key);
    }

    private void show(String key) {
        tab = key;
        for (int i = 0; i < navItems.size(); i++) {
            boolean on = navTabs.get(i).equals(key);
            LinearLayout item = (LinearLayout) navItems.get(i);
            item.setBackground(Ui.pressable(this, t, on ? t.accentSoft : 0x00000000, 0, 12));
            int fg = on ? t.accent : t.text;
            ((ImageView) item.getChildAt(0)).setColorFilter(fg);
            TextView tv = (TextView) item.getChildAt(1);
            tv.setTextColor(fg);
            tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        targetList = null;
        switch (key) {
            case TAB_KEYS: buildKeys(); break;
            case TAB_CHOOSER: buildChooser(); break;
            case TAB_TARGETS: buildTargets(); break;
            case TAB_PROFILES: buildProfiles(); break;
            case TAB_ABOUT: buildAbout(); break;
            default: buildStatus(); break;
        }
        scroll.scrollTo(0, 0);
        content.setAlpha(0f);
        content.setTranslationY(dp(8));
        content.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.spring()).start();
    }

    // ------------------------------------------------------------ building blocks

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(color);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private TextView h1(String s) {
        TextView tv = Ui.text(this, s, 24, t.text, true);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView h2(String s) {
        TextView tv = Ui.text(this, s, 17, t.text, true);
        tv.setPadding(0, dp(28), 0, dp(12));
        return tv;
    }

    private TextView para(String s) {
        TextView tv = Ui.text(this, s, 14, t.text2, false);
        tv.setSingleLine(false);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, 0, 0, dp(18));
        return tv;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardBg());
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        return c;
    }

    private Drawable cardBg() {
        android.graphics.drawable.GradientDrawable g = Ui.round(t.surface, dp(20));
        g.setStroke(Math.max(1, dp(0.6f)), t.border);
        return g;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** kind: 0 secondary, 1 primary, 2 danger, 3 text-only */
    private TextView button(String label, int iconRes, int kind, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 14, kind == 1 || kind == 2 ? t.onAccent : kind == 3 ? t.accent : t.text, kind != 0);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setPadding(dp(16), 0, dp(16), 0);
        int fill = kind == 1 ? t.accent : kind == 2 ? t.err : kind == 3 ? 0x00000000 : t.surface;
        b.setBackground(Ui.pressable(this, t, fill, kind == 0 ? t.border : 0, 12));
        if (iconRes != 0) {
            Drawable d = getDrawable(iconRes).mutate();
            d.setBounds(0, 0, dp(16), dp(16));
            d.setTint(b.getCurrentTextColor());
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private ImageView iconButton(int res, String desc, View.OnClickListener l) {
        ImageView b = icon(res, t.text2, 36);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setContentDescription(desc);
        b.setBackground(Ui.pressable(this, t, 0x00000000, 0, 10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** A choice card with a radio dot; used for boot policy and chooser style. */
    private LinearLayout radioCard(String title, String desc, boolean on, View.OnClickListener l) {
        LinearLayout c = Ui.row(this, 12);
        c.setGravity(Gravity.TOP);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable g = Ui.round(on ? t.accentSoft : t.surface, dp(16));
        g.setStroke(dp(on ? 2 : 1), on ? t.accent : t.border);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable f = Ui.round(on ? t.accentSoft : t.surface, dp(16));
        f.setStroke(dp(2), t.accent);
        s.addState(new int[] {android.R.attr.state_focused}, f);
        s.addState(new int[0], g);
        c.setBackground(s);
        c.addView(radioDot(on));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, title, 15, t.text, true));
        TextView d = Ui.text(this, desc, 13, t.text2, false);
        d.setSingleLine(false);
        d.setPadding(0, dp(4), 0, 0);
        col.addView(d);
        c.addView(col, Ui.weight1());
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(l);
        return c;
    }

    private View radioDot(boolean on) {
        FrameLayout box = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(Ui.oval(0x00000000, dp(2), on ? t.accent : t.text2));
        box.addView(ring, new FrameLayout.LayoutParams(dp(20), dp(20)));
        if (on) {
            View dot = new View(this);
            dot.setBackground(Ui.oval(t.accent, 0, 0));
            box.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
        lp.topMargin = dp(1);
        box.setLayoutParams(lp);
        return box;
    }

    private LinearLayout headerRow(String title) {
        LinearLayout r = Ui.row(this, 10);
        TextView h = h1(title);
        r.addView(h, Ui.weight1());
        return r;
    }

    private void snack(String msg, final Runnable undo) {
        handler.removeCallbacksAndMessages(null);
        snack.setText(msg);
        while (snackBox.getChildCount() > 1) snackBox.removeViewAt(1);
        if (undo != null) {
            TextView u = button(getString(R.string.undo), 0, 3, new View.OnClickListener() {
                @Override public void onClick(View v) { snackBox.setVisibility(View.GONE); undo.run(); }
            });
            u.setTextColor(0xFF8AB4F8);
            u.setBackground(Ui.pressable(this, t, 0x00000000, 0, 8));
            snackBox.addView(u, Ui.wrap());
        }
        snackBox.setPadding(dp(20), dp(6), dp(undo != null ? 8 : 20), dp(6));
        snackBox.setVisibility(View.VISIBLE);
        snackBox.setAlpha(0f);
        snackBox.setTranslationY(dp(12));
        snackBox.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.spring()).start();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                snackBox.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
                    @Override public void run() { snackBox.setVisibility(View.GONE); removed = null; }
                }).start();
            }
        }, undo != null ? 5000 : 2600);
    }

    /** Rounded floating dialog holding our own content. */
    private Dialog dialog(String overline, String title, View body, View footer) {
        final Dialog d = new Dialog(this);
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface di, int code, KeyEvent e) {
                if (code != KeyEvent.KEYCODE_ESCAPE) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) d.dismiss();
                return true;
            }
        });
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(22), 0, dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(24), 0, dp(24), dp(12));
        if (overline != null) head.addView(Ui.text(this, overline, 13, t.text2, false));
        TextView tt = Ui.text(this, title, 20, t.text, true);
        tt.setPadding(0, dp(4), 0, 0);
        head.addView(tt);
        box.addView(head);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (footer != null) box.addView(footer);
        d.setContentView(box);
        Window w = d.getWindow();
        w.setBackgroundDrawable(Ui.round(t.surface, dp(24)));
        w.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setWindowAnimations(R.style.OverlayAnim);
        return d;
    }

    private LinearLayout dialogFooter(View... buttons) {
        LinearLayout f = Ui.row(this, 8);
        f.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        f.setPadding(dp(24), dp(8), dp(24), 0);
        for (View b : buttons) f.addView(b, Ui.wrap());
        return f;
    }

    private void confirm(String title, String body, String ok, boolean danger, final Runnable action) {
        TextView msg = para(body);
        msg.setPadding(dp(24), 0, dp(24), dp(8));
        final Dialog[] holder = new Dialog[1];
        TextView cancel = button(getString(R.string.cancel), 0, 3, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        TextView yes = button(ok, 0, danger ? 2 : 1, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); action.run(); }
        });
        holder[0] = dialog(null, title, msg, dialogFooter(cancel, yes));
        holder[0].getWindow().setLayout(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT);
        holder[0].show();
        yes.requestFocus();
    }

    // ------------------------------------------------------------ 状态

    private void buildStatus() {
        content.addView(h1(getString(R.string.nav_status)));
        boolean active = ProviderClient.moduleActive(this);
        Bundle st = ProviderClient.call(this, "rs_status");
        String mode = st != null ? st.getString("mode") : ModeSwitcher.currentMode(this);
        boolean remote = KeyBindings.MODE_REMOTE.equals(mode);
        String dh = st != null ? st.getString("default_home") : ModeSwitcher.defaultHome(this);
        String ph = st != null ? st.getString("preferred_home") : ModeSwitcher.preferredHome(this);
        boolean consistent = remote
                ? ModeSwitcher.SELF_PKG.equals(dh) && ModeSwitcher.CHOOSER.flattenToShortString().equals(ph)
                : ModeSwitcher.MIFAVOR_PKG.equals(dh) && ModeSwitcher.MIFAVOR_HOME.flattenToShortString().equals(ph);

        LinearLayout c = card();
        c.setPadding(dp(24), dp(24), dp(24), dp(20));
        LinearLayout top = Ui.row(this, 16);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.oval(active ? t.okSoft : t.errSoft, 0, 0));
        ImageView mark = icon(active ? R.drawable.ic_check : R.drawable.ic_alert, active ? t.ok : t.err, 28);
        badge.addView(mark, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        top.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(Ui.text(this, getString(active ? R.string.st_hook_ok : R.string.st_hook_off), 20, t.text, true));
        TextView sub = Ui.text(this, getString(active ? R.string.st_hook_ok_sub : R.string.st_hook_off_sub), 14, t.text2, false);
        sub.setSingleLine(false);
        sub.setPadding(0, dp(4), 0, 0);
        tt.addView(sub);
        top.addView(tt, Ui.weight1());
        top.addView(button(getString(R.string.st_recheck), R.drawable.ic_refresh, 0, new View.OnClickListener() {
            @Override public void onClick(View v) { show(TAB_STATUS); snack(getString(R.string.st_checked), null); }
        }), Ui.wrap());
        c.addView(top);

        LinearLayout tiles = Ui.row(this, 12);
        tiles.setPadding(0, dp(20), 0, 0);
        tiles.addView(tile(getString(R.string.st_mode), getString(remote ? R.string.mode_remote : R.string.mode_tablet), remote ? t.ok : t.muted), Ui.weight1());
        tiles.addView(tile(getString(R.string.st_home), getString(consistent ? (remote ? R.string.st_home_remote_ok : R.string.st_home_tablet_ok) : R.string.st_home_mismatch), consistent ? 0 : t.err), Ui.weight1());
        tiles.addView(tile(getString(R.string.row_boot), getResources().getStringArray(R.array.boot_labels)[KeyBindings.BOOT_KEEP.equals(KeyBindings.bootPolicy(prefs)) ? 1 : 0], 0), Ui.weight1());
        c.addView(tiles, full());

        if (!active) {
            TextView steps = para(getString(R.string.st_fix_steps));
            steps.setTextColor(t.text);
            steps.setPadding(dp(16), dp(14), dp(16), dp(14));
            steps.setBackground(Ui.round(t.errSoft, dp(14)));
            LinearLayout.LayoutParams sp = full();
            sp.topMargin = dp(16);
            c.addView(steps, sp);
        }

        View rule = new View(this);
        rule.setBackgroundColor(t.border);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        rl.topMargin = dp(20);
        c.addView(rule, rl);

        final StringBuilder diag = new StringBuilder();
        diag.append("rs_ping          ").append(active ? "ok" : getString(R.string.st_no_answer)).append('\n');
        diag.append("mode             ").append(mode).append('\n');
        diag.append("default_home     ").append(dh).append('\n');
        diag.append("preferred_home   ").append(ph).append('\n');
        Bundle kb = ProviderClient.call(this, "rs_bindings");
        diag.append("rs_bindings      ").append(kb == null || kb.isEmpty() ? getString(R.string.st_defaults) : KeyBindings.encode(kb).replace(";", "\n                 "));
        TextView toggle = button(getString(diagOpen ? R.string.st_collapse : R.string.st_diag), diagOpen ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down, 3,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { diagOpen = !diagOpen; show(TAB_STATUS); }
                });
        toggle.setPadding(dp(8), 0, dp(12), 0);
        LinearLayout.LayoutParams tl = Ui.wrap();
        tl.topMargin = dp(10);
        c.addView(toggle, tl);
        if (diagOpen) {
            TextView code = Ui.text(this, diag.toString(), 13, t.codeText, false);
            code.setSingleLine(false);
            code.setTypeface(Typeface.MONOSPACE);
            code.setLineSpacing(dp(4), 1f);
            code.setPadding(dp(16), dp(14), dp(16), dp(14));
            code.setBackground(Ui.round(t.code, dp(12)));
            LinearLayout.LayoutParams cl = full();
            cl.topMargin = dp(8);
            c.addView(code, cl);
            LinearLayout.LayoutParams bl = Ui.wrap();
            bl.topMargin = dp(10);
            c.addView(button(getString(R.string.st_copy_diag), 0, 0, new View.OnClickListener() {
                @Override public void onClick(View v) { copy("remoteswitch diagnostics", diag.toString()); snack(getString(R.string.st_diag_copied), null); }
            }), bl);
        }
        content.addView(c, full());
    }

    private View tile(String label, String value, int dotColor) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(Ui.round(t.surface2, dp(14)));
        v.addView(Ui.text(this, label, 13, t.text2, false));
        LinearLayout r = Ui.row(this, 8);
        r.setPadding(0, dp(6), 0, 0);
        if (dotColor != 0) r.addView(Ui.dot(this, 9, dotColor, 0, 0));
        r.addView(Ui.text(this, value, 16, dotColor == t.err ? t.err : t.text, true), Ui.wrap());
        v.addView(r);
        return v;
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    // ------------------------------------------------------------ F9 按键

    /** The hook's copy (Settings.Secure) wins: it is what the keys actually do. */
    private void pullBindings() {
        Bundle kb = ProviderClient.call(this, "rs_bindings");
        if (kb == null || kb.isEmpty()) return;
        SharedPreferences.Editor e = prefs.edit().clear();
        for (String k : KeyBindings.KEYS) {
            String v = kb.getString(k);
            if (v != null) e.putString(k, v);
        }
        e.commit();
    }

    private boolean pushBindings() {
        boolean ok = ProviderClient.setBindings(this, KeyBindings.toBundle(prefs));
        if (!ok) Toast.makeText(this, R.string.bindings_not_synced, Toast.LENGTH_LONG).show();
        return ok;
    }

    private void setBinding(String key, String value, String what) {
        prefs.edit().putString(key, value).commit();
        boolean ok = pushBindings();
        show(TAB_KEYS);
        if (ok) snack(what, null);
    }

    private void buildKeys() {
        LinearLayout hr = headerRow(getString(R.string.nav_keys));
        hr.addView(button(getString(R.string.restore_defaults), 0, 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm(getString(R.string.keys_restore_q), getString(R.string.keys_restore_body), getString(R.string.restore_defaults), false,
                        new Runnable() {
                            @Override public void run() {
                                String boot = KeyBindings.bootPolicy(prefs);
                                prefs.edit().clear().commit();
                                KeyBindings.ensureDefaults(prefs);
                                prefs.edit().putString(KeyBindings.BOOT_KEY, boot).commit();
                                boolean ok = pushBindings();
                                show(TAB_KEYS);
                                if (ok) snack(getString(R.string.keys_restored), null);
                            }
                        });
            }
        }), Ui.wrap());
        content.addView(hr, full());
        content.addView(para(getString(R.string.keys_intro)));

        // two presets cover the sensible setups; the four cells stay one tap away under "advanced"
        String preset = KeyBindings.presetOf(prefs);
        LinearLayout presets = Ui.row(this, 12);
        presets.setGravity(Gravity.TOP);
        presets.addView(radioCard(getString(R.string.preset_chooser), getString(R.string.preset_chooser_desc),
                KeyBindings.PRESET_CHOOSER.equals(preset), presetPick(KeyBindings.PRESET_CHOOSER, R.string.preset_chooser)), Ui.weight1());
        presets.addView(radioCard(getString(R.string.preset_last), getString(R.string.preset_last_desc),
                KeyBindings.PRESET_LAST.equals(preset), presetPick(KeyBindings.PRESET_LAST, R.string.preset_last)), Ui.weight1());
        content.addView(presets, full());

        if (preset != null && !keysAdvanced) {
            LinearLayout.LayoutParams al = Ui.wrap();
            al.topMargin = dp(12);
            content.addView(button(getString(R.string.keys_advanced), R.drawable.ic_chevron_down, 0, new View.OnClickListener() {
                @Override public void onClick(View v) { keysAdvanced = true; show(TAB_KEYS); }
            }), al);
        } else {
            content.addView(h2(getString(R.string.keys_advanced)));
            content.addView(para(getString(R.string.keys_advanced_intro)));
            buildKeyCells();
        }

        content.addView(h2(getString(R.string.row_boot)));
        String[] bl = getResources().getStringArray(R.array.boot_labels);
        boolean keep = KeyBindings.BOOT_KEEP.equals(KeyBindings.bootPolicy(prefs));
        LinearLayout boots = Ui.row(this, 12);
        boots.setGravity(Gravity.TOP);
        boots.addView(radioCard(bl[0], getString(R.string.boot_tablet_desc), !keep, bootPick(KeyBindings.BOOT_TABLET, bl[0])), Ui.weight1());
        boots.addView(radioCard(bl[1], getString(R.string.boot_keep_desc), keep,
                bootPick(KeyBindings.BOOT_KEEP, bl[1])), Ui.weight1());
        content.addView(boots, full());
    }

    private View.OnClickListener presetPick(final String name, final int label) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                SharedPreferences.Editor e = prefs.edit();
                for (java.util.Map.Entry<String, String> en : KeyBindings.preset(name).entrySet()) e.putString(en.getKey(), en.getValue());
                e.commit();
                boolean ok = pushBindings();
                show(TAB_KEYS);
                if (ok) snack(getString(R.string.preset_saved_fmt, getString(label)), null);
            }
        };
    }

    /** The four F9 cells (gesture x mode), each opening the action picker. */
    private void buildKeyCells() {
        LinearLayout c = card();
        LinearLayout head = Ui.row(this, 12);
        head.addView(new View(this), new LinearLayout.LayoutParams(dp(100), 1));
        head.addView(colHead(getString(R.string.keys_in_tablet), t.muted), Ui.weight1());
        head.addView(colHead(getString(R.string.keys_in_remote), t.ok), Ui.weight1());
        c.addView(head, full());
        c.addView(keyRow(getString(R.string.keys_tap), KeyBindings.GESTURE_SHORT), full());
        c.addView(keyRow(getString(R.string.keys_hold), KeyBindings.GESTURE_LONG), full());
        content.addView(c, full());
    }

    private View.OnClickListener bootPick(final String value, final String label) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) { setBinding(KeyBindings.BOOT_KEY, value, getString(R.string.boot_saved_fmt, label)); }
        };
    }

    private View colHead(String s, int dot) {
        LinearLayout r = Ui.row(this, 8);
        r.setPadding(0, 0, 0, dp(4));
        r.addView(Ui.dot(this, 8, dot, 0, 0));
        r.addView(Ui.text(this, s, 13, t.text2, true), Ui.wrap());
        return r;
    }

    private View keyRow(String title, String gesture) {
        LinearLayout r = Ui.row(this, 12);
        r.setPadding(0, dp(12), 0, 0);
        r.addView(Ui.text(this, title, 16, t.text, true), new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(keyCell(gesture, KeyBindings.MODE_TABLET), new LinearLayout.LayoutParams(0, dp(66), 1f));
        r.addView(keyCell(gesture, KeyBindings.MODE_REMOTE), new LinearLayout.LayoutParams(0, dp(66), 1f));
        return r;
    }

    private String cellTitle(String gesture, String mode) {
        return getString(R.string.cell_title_fmt,
                getString(KeyBindings.GESTURE_SHORT.equals(gesture) ? R.string.keys_tap : R.string.keys_hold),
                getString(KeyBindings.MODE_TABLET.equals(mode) ? R.string.keys_in_tablet : R.string.keys_in_remote));
    }

    private int indexOf(String name) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(name)) return i;
        return 0;
    }

    private String targetName(Target x) {
        return x.label + " · " + store.tagFor(x);
    }

    private View keyCell(final String gesture, final String mode) {
        String v = KeyBindings.actionFor(prefs, gesture, mode);
        String name = KeyBindings.actionName(v);
        int i = indexOf(name);
        String label = labels[i], desc = descs[i];
        if (KeyBindings.A_LAUNCH_TARGET.equals(name)) {
            Target x = store.byId(KeyBindings.actionParam(v));
            label = getString(R.string.cell_launch_fmt, x != null ? targetName(x) : getString(R.string.cell_target_deleted));
            desc = getString(R.string.cell_launch_desc);
        }
        boolean changed = !v.equals(KeyBindings.defaultFor(gesture, mode));
        LinearLayout cell = Ui.row(this, 10);
        cell.setPadding(dp(16), 0, dp(14), 0);
        cell.setBackground(Ui.pressable(this, t, t.surface2, t.border, 14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, label, 15, t.text, true));
        TextView d = Ui.text(this, desc, 13, t.text2, false);
        d.setPadding(0, dp(3), 0, 0);
        col.addView(d);
        cell.addView(col, Ui.weight1());
        if (changed) cell.addView(Ui.dot(this, 8, t.accent, 0, 0));
        cell.addView(icon(R.drawable.ic_chevron_down, t.text2, 18));
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { actionDialog(gesture, mode); }
        });
        return cell;
    }

    private void actionDialog(final String gesture, final String mode) {
        final String cur = KeyBindings.actionName(KeyBindings.actionFor(prefs, gesture, mode));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), 0);
        final Dialog[] holder = new Dialog[1];
        View focus = null;
        for (int i = 0; i < values.length; i++) {
            final String name = values[i];
            boolean on = name.equals(cur);
            LinearLayout row = Ui.row(this, 14);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(Ui.pressable(this, t, on ? t.accentSoft : 0x00000000, 0, 12));
            row.addView(radioDot(on));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(this, labels[i], 15, t.text, true));
            TextView d = Ui.text(this, descs[i], 13, t.text2, false);
            d.setPadding(0, dp(2), 0, 0);
            col.addView(d);
            row.addView(col, Ui.weight1());
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    holder[0].dismiss();
                    if (KeyBindings.A_LAUNCH_TARGET.equals(name)) targetDialog(gesture, mode);
                    else setBinding(KeyBindings.key(gesture, mode), name, getString(R.string.cell_saved_fmt, cellTitle(gesture, mode)));
                }
            });
            list.addView(row, full());
            if (on) focus = row;
        }
        TextView cancel = button(getString(R.string.cancel), 0, 3, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        holder[0] = dialog(getString(R.string.pick_action), cellTitle(gesture, mode), list, dialogFooter(cancel));
        holder[0].show();
        if (focus != null) focus.requestFocus();
    }

    private void targetDialog(final String gesture, final String mode) {
        store.refresh();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), 0);
        final Dialog[] holder = new Dialog[1];
        for (final Target x : store.all()) {
            if (!x.available) continue;
            LinearLayout row = Ui.row(this, 14);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(Ui.pressable(this, t, 0x00000000, 0, 12));
            row.addView(targetIcon(x, 36));
            row.addView(Ui.text(this, x.label, 15, t.text, true), Ui.wrap());
            row.addView(Ui.chip(this, store.tagFor(x), t.profile[store.profileColor(x.serial)], t.chip, t.text), Ui.wrap());
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    holder[0].dismiss();
                    setBinding(KeyBindings.key(gesture, mode), KeyBindings.A_LAUNCH_TARGET + ":" + x.id,
                            getString(R.string.cell_saved_launch_fmt, cellTitle(gesture, mode), targetName(x)));
                }
            });
            list.addView(row, full());
        }
        TextView back = button(getString(R.string.back), 0, 3, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); actionDialog(gesture, mode); }
        });
        TextView cancel = button(getString(R.string.cancel), 0, 3, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        holder[0] = dialog(getString(R.string.pick_target_over_fmt, cellTitle(gesture, mode)), getString(R.string.pick_target), list, dialogFooter(back, cancel));
        holder[0].show();
    }

    // ------------------------------------------------------------ 选择层

    private void buildChooser() {
        content.addView(h1(getString(R.string.chooser_section)));
        content.addView(para(getString(R.string.ch_intro)));
        final boolean b = "B".equals(ui.getString(OverlayActivity.K_STYLE, "A"));
        LinearLayout styles = Ui.row(this, 12);
        styles.setGravity(Gravity.TOP);
        styles.addView(radioCard(getString(R.string.ch_style_a), getString(R.string.ch_style_a_desc), !b, stylePick("A")), Ui.weight1());
        styles.addView(radioCard(getString(R.string.ch_style_b), getString(R.string.ch_style_b_desc), b, stylePick("B")), Ui.weight1());
        content.addView(styles, full());

        LinearLayout c = card();
        LinearLayout.LayoutParams cl = full();
        cl.topMargin = dp(16);
        LinearLayout r = Ui.row(this, 16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, getString(R.string.blur_behind), 15, t.text, true));
        TextView d = Ui.text(this, getString(R.string.ch_blur_desc), 13, t.text2, false);
        d.setSingleLine(false);
        d.setPadding(0, dp(4), 0, 0);
        col.addView(d);
        r.addView(col, Ui.weight1());
        Switch sw = new Switch(this);
        sw.setChecked(ui.getBoolean(OverlayActivity.K_BLUR_BEHIND, true));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton x, boolean on) {
                ui.edit().putBoolean(OverlayActivity.K_BLUR_BEHIND, on).commit();
                snack(getString(on ? R.string.ch_blur_on : R.string.ch_blur_off), null);
            }
        });
        r.addView(sw, Ui.wrap());
        c.addView(r, full());
        content.addView(c, cl);

        LinearLayout.LayoutParams bl = Ui.wrap();
        bl.topMargin = dp(20);
        content.addView(button(getString(R.string.preview_chooser), R.drawable.ic_layers, 1, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(OverlayActivity.intent(SettingsActivity.this)); }
        }), bl);
    }

    private View.OnClickListener stylePick(final String style) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ui.edit().putString(OverlayActivity.K_STYLE, style).commit();
                show(TAB_CHOOSER);
                snack(getString(R.string.ch_style_set_fmt, style), null);
            }
        };
    }

    // ------------------------------------------------------------ 远程目标

    private void buildTargets() {
        LinearLayout hr = headerRow(getString(R.string.nav_targets));
        targetSummary = Ui.text(this, "", 14, t.text2, false);
        hr.addView(targetSummary, Ui.wrap());
        hr.addView(button(getString(R.string.rescan), R.drawable.ic_refresh, 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = store.scanKnown();
                fillTargets(null);
                snack(n > 0 ? getString(R.string.tg_scan_added_fmt, n) : getString(R.string.tg_scan_none), null);
            }
        }), Ui.wrap());
        hr.addView(button(getString(R.string.add_app), R.drawable.ic_add, 1, new View.OnClickListener() {
            @Override public void onClick(View v) { addAppDialog(); }
        }), Ui.wrap());
        content.addView(hr, full());
        content.addView(para(getString(R.string.tg_intro)));

        targetList = new LinearLayout(this);
        targetList.setOrientation(LinearLayout.VERTICAL);
        targetList.setBackground(cardBg());
        targetList.setPadding(0, dp(4), 0, dp(4));
        content.addView(targetList, full());
        fillTargets(null);
    }

    /** Rebuilds the rows; focusId/which restore keyboard focus after a move. */
    private void fillTargets(String focusId) {
        if (targetList == null) return;
        store.refresh();
        targetList.removeAllViews();
        List<Target> all = store.all();
        int hidden = 0;
        View focus = null;
        for (int i = 0; i < all.size(); i++) {
            final Target x = all.get(i);
            if (x.hidden) hidden++;
            if (i > 0) {
                View rule = new View(this);
                rule.setBackgroundColor(t.border);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
                rl.leftMargin = dp(72);
                targetList.addView(rule, rl);
            }
            View row = targetRow(x, i, all.size());
            targetList.addView(row, full());
            if (x.id.equals(focusId)) focus = row;
        }
        if (all.isEmpty()) {
            TextView e = para(getString(R.string.tg_empty));
            e.setPadding(dp(20), dp(20), dp(20), dp(20));
            targetList.addView(e);
        }
        targetSummary.setText(getString(R.string.tg_summary_fmt, all.size(), hidden) + "  ");
        if (focus != null) focus.requestFocus();
    }

    private View targetIcon(Target x, int sizeDp) {
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        ImageView iv = new ImageView(this);
        Drawable ic = x.icon;
        if (ic == null) {
            try { ic = getPackageManager().getApplicationIcon(x.pkg); } catch (Throwable e) { ic = getPackageManager().getDefaultActivityIcon(); }
        }
        iv.setImageDrawable(ic);
        if (!x.available) iv.setAlpha(0.45f);
        box.addView(iv, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        View badge = new View(this);
        badge.setBackground(Ui.oval(t.profile[store.profileColor(x.serial)], dp(2), t.surface));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM | Gravity.END);
        bl.setMargins(0, 0, -dp(3), -dp(3));
        box.addView(badge, bl);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return box;
    }

    private View targetRow(final Target x, final int index, int count) {
        LinearLayout row = Ui.row(this, 14);
        row.setPadding(dp(16), dp(10), dp(10), dp(10));
        row.setMinimumHeight(dp(64));
        row.setBackground(Ui.pressable(this, t, 0x00000000, 0, 14));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editNote(x); }
        });
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN || !e.isAltPressed()) return false;
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) { move(x, -1); return true; }
                if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) { move(x, 1); return true; }
                return false;
            }
        });

        View ic = targetIcon(x, 40);
        row.addView(ic);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = Ui.row(this, 8);
        line.addView(Ui.text(this, x.label, 16, t.text, true), Ui.wrap());
        line.addView(Ui.chip(this, store.tagFor(x), t.profile[store.profileColor(x.serial)], t.chip, t.text), Ui.wrap());
        if (!x.available) line.addView(Ui.chip(this, x.reason != null ? x.reason : getString(R.string.reason_unavailable), t.err, t.errSoft, t.err), Ui.wrap());
        col.addView(line);
        String own = x.displayNote().isEmpty() ? "" : getString(R.string.tg_note_overrides_fmt, store.profileName(x.serial));
        TextView sub = Ui.text(this, x.pkg + " · user serial " + x.serial + own, 12, t.text2, false);
        sub.setPadding(0, dp(4), 0, 0);
        col.addView(sub);
        col.setAlpha(x.hidden ? 0.55f : 1f);
        ic.setAlpha(x.hidden ? 0.55f : 1f);
        row.addView(col, Ui.weight1());

        row.addView(Ui.text(this, getString(x.hidden ? R.string.tg_hidden : R.string.tg_shown), 13, t.text2, false), Ui.wrap());
        Switch sw = new Switch(this);
        sw.setChecked(!x.hidden);
        sw.setContentDescription(getString(R.string.tg_show_in_fmt, x.label));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                x.hidden = !on;
                store.save();
                fillTargets(x.id);
            }
        });
        row.addView(sw, Ui.wrap());

        ImageView up = iconButton(R.drawable.ic_chevron_up, getString(R.string.tg_up_fmt, x.label), new View.OnClickListener() {
            @Override public void onClick(View v) { move(x, -1); }
        });
        up.setEnabled(index > 0);
        up.setAlpha(index > 0 ? 1f : 0.3f);
        row.addView(up);
        ImageView down = iconButton(R.drawable.ic_chevron_down, getString(R.string.tg_down_fmt, x.label), new View.OnClickListener() {
            @Override public void onClick(View v) { move(x, 1); }
        });
        down.setEnabled(index < count - 1);
        down.setAlpha(index < count - 1 ? 1f : 0.3f);
        row.addView(down);
        row.addView(iconButton(R.drawable.ic_delete, getString(R.string.tg_delete_fmt, x.label), new View.OnClickListener() {
            @Override public void onClick(View v) { delete(x); }
        }));
        return row;
    }

    private void move(Target x, int delta) {
        store.move(x, delta);
        fillTargets(x.id);
    }

    private void delete(Target x) {
        removedIndex = store.all().indexOf(x);
        removed = x;
        store.remove(x);
        fillTargets(null);
        snack(getString(R.string.tg_deleted_fmt, x.label + " · " + store.tagFor(x)), new Runnable() {
            @Override public void run() {
                if (removed == null) return;
                List<Target> list = store.all();
                list.add(Math.min(removedIndex, list.size()), removed);
                for (int k = 0; k < list.size(); k++) list.get(k).order = k;
                store.save();
                String id = removed.id;
                removed = null;
                fillTargets(id);
            }
        });
    }

    private void editNote(final Target x) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), 0, dp(24), dp(8));
        TextView hint = para(getString(R.string.note_hint_fmt, store.profileName(x.serial)));
        body.addView(hint);
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setText(x.displayNote());
        et.setSelectAllOnFocus(true);
        et.setTextColor(t.text);
        et.setTextSize(16);
        body.addView(et, full());
        final Dialog[] holder = new Dialog[1];
        TextView cancel = button(getString(R.string.cancel), 0, 3, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        TextView ok = button(getString(R.string.save), 0, 1, new View.OnClickListener() {
            @Override public void onClick(View v) {
                x.note = et.getText().toString().trim();
                store.save();
                holder[0].dismiss();
                fillTargets(x.id);
                snack(getString(R.string.note_saved), null);
            }
        });
        holder[0] = dialog(x.label + " · user serial " + x.serial, getString(R.string.edit_note), body, dialogFooter(cancel, ok));
        holder[0].show();
        et.requestFocus();
    }

    private void addAppDialog() {
        final List<LauncherActivityInfo> all = store.allActivities();
        final UserManager um = (UserManager) getSystemService(USER_SERVICE);
        final Set<String> have = new HashSet<>();
        for (Target x : store.all()) have.add(x.id);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), 0, dp(12), 0);
        final EditText filter = new EditText(this);
        filter.setHint(R.string.add_filter_hint);
        filter.setSingleLine(true);
        filter.setTextColor(t.text);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fl = full();
        fl.leftMargin = dp(12);
        fl.rightMargin = dp(12);
        body.addView(filter, fl);
        body.addView(rows, full());

        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() {
            @Override public void run() {
                rows.removeAllViews();
                String q = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
                int shown = 0;
                for (final LauncherActivityInfo lai : all) {
                    final long serial = um.getSerialNumberForUser(lai.getUser());
                    String pkg = lai.getComponentName().getPackageName();
                    final String prof = store.profileName(serial);
                    String label = String.valueOf(lai.getLabel());
                    if (!q.isEmpty() && !(label + " " + pkg + " " + prof).toLowerCase(Locale.ROOT).contains(q)) continue;
                    if (++shown > 200) break;
                    final String id = Target.makeId(pkg, lai.getComponentName().getClassName(), serial);
                    final boolean added = have.contains(id);
                    LinearLayout row = Ui.row(SettingsActivity.this, 12);
                    row.setPadding(dp(12), dp(6), dp(12), dp(6));
                    row.setBackground(Ui.pressable(SettingsActivity.this, t, 0x00000000, 0, 12));
                    ImageView iv = new ImageView(SettingsActivity.this);
                    iv.setImageDrawable(lai.getIcon(0));
                    row.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
                    row.addView(Ui.text(SettingsActivity.this, label, 15, t.text, true), Ui.wrap());
                    row.addView(Ui.chip(SettingsActivity.this, prof, t.profile[store.profileColor(serial)], t.chip, t.text), Ui.wrap());
                    row.addView(new View(SettingsActivity.this), new LinearLayout.LayoutParams(0, 1, 1f));
                    if (added) row.addView(icon(R.drawable.ic_check, t.ok, 18));
                    row.setAlpha(added ? 0.5f : 1f);
                    row.setFocusable(true);
                    row.setClickable(!added);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (have.contains(id)) return;
                            store.add(lai);
                            have.add(id);
                            fillTargets(null);
                            snack(getString(R.string.add_added_fmt, lai.getLabel() + " · " + prof), null);
                            fill[0].run();
                        }
                    });
                    rows.addView(row, full());
                }
            }
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        fill[0].run();
        final Dialog[] holder = new Dialog[1];
        TextView done = button(getString(R.string.done), 0, 1, new View.OnClickListener() {
            @Override public void onClick(View v) { holder[0].dismiss(); }
        });
        holder[0] = dialog(getString(R.string.add_overline), getString(R.string.add_app), body, dialogFooter(done));
        holder[0].getWindow().setLayout(dp(620), dp(600));
        holder[0].show();
        filter.requestFocus();
    }

    // ------------------------------------------------------------ 资料名与颜色

    private void buildProfiles() {
        content.addView(h1(getString(R.string.nav_profiles)));
        content.addView(para(getString(R.string.pf_intro)));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(cardBg());
        list.setPadding(0, dp(4), 0, dp(4));
        store.refresh();
        List<Long> serials = store.profileSerials();
        for (int i = 0; i < serials.size(); i++) {
            if (i > 0) {
                View rule = new View(this);
                rule.setBackgroundColor(t.border);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
                rl.leftMargin = dp(64);
                list.addView(rule, rl);
            }
            list.addView(profileRow(serials.get(i)), full());
        }
        content.addView(list, full());
    }

    private View profileRow(final long serial) {
        LinearLayout row = Ui.row(this, 16);
        row.setPadding(dp(18), dp(12), dp(14), dp(12));

        final View swatch = new View(this);
        swatch.setBackground(Ui.round(t.profile[store.profileColor(serial)], dp(9)));
        row.addView(swatch, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        UserHandleInfo uh = new UserHandleInfo(serial);
        col.addView(Ui.text(this, uh.label(), 12, t.text2, false));
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(t.text);
        name.setHint(store.defaultName(serial));
        name.setText(store.profileName(serial));
        name.setSelectAllOnFocus(true);
        name.setPadding(dp(10), 0, dp(10), 0);
        name.setBackground(Ui.pressable(this, t, t.surface2, t.border, 8));
        name.setContentDescription(getString(R.string.pf_name_desc_fmt, uh.label()));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { store.setProfileName(serial, s.toString()); }
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(220), dp(34));
        nl.topMargin = dp(4);
        col.addView(name, nl);
        row.addView(col, Ui.wrap());

        Set<String> apps = new java.util.LinkedHashSet<>();
        for (Target x : store.all()) if (x.serial == serial) apps.add(x.label);
        String uses = apps.isEmpty() ? getString(R.string.pf_no_targets) : getString(R.string.pf_used_by_fmt, android.text.TextUtils.join(getString(R.string.list_sep), apps));
        TextView u = Ui.text(this, uses, 13, t.text2, false);
        row.addView(u, Ui.weight1());

        final LinearLayout swatches = Ui.row(this, 6);
        fillSwatches(swatches, swatch, serial);
        row.addView(swatches, Ui.wrap());
        return row;
    }

    private void fillSwatches(final LinearLayout box, final View big, final long serial) {
        box.removeAllViews();
        int cur = store.profileColor(serial);
        for (int c = 0; c < TargetStore.COLOR_SLOTS; c++) {
            final int slot = c;
            View s = new View(this);
            android.graphics.drawable.GradientDrawable g = Ui.oval(t.profile[c], dp(2), c == cur ? t.text : t.surface);
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[] {android.R.attr.state_focused}, Ui.oval(t.profile[c], dp(3), t.accent));
            sl.addState(new int[0], g);
            s.setBackground(sl);
            s.setFocusable(true);
            s.setClickable(true);
            s.setContentDescription(getString(R.string.pf_color_fmt, c + 1));
            s.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.setProfileColor(serial, slot);
                    big.setBackground(Ui.round(t.profile[slot], dp(9)));
                    fillSwatches(box, big, serial);
                    box.getChildAt(slot).requestFocus();
                }
            });
            box.addView(s, new LinearLayout.LayoutParams(dp(26), dp(26)));
        }
    }

    /** "user 11 · serial 12" */
    private final class UserHandleInfo {
        final long serial;
        final int userId;

        UserHandleInfo(long serial) {
            this.serial = serial;
            this.userId = store.userIdFor(serial);
        }

        String label() {
            return (userId >= 0 ? "user " + userId + " · " : "") + "serial " + serial;
        }
    }

    // ------------------------------------------------------------ 关于与回滚

    private void buildAbout() {
        content.addView(h1(getString(R.string.nav_about)));
        LinearLayout c = card();
        c.addView(infoRow(getString(R.string.ab_version), versionName()));
        c.addView(infoRow(getString(R.string.ab_scope), "com.zte.usmartlauncher · cm.komect.aqb.android.hjqbind"));
        c.addView(infoRow(getString(R.string.ab_nofreeze), getString(R.string.ab_nofreeze_desc)));
        content.addView(c, full());

        content.addView(h2(getString(R.string.ab_stuck)));
        final String cmds = "settings put secure default_home com.zte.mifavor.launcher\n"
                + "cmd package set-home-activity com.zte.mifavor.launcher/com.android.launcher3.uioverrides.QuickstepLauncher\n"
                + "settings put secure remoteswitch_mode tablet";
        TextView code = Ui.text(this, cmds, 13, t.codeText, false);
        code.setSingleLine(false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setLineSpacing(dp(5), 1f);
        code.setPadding(dp(16), dp(14), dp(16), dp(14));
        code.setBackground(Ui.round(t.code, dp(16)));
        content.addView(code, full());
        LinearLayout.LayoutParams bl = Ui.wrap();
        bl.topMargin = dp(10);
        content.addView(button(getString(R.string.ab_copy_cmds), 0, 0, new View.OnClickListener() {
            @Override public void onClick(View v) { copy("remoteswitch rollback", cmds); snack(getString(R.string.ab_cmds_copied), null); }
        }), bl);

        LinearLayout danger = Ui.row(this, 16);
        danger.setPadding(dp(20), dp(18), dp(20), dp(18));
        android.graphics.drawable.GradientDrawable g = Ui.round(0x00000000, dp(16));
        g.setStroke(Math.max(1, dp(1)), t.err);
        danger.setBackground(g);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, getString(R.string.ab_force), 15, t.err, true));
        TextView d = Ui.text(this, getString(R.string.ab_force_desc), 13, t.text2, false);
        d.setPadding(0, dp(4), 0, 0);
        col.addView(d);
        danger.addView(col, Ui.weight1());
        danger.addView(button(getString(R.string.ab_force_btn), 0, 2, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm(getString(R.string.ab_force_q), getString(R.string.ab_force_body), getString(R.string.ab_force_btn), true,
                        new Runnable() {
                            @Override public void run() {
                                if (!ProviderClient.exitRemote(SettingsActivity.this)) {
                                    Toast.makeText(SettingsActivity.this, R.string.module_inactive, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
            }
        }), Ui.wrap());
        LinearLayout.LayoutParams dl = full();
        dl.topMargin = dp(28);
        content.addView(danger, dl);
    }

    private View infoRow(String k, String v) {
        LinearLayout r = Ui.row(this, 0);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(5), 0, dp(5));
        r.addView(Ui.text(this, k, 14, t.text2, false), new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tv = Ui.text(this, v, 14, t.text, false);
        tv.setSingleLine(false);
        r.addView(tv, Ui.weight1());
        return r;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }
}

package dev.w203ds.remoteswitch;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The chooser: a floating, frosted-glass window over whatever is on screen (remote session,
 * ZTE desktop or our wallpaper HOME). Style "A": centred panel + compact tile grid;
 * style "B": top command panel + filter + 2-column list. Started by the hooks
 * ({@link ModeSwitcher#startChooser}) and by {@link ChooserActivity}.
 */
public class OverlayActivity extends Activity {
    /** app-private UI prefs; the hooks never read these */
    static final String PREFS = "ui";
    static final String K_STYLE = "chooser_style";      // "A" | "B"
    static final String K_BLUR_BEHIND = "blur_behind";  // default true
    /** id of the "tablet desktop" entry that ends the list (not a stored target) */
    private static final String TABLET_ID = "#tablet";

    private static final int PANEL_BLUR_DP = 28;
    private static final int BEHIND_BLUR_DP = 16;

    private TargetStore store;
    private Ui.Theme t;
    private boolean variantB;
    private final int cols = 6;
    private String mode;
    private boolean active;
    private final List<Target> all = new ArrayList<>();
    private final List<Target> shown = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private String lastId;
    private int sel;
    /** "pkg:userId" of the clients with an open session */
    private java.util.Set<String> running = new java.util.HashSet<>();

    private EditText query;
    private LinearLayout listBox;
    private TextView countText;
    private TextView emptyText;
    private View emptyAll;
    /** in-panel message line; replaces Toasts while the panel stays open */
    private TextView noticeView;
    private String pendingNotice;
    private final Handler noticeHandler = new Handler();

    static Intent intent(Context ctx) {
        return new Intent(ctx, OverlayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new TargetStore(this);
        if (store.all().isEmpty()) store.scanKnown();
        if (runDirectAction(getIntent())) {
            // switch_last / cycle_next / launch_target: no UI, leave without a dim or animation
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setWindowAnimations(0);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        variantB = "B".equals(prefs.getString(K_STYLE, "A"));
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        t = Ui.Theme.of(night);
        load();
        setupWindow(prefs.getBoolean(K_BLUR_BEHIND, true));
        setContentView(variantB ? buildB() : buildA());
        if (variantB) rebuildList();
        else {
            select(sel, false);
            stagger();
        }
        if (pendingNotice != null) notice(pendingNotice); // e.g. a launch_target binding whose target is gone
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (runDirectAction(intent)) {
            finish();
            return;
        }
        if (pendingNotice != null) notice(pendingNotice);
        // F9 while we are up toggles us off like Esc (the hook normally sends close_chooser for that already)
        String src = intent.getStringExtra(ModeSwitcher.EXTRA_SOURCE);
        if (KeyBindings.GESTURE_LONG.equals(src) || KeyBindings.GESTURE_SHORT.equals(src)) finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // transient: once covered (F9 back to tablet, home, another app) it never comes back stale
        if (!isFinishing() && !isChangingConfigurations()) finish();
    }

    /** @return true when the intent asked to leave without UI: a target was launched, or F9 closes us */
    private boolean runDirectAction(Intent in) {
        String action = in == null ? null : in.getStringExtra(ModeSwitcher.EXTRA_ACTION);
        String param = in == null ? null : in.getStringExtra(ModeSwitcher.EXTRA_PARAM);
        if (action == null) return false;
        in.removeExtra(ModeSwitcher.EXTRA_ACTION);
        if (KeyBindings.A_CLOSE_CHOOSER.equals(action)) return true; // F9 with us up; nothing to launch
        store.refresh();
        List<Target> v = store.visible();
        switch (action) {
            case KeyBindings.A_SWITCH_LAST: {
                Target t = store.byId(store.lastId());
                return t != null && t.available && !t.hidden && launch(t, null);
            }
            case KeyBindings.A_CYCLE_NEXT: {
                int idx = -1;
                for (int i = 0; i < v.size(); i++) if (v.get(i).id.equals(store.lastId())) idx = i;
                for (int k = 1; k <= v.size(); k++) {
                    Target t = v.get((idx + k) % v.size());
                    if (t.available) return launch(t, null);
                }
                return false;
            }
            case KeyBindings.A_LAUNCH_TARGET: {
                Target t = store.byId(param == null ? "" : param);
                if (t != null && t.available) return launch(t, null);
                notice(t == null ? getString(R.string.ov_target_deleted) : unavailableMessage(t));
                return false;
            }
            default:
                return false; // show_chooser
        }
    }

    private void load() {
        store.refresh();
        all.clear();
        all.addAll(store.visible());
        mode = ModeSwitcher.currentMode(this);
        shown.clear();
        shown.addAll(all);
        shown.add(tabletEntry());
        lastId = store.lastId();
        sel = 0;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(lastId)) sel = i;
        active = ProviderClient.moduleActive(this);
        running = ProviderClient.running(this);
        if (active && KeyBindings.MODE_REMOTE.equals(mode)
                && !ModeSwitcher.SELF_PKG.equals(ModeSwitcher.defaultHome(this))) {
            ProviderClient.sync(this); // somebody (ZTE) changed default_home behind our back
        }
    }

    private boolean remote() {
        return KeyBindings.MODE_REMOTE.equals(mode);
    }

    /** "Tablet desktop" as the last entry of the list, so leaving is picked like any target (B in style A). */
    private Target tabletEntry() {
        Target x = new Target();
        x.id = TABLET_ID;
        x.pkg = ModeSwitcher.MIFAVOR_PKG;
        x.label = getString(R.string.key_tablet_desktop);
        x.available = true;
        return x;
    }

    private static boolean isTablet(Target x) {
        return TABLET_ID.equals(x.id);
    }

    /** shown targets, without the tablet entry */
    private int targetCount() {
        int n = 0;
        for (Target x : shown) if (!isTablet(x)) n++;
        return n;
    }

    private boolean isRunning(Target x) {
        return running.contains(x.pkg + ":" + store.userIdFor(x.serial));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------ window

    private void setupWindow(boolean blurBehind) {
        Window w = getWindow();
        w.getDecorView(); // background blur needs the decor to exist
        boolean blur = Build.VERSION.SDK_INT >= 31 && getWindowManager().isCrossWindowBlurEnabled();
        GradientDrawable bg = Ui.round(blur ? t.glass : t.glassOpaque, dp(variantB ? 26 : 28));
        bg.setStroke(Math.max(1, dp(0.5f)), t.glassEdge);
        w.setBackgroundDrawable(bg);
        if (blur) w.setBackgroundBlurRadius(dp(PANEL_BLUR_DP));

        WindowManager.LayoutParams lp = w.getAttributes();
        if (blur && blurBehind) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            lp.setBlurBehindRadius(dp(BEHIND_BLUR_DP));
        }
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        lp.dimAmount = blur && blurBehind ? 0.18f : 0.22f;
        lp.width = dp(variantB ? 660 : cols * 122 + 30);
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (variantB) {
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(64);
        } else {
            lp.gravity = Gravity.CENTER;
        }
        w.setAttributes(lp);
        w.setWindowAnimations(variantB ? R.style.OverlayAnimTop : R.style.OverlayAnim);
        setFinishOnTouchOutside(true);
    }

    // ------------------------------------------------------------ shared bits

    private LinearLayout statusPill() {
        int fg = !active ? t.err : remote() ? t.ok : t.text;
        int dot = !active ? t.err : remote() ? t.ok : t.muted;
        int soft = !active ? t.errSoft : remote() ? t.okSoft : t.chip;
        LinearLayout pill = Ui.row(this, 7);
        pill.setPadding(dp(9), 0, dp(11), 0);
        pill.setMinimumHeight(dp(28));
        pill.setBackground(Ui.round(soft, dp(14)));
        pill.addView(Ui.dot(this, 8, dot, 0, 0));
        // the mode in words; the dot colour repeats it (green remote, grey tablet, red module off)
        pill.addView(Ui.text(this, getString(!active ? R.string.ov_module_off : remote() ? R.string.mode_remote : R.string.mode_tablet),
                13, fg, true), Ui.wrap());
        return pill;
    }

    /** Red banner when the hook does not answer: says what breaks and offers the self check. */
    private View moduleBanner() {
        LinearLayout b = Ui.row(this, 10);
        b.setPadding(dp(12), dp(8), dp(8), dp(8));
        b.setBackground(Ui.round(t.errSoft, dp(12)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_alert);
        ic.setColorFilter(t.err);
        b.addView(ic, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView tx = Ui.text(this, getString(R.string.ov_banner), 13, t.err, false);
        tx.setSingleLine(false);
        b.addView(tx, Ui.weight1());
        TextView check = Ui.text(this, getString(R.string.ov_self_check), 12, t.err, true);
        check.setGravity(Gravity.CENTER);
        check.setHeight(dp(26));
        check.setPadding(dp(10), 0, dp(10), 0);
        android.graphics.drawable.GradientDrawable g = Ui.round(0x00000000, dp(8));
        g.setStroke(Math.max(1, dp(1)), t.err);
        check.setBackground(g);
        check.setClickable(true);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(SettingsActivity.TAB_STATUS, false); }
        });
        b.addView(check, Ui.wrap());
        return b;
    }

    /** No targets at all: say so and offer the add-app picker in settings. */
    private View emptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(28), dp(24), dp(24));
        box.addView(Ui.text(this, getString(R.string.ov_empty_title), 16, t.text, true));
        TextView sub = Ui.text(this, getString(R.string.ov_empty_sub), 13, t.text2, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        box.addView(sub);
        TextView add = Ui.text(this, getString(R.string.add_app), 14, t.onAccent, true);
        add.setGravity(Gravity.CENTER);
        add.setHeight(dp(38));
        add.setPadding(dp(18), 0, dp(18), 0);
        add.setBackground(Ui.pressable(this, t, t.accent, 0, 12));
        add.setClickable(true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(SettingsActivity.TAB_TARGETS, true); }
        });
        box.addView(add, Ui.wrap());
        return box;
    }

    private TextView noticeLine() {
        TextView n = Ui.text(this, "", 13, t.err, false);
        n.setSingleLine(false);
        n.setPadding(dp(12), dp(9), dp(12), dp(9));
        n.setBackground(Ui.round(t.errSoft, dp(12)));
        Drawable d = getDrawable(R.drawable.ic_alert).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        d.setTint(t.err);
        n.setCompoundDrawables(d, null, null, null);
        n.setCompoundDrawablePadding(dp(8));
        n.setGravity(Gravity.CENTER_VERTICAL);
        n.setVisibility(View.GONE);
        return n;
    }

    /** Shows a message inside the panel for a few seconds (queued if the panel is not built yet). */
    private void notice(String msg) {
        if (noticeView == null) {
            pendingNotice = msg;
            return;
        }
        pendingNotice = null;
        noticeView.setText(msg);
        noticeView.setVisibility(View.VISIBLE);
        noticeView.setAlpha(0f);
        noticeView.setTranslationY(dp(6));
        noticeView.animate().alpha(1f).translationY(0).setDuration(280).setInterpolator(Ui.spring()).start();
        noticeHandler.removeCallbacksAndMessages(null);
        noticeHandler.postDelayed(new Runnable() {
            @Override public void run() {
                noticeView.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
                    @Override public void run() { noticeView.setVisibility(View.GONE); }
                }).start();
            }
        }, 4500);
    }

    private String tagged(Target x) {
        return getString(R.string.tag_fmt, x.label, store.tagFor(x));
    }

    private String unavailableMessage(Target x) {
        String r = x.reason == null ? getString(R.string.reason_unavailable) : x.reason;
        int hint;
        if (x.reasonRes == R.string.reason_frozen || x.reasonRes == R.string.reason_hidden
                || x.reasonRes == R.string.reason_suspended) hint = R.string.ov_hint_frozen;
        else if (x.reasonRes == R.string.reason_profile_stopped || x.reasonRes == R.string.reason_profile_paused) hint = R.string.ov_hint_profile;
        else if (x.reasonRes == R.string.reason_uninstalled || x.reasonRes == R.string.reason_profile_deleted) hint = R.string.ov_hint_gone;
        else if (x.reasonRes == R.string.reason_entry_changed) hint = R.string.ov_hint_moved;
        else hint = 0;
        String msg = getString(R.string.ov_unavailable_fmt, tagged(x), r);
        return hint == 0 ? msg : msg + " · " + getString(hint);
    }

    private View keyButton(String key, String label, View.OnClickListener l) {
        LinearLayout b = Ui.row(this, 7);
        b.setPadding(dp(6), 0, dp(10), 0);
        b.setMinimumHeight(dp(32));
        b.setBackground(Ui.round(t.chip, dp(10)));
        b.addView(Ui.keycap(this, key, t, 20));
        b.addView(Ui.text(this, label, 13, t.text, false), Ui.wrap());
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private View smallButton(String label, View.OnClickListener l) {
        TextView b = Ui.text(this, label, 12, t.text, false);
        b.setGravity(Gravity.CENTER);
        b.setHeight(dp(28));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(Ui.round(t.chip, dp(9)));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private final View.OnClickListener toSettings = new View.OnClickListener() {
        @Override public void onClick(View v) { openSettings(); }
    };

    private LinearLayout hints(String[][] hs) {
        LinearLayout r = Ui.row(this, 16);
        for (String[] h : hs) {
            LinearLayout one = Ui.row(this, 6);
            one.addView(Ui.keycap(this, h[0], t, 18));
            one.addView(Ui.text(this, h[1], 12, t.text2, false), Ui.wrap());
            r.addView(one, Ui.wrap());
        }
        return r;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(t.divider);
        return d;
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    private String keyLabel(int i, boolean enabled) {
        if (!enabled || i > 9) return null;
        return i == 9 ? "0" : String.valueOf(i + 1);
    }

    // ------------------------------------------------------------ style A

    private View buildA() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(15), dp(18), dp(15), dp(14));
        root.setClipChildren(false);

        LinearLayout header = Ui.row(this, 10);
        header.setPadding(dp(5), 0, dp(5), 0);
        header.addView(statusPill(), Ui.wrap());
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(keyButton("S", getString(R.string.settings), toSettings), Ui.wrap());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp(9);
        root.addView(header, hp);
        if (!active) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.setMargins(dp(5), 0, dp(5), dp(9));
            root.addView(moduleBanner(), bl);
        }

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        grid.setUseDefaultMargins(false);
        grid.setClipChildren(false);
        cells.clear();
        for (int i = 0; i < shown.size(); i++) {
            Cell c = new Cell(i, shown.get(i), false, isTablet(shown.get(i)) ? "B" : keyLabel(i, true));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(112);
            lp.height = dp(104);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(c.view, lp);
            cells.add(c);
        }
        if (all.isEmpty()) root.addView(emptyState(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(grid, Ui.wrap());

        noticeView = noticeLine();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.setMargins(dp(5), dp(8), dp(5), 0);
        root.addView(noticeView, nl);

        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        dl.setMargins(dp(5), dp(7), dp(5), dp(10));
        root.addView(divider(), dl);
        LinearLayout hs = hints(new String[][] {
                {"1–0", getString(R.string.hint_pick)}, {"←→", getString(R.string.hint_move)}, {"↵", getString(R.string.hint_open)},
                {"Esc · F9", getString(R.string.hint_close)}});
        hs.setPadding(dp(5), 0, dp(5), 0);
        root.addView(hs);
        return root;
    }

    private void stagger() {
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            View v = c.view;
            v.setAlpha(0f);
            v.setTranslationY(dp(variantB ? -6 : 12));
            if (!variantB) {
                v.setScaleX(0.9f);
                v.setScaleY(0.9f);
            }
            float s = c.on ? c.selScale : 1f;
            v.animate().alpha(c.restAlpha).translationY(0).scaleX(s).scaleY(s)
                    .setStartDelay(variantB ? 40 + i * 18 : 60 + i * 22)
                    .setDuration(variantB ? 360 : 420)
                    .setInterpolator(variantB ? Ui.spring() : Ui.springTile())
                    .start();
        }
    }

    // ------------------------------------------------------------ style B

    private View buildB() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setClipChildren(false);

        LinearLayout field = Ui.row(this, 10);
        field.setPadding(dp(14), 0, dp(8), 0);
        field.setBackground(Ui.round(t.field, dp(18)));
        ImageView mag = new ImageView(this);
        mag.setImageResource(R.drawable.ic_search);
        mag.setColorFilter(t.text2);
        field.addView(mag, new LinearLayout.LayoutParams(dp(20), dp(20)));
        query = new EditText(this);
        query.setBackground(null);
        query.setPadding(0, 0, 0, 0);
        query.setTextSize(17);
        query.setTextColor(t.text);
        query.setHintTextColor(0xFF7A8089);
        query.setHint(R.string.ov_filter_hint);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refilter(); }
        });
        field.addView(query, new LinearLayout.LayoutParams(0, dp(40), 1f));
        field.addView(statusPill(), Ui.wrap());
        root.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        if (!active) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = dp(8);
            root.addView(moduleBanner(), bl);
        }

        LinearLayout meta = Ui.row(this, 0);
        meta.setPadding(dp(6), dp(10), dp(6), dp(4));
        countText = Ui.text(this, "", 12, t.text2, false);
        meta.addView(countText, Ui.weight1());
        root.addView(meta);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setClipChildren(false);
        root.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyText = Ui.text(this, "", 14, t.text2, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, dp(28), 0, dp(24));
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyAll = emptyState();
        emptyAll.setVisibility(View.GONE);
        root.addView(emptyAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        noticeView = noticeLine();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.setMargins(dp(4), dp(8), dp(4), 0);
        root.addView(noticeView, nl);

        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        dl.setMargins(dp(6), dp(8), dp(6), dp(10));
        root.addView(divider(), dl);
        LinearLayout foot = Ui.row(this, 8);
        foot.setPadding(dp(6), 0, dp(6), dp(2));
        foot.addView(hints(new String[][] {{"↑↓←→", getString(R.string.hint_move)}, {"↵", getString(R.string.hint_open)},
                {"1–0", getString(R.string.hint_pick_unfiltered)}, {"Esc · F9", getString(R.string.hint_close)}}), Ui.weight1());
        foot.addView(smallButton(getString(R.string.settings), toSettings), Ui.wrap());
        root.addView(foot);
        return root;
    }

    private void refilter() {
        String q = query.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Target c : all) {
            String hay = (c.label + " " + store.tagFor(c) + " " + c.pkg).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) shown.add(c);
        }
        Target tablet = tabletEntry();
        if (q.isEmpty() || (tablet.label + " tablet zte").toLowerCase(Locale.ROOT).contains(q)) shown.add(tablet);
        sel = 0;
        if (q.isEmpty()) for (int i = 0; i < shown.size(); i++) if (shown.get(i).id.equals(lastId)) sel = i;
        rebuildList();
    }

    private void rebuildList() {
        boolean filtered = query.length() > 0;
        listBox.removeAllViews();
        cells.clear();
        for (int i = 0; i < shown.size(); i += 2) {
            LinearLayout pair = new LinearLayout(this);
            pair.setOrientation(LinearLayout.HORIZONTAL);
            pair.setClipChildren(false);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                if (j < shown.size()) {
                    Cell c = new Cell(j, shown.get(j), true, isTablet(shown.get(j)) ? null : keyLabel(j, !filtered));
                    pair.addView(c.view, lp);
                    cells.add(c);
                } else {
                    pair.addView(new View(this), lp);
                }
            }
            listBox.addView(pair);
        }
        countText.setText(filtered ? getString(R.string.ov_count_filtered, targetCount()) : getString(R.string.ov_count_all, all.size()));
        emptyText.setText(getString(R.string.ov_no_match, query.getText()));
        emptyText.setVisibility(targetCount() == 0 && !all.isEmpty() ? View.VISIBLE : View.GONE);
        emptyAll.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        select(sel, false);
        stagger();
    }

    // ------------------------------------------------------------ actions

    private void select(int i, boolean animate) {
        if (cells.isEmpty()) return;
        i = Math.max(0, Math.min(cells.size() - 1, i));
        for (Cell c : cells) c.setOn(c.index == i, animate);
        sel = i;
    }

    private void choose(int i) {
        if (i < 0 || i >= shown.size()) return;
        select(i, true);
        Target target = shown.get(i);
        if (isTablet(target)) {
            exitToTablet();
            return;
        }
        if (!target.available) {
            ObjectAnimator.ofFloat(cells.get(i).view, View.TRANSLATION_X,
                    0, -dp(8), dp(8), -dp(6), dp(6), -dp(3), 0).setDuration(380).start();
            notice(unavailableMessage(target));
            return;
        }
        if (launch(target, cells.get(i).view)) finish();
    }

    private boolean launch(Target target, View from) {
        boolean entered = false; // remote mode started here, with our desktop placed underneath
        if (KeyBindings.MODE_TABLET.equals(ModeSwitcher.currentMode(this))) {
            // the moment remote mode starts: a client is being opened
            if (ProviderClient.enterRemote(this)) {
                entered = true;
                // put our remote-mode desktop underneath first, so closing the client later shows it, not the ZTE desktop
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(ModeSwitcher.CHOOSER).putExtra(ChooserActivity.EXTRA_QUIET_AT, SystemClock.elapsedRealtime())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                overridePendingTransition(0, 0);
            } else {
                toast(getString(R.string.module_inactive));
            }
        }
        Rect r = null;
        if (from != null) {
            int[] xy = new int[2];
            from.getLocationOnScreen(xy);
            r = new Rect(xy[0], xy[1], xy[0] + from.getWidth(), xy[1] + from.getHeight());
        }
        if (!store.launch(target, r)) {
            String msg = getString(R.string.ov_launch_failed_fmt, tagged(target));
            if (entered) {
                // the remote desktop went on top of us and nothing covers it: undo, back to the tablet app
                ProviderClient.exitRemote(this, true);
                toast(msg);
            } else {
                notice(msg);
            }
            return false;
        }
        if (ModeSwitcher.isZteFlipPackage(target.pkg)) {
            // ZTE's ActivityStarter rewrites default_home when its own cloud apps start; put ours back
            final Context app = getApplicationContext();
            new Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { ProviderClient.sync(app); }
            }, 1500);
        }
        return true;
    }

    /** B / the tablet entry: the ZTE desktop. In remote mode that also leaves remote mode; in tablet mode it just goes home. */
    private void exitToTablet() {
        if (!remote()) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(ModeSwitcher.MIFAVOR_PKG).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (!ProviderClient.exitRemote(this)) {
            toast(getString(R.string.module_inactive));
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(ModeSwitcher.MIFAVOR_PKG).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    private void openSettings() {
        openSettings(null, false);
    }

    private void openSettings(String tab, boolean addApp) {
        Intent i = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tab != null) i.putExtra(SettingsActivity.EXTRA_TAB, tab);
        if (addApp) i.putExtra(SettingsActivity.EXTRA_ADD_APP, true);
        startActivity(i);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean qEmpty = query == null || query.length() == 0;
        int step = variantB ? 2 : cols;
        int n = cells.size();
        switch (k) {
            case KeyEvent.KEYCODE_ESCAPE:
                if (!qEmpty) query.setText("");
                else finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: select(Math.min(n - 1, sel + 1), true); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: select(Math.max(0, sel - 1), true); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: select(Math.min(n - 1, sel + step), true); return true;
            case KeyEvent.KEYCODE_DPAD_UP: select(Math.max(0, sel - step), true); return true;
            case KeyEvent.KEYCODE_TAB:
                if (n > 0) select((sel + 1) % n, true);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                choose(sel);
                return true;
            default:
                break;
        }
        int digit = -1;
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) digit = k - KeyEvent.KEYCODE_0;
        else if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) digit = k - KeyEvent.KEYCODE_NUMPAD_0;
        if (digit >= 0 && qEmpty && !e.isAltPressed() && !e.isCtrlPressed()) {
            int i = digit == 0 ? 9 : digit - 1;
            if (i < targetCount()) choose(i);
            return true;
        }
        if (!variantB && !e.isCtrlPressed() && !e.isAltPressed()) {
            if (k == KeyEvent.KEYCODE_B) { exitToTablet(); return true; }
            if (k == KeyEvent.KEYCODE_S) { openSettings(); return true; }
        }
        if (variantB && query != null && !query.hasFocus() && e.isPrintingKey()) query.requestFocus();
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ one tile (A) or one row (B)

    private Drawable iconFor(Target target) {
        if (target.icon != null) return target.icon;
        try {
            return getPackageManager().getApplicationIcon(target.pkg); // frozen apps have no launcher icon
        } catch (Throwable e) {
            return getPackageManager().getDefaultActivityIcon();
        }
    }

    private final class Cell {
        final int index;
        final View view;
        final GradientDrawable bg;
        final TextView key;
        final float selScale;
        final float restAlpha;
        final int fillOff, fillOn, edgeOff, edgeOn, strokePx;
        int fill, edge;
        boolean on;
        ValueAnimator colorAnim;

        Cell(final int index, Target target, boolean horizontal, String keyLabel) {
            this.index = index;
            boolean off = !target.available;
            boolean tablet = isTablet(target);
            int slot = store.profileColor(target.serial);
            selScale = horizontal ? 1.015f : 1.06f;
            restAlpha = off ? 0.5f : 1f;
            fillOff = horizontal ? 0x00000000 : t.tile;
            fillOn = horizontal ? t.rowSel : t.tileSel;
            edgeOff = horizontal ? 0x00000000 : t.tileEdge;
            edgeOn = t.accent;
            strokePx = Math.max(2, dp(1.5f));
            fill = fillOff;
            edge = edgeOff;
            bg = Ui.round(fill, dp(horizontal ? 14 : 18));
            bg.setStroke(strokePx, edge);

            int icon = horizontal ? 30 : 34;
            FrameLayout iconBox = new FrameLayout(OverlayActivity.this);
            iconBox.setClipChildren(false);
            ImageView iv = new ImageView(OverlayActivity.this);
            iv.setImageDrawable(iconFor(target));
            if (off) {
                ColorMatrix m = new ColorMatrix();
                m.setSaturation(0);
                iv.setColorFilter(new ColorMatrixColorFilter(m));
            }
            iconBox.addView(iv, new FrameLayout.LayoutParams(dp(icon), dp(icon)));
            View badge = new View(OverlayActivity.this);
            badge.setBackground(Ui.oval(t.profile[slot], dp(2), t.badgeEdge));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(horizontal ? 11 : 12), dp(horizontal ? 11 : 12),
                    Gravity.BOTTOM | Gravity.END);
            blp.setMargins(0, 0, -dp(3), -dp(3));
            if (!tablet) iconBox.addView(badge, blp); // the profile dot; the tablet entry has no profile

            View chip = tablet
                    ? Ui.chip(OverlayActivity.this, getString(remote() ? R.string.ov_tablet_leaves : R.string.ov_tablet_home),
                            t.muted, t.chip, t.text2)
                    : Ui.chip(OverlayActivity.this, off ? target.reason : store.tagFor(target), t.profile[slot],
                            off ? t.errSoft : t.chip, off ? t.err : t.text);
            TextView app = Ui.text(OverlayActivity.this, target.label, horizontal ? 14 : 13, t.text, true);
            key = keyLabel == null ? null : Ui.keycap(OverlayActivity.this, keyLabel, t, horizontal ? 20 : 18);
            boolean isLast = target.id.equals(lastId);
            boolean live = !tablet && !off && isRunning(target);

            if (horizontal) {
                LinearLayout r = Ui.row(OverlayActivity.this, 10);
                r.setPadding(dp(8), 0, dp(10), 0);
                r.addView(iconBox, new LinearLayout.LayoutParams(dp(icon), dp(icon)));
                r.addView(app, Ui.wrap());
                r.addView(chip, Ui.wrap());
                r.addView(new View(OverlayActivity.this), new LinearLayout.LayoutParams(0, 1, 1f));
                if (live) r.addView(Ui.runningPill(OverlayActivity.this, t), Ui.wrap());
                if (isLast) r.addView(Ui.lastPill(OverlayActivity.this, t), Ui.wrap());
                if (key != null) r.addView(key, Ui.wrap());
                view = r;
            } else {
                LinearLayout col = new LinearLayout(OverlayActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(10), dp(10), dp(10), dp(9));
                col.setClipChildren(false);
                LinearLayout top = Ui.row(OverlayActivity.this, 4);
                top.setGravity(Gravity.TOP);
                top.setClipChildren(false);
                top.addView(iconBox, new LinearLayout.LayoutParams(dp(icon), dp(icon)));
                top.addView(new View(OverlayActivity.this), new LinearLayout.LayoutParams(0, 1, 1f));
                if (live) top.addView(Ui.runningPill(OverlayActivity.this, t), Ui.wrap());
                if (isLast) top.addView(Ui.lastPill(OverlayActivity.this, t), Ui.wrap());
                if (key != null) top.addView(key, Ui.wrap());
                col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                col.addView(new View(OverlayActivity.this), new LinearLayout.LayoutParams(1, 0, 1f));
                col.addView(app, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams cl = Ui.wrap();
                cl.topMargin = dp(4);
                col.addView(chip, cl);
                view = col;
            }
            view.setBackground(bg);
            view.setAlpha(restAlpha);
            view.setClickable(true);
            view.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { choose(index); }
            });
        }

        void setOn(boolean want, boolean animate) {
            if (want == on && animate) return;
            on = want;
            final int fromFill = fill, toFill = want ? fillOn : fillOff;
            final int fromEdge = edge, toEdge = want ? edgeOn : edgeOff;
            if (key != null) Ui.setKeycap(OverlayActivity.this, key,
                    want ? t.accent : t.key, want ? t.accent : t.keyEdge, want ? t.onAccent : t.keyText);
            float s = want ? selScale : 1f;
            float z = want ? dp(variantB ? 2 : 10) : 0;
            if (colorAnim != null) colorAnim.cancel();
            if (!animate) {
                fill = toFill;
                edge = toEdge;
                bg.setColor(fill);
                bg.setStroke(strokePx, edge);
                view.setScaleX(s);
                view.setScaleY(s);
                view.setTranslationZ(z);
                return;
            }
            colorAnim = ValueAnimator.ofFloat(0f, 1f);
            colorAnim.setDuration(180);
            colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float f = (float) a.getAnimatedValue();
                    fill = blend(fromFill, toFill, f);
                    edge = blend(fromEdge, toEdge, f);
                    bg.setColor(fill);
                    bg.setStroke(strokePx, edge);
                }
            });
            colorAnim.start();
            view.animate().scaleX(s).scaleY(s).translationZ(z).setStartDelay(0)
                    .setDuration(300).setInterpolator(Ui.pop()).start();
        }
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * f) << 24) | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bgc - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}

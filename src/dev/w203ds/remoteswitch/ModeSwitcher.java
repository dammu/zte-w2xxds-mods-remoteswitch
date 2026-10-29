package dev.w203ds.remoteswitch;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Switches between tablet mode and remote mode the same way ZTE's uSmartLauncher does:
 * preferred HOME activity + Settings.Secure.default_home. Runs inside system-uid processes
 * (uSmartLauncher / HJQBIND), so no extra permissions are needed.
 *
 * Our own mode is remembered in Settings.Secure "remoteswitch_mode", because ZTE's
 * ActivityStarter rewrites default_home to usmartlauncher whenever one of its cloud apps
 * (hjqbind, 移动云电脑, 聚合平台 ...) is started; {@link #sync} puts things back.
 */
public final class ModeSwitcher {
    public static final String TAG = "RemoteSwitch";
    public static final String SELF_PKG = "dev.w203ds.remoteswitch";
    public static final String DEFAULT_HOME_KEY = "default_home";
    public static final String MODE_KEY = "remoteswitch_mode";
    /** task id of the tablet app remote mode was entered from; -1 = the ZTE desktop */
    public static final String RETURN_TASK_KEY = "remoteswitch_return_task";
    public static final String MIFAVOR_PKG = "com.zte.mifavor.launcher";
    public static final String USMART_PKG = "com.zte.usmartlauncher";
    public static final ComponentName MIFAVOR_HOME = new ComponentName(MIFAVOR_PKG,
            "com.android.launcher3.uioverrides.QuickstepLauncher");
    /** remote-mode HOME (wallpaper backdrop) */
    public static final ComponentName CHOOSER = new ComponentName(SELF_PKG, SELF_PKG + ".ChooserActivity");
    /** the floating chooser; started directly so it floats over the current app instead of raising HOME */
    public static final ComponentName OVERLAY = new ComponentName(SELF_PKG, SELF_PKG + ".OverlayActivity");

    /** Packages whose explicit start makes ZTE's ActivityStarter flip default_home to usmartlauncher. */
    public static final String[] ZTE_FLIP_PKGS = {
            "cm.komect.aqb.android.hjqbind", "cm.komect.aqb.android.launcher",
            "cm.komect.aqb.android.cloudcomputerpad", "com.cmss.cloudcomputer.tablet",
            "com.chinaunicom.cloud.computer", "com.ctg.itrdc.clouddesk", "com.zte.mobile",
            "com.cmgame.gamehalltv.kycloud"};

    public static final String EXTRA_ACTION = "rs.action";
    public static final String EXTRA_PARAM = "rs.param";
    public static final String EXTRA_SOURCE = "rs.source";
    public static final String EXTRA_MARK = "rs.mark";

    private ModeSwitcher() {}

    public static String defaultHome(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), DEFAULT_HOME_KEY);
        } catch (Throwable t) {
            Log.w(TAG, "read default_home failed", t);
            return null;
        }
    }

    /** Our remembered mode; falls back to what default_home says when the flag was never written. */
    public static String currentMode(Context ctx) {
        try {
            String m = Settings.Secure.getString(ctx.getContentResolver(), MODE_KEY);
            if (KeyBindings.MODE_REMOTE.equals(m)) return KeyBindings.MODE_REMOTE;
            if (KeyBindings.MODE_TABLET.equals(m)) return KeyBindings.MODE_TABLET;
        } catch (Throwable ignored) {
        }
        return SELF_PKG.equals(defaultHome(ctx)) ? KeyBindings.MODE_REMOTE : KeyBindings.MODE_TABLET;
    }

    public static boolean isZteFlipPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : ZTE_FLIP_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    public static boolean enterRemote(Context ctx) {
        boolean ok = setPreferredHome(ctx, CHOOSER);
        ok &= putSecure(ctx, DEFAULT_HOME_KEY, SELF_PKG);
        putSecure(ctx, MODE_KEY, KeyBindings.MODE_REMOTE);
        Log.i(TAG, "enterRemote ok=" + ok);
        return ok;
    }

    /**
     * @param goHome show the tablet: the ZTE desktop, or with toApp the tablet app remote mode was entered
     *               from ({@link #rememberReturnTask}), falling back to the desktop when it is gone
     */
    public static boolean exitRemote(Context ctx, boolean goHome, boolean toApp) {
        boolean ok = setPreferredHome(ctx, MIFAVOR_HOME);
        ok &= putSecure(ctx, DEFAULT_HOME_KEY, MIFAVOR_PKG);
        putSecure(ctx, MODE_KEY, KeyBindings.MODE_TABLET);
        int back = goHome && toApp ? returnTask(ctx) : -1;
        if (goHome) {
            try {
                // the desktop goes over the remote clients even when the app comes back on top of it, so closing
                // that app later lands on the desktop and not on a client in tablet mode; no animation then
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (back >= 0) ctx.startActivity(home, ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle());
                else ctx.startActivity(home);
            } catch (Throwable t) {
                Log.w(TAG, "start home failed", t);
                ok = false;
            }
        }
        if (back >= 0) {
            try {
                ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).moveTaskToFront(back, 0);
            } catch (Throwable t) {
                Log.w(TAG, "move task " + back + " failed", t);
            }
        }
        Log.i(TAG, "exitRemote ok=" + ok + " goHome=" + goHome + " backToTask=" + back);
        return ok;
    }

    /** Re-applies the remembered mode if ZTE (or anything else) changed default_home behind our back. */
    public static boolean sync(Context ctx) {
        String mode = currentMode(ctx);
        String dh = defaultHome(ctx);
        if (KeyBindings.MODE_REMOTE.equals(mode)) {
            if (!SELF_PKG.equals(dh) || !CHOOSER.flattenToShortString().equals(preferredHome(ctx))) {
                Log.i(TAG, "sync: restoring remote mode (default_home was " + dh + ")");
                return enterRemote(ctx);
            }
        } else {
            if (!MIFAVOR_PKG.equals(dh) || !MIFAVOR_HOME.flattenToShortString().equals(preferredHome(ctx))) {
                Log.i(TAG, "sync: restoring tablet mode (default_home was " + dh + ")");
                return exitRemote(ctx, false, false);
            }
        }
        return true;
    }

    public static void startChooser(Context ctx, String action, String param, String source) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(OVERLAY)
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_PARAM, param == null ? "" : param)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_MARK, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "startChooser failed", t);
        }
    }

    /**
     * Entering remote mode: bring up our remote-mode HOME (wallpaper backdrop), which opens the floating
     * chooser on top. Without this the ZTE desktop stays the visible home while we are in remote mode,
     * and anything that closes (chooser, settings) lands on it.
     */
    public static void startRemoteHome(Context ctx, String action, String param, String source) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(CHOOSER)
                    .putExtra(EXTRA_ACTION, action)
                    .putExtra(EXTRA_PARAM, param == null ? "" : param)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_MARK, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "startRemoteHome failed", t);
        }
    }

    /**
     * The first n tasks, front first. The full list (other users and apps) needs the system uid of the hook
     * processes. Never call it while system_server waits on us (the short-press provider call): it calls
     * back into the task manager.
     */
    @SuppressWarnings("deprecation")
    static List<ActivityManager.RunningTaskInfo> tasks(Context ctx, int n) {
        try {
            return ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(n);
        } catch (Throwable t) {
            Log.w(TAG, "read tasks failed", t);
            return new ArrayList<>();
        }
    }

    /** True when the floating chooser is the front task. */
    public static boolean overlayInFront(Context ctx) {
        List<ActivityManager.RunningTaskInfo> t = tasks(ctx, 1);
        return !t.isEmpty() && OVERLAY.equals(t.get(0).topActivity);
    }

    /**
     * Remote mode is starting from the tablet: remember the tablet app under the chooser, so leaving remote
     * mode with F9 goes back to it instead of the ZTE desktop. Nothing is remembered for the desktop itself.
     */
    public static void rememberReturnTask(Context ctx) {
        int id = -1;
        for (ActivityManager.RunningTaskInfo t : tasks(ctx, 8)) {
            // only what is on screen under the chooser; a client left in the background is not "the tablet app"
            if (!isVisible(t)) continue;
            String pkg = t.topActivity == null ? null : t.topActivity.getPackageName();
            if (SELF_PKG.equals(pkg)) continue; // the chooser itself, our remote desktop, settings
            if (pkg != null && !MIFAVOR_PKG.equals(pkg)) id = t.taskId;
            break;
        }
        putSecure(ctx, RETURN_TASK_KEY, String.valueOf(id));
        Log.i(TAG, "return task " + id);
    }

    /** TaskInfo.isVisible is hidden; readable from the platform-signed hook processes. Unknown counts as visible. */
    private static boolean isVisible(ActivityManager.RunningTaskInfo t) {
        try {
            return android.app.TaskInfo.class.getField("isVisible").getBoolean(t);
        } catch (Throwable e) {
            Log.w(TAG, "TaskInfo.isVisible unreadable: " + e);
            return true;
        }
    }

    /** The remembered tablet app's task id, or -1 when there is none or it is gone. */
    private static int returnTask(Context ctx) {
        int id;
        try {
            id = Integer.parseInt(String.valueOf(Settings.Secure.getString(ctx.getContentResolver(), RETURN_TASK_KEY)));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (id < 0) return -1;
        for (ActivityManager.RunningTaskInfo t : tasks(ctx, 64)) if (t.taskId == id) return id;
        return -1;
    }

    /** "pkg:userId" for every task, i.e. the clients whose session is still open somewhere. */
    public static ArrayList<String> runningApps(Context ctx) {
        ArrayList<String> out = new ArrayList<>();
        for (ActivityManager.RunningTaskInfo t : tasks(ctx, 64)) {
            ComponentName c = t.baseActivity != null ? t.baseActivity : t.topActivity;
            if (c == null) continue;
            int user;
            try {
                user = android.app.TaskInfo.class.getField("userId").getInt(t); // hidden field, readable as system
            } catch (Throwable e) {
                user = 0;
            }
            out.add(c.getPackageName() + ":" + user);
        }
        return out;
    }

    public static Bundle status(Context ctx) {
        Bundle b = new Bundle();
        b.putString("mode", currentMode(ctx));
        b.putString("default_home", String.valueOf(defaultHome(ctx)));
        b.putString("preferred_home", String.valueOf(preferredHome(ctx)));
        b.putInt("version", 2);
        return b;
    }

    public static String preferredHome(Context ctx) {
        try {
            List<ResolveInfo> l = new ArrayList<>();
            PackageManager pm = ctx.getPackageManager();
            Method m = pm.getClass().getMethod("getHomeActivities", List.class);
            Object cn = m.invoke(pm, l);
            return cn == null ? null : ((ComponentName) cn).flattenToShortString();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean putSecure(Context ctx, String key, String value) {
        try {
            return Settings.Secure.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            Log.w(TAG, "write " + key + " failed", t);
            return false;
        }
    }

    /** Mirrors UsmartHelper.setDefaultLauncher: collect every HOME handler and prefer one. */
    private static boolean setPreferredHome(Context ctx, ComponentName target) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
            List<ComponentName> set = new ArrayList<>();
            boolean found = false;
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                set.add(cn);
                if (cn.equals(target)) found = true;
            }
            if (!found) set.add(target);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            Method m = pm.getClass().getMethod("replacePreferredActivity", IntentFilter.class, int.class,
                    ComponentName[].class, ComponentName.class);
            m.invoke(pm, f, IntentFilter.MATCH_CATEGORY_EMPTY, set.toArray(new ComponentName[0]), target);
            Log.i(TAG, "setPreferredHome -> " + target.flattenToShortString());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setPreferredHome failed", t);
            return false;
        }
    }
}

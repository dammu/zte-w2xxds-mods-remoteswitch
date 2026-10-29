package dev.w203ds.remoteswitch;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Two hook sites:
 *  - com.zte.usmartlauncher: DefaultHomeProvider.call("switch_mode") = short press of F9
 *    (also serves rs_* methods for the manager app, applies the boot policy in onCreate,
 *    and keeps ZTE's own mode-switch methods from fighting us while in remote mode).
 *  - cm.komect.aqb.android.hjqbind: ui.Main started by system_server (long press of F9) or by
 *    ZTE's boot flow while we are in remote mode.
 * Both processes run as uid system. Nothing here may throw: the provider's exceptions travel back
 * into system_server's PhoneWindowManager$15.run(), which has no try/catch.
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = ModeSwitcher.TAG;
    private static final String USMART_PKG = "com.zte.usmartlauncher";
    private static final String HJQ_PKG = "cm.komect.aqb.android.hjqbind";
    private static final String PROVIDER_CLASS = "com.zte.usmartlauncher.provider.DefaultHomeProvider";
    private static final String HJQ_MAIN = "cm.komect.aqb.android.hjqbind.ui.Main";
    private static final String BOOT_SEEN_KEY = "remoteswitch_boot_seen";
    private static final long REDIRECT_WINDOW_MS = 4000;

    /** ZTE's own switching methods; swallowed while we own remote mode */
    private static final String[] ZTE_SWITCH_METHODS = {
            "switch_pad", "switch_pc", "switch_mode_and_pc", "switch_mode_and_app", "switch_cm_dizuo"};
    /**
     * Called by the ZTE launcher after boot/unlock. When the 和家亲 binding is missing (or no recent
     * cloud login) it starts uSmartLauncher's cloud flow: default_home=usmartlauncher, hjqbind -> 聚合平台
     * login page, cloud mode on. Its return value is always null, so swallowing it is harmless.
     */
    private static final String[] ZTE_AUTOSTART_METHODS = {"check_connect_status"};

    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** until this uptime, activity starts made by HJQBIND itself are swallowed (redirect) */
    private static volatile long sRedirectUntil = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (USMART_PKG.equals(lp.packageName) && USMART_PKG.equals(lp.processName)) {
                hookProvider(lp);
            } else if (HJQ_PKG.equals(lp.packageName) && HJQ_PKG.equals(lp.processName)) {
                hookHjqMain(lp);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": handleLoadPackage failed in " + lp.processName + ": " + t);
        }
    }

    // ---------------------------------------------------------------- uSmartLauncher provider

    private void hookProvider(final XC_LoadPackage.LoadPackageParam lp) {
        // boot policy: runs once per boot, before the first call() that started this process
        XposedHelpers.findAndHookMethod(PROVIDER_CLASS, lp.classLoader, "onCreate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Context ctx = ((ContentProvider) param.thisObject).getContext();
                    applyBootPolicyIfNeeded(ctx);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": boot policy error: " + t);
                }
            }
        });

        XposedHelpers.findAndHookMethod(PROVIDER_CLASS, lp.classLoader, "call",
                String.class, String.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String method = (String) param.args[0];
                            ContentProvider cp = (ContentProvider) param.thisObject;
                            Context ctx = cp.getContext();
                            String caller = null;
                            try { caller = cp.getCallingPackage(); } catch (Throwable ignored) { }
                            XposedBridge.log(TAG + ": provider call " + method + " from " + caller);
                            if ("switch_mode".equals(method)) {
                                // system_server's android.ui thread is blocked in this call: decide fast,
                                // answer immediately, do the actual switching on our own thread.
                                final String action = resolveAction(ctx, KeyBindings.GESTURE_SHORT);
                                if (KeyBindings.A_VENDOR_ORIGINAL.equals(KeyBindings.actionName(action))) {
                                    return; // let ZTE handle it
                                }
                                param.setResult(ModeSwitcher.status(ctx));
                                final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                                EXEC.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            runAction(appCtx, KeyBindings.GESTURE_SHORT, action);
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + ": async action error: " + t);
                                        }
                                    }
                                });
                            } else if (method != null && method.startsWith("rs_")) {
                                param.setResult(serve(ctx, method, (Bundle) param.args[2], caller));
                            } else if (isIn(ZTE_AUTOSTART_METHODS, method)) {
                                XposedBridge.log(TAG + ": swallowed ZTE " + method + " (vendor auto-start)");
                                param.setResult(new Bundle());
                            } else if (isZteSwitchMethod(method)
                                    && KeyBindings.MODE_REMOTE.equals(ModeSwitcher.currentMode(ctx))) {
                                // ZTE (SystemUI tile / launcher) trying to switch while we own remote mode
                                XposedBridge.log(TAG + ": swallowed ZTE " + method + " in remote mode");
                                param.setResult(new Bundle());
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": provider hook error: " + t);
                            Log.w(TAG, "provider hook error", t);
                            param.setResult(new Bundle()); // never propagate
                        }
                    }
                });
        XposedBridge.log(TAG + ": hooked " + PROVIDER_CLASS + ".call/onCreate");
    }

    private static boolean isZteSwitchMethod(String m) {
        return isIn(ZTE_SWITCH_METHODS, m);
    }

    private static boolean isIn(String[] list, String m) {
        if (m == null) return false;
        for (String s : list) if (s.equals(m)) return true;
        return false;
    }

    /** First provider start after a reboot: apply the configured boot policy. */
    private void applyBootPolicyIfNeeded(Context ctx) {
        int bootCount = Settings.Global.getInt(ctx.getContentResolver(), "boot_count", -1);
        int seen = Settings.Secure.getInt(ctx.getContentResolver(), BOOT_SEEN_KEY, -2);
        if (bootCount < 0 || bootCount == seen) return;
        Settings.Secure.putInt(ctx.getContentResolver(), BOOT_SEEN_KEY, bootCount);
        String policy = KeyBindings.bootPolicy(loadBindings(ctx));
        String mode = ModeSwitcher.currentMode(ctx);
        XposedBridge.log(TAG + ": boot #" + bootCount + " policy=" + policy + " previous mode=" + mode);
        if (KeyBindings.BOOT_KEEP.equals(policy)) {
            if (KeyBindings.MODE_REMOTE.equals(mode)) {
                ModeSwitcher.enterRemote(ctx);
                ModeSwitcher.startRemoteHome(ctx, KeyBindings.A_SHOW_CHOOSER, "", "boot");
            }
        } else {
            if (KeyBindings.MODE_REMOTE.equals(mode)) {
                ModeSwitcher.exitRemote(ctx, false, false);
            }
        }
    }

    private Bundle serve(Context ctx, String method, Bundle extras, String caller) {
        Bundle out = new Bundle();
        switch (method) {
            case "rs_ping":
                out.putInt("version", 5); // 5: rs_running, rs_exit_remote to_app
                out.putBoolean("ok", true);
                return out;
            case "rs_status":
                return ModeSwitcher.status(ctx);
            case "rs_enter_remote":
                if (KeyBindings.MODE_TABLET.equals(ModeSwitcher.currentMode(ctx))) ModeSwitcher.rememberReturnTask(ctx);
                out.putBoolean("ok", ModeSwitcher.enterRemote(ctx));
                return out;
            case "rs_exit_remote":
                // to_app: back to the tablet app remote mode was entered from; otherwise the ZTE desktop
                out.putBoolean("ok", ModeSwitcher.exitRemote(ctx, true, extras != null && extras.getBoolean("to_app", false)));
                return out;
            case "rs_running":
                out.putStringArrayList("running", ModeSwitcher.runningApps(ctx));
                out.putBoolean("ok", true);
                return out;
            case "rs_sync":
                out.putBoolean("ok", ModeSwitcher.sync(ctx));
                return out;
            case "rs_bindings": {
                Bundle b = loadBindings(ctx);
                return b == null ? new Bundle() : b;
            }
            case "rs_set_bindings":
                // the app can't write Settings.Secure itself; we run as uid system
                if (!ModeSwitcher.SELF_PKG.equals(caller)) {
                    out.putBoolean("ok", false);
                    out.putString("error", "caller " + caller + " not allowed");
                    return out;
                }
                out.putBoolean("ok", ModeSwitcher.putSecure(ctx, KeyBindings.SETTINGS_KEY, KeyBindings.encode(extras)));
                return out;
            default:
                out.putBoolean("ok", false);
                out.putString("error", "unknown method " + method);
                return out;
        }
    }

    // ---------------------------------------------------------------- HJQBIND

    private void hookHjqMain(final XC_LoadPackage.LoadPackageParam lp) {
        XposedHelpers.findAndHookMethod(HJQ_MAIN, lp.classLoader, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity a = (Activity) param.thisObject;
                            Intent in = a.getIntent();
                            if (in == null || in.hasExtra(ModeSwitcher.EXTRA_MARK)) return;
                            Context ctx = a.getApplicationContext();
                            // system_server builds the long-press intent with setClassName only (no action);
                            // ZTE's boot/unlock flow starts Main with ACTION_MAIN, which we also take over
                            // while we own remote mode.
                            boolean fromKey = in.getAction() == null;
                            boolean ours = KeyBindings.MODE_REMOTE.equals(ModeSwitcher.currentMode(ctx));
                            if (!fromKey && !ours) return;
                            boolean handled;
                            if (fromKey) {
                                handled = onSwitchKey(ctx, KeyBindings.GESTURE_LONG);
                            } else {
                                XposedBridge.log(TAG + ": hjqbind started by vendor flow in remote mode -> chooser");
                                ModeSwitcher.startChooser(ctx, KeyBindings.A_SHOW_CHOOSER, "", "vendor");
                                handled = true;
                            }
                            if (!handled) return; // vendor_original
                            sRedirectUntil = SystemClock.uptimeMillis() + REDIRECT_WINDOW_MS;
                            ModeSwitcher.sync(ctx); // undo ZTE's default_home flip according to the resulting mode
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": hjq onCreate hook error: " + t);
                            sRedirectUntil = 0;
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (SystemClock.uptimeMillis() >= sRedirectUntil) return;
                        try {
                            ((Activity) param.thisObject).finish();
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": hjq finish error: " + t);
                        }
                    }
                });

        // While redirecting, HJQBIND starts the 家庭办公聚合平台 login page from a plain Context
        // (SettingUtils.startCloudPC). Every Context/Activity start funnels through
        // Instrumentation.execStartActivity, so swallow there.
        XC_MethodHook swallow = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (SystemClock.uptimeMillis() >= sRedirectUntil) return;
                    Intent i = null;
                    for (Object o : param.args) {
                        if (o instanceof Intent) { i = (Intent) o; break; }
                    }
                    if (i != null && i.hasExtra(ModeSwitcher.EXTRA_MARK)) return;
                    Log.i(TAG, "swallowed activity start during redirect: " + i);
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": swallow hook error: " + t);
                }
            }
        };
        int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", swallow).size();
        XposedBridge.log(TAG + ": hooked " + HJQ_MAIN + ".onCreate, execStartActivity x" + n);
    }

    // ---------------------------------------------------------------- action dispatch

    /** Reads mode + bindings (fast, local) and logs the decision. */
    private String resolveAction(Context ctx, String gesture) {
        String mode = ModeSwitcher.currentMode(ctx);
        String action = KeyBindings.actionFor(loadBindings(ctx), gesture, mode);
        Log.i(TAG, "key " + gesture + " in " + mode + " -> " + action);
        XposedBridge.log(TAG + ": key " + gesture + " in " + mode + " -> " + action);
        return action;
    }

    /** @return false when the vendor logic should run instead */
    private boolean onSwitchKey(Context ctx, String gesture) {
        return runAction(ctx, gesture, resolveAction(ctx, gesture));
    }

    private boolean runAction(Context ctx, String gesture, String action) {
        String name = KeyBindings.actionName(action);
        String param = KeyBindings.actionParam(action);
        if (!KeyBindings.A_VENDOR_ORIGINAL.equals(name) && !KeyBindings.A_NONE.equals(name)
                && ModeSwitcher.overlayInFront(ctx)) {
            // the floating UI is up: F9 closes it, whatever the binding says (F9 toggles it)
            XposedBridge.log(TAG + ": " + gesture + " with the chooser up -> close it");
            ModeSwitcher.startChooser(ctx, KeyBindings.A_CLOSE_CHOOSER, "", gesture);
            return true;
        }
        switch (name) {
            case KeyBindings.A_EXIT_TO_TABLET: {
                // back to the tablet app remote mode was entered from, not just the desktop
                boolean remote = KeyBindings.MODE_REMOTE.equals(ModeSwitcher.currentMode(ctx));
                ModeSwitcher.exitRemote(ctx, true, remote);
                return true;
            }
            // Only show the floating UI over whatever is on screen. Remote mode starts when a client is
            // actually opened (OverlayActivity.launch), so Esc / settings leave the user where they were.
            case KeyBindings.A_SHOW_CHOOSER:
            case KeyBindings.A_SWITCH_LAST:
            case KeyBindings.A_CYCLE_NEXT:
            case KeyBindings.A_LAUNCH_TARGET:
                ModeSwitcher.startChooser(ctx, name, param, gesture);
                return true;
            case KeyBindings.A_VENDOR_ORIGINAL:
                return false;
            case KeyBindings.A_NONE:
            default:
                return true;
        }
    }

    /**
     * Bindings from Settings.Secure (same source as the mode flag; never an IPC into another app,
     * because system_server may be waiting on us). null = built-in defaults.
     */
    private static Bundle loadBindings(Context ctx) {
        try {
            return KeyBindings.decode(Settings.Secure.getString(ctx.getContentResolver(), KeyBindings.SETTINGS_KEY));
        } catch (Throwable t) {
            Log.w(TAG, "read " + KeyBindings.SETTINGS_KEY + " failed: " + t);
            return null;
        }
    }
}

package dev.w203ds.remoteswitch;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Talks to the hooked uSmartLauncher provider (rs_* methods served by Hook). */
public final class ProviderClient {
    private static final Uri URI = Uri.parse("content://com.zte.usmartlauncher.defaulthome");

    private ProviderClient() {}

    public static Bundle call(Context ctx, String method) {
        return call(ctx, method, null);
    }

    public static Bundle call(Context ctx, String method, Bundle extras) {
        try {
            return ctx.getContentResolver().call(URI, method, null, extras);
        } catch (Throwable t) {
            Log.w(ModeSwitcher.TAG, "provider call " + method + " failed: " + t);
            return null;
        }
    }

    /** true when the hook answers rs_ping */
    public static boolean moduleActive(Context ctx) {
        Bundle b = call(ctx, "rs_ping");
        return b != null && b.getBoolean("ok", false);
    }

    public static boolean enterRemote(Context ctx) {
        Bundle b = call(ctx, "rs_enter_remote");
        return b != null && b.getBoolean("ok", false);
    }

    /** back to tablet mode and the ZTE desktop */
    public static boolean exitRemote(Context ctx) {
        return exitRemote(ctx, false);
    }

    /** @param toApp go back to the tablet app remote mode was entered from (the desktop if it is gone) */
    public static boolean exitRemote(Context ctx, boolean toApp) {
        Bundle in = new Bundle();
        in.putBoolean("to_app", toApp);
        Bundle b = call(ctx, "rs_exit_remote", in);
        return b != null && b.getBoolean("ok", false);
    }

    /** "pkg:userId" of every app with an open task (only the hook can see other users' tasks); empty if unknown */
    public static java.util.Set<String> running(Context ctx) {
        Bundle b = call(ctx, "rs_running");
        java.util.ArrayList<String> l = b == null ? null : b.getStringArrayList("running");
        return l == null ? new java.util.HashSet<String>() : new java.util.HashSet<>(l);
    }

    /** hands the key-binding table to the hook, which stores it in Settings.Secure */
    public static boolean setBindings(Context ctx, Bundle bindings) {
        Bundle b = call(ctx, "rs_set_bindings", bindings);
        return b != null && b.getBoolean("ok", false);
    }

    /** re-applies the remembered mode (ZTE flips default_home when its cloud apps start) */
    public static boolean sync(Context ctx) {
        Bundle b = call(ctx, "rs_sync");
        return b != null && b.getBoolean("ok", false);
    }
}

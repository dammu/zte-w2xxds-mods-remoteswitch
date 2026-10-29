package dev.w203ds.remoteswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key-binding model: (gesture, mode) -> action string ("name" or "name:param").
 * The app edits its private prefs and pushes the table to the hook (rs_set_bindings), which
 * stores it in Settings.Secure {@link #SETTINGS_KEY}; the hooks read only that setting.
 */
public final class KeyBindings {
    public static final String PREFS_NAME = "keybindings";
    /** Settings.Secure key holding the table for the hooks, encoded "key=value;key=value" */
    public static final String SETTINGS_KEY = "remoteswitch_bindings";

    public static final String GESTURE_SHORT = "short";
    public static final String GESTURE_LONG = "long";
    public static final String MODE_TABLET = "tablet";
    public static final String MODE_REMOTE = "remote";

    /** old names, same behaviour as show_chooser / switch_last (opening a client always enters remote mode) */
    static final String A_ENTER_REMOTE_CHOOSER = "enter_remote_chooser";
    static final String A_ENTER_REMOTE_LAST = "enter_remote_last";
    public static final String A_EXIT_TO_TABLET = "exit_to_tablet";
    public static final String A_SHOW_CHOOSER = "show_chooser";
    public static final String A_SWITCH_LAST = "switch_last";
    public static final String A_CYCLE_NEXT = "cycle_next";
    public static final String A_LAUNCH_TARGET = "launch_target";
    public static final String A_VENDOR_ORIGINAL = "vendor_original";
    public static final String A_NONE = "none";
    /** internal, never bound: F9 pressed while the chooser is up closes it */
    public static final String A_CLOSE_CHOOSER = "close_chooser";

    /** boot policy: what mode to apply right after a reboot */
    public static final String BOOT_KEY = "boot_policy";
    public static final String BOOT_TABLET = "tablet";   // always come up in tablet mode (default)
    public static final String BOOT_KEEP = "keep";       // restore the mode from before the reboot

    public static final String[] KEYS = {
            key(GESTURE_SHORT, MODE_TABLET), key(GESTURE_SHORT, MODE_REMOTE),
            key(GESTURE_LONG, MODE_TABLET), key(GESTURE_LONG, MODE_REMOTE), BOOT_KEY};

    private KeyBindings() {}

    public static String key(String gesture, String mode) {
        return gesture + "_" + mode;
    }

    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key(GESTURE_SHORT, MODE_TABLET), A_SHOW_CHOOSER);
        m.put(key(GESTURE_SHORT, MODE_REMOTE), A_EXIT_TO_TABLET);
        m.put(key(GESTURE_LONG, MODE_TABLET), A_SHOW_CHOOSER);
        m.put(key(GESTURE_LONG, MODE_REMOTE), A_SHOW_CHOOSER);
        m.put(BOOT_KEY, BOOT_TABLET);
        return m;
    }

    public static String bootPolicy(Bundle b) {
        String v = b == null ? null : b.getString(BOOT_KEY);
        return BOOT_KEEP.equals(v) ? BOOT_KEEP : BOOT_TABLET;
    }

    /** The app's own (private) copy of the table. */
    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Only the known keys, empty values dropped. */
    public static Bundle toBundle(SharedPreferences prefs) {
        Bundle b = new Bundle();
        for (String k : KEYS) {
            String v = prefs.getString(k, null);
            if (v != null && !v.trim().isEmpty()) b.putString(k, v.trim());
        }
        return b;
    }

    /** Bundle -> setting value; only known keys, so a caller can't smuggle anything else in. */
    public static String encode(Bundle b) {
        StringBuilder sb = new StringBuilder();
        if (b == null) return "";
        for (String k : KEYS) {
            String v = b.getString(k);
            if (v == null || v.trim().isEmpty() || v.indexOf(';') >= 0) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(k).append('=').append(v.trim());
        }
        return sb.toString();
    }

    /** setting value -> Bundle; null when the setting was never written (= built-in defaults). */
    public static Bundle decode(String s) {
        if (s == null || s.isEmpty()) return null;
        Bundle b = new Bundle();
        for (String part : s.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) b.putString(part.substring(0, i), part.substring(i + 1));
        }
        return b;
    }

    /** Fills in missing keys with the defaults. */
    public static void ensureDefaults(SharedPreferences prefs) {
        if (prefs == null) return;
        try {
            SharedPreferences.Editor e = prefs.edit();
            boolean dirty = false;
            for (Map.Entry<String, String> en : defaults().entrySet()) {
                if (!prefs.contains(en.getKey())) {
                    e.putString(en.getKey(), en.getValue());
                    dirty = true;
                }
            }
            if (dirty) e.commit();
        } catch (Throwable ignored) {
        }
    }

    public static String defaultFor(String gesture, String mode) {
        return defaults().get(key(gesture, mode));
    }

    public static String bootPolicy(SharedPreferences prefs) {
        try {
            String v = prefs == null ? null : prefs.getString(BOOT_KEY, null);
            return BOOT_KEEP.equals(v) ? BOOT_KEEP : BOOT_TABLET;
        } catch (Throwable t) {
            return BOOT_TABLET;
        }
    }

    /** Reads one binding from prefs, falling back to the default. */
    public static String actionFor(SharedPreferences prefs, String gesture, String mode) {
        String def = defaultFor(gesture, mode);
        if (prefs == null) return def;
        try {
            String v = prefs.getString(key(gesture, mode), null);
            return (v == null || v.trim().isEmpty()) ? def : normalize(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static String actionFor(Bundle b, String gesture, String mode) {
        String def = defaultFor(gesture, mode);
        if (b == null) return def;
        String v = b.getString(key(gesture, mode));
        return (v == null || v.trim().isEmpty()) ? def : normalize(v.trim());
    }

    /** Maps the old enter_remote_* names onto the actions that do the same thing. */
    static String normalize(String action) {
        if (A_ENTER_REMOTE_CHOOSER.equals(action)) return A_SHOW_CHOOSER;
        if (A_ENTER_REMOTE_LAST.equals(action)) return A_SWITCH_LAST;
        return action;
    }

    /** The F9 presets: the four cells each one sets. */
    public static final String PRESET_CHOOSER = "chooser";   // default: F9 in tablet mode picks a target
    public static final String PRESET_LAST = "last";         // short F9 in tablet mode goes straight back to the last target

    public static Map<String, String> preset(String name) {
        Map<String, String> m = defaults();
        m.remove(BOOT_KEY);
        if (PRESET_LAST.equals(name)) m.put(key(GESTURE_SHORT, MODE_TABLET), A_SWITCH_LAST);
        return m;
    }

    /** The preset the four cells match, or null when they were set one by one. */
    public static String presetOf(SharedPreferences prefs) {
        for (String p : new String[] {PRESET_CHOOSER, PRESET_LAST}) {
            boolean same = true;
            for (Map.Entry<String, String> e : preset(p).entrySet()) {
                String[] gm = e.getKey().split("_");
                if (!e.getValue().equals(actionFor(prefs, gm[0], gm[1]))) same = false;
            }
            if (same) return p;
        }
        return null;
    }

    public static String actionName(String action) {
        int i = action.indexOf(':');
        return i < 0 ? action : action.substring(0, i);
    }

    public static String actionParam(String action) {
        int i = action.indexOf(':');
        return i < 0 ? "" : action.substring(i + 1);
    }
}

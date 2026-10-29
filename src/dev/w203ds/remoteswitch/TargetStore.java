package dev.w203ds.remoteswitch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists the target list (targets.json) and launches targets through LauncherApps. */
public final class TargetStore {
    private static final String TAG = ModeSwitcher.TAG;
    private static final String FILE = "targets.json";

    /**
     * Discovery list: every one of these found in any profile is preset automatically
     * (first start and "重新扫描"). Unknown / absent packages are simply skipped.
     */
    static final List<String> KNOWN_REMOTE_PKGS = Arrays.asList(
            // 远程桌面
            "com.netease.uuremote",                 // 网易UU远程
            "com.microsoft.rdc.androidx",           // Windows App
            "com.microsoft.rdc.android",            // Remote Desktop (旧版 RD Client)
            "com.carriez.flutter_hbb",              // RustDesk
            "com.anydesk.anydeskandroid",           // AnyDesk
            "com.teamviewer.teamviewer.market.mobile", // TeamViewer
            "com.google.chromeremotedesktop",       // Chrome 远程桌面
            "com.youqu.todesk.mobile",              // ToDesk
            "com.oray.sunlogin",                    // 向日葵
            "com.splashtop.remote.pad.v2",          // Splashtop Personal
            "com.splashtop.remote.business",        // Splashtop Business
            "com.realvnc.viewer.android",           // VNC Viewer
            "com.p5sys.android.jump",               // Jump Desktop
            "com.nomachine.nxplayer",               // NoMachine
            // 串流
            "com.parsec.app",                       // Parsec
            "com.limelight",                        // Moonlight
            "com.limelight.noir",                   // Artemis (Moonlight 分支)
            "com.valvesoftware.steamlink",          // Steam Link
            "com.nvidia.geforcenow",                // GeForce NOW
            "com.playstation.remoteplay",           // PS Remote Play
            "com.microsoft.xboxone.smartglass",     // Xbox
            // 云电脑
            "cm.komect.aqb.android.cloudcomputerpad", // 移动云电脑（原厂）
            "com.ctyun.cloudpc",                    // 天翼云电脑
            "com.alibaba.wuying",                   // 无影
            "com.huawei.cloudpc");                  // 华为云电脑

    /** Colour slots into Ui.Theme.profile: 0 gray = owner, 6 green = app clone, the rest cycle for other profiles. */
    static final int COLOR_SLOTS = 8;
    private static final int COLOR_OWNER = 0, COLOR_CLONE = 6;
    private static final int[] COLOR_OTHERS = {1, 2, 3, 4, 5, 7};
    /** ZTE (like several OEMs) runs its app-clone space ("应用分身") as user 999 */
    private static final int CLONE_USER_ID = 999;

    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final List<Target> targets = new ArrayList<>();
    private final Map<Long, String> profileNames = new HashMap<>();   // only user-set names
    private final Map<Long, Integer> profileColors = new HashMap<>(); // only user-set colours
    private String lastId = "";

    public TargetStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.la = (LauncherApps) this.ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        this.um = (UserManager) this.ctx.getSystemService(Context.USER_SERVICE);
        load();
    }

    // ------------------------------------------------------------ persistence

    private File file() {
        return new File(ctx.getFilesDir(), FILE);
    }

    private void load() {
        targets.clear();
        File f = file();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            JSONObject root = new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("targets");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) targets.add(Target.fromJson(arr.getJSONObject(i)));
            }
            lastId = root.optString("last", "");
            JSONObject profiles = root.optJSONObject("profiles");
            if (profiles != null) {
                for (java.util.Iterator<String> it = profiles.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject p = profiles.getJSONObject(k);
                    long serial = Long.parseLong(k);
                    String name = p.optString("name", "");
                    if (!name.isEmpty()) profileNames.put(serial, name);
                    if (p.has("color")) profileColors.put(serial, p.optInt("color"));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "load targets failed", t);
        }
        sortByOrder();
    }

    public void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Target t : targets) arr.put(t.toJson());
            root.put("targets", arr);
            root.put("last", lastId);
            JSONObject profiles = new JSONObject();
            Set<Long> serials = new HashSet<>(profileNames.keySet());
            serials.addAll(profileColors.keySet());
            for (Long s : serials) {
                JSONObject p = new JSONObject();
                if (profileNames.containsKey(s)) p.put("name", profileNames.get(s));
                if (profileColors.containsKey(s)) p.put("color", profileColors.get(s));
                profiles.put(String.valueOf(s), p);
            }
            root.put("profiles", profiles);
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.w(TAG, "save targets failed", t);
        }
    }

    private void sortByOrder() {
        Collections.sort(targets, new Comparator<Target>() {
            @Override
            public int compare(Target a, Target b) {
                return Integer.compare(a.order, b.order);
            }
        });
        for (int i = 0; i < targets.size(); i++) targets.get(i).order = i;
    }

    // ------------------------------------------------------------ scanning

    public UserHandle userForSerial(long serial) {
        for (UserHandle u : um.getUserProfiles()) {
            if (um.getSerialNumberForUser(u) == serial) return u;
        }
        return null;
    }

    /** Adds every known remote package found in any profile; keeps existing entries and notes. */
    public int scanKnown() {
        return addFromScan(KNOWN_REMOTE_PKGS);
    }

    public int addFromScan(List<String> packages) {
        Set<String> have = new HashSet<>();
        for (Target t : targets) have.add(t.id);
        int added = 0;
        for (UserHandle u : um.getUserProfiles()) {
            long serial = um.getSerialNumberForUser(u);
            for (String pkg : packages) {
                List<LauncherActivityInfo> list;
                try {
                    list = la.getActivityList(pkg, u);
                } catch (Throwable t) {
                    continue;
                }
                if (list == null || list.isEmpty()) continue;
                LauncherActivityInfo lai = list.get(0);
                String cls = lai.getComponentName().getClassName();
                String id = Target.makeId(pkg, cls, serial);
                if (have.contains(id)) continue;
                Target t = new Target();
                t.id = id;
                t.pkg = pkg;
                t.cls = cls;
                t.serial = serial;
                t.label = String.valueOf(lai.getLabel());
                t.note = ""; // follow the profile name
                t.order = targets.size();
                targets.add(t);
                have.add(id);
                added++;
            }
        }
        if (added > 0) save();
        return added;
    }

    /** All launcher activities across profiles, for the "add app" picker. */
    public List<LauncherActivityInfo> allActivities() {
        List<LauncherActivityInfo> all = new ArrayList<>();
        for (UserHandle u : um.getUserProfiles()) {
            try {
                all.addAll(la.getActivityList(null, u));
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(all, new Comparator<LauncherActivityInfo>() {
            @Override
            public int compare(LauncherActivityInfo a, LauncherActivityInfo b) {
                return String.valueOf(a.getLabel()).compareToIgnoreCase(String.valueOf(b.getLabel()));
            }
        });
        return all;
    }

    public Target add(LauncherActivityInfo lai) {
        long serial = um.getSerialNumberForUser(lai.getUser());
        String pkg = lai.getComponentName().getPackageName();
        String cls = lai.getComponentName().getClassName();
        String id = Target.makeId(pkg, cls, serial);
        for (Target t : targets) if (t.id.equals(id)) return t;
        Target t = new Target();
        t.id = id;
        t.pkg = pkg;
        t.cls = cls;
        t.serial = serial;
        t.label = String.valueOf(lai.getLabel());
        t.note = ""; // follow the profile name
        t.order = targets.size();
        targets.add(t);
        save();
        return t;
    }

    // ------------------------------------------------------------ profiles

    public int userIdFor(long serial) {
        return userIdOf(userForSerial(serial));
    }

    /** Android user id; only exposed through UserHandle's hidden getIdentifier / toString. */
    static int userIdOf(UserHandle h) {
        if (h == null) return -1;
        try {
            return (Integer) UserHandle.class.getMethod("getIdentifier").invoke(h);
        } catch (Throwable e) {
            String s = h.toString(); // UserHandle{11}
            try {
                return Integer.parseInt(s.substring(s.indexOf('{') + 1, s.indexOf('}')));
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }

    private boolean isClone(long serial) {
        return serial != 0 && userIdFor(serial) == CLONE_USER_ID;
    }

    /** 1-based position among the profiles that are neither the owner nor the app clone; 0 for those two */
    private int otherIndex(long serial) {
        int n = 0;
        for (long s : profileSerials()) {
            if (s == 0 || isClone(s)) continue;
            n++;
            if (s == serial) return n;
        }
        return 0;
    }

    /** Owner / App clone / Profile N (localized); the user renames them in settings. */
    public String defaultName(long serial) {
        if (serial == 0) return ctx.getString(R.string.profile_owner);
        if (isClone(serial)) return ctx.getString(R.string.profile_clone);
        int n = otherIndex(serial);
        return n > 0 ? ctx.getString(R.string.profile_other_fmt, n) : "u" + serial;
    }

    public String profileName(long serial) {
        String n = profileNames.get(serial);
        return n != null ? n : defaultName(serial);
    }

    /** colour slot 0..COLOR_SLOTS-1, see Ui.Theme.profile */
    public int profileColor(long serial) {
        Integer c = profileColors.get(serial);
        if (c != null && c >= 0 && c < COLOR_SLOTS) return c;
        if (serial == 0) return COLOR_OWNER;
        if (isClone(serial)) return COLOR_CLONE;
        int n = otherIndex(serial);
        return COLOR_OTHERS[(Math.max(n, 1) - 1) % COLOR_OTHERS.length];
    }

    /** empty name = back to the default */
    public void setProfileName(long serial, String name) {
        if (name == null || name.trim().isEmpty()) profileNames.remove(serial);
        else profileNames.put(serial, name.trim());
        save();
    }

    public void setProfileColor(long serial, int slot) {
        profileColors.put(serial, slot);
        save();
    }

    /** The tag a target shows: its own note, else its profile's name. */
    public String tagFor(Target t) {
        String n = t.displayNote();
        return n.isEmpty() ? profileName(t.serial) : n;
    }

    /** Serials of the profiles on this device: owner first, then the others by serial, the app clone last. */
    public List<Long> profileSerials() {
        List<Long> out = new ArrayList<>();
        Long clone = null;
        for (UserHandle u : um.getUserProfiles()) {
            long s = um.getSerialNumberForUser(u);
            if (s != 0 && userIdOf(u) == CLONE_USER_ID) clone = s;
            else out.add(s);
        }
        Collections.sort(out);
        if (clone != null) out.add(clone);
        return out;
    }

    public long serialFor(UserHandle u) {
        return um.getSerialNumberForUser(u);
    }

    /** Refreshes availability + icon for every target. */
    public void refresh() {
        for (Target t : targets) {
            t.available = false;
            t.reasonRes = 0;
            t.reason = null;
            t.icon = null;
            UserHandle u = userForSerial(t.serial);
            if (u == null) {
                setReason(t, R.string.reason_profile_deleted);
                continue;
            }
            try {
                for (LauncherActivityInfo lai : la.getActivityList(t.pkg, u)) {
                    if (lai.getComponentName().getClassName().equals(t.cls)) {
                        t.icon = lai.getIcon(0); // the chooser draws its own profile badge
                        t.label = String.valueOf(lai.getLabel());
                        if ((lai.getApplicationInfo().flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
                            setReason(t, R.string.reason_suspended); // e.g. Hail's suspend mode: the launcher greys it
                        } else {
                            t.available = true;
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!t.available && t.reasonRes == 0) setReason(t, whyMissing(t, u));
        }
    }

    private void setReason(Target t, int res) {
        t.reasonRes = res;
        t.reason = ctx.getString(res);
    }

    /** A target with no launcher activity: profile off, app frozen / hidden / uninstalled, or entry renamed. */
    private int whyMissing(Target t, UserHandle u) {
        try {
            if (um.isQuietModeEnabled(u)) return R.string.reason_profile_paused;
            if (!um.isUserUnlocked(u)) return R.string.reason_profile_stopped;
        } catch (Throwable ignored) {
        }
        try {
            ApplicationInfo ai = la.getApplicationInfo(t.pkg, PackageManager.MATCH_DISABLED_COMPONENTS, u);
            if (!ai.enabled) return R.string.reason_frozen;
            return R.string.reason_entry_changed; // the app was updated and renamed its launcher activity
        } catch (PackageManager.NameNotFoundException e) {
            try {
                la.getApplicationInfo(t.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, u);
                return R.string.reason_hidden; // pm hide (another freeze mode) looks like uninstalled without this flag
            } catch (Throwable ignored) {
                return R.string.reason_uninstalled;
            }
        } catch (Throwable e) {
            return R.string.reason_unavailable;
        }
    }

    // ------------------------------------------------------------ queries

    public List<Target> all() {
        return targets;
    }

    public List<Target> visible() {
        List<Target> v = new ArrayList<>();
        for (Target t : targets) if (!t.hidden) v.add(t);
        return v;
    }

    public Target byId(String id) {
        for (Target t : targets) if (t.id.equals(id)) return t;
        return null;
    }

    public String lastId() {
        return lastId;
    }

    public void remove(Target t) {
        targets.remove(t);
        sortByOrder();
        save();
    }

    public void move(Target t, int delta) {
        int i = targets.indexOf(t);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= targets.size()) return;
        Collections.swap(targets, i, j);
        for (int k = 0; k < targets.size(); k++) targets.get(k).order = k;
        save();
    }

    // ------------------------------------------------------------ launching

    public boolean launch(Target t, Rect bounds) {
        UserHandle u = userForSerial(t.serial);
        if (u == null) {
            Log.w(TAG, "no user for serial " + t.serial);
            return false;
        }
        try {
            la.startMainActivity(t.component(), u, bounds, null);
            lastId = t.id;
            save();
            Log.i(TAG, "launched " + t.id);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "launch failed " + t.id, e);
            return false;
        }
    }
}

package dev.w203ds.remoteswitch;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

import org.json.JSONException;
import org.json.JSONObject;

/** One launchable remote client: a launcher activity in a given user/profile. */
public final class Target {
    public String id;        // pkg/cls@serial
    public String pkg;
    public String cls;
    public long serial;      // user serial number
    public String label;     // app label at scan time
    public String note;      // user note; overrides the profile name as the tag
    public boolean hidden;
    public int order;

    // runtime only
    public boolean available;
    public int reasonRes;    // why not available (R.string.reason_*); 0 when available
    public String reason;    // the same, localized for display
    public Drawable icon;

    public static String makeId(String pkg, String cls, long serial) {
        return pkg + "/" + cls + "@" + serial;
    }

    public ComponentName component() {
        return new ComponentName(pkg, cls);
    }

    public String displayNote() {
        return note == null ? "" : note;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("pkg", pkg).put("cls", cls).put("serial", serial)
                .put("label", label).put("note", note == null ? "" : note)
                .put("hidden", hidden).put("order", order);
        return o;
    }

    public static Target fromJson(JSONObject o) {
        Target t = new Target();
        t.id = o.optString("id");
        t.pkg = o.optString("pkg");
        t.cls = o.optString("cls");
        t.serial = o.optLong("serial", 0);
        t.label = o.optString("label");
        t.note = o.optString("note", "");
        t.hidden = o.optBoolean("hidden", false);
        t.order = o.optInt("order", 0);
        return t;
    }
}

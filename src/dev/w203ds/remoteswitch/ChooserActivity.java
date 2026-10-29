package dev.w203ds.remoteswitch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The remote-mode HOME: just the wallpaper, a stage for the floating {@link OverlayActivity}. It is never a
 * place to rest: whenever it becomes visible in remote mode (launched as HOME, the last client on top of it
 * closed, back from settings, screen back on) it opens the chooser, and when that chooser is closed with
 * us still underneath (Esc, F9, tap outside) remote mode has nothing left to show, so we leave for the tablet.
 */
public class ChooserActivity extends Activity {
    /**
     * elapsedRealtime of a start that places us underneath a client being opened: don't pop the floating UI.
     * The client goes on top before we resume, so the system holds this intent (or creates us from it) until
     * the client closes; delivered that late it no longer applies, hence the stamp instead of a flag.
     */
    static final String EXTRA_QUIET_AT = "rs.quiet_at";
    private static final long QUIET_MS = 1000;

    private static final int IDLE = 0, OPEN = 1, LEAVE = 2;
    /** what the next onResume does */
    private int next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlay(null); }
        });
        setContentView(root);
        next = quiet(getIntent()) ? IDLE : OPEN;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // a stale quiet start arrives right after onRestart when the client closed: keep what onRestart decided
        if (quiet(intent)) next = IDLE;
        else if (!intent.hasExtra(EXTRA_QUIET_AT)) next = OPEN;
    }

    private static boolean quiet(Intent in) {
        long at = in.getLongExtra(EXTRA_QUIET_AT, 0);
        return at > 0 && SystemClock.elapsedRealtime() - at < QUIET_MS;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // something opaque on top went away (a client, settings, the screen being off); the overlay is
        // translucent and only pauses us, so closing it never gets here
        next = remote() ? OPEN : LEAVE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        int what = next;
        next = LEAVE; // resumed again without a restart = the chooser above us was closed
        if (what == OPEN) openOverlay(getIntent());
        else if (what == LEAVE) leave();
    }

    private boolean remote() {
        return KeyBindings.MODE_REMOTE.equals(ModeSwitcher.currentMode(this));
    }

    /** Forwards the hook's action extras, if any, so old callers of this component keep working. */
    private void openOverlay(Intent src) {
        Intent i = OverlayActivity.intent(this);
        if (src != null && src.hasExtra(ModeSwitcher.EXTRA_ACTION)) {
            i.putExtra(ModeSwitcher.EXTRA_ACTION, src.getStringExtra(ModeSwitcher.EXTRA_ACTION))
                    .putExtra(ModeSwitcher.EXTRA_PARAM, src.getStringExtra(ModeSwitcher.EXTRA_PARAM))
                    .putExtra(ModeSwitcher.EXTRA_SOURCE, src.getStringExtra(ModeSwitcher.EXTRA_SOURCE));
            src.removeExtra(ModeSwitcher.EXTRA_ACTION);
        }
        startActivity(i);
    }

    /** Off to the tablet: the app remote mode was entered from, else the ZTE desktop. */
    private void leave() {
        if (remote() && ProviderClient.exitRemote(this, true)) return;
        // tablet mode (we only show up stale behind another home) or no hook: the ZTE desktop, named
        // explicitly so a HOME that still resolves to us cannot loop
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(ModeSwitcher.MIFAVOR_PKG).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onBackPressed() {
        leave();
    }
}

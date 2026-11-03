package app.anora.quran;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PlaybackPlugin.class);
        registerPlugin(SpeechPlugin.class);
        super.onCreate(savedInstanceState);
        immersive();
        fastestRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        fastestRefresh();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        /* folding or unfolding switches to the other screen, which has its own display modes */
        fastestRefresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    /** Full screen for reading: the status and navigation bars stay hidden and only peek in on a swipe from the edge. */
    private void immersive() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * Ask for the screen's highest refresh rate (120 Hz on the Fold and S Ultra) at the current resolution.
     * Without this, many phones keep apps that draw through a web view at 60 Hz, and page turns look less smooth.
     */
    @SuppressWarnings("deprecation")
    private void fastestRefresh() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Display d = Build.VERSION.SDK_INT >= 30 ? getDisplay() : getWindowManager().getDefaultDisplay();
            if (d == null) return;
            Display.Mode cur = d.getMode(), best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate() + 0.5f) best = m;
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            boolean change = lp.preferredDisplayModeId != best.getModeId() || lp.preferredRefreshRate != best.getRefreshRate();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            if (change) getWindow().setAttributes(lp);
        } catch (Exception ignored) { }
    }
}

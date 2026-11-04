package app.anora.quran;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PlaybackPlugin.class);
        registerPlugin(SpeechPlugin.class);
        registerPlugin(AudioPlugin.class);
        super.onCreate(savedInstanceState);
        immersive();
        fastestRefresh();
        watchCutout();
        survivePageLoss();
    }

    /**
     * If Android ends the web view's page process (low memory with the app in the background), the default
     * is to crash the whole app. Instead, open the app again: the page then offers "كمّل التلاوة" from the
     * last ayah heard.
     */
    private void survivePageLoss() {
        if (getBridge() == null) return;
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                runOnUiThread(MainActivity.this::recreate);
                return true;
            }
        });
    }

    /** Camera hole / notch size in CSS px (top, right, bottom, left); read by the page through Playback.insets. */
    static volatile int[] cut = { 0, 0, 0, 0 };

    /**
     * The app runs full screen, so the page reaches the camera hole. The web view does not always report it
     * (env(safe-area-inset-top) stays 0 with hidden bars), so the cutout is measured here and handed to the
     * page as --cutT/--cutR/--cutB/--cutL; the top bar then starts below the camera.
     */
    private void watchCutout() {
        View d = getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(d, (v, in) -> {
            Insets c = in.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
            float k = getResources().getDisplayMetrics().density;
            int[] n = { Math.round(c.top / k), Math.round(c.right / k), Math.round(c.bottom / k), Math.round(c.left / k) };
            if (n[0] != cut[0] || n[1] != cut[1] || n[2] != cut[2] || n[3] != cut[3]) {
                cut = n;
                pushCutout();
            }
            return ViewCompat.onApplyWindowInsets(v, in);
        });
        ViewCompat.requestApplyInsets(d);
    }

    void pushCutout() {
        try {
            int[] n = cut;
            String js = "(function(s){s.setProperty('--cutT','" + n[0] + "px');s.setProperty('--cutR','" + n[1] + "px');s.setProperty('--cutB','"
                    + n[2] + "px');s.setProperty('--cutL','" + n[3] + "px');window.dispatchEvent(new Event('resize'))})(document.documentElement.style)";
            if (getBridge() != null && getBridge().getWebView() != null) getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript(js, null));
        } catch (Exception ignored) { }
    }

    /** Set by the page (Playback.volKeys): the volume keys turn pages instead of changing the volume. */
    static volatile boolean volTurn = false;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (volTurn && (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && getBridge() != null && getBridge().getWebView() != null) {
                String js = "window.__volKey&&window.__volKey(" + (k == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1) + ")";
                getBridge().getWebView().evaluateJavascript(js, null);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
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

package app.anora.quran;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
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
        registerPlugin(WidgetPlugin.class);
        super.onCreate(savedInstanceState);
        fromWidget(getIntent());
        fixedTextZoom();
        immersive();
        fastestRefresh();
        watchCutout();
        survivePageLoss();
        backClosesPanels();
    }

    /**
     * The back button (or the back swipe) first closes what is open in the page: the index, the ayah sheet,
     * settings, adhkar, memorising... one layer at a time. Only when nothing is open does the app go to the
     * background, without closing, so the recitation keeps playing and the page is where it was left.
     */
    private void backClosesPanels() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge() == null ? null : getBridge().getWebView();
                if (wv == null) { moveTaskToBack(true); return; }
                wv.evaluateJavascript("(window.anoraBack&&window.anoraBack())===true", v -> {
                    if (!"true".equals(v)) moveTaskToBack(true);
                });
            }
        });
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        fromWidget(intent);
    }

    /** A tap on a home-screen widget names the screen to open ("az:sab", "page:25"); the page picks it up. */
    private void fromWidget(Intent i) {
        if (i == null || !i.hasExtra(AnoraWidget.EXTRA_GO)) return;
        WidgetPlugin.deliver(i.getStringExtra(AnoraWidget.EXTRA_GO));
        i.removeExtra(AnoraWidget.EXTRA_GO);
    }

    /** The phone's font size setting (1 = default); read by the page through Playback.insets. */
    static volatile float fontScale = 1f;

    /**
     * Android's web view follows the phone's font size setting by scaling every letter on its own, after the page
     * has measured them, so on a phone set to a larger font (common on Galaxy A and Xiaomi phones) the mushaf lines
     * ran past the frame and the page header was cut. The page sizes its text itself, so the web view keeps its
     * text at 100%; the setting is handed to the page instead, which picks a matching app text size on first run.
     */
    private void fixedTextZoom() {
        fontScale = getResources().getConfiguration().fontScale;
        if (getBridge() != null && getBridge().getWebView() != null) getBridge().getWebView().getSettings().setTextZoom(100);
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
        fixedTextZoom();
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

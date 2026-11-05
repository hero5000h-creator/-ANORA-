package app.anora.quran;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Keeps the recitation going with the app in the background, with a notification to pause or end it. */
@CapacitorPlugin(name = "Playback")
public class PlaybackPlugin extends Plugin {

    static PlaybackPlugin inst;
    private boolean askedNotif = false;

    @Override
    public void load() {
        inst = this;
    }

    @PluginMethod
    public void start(PluginCall call) {
        askNotif();
        String t = call.getString("title", "أنورا"), x = call.getString("text", "");
        boolean p = call.getBoolean("playing", true);
        long[] st = starts(call);
        keepPageAwake(true);
        if (PlaybackService.inst != null) {
            PlaybackService.inst.update(t, x, p, st);
        } else {
            Intent i = new Intent(getContext(), PlaybackService.class);
            i.putExtra("title", t).putExtra("text", x).putExtra("playing", p);
            if (st != null) i.putExtra("starts", st);
            try {
                ContextCompat.startForegroundService(getContext(), i);
            } catch (Exception e) {
                call.reject("start failed: " + e.getMessage());
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void update(PluginCall call) {
        if (PlaybackService.inst != null) {
            PlaybackService.inst.update(call.getString("title", "أنورا"), call.getString("text", ""), call.getBoolean("playing", true), starts(call));
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        keepPageAwake(false);
        getContext().stopService(new Intent(getContext(), PlaybackService.class));
        call.resolve();
    }

    /** Camera hole / notch size in CSS px, so the page can keep its top bar clear of it. */
    @PluginMethod
    public void volKeys(PluginCall call) {
        MainActivity.volTurn = Boolean.TRUE.equals(call.getBoolean("on", false));
        call.resolve();
    }

    @PluginMethod
    public void insets(PluginCall call) {
        int[] n = MainActivity.cut;
        JSObject o = new JSObject();
        o.put("t", n[0]);
        o.put("r", n[1]);
        o.put("b", n[2]);
        o.put("l", n[3]);
        o.put("fs", MainActivity.fontScale);
        call.resolve(o);
    }

    /** The page confirms it handled a next / previous press (so the service does not jump as well). */
    @PluginMethod
    public void ack(PluginCall call) {
        int n = call.getInt("n", 0);
        if (n > PlaybackService.ackN) PlaybackService.ackN = n;
        call.resolve();
    }

    /** Ayah start times (ms) in the surah file now playing, or null when the page sent none. */
    private static long[] starts(PluginCall call) {
        JSArray a = call.getArray("starts", null);
        if (a == null) return null;
        long[] r = new long[a.length()];
        for (int k = 0; k < r.length; k++) r[k] = (long) a.optDouble(k, 0);
        return r;
    }

    /**
     * While a recitation runs, keep the web view's page process at full priority even when the app is not
     * on screen. Otherwise Android may freeze or drop it in the background: the ayah tracking stops, the
     * notification buttons reach nobody, and on return the recitation had stopped.
     */
    private void keepPageAwake(boolean on) {
        if (Build.VERSION.SDK_INT < 26 || getBridge() == null) return;
        getBridge().executeOnMainThread(() -> {
            WebView w = getBridge().getWebView();
            if (w != null) w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, !on);
        });
    }

    void emit(String action, int n) {
        JSObject o = new JSObject();
        o.put("a", action);
        o.put("n", n);
        o.put("t", System.currentTimeMillis());
        notifyListeners("action", o);
    }

    private void askNotif() {
        if (askedNotif || Build.VERSION.SDK_INT < 33 || getActivity() == null) return;
        askedNotif = true;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 4711);
        }
    }
}

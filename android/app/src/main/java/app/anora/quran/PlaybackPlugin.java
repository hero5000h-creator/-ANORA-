package app.anora.quran;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
        if (PlaybackService.inst != null) {
            PlaybackService.inst.update(t, x, p);
        } else {
            Intent i = new Intent(getContext(), PlaybackService.class);
            i.putExtra("title", t).putExtra("text", x).putExtra("playing", p);
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
            PlaybackService.inst.update(call.getString("title", "أنورا"), call.getString("text", ""), call.getBoolean("playing", true));
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), PlaybackService.class));
        call.resolve();
    }

    /** Camera hole / notch size in CSS px, so the page can keep its top bar clear of it. */
    @PluginMethod
    public void insets(PluginCall call) {
        int[] n = MainActivity.cut;
        JSObject o = new JSObject();
        o.put("t", n[0]);
        o.put("r", n[1]);
        o.put("b", n[2]);
        o.put("l", n[3]);
        call.resolve(o);
    }

    void emit(String action) {
        JSObject o = new JSObject();
        o.put("a", action);
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

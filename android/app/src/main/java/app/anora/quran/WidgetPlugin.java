package app.anora.quran;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * The page's side of the home-screen widgets.
 *  update(data)  the page hands over what the widgets show (today's adhkar progress, prayer times, last page)
 *  take()        returns and clears the screen a widget tap asked for ("az:sab", "page:25" ...)
 *  pin({kind})   asks the launcher to place a widget ("adhkar" or "tasbih"), where the launcher allows it
 * When a widget is tapped while the app is open, the page gets an "open" event and then calls take().
 */
@CapacitorPlugin(name = "Widget")
public class WidgetPlugin extends Plugin {

    static volatile WidgetPlugin inst;
    private static volatile String pending;

    @Override
    public void load() {
        inst = this;
    }

    /** Called by MainActivity for an intent that came from a widget. */
    static void deliver(String go) {
        if (go == null || go.isEmpty()) return;
        pending = go;
        WidgetPlugin p = inst;
        if (p != null) p.notifyListeners("open", new JSObject());
    }

    @PluginMethod
    public void update(PluginCall call) {
        Context c = getContext();
        try {
            c.getSharedPreferences(AnoraWidget.PREFS, Context.MODE_PRIVATE).edit().putString("data", call.getData().toString()).apply();
            AnoraWidget.refresh(c);
            call.resolve();
        } catch (Exception e) {
            call.reject("widget update failed", e);
        }
    }

    @PluginMethod
    public void take(PluginCall call) {
        JSObject r = new JSObject();
        String g = pending;
        pending = null;
        r.put("go", g);
        call.resolve(r);
    }

    @PluginMethod
    public void pin(PluginCall call) {
        JSObject r = new JSObject();
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Context c = getContext();
                AppWidgetManager m = AppWidgetManager.getInstance(c);
                Class<?> k = "tasbih".equals(call.getString("kind")) ? TasbihWidget.class : AnoraWidget.class;
                if (m.isRequestPinAppWidgetSupported()) ok = m.requestPinAppWidget(new ComponentName(c, k), null, null);
            } catch (Exception ignored) { }
        }
        r.put("ok", ok);
        call.resolve(r);
    }
}

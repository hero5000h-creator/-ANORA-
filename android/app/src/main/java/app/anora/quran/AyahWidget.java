package app.anora.quran;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The ayah of the day on the home screen: the same short ayah the today card shows. The page sends the
 * next fifteen days at once (AnoraWidget data "ayd": [{d: day number, t: text, r: "سورة … · آية …", id}]),
 * so the widget turns to the new ayah at midnight by itself (AnoraWidget's hourly alarm redraws it).
 * A tap opens the ayah in the mushaf.
 */
public class AyahWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) draw(c, m, id);
        AnoraWidget.schedule(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        draw(c, m, id);
    }

    @Override
    public void onDisabled(Context c) {
        new AnoraWidget().onDisabled(c);
    }

    static void draw(Context c, AppWidgetManager m, int id) {
        m.updateAppWidget(id, build(c, AnoraWidget.data(c), System.currentTimeMillis()));
    }

    static RemoteViews build(Context c, JSONObject d, long now) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.wg_ayah);
        JSONArray a = d.optJSONArray("ayd");
        long day = AnoraWidget.dayNum(now);
        JSONObject pick = null;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optLong("d") == day) { pick = o; break; }
            }
        }
        v.setOnClickPendingIntent(R.id.ayOpen, AnoraWidget.open(c, ""));
        if (pick == null) {
            // not sent yet, or the app has not been opened for two weeks
            v.setTextViewText(R.id.ayTitle, "آية اليوم");
            v.setTextViewText(R.id.aySub, a == null ? "افتح أنورا مرة لتظهر الآية" : "افتح أنورا لتتحدّث الآية");
            v.setTextViewText(R.id.ayText, "أَلَا بِذِكْرِ ٱللَّهِ تَطْمَئِنُّ ٱلْقُلُوبُ");
            v.setOnClickPendingIntent(R.id.ayBody, AnoraWidget.open(c, ""));
            return v;
        }
        String t = pick.optString("t", ""), r = pick.optString("r", "");
        v.setTextViewText(R.id.ayTitle, "آية اليوم");
        v.setTextViewText(R.id.aySub, r);
        v.setTextViewText(R.id.ayText, t);
        v.setOnClickPendingIntent(R.id.ayBody, AnoraWidget.open(c, "ayah:" + pick.optLong("id")));
        v.setContentDescription(R.id.ayBody, "آية اليوم: " + t + "، " + r);
        return v;
    }

    /** Today's hijri date as the page wrote it (data "hj": {day number: "١٥ جمادى الآخرة"}), or "". */
    static String hijri(JSONObject d, long day) {
        JSONObject h = d.optJSONObject("hj");
        return h == null ? "" : h.optString(String.valueOf(day), "");
    }
}

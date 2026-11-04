package app.anora.quran;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Prayer times on the home screen, in the adhkar look: the next prayer and its time, a live countdown
 * (a Chronometer, so it ticks without waking the app), and the five prayers of that day with the next one
 * lit in gold. Times come from the page (AnoraWidget data "pt", 15 days computed on the phone by adhan-js),
 * so it keeps working for two weeks with the app closed. It shares AnoraWidget's alarm, which fires at each
 * prayer time.
 */
public class PrayerWidget extends AppWidgetProvider {

    private static final int[] CELL = { R.id.pc0, R.id.pc1, R.id.pc2, R.id.pc3, R.id.pc4 };
    private static final int[] NAME = { R.id.pn0, R.id.pn1, R.id.pn2, R.id.pn3, R.id.pn4 };
    private static final int[] TIME = { R.id.pt0, R.id.pt1, R.id.pt2, R.id.pt3, R.id.pt4 };

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
        JSONObject d = AnoraWidget.data(c);
        long now = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> map = new HashMap<>();
            map.put(new SizeF(200f, 80f), build(c, d, now, false));
            map.put(new SizeF(200f, 165f), build(c, d, now, true));
            m.updateAppWidget(id, new RemoteViews(map));
        } else {
            Bundle o = m.getAppWidgetOptions(id);
            int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            m.updateAppWidget(id, build(c, d, now, h >= 165));
        }
    }

    static RemoteViews build(Context c, JSONObject d, long now, boolean tall) {
        RemoteViews v = new RemoteViews(c.getPackageName(), tall ? R.layout.wg_prayer : R.layout.wg_prayer_small);
        JSONArray pt = d.optJSONArray("pt");
        long[] day = null;
        long next = 0;
        int nj = -1;
        if (pt != null) {
            for (int i = 0; i < pt.length() && nj < 0; i++) {
                JSONArray r = pt.optJSONArray(i);
                if (r == null || r.length() < 6) continue;
                for (int j : AnoraWidget.SAL) {
                    long t = r.optLong(j);
                    if (t > now) {
                        next = t;
                        nj = j;
                        day = new long[6];
                        for (int k = 0; k < 6; k++) day[k] = r.optLong(k);
                        break;
                    }
                }
            }
        }
        v.setOnClickPendingIntent(R.id.pBody, AnoraWidget.open(c, "az:pt"));
        if (tall) v.setOnClickPendingIntent(R.id.pOpen, AnoraWidget.open(c, ""));
        if (nj < 0) {
            // not set up yet (or the app has not been opened for two weeks)
            boolean never = pt == null || pt.length() == 0;
            v.setTextViewText(R.id.pTitle, "مواقيت الصلاة");
            v.setTextViewText(R.id.pSub, never ? "المس لتختار مدينتك وطريقة الحساب" : "افتح أنورا لتتحدّث المواقيت");
            v.setViewVisibility(R.id.pLeftBox, View.GONE);
            v.setViewVisibility(R.id.pRow, View.GONE);
            if (tall) v.setTextViewText(R.id.pBig, "—");
            return v;
        }
        boolean tomorrow = AnoraWidget.dayNum(next) != AnoraWidget.dayNum(now);
        String name = AnoraWidget.PT_NAMES[nj], at = AnoraWidget.clock(next);
        String label = tomorrow && nj == 0 ? "فجر الغد" : "صلاة " + name;
        v.setTextViewText(R.id.pTitle, tall ? label : label + " · " + at);
        String city = d.optString("ptc", ""), meth = d.optString("ptm", "");
        v.setTextViewText(R.id.pSub, city.isEmpty() && meth.isEmpty() ? "مواقيت الصلاة" : city + (city.isEmpty() || meth.isEmpty() ? "" : " · ") + meth);
        if (tall) v.setTextViewText(R.id.pBig, at);
        v.setViewVisibility(R.id.pLeftBox, View.VISIBLE);
        v.setChronometer(R.id.pLeft, SystemClock.elapsedRealtime() + (next - now), null, true);
        if (Build.VERSION.SDK_INT >= 24) v.setChronometerCountDown(R.id.pLeft, true);
        v.setViewVisibility(R.id.pRow, View.VISIBLE);
        for (int i = 0; i < 5; i++) {
            int j = AnoraWidget.SAL[i];
            boolean on = j == nj, past = day[j] < now;
            v.setTextViewText(NAME[i], AnoraWidget.PT_NAMES[j]);
            v.setTextViewText(TIME[i], AnoraWidget.clock(day[j]));
            v.setInt(CELL[i], "setBackgroundResource", on ? R.drawable.wg_cell_on : R.drawable.wg_cell);
            v.setTextColor(TIME[i], on ? 0xFFF0C98A : past ? 0x8CEAE6DC : 0xFFF5F1E8);
            v.setTextColor(NAME[i], on ? 0xFFF3E3BF : 0xB3EAE6DC);
        }
        v.setContentDescription(R.id.pBody, label + " الساعة " + at + (city.isEmpty() ? "" : "، " + city));
        return v;
    }
}

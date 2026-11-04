package app.anora.quran;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The adhkar widget: the dhikr due at this hour (morning, evening, after prayer, sleep) in the adhkar
 * screen's look, today's progress, the next prayer and "carry on reading" from the last page.
 *
 * The page sends its data through WidgetPlugin.update (prefs "anora_widget", key "data"):
 *   {day, page, sur, pt:[[fajr,sunrise,dhuhr,asr,maghrib,isha] ms per day], free:[...],
 *    cats:{sab|mas|nom|sal:{name, sub, of, n, nx:{t,s}|null, f0:{t,s}}}}
 * The widget decides by itself which adhkar are due now (the same rule as azNowCat in app.html),
 * so it stays right while the app is closed. It wakes itself at the next change (a prayer time, the
 * top of the hour, midnight).
 */
public class AnoraWidget extends AppWidgetProvider {

    static final String PREFS = "anora_widget";
    static final String TICK = "app.anora.quran.WIDGET_TICK";
    static final String EXTRA_GO = "anora_go";
    private static final long MIN = 60_000L, DAY = 86_400_000L;
    private static final int[] SAL = { 0, 2, 3, 4, 5 };
    private static final String[] PT_NAMES = { "الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء" };

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) draw(c, m, id);
        schedule(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        draw(c, m, id);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        if (TICK.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) refresh(c);
    }

    @Override
    public void onDisabled(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(tick(c));
    }

    /** Redraw every placed adhkar widget (and the tasbih, which shares nothing but is cheap). */
    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, AnoraWidget.class));
        for (int id : ids) draw(c, m, id);
        if (ids.length > 0) schedule(c);
    }

    // ---------------------------------------------------------------- what to show

    static final class St {
        String key = "", title, sub, text, src = "", prayer, read, go, prayGo, readGo;
        int icon, n, of;
        boolean done;
    }

    static JSONObject data(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("data", null);
            if (s != null) return new JSONObject(s);
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    static long dayNum(long ms) {
        return Math.floorDiv(ms + TimeZone.getDefault().getOffset(ms), DAY);
    }

    /** The prayer-time row for the day of `ms`, or null when the person has not set up prayer times. */
    static long[] row(JSONArray pt, long ms) {
        if (pt == null) return null;
        long d = dayNum(ms);
        for (int i = 0; i < pt.length(); i++) {
            JSONArray r = pt.optJSONArray(i);
            if (r == null || r.length() < 6) continue;
            if (dayNum(r.optLong(0)) == d) {
                long[] t = new long[6];
                for (int j = 0; j < 6; j++) t[j] = r.optLong(j);
                return t;
            }
        }
        return null;
    }

    /** Same rule as azNowCat() in app.html. */
    static String nowCat(JSONArray pt, long now) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long[] t = row(pt, now);
        if (t != null) {
            for (int j : SAL) if (now >= t[j] && now < t[j] + 25 * MIN) return "sal";
            if (now >= t[0] && now < t[2]) return "sab";
            if (now >= t[3] && now < t[5] + 60 * MIN) return "mas";
            if (now >= t[5] + 90 * MIN || h < 3) return "nom";
            return "";
        }
        return h >= 4 && h < 11 ? "sab" : h >= 15 && h < 20 ? "mas" : h >= 21 || h < 3 ? "nom" : "";
    }

    private static final Map<String, String[]> DEF = new HashMap<>();
    static {
        DEF.put("sab", new String[] { "أذكار الصباح", "بعد الفجر حتى الضحى", "أَصْبَحْنَا وَأَصْبَحَ الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ" });
        DEF.put("mas", new String[] { "أذكار المساء", "بعد العصر حتى الليل", "أَمْسَيْنَا وَأَمْسَى الْمُلْكُ لِلَّهِ، وَالْحَمْدُ لِلَّهِ" });
        DEF.put("nom", new String[] { "أذكار النوم", "قبل أن تنام", "بِاسْمِكَ اللَّهُمَّ أَمُوتُ وَأَحْيَا" });
        DEF.put("sal", new String[] { "بعد الصلاة", "أذكار ما بعد السلام", "أَسْتَغْفِرُ اللَّهَ" });
    }
    private static final String[] FREE = { "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ", "لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ",
            "اللَّهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ", "أَسْتَغْفِرُ اللَّهَ", "لَا إِلَٰهَ إِلَّا اللَّهُ" };

    static St state(JSONObject d, long now) {
        St s = new St();
        JSONArray pt = d.optJSONArray("pt");
        String k = nowCat(pt, now);
        boolean today = d.optLong("day", -1) == dayNum(now);
        JSONObject cats = d.optJSONObject("cats"), cat = cats == null ? null : cats.optJSONObject(k);
        String[] def = DEF.get(k);
        if (def != null) {
            s.key = k;
            s.icon = "sab".equals(k) ? R.drawable.wg_ic_sab : "mas".equals(k) ? R.drawable.wg_ic_mas : "nom".equals(k) ? R.drawable.wg_ic_nom : R.drawable.wg_ic_sal;
            s.title = cat != null ? cat.optString("name", def[0]) : def[0];
            String sub = cat != null ? cat.optString("sub", def[1]) : def[1];
            s.of = cat != null ? cat.optInt("of", 0) : 0;
            s.n = today && cat != null ? Math.min(cat.optInt("n", 0), s.of) : 0;
            JSONObject it = cat == null ? null : today ? cat.optJSONObject("nx") : cat.optJSONObject("f0");
            s.done = today && cat != null && s.of > 0 && cat.isNull("nx");
            if (s.done) {
                s.text = "تَقَبَّلَ اللَّهُ مِنْكَ";
                s.src = "أتممتها اليوم · ﴿أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ﴾";
                s.sub = "✓ " + ar(s.of) + " من " + ar(s.of) + " · " + sub;
                s.go = "az:home";
            } else {
                s.text = it != null ? it.optString("t", def[2]) : def[2];
                s.src = it != null ? it.optString("s", "") : "";
                s.sub = (s.of > 0 ? ar(s.n) + " من " + ar(s.of) + " · " : "") + sub;
                s.go = "az:" + k;
            }
        } else {
            JSONArray fr = d.optJSONArray("free");
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            s.icon = R.drawable.wg_ic_free;
            s.title = "اذكر الله";
            s.sub = "التسبيح الحر · سبحتك في كل وقت";
            s.text = fr != null && fr.length() > 0 ? fr.optString(h % fr.length(), FREE[0]) : FREE[h % FREE.length];
            s.src = "﴿أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ﴾";
            s.go = "az:free";
        }
        s.text = clip(s.text, 150);

        // next prayer
        s.prayer = "مواقيت الصلاة";
        s.prayGo = "az:pt";
        if (pt != null) {
            long best = Long.MAX_VALUE;
            int bj = -1;
            for (int i = 0; i < pt.length(); i++) {
                JSONArray r = pt.optJSONArray(i);
                if (r == null || r.length() < 6) continue;
                for (int j : SAL) {
                    long t = r.optLong(j);
                    if (t > now && t < best) { best = t; bj = j; }
                }
            }
            if (bj >= 0) s.prayer = PT_NAMES[bj] + " " + clock(best);
        }

        // carry on reading
        int page = d.optInt("page", 0);
        String sur = d.optString("sur", "");
        s.read = page > 1 ? (sur.isEmpty() ? "" : sur + " · ") + "ص " + ar(page) : "افتح المصحف";
        s.readGo = "page:" + page;
        return s;
    }

    static String clip(String t, int max) {
        if (t == null) return "";
        t = t.trim();
        if (t.length() <= max) return t;
        int cut = t.lastIndexOf(' ', max);
        return t.substring(0, cut > max / 2 ? cut : max).replaceAll("[،,\\s]+$", "") + " …";
    }

    static String ar(long n) {
        String s = String.valueOf(n);
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return b.toString();
    }

    /** 12-hour clock without am/pm, as the app shows prayer times. */
    static String clock(long ms) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(ms);
        int h = k.get(Calendar.HOUR_OF_DAY) % 12, m = k.get(Calendar.MINUTE);
        return ar(h == 0 ? 12 : h) + ":" + ar(m / 10) + ar(m % 10);
    }

    // ---------------------------------------------------------------- drawing

    static void draw(Context c, AppWidgetManager m, int id) {
        St s = state(data(c), System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> map = new HashMap<>();
            map.put(new SizeF(170f, 90f), build(c, s, false));
            map.put(new SizeF(170f, 175f), build(c, s, true));
            m.updateAppWidget(id, new RemoteViews(map));
        } else {
            Bundle o = m.getAppWidgetOptions(id);
            int h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            m.updateAppWidget(id, build(c, s, h >= 175));
        }
    }

    static RemoteViews build(Context c, St s, boolean tall) {
        RemoteViews v = new RemoteViews(c.getPackageName(), tall ? R.layout.wg_adhkar : R.layout.wg_adhkar_small);
        v.setImageViewResource(R.id.wgIcon, s.icon);
        v.setTextViewText(R.id.wgTitle, s.title);
        v.setTextViewText(R.id.wgSub, s.sub);
        v.setTextViewText(R.id.wgText, s.text);
        if (s.of > 0) {
            v.setViewVisibility(R.id.wgBar, View.VISIBLE);
            v.setProgressBar(R.id.wgBar, s.of, s.n, false);
        } else v.setViewVisibility(R.id.wgBar, View.INVISIBLE);
        if (tall) v.setTextViewText(R.id.wgSrc, s.src);
        v.setTextViewText(R.id.wgPrayT, s.prayer);
        v.setTextViewText(R.id.wgReadT, s.read);
        v.setContentDescription(R.id.wgPray, "الصلاة القادمة: " + s.prayer);
        v.setContentDescription(R.id.wgRead, "كمّل القراءة: " + s.read);
        v.setOnClickPendingIntent(R.id.wgBody, open(c, s.go));
        v.setOnClickPendingIntent(R.id.wgOpen, open(c, ""));
        v.setOnClickPendingIntent(R.id.wgPray, open(c, s.prayGo));
        v.setOnClickPendingIntent(R.id.wgRead, open(c, s.readGo));
        return v;
    }

    /** Opens the app; `go` names the screen (read by MainActivity → WidgetPlugin.take). */
    static PendingIntent open(Context c, String go) {
        Intent i = new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("anora-widget://open/" + Uri.encode(go)))
                .putExtra(EXTRA_GO, go)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 7300 + Math.abs(go.hashCode() % 97), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------------------------------------------------------- waking at the next change

    private static PendingIntent tick(Context c) {
        Intent i = new Intent(c, AnoraWidget.class).setAction(TICK);
        return PendingIntent.getBroadcast(c, 7399, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        long now = System.currentTimeMillis();
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(now);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        k.add(Calendar.HOUR_OF_DAY, 1);
        long next = k.getTimeInMillis();
        JSONArray pt = data(c).optJSONArray("pt");
        if (pt != null) {
            for (int i = 0; i < pt.length(); i++) {
                JSONArray r = pt.optJSONArray(i);
                if (r == null || r.length() < 6) continue;
                long[] cand = { r.optLong(0), r.optLong(2), r.optLong(3), r.optLong(4), r.optLong(5), r.optLong(0) + 25 * MIN, r.optLong(2) + 25 * MIN,
                        r.optLong(3) + 25 * MIN, r.optLong(4) + 25 * MIN, r.optLong(5) + 25 * MIN, r.optLong(5) + 60 * MIN, r.optLong(5) + 90 * MIN };
                for (long t : cand) if (t > now + 1000 && t < next) next = t;
            }
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.RTC, next + 1500, tick(c));
    }
}

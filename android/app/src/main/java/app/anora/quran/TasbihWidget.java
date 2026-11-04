package app.anora.quran;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.RemoteViews;

/**
 * A tasbih on the home screen: every tap counts, without opening the app. It follows the dhikr after prayer:
 * 33 سبحان الله, 33 الحمد لله, 33 الله أكبر, then لا إله إلا الله to complete the hundred. A short buzz marks each
 * change; after the last one the ring stays full until the next tap starts again.
 */
public class TasbihWidget extends AppWidgetProvider {

    static final String TAP = "app.anora.quran.TASBIH_TAP";
    static final String RESET = "app.anora.quran.TASBIH_RESET";
    static final String[] T = { "سُبْحَانَ اللَّهِ", "الْحَمْدُ لِلَّهِ", "اللَّهُ أَكْبَرُ", "لَا إِلَٰهَ إِلَّا اللَّهُ" };
    static final int[] N = { 33, 33, 33, 1 };

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        draw(c, m, ids);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        String a = i.getAction();
        if (!TAP.equals(a) && !RESET.equals(a)) return;
        SharedPreferences p = c.getSharedPreferences(AnoraWidget.PREFS, Context.MODE_PRIVATE);
        int ph = p.getInt("tzPh", 0), n = p.getInt("tzN", 0);
        if (RESET.equals(a) || ph >= T.length) {
            ph = 0;
            n = 0;
        } else {
            n++;
            if (n >= N[ph]) {
                ph++;
                n = 0;
                buzz(c, ph >= T.length ? new long[] { 0, 60, 90, 60 } : new long[] { 0, 45 });
            }
        }
        p.edit().putInt("tzPh", ph).putInt("tzN", n).apply();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        draw(c, m, m.getAppWidgetIds(new ComponentName(c, TasbihWidget.class)));
    }

    static void draw(Context c, AppWidgetManager m, int[] ids) {
        if (ids == null || ids.length == 0) return;
        SharedPreferences p = c.getSharedPreferences(AnoraWidget.PREFS, Context.MODE_PRIVATE);
        int ph = p.getInt("tzPh", 0), n = p.getInt("tzN", 0);
        boolean done = ph >= T.length;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.wg_tasbih);
        if (done) {
            v.setTextViewText(R.id.tzName, "تَقَبَّلَ اللَّهُ");
            v.setTextViewText(R.id.tzCount, AnoraWidget.ar(100));
            v.setTextViewText(R.id.tzOf, "المس لتبدأ");
            v.setProgressBar(R.id.tzRing, 1, 1, false);
        } else {
            v.setTextViewText(R.id.tzName, T[ph]);
            v.setTextViewText(R.id.tzCount, AnoraWidget.ar(n));
            v.setTextViewText(R.id.tzOf, "من " + AnoraWidget.ar(N[ph]));
            v.setProgressBar(R.id.tzRing, N[ph], n, false);
        }
        v.setContentDescription(R.id.tzTap, done ? "تمّت المئة، المس لتبدأ من جديد" : T[ph] + "، " + n + " من " + N[ph] + "، المس للتسبيح");
        v.setOnClickPendingIntent(R.id.tzTap, broadcast(c, TAP, 7311));
        v.setOnClickPendingIntent(R.id.tzReset, broadcast(c, RESET, 7312));
        for (int id : ids) m.updateAppWidget(id, v);
    }

    private static PendingIntent broadcast(Context c, String action, int code) {
        Intent i = new Intent(c, TasbihWidget.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private static void buzz(Context c, long[] pattern) {
        try {
            Vibrator vb = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (vb == null || !vb.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vb.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }
}

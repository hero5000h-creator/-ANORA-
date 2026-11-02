package app.anora.quran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

/** A foreground service so Android keeps the app (and the recitation playing in it) alive with the screen off. */
public class PlaybackService extends Service {

    static PlaybackService inst;
    static final String TOGGLE = "app.anora.quran.TOGGLE", STOP = "app.anora.quran.STOP", CH = "recitation";
    private String title = "أنورا", text = "";
    private boolean playing = true;
    private PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i != null ? i.getAction() : null;
        if (TOGGLE.equals(a)) {
            if (PlaybackPlugin.inst != null) PlaybackPlugin.inst.emit("toggle");
            return START_NOT_STICKY;
        }
        if (STOP.equals(a)) {
            if (PlaybackPlugin.inst != null) PlaybackPlugin.inst.emit("stop");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null) {
            title = i.getStringExtra("title") != null ? i.getStringExtra("title") : title;
            text = i.getStringExtra("text") != null ? i.getStringExtra("text") : text;
            playing = i.getBooleanExtra("playing", true);
        }
        inst = this;
        channel();
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        lock(playing);
        return START_NOT_STICKY;
    }

    void update(String t, String x, boolean p) {
        title = t;
        text = x;
        playing = p;
        lock(p);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, build());
    }

    private void lock(boolean on) {
        if (on) {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anora:recitation");
                wl.setReferenceCounted(false);
            }
            if (!wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L);
        } else if (wl != null && wl.isHeld()) {
            wl.release();
        }
    }

    private void channel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CH) != null) return;
        NotificationChannel c = new NotificationChannel(CH, "التلاوة", NotificationManager.IMPORTANCE_LOW);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent po = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_anora)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(po)
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, playing ? "إيقاف مؤقت" : "متابعة", pi(TOGGLE))
            .addAction(0, "إنهاء", pi(STOP))
            .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        inst = null;
        lock(false);
        super.onDestroy();
    }
}

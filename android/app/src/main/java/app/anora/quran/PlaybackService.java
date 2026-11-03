package app.anora.quran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * A foreground service so Android keeps the app (and the recitation playing in it) alive with the screen off.
 * It also owns the media session (lock screen, headset and Bluetooth buttons, car); audio focus is held by the player itself (AudioPlugin.kt)
 * (the recitation pauses for a phone call and comes back after it, and pauses when headphones are unplugged).
 */
public class PlaybackService extends Service {

    static PlaybackService inst;
    static final String TOGGLE = "app.anora.quran.TOGGLE", STOP = "app.anora.quran.STOP", NEXT = "app.anora.quran.NEXT", PREV = "app.anora.quran.PREV", CH = "recitation";
    private String title = "أنورا", text = "";
    private boolean playing = true, noisyOn = false;
    private PowerManager.WakeLock wl;
    private MediaSessionCompat session;
    private AudioManager am;


    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()) && playing) emit("pause");
        }
    };

    private static void emit(String a) {
        if (PlaybackPlugin.inst != null) PlaybackPlugin.inst.emit(a);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSessionCompat(this, "anora");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { emit("play"); }
            @Override public void onPause() { emit("pause"); }
            @Override public void onSkipToNext() { emit("next"); }
            @Override public void onSkipToPrevious() { emit("prev"); }
            @Override public void onStop() { emit("stop"); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i != null ? i.getAction() : null;
        if (TOGGLE.equals(a)) { emit("toggle"); return START_NOT_STICKY; }
        if (NEXT.equals(a)) { emit("next"); return START_NOT_STICKY; }
        if (PREV.equals(a)) { emit("prev"); return START_NOT_STICKY; }
        if (STOP.equals(a)) { emit("stop"); stopSelf(); return START_NOT_STICKY; }
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
        apply();
        return START_NOT_STICKY;
    }

    void update(String t, String x, boolean p) {
        title = t;
        text = x;
        playing = p;
        apply();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, build());
    }

    /** wake lock, headphone watch and the media session follow playing / paused */
    private void apply() {
        lock(playing);
        if (playing) {
            /* no audio focus request here: the player (AudioPlugin.kt, or the web view's <audio> as a fallback)
               already holds focus and pauses itself for calls. Asking for it again here made the two fight
               and every play was stopped straight away. */
            if (!noisyOn) {
                ContextCompat.registerReceiver(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
                noisyOn = true;
            }
        } else if (noisyOn) {
            unregisterReceiver(noisy);
            noisyOn = false;
        }
        session.setMetadata(new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, text)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "أنورا")
            .build());
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP)
            .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build());
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
            .addAction(android.R.drawable.ic_media_previous, "السابقة", pi(PREV))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "إيقاف مؤقت" : "متابعة", pi(TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "التالية", pi(NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إنهاء", pi(STOP))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
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
        if (noisyOn) { try { unregisterReceiver(noisy); } catch (Exception e) { } noisyOn = false; }
        if (session != null) session.release();
        super.onDestroy();
    }
}

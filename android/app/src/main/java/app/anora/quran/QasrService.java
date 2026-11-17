package app.anora.quran;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

/**
 * Runs the app's own Quran recogniser (and its microphone) in a process of its own (":qasr", see the manifest).
 *
 * ONNX Runtime is native code: if it ever fails on some phone's processor, only this process ends, never the app.
 * SpeechPlugin sees the process go (linkToDeath) and carries on with the phone's recogniser. It also keeps the
 * model's memory away from the page.
 *
 * Messages from the app: START (replyTo = where to send), STOP, RESET, EXPECT (data: words, the words due next on
 * the page in order, for follow-along).
 * Messages to the app: READY (arg1: 1 listening, 0 the model cannot run here, 2 no microphone),
 * HEARD (data: text, fin), ERROR (arg1: -2 the microphone stream ended), PHRASE (arg1: number, data: path, n:
 * the phrase's audio for the vowel check). VOWELS comes from HarakatService.
 */
public class QasrService extends Service {

    static final int START = 1, STOP = 2, RESET = 3, EXPECT = 4;
    static final int READY = 10, HEARD = 11, ERROR = 12, PHRASE = 13, VOWELS = 14;
    static final String TAG = "qasr";
    static final String SELFTEST = "app.anora.quran.QASR_SELFTEST";

    private static QuranAsr asr;
    private static boolean failed = false;
    /** follow-along: each phrase's audio is handed on for the vowel check (HarakatService, a process of its own) */
    private volatile boolean checkVowels = false;
    private int phraseSeq = 0;
    private static String why = "";

    private volatile boolean on = false;
    private volatile Messenger client;
    private volatile QuranListen listen;
    /** the words due next (follow-along); kept for a listener that starts after they came */
    private volatile String[] expectWords = null;
    private volatile Thread thread;

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), m -> {
        switch (m.what) {
            case START:
                checkVowels = m.arg1 == 1;
                begin(m.replyTo);
                return true;
            case STOP:
                end();
                return true;
            case RESET:
                QuranListen l = listen;
                if (l != null) l.reset();
                return true;
            case EXPECT:
                String[] w = m.getData().getStringArray("words");
                expectWords = w;
                QuranListen e = listen;
                if (e != null) e.expect(w);
                return true;
            default:
                return false;
        }
    }));

    @Override
    public IBinder onBind(Intent intent) {
        return inbox.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        end();
        return false;
    }

    @Override
    public void onDestroy() {
        end();
        super.onDestroy();
    }

    /** Debuggable builds only (CI): decode a few seconds of noise and log the result, with no microphone. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent != null && SELFTEST.equals(intent.getAction())) {
            new Thread(() -> {
                try {
                    QuranAsr a = model(this);
                    if (a == null) {
                        Log.e(TAG, "selftest: model failed to load");
                    } else {
                        Random r = new Random(1);
                        float[] x = new float[1600];
                        StringBuilder heard = new StringBuilder();
                        QuranListen l = new QuranListen(a, (t, fin) -> heard.append(fin ? "F:" : "p:").append(t).append(' '));
                        long t0 = System.nanoTime();
                        for (int c = 0; c < 40; c++) {
                            boolean loud = (c / 10) % 2 == 0;
                            for (int i = 0; i < x.length; i++) x[i] = loud ? (float) (r.nextGaussian() * 0.1) : 0f;
                            l.feed(x, x.length);
                        }
                        l.flush();
                        Log.i(TAG, "selftest ok " + (System.nanoTime() - t0) / 1000000 + "ms last=" + l.decodeMs + "ms heard=" + heard);
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "selftest failed", e);
                }
                stopSelf(startId);
            }, "anora-qasr-test").start();
        }
        return START_NOT_STICKY;
    }

    /** Loads the model once per process (copied out of the APK to app storage the first time). Slow. */
    static synchronized QuranAsr model(Context ctx) {
        if (asr != null || failed) return asr;
        try {
            File f = copiedFile(ctx, "qasr/model.onnx", "qasr-1.onnx");
            int cores = Runtime.getRuntime().availableProcessors();
            // more threads, a quicker decode each step, so the word lights sooner (an 8-core phone: about a quarter faster)
            asr = new QuranAsr(f.getAbsolutePath(), cores >= 8 ? 4 : cores >= 6 ? 2 : 1);
        } catch (Throwable e) {
            Log.e(TAG, "model failed", e);
            failed = true;
            why = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return asr;
    }

    /** An asset copied once to app storage (ONNX Runtime reads a file, so the model is not held twice in memory). */
    static File copiedFile(Context ctx, String asset, String name) throws Exception {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists() || f.length() < 1000000) {
            File tmp = new File(ctx.getFilesDir(), name + ".tmp");
            try (InputStream in = ctx.getAssets().open(asset); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) throw new Exception("rename");
        }
        return f;
    }

    static String copied(Context ctx, String asset, String name) throws Exception {
        return copiedFile(ctx, asset, name).getAbsolutePath();
    }

    /**
     * A phrase ended: its audio is written (16-bit) to a file, and the app is told; the app hands the file to the
     * vowel check, in its own process. Each file is deleted once heard.
     */
    private void phrase(float[] a, int n, Thread me) {
        if (!checkVowels || thread != me) return;
        int seq = ++phraseSeq;
        // a file per phrase (64 names in turn): with only four, a vowel check four phrases behind read newer audio
        // and marked the vowels of the wrong words
        File f = new File(getCacheDir(), "phrase-" + (seq % 64) + ".pcm");
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = Math.max(-32768, Math.min(32767, Math.round(a[i] * 32767f)));
            buf[2 * i] = (byte) v;
            buf[2 * i + 1] = (byte) (v >> 8);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(buf);
        } catch (Throwable e) {
            Log.e(TAG, "phrase audio", e);
            return;
        }
        Bundle b = new Bundle();
        b.putString("path", f.getAbsolutePath());
        b.putInt("n", n);
        send(PHRASE, seq, b);
    }

    private void send(int what, int arg1, Bundle data) {
        Messenger c = client;
        if (c == null) return;
        Message m = Message.obtain(null, what, arg1, 0);
        if (data != null) m.setData(data);
        try {
            c.send(m);
        } catch (RemoteException e) {
            on = false;
        }
    }

    private void begin(Messenger to) {
        end();
        client = to;
        on = true;
        Thread t = new Thread(() -> run(Thread.currentThread()), "anora-qasr");
        t.setPriority(Thread.MAX_PRIORITY - 1);
        thread = t;
        t.start();
    }

    private void end() {
        on = false;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(800); } catch (InterruptedException ignored) {}
        }
    }

    private void run(Thread me) {
        QuranAsr a = model(this);
        if (thread != me || !on) return;
        if (a == null) {
            on = false;
            Bundle b = new Bundle();
            b.putString("why", why);
            send(READY, 0, b);
            return;
        }
        int rate = QuranAsr.RATE;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            // 4 s of buffer: the recorder keeps hearing while a decode runs
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2 * 4));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                ar.release();
                throw new IllegalStateException("mic");
            }
            ar.startRecording();
        } catch (Throwable e) {
            on = false;
            send(READY, 2, null);
            return;
        }
        send(READY, 1, null);
        QuranListen l = new QuranListen(a, new QuranListen.Out() {
            @Override
            public void heard(String text, boolean fin) {
                if (!on || thread != me) return;
                Bundle b = new Bundle();
                b.putString("text", text);
                b.putBoolean("fin", fin);
                send(HEARD, 0, b);
            }

            @Override
            public void phrase(float[] x, int n) {
                QasrService.this.phrase(x, n, me);
            }
        });
        String[] ew = expectWords;
        if (ew != null) l.expect(ew);
        listen = l;
        // words that came in between are not lost
        if (expectWords != ew && expectWords != null) l.expect(expectWords);
        short[] pcm = new short[rate / 10];
        float[] x = new float[pcm.length];
        try {
            while (on && thread == me) {
                int k = ar.read(pcm, 0, pcm.length);
                if (k <= 0) {
                    if (k < 0) break;
                    continue;
                }
                for (int i = 0; i < k; i++) x[i] = pcm[i] / 32768f;
                l.feed(x, k);
            }
            if (on && thread == me) send(ERROR, -2, null);
        } catch (Throwable e) {
            Log.e(TAG, "listen failed", e);
            if (thread == me) send(ERROR, -2, null);
        } finally {
            try { ar.stop(); } catch (Throwable ignored) {}
            ar.release();
            if (listen == l) listen = null;
        }
    }
}

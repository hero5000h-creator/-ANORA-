package app.anora.quran;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;

/**
 * The vowel check (tajweed-v2) in a process of its own (":harakat", see the manifest), apart from the word
 * recogniser (":qasr"): if the vowel model ever fails natively on some phone, only the vowel check stops and the
 * follow-along keeps listening with the app's own recogniser, offline.
 *
 * Message from the app: HEAR (arg1: phrase number, data: "path" of the phrase's 16-bit audio, "n" samples,
 * replyTo: where to send). Message back: QasrService.VOWELS (arg1: phrase number, data: sym, prob).
 * The audio goes by file: a phrase of up to 28 s is too big for one Binder message.
 */
public class HarakatService extends Service {

    static final int HEAR = 21;
    private static final String TAG = "qasr";

    private static HarakatAsr model;
    private static boolean failed = false;
    private HandlerThread work;
    private Messenger inbox;

    @Override
    public void onCreate() {
        super.onCreate();
        work = new HandlerThread("anora-vowels", Process.THREAD_PRIORITY_BACKGROUND);
        work.start();
        inbox = new Messenger(new Handler(work.getLooper(), m -> {
            if (m.what != HEAR) return false;
            hear(m.arg1, m.getData(), m.replyTo);
            return true;
        }));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return inbox.getBinder();
    }

    @Override
    public void onDestroy() {
        if (work != null) work.quitSafely();
        super.onDestroy();
    }

    private static synchronized HarakatAsr model(android.content.Context ctx) {
        if (model != null || failed) return model;
        try {
            model = new HarakatAsr(QasrService.copied(ctx, "qasr/harakat.onnx", "harakat-1.onnx"), 1);
        } catch (Throwable e) {
            Log.e(TAG, "vowel model failed", e);
            failed = true;
        }
        return model;
    }

    /** Work thread: read the phrase, hear its vowels, answer. A newer phrase waiting makes this one moot. */
    private void hear(int seq, Bundle d, Messenger to) {
        if (d == null || to == null) return;
        String path = d.getString("path");
        int n = d.getInt("n", 0);
        if (path == null || n <= 0) return;
        HarakatAsr h = model(this);
        if (h == null) return;
        float[] x = new float[n];
        File f = new File(path);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            for (int i = 0; i < n; i++) {
                int lo = in.read(), hi = in.read();
                if (lo < 0 || hi < 0) { n = i; break; }
                x[i] = (short) ((hi << 8) | lo) / 32768f;
            }
        } catch (Throwable e) {
            Log.e(TAG, "phrase audio", e);
            return;
        } finally {
            f.delete();
        }
        try {
            HarakatAsr.Heard r = h.hear(x, n);
            Message m = Message.obtain(null, QasrService.VOWELS, seq, 0);
            Bundle b = new Bundle();
            b.putStringArray("sym", r.sym);
            b.putFloatArray("prob", r.prob);
            m.setData(b);
            to.send(m);
        } catch (RemoteException ignored) {
        } catch (Throwable e) {
            Log.e(TAG, "vowels failed", e);
        }
    }
}

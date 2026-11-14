package app.anora.quran;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;

/**
 * Listens while the user recites (from memory, or following the page).
 *
 * First choice: the app's own Quran recogniser (quran-asr-tiny, in assets/qasr), which runs on the phone with
 * no internet and knows recitation (madd, ghunna, waqf) far better than a general Arabic recogniser. It emits the
 * same "heard" events, with engine "q": the bare Uthmani letters of each word, given out word by word.
 *
 * Otherwise (model missing, or the phone cannot run it): The Android WebView has no speech recognition of its own,
 * so this uses the phone's recogniser (Arabic) and keeps restarting it after each pause until stopped.
 * Emits "heard" {text, final}: partial text while speaking, then the final text of each phrase.
 */
@CapacitorPlugin(name = "Speech", permissions = { @Permission(alias = "mic", strings = { Manifest.permission.RECORD_AUDIO }) })
public class SpeechPlugin extends Plugin {

    private SpeechRecognizer rec;
    private boolean running = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The next hidden words of the page, in plain spelling: the recogniser is asked to lean towards them. */
    private ArrayList<String> bias = new ArrayList<>();
    /** Listen on the phone itself, with no internet: set after a network failure (or asked for by the page);
     *  needs Google's Arabic offline speech pack, which Android 13+ is asked to download when it is missing. */
    private boolean offline = false;
    private boolean askedModel = false;
    /** Android 13+: the phone's own Arabic recogniser, in one continuous (segmented) session. It never stops between
     *  phrases, so nothing is lost while a new session starts, and it needs no internet. Chosen when the Arabic
     *  on-device pack is installed; otherwise the usual recogniser with a restart after each phrase. */
    private boolean device = false;
    private String devLang = "ar-SA";
    private int devFails = 0;
    /** follow-along reading: quicker phrase ends (the page follows each word, it does not wait for whole phrases) */
    private boolean quick = false;

    /* ---- the app's own Quran recogniser, in its own process (QasrService) ---- */
    private static boolean qasrFailed = false;
    private boolean qOn = false;
    private PluginCall qCall;
    private Messenger svc;
    private boolean bound = false;
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::fromQasr));
    private final Runnable unbindLater = this::unbindQasr;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            try {
                b.linkToDeath(() -> main.post(SpeechPlugin.this::qasrDied), 0);
            } catch (RemoteException e) {
                qasrDied();
                return;
            }
            svc = new Messenger(b);
            if (qOn) qSend(QasrService.START);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    /** The recogniser's process ended on its own (it can fail natively on some processor): never try it again
     *  with this app version, and carry on listening with the phone's recogniser. */
    private void qasrDied() {
        svc = null;
        if (bound) {
            try { getContext().unbindService(conn); } catch (Throwable ignored) {}
            bound = false;
        }
        if (qasrFailed) return;
        qasrFailed = true;
        prefs().edit().putLong("qasrDead", appVersion()).apply();
        // Android records why the process ended a moment later: kept, and shown to the reader
        main.postDelayed(() -> {
            String why = exitReason();
            prefs().edit().putString("qasrWhy", why).apply();
            fellBack(why);
        }, 1200);
        if (!qOn) return;
        qOn = false;
        PluginCall c = qCall;
        qCall = null;
        googleBegin(c);
    }

    /** Tells the page that the phone's recogniser listens in place of the own one, and why (shown once). */
    private void fellBack(String why) {
        JSObject o = new JSObject();
        o.put("code", -3);
        o.put("why", why == null ? "" : why);
        notifyListeners("error", o);
    }

    /** Why the recogniser's process ended last (Android 11+), e.g. "crash_native SIGSEGV". */
    private String exitReason() {
        if (Build.VERSION.SDK_INT < 30) return "";
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(getContext().getPackageName(), 0, 16)) {
                if (e.getProcessName() == null || !e.getProcessName().endsWith(":qasr")) continue;
                String r;
                switch (e.getReason()) {
                    case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: r = "crash_native"; break;
                    case android.app.ApplicationExitInfo.REASON_CRASH: r = "crash"; break;
                    case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: r = "low_memory"; break;
                    case android.app.ApplicationExitInfo.REASON_SIGNALED: r = "signaled"; break;
                    case android.app.ApplicationExitInfo.REASON_ANR: r = "anr"; break;
                    case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: r = "excessive"; break;
                    default: r = "reason" + e.getReason();
                }
                String d = e.getDescription();
                return r + " " + e.getStatus() + (d != null && !d.isEmpty() ? " " + d : "") + " " + Build.MODEL;
            }
        } catch (Throwable ignored) {}
        return Build.MODEL;
    }

    private android.content.SharedPreferences prefs() {
        return getContext().getSharedPreferences("speech", Context.MODE_PRIVATE);
    }

    private long appVersion() {
        try {
            android.content.pm.PackageInfo p = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    @Override
    public void load() {
        // the recogniser failed on this phone with this app version: a new version tries again
        if (prefs().getLong("qasrDead", -1) == appVersion()) qasrFailed = true;
    }

    private void qSend(int what) {
        Messenger s = svc;
        if (s == null) return;
        Message m = Message.obtain(null, what);
        m.replyTo = inbox;
        try {
            s.send(m);
        } catch (RemoteException e) {
            qasrDied();
        }
    }

    private boolean fromQasr(Message m) {
        switch (m.what) {
            case QasrService.READY: {
                PluginCall c = qCall;
                qCall = null;
                if (m.arg1 == 1) {
                    if (c != null) {
                        JSObject ok = new JSObject();
                        ok.put("device", true);
                        ok.put("quran", true);
                        c.resolve(ok);
                    }
                } else if (m.arg1 == 0) {
                    qasrFailed = true;
                    qOn = false;
                    unbindQasr();
                    googleBegin(c);
                    String why = m.getData() == null ? "" : m.getData().getString("why", "");
                    fellBack("load " + why + " " + Build.MODEL);
                } else {
                    qOn = false;
                    if (c != null) c.reject("mic", "mic");
                    else emitError(-2);
                }
                return true;
            }
            case QasrService.HEARD: {
                if (!qOn) return true;
                Bundle b = m.getData();
                JSObject o = new JSObject();
                o.put("text", b.getString("text", ""));
                o.put("final", b.getBoolean("fin", false));
                o.put("engine", "q");
                notifyListeners("heard", o);
                return true;
            }
            case QasrService.ERROR:
                if (!qOn) return true;
                qOn = false;
                emitError(m.arg1);
                return true;
            default:
                return false;
        }
    }

    /** Main thread. */
    private void qStart(PluginCall call) {
        main.removeCallbacks(unbindLater);
        PluginCall old = qCall;
        if (old != null && old != call) old.resolve(new JSObject());
        qCall = call;
        qOn = true;
        if (svc != null) {
            qSend(QasrService.START);
            return;
        }
        if (bound) return;   // connecting: START goes out once connected
        boolean ok;
        try {
            ok = getContext().bindService(new Intent(getContext(), QasrService.class), conn,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        } catch (Throwable e) {
            ok = false;
        }
        if (ok) {
            bound = true;
        } else {
            try { getContext().unbindService(conn); } catch (Throwable ignored) {}
            qasrFailed = true;
            qOn = false;
            qCall = null;
            googleBegin(call);
        }
    }

    /** Main thread. The process stays a minute after stopping, so starting again is instant. */
    private void qStop() {
        boolean was = qOn || qCall != null;
        qOn = false;
        PluginCall c = qCall;
        qCall = null;
        if (c != null) c.resolve(new JSObject());
        if (was) qSend(QasrService.STOP);
        main.removeCallbacks(unbindLater);
        if (bound) main.postDelayed(unbindLater, 60000);
    }

    private void unbindQasr() {
        main.removeCallbacks(unbindLater);
        if (!bound) return;
        bound = false;
        svc = null;
        try { getContext().unbindService(conn); } catch (Throwable ignored) {}
    }

    /** The reader starts again from another word (a tap on the page): forget what was heard so far. */
    @PluginMethod
    public void reset(PluginCall call) {
        main.post(() -> qSend(QasrService.RESET));
        call.resolve();
    }
    private void takeBias(PluginCall call) {
        JSArray a = call.getArray("words", null);
        if (a == null) return;
        ArrayList<String> b = new ArrayList<>();
        for (int k = 0; k < a.length() && b.size() < 60; k++) {
            String w = a.optString(k, "");
            if (!w.isEmpty()) b.add(w);
        }
        bias = b;
    }

    /** New words to lean towards; used from the next phrase on (the recogniser restarts after each pause). */
    @PluginMethod
    public void bias(PluginCall call) {
        takeBias(call);
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        takeBias(call);
        offline = call.getBoolean("offline", false);
        quick = call.getBoolean("quick", false);
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micAnswered");
            return;
        }
        begin(call);
    }

    @PermissionCallback
    private void micAnswered(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) begin(call);
        else call.reject("denied", "denied");
    }

    private void begin(PluginCall call) {
        if (!call.getBoolean("google", false) && !qasrFailed) {
            main.post(() -> {
                if (qasrFailed) googleBegin(call);
                else qStart(call);
            });
            return;
        }
        googleBegin(call);
    }

    /** call may be null: the own recogniser stopped mid-way and this one carries on in its place. */
    private void googleBegin(PluginCall call) {
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            if (call != null) call.reject("unavailable", "unavailable");
            else emitError(-2);
            return;
        }
        main.post(() -> {
            running = true;
            pickDevice(() -> {
                listen();
                JSObject o = new JSObject();
                o.put("device", device);
                if (call != null) call.resolve(o);
            });
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            qStop();
            running = false;
            destroy();
            call.resolve();
        });
    }

    @Override
    protected void handleOnDestroy() {
        qStop();
        unbindQasr();
        running = false;
        destroy();
    }

    private void destroy() {
        if (rec != null) {
            try {
                rec.cancel();
                rec.destroy();
            } catch (Exception ignored) {}
            rec = null;
        }
    }

    /** Is Arabic installed for the on-device recogniser? Asks Android to fetch it once when it can be but is not. */
    private void pickDevice(Runnable then) {
        device = false;
        if (Build.VERSION.SDK_INT < 33 || devFails >= 2) { then.run(); return; }
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext())) { then.run(); return; }
            final SpeechRecognizer probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext());
            final Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
            final boolean[] done = { false };
            final Runnable finish = () -> {
                if (done[0]) return;
                done[0] = true;
                try { probe.destroy(); } catch (Exception ignored) {}
                then.run();
            };
            probe.checkRecognitionSupport(i, getContext().getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport r) {
                    String tag = arabic(r.getInstalledOnDeviceLanguages());
                    if (tag != null) {
                        device = true;
                        devLang = tag;
                    } else if (!askedModel && arabic(r.getSupportedOnDeviceLanguages()) != null) {
                        askedModel = true;
                        try { probe.triggerModelDownload(i); } catch (Exception ignored) {}
                    }
                    main.post(finish);
                }

                @Override
                public void onError(int e) {
                    main.post(finish);
                }
            });
            // a recogniser that never answers must not hold the start
            main.postDelayed(finish, 1500);
        } catch (Exception e) {
            then.run();
        }
    }

    private static String arabic(List<String> tags) {
        if (tags == null) return null;
        String best = null;
        for (String t : tags) {
            if (t == null || !t.toLowerCase().startsWith("ar")) continue;
            if (t.equalsIgnoreCase("ar-SA")) return t;
            if (best == null) best = t;
        }
        return best;
    }

    private void listen() {
        if (!running) return;
        if (rec == null) {
            rec = device && Build.VERSION.SDK_INT >= 31
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext())
                : SpeechRecognizer.createSpeechRecognizer(getContext());
            rec.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, device ? devLang : "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // several guesses per phrase: the page keeps the one that follows the mushaf furthest
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        if (Build.VERSION.SDK_INT >= 33 && !bias.isEmpty()) i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
        if (device && Build.VERSION.SDK_INT >= 33) {
            // one session for the whole reading, cut into segments at each short breath: each segment's text comes
            // at once and listening goes on with no gap
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, quick ? 700 : 1200);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        } else {
            // recitation has long breaths between ayat: wait longer before ending a phrase (follow-along: shorter,
            // the words are matched from the partial text anyway)
            int sil = quick ? 1500 : 2500;
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, sil);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, sil);
            if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        try {
            rec.startListening(i);
        } catch (Exception e) {
            emitError(-1);
        }
    }

    private void again(long ms) {
        main.postDelayed(this::listen, ms);
    }

    private void emit(Bundle b, boolean fin) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty()) return;
        JSObject o = new JSObject();
        o.put("text", r.get(0));
        o.put("final", fin);
        if (fin) {
            JSArray alts = new JSArray();
            for (String x : r) alts.put(x);
            o.put("alts", alts);
        }
        notifyListeners("heard", o);
    }

    private void emitError(int code) {
        JSObject o = new JSObject();
        o.put("code", code);
        notifyListeners("error", o);
    }

    private class Listener implements RecognitionListener {

        @Override
        public void onResults(Bundle b) {
            emit(b, true);
            again(device ? 20 : 60);
        }

        /** a breath inside the continuous session: the phrase is settled, listening never stopped */
        @Override
        public void onSegmentResults(Bundle b) {
            devFails = 0;
            emit(b, true);
        }

        @Override
        public void onEndOfSegmentedSession() {
            if (running) again(20);
        }

        @Override
        public void onPartialResults(Bundle b) {
            emit(b, false);
        }

        @Override
        public void onError(int e) {
            if (!running) return;
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                running = false;
                emitError(e);
                return;
            }
            // the phone's own recogniser failing over and over: back to the usual one
            if (device && e != SpeechRecognizer.ERROR_NO_MATCH && e != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && ++devFails >= 2) {
                device = false;
                destroy();
                emitError(101);
                again(150);
                return;
            }
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                destroy();
                again(300);
                return;
            }
            boolean net = e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_SERVER
                || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            // no internet: carry on with the phone's own recogniser instead of failing
            if (net && !offline) {
                offline = true;
                emitError(100);
                again(150);
                return;
            }
            // offline and the Arabic pack is not on the phone: ask Android to fetch it (13+) and tell the page
            if (Build.VERSION.SDK_INT >= 31 && (e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) {
                if (Build.VERSION.SDK_INT >= 33 && !askedModel && rec != null) {
                    askedModel = true;
                    try {
                        Intent d = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        d.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
                        rec.triggerModelDownload(d);
                    } catch (Exception ignored) {}
                }
                emitError(e);
                offline = false;
                again(3000);
                return;
            }
            // no match / speech timeout / network blips: keep listening
            again(net ? 1200 : 80);
            if (net) emitError(e);
        }

        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int t, Bundle b) {}
    }
}

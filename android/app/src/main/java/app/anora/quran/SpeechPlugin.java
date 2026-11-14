package app.anora.quran;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
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

/**
 * Listens while the user recites from memory. The Android WebView has no speech recognition of its own,
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
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            call.reject("unavailable", "unavailable");
            return;
        }
        main.post(() -> {
            running = true;
            listen();
            call.resolve();
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            running = false;
            destroy();
            call.resolve();
        });
    }

    @Override
    protected void handleOnDestroy() {
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

    private void listen() {
        if (!running) return;
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(getContext());
            rec.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // several guesses per phrase: the page keeps the one that follows the mushaf furthest
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        if (Build.VERSION.SDK_INT >= 33 && !bias.isEmpty()) i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
        // recitation has long breaths between ayat: wait longer before ending a phrase
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
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
            again(60);
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

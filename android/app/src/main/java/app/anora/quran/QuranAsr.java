package app.anora.quran;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * On-device Quran recitation recogniser: quran-asr-tiny (Conformer-CTC, 16M parameters, MIT licence), run with
 * ONNX Runtime. Audio (16 kHz mono, samples in [-1, 1]) becomes 80-bin Kaldi log-mel features, the model gives
 * letter probabilities per 40 ms, and greedy CTC turns them into bare Uthmani letters with spaces between words
 * (no harakat; ة→ه, ى/ئ→ي, ؤ→و, alif forms→ا). Plain Java (no Android classes) so it can be tested on a desktop JVM.
 */
public final class QuranAsr implements AutoCloseable {

    public static final int RATE = 16000;
    private static final int SHIFT = 160, LENGTH = 400, FFT = 512, BINS = 80;
    private static final float EPS = 1.1920929e-07f;
    /** blank, 29 letters, word separator: the model's tokens.txt */
    private static final String LETTERS = "ءابتثجحخدذرزسشصضطظعغفقكلمنهوي";
    private static final int BLANK = 0, WORD = 30;

    private static final float[] WINDOW = new float[LENGTH];
    private static final float[][] BANKS = new float[BINS][FFT / 2];
    private static final int[] BANK_LO = new int[BINS], BANK_HI = new int[BINS];
    private static final double[] COS = new double[FFT / 2], SIN = new double[FFT / 2];
    private static final int[] REV = new int[FFT];

    static {
        for (int i = 0; i < LENGTH; i++) WINDOW[i] = (float) Math.pow(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (LENGTH - 1)), 0.85);
        double width = (double) RATE / FFT, lo = mel(20.0), hi = mel(RATE / 2.0 - 400.0), delta = (hi - lo) / (BINS + 1);
        for (int b = 0; b < BINS; b++) {
            double left = lo + b * delta, center = lo + (b + 1) * delta, right = lo + (b + 2) * delta;
            BANK_LO[b] = FFT / 2;
            BANK_HI[b] = 0;
            for (int i = 0; i < FFT / 2; i++) {
                double m = mel(width * i);
                if (left < m && m < right) {
                    BANKS[b][i] = (float) (m <= center ? (m - left) / (center - left) : (right - m) / (right - center));
                    BANK_LO[b] = Math.min(BANK_LO[b], i);
                    BANK_HI[b] = Math.max(BANK_HI[b], i + 1);
                }
            }
        }
        for (int i = 0; i < FFT / 2; i++) {
            COS[i] = Math.cos(2 * Math.PI * i / FFT);
            SIN[i] = -Math.sin(2 * Math.PI * i / FFT);
        }
        int bits = Integer.numberOfTrailingZeros(FFT);
        for (int i = 0; i < FFT; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }

    private static double mel(double f) {
        return 1127.0 * Math.log(1.0 + f / 700.0);
    }

    /** Kaldi fbank, exactly as the model was trained: 25 ms povey window, 10 ms shift, edges reflected. Returns [frames*80]. */
    public static float[] fbank(float[] wave, int n) {
        int t = (n + SHIFT / 2) / SHIFT;
        float[] out = new float[t * BINS];
        float[] fr = new float[LENGTH];
        double[] re = new double[FFT], im = new double[FFT];
        for (int f = 0; f < t; f++) {
            int start = f * SHIFT + SHIFT / 2 - LENGTH / 2;
            double mean = 0;
            for (int k = 0; k < LENGTH; k++) {
                int i = start + k;
                if (i < 0) i = -i - 1;
                if (i >= n) i = 2 * n - 1 - i;
                if (i < 0) i = 0;
                if (i >= n) i = n - 1;
                fr[k] = n > 0 ? wave[i] : 0f;
                mean += fr[k];
            }
            float m = (float) (mean / LENGTH);
            for (int k = 0; k < LENGTH; k++) fr[k] -= m;
            for (int k = LENGTH - 1; k > 0; k--) fr[k] -= 0.97f * fr[k - 1];
            fr[0] *= 1 - 0.97f;
            for (int k = 0; k < FFT; k++) {
                re[REV[k]] = k < LENGTH ? fr[k] * WINDOW[k] : 0;
                im[REV[k]] = 0;
            }
            for (int size = 2; size <= FFT; size <<= 1) {
                int half = size >> 1, step = FFT / size;
                for (int s = 0; s < FFT; s += size) {
                    for (int j = 0; j < half; j++) {
                        double c = COS[j * step], sn = SIN[j * step];
                        int a = s + j, b = a + half;
                        double tr = re[b] * c - im[b] * sn, ti = re[b] * sn + im[b] * c;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
            for (int b = 0; b < BINS; b++) {
                double e = 0;
                float[] bank = BANKS[b];
                for (int i = BANK_LO[b]; i < BANK_HI[b]; i++) e += bank[i] * (re[i] * re[i] + im[i] * im[i]);
                out[f * BINS + b] = (float) Math.log(Math.max((float) e, EPS));
            }
        }
        return out;
    }

    private final OrtEnvironment env;
    private final OrtSession session;

    public QuranAsr(byte[] model, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(model, options(threads));
    }

    /** from a model file (the phone copies it out of the APK once, so it is not held twice in memory) */
    public QuranAsr(String path, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(path, options(threads));
    }

    private static OrtSession.SessionOptions options(int threads) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        return o;
    }

    /** Bare Uthmani letters of what was recited in wave[0..n), words separated by single spaces. */
    public String decode(float[] wave, int n) throws Exception {
        if (n < LENGTH) return "";
        float[] feats = fbank(wave, n);
        int t = feats.length / BINS;
        Map<String, OnnxTensor> in = new HashMap<>();
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[] { 1, t, BINS });
             OnnxTensor xl = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] { t }), new long[] { 1 })) {
            in.put("x", x);
            in.put("x_lens", xl);
            try (OrtSession.Result r = session.run(in)) {
                float[][][] lp = (float[][][]) r.get(0).getValue();
                int len = lp[0].length;
                Object lens = r.get(1).getValue();
                if (lens instanceof long[]) len = (int) Math.min(len, ((long[]) lens)[0]);
                StringBuilder sb = new StringBuilder();
                int prev = -1;
                for (int f = 0; f < len; f++) {
                    float[] p = lp[0][f];
                    int best = 0;
                    for (int k = 1; k < p.length; k++) if (p[k] > p[best]) best = k;
                    if (best != prev && best != BLANK) {
                        if (best == WORD) {
                            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                        } else if (best - 1 < LETTERS.length()) sb.append(LETTERS.charAt(best - 1));
                    }
                    prev = best;
                }
                return sb.toString().trim();
            }
        }
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignored) {}
    }
}

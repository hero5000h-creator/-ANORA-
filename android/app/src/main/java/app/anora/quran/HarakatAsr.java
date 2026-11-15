package app.anora.quran;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Hears how a recited phrase was pronounced, vowels included: tajweed-v2 (HisnLabs, Apache-2.0; a Whisper-base
 * encoder distilled from the Muaalem model) writes the audio in the Quran phonetic script (letters with fatha,
 * damma, kasra, long vowels and ghunnah marks), one symbol at a time with its probability. The page compares that
 * with how the words must sound, to find a vowel said wrong. Plain Java (no Android classes): tested on a desktop JVM.
 */
public final class HarakatAsr implements AutoCloseable {

    public static final int RATE = 16000;
    private static final int FFT = 400, HOP = 160, BINS = FFT / 2 + 1, MELS = 80, MAX_FRAMES = 3000;
    /** the training clips start and end with 0.4 s of silence */
    private static final int LEAD = RATE * 2 / 5;
    /** blank, then the model's phonemes (config.json) */
    static final String[] PH = { "ء", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش", "ص", "ض", "ط", "ظ", "ع",
        "غ", "ف", "ق", "ك", "ل", "م", "ن", "ه", "و", "ي", "ا", "ۦ", "ۥ", "َ", "ُ", "ِ", "۪", "ـ", "ٲ", "ڇ", "ں", "۾", "ۜ", "ؙ" };

    private static final float[] WINDOW = new float[FFT];
    private static final float[][] MEL = new float[MELS][BINS];
    private static final int[] MEL_LO = new int[MELS], MEL_HI = new int[MELS];
    private static final float[] COS = new float[FFT], SIN = new float[FFT];

    static {
        for (int i = 0; i < FFT; i++) {
            WINDOW[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT));   // periodic Hann
            COS[i] = (float) Math.cos(2 * Math.PI * i / FFT);
            SIN[i] = (float) Math.sin(2 * Math.PI * i / FFT);
        }
        // Whisper's (librosa's) Slaney mel filters, 0 to 8 kHz
        double maxMel = 45.245640471924965, fsp = 200.0 / 3, minLogHz = 1000.0, minLogMel = minLogHz / fsp, step = Math.log(6.4) / 27.0;
        double[] f = new double[MELS + 2];
        for (int i = 0; i < MELS + 2; i++) {
            double m = maxMel * i / (MELS + 1);
            f[i] = m >= minLogMel ? minLogHz * Math.exp(step * (m - minLogMel)) : fsp * m;
        }
        for (int b = 0; b < MELS; b++) {
            double enorm = 2.0 / (f[b + 2] - f[b]);
            MEL_LO[b] = BINS;
            MEL_HI[b] = 0;
            for (int k = 0; k < BINS; k++) {
                double hz = (double) k * RATE / FFT;
                double lower = (hz - f[b]) / (f[b + 1] - f[b]), upper = (f[b + 2] - hz) / (f[b + 2] - f[b + 1]);
                double w = Math.max(0, Math.min(lower, upper)) * enorm;
                if (w > 0) {
                    MEL[b][k] = (float) w;
                    MEL_LO[b] = Math.min(MEL_LO[b], k);
                    MEL_HI[b] = Math.max(MEL_HI[b], k + 1);
                }
            }
        }
    }

    /** Whisper's 80-bin log-mel of x[0..n) (centred frames, edges reflected), as [80][frames] row by row. */
    public static float[] mel(float[] x, int n, int[] framesOut) {
        int frames = n / HOP;                       // Whisper drops the last centred frame
        float[] spec = new float[MELS * frames];
        float[] fr = new float[FFT];
        double[] pow = new double[BINS];
        float top = -Float.MAX_VALUE;
        for (int t = 0; t < frames; t++) {
            int start = t * HOP - FFT / 2;
            for (int i = 0; i < FFT; i++) {
                int j = start + i;
                if (j < 0) j = -j;
                if (j >= n) j = 2 * (n - 1) - j;
                if (j < 0) j = 0;
                fr[i] = (j < n ? x[j] : 0f) * WINDOW[i];
            }
            for (int k = 0; k < BINS; k++) {
                double re = 0, im = 0;
                int p = 0;
                for (int i = 0; i < FFT; i++) {
                    re += fr[i] * COS[p];
                    im -= fr[i] * SIN[p];
                    p += k;
                    if (p >= FFT) p -= FFT;
                }
                pow[k] = re * re + im * im;
            }
            for (int b = 0; b < MELS; b++) {
                double e = 0;
                for (int k = MEL_LO[b]; k < MEL_HI[b]; k++) e += MEL[b][k] * pow[k];
                float v = (float) Math.log10(Math.max(e, 1e-10));
                spec[b * frames + t] = v;
                if (v > top) top = v;
            }
        }
        for (int i = 0; i < spec.length; i++) spec[i] = (Math.max(spec[i], top - 8f) + 4f) / 4f;
        framesOut[0] = frames;
        return spec;
    }

    /** What was heard: the phonetic script, one string per symbol, and how sure the model was of each. */
    public static final class Heard {
        public final String[] sym;
        public final float[] prob;

        Heard(String[] sym, float[] prob) {
            this.sym = sym;
            this.prob = prob;
        }

        public String text() {
            StringBuilder b = new StringBuilder();
            for (String s : sym) b.append(s);
            return b.toString();
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;

    public HarakatAsr(String path, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        session = env.createSession(path, o);
    }

    /** One phrase (at most about 29 s; longer is cut) of 16 kHz audio in [-1, 1]. */
    public Heard hear(float[] wave, int n) throws Exception {
        int total = Math.min(n, (MAX_FRAMES - 2) * HOP - 2 * LEAD);
        float[] x = new float[total + 2 * LEAD];
        System.arraycopy(wave, 0, x, LEAD, total);
        int[] fo = new int[1];
        float[] full = mel(x, x.length, fo);
        int frames = Math.min(MAX_FRAMES, fo[0] / 2 * 2);
        float[] m = full;
        if (frames != fo[0]) {
            m = new float[MELS * frames];
            for (int b = 0; b < MELS; b++) System.arraycopy(full, b * fo[0], m, b * frames, frames);
        }
        Map<String, OnnxTensor> in = new HashMap<>();
        try (OnnxTensor mel = OnnxTensor.createTensor(env, FloatBuffer.wrap(m), new long[] { 1, MELS, frames });
             OnnxTensor riw = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] { 0 }), new long[] { 1 })) {
            in.put("mel", mel);
            in.put("riwaya", riw);
            try (OrtSession.Result r = session.run(in)) {
                float[][] lp = ((float[][][]) r.get(0).getValue())[0];
                java.util.ArrayList<String> sym = new java.util.ArrayList<>();
                java.util.ArrayList<Float> pr = new java.util.ArrayList<>();
                int prev = 0;
                for (float[] p : lp) {
                    int best = 0;
                    for (int k = 1; k < p.length; k++) if (p[k] > p[best]) best = k;
                    if (best != prev && best != 0 && best - 1 < PH.length) {
                        sym.add(PH[best - 1]);
                        pr.add((float) Math.exp(p[best]));
                    }
                    prev = best;
                }
                float[] pa = new float[pr.size()];
                for (int i = 0; i < pa.length; i++) pa[i] = pr.get(i);
                return new Heard(sym.toArray(new String[0]), pa);
            }
        }
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignored) {}
    }
}

package app.anora.quran;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a live stream of 16 kHz audio into recited words with the on-device Quran model.
 *
 * The model hears whole stretches of audio (it is not a streaming model), and a short piece cut out of a
 * recitation is heard badly, so it is always given the last WIN seconds of what was said and decoded again every
 * few hundred milliseconds. Each decode is lined up against the words already given out, and only the words after
 * them are new. A new word is given out ("final") once the next word has begun, or the reader pauses, so it is
 * whole; the word still being said goes out as "partial", which the page uses only when it is exactly the word due.
 * Nothing is decoded while the reader is silent. Plain Java: the phone feeds it from the microphone, a test from a file.
 */
public final class QuranListen {

    public interface Out {
        void heard(String text, boolean fin);
    }

    private static final int RATE = QuranAsr.RATE;
    private static final int HOP = RATE / 100;              // 10 ms energy frames
    private static final int WIN = RATE * 6;                // the model always hears the last 6 s
    private static final int PAUSE = 50;                    // 500 ms of quiet: hear the phrase once more
    private static final int STOP = 90;                     // 900 ms of quiet: the phrase is over, all of it is given out

    private final QuranAsr asr;
    private final Out out;
    private final float[] ring = new float[WIN];
    private final float[] win = new float[WIN];
    private long total = 0;                                 // samples heard since start
    private long lastDecode = 0, lastVoice = -1;
    private int step = RATE * 35 / 100;                     // grows if the phone decodes slowly
    private int quiet = 0;
    private double floor = 0.003;
    private final float[] hop = new float[HOP];
    private int hopN = 0;
    private final List<String> said = new ArrayList<>();   // words given out so far (the last 24 are kept)
    private String pending = "";                            // the word still being said
    private String[] prevNew = new String[0];               // the new words of the previous decode
    public long decodeMs = 0;
    public static boolean DEBUG = false;

    public QuranListen(QuranAsr asr, Out out) {
        this.asr = asr;
        this.out = out;
    }

    /** The reader starts over somewhere else (a tap on a word): forget what was heard. */
    public void reset() {
        said.clear();
        pending = "";
        prevNew = new String[0];
        Arrays.fill(ring, 0f);
    }

    /** Feed samples in [-1, 1]. Decoding happens on the caller's thread. */
    public void feed(float[] x, int len) throws Exception {
        for (int i = 0; i < len; i++) {
            ring[(int) (total % WIN)] = x[i];
            total++;
            hop[hopN++] = x[i];
            if (hopN == HOP) {
                frame();
                hopN = 0;
            }
        }
    }

    private void frame() throws Exception {
        double e = 0;
        for (float v : hop) e += v * v;
        double rms = Math.sqrt(e / HOP);
        // the room's own level: followed quickly downwards, slowly upwards
        floor = rms < floor ? floor * 0.9 + rms * 0.1 : floor * 0.998 + rms * 0.002;
        boolean loud = rms > Math.max(floor * 2.5, 0.003);
        if (loud) {
            quiet = 0;
            lastVoice = total;
        } else quiet++;
        if ((quiet == PAUSE || quiet == STOP) && lastVoice >= 0) {
            // a pause: hear the end of the phrase again; after a longer one its last word is given out too
            decode(quiet == STOP);
            return;
        }
        if (lastVoice >= 0 && quiet < PAUSE && total - lastDecode >= step) decode(false);
    }

    private void decode(boolean pause) throws Exception {
        lastDecode = total;
        int n = (int) Math.min(total, WIN);
        long start = total - n;
        for (int i = 0; i < n; i++) win[i] = ring[(int) ((start + i) % WIN)];
        long t0 = System.nanoTime();
        String text = asr.decode(win, n);
        decodeMs = (System.nanoTime() - t0) / 1_000_000;
        // a phone that needs longer than a step to decode decodes less often, so it never falls behind
        step = (int) Math.max(RATE * 35 / 100, Math.min(RATE * 12 / 10, decodeMs * RATE / 1000 * 3 / 2));
        if (DEBUG) System.out.printf("   dec %.2f-%.2f%s: %s%n", start / (double) RATE, total / (double) RATE, pause ? " P" : "", text);
        if (text.isEmpty()) return;
        String[] h = text.split(" ");
        // the window's first word is cut in half only when the reader was speaking right where the window starts
        boolean cut = false;
        if (start > 0) {
            double e = 0;
            int k = Math.min(n, RATE / 20);
            for (int i = 0; i < k; i++) e += win[i] * win[i];
            cut = Math.sqrt(e / Math.max(1, k)) > Math.max(floor * 2.5, 0.003);
        }
        int from = newFrom(h, cut);
        String[] now = Arrays.copyOfRange(h, Math.min(from, h.length), h.length);
        // a word is given out once two decodes in a row agree on it and it is not the last word heard (that one
        // may still be unfinished); at a pause everything heard is given out
        int agree = 0;
        if (pause) agree = now.length;
        else {
            // in the quiet after a phrase its last word is finished too, once two decodes agree on it
            int last = quiet >= PAUSE ? now.length : now.length - 1;
            while (agree < last && agree < prevNew.length && now[agree].equals(prevNew[agree])) agree++;
        }
        StringBuilder done = new StringBuilder();
        for (int k = 0; k < agree; k++) {
            if (done.length() > 0) done.append(' ');
            done.append(now[k]);
            said.add(now[k]);
        }
        while (said.size() > 24) said.remove(0);
        prevNew = Arrays.copyOfRange(now, agree, now.length);
        if (done.length() > 0) out.heard(done.toString(), true);
        // the next word not yet given out: the page lights it at once when it is exactly the word due
        String next = !pause && prevNew.length > 0 ? prevNew[0] : "";
        if (!next.isEmpty() && !next.equals(pending)) out.heard(next, false);
        pending = next;
    }

    /**
     * Where the new words start in a decode h: h is lined up against the words already given out (the start of
     * those is free, h covers only the last seconds; the first word of h may be cut in half by the window), and
     * the words of h after the last one given out are new.
     */
    private int newFrom(String[] h, boolean cut) {
        int m = said.size(), w = h.length;
        if (m == 0) return cut && w > 1 ? 1 : 0;
        double[][] d = new double[m + 1][w + 1];
        for (int i = 0; i <= m; i++) d[i][0] = 0;                       // any number of older words not in h
        for (int j = 1; j <= w; j++) d[0][j] = (j == 1 && cut ? 0.6 : 1) + d[0][j - 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= w; j++) {
                double s = d[i - 1][j - 1] + sub(said.get(i - 1), h[j - 1]);
                s = Math.min(s, d[i - 1][j] + 1);
                s = Math.min(s, d[i][j - 1] + (j == 1 && cut ? 0.6 : 1));
                d[i][j] = s;
            }
        }
        int best = 0;
        double low = d[m][0] + w * 0.45;                                 // nothing of h heard before
        for (int j = 1; j <= w; j++) {
            double c = d[m][j] + (w - j) * 0.45;
            if (c < low - 1e-9) {
                low = c;
                best = j;
            }
        }
        return best;
    }

    private static double sub(String a, String b) {
        if (a.equals(b)) return 0;
        int la = a.length(), lb = b.length();
        int[] p = new int[lb + 1], c = new int[lb + 1];
        for (int j = 0; j <= lb; j++) p[j] = j;
        for (int i = 1; i <= la; i++) {
            c[0] = i;
            for (int j = 1; j <= lb; j++) c[j] = Math.min(Math.min(c[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p;
            p = c;
            c = t;
        }
        return Math.min(1.0, 1.4 * p[lb] / Math.max(la, lb));
    }

    /** The reader stopped: give out the word still being said. */
    public void flush() throws Exception {
        if (lastVoice >= 0 && !pending.isEmpty()) decode(true);
    }
}

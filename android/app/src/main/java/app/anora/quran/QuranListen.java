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

        /** A phrase ended (the reader stopped): its audio, for the vowel check. a[0..n) is only valid during the call. */
        default void phrase(float[] a, int n) {}
    }

    private static final int RATE = QuranAsr.RATE;
    private static final int HOP = RATE / 100;              // 10 ms energy frames
    private static final int WIN = RATE * 6;                // the model always hears the last 6 s
    private static final int PAUSE = 50;                    // 500 ms of quiet: hear the phrase once more
    private static final int STOP = 90;                     // 900 ms of quiet: the phrase is over, all of it is given out
    private static final int LONG = RATE * 28;              // a phrase's audio is kept up to 28 s (the vowel model's limit)

    private final QuranAsr asr;
    private final Out out;
    private final float[] ring = new float[WIN];
    private final float[] win = new float[WIN];
    private final float[] longRing = new float[LONG];
    private final float[] phraseBuf = new float[LONG];
    private long phraseFrom = -1;                           // where the phrase being said began (total)
    private long total = 0;                                 // samples heard since start
    private long lastDecode = 0, lastVoice = -1;
    private static final int STEP = RATE * 35 / 100;        // decode every 350 ms on a quick phone
    private int step = STEP;                                // grows if the phone decodes slowly
    private int sameLast = 0;                               // decodes in a row ending on the same unfinished word
    private int quiet = 0;
    private double floor = 0.003;
    private final float[] hop = new float[HOP];
    private int hopN = 0;
    private final List<String> said = new ArrayList<>();   // words given out so far (the last 24 are kept)
    private String pending = "";                            // the word still being said
    private String[] prevNew = new String[0];               // the new words of the previous decode
    public long decodeMs = 0;
    public static boolean DEBUG = false;

    // The words due next on the page (their bare consonants), so a word that is clearly the one due is given out
    // the moment it is heard instead of waiting for a second decode to agree on it. Free decoding wavers on a long
    // madd or a slow reading, and waiting for agreement then held words back for seconds before giving out a burst.
    private volatile String[] expectNew = null;
    private String[] expect = null;
    private int ePos = 0;
    public int byExpect = 0;                                // words given out early because they were the ones due

    public QuranListen(QuranAsr asr, Out out) {
        this.asr = asr;
        this.out = out;
    }

    private volatile boolean resetAsked = false;

    /**
     * The reader starts over somewhere else (a tap on a word): forget what was heard. Safe from any thread: it is
     * carried out on the feeding thread before the next samples, never in the middle of a decode.
     */
    public void reset() {
        resetAsked = true;
    }

    /**
     * The words due next on the page, in order, from the next one due (any thread; taken before the next decode).
     * The text given out is still what was heard, never these words: they only decide when a word is given out.
     */
    public void expect(String[] words) {
        String[] k = new String[words == null ? 0 : words.length];
        int n = 0;
        for (int i = 0; i < k.length; i++) {
            String s = bare(words[i]);
            if (s.isEmpty()) continue;
            k[n++] = s;
        }
        expectNew = Arrays.copyOf(k, n);
    }

    /** The letters a word is matched by: no vowels, no alif/hamza/waw/ya (how the page lines words up too). */
    static String bare(String w) {
        StringBuilder b = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c == '\u0629') c = '\u0647';                       // ة as ه
            if (c < '\u0621' || c > '\u064A') continue;             // vowels, marks, tatweel, anything else
            if ("\u0621\u0622\u0623\u0624\u0625\u0626\u0627\u0648\u0649\u064A\u0640".indexOf(c) >= 0) continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * The decode h lined up against the page's words around the next one due (the dozen before it, already given
     * out, and those after it). Returns null when h does not follow the page (the reader is elsewhere, or the
     * decode is mostly noise): then the words are given out the free way. Otherwise {where the new words start in
     * h, how many of them to give out now, the next word due after those}. A new word is given out when it is the
     * word due (or close to it: a slip is still given out, as heard, so the page can mark it); never the last
     * word heard, which may be unfinished. A word that fits nothing between two that do
     * goes out too (the page decides what it was); two in a row stop it.
     */
    private int[] anchor(String[] h) {
        if (expect == null || expect.length == 0 || h.length == 0) return null;
        int base = Math.max(0, ePos - 12), top = Math.min(expect.length, ePos + 16), m = top - base, w = h.length;
        int due = ePos - base;
        if (due >= m) return null;
        String[] hb = new String[w];
        for (int i = 0; i < w; i++) hb[i] = bare(h[i]);
        final double GAP_H = 0.7, GAP_E = 0.9;
        double[][] d = new double[w + 1][m + 1];
        byte[][] bt = new byte[w + 1][m + 1];                    // 0 match, 1 skip a heard word, 2 skip a page word
        for (int j = 0; j <= m; j++) {
            d[0][j] = j <= due ? 0 : (j - due) * GAP_E;          // it may start anywhere up to the word due
            bt[0][j] = 2;
        }
        for (int i = 1; i <= w; i++) {
            d[i][0] = d[i - 1][0] + GAP_H;
            bt[i][0] = 1;
            for (int j = 1; j <= m; j++) {
                double c = hb[i - 1].isEmpty() ? 1 : sub(hb[i - 1], expect[base + j - 1]);
                double best = d[i - 1][j - 1] + c;
                byte b = 0;
                if (d[i - 1][j] + GAP_H < best) { best = d[i - 1][j] + GAP_H; b = 1; }
                if (d[i][j - 1] + GAP_E < best) { best = d[i][j - 1] + GAP_E; b = 2; }
                d[i][j] = best;
                bt[i][j] = b;
            }
        }
        int jEnd = 0;
        for (int j = 1; j <= m; j++) if (d[w][j] < d[w][jEnd]) jEnd = j;
        int[] at = new int[w];                                   // the page word each heard word lines up with, or -1
        double[] cost = new double[w];
        Arrays.fill(at, -1);
        for (int i = w, j = jEnd; i > 0; ) {
            if (j > 0 && bt[i][j] == 0) {
                at[i - 1] = j - 1;
                cost[i - 1] = hb[i - 1].isEmpty() ? 1 : sub(hb[i - 1], expect[base + j - 1]);
                i--;
                j--;
            } else if (j == 0 || bt[i][j] == 1) i--;
            else j--;
        }
        int good = 0, any = -1, from = -1;
        for (int i = 0; i < w; i++) {
            if (at[i] >= 0 && cost[i] <= 0.35) {
                good++;
                if (from < 0 && at[i] >= due) from = i;
            }
            if (any < 0 && at[i] >= due) any = i;
        }
        // a poor fit on the word due (a slip) still goes out, so the page can mark it, but only with a word that
        // fits after it: alone it may be the first half of the word due, which would then be given out twice
        if (from >= 0 && any >= 0 && any < from && from < w - 1) from = any;
        if (good < (w <= 2 ? 1 : Math.max(2, (w + 1) / 2))) return null;
        if (from < 0) return null;                               // nothing due heard: the free way decides
        int take = 0, next = ePos, miss = 0;
        for (int i = from; i < w; i++) {
            // the last word heard may be unfinished, and the model then guesses its ending from the Quran it knows
            // (it heard -ون where the reader went on to say -ين): it waits for the next word or the pause
            boolean fits = i < w - 1 && at[i] >= due && cost[i] <= 0.35;
            if (fits) {
                take = i - from + 1;
                next = base + at[i] + 1;
                miss = 0;
            } else if (++miss >= 2) break;
        }
        return new int[] { from, take, next };
    }

    /**
     * The page sends its list after it has taken in the words given out so far, so the list may still begin with
     * a few words given out since: the longest start of the list that is the end of what was given out is passed
     * over, or those words would be matched and given out a second time.
     */
    private int caughtUp() {
        int best = 0, m = said.size();
        for (int k = Math.min(Math.min(6, m), expect.length); k >= 1; k--) {
            boolean all = true;
            for (int i = 0; i < k && all; i++) {
                String s = bare(said.get(m - k + i));
                all = s.length() >= 2 && sub(s, expect[i]) <= 0.3;
            }
            if (all) {
                best = k;
                break;
            }
        }
        return best;
    }

    /** Words were given out: move past them in the list of words due. */
    private void passDue(String w) {
        if (expect == null) return;
        String s = bare(w);
        if (s.length() < 2) return;
        // only a close fit moves the place, and only a word or two on: a stray short word that looks like one
        // further on must not carry it past words not yet read (they would never be given out)
        for (int j = ePos; j < Math.min(expect.length, ePos + 3); j++) {
            if (sub(s, expect[j]) <= 0.3) {
                ePos = j + 1;
                return;
            }
        }
        // a word that fits none of the next few (noise, a word cut in half) does not move the place: the next
        // decode lines up with the page again, and may move on by more than a word
    }

    private void doReset() {
        resetAsked = false;
        phraseFrom = -1;
        said.clear();
        pending = "";
        prevNew = new String[0];
        Arrays.fill(ring, 0f);
    }

    /** Feed samples in [-1, 1]. Decoding happens on the caller's thread. */
    public void feed(float[] x, int len) throws Exception {
        if (resetAsked) doReset();
        for (int i = 0; i < len; i++) {
            ring[(int) (total % WIN)] = x[i];
            longRing[(int) (total % LONG)] = x[i];
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
            if (phraseFrom < 0) phraseFrom = Math.max(0, total - RATE * 3 / 10);
            quiet = 0;
            lastVoice = total;
        } else quiet++;
        if ((quiet == PAUSE || quiet == STOP) && lastVoice >= 0) {
            // a pause: hear the end of the phrase again; after a longer one its last word is given out too
            decode(quiet == STOP);
            if (quiet == STOP && phraseFrom >= 0) {
                // the phrase with a little quiet on each side; a longer one keeps its last 28 s
                long from = Math.max(phraseFrom, Math.max(0, total - LONG)), to = Math.min(total, lastVoice + RATE * 3 / 10);
                int n = (int) (to - from);
                for (int i = 0; i < n; i++) phraseBuf[i] = longRing[(int) ((from + i) % LONG)];
                phraseFrom = -1;
                if (n > RATE / 2) out.phrase(phraseBuf, n);
            }
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
        step = (int) Math.max(STEP, Math.min(RATE * 12 / 10, decodeMs * RATE / 1000 * 3 / 2));
        if (DEBUG) System.out.printf("   dec %.2f-%.2f%s: %s%n", start / (double) RATE, total / (double) RATE, pause ? " P" : "", text);
        String[] ne = expectNew;
        if (ne != null) {
            expectNew = null;
            expect = ne;
            ePos = caughtUp();
        }
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
        int[] an = pause ? null : anchor(h);
        if (DEBUG) System.out.println("      ePos " + ePos + (expect != null && ePos < expect.length ? " " + expect[ePos] : "") + " an " + (an == null ? "-" : an[0] + "," + an[1] + "," + an[2]));
        int from = an != null ? an[0] : newFrom(h, cut);
        String[] now = Arrays.copyOfRange(h, Math.min(from, h.length), h.length);
        // a word is given out once two decodes in a row agree on it and it is not the last word heard (that one
        // may still be unfinished); at a pause everything heard is given out
        int agree = 0;
        StringBuilder done = new StringBuilder();
        if (pause) agree = now.length;
        else {
            // The window moved on since the last decode (by more than a word on a slow phone): its first new
            // word may have slid out or come back cut. When this decode starts with the previous decode's
            // second or third new word, the ones before it were heard whole last time: they are given out now.
            if (now.length > 0 && prevNew.length > 1 && !now[0].equals(prevNew[0])) {
                for (int o = 1; o <= 2 && o < prevNew.length; o++) {
                    if (now[0].equals(prevNew[o]) && now[0].length() > 1) {
                        for (int k = 0; k < o; k++) {
                            if (done.length() > 0) done.append(' ');
                            done.append(prevNew[k]);
                            said.add(prevNew[k]);
                            passDue(prevNew[k]);
                        }
                        prevNew = Arrays.copyOfRange(prevNew, o, prevNew.length);
                        break;
                    }
                }
            }
            // the last word heard may be unfinished; it is given out in the quiet after a phrase, or once three
            // decodes in a row (most of a second) end on it unchanged, so a room that is never quite silent does
            // not hold the phrase's last word back for seconds
            if (now.length > 0 && now[now.length - 1].length() >= 3 && prevNew.length == now.length && now[now.length - 1].equals(prevNew[prevNew.length - 1])) sameLast++;
            else sameLast = 0;
            int last = quiet >= PAUSE || sameLast >= 2 ? now.length : now.length - 1;
            while (agree < last && agree < prevNew.length && now[agree].equals(prevNew[agree])) agree++;
            if (an != null && an[1] > agree) {
                byExpect += an[1] - agree;
                agree = an[1];
            }
        }
        for (int k = 0; k < agree; k++) {
            if (done.length() > 0) done.append(' ');
            done.append(now[k]);
            said.add(now[k]);
            if (an == null || k >= an[1]) passDue(now[k]);
        }
        if (an != null && agree >= an[1] && an[1] > 0) ePos = Math.max(ePos, an[2]);
        while (said.size() > 24) said.remove(0);
        if (agree > 0) sameLast = 0;
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
        final double CUT = 0.3;                                         // a cut first word: cheap to pass over
        double[][] d = new double[m + 1][w + 1];
        for (int i = 0; i <= m; i++) d[i][0] = 0;                       // any number of older words not in h
        for (int j = 1; j <= w; j++) d[0][j] = (j == 1 && cut ? CUT : 1) + d[0][j - 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= w; j++) {
                double s = d[i - 1][j - 1] + sub(said.get(i - 1), h[j - 1]);
                s = Math.min(s, d[i - 1][j] + 1);
                s = Math.min(s, d[i][j - 1] + (j == 1 && cut ? CUT : 1));
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

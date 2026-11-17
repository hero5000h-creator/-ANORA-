import app.anora.quran.QuranAsr;
import app.anora.quran.QuranListen;
import app.anora.quran.HarakatAsr;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

/** CI: run the own Quran recogniser on noise and a tone with the phone's ONNX Runtime version (no Android needed). */
public class Noise {
    public static void main(String[] a) throws Exception {
        for (int threads = 1; threads <= 2; threads++) {
            QuranAsr q = new QuranAsr(a[0], threads);
            QuranAsr qb = new QuranAsr(Files.readAllBytes(Paths.get(a[0])), threads);
            Random r = new Random(7);
            StringBuilder heard = new StringBuilder();
            QuranListen l = new QuranListen(q, (t, fin) -> heard.append(fin ? "F:" : "p:").append(t).append(' '));
            // follow-along: the words due next on the page (with their vowels, as the page sends them; one has none)
            l.expect(new String[] { "إِنَّ", "ٱلَّذِينَ", "كَفَرُواْ", "سَوَآءٌ", "عَلَيۡهِمۡ", "ءَأَنذَرۡتَهُمۡ", "ۖ", "" });
            float[] x = new float[1600];
            long t0 = System.nanoTime();
            for (int c = 0; c < 80; c++) {
                boolean loud = (c / 10) % 2 == 0;
                for (int i = 0; i < x.length; i++)
                    x[i] = loud ? (float) (r.nextGaussian() * 0.1 + 0.3 * Math.sin(2 * Math.PI * 220 * (c * 1600 + i) / 16000.0)) : 0f;
                l.feed(x, x.length);
            }
            l.flush();
            l.expect(null);
            float[] w = new float[16000 * 6];
            for (int i = 0; i < w.length; i++) w[i] = (float) (r.nextGaussian() * 0.2);
            String s1 = q.decode(w, w.length), s2 = qb.decode(w, w.length), s3 = q.decode(w, 300), s4 = q.decode(w, 401);
            System.out.printf("threads=%d stream %dms (last decode %dms) heard=[%s] full=[%s] same=%b short=[%s][%s]%n",
                threads, (System.nanoTime() - t0) / 1000000, l.decodeMs, heard, s1, s1.equals(s2), s3, s4);
            q.close();
            qb.close();
        }
        if (a.length > 1) {
            HarakatAsr h = new HarakatAsr(a[1], 1);
            Random r = new Random(3);
            float[] w = new float[16000 * 5];
            for (int i = 0; i < w.length; i++) w[i] = (float) (r.nextGaussian() * 0.1 + 0.3 * Math.sin(2 * Math.PI * 180 * i / 16000.0));
            long t0 = System.nanoTime();
            HarakatAsr.Heard v = h.hear(w, w.length);
            System.out.printf("vowel model %dms heard=[%s] %d symbols%n", (System.nanoTime() - t0) / 1000000, v.text(), v.sym.length);
            h.close();
        }
        System.out.println("QASR OK");
    }
}

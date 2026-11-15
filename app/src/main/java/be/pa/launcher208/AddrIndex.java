package be.pa.launcher208;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Recherche d'une destination dans les localités, rues, adresses et lieux de Belgique (fichier fabriqué par
 * outils/nav/construire.py). On tape le début des mots, dans n'importe quel ordre, sans accents :
 * « nimy 15 mons », « hopital ambroise », « gare charleroi »... Les résultats proches passent devant.
 * Aucun lien avec Android.
 */
final class AddrIndex {
    static final int PLACE = 0, STREET = 1, POI = 2;
    private static final String STOP = " de du des la le les l d a au aux et en sur sous lez het van den der op t s ";

    private final int n, nTok, nHouse;
    private final ByteBuffer typ, imp, tokBytes, text;
    private final IntBuffer lat, lon, name, sub, hStart, hNum, tokStart, postStart, post;
    private final ShortBuffer hDx, hDy;

    AddrIndex(File f) throws IOException {
        NavFile nf = new NavFile(f, "R208ADRS");
        n = nf.counts[0];
        nTok = nf.counts[1];
        nHouse = nf.counts[3];
        typ = nf.bytes(0);
        imp = nf.bytes(1);
        lat = nf.ints(2);
        lon = nf.ints(3);
        name = nf.ints(4);
        sub = nf.ints(5);
        hStart = nf.ints(6);
        hNum = nf.ints(7);
        hDx = nf.shorts(8);
        hDy = nf.shorts(9);
        tokStart = nf.ints(10);
        tokBytes = nf.bytes(11);
        postStart = nf.ints(12);
        post = nf.ints(13);
        text = nf.bytes(14);
    }

    /** Un résultat de recherche. */
    static final class Hit {
        int type;
        String name, sub;
        double lat, lon, km;
        double score;

        @Override public String toString() { return name + " — " + sub; }
    }

    /** Texte simplifié comme dans le fichier : minuscules, sans accents, lettres et chiffres seulement. */
    static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae").replace("ß", "ss").replace("ĳ", "ij");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String str(int off) {
        int len = (text.get(off) & 0xff) | ((text.get(off + 1) & 0xff) << 8);
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = text.get(off + 2 + i);
        return new String(b, StandardCharsets.UTF_8);
    }

    private int cmpTok(int t, byte[] w, boolean prefix) {
        int a = tokStart.get(t), b = tokStart.get(t + 1), la = b - a;
        int m = Math.min(la, w.length);
        for (int i = 0; i < m; i++) {
            int c = (tokBytes.get(a + i) & 0xff) - (w[i] & 0xff);
            if (c != 0) return c;
        }
        if (prefix && la >= w.length) return 0;
        return la - w.length;
    }

    /** Mots du fichier commençant par w : [premier, dernier+1). */
    private int[] range(String w) {
        byte[] wb = w.getBytes(StandardCharsets.US_ASCII);
        int lo = 0, hi = nTok;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (cmpTok(m, wb, true) < 0) lo = m + 1; else hi = m; }
        int a = lo;
        hi = nTok;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (cmpTok(m, wb, true) <= 0) lo = m + 1; else hi = m; }
        return new int[]{a, lo};
    }

    private boolean exactTok(int t, String w) {
        return cmpTok(t, w.getBytes(StandardCharsets.US_ASCII), false) == 0;
    }

    /** Entrées dont un mot commence par w (triées, sans doublon), ou null si trop nombreuses. */
    private int[] union(int[] r, int max) {
        int total = 0;
        for (int t = r[0]; t < r[1]; t++) total += postStart.get(t + 1) - postStart.get(t);
        if (total > max) return null;
        int[] u = new int[total];
        int k = 0;
        for (int t = r[0]; t < r[1]; t++) for (int i = postStart.get(t); i < postStart.get(t + 1); i++) u[k++] = post.get(i);
        Arrays.sort(u);
        int m = 0;
        for (int i = 0; i < k; i++) if (m == 0 || u[m - 1] != u[i]) u[m++] = u[i];
        return Arrays.copyOf(u, m);
    }

    private static int[] inter(int[] a, int[] b) {
        int[] o = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) i++;
            else if (a[i] > b[j]) j++;
            else { o[k++] = a[i]; i++; j++; }
        }
        return Arrays.copyOf(o, k);
    }

    /** Recherche ; (lat, lon) = position actuelle pour classer les résultats proches d'abord. */
    ArrayList<Hit> search(String query, double here, double hereLon, int max) {
        ArrayList<Hit> out = new ArrayList<>();
        String q = norm(query);
        if (q.isEmpty()) return out;
        String[] words = q.split(" ");
        ArrayList<String> alpha = new ArrayList<>();
        String number = null;
        for (String w : words) {
            if (w.isEmpty()) continue;
            boolean house = w.matches("\\d{1,4}[a-z]?") && !(w.length() == 4 && w.matches("\\d{4}"));
            if (house && number == null) number = w;
            else alpha.add(w);
        }
        // un nombre seul (ex. « 7000 ») : code postal
        if (alpha.isEmpty() && number != null) { alpha.add(number); number = null; }
        // petits mots (« de », « la »...) : pas obligatoires (« gare de mons » trouve la gare nommée « Mons »)
        ArrayList<String> must = new ArrayList<>();
        for (String w : alpha) if (!STOP.contains(" " + w + " ")) must.add(w);
        if (must.isEmpty()) must = alpha;
        int[] cand = null;
        // les mots les plus précis d'abord
        ArrayList<int[]> sets = new ArrayList<>();
        for (String w : must) {
            int[] u = union(range(w), must.size() == 1 ? 400000 : 150000);
            if (u == null) continue; // mot trop court ou trop courant : on s'en passe
            sets.add(u);
        }
        if (sets.isEmpty()) return out;
        Collections.sort(sets, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return a.length - b.length; }
        });
        for (int[] s : sets) cand = cand == null ? s : inter(cand, s);
        // un nombre qui n'est pas un numéro de maison connu peut aussi faire partie du nom (« rue du 11 novembre »)
        if (number != null) {
            int[] u = union(range(number), 150000);
            if (u != null) {
                int[] withNum = inter(cand, u);
                if (withNum.length > 0) { cand = withNum; }
            }
        }
        double kx = 111320.0 * Math.cos(Math.toRadians(here));
        // premier tri rapide (type, importance, distance), puis noms lus pour les meilleurs seulement
        int[] best = cand;
        final double[] pre = new double[cand.length];
        for (int i = 0; i < cand.length; i++) {
            int e = cand[i];
            double d = Math.hypot((lon.get(e) / 1e6 - hereLon) * kx, (lat.get(e) / 1e6 - here) * 110540.0) / 1000.0;
            pre[i] = base(e) - 7 * Math.log(1 + d) / Math.log(2);
        }
        Integer[] idx = new Integer[cand.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Double.compare(pre[b], pre[a]); }
        });
        int lim = Math.min(idx.length, 400);
        for (int r = 0; r < lim; r++) {
            int e = best[idx[r]];
            Hit h = new Hit();
            h.type = typ.get(e);
            h.name = str(name.get(e));
            h.sub = str(sub.get(e));
            h.lat = lat.get(e) / 1e6;
            h.lon = lon.get(e) / 1e6;
            h.score = pre[idx[r]];
            // les mots tapés trouvés dans le nom lui-même (pas seulement dans la ville) : bien meilleur
            // pour un lieu, sa catégorie compte aussi (« gare mons » : la gare nommée « Mons »)
            String label = h.type == POI ? h.sub.split(" · ")[0] : "";
            String nn = " " + norm(h.name + " " + label) + " ";
            int inName = 0;
            for (String w : alpha) if (nn.contains(" " + w)) inName++;
            h.score += 12 * inName + (inName == alpha.size() ? 10 : 0);
            if (!alpha.isEmpty() && nn.startsWith(" " + alpha.get(0))) h.score += 6;
            StringBuilder all = new StringBuilder();
            for (String w : alpha) all.append(all.length() == 0 ? "" : " ").append(w);
            if (nn.trim().equals(all.toString())) h.score += 10;
            // numéro de maison
            if (number != null && h.type == STREET) {
                int a = hStart.get(e), b = hStart.get(e + 1);
                for (int k = a; k < b; k++) {
                    if (str(hNum.get(k)).equalsIgnoreCase(number)) {
                        double kxe = 111320.0 * Math.cos(Math.toRadians(h.lat));
                        h.lat += hDy.get(k) / 110540.0;
                        h.lon += hDx.get(k) / kxe;
                        h.name = h.name + " " + str(hNum.get(k));
                        h.score += 15;
                        break;
                    }
                }
            }
            h.km = Math.hypot((h.lon - hereLon) * kx, (h.lat - here) * 110540.0) / 1000.0;
            out.add(h);
        }
        Collections.sort(out, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Double.compare(b.score, a.score); }
        });
        // pas deux fois la même chose (même nom, même lieu)
        ArrayList<Hit> res = new ArrayList<>();
        for (Hit h : out) {
            boolean dup = false;
            for (Hit o : res) if (o.name.equals(h.name) && o.sub.equals(h.sub)) { dup = true; break; }
            if (!dup) res.add(h);
            if (res.size() >= max) break;
        }
        return res;
    }

    private double base(int e) {
        int t = typ.get(e), im = imp.get(e);
        return t == PLACE ? 22 + 5 * im : t == STREET ? 22 : 14 + 4 * im;
    }

    int size() { return n; }

    int houses() { return nHouse; }
}

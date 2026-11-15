package be.pa.launcher208;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Fichier de navigation fabriqué par outils/nav/construire.py, lu directement sur la mémoire de l'autoradio
 * (sans tout charger : seules les parties utilisées sont lues). Aucun lien avec Android.
 */
class NavFile {
    final int[] counts;
    private final ByteBuffer buf;
    private final int[][] sec;

    NavFile(File f, String magic) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            FileChannel ch = raf.getChannel();
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            m.order(ByteOrder.LITTLE_ENDIAN);
            buf = m;
        } finally {
            raf.close(); // la projection en mémoire reste valable après la fermeture
        }
        byte[] mg = new byte[8];
        buf.position(0);
        buf.get(mg);
        if (!magic.equals(new String(mg, StandardCharsets.US_ASCII))) throw new IOException("fichier inattendu : " + f.getName());
        int version = buf.getInt();
        if (version != 1) throw new IOException("version " + version + " non prise en charge");
        int nc = buf.getInt();
        counts = new int[nc];
        for (int i = 0; i < nc; i++) counts[i] = buf.getInt();
        int ns = buf.getInt();
        sec = new int[ns][2];
        for (int i = 0; i < ns; i++) { sec[i][0] = buf.getInt(); sec[i][1] = buf.getInt(); }
    }

    ByteBuffer bytes(int i) {
        ByteBuffer d = buf.duplicate();
        d.position(sec[i][0]);
        d.limit(sec[i][0] + sec[i][1]);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    IntBuffer ints(int i) { return bytes(i).asIntBuffer(); }

    ShortBuffer shorts(int i) { return bytes(i).asShortBuffer(); }
}

/** Réseau routier pour voitures (carrefours, tronçons, sens uniques, interdictions de tourner). */
final class NavGraph {
    final int nNodes, nEdges, nNames, nBans, gw, gh, lat0, lon0, step;
    final IntBuffer nLat, nLon, firstOut, out, eu, ev, elen, ename, geoStart, gLat, gLon, banVia, banIn, banOut,
            cellStart, cellEdges, nameStart;
    final ByteBuffer nFlag, nDeg, espeed, eflags, nameBytes;

    NavGraph(File f) throws IOException {
        NavFile nf = new NavFile(f, "R208ROUT");
        int[] c = nf.counts;
        nNodes = c[0];
        nEdges = c[1];
        nNames = c[3];
        nBans = c[4];
        gw = c[5];
        gh = c[6];
        lat0 = c[9];
        lon0 = c[10];
        step = c[11];
        nLat = nf.ints(0);
        nLon = nf.ints(1);
        nFlag = nf.bytes(2);
        nDeg = nf.bytes(3);
        firstOut = nf.ints(4);
        out = nf.ints(5);
        eu = nf.ints(6);
        ev = nf.ints(7);
        elen = nf.ints(8);
        espeed = nf.bytes(9);
        eflags = nf.bytes(10);
        ename = nf.ints(11);
        geoStart = nf.ints(12);
        gLat = nf.ints(13);
        gLon = nf.ints(14);
        banVia = nf.ints(15);
        banIn = nf.ints(16);
        banOut = nf.ints(17);
        cellStart = nf.ints(18);
        cellEdges = nf.ints(19);
        nameStart = nf.ints(20);
        nameBytes = nf.bytes(21);
    }

    double lat(int n) { return nLat.get(n) / 1e6; }

    double lon(int n) { return nLon.get(n) / 1e6; }

    /** Longueur du tronçon (m). */
    double len(int e) { return elen.get(e) / 10.0; }

    /** Vitesse habituelle sur le tronçon (m/s). */
    double mps(int e) { return (espeed.get(e) & 0xff) / 3.6; }

    int rank(int e) { return (eflags.get(e) >> 2) & 7; }

    boolean fwd(int e) { return (eflags.get(e) & 1) != 0; }

    boolean bwd(int e) { return (eflags.get(e) & 2) != 0; }

    /** Tronçon hors du réseau principal (morceau isolé : parking fermé, cour derrière une barrière...). */
    boolean isolated(int e) { return (eflags.get(e) & 32) != 0; }

    boolean blocked(int n) { return (nFlag.get(n) & 1) != 0; }

    int deg(int n) { return nDeg.get(n) & 0xff; }

    String name(int e) {
        int id = ename.get(e);
        if (id <= 0 || id >= nNames) return "";
        int a = nameStart.get(id), b = nameStart.get(id + 1);
        byte[] bb = new byte[b - a];
        for (int i = 0; i < bb.length; i++) bb[i] = nameBytes.get(a + i);
        return new String(bb, StandardCharsets.UTF_8);
    }

    /** Est-ce interdit de passer du tronçon a au tronçon b au carrefour n ? */
    boolean banned(int n, int a, int b) {
        int lo = 0, hi = nBans;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (banVia.get(m) < n) lo = m + 1; else hi = m;
        }
        for (int i = lo; i < nBans && banVia.get(i) == n; i++) {
            if (banIn.get(i) == a && banOut.get(i) == b) return true;
        }
        return false;
    }

    /** Points du tronçon e de u vers v (bouts compris), en millionièmes de degré : {lat0, lon0, lat1, lon1, ...}. */
    int[] points(int e) {
        int a = geoStart.get(e), b = geoStart.get(e + 1);
        int[] p = new int[(b - a + 2) * 2];
        int u = eu.get(e), v = ev.get(e);
        p[0] = nLat.get(u);
        p[1] = nLon.get(u);
        for (int i = a; i < b; i++) { p[(i - a + 1) * 2] = gLat.get(i); p[(i - a + 1) * 2 + 1] = gLon.get(i); }
        p[p.length - 2] = nLat.get(v);
        p[p.length - 1] = nLon.get(v);
        return p;
    }

    /** Tronçons qui passent dans la case contenant ce point, et ses voisines (rayon en cases). */
    int[] near(double lat, double lon, int ring) {
        int ci = (int) Math.floor((lat * 1e6 - lat0) / step), cj = (int) Math.floor((lon * 1e6 - lon0) / step);
        int n = 0;
        int[] tmp = new int[64];
        for (int i = ci - ring; i <= ci + ring; i++) {
            if (i < 0 || i >= gh) continue;
            for (int j = cj - ring; j <= cj + ring; j++) {
                if (j < 0 || j >= gw) continue;
                int c = i * gw + j, a = cellStart.get(c), b = cellStart.get(c + 1);
                for (int k = a; k < b; k++) {
                    if (n == tmp.length) tmp = java.util.Arrays.copyOf(tmp, n * 2);
                    tmp[n++] = cellEdges.get(k);
                }
            }
        }
        return java.util.Arrays.copyOf(tmp, n);
    }
}

package be.pa.launcher208;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;

/** Trajets enregistrés (fichier texte léger, 60 jours gardés). */
final class TripStore {
    static final class Trip {
        final long start, end;
        final float km, maxKmh;

        Trip(long start, long end, float km, float maxKmh) {
            this.start = start; this.end = end; this.km = km; this.maxKmh = maxKmh;
        }

        long minutes() { return Math.max(0, (end - start) / 60000L); }
    }

    private final File file;
    private final ArrayList<Trip> trips = new ArrayList<>();
    private boolean loaded;

    // Trajet en cours (mis à jour par le GPS)
    volatile long curStart;
    volatile float curKm, curMax;

    TripStore(Context c) { file = new File(c.getFilesDir(), "trajets.txt"); }

    private synchronized void load() {
        if (loaded) return;
        loaded = true;
        long limit = System.currentTimeMillis() - 60L * 86400000L;
        BufferedReader r = null;
        try {
            if (!file.exists()) return;
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 4) continue;
                try {
                    Trip t = new Trip(Long.parseLong(p[0]), Long.parseLong(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                    if (t.end >= limit) trips.add(t);
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    synchronized void add(final Trip t) {
        load();
        trips.add(t);
        App.I.bg.execute(new Runnable() {
            @Override public void run() { save(); }
        });
    }

    private synchronized void save() {
        FileWriter w = null;
        try {
            File tmp = new File(file.getPath() + ".tmp");
            w = new FileWriter(tmp);
            for (Trip t : trips) {
                w.write(t.start + "\t" + t.end + "\t" + t.km + "\t" + t.maxKmh + "\n");
            }
            w.close();
            w = null;
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) { }
        }
    }

    /** Trajets depuis une date, y compris celui en cours. */
    synchronized ArrayList<Trip> since(long from) {
        load();
        ArrayList<Trip> out = new ArrayList<>();
        for (Trip t : trips) if (t.end >= from) out.add(t);
        long cs = curStart;
        if (cs > 0) out.add(new Trip(cs, Clock.now(), curKm, curMax));
        return out;
    }

    static float km(ArrayList<Trip> l) {
        float s = 0;
        for (Trip t : l) s += t.km;
        return s;
    }

    static long minutes(ArrayList<Trip> l) {
        long s = 0;
        for (Trip t : l) s += t.minutes();
        return s;
    }

    static float max(ArrayList<Trip> l) {
        float s = 0;
        for (Trip t : l) s = Math.max(s, t.maxKmh);
        return s;
    }

    /** Kilomètres des 7 derniers jours (le dernier = aujourd'hui). */
    synchronized float[] last7(long now) {
        float[] v = new float[7];
        long today = Fmt.dayStart(now);
        ArrayList<Trip> l = since(today - 6L * 86400000L - 3600000L);
        for (Trip t : l) {
            long d = Fmt.dayStart(t.start);
            int idx = 6 - (int) Math.round((today - d) / 86400000.0);
            if (idx >= 0 && idx < 7) v[idx] += t.km;
        }
        return v;
    }
}

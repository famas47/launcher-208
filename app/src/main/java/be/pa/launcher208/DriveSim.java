package be.pa.launcher208;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import org.mapsforge.map.reader.MapFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

/**
 * Trajet simulé sur de vraies routes de la carte (tests automatiques seulement, jamais dans la voiture).
 * Donne au launcher des positions GPS réalistes (une par seconde, avec l'erreur habituelle d'un GPS) le long
 * d'un parcours qui démarre, prend des virages et s'arrête, puis mesure la voiture affichée sur la carte :
 * écart avec la vraie position, régularité du mouvement, cap, marche arrière éventuelle.
 */
final class DriveSim {
    private static DriveSim cur;

    private final double hz;
    private double[] rx, ry, rc; // parcours en mètres (repère local) et distances cumulées
    private double lat0, lon0, kx, ky = 110540.0;
    private double[] T, S, V;
    private double tEnd;
    private long t0Ns;
    private final Random rnd = new Random(7);
    private double nx, ny;
    private int fixN;
    private boolean running;
    // mesures
    private final ArrayList<Double> err = new ArrayList<>(), side = new ArrayList<>(), spd = new ArrayList<>(),
            acc = new ArrayList<>(), head = new ArrayList<>();
    private final ArrayList<double[]> hist = new ArrayList<>();
    private int back, frames;
    private double accMax, accMaxT;
    private double pX = Double.NaN, pY, pT;

    private final boolean ecart;
    // vitesse affichée : écart avec la vraie vitesse (km/h), nouvelle et ancienne façon, et ligne de l'itinéraire
    private final ArrayList<Double> vErr = new ArrayList<>(), vOld = new ArrayList<>(), cutGap = new ArrayList<>();
    private double nextSpeedT = 3, oldShown = -1, nextOldT = 3;

    private DriveSim(double hz, boolean ecart) { this.hz = hz; this.ecart = ecart; }

    static void start(double hz) { start(hz, false); }

    /** ecart : en navigation, la voiture quitte l'itinéraire au premier virage (elle continue tout droit). */
    static void start(final double hz, boolean ecart) {
        if (cur != null) cur.stop("remplacé");
        final DriveSim s = new DriveSim(hz, ecart);
        cur = s;
        final double lat = App.I.loc.lat != 0 ? App.I.loc.lat : P.lat(), lon = App.I.loc.lon != 0 ? App.I.loc.lon : P.lon();
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = s.buildRoute(lat, lon);
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        Log.i("Test208", "simu parcours : " + r);
                        if (cur == s && s.rx != null) s.go();
                    }
                });
            }
        }, "simu").start();
    }

    static void stopNow() { if (cur != null) cur.stop("arrêté"); }

    /** Parcours d'environ 1,2 km sur les routes, en continuant le plus droit possible aux carrefours. */
    private String buildRoute(double lat, double lon) {
        Router.Route navRoute = App.I.nav.active ? App.I.nav.route : null;
        if (navRoute != null && navRoute.lat.length >= 2) {
            // navigation en cours : on suit son itinéraire (au plus 2,5 km)
            ArrayList<double[]> pts = new ArrayList<>();
            int cut = navRoute.lat.length - 1;
            if (ecart && navRoute.steps != null) {
                // premier vrai virage après 150 m : la voiture ne le prend pas
                for (Guide.Step st : navRoute.steps) {
                    if (st.at > 150 && Math.abs(st.angle) >= 60 && !st.action.equals("arrivee")) { cut = st.point; break; }
                }
            }
            for (int i = 0; i <= cut && navRoute.cum[i] <= 2500; i++) pts.add(new double[]{navRoute.lat[i], navRoute.lon[i]});
            if (ecart && pts.size() >= 2) {
                MapFile mf = null;
                try {
                    mf = new MapFile(MiniMap.file(App.I), "fr");
                    RoadSnap sn = new RoadSnap(mf);
                    double[] q = pts.get(pts.size() - 1), o = pts.get(pts.size() - 2);
                    double kxx = 111320.0 * Math.cos(Math.toRadians(q[0]));
                    double b = Math.toDegrees(Math.atan2((q[1] - o[1]) * kxx, (q[0] - o[0]) * 110540.0));
                    RoadSnap.Result r = sn.snap(q[0], q[1], b, true, 15);
                    if (r != null) extend(pts, sn, r, 700);
                } catch (Throwable ignored) {
                } finally {
                    if (mf != null) try { mf.close(); } catch (Throwable ignored) { }
                }
                return setRoute(pts) + " (itinéraire de la navigation, puis tout droit au lieu de tourner)";
            }
            if (pts.size() >= 2) return setRoute(pts) + " (itinéraire de la navigation)";
        }
        MapFile mf = null;
        try {
            mf = new MapFile(MiniMap.file(App.I), "fr");
            RoadSnap sn = new RoadSnap(mf);
            ArrayList<double[]> pts = new ArrayList<>();
            // départ : la route pour voitures la plus proche (jusqu'à 300 m)
            RoadSnap.Result r = RoadSnap.snap(sn.around(lat, lon, 310), lat, lon, 0, false, 300, null, 1);
            if (r == null) return "aucune route près de " + lat + "," + lon;
            double len = extend(pts, sn, r, 1200);
            if (pts.size() < 2 || len < 200) return "parcours trop court (" + Math.round(len) + " m)";
            return setRoute(pts);
        } catch (Throwable t) {
            rx = null;
            return "erreur " + t;
        } finally {
            if (mf != null) try { mf.close(); } catch (Throwable ignored) { }
        }
    }

    /** Continue le parcours sur les routes depuis r, le plus droit possible, sur environ maxLen mètres. */
    private static double extend(ArrayList<double[]> pts, RoadSnap sn, RoadSnap.Result r, double maxLen) {
            double len = 0;
            for (int hop = 0; hop < 12 && len < maxLen; hop++) {
                RoadSnap.path(r, sn.around(r.lat, r.lon, 400));
                int start = pts.isEmpty() ? 0 : 1;
                for (int i = start; i < r.pathLat.length; i++) {
                    double[] p = {r.pathLat[i], r.pathLon[i]};
                    if (!pts.isEmpty()) {
                        double[] q = pts.get(pts.size() - 1);
                        double d = Math.hypot((p[1] - q[1]) * 111320.0 * Math.cos(Math.toRadians(p[0])), (p[0] - q[0]) * 110540.0);
                        if (d < 0.3) continue;
                        len += d;
                    }
                    pts.add(p);
                }
                if (r.pathLat.length < 2) break;
                int n = r.pathLat.length;
                double kxx = 111320.0 * Math.cos(Math.toRadians(r.pathLat[n - 1]));
                double b = Math.toDegrees(Math.atan2((r.pathLon[n - 1] - r.pathLon[n - 2]) * kxx, (r.pathLat[n - 1] - r.pathLat[n - 2]) * 110540.0));
                RoadSnap.Result nr = sn.snap(r.pathLat[n - 1], r.pathLon[n - 1], b, true, 10);
                if (nr == null) break;
                r = nr;
            }
            return len;
    }

    private String setRoute(ArrayList<double[]> pts) {
        try {
            lat0 = pts.get(0)[0];
            lon0 = pts.get(0)[1];
            kx = 111320.0 * Math.cos(Math.toRadians(lat0));
            int n = pts.size();
            rx = new double[n];
            ry = new double[n];
            rc = new double[n];
            for (int i = 0; i < n; i++) {
                rx[i] = (pts.get(i)[1] - lon0) * kx;
                ry[i] = (pts.get(i)[0] - lat0) * ky;
                if (i > 0) rc[i] = rc[i - 1] + Math.hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1]);
            }
            profile();
            return String.format(Locale.ROOT, "%d m, %d points, %.0f s, départ %.5f,%.5f", Math.round(rc[n - 1]), n, tEnd, lat0, lon0);
        } catch (Throwable t) {
            rx = null;
            return "erreur " + t;
        }
    }

    private double[] at(double s) {
        s = Math.max(0, Math.min(rc[rc.length - 1], s));
        int i = 1;
        while (i < rc.length - 1 && rc[i] < s) i++;
        double seg = Math.max(1e-6, rc[i] - rc[i - 1]), r = (s - rc[i - 1]) / seg;
        return new double[]{rx[i - 1] + (rx[i] - rx[i - 1]) * r, ry[i - 1] + (ry[i] - ry[i - 1]) * r,
                (rx[i] - rx[i - 1]) / seg, (ry[i] - ry[i - 1]) / seg};
    }

    /** Vitesse : démarre, 50 km/h au plus, ralentit dans les virages, s'arrête à la fin. */
    private void profile() {
        double L = rc[rc.length - 1];
        int n = (int) (L / 1.0) + 1;
        double[] vm = new double[n];
        for (int i = 0; i < n; i++) {
            double[] a = at(i - 8.0), b = at(i + 8.0);
            double d = Math.abs(Glide.wrap(Math.toDegrees(Math.atan2(b[2], b[3]) - Math.atan2(a[2], a[3]))));
            double k = Math.toRadians(d) / 16.0;
            vm[i] = Math.min(13.9, k > 1e-4 ? Math.sqrt(2.5 / k) : 13.9);
        }
        vm[0] = 0;
        vm[n - 1] = 0;
        for (int i = 1; i < n; i++) vm[i] = Math.min(vm[i], Math.sqrt(vm[i - 1] * vm[i - 1] + 2 * 2.0));
        for (int i = n - 2; i >= 0; i--) vm[i] = Math.min(vm[i], Math.sqrt(vm[i + 1] * vm[i + 1] + 2 * 2.5));
        T = new double[n];
        S = new double[n];
        V = new double[n];
        for (int i = 1; i < n; i++) {
            T[i] = T[i - 1] + 1.0 / Math.max(0.3, (vm[i - 1] + vm[i]) / 2);
            S[i] = i;
            V[i] = vm[i];
        }
        tEnd = T[n - 1];
    }

    /** Vraie position au temps t (s) : x, y, direction x, direction y, vitesse. */
    private double[] truth(double t) {
        if (t >= tEnd) { double[] p = at(S[S.length - 1]); return new double[]{p[0], p[1], p[2], p[3], 0}; }
        if (t <= 0) { double[] p = at(0); return new double[]{p[0], p[1], p[2], p[3], 0}; }
        int i = 1;
        int lo = 1, hi = T.length - 1;
        while (lo < hi) { int m = (lo + hi) / 2; if (T[m] < t) lo = m + 1; else hi = m; }
        i = lo;
        double r = (t - T[i - 1]) / (T[i] - T[i - 1]);
        double s = S[i - 1] + (S[i] - S[i - 1]) * r, v = V[i - 1] + (V[i] - V[i - 1]) * r;
        double[] p = at(s);
        return new double[]{p[0], p[1], p[2], p[3], v};
    }

    private void go() {
        running = true;
        App.I.simSpeed = -1;
        App.I.loc.simulating = true;
        t0Ns = System.nanoTime();
        fixN = 0;
        App.I.main.post(fixTask);
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private final Runnable fixTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            double t = (System.nanoTime() - t0Ns) / 1e9 - 0.15; // mesure faite 0,15 s avant la réception
            double[] tr = truth(t);
            double a = 0.75, q = 2.5 * Math.sqrt(1 - a * a);
            nx = a * nx + q * rnd.nextGaussian();
            ny = a * ny + q * rnd.nextGaussian();
            Location l = new Location("gps");
            l.setLatitude(lat0 + (tr[1] + ny) / ky);
            l.setLongitude(lon0 + (tr[0] + nx) / kx);
            l.setTime(System.currentTimeMillis() - 150);
            if (Build.VERSION.SDK_INT >= 17) l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 150_000_000L);
            double v = Math.max(0, tr[4] + rnd.nextGaussian() * 0.3);
            if (v < 0.4) v = 0;
            l.setSpeed((float) v);
            if (tr[4] > 1.5) l.setBearing((float) Glide.norm(Math.toDegrees(Math.atan2(tr[2], tr[3])) + rnd.nextGaussian() * 3));
            l.setAccuracy(5f);
            App.I.loc.simFix(l);
            fixN++;
            if (t > tEnd + 6) { stop("fin"); return; }
            long next = t0Ns + (long) (fixN * 1e9 / hz);
            App.I.main.postDelayed(this, Math.max(1, (next - System.nanoTime()) / 1_000_000L));
        }
    };

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long ns) {
            if (!running) return;
            double[] d = MiniMap.displayed();
            // moment exact de l'image affichée (elle peut dater de l'image précédente)
            double t = d == null ? 0 : (d[3] * 1e6 - t0Ns) / 1e9;
            double tNow = (ns - t0Ns) / 1e9;
            if (tNow > nextSpeedT && tNow < tEnd) {
                // 10 fois par seconde, comme l'écran : vitesse estimée maintenant contre la vraie
                nextSpeedT += 0.1;
                double est = App.I.speedo.mps(System.nanoTime() / 1e6);
                double vt = truth(tNow)[4] * 3.6;
                if (est >= 0) vErr.add(Math.abs(Math.round(est * 3.6) - vt));
                // ancienne façon : vitesse du dernier point GPS, chiffre mis à jour chaque seconde
                if (tNow > nextOldT) { oldShown = Math.round(App.I.loc.speedKmh); nextOldT += 1.0; }
                if (oldShown >= 0) vOld.add(Math.abs(oldShown - vt));
                // ligne bleue : elle doit commencer à la voiture affichée (distance le long de l'itinéraire)
                double cut = MiniMap.routeCut();
                double[] dd = MiniMap.displayed();
                if (cut >= 0 && dd != null && App.I.nav.active) {
                    double along = alongNav(dd[0], dd[1]);
                    if (along >= 0) cutGap.add(along - cut);
                }
            }
            if (d != null && t > 3 && t > pT + 1e-6) {
                frames++;
                double x = (d[1] - lon0) * kx, y = (d[0] - lat0) * ky;
                double[] tr = truth(t);
                err.add(Math.hypot(x - tr[0], y - tr[1]));
                side.add(Math.abs((x - tr[0]) * tr[3] - (y - tr[1]) * tr[2]));
                if (!Double.isNaN(pX)) {
                    double dt = t - pT, vx = (x - pX) / dt, vy = (y - pY) / dt;
                    spd.add(Math.abs(Math.hypot(vx, vy) - tr[4]));
                    if ((x - pX) * tr[2] + (y - pY) * tr[3] < -1e-3 && tr[4] > 1) back++;
                }
                hist.add(new double[]{t, x, y});
                while (hist.size() > 2 && hist.get(hist.size() - 1)[0] - hist.get(0)[0] > 0.2) hist.remove(0);
                double[] h0 = hist.get(0), h2 = hist.get(hist.size() - 1);
                if (h2[0] - h0[0] > 0.15) {
                    double[] h1 = hist.get(hist.size() / 2);
                    double a1 = h1[0] - h0[0], a2 = h2[0] - h1[0];
                    if (a1 > 0.04 && a2 > 0.04) {
                        double v1x = (h1[1] - h0[1]) / a1, v1y = (h1[2] - h0[2]) / a1, v2x = (h2[1] - h1[1]) / a2, v2y = (h2[2] - h1[2]) / a2;
                        double ac = Math.hypot(v2x - v1x, v2y - v1y) / ((a1 + a2) / 2);
                        acc.add(ac);
                        if (ac > accMax) { accMax = ac; accMaxT = t; }
                    }
                }
                if (tr[4] > 3) head.add(Math.abs(Glide.wrap(d[2] - Math.toDegrees(Math.atan2(tr[2], tr[3])))));
                pX = x;
                pY = y;
                pT = t;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Distance le long de l'itinéraire de la navigation du point le plus proche (m), -1 si loin. */
    private static double alongNav(double lat, double lon) {
        Router.Route r = App.I.nav.route;
        if (r == null) return -1;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0, best = 30, along = -1;
        for (int i = 0; i + 1 < r.lat.length; i++) {
            double ax = (r.lon[i] - lon) * kx, ay = (r.lat[i] - lat) * ky;
            double bx = (r.lon[i + 1] - lon) * kx, by = (r.lat[i + 1] - lat) * ky;
            double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
            double tt = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
            double qx = ax + tt * sx, qy = ay + tt * sy, d = Math.sqrt(qx * qx + qy * qy);
            if (d < best) { best = d; along = r.cum[i] + tt * (r.cum[i + 1] - r.cum[i]); }
        }
        return along;
    }

    private static double pct(ArrayList<Double> v, double p) {
        if (v.isEmpty()) return 0;
        ArrayList<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) (c.size() * p)));
    }

    private static double mean(ArrayList<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return v.isEmpty() ? 0 : s / v.size();
    }

    private void stop(String why) {
        if (!running && cur != this) return;
        running = false;
        App.I.loc.simulating = false;
        App.I.main.removeCallbacks(fixTask);
        Choreographer.getInstance().removeFrameCallback(frame);
        if (cur == this) cur = null;
        Log.i("Test208", String.format(Locale.ROOT,
                "simu %s : %d images mesurées | écart %.1f m (95%% sous %.1f) | à côté de la route %.1f m (95%% sous %.1f)"
                        + " | vitesse ±%.2f m/s (95%% sous %.2f) | à-coups 95%% sous %.1f m/s² (max %.1f à %.0f s) | cap 95%% sous %.1f° | recul %d | carte : %s | gps %.1f/s",
                why, frames, mean(err), pct(err, .95), mean(side), pct(side, .95), mean(spd), pct(spd, .95),
                pct(acc, .95), pct(acc, 1), accMaxT, pct(head, .95), back, MiniMap.statsNow(), App.I.loc.fixRate()));
        Log.i("Test208", String.format(Locale.ROOT,
                "vitesse affichée : écart %.2f km/h (95%% sous %.1f) | ancienne façon : écart %.2f km/h (95%% sous %.1f) | bruit appris %.2f m/s",
                mean(vErr), pct(vErr, .95), mean(vOld), pct(vOld, .95), App.I.speedo.noise()));
        if (!cutGap.isEmpty()) {
            ArrayList<Double> ab = new ArrayList<>();
            for (double g : cutGap) ab.add(Math.abs(g));
            Log.i("Test208", String.format(Locale.ROOT,
                    "ligne bleue : commence à %.1f m de la voiture en moyenne (95%% sous %.1f m, au plus %.1f m derrière, %.1f m devant) | %d mesures",
                    mean(ab), pct(ab, .95), Math.max(0, pct(cutGap, 1)), Math.max(0, -pct(cutGap, 0)), cutGap.size()));
        }
    }
}

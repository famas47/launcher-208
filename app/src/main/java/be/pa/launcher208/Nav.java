package be.pa.launcher208;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation en cours : la destination, l'itinéraire, ce qu'il reste (distance, durée, heure d'arrivée).
 * Suit chaque position GPS : si la voiture quitte l'itinéraire, un nouveau trajet est calculé depuis
 * l'endroit où elle se trouve ; à l'arrivée, la navigation s'arrête toute seule.
 */
final class Nav {
    /** Tout ce qui touche à la recherche et au calcul se fait sur ce fil (un seul à la fois). */
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    NavData.Place dest;
    Router.Route route;
    boolean active, arrived;
    String state = "";          // « Nouveau calcul… », « Tu es arrivé », message d'erreur
    int version;                // change à chaque nouvel itinéraire (la carte le redessine)
    double remainingM, remainingS, offM;
    private int idx;            // segment de l'itinéraire où se trouve la voiture
    private long offSince, arrivedAt, lastRecalc;
    private boolean recalculating;

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }

    void remove(Runnable r) { listeners.remove(r); }

    private void fire() { for (int i = 0; i < listeners.size(); i++) listeners.get(i).run(); }

    /** Heure d'arrivée prévue (ms, heure de l'autoradio). */
    long arrival() { return Clock.now() + Math.round(remainingS * 1000); }

    void start(NavData.Place p, Router.Route r) {
        dest = p;
        route = r;
        active = true;
        arrived = false;
        state = "";
        idx = 0;
        offSince = 0;
        remainingM = r.meters;
        remainingS = r.seconds;
        version++;
        NavData.addRecent(p);
        App.I.loc.addFix(fixes);
        fire();
    }

    void stop() {
        if (!active) return;
        active = false;
        route = null;
        state = "";
        version++;
        App.I.loc.removeFix(fixes);
        fire();
    }

    private final LocationTracker.FixListener fixes = new LocationTracker.FixListener() {
        @Override public void onFix(double monoMs, double lat, double lon, float mps, boolean measured, float bearing,
                                    boolean bearingOk, float accuracy) {
            if (!active || route == null) return;
            progress(lat, lon, mps, bearingOk ? bearing : Double.NaN, accuracy);
        }
    };

    /** Où en est-on sur l'itinéraire ? (position GPS) */
    void progress(double lat, double lon, float mps, double heading, float accuracy) {
        Router.Route r = route;
        if (r == null) return;
        long now = SystemClock.uptimeMillis();
        if (arrived) {
            if (now - arrivedAt > 20000) stop();
            return;
        }
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        int n = r.lat.length;
        // d'abord un peu avant et jusqu'à 500 m devant la dernière position connue ; sinon tout l'itinéraire
        int[] range = {Math.max(0, idx - 3), n - 1};
        int bestI = -1;
        double bestD = Double.MAX_VALUE, bestT = 0;
        for (int pass = 0; pass < 2 && (bestI < 0 || bestD > 60); pass++) {
            int a = pass == 0 ? range[0] : 0;
            for (int i = a; i + 1 < n; i++) {
                if (pass == 0 && r.cum[i] > r.cum[Math.min(idx, n - 1)] + 500) break;
                double ax = (r.lon[i] - lon) * kx, ay = (r.lat[i] - lat) * ky;
                double bx = (r.lon[i + 1] - lon) * kx, by = (r.lat[i + 1] - lat) * ky;
                double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
                double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
                double qx = ax + t * sx, qy = ay + t * sy, d = Math.sqrt(qx * qx + qy * qy);
                if (d < bestD) { bestD = d; bestI = i; bestT = t; }
            }
        }
        if (bestI < 0) return;
        offM = bestD;
        double tol = Math.max(45, accuracy > 0 ? accuracy * 1.5 : 0);
        if (bestD <= tol) {
            idx = bestI;
            offSince = 0;
            double along = r.cum[bestI] + bestT * (r.cum[bestI + 1] - r.cum[bestI]);
            double alongT = r.cumT[bestI] + bestT * (r.cumT[bestI + 1] - r.cumT[bestI]);
            remainingM = Math.max(0, r.meters - along);
            remainingS = Math.max(0, r.seconds - alongT);
            if (state.length() > 0 && !recalculating) state = "";
            double toDest = Math.hypot((dest.lon - lon) * kx, (dest.lat - lat) * ky);
            if (remainingM < 25 || toDest < 25) {
                arrived = true;
                arrivedAt = now;
                remainingM = 0;
                remainingS = 0;
                state = "Tu es arrivé";
            }
        } else if (mps > 1.5) {
            // hors de l'itinéraire depuis 4 s en roulant : nouveau calcul depuis ici
            if (offSince == 0) offSince = now;
            if (now - offSince > 4000 && !recalculating && now - lastRecalc > 8000) recalc(lat, lon, heading);
        }
        fire();
    }

    /**
     * L'itinéraire devant ce point (250 m), en commençant par le point de l'itinéraire le plus proche : la voiture
     * affichée suit ainsi les virages prévus entre deux positions GPS. Null si le point est loin de l'itinéraire.
     */
    double[][] pathAhead(double lat, double lon, double maxM) {
        Router.Route r = route;
        if (!active || arrived || r == null) return null;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        int n = r.lat.length, from = Math.max(0, idx - 10);
        double limit = r.cum[Math.min(idx, n - 1)] + 600;
        int bestI = -1;
        double bestD = 30, bestT = 0; // jusqu'à 30 m : erreur habituelle du GPS ; au-delà, la voiture a quitté le trajet
        for (int i = from; i + 1 < n && r.cum[i] <= limit; i++) {
            double ax = (r.lon[i] - lon) * kx, ay = (r.lat[i] - lat) * ky;
            double bx = (r.lon[i + 1] - lon) * kx, by = (r.lat[i + 1] - lat) * ky;
            double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
            double qx = ax + t * sx, qy = ay + t * sy, d = Math.sqrt(qx * qx + qy * qy);
            if (d < bestD) { bestD = d; bestI = i; bestT = t; }
        }
        if (bestI < 0) return null;
        double along = r.cum[bestI] + bestT * (r.cum[bestI + 1] - r.cum[bestI]);
        ArrayList<double[]> pts = new ArrayList<>();
        pts.add(new double[]{r.lat[bestI] + bestT * (r.lat[bestI + 1] - r.lat[bestI]), r.lon[bestI] + bestT * (r.lon[bestI + 1] - r.lon[bestI])});
        for (int i = bestI + 1; i < n && r.cum[i] - along <= maxM; i++) pts.add(new double[]{r.lat[i], r.lon[i]});
        if (pts.size() < 2) return null;
        double[] la = new double[pts.size()], lo = new double[pts.size()];
        for (int i = 0; i < la.length; i++) { la[i] = pts.get(i)[0]; lo[i] = pts.get(i)[1]; }
        return new double[][]{la, lo};
    }

    private void recalc(final double lat, final double lon, final double heading) {
        recalculating = true;
        lastRecalc = SystemClock.uptimeMillis();
        state = "Nouveau calcul…";
        final NavData.Place d = dest;
        final int v = version;
        worker.execute(new Runnable() {
            @Override public void run() {
                Router.Route nr = null;
                try {
                    Router ro = App.I.navData.router();
                    if (ro != null) {
                        Router.Snap from = ro.snap(lat, lon, 150, heading);
                        Router.Snap to = ro.snap(d.lat, d.lon, 500, Double.NaN);
                        if (from != null && to != null) nr = ro.route(from, to, heading);
                    }
                } catch (Throwable ignored) { }
                final Router.Route res = nr;
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        recalculating = false;
                        if (!active || version != v) return;
                        if (res == null) { state = "Pas de trajet depuis ici"; fire(); return; }
                        route = res;
                        idx = 0;
                        offSince = 0;
                        remainingM = res.meters;
                        remainingS = res.seconds;
                        state = "";
                        version++;
                        fire();
                    }
                });
            }
        });
    }
}

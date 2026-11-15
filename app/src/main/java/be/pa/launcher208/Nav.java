package be.pa.launcher208;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation en cours : la destination, l'itinéraire, la prochaine instruction (flèche, distance, voix), ce
 * qu'il reste (distance, durée, heure d'arrivée). Suit chaque position GPS :
 * - si la voiture quitte l'itinéraire (mauvais chemin, demi-tour), un nouveau trajet est calculé depuis
 *   l'endroit où elle se trouve, et la voix dit « Nouvel itinéraire » ;
 * - à l'arrivée, « Tu es arrivé », puis la navigation s'arrête toute seule.
 * Donne aussi la limitation de vitesse de la route où l'on roule (avec ou sans navigation).
 */
final class Nav {
    /** Tout ce qui touche à la recherche et au calcul se fait sur ce fil (un seul à la fois). */
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final NavVoice voice = new NavVoice();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    NavData.Place dest;
    Router.Route route;
    boolean active, arrived;
    String state = "";          // « Nouveau calcul… », « Tu es arrivé », message d'erreur
    int version;                // change à chaque nouvel itinéraire (la carte le redessine)
    double remainingM, remainingS, offM;
    /** Prochaine instruction et distance jusqu'à elle (m). */
    Guide.Step next;
    double nextM;
    int reroutes;
    /** Limitation de vitesse de la route actuelle (km/h), 0 si inconnue. */
    volatile int limit;
    /** Distance (m) entre la dernière position et l'itinéraire, calculée par pathAhead. */
    double aheadD;

    private List<Guide.Step> steps;
    private int stepIdx;
    private int spokenFar = -1, spokenNear = -1, spokenNow = -1; // instruction déjà annoncée (loin, près, maintenant)
    private int idx;            // segment de l'itinéraire où se trouve la voiture
    private long offSince, wrongWaySince, arrivedAt, lastRecalc;
    private boolean recalculating;

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }

    void remove(Runnable r) { listeners.remove(r); }

    private void fire() { for (int i = 0; i < listeners.size(); i++) listeners.get(i).run(); }

    /** Heure d'arrivée prévue (ms, heure de l'autoradio). */
    long arrival() { return Clock.now() + Math.round(remainingS * 1000); }

    /** À appeler une fois : suit les positions GPS (limitations de vitesse, navigation). */
    void init() { App.I.loc.addFix(fixes); }

    void start(NavData.Place p, Router.Route r) {
        dest = p;
        active = true;
        arrived = false;
        state = "";
        reroutes = 0;
        setRoute(r);
        NavData.addRecent(p);
        voice.say("depart");
        LocationTracker l = App.I.loc;
        if (l.lat != 0) progress(l.lat, l.lon, l.speedKmh / 3.6f, Double.NaN, l.accuracy);
        fire();
    }

    private void setRoute(Router.Route r) {
        route = r;
        steps = r.steps;
        stepIdx = 0;
        spokenFar = spokenNear = spokenNow = -1;
        next = steps != null && !steps.isEmpty() ? steps.get(0) : null;
        nextM = next != null ? next.at : 0;
        idx = 0;
        offSince = 0;
        wrongWaySince = 0;
        remainingM = r.meters;
        remainingS = r.seconds;
        version++;
        if (BuildConfig.TEST_HOOKS && steps != null) {
            StringBuilder sb = new StringBuilder();
            for (Guide.Step s : steps) sb.append(" | ").append(s);
            android.util.Log.i("Test208", String.format(Locale.ROOT, "guidage %.0f m :%s", r.meters, sb));
        }
    }

    void stop() {
        if (!active) return;
        active = false;
        route = null;
        steps = null;
        next = null;
        state = "";
        version++;
        voice.stop();
        fire();
    }

    private final LocationTracker.FixListener fixes = new LocationTracker.FixListener() {
        @Override public void onFix(double monoMs, double lat, double lon, float mps, boolean measured, float bearing,
                                    boolean bearingOk, float accuracy) {
            double head = bearingOk ? bearing : Double.NaN;
            if (active && route != null) progress(lat, lon, mps, head, accuracy);
            speedLimit(lat, lon, mps, head);
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
        int bestI = -1;
        double bestD = Double.MAX_VALUE, bestT = 0;
        for (int pass = 0; pass < 2 && (bestI < 0 || bestD > 60); pass++) {
            int a = pass == 0 ? Math.max(0, idx - 3) : 0;
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
        double tol = Math.max(35, accuracy > 0 ? accuracy * 1.5 : 0);
        boolean moving = mps > 2;
        if (bestD <= tol) {
            idx = bestI;
            offSince = 0;
            double along = r.cum[bestI] + bestT * (r.cum[bestI + 1] - r.cum[bestI]);
            double alongT = r.cumT[bestI] + bestT * (r.cumT[bestI + 1] - r.cumT[bestI]);
            remainingM = Math.max(0, r.meters - along);
            remainingS = Math.max(0, r.seconds - alongT);
            if (state.length() > 0 && !recalculating) state = "";
            // dans le mauvais sens sur l'itinéraire (demi-tour) depuis 5 s : nouveau calcul
            if (moving && !Double.isNaN(heading) && r.cum[bestI + 1] - r.cum[bestI] > 1) {
                double segB = Math.toDegrees(Math.atan2((r.lon[bestI + 1] - r.lon[bestI]) * kx, (r.lat[bestI + 1] - r.lat[bestI]) * ky));
                if (Math.abs(Glide.wrap(heading - segB)) > 120) {
                    if (wrongWaySince == 0) wrongWaySince = now;
                    if (now - wrongWaySince > 5000) maybeRecalc(lat, lon, heading, now);
                } else wrongWaySince = 0;
            }
            guide(along, Math.max(mps, 5));
            double toDest = Math.hypot((dest.lon - lon) * kx, (dest.lat - lat) * ky);
            if (remainingM < 25 || toDest < 25) {
                arrived = true;
                arrivedAt = now;
                remainingM = 0;
                remainingS = 0;
                next = null;
                state = "Tu es arrivé";
                voice.say("arrive");
            }
        } else if (moving) {
            // hors de l'itinéraire depuis 3 s en roulant : nouveau calcul depuis ici
            if (offSince == 0) offSince = now;
            if (now - offSince > 3000) maybeRecalc(lat, lon, heading, now);
        }
        fire();
    }

    /** Prochaine instruction, distance, et phrases à dire au bon moment. */
    private void guide(double along, double v) {
        if (steps == null || steps.isEmpty()) return;
        while (stepIdx < steps.size() - 1 && steps.get(stepIdx).at < along - 8) stepIdx++;
        Guide.Step s = steps.get(stepIdx);
        next = s;
        nextM = Math.max(0, s.at - along);
        double d = nextM;
        boolean arrival = "arrivee".equals(s.action);
        // « maintenant » : quelques secondes avant le carrefour (plus tôt quand on roule vite)
        double nowD = Math.max(35, Math.min(160, v * (s.action.startsWith("sortie") ? 6 : 4.5)));
        // annonces à l'avance : loin et près, selon la vitesse (ville, route, autoroute)
        double far = v > 25 ? 2000 : v > 15 ? 800 : 300;
        double near = v > 25 ? 500 : v > 15 ? 300 : 0;
        if (!arrival && d <= nowD) {
            if (spokenNow != stepIdx) {
                spokenNow = spokenNear = spokenFar = stepIdx;
                voice.say(s.action);
            }
            return;
        }
        if (near > 0 && d <= near && d > nowD + 40 && spokenNear != stepIdx) {
            spokenNear = spokenFar = stepIdx;
            String c = NavVoice.announce(s.action, d);
            if (c != null) voice.say(c);
            return;
        }
        if (d <= far && d > nowD + 40 && spokenFar != stepIdx && (near == 0 || d > near + 100)) {
            spokenFar = stepIdx;
            String c = NavVoice.announce(s.action, d);
            if (c != null) voice.say(c);
        }
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
        double lim = r.cum[Math.min(idx, n - 1)] + 600;
        int bestI = -1;
        double bestD = 30, bestT = 0; // jusqu'à 30 m : erreur habituelle du GPS ; au-delà, la voiture a quitté le trajet
        for (int i = from; i + 1 < n && r.cum[i] <= lim; i++) {
            double ax = (r.lon[i] - lon) * kx, ay = (r.lat[i] - lat) * ky;
            double bx = (r.lon[i + 1] - lon) * kx, by = (r.lat[i + 1] - lat) * ky;
            double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
            double qx = ax + t * sx, qy = ay + t * sy, d = Math.sqrt(qx * qx + qy * qy);
            if (d < bestD) { bestD = d; bestI = i; bestT = t; }
        }
        if (bestI < 0) return null;
        aheadD = bestD;
        double along = r.cum[bestI] + bestT * (r.cum[bestI + 1] - r.cum[bestI]);
        ArrayList<double[]> pts = new ArrayList<>();
        pts.add(new double[]{r.lat[bestI] + bestT * (r.lat[bestI + 1] - r.lat[bestI]), r.lon[bestI] + bestT * (r.lon[bestI + 1] - r.lon[bestI])});
        for (int i = bestI + 1; i < n && r.cum[i] - along <= maxM; i++) pts.add(new double[]{r.lat[i], r.lon[i]});
        if (pts.size() < 2) return null;
        double[] la = new double[pts.size()], lo = new double[pts.size()];
        for (int i = 0; i < la.length; i++) { la[i] = pts.get(i)[0]; lo[i] = pts.get(i)[1]; }
        return new double[][]{la, lo};
    }

    private void maybeRecalc(double lat, double lon, double heading, long now) {
        if (recalculating || now - lastRecalc < 6000) return;
        recalc(lat, lon, heading);
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
                long t0 = System.nanoTime();
                try {
                    Router ro = App.I.navData.router();
                    if (ro != null) {
                        Router.Snap from = ro.snap(lat, lon, 150, heading);
                        Router.Snap to = ro.snap(d.lat, d.lon, 600, Double.NaN);
                        if (from != null && to != null) nr = ro.route(from, to, heading);
                        if (nr != null) nr.steps = Guide.build(ro.g, nr);
                    }
                } catch (Throwable ignored) { }
                final Router.Route res = nr;
                final double ms = (System.nanoTime() - t0) / 1e6;
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        recalculating = false;
                        if (!active || version != v) return;
                        if (BuildConfig.TEST_HOOKS) {
                            android.util.Log.i("Test208", String.format(Locale.ROOT, "trajet recalculé depuis %.5f,%.5f : %s, %.0f ms",
                                    lat, lon, res == null ? "aucun" : String.format(Locale.ROOT, "%.1f km, %.0f min", res.meters / 1000, res.seconds / 60), ms));
                        }
                        if (res == null) { state = "Pas de trajet depuis ici"; fire(); return; }
                        reroutes++;
                        setRoute(res);
                        state = "";
                        voice.say("nouvel");
                        fire();
                    }
                });
            }
        });
    }

    // ---------- Limitation de vitesse ----------

    private int pendingLimit = -1;
    private boolean limitBusy;

    /** Limitation de vitesse de la route où l'on roule (fichier des routes ; itinéraire en priorité). */
    private void speedLimit(final double lat, final double lon, final float mps, final double heading) {
        if (!P.speedSigns()) { limit = 0; return; }
        if (limitBusy || !NavData.available(App.I)) return;
        // en navigation, sur l'itinéraire : le tronçon de l'itinéraire où l'on se trouve
        final Router.Route r = active && !arrived && offM < 25 ? route : null;
        final int at = idx;
        limitBusy = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                int v = -1;
                try {
                    Router ro = App.I.navData.router();
                    if (ro != null) {
                        if (r != null) {
                            int k = 0;
                            while (k + 1 < r.edges.length && r.edgeFirstPoint[k + 1] <= at) k++;
                            v = ro.g.limit(r.edges[k] >> 1, r.edges[k] & 1);
                        } else if (mps > 1.5 && !Double.isNaN(heading)) {
                            Router.Snap s = ro.snap(lat, lon, 25, heading);
                            if (s != null) {
                                int[] p = ro.g.points(s.edge);
                                int i = Math.min(s.seg * 2, p.length - 4);
                                double kx = 111320.0 * Math.cos(Math.toRadians(lat));
                                double h = Math.toDegrees(Math.atan2((p[i + 3] - p[i + 1]) * kx, (p[i + 2] - p[i]) * 110540.0));
                                int dir = Math.abs(Glide.wrap(h - heading)) <= 90 ? 0 : 1;
                                v = ro.g.limit(s.edge, dir);
                            } else v = 0;
                        }
                    }
                } catch (Throwable ignored) { }
                final int val = v;
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        limitBusy = false;
                        if (val < 0) return; // à l'arrêt : on garde le panneau
                        // une nouvelle valeur doit être vue deux fois de suite (pas de clignotement aux carrefours)
                        if (val == limit) pendingLimit = -1;
                        else if (limit == 0 || val == pendingLimit) { limit = val; pendingLimit = -1; }
                        else pendingLimit = val;
                    }
                });
            }
        });
    }

    /** Distance affichée (« 300 m », « 1,2 km »). */
    static String dist(double m) {
        if (m < 20) return "0 m";
        if (m < 300) return (Math.round(m / 10) * 10) + " m";
        if (m < 950) return (Math.round(m / 50) * 50) + " m";
        double km = m / 1000;
        return km < 10 ? String.format(Locale.FRANCE, "%.1f km", km) : Math.round(km) + " km";
    }
}

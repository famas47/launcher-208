package be.pa.launcher208;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

/**
 * Banc d'essai de la vitesse affichée (sur ordinateur) : des trajets réalistes (ville, autoroute, accélérations
 * franches), un GPS simulé (1, 5 ou 10 mesures par seconde, bruit, retard), et l'écart entre le chiffre affiché
 * et la vraie vitesse, pour l'ancienne façon (chiffre du dernier point GPS, mis à jour chaque seconde) et Speedo.
 *
 * javac -d /tmp/banc app/src/main/java/be/pa/launcher208/Speedo.java outils/vitesse/Banc.java
 * java -cp /tmp/banc be.pa.launcher208.Banc
 */
public class Banc {
    // ---------- trajets : vitesse vraie (m/s) par pas de 10 ms ----------
    static final double DT = 0.01;
    static boolean DEBUG = false;

    /**
     * Suite de consignes (vitesse km/h, durée de maintien s). Accélération limitée ; si jerk > 0, elle varie
     * progressivement (conduite souple) ; sinon elle change d'un coup (accélérations franches).
     */
    static double[] profile(double[][] legs, double accUp, double accDown, double jerk) {
        ArrayList<Double> out = new ArrayList<>();
        double v = 0, a = 0;
        for (double[] leg : legs) {
            double target = leg[0] / 3.6, hold = leg[1];
            for (int k = 0; k < 20000; k++) {
                double err = target - v;
                if (Math.abs(err) < 0.005) break;
                double want = err > 0 ? accUp : -accDown;
                if (jerk > 0) {
                    // freine l'accélération à l'approche : |a| <= sqrt(2 jerk |err|)
                    double lim = Math.sqrt(2 * jerk * Math.abs(err));
                    want = Math.max(-lim, Math.min(lim, want));
                    a += Math.max(-jerk * DT, Math.min(jerk * DT, want - a));
                } else a = want;
                double nv = v + a * DT;
                if ((err > 0 && nv > target) || (err < 0 && nv < target)) nv = target;
                v = Math.max(0, nv);
                out.add(v);
            }
            v = target;
            a = 0;
            for (int k = 0; k < hold / DT; k++) out.add(v);
        }
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static double at(double[] p, double s) {
        if (s <= 0) return p[0];
        int i = (int) (s / DT);
        if (i >= p.length - 1) return p[p.length - 1];
        double f = s / DT - i;
        return p[i] + (p[i + 1] - p[i]) * f;
    }

    static double pct(ArrayList<Double> v, double q) {
        if (v.isEmpty()) return 0;
        ArrayList<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) (c.size() * q)));
    }

    static double mean(ArrayList<Double> v) { double s = 0; for (double d : v) s += d; return v.isEmpty() ? 0 : s / v.size(); }

    static final class Res {
        ArrayList<Double> err = new ArrayList<>();
        double over = 0;
        int changesCruise = 0;
        double cruiseS = 0;
        int same = 0, total = 0;
        ArrayList<Double> errCruise = new ArrayList<>();
        int sameCruise = 0;
        String fmt() {
            return String.format(Locale.ROOT, "tout : écart %.2f km/h (95%% sous %.1f, dépasse au plus de %.1f), même chiffre %3.0f%% | vitesse stable : écart %.2f, même chiffre %3.0f%%, changements %.1f/min",
                    mean(err), pct(err, .95), over, 100.0 * same / Math.max(1, total),
                    mean(errCruise), 100.0 * sameCruise / Math.max(1, errCruise.size()), changesCruise / Math.max(1e-9, cruiseS / 60));
        }
    }

    /**
     * @param hz     mesures GPS par seconde
     * @param sigma  bruit de la vitesse mesurée (m/s)
     * @param chip   retard propre de la puce (s) : sa vitesse est celle d'un peu avant
     * @param dash   retard du compteur (s)
     * @param old    ancienne façon (dernier point, affiché à chaque seconde)
     */
    static Res run(double[] prof, double hz, double sigma, double chip, double dash, boolean old, Speedo sp, long seed) {
        Random rnd = new Random(seed);
        Res r = new Res();
        double end = prof.length * DT;
        double nextFix = rnd.nextDouble() / hz;
        double lastFixV = 0;
        double nextTick = rnd.nextDouble();
        int shownOld = -1, prevShown = Integer.MIN_VALUE;
        double recvLag = 0.15; // la position arrive 0,15 s après la mesure
        ArrayList<double[]> pending = new ArrayList<>();
        if (sp != null) sp.reset();
        for (double now = 0; now < end; now += 0.1) { // écran mis à jour 10 fois par seconde
            // mesures faites jusqu'ici, reçues après recvLag
            while (nextFix <= now) {
                double tv = at(prof, nextFix - chip);
                double m = Math.max(0, tv + rnd.nextGaussian() * sigma);
                if (m * 3.6 < 1) m = 0; // comme LocationTracker (seuil 1 km/h)
                pending.add(new double[]{nextFix, m});
                nextFix += 1.0 / hz;
            }
            for (int i = 0; i < pending.size(); ) {
                double[] f = pending.get(i);
                if (f[0] + recvLag <= now) {
                    if (sp != null) sp.onFix(f[0] * 1000, f[1]);
                    lastFixV = f[1];
                    pending.remove(i);
                } else i++;
            }
            int shown;
            double est;
            if (old) {
                if (now >= nextTick) { shownOld = (int) Math.round(lastFixV * 3.6); nextTick += 1.0; }
                shown = shownOld;
                est = shownOld;
            } else {
                shown = sp.shown(now * 1000, 0);
                double m = sp.mps(now * 1000);
                est = m < 0 ? -1 : m * 3.6;
            }
            if (now < 3 || shown < 0) { prevShown = shown; continue; }
            double ref = at(prof, now - dash) * 3.6;
            r.err.add(Math.abs(shown - ref));
            double hi = 0;
            for (double b = 0; b <= 1.5; b += 0.05) hi = Math.max(hi, at(prof, now - b) * 3.6);
            r.over = Math.max(r.over, est - hi); // au-dessus de toute vitesse récente : vrai dépassement
            r.total++;
            if (shown == (int) Math.round(ref)) r.same++;
            // croisière : vraie vitesse stable sur ±1 s
            boolean cruise = Math.abs(at(prof, now - 3) - at(prof, now)) < 0.01 && Math.abs(at(prof, now - 1.5) - at(prof, now)) < 0.01 && Math.abs(at(prof, now + 1) - at(prof, now)) < 0.01 && ref > 10;
            if (cruise) {
                r.cruiseS += 0.1;
                r.errCruise.add(Math.abs(shown - ref));
                if (shown == (int) Math.round(ref)) r.sameCruise++;
                if (prevShown != Integer.MIN_VALUE && shown != prevShown) { r.changesCruise++; if (DEBUG) System.out.printf(Locale.ROOT, "    chg t=%.1f ref=%.2f est=%.2f %d->%d%n", now, ref, est, prevShown, shown); }
            }
            prevShown = shown;
        }
        return r;
    }

    public static void main(String[] args) {
        double[][] city = {{0, 3}, {50, 12}, {30, 6}, {50, 15}, {0, 6}, {35, 8}, {50, 10}, {0, 5}};
        double[][] road = {{0, 3}, {90, 20}, {70, 8}, {90, 15}, {120, 30}, {90, 10}, {50, 8}, {0, 4}};
        double[][] sharp = {{0, 3}, {50, 10}, {0, 4}, {50, 8}, {20, 5}, {50, 6}, {0, 4}};
        double[][] profs = {profile(city, 2.0, 3.0, 2.5), profile(road, 2.0, 3.0, 2.5), profile(sharp, 2.5, 3.0, 0)};
        System.out.println("Ville, route et autoroute (conduite souple), accélérations franches ; puce sans retard et avec 0,3 s de retard.");
        for (double hz : new double[]{1, 5, 10}) for (double sg : new double[]{0.1, 0.3}) {
            Res o = new Res(), n = new Res();
            for (double[] prof : profs) for (double ch : new double[]{0, 0.3}) for (int seed = 1; seed <= 6; seed++) {
                merge(o, run(prof, hz, sg, ch, 0.0, true, null, seed));
                merge(n, run(prof, hz, sg, ch, 0.0, false, new Speedo(), seed));
            }
            System.out.printf(Locale.ROOT, "%2.0f mesures/s, bruit %.1f m/s%n", hz, sg);
            System.out.println("   avant : " + o.fmt());
            System.out.println("   après : " + n.fmt());
        }
    }

    static void merge(Res into, Res r) {
        into.err.addAll(r.err);
        into.over = Math.max(into.over, r.over);
        into.changesCruise += r.changesCruise;
        into.cruiseS += r.cruiseS;
        into.same += r.same;
        into.total += r.total;
        into.errCruise.addAll(r.errCruise);
        into.sameCruise += r.sameCruise;
    }
}

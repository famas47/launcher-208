package be.pa.launcher208;

/**
 * Vitesse affichée en km/h, au plus près du compteur de la voiture.
 *
 *  - La vitesse vient de la puce GPS (mesurée par effet Doppler : précise à quelques dixièmes de km/h).
 *  - Les mesures des 1,7 dernières secondes donnent la vitesse et l'accélération ; la mesure arrivant un peu
 *    en retard, la vitesse est avancée jusqu'à « maintenant », de moins en moins loin (peu de dépassement quand
 *    la voiture arrête d'accélérer).
 *  - Si la pente reste dans ce que le bruit de la puce peut donner (bruit appris en roulant), la vitesse est
 *    stable : moyenne sur 3 s, le chiffre reste calme.
 *  - Le compteur d'une voiture affiche toujours un peu plus que la vraie vitesse (règlement européen R39 :
 *    jamais moins, au plus 10 % + 4 km/h de plus). Chez Peugeot, c'est en général 2 à 5 km/h de plus :
 *    l'écart est réglable (Réglages → Mode conduite), +3 km/h au départ. Sous 50 km/h, il diminue jusqu'à 0.
 *  - Le chiffre ne change que si la vitesse s'en écarte nettement (pas de clignotement 49 / 50).
 *
 * Aucune dépendance à Android : testé aussi sur ordinateur (outils/vitesse).
 */
final class Speedo {
    // Réglages (modifiables par le banc d'essai)
    double windowMs = 1700;     // mesures utilisées pour la tendance (accélération)
    double steadyMs = 3000;     // vitesse stable : moyenne plus longue (chiffre bien calme)
    double deadA = 0.4;        // m/s² : accélération sous laquelle la vitesse est considérée stable
    double tauMs = 400;         // avance de moins en moins loin (constante de temps)
    double maxAheadMs = 1500;   // au plus 1,5 s d'avance
    double staleMs = 5000;      // plus de mesure depuis 5 s : vitesse inconnue (tiret)
    double hyst = 0.3;          // km/h de marge avant de changer de chiffre

    private static final int CAP = 128;
    private final double[] t = new double[CAP], v = new double[CAP];
    private int start, count;
    private int shown = -1;
    private double noise = 0.12; // bruit estimé de la vitesse mesurée (m/s), appris en roulant

    synchronized void reset() {
        start = 0;
        count = 0;
        shown = -1;
        noise = 0.12;
    }

    /**
     * Nouvelle mesure.
     * @param ms  moment de la mesure (ms, horloge monotone)
     * @param mps vitesse (m/s), 0 à l'arrêt
     */
    synchronized void onFix(double ms, double mps) {
        if (count > 0) {
            double last = t[(start + count - 1) % CAP];
            if (ms <= last) {
                if (ms < last - 3000) reset(); // horloge repartie (redémarrage) : on recommence
                else return;                   // mesure en double ou en retard
            }
        }
        if (count >= 2) {
            // bruit de la puce : écart à la droite des deux mesures précédentes (les variations régulières de
            // vitesse s'annulent), si les mesures sont régulièrement espacées
            int i1 = (start + count - 1) % CAP, i2 = (start + count - 2) % CAP;
            double d1 = ms - t[i1], d2 = t[i1] - t[i2];
            if (d1 > 0 && d2 > 0 && Math.abs(d1 - d2) < 0.2 * Math.max(d1, d2) && d1 < 2500 && mps > 0 && v[i1] > 0 && v[i2] > 0) {
                double dd = mps - 2 * v[i1] + v[i2]; // vaut 6 fois la variance du bruit en moyenne
                if (Math.abs(dd) < 2.0) {
                    double n2 = noise * noise;
                    n2 += 0.04 * (Math.max(0.0016, Math.min(1.0, dd * dd / 6)) - n2);
                    noise = Math.sqrt(n2);
                }
            }
        }
        if (count == CAP) { start = (start + 1) % CAP; count--; }
        int i = (start + count) % CAP;
        t[i] = ms;
        v[i] = Math.max(0, mps);
        count++;
        double keep = Math.max(windowMs, steadyMs);
        while (count > 1 && ms - t[start] > keep) { start = (start + 1) % CAP; count--; }
    }

    /** Vraie vitesse estimée (m/s) au moment now (ms, même horloge), ou -1 si inconnue. */
    synchronized double mps(double now) {
        if (count == 0) return -1;
        int li = (start + count - 1) % CAP;
        double lt = t[li], lv = v[li];
        if (now - lt > staleMs) return -1;
        if (lv <= 0) return 0; // la puce dit « arrêté » : 0 tout de suite
        // droite des moindres carrés sur les mesures récentes (temps relatifs à la dernière)
        double[] f = fit(lt, windowMs);
        double fit = f == null ? lv : f[0], a = f == null ? 0 : f[1];
        // pente plus faible que ce que le bruit peut donner : vitesse stable
        if (steadyMs > windowMs && (f == null || Math.abs(a) < deadA + 2 * noise / Math.sqrt(f[2]))) {
            // la même droite sur plus longtemps, si elle confirme : bruit bien plus faible, chiffre calme
            double[] g = fit(lt, steadyMs);
            if (g != null && Math.abs(g[1]) < 0.6 * deadA + 2 * noise / Math.sqrt(g[2])) return Math.max(g[0], 0.3);
        }
        a = Math.max(-8, Math.min(5, a));
        // petite accélération : bruit de mesure, vitesse stable
        a = a > 0 ? Math.max(0, a - deadA) : Math.min(0, a + deadA);
        double h = Math.max(0, Math.min(maxAheadMs, now - lt)) / 1000.0, tau = tauMs / 1000.0;
        double ahead = tau > 0 ? tau * (1 - Math.exp(-h / tau)) : h;
        double est = fit + a * ahead;
        // pendant un freinage, ne pas annoncer l'arrêt avant la puce
        return Math.max(est, lv > 0 ? 0.3 : 0);
    }

    /** Droite des moindres carrés sur les mesures des dernières windowMs : {valeur au temps lt, pente}, ou null. */
    private double[] fit(double lt, double windowMs) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int nn = 0;
        for (int k = count - 1; k >= 0; k--) {
            int j = (start + k) % CAP;
            if (lt - t[j] > windowMs) break;
            double x = (t[j] - lt) / 1000.0, y = v[j];
            sx += x; sy += y; sxx += x * x; sxy += x * y;
            nn++;
        }
        if (nn < 2) return null;
        double den = nn * sxx - sx * sx;
        if (den < 1e-9) return null;
        double a = (nn * sxy - sx * sy) / den;
        // den / nn = somme des (x - moyenne)² : sert à savoir si la pente est plus forte que le bruit
        return new double[]{(sy - a * sx) / nn, a, den / nn};
    }

    /** Bruit estimé de la vitesse mesurée par la puce (m/s). */
    synchronized double noise() { return noise; }

    /** Vitesse du compteur (km/h) pour une vraie vitesse (km/h) et l'écart réglé (km/h, atteint dès 50 km/h). */
    static double dash(double kmh, int offset) {
        if (kmh <= 0) return 0;
        return kmh + offset * Math.min(1.0, kmh / 50.0);
    }

    /** Chiffre à afficher (km/h, comme le compteur), -1 si inconnu. */
    synchronized int shown(double now, int offset) {
        double m = mps(now);
        if (m < 0) { shown = -1; return -1; }
        double k = dash(m * 3.6, offset);
        // sous 1 km/h la puce est trop imprécise : 0
        int r = k < 1 ? 0 : (int) Math.round(k);
        if (shown < 0 || r == 0 || Math.abs(k - shown) >= 0.5 + hyst) shown = r;
        return shown;
    }
}

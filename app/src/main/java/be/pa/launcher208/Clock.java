package be.pa.launcher208;

/**
 * Heure affichée par le launcher.
 * L'autoradio perd son heure quand il s'éteint complètement : on corrige avec l'heure du GPS,
 * et on affiche --:-- tant qu'on n'est pas sûr.
 */
final class Clock {
    private static volatile long gpsOffset;
    private static volatile boolean gpsValid;

    static long now() {
        long s = System.currentTimeMillis();
        long o = gpsOffset;
        if (gpsValid && Math.abs(o) > 30000L) return s + o;
        return s;
    }

    /** Vrai si l'heure est fiable (GPS reçu, ou horloge de l'autoradio plus récente que cette version). */
    static boolean trusted() {
        if (gpsValid) return true;
        return System.currentTimeMillis() > BuildConfig.BUILD_TIME - 86400000L;
    }

    static boolean fromGps() { return gpsValid; }

    static void onGpsTime(long gpsUtc) {
        if (gpsUtc < 1577836800000L) return; // avant 2020 : valeur impossible
        gpsOffset = gpsUtc - System.currentTimeMillis();
        gpsValid = true;
    }
}

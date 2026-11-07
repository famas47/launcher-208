package be.pa.launcher208;

/**
 * Heure affichée par le launcher : celle de l'autoradio, exactement comme le tableau de bord de la voiture
 * (qui la reçoit de l'autoradio). Rien n'est corrigé par le GPS.
 * Si elle paraît fausse, le launcher le signale et propose de la régler (écran « Date et heure » d'Android).
 */
final class Clock {
    private static volatile long gpsUtc;       // dernière heure GPS reçue (seulement pour le mode test)
    private static volatile long gpsAtElapsed;

    static long now() { return System.currentTimeMillis(); }

    /**
     * L'heure de l'autoradio paraît fausse : plus ancienne que la fabrication de cette version du launcher,
     * ou différente de plus de 2 minutes de l'heure lue sur Internet.
     */
    static boolean suspicious() {
        long s = System.currentTimeMillis();
        if (s < BuildConfig.BUILD_TIME - 86400000L) return true;
        if (wrongZone()) return true;
        return Net.realKnown() && Math.abs(Net.offset()) > 2L * 60000L;
    }

    /** Fuseau horaire de l'autoradio différent de celui de la Belgique (heure d'été comprise). */
    static boolean wrongZone() {
        long s = System.currentTimeMillis();
        return java.util.TimeZone.getDefault().getOffset(s) != Fmt.BRUSSELS.getOffset(s);
    }

    /** Heure réelle si on la connaît (Internet), sinon -1. Sert seulement au conseil « il est en réalité… ». */
    static long realOrUnknown() { return Net.realKnown() ? Net.realNow() : -1; }

    static void onGpsTime(long utc) {
        if (utc < 1577836800000L) return; // avant 2020 : valeur impossible
        gpsUtc = utc;
        gpsAtElapsed = android.os.SystemClock.elapsedRealtime();
    }

    /** Heure du GPS (mode test seulement), -1 si jamais reçue. */
    static long gpsNow() {
        long g = gpsUtc;
        return g == 0 ? -1 : g + (android.os.SystemClock.elapsedRealtime() - gpsAtElapsed);
    }
}

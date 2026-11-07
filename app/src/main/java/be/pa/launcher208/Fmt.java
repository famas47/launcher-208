package be.pa.launcher208;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Mise en forme en français, dans le fuseau horaire de l'autoradio (comme son heure et le tableau de bord). */
final class Fmt {
    static final TimeZone BRUSSELS = TimeZone.getTimeZone("Europe/Brussels");

    /** Fuseau de l'autoradio (relu à chaque fois : il peut être changé dans les réglages d'Android). */
    static TimeZone tz() { return TimeZone.getDefault(); }
    static final Locale FR = Locale.FRANCE;
    private static final String[] DAYS = {"Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi"};
    private static final String[] DAYS_S = {"D", "L", "M", "M", "J", "V", "S"};
    private static final String[] MONTHS = {"janvier", "février", "mars", "avril", "mai", "juin", "juillet",
            "août", "septembre", "octobre", "novembre", "décembre"};

    static Calendar cal(long t) {
        Calendar c = Calendar.getInstance(tz(), FR);
        c.setTimeInMillis(t);
        return c;
    }

    /** Heure à Bruxelles (pour le conseil « il est en réalité… », même si le fuseau de l'autoradio est faux). */
    static String hhmmBrussels(long t) {
        Calendar c = Calendar.getInstance(BRUSSELS, FR);
        c.setTimeInMillis(t);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    static String hhmm(long t) {
        Calendar c = cal(t);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
    }

    static String dayLong(long t) {
        Calendar c = cal(t);
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    static String dateShort(long t) {
        Calendar c = cal(t);
        return c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    static String dayLetter(long t) { return DAYS_S[cal(t).get(Calendar.DAY_OF_WEEK) - 1]; }

    static int minuteOfDay(long t) {
        Calendar c = cal(t);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Début de la journée (minuit à Bruxelles). */
    static long dayStart(long t) {
        Calendar c = cal(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    static String hm(int minutes) { return Fmt.two(minutes / 60) + ":" + Fmt.two(minutes % 60); }

    /** 12,4 */
    static String km1(double km) {
        long t = Math.round(km * 10);
        return (t / 10) + "," + (t % 10);
    }

    /** 12,4 sous 100 km, sinon 142 */
    static String km(double km) { return km < 100 ? km1(km) : String.valueOf(Math.round(km)); }

    /** 48 250 (espace fine entre les milliers) */
    static String big(long v) {
        String s = String.valueOf(Math.abs(v));
        StringBuilder b = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (v < 0 ? "-" : "") + b;
    }

    /** 34 min, 1 h 05 */
    static String dur(long minutes) {
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h " + two((int) (minutes % 60));
    }
}

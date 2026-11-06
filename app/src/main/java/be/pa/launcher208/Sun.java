package be.pa.launcher208;

import java.util.Calendar;
import java.util.TimeZone;

/** Lever et coucher du soleil (calcul hors ligne, précision de quelques minutes). */
final class Sun {
    private static final double ZENITH = 90.833;

    /** Minutes depuis minuit (heure de Bruxelles) du lever [0] et du coucher [1], ou null (jour ou nuit polaire). */
    static int[] riseSet(long now, double lat, double lon) {
        Calendar local = Fmt.cal(now);
        int n = local.get(Calendar.DAY_OF_YEAR);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        long midnightUtc = utc.getTimeInMillis();
        double rise = ut(n, lat, lon, true), set = ut(n, lat, lon, false);
        if (Double.isNaN(rise) || Double.isNaN(set)) return null;
        int r = Fmt.minuteOfDay(midnightUtc + (long) (rise * 3600000.0));
        int s = Fmt.minuteOfDay(midnightUtc + (long) (set * 3600000.0));
        return new int[]{r, s};
    }

    static boolean isNight(long now, double lat, double lon) {
        int[] rs = riseSet(now, lat, lon);
        int m = Fmt.minuteOfDay(now);
        if (rs == null) {
            int month = Fmt.cal(now).get(Calendar.MONTH);
            boolean summer = month >= 3 && month <= 8;
            return lat >= 0 ? !summer : summer;
        }
        if (rs[0] < rs[1]) return m < rs[0] || m >= rs[1];
        return m >= rs[1] && m < rs[0];
    }

    private static double ut(int n, double lat, double lon, boolean rising) {
        double lngHour = lon / 15.0;
        double t = n + ((rising ? 6.0 : 18.0) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm360(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634);
        double ra = norm360(Math.toDegrees(Math.atan(0.91764 * tan(l))));
        double lq = Math.floor(l / 90.0) * 90.0, rq = Math.floor(ra / 90.0) * 90.0;
        ra = (ra + (lq - rq)) / 15.0;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(ZENITH) - sinDec * sin(lat)) / (cosDec * cos(lat));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double h = rising ? 360.0 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        h /= 15.0;
        double tt = h + ra - 0.06571 * t - 6.622;
        double u = tt - lngHour;
        u = u % 24.0;
        if (u < 0) u += 24.0;
        return u;
    }

    private static double sin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double tan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double norm360(double d) { d = d % 360.0; return d < 0 ? d + 360.0 : d; }
}

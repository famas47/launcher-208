package be.pa.launcher208;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Jour ou nuit ?
 * - Phares : quand l'autoradio baisse sa luminosité (phares allumés). Si ce n'est jamais observé,
 *   on se base sur le soleil.
 * - Soleil : lever et coucher calculés pour ta position.
 * - Heures : plage horaire choisie.
 */
final class Night {
    private final ContentResolver cr;
    private boolean started;
    Runnable onChange;

    Night(ContentResolver cr) { this.cr = cr; }

    void start() {
        if (started) return;
        started = true;
        learn(brightness());
        try {
            Uri u = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
            cr.registerContentObserver(u, false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    learn(brightness());
                    if (onChange != null) onChange.run();
                }
            });
        } catch (Exception ignored) { }
    }

    int brightness() {
        try { return Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1); } catch (Exception e) { return -1; }
    }

    private void learn(int b) {
        if (b < 0) return;
        int max = P.i(P.BR_MAX, -1), min = P.i(P.BR_MIN, -1);
        if (max < 0 || b > max) P.put(P.BR_MAX, b);
        if (min < 0 || b < min) P.put(P.BR_MIN, b);
    }

    /** Vrai si les phares ont déjà fait varier la luminosité (sinon on ne peut pas les détecter). */
    boolean headlightsKnown() {
        int max = P.i(P.BR_MAX, -1), min = P.i(P.BR_MIN, -1);
        return max >= 0 && min >= 0 && max - min >= 10;
    }

    boolean isNight(long now) {
        int th = P.theme();
        if (th == 1) return false;
        if (th == 2) return true;
        int by = P.nightBy();
        if (by == 2) {
            int m = Fmt.minuteOfDay(now);
            int from = P.i(P.NIGHT_FROM, 20 * 60), to = P.i(P.NIGHT_TO, 7 * 60);
            if (from == to) return false;
            return from > to ? (m >= from || m < to) : (m >= from && m < to);
        }
        boolean sun = Sun.isNight(now, P.lat(), P.lon());
        if (by == 1 || !headlightsKnown()) return sun;
        int cur = brightness();
        if (cur < 0) return sun;
        int max = P.i(P.BR_MAX, 255), min = P.i(P.BR_MIN, 0);
        return cur <= min + (max - min) * 0.4f;
    }
}

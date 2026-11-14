package be.pa.launcher208;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;

/**
 * GPS : vitesse, position, heure, trajets.
 * Tourne dans le service du launcher, donc aussi quand CarPlay est à l'écran.
 */
final class LocationTracker implements LocationListener {
    private final Context c;
    private final TripStore trips;
    private final LocationManager lm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private boolean running;
    private Object gnssCb;

    volatile float speedKmh;      // vitesse affichée
    volatile float bearing;
    volatile int sats, satsUsed;
    volatile long lastFixUptime;   // SystemClock.uptimeMillis() du dernier point GPS
    volatile double lat, lon;
    volatile float accuracy = -1;
    String street = "";            // rempli par la minimap

    private Location prev;
    private long stopSince;        // début de l'arrêt pendant un trajet (uptime)
    private long stopSinceWall;    // même moment, en heure réelle
    private long lastFixWall;      // heure réelle du dernier point GPS
    private int fixCount;
    private float pendingKm;       // km GPS pas encore enregistrés
    static final float START_KMH = 10f, STOP_KMH = 3f;
    /** Vitesse d'activation du mode conduite si rien n'est réglé (le réglage, 1 km/h par défaut, est dans P.driveKmh()). */
    static final float DRIVE_KMH = 5f;
    static final long TRIP_END_MS = 3L * 60000L;

    LocationTracker(Context c, TripStore trips) {
        this.c = c.getApplicationContext();
        this.trips = trips;
        this.lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (P.hasPos()) { lat = P.lat(); lon = P.lon(); }
    }

    boolean permitted() {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasFix() { return lastFixUptime > 0 && SystemClock.uptimeMillis() - lastFixUptime < 5000; }

    @SuppressLint("MissingPermission")
    void start() {
        if (running || lm == null || !permitted()) return;
        try {
            if (lm.getProvider(LocationManager.GPS_PROVIDER) != null) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
                running = true;
            }
            // Après un démarrage complet de l'autoradio, le GPS repart de zéro : on lui demande de récupérer
            // ses données d'aide (heure et position des satellites) pour trouver sa position plus vite.
            // Sans effet si la puce GPS de l'autoradio ne le gère pas.
            try { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null); } catch (Exception ignored) { }
            try { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null); } catch (Exception ignored) { }
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && !P.hasPos()) savePos(last.getLatitude(), last.getLongitude());
            if (Build.VERSION.SDK_INT >= 24) {
                GnssStatus.Callback cb = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus s) {
                        int n = s.getSatelliteCount(), u = 0;
                        for (int i = 0; i < n; i++) if (s.usedInFix(i)) u++;
                        sats = n;
                        satsUsed = u;
                    }
                };
                lm.registerGnssStatusCallback(cb, h);
                gnssCb = cb;
            }
        } catch (Exception ignored) { }
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 5000);
    }

    void stop() {
        if (lm == null) return;
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 24 && gnssCb != null) {
            try { lm.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCb); } catch (Exception ignored) { }
        }
        gnssCb = null;
        running = false;
        h.removeCallbacks(watchdog);
    }

    boolean running() { return running; }

    /** Vérifie chaque 5 s : vitesse à 0 si plus de GPS, fin de trajet si arrêt prolongé. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            if (lastFixUptime > 0 && now - lastFixUptime > 4000 && speedKmh != 0) {
                speedKmh = 0;
                fire();
            }
            if (trips.curStart > 0) {
                boolean stale = lastFixUptime == 0 || now - lastFixUptime > TRIP_END_MS;
                boolean stoppedLong = stopSince > 0 && now - stopSince > TRIP_END_MS;
                if (stoppedLong) endTrip(stopSinceWall);
                else if (stale) endTrip(lastFixWall > 0 ? lastFixWall : Clock.now());
            }
            h.postDelayed(this, 5000);
        }
    };

    @Override public void onLocationChanged(Location l) {
        long nowUp = SystemClock.uptimeMillis();
        Clock.onGpsTime(l.getTime());
        float kmh;
        boolean doppler = l.hasSpeed(); // vitesse mesurée par la puce GPS (précise même très lentement)
        if (doppler) kmh = l.getSpeed() * 3.6f;
        else if (prev != null && l.getTime() > prev.getTime() && (!l.hasAccuracy() || l.getAccuracy() < 25)) {
            kmh = prev.distanceTo(l) / ((l.getTime() - prev.getTime()) / 1000f) * 3.6f; // GPS sans vitesse : on la calcule
        }
        else kmh = 0;
        // à l'arrêt, le GPS « bouge » un peu : on ignore les toutes petites vitesses. La vitesse calculée à partir
        // des positions est trop imprécise sous 2 km/h ; celle de la puce suit le réglage (1 km/h possible).
        float floor = doppler ? Math.min(2f, P.driveKmh()) : 2f;
        if (kmh < floor) kmh = 0;
        speedKmh = kmh;
        // Cap de la puce GPS s'il est donné ; certaines puces annoncent un cap (et parfois une vitesse) mais
        // renvoient toujours 0 : dans ce cas, on le calcule à partir du point précédent, si la voiture a
        // vraiment avancé (plus de 8 m, à plus de 7 km/h : pas le petit « bougé » du GPS à l'arrêt).
        if (kmh > 3 && l.hasBearing() && l.getBearing() != 0f) {
            bearing = l.getBearing();
        } else if (prev != null && l.getTime() > prev.getTime()) {
            float d = prev.distanceTo(l);
            float mps = d / ((l.getTime() - prev.getTime()) / 1000f);
            if (d > 8f && mps > 2f) {
                float b = prev.bearingTo(l);
                bearing = b < 0 ? b + 360f : b;
            }
        }
        accuracy = l.hasAccuracy() ? l.getAccuracy() : -1;
        lat = l.getLatitude();
        lon = l.getLongitude();
        lastFixUptime = nowUp;
        lastFixWall = Clock.now();

        // Distance parcourue (on ignore les points imprécis et le « bruit » à l'arrêt)
        if (prev != null && (!l.hasAccuracy() || l.getAccuracy() < 30)) {
            float d = prev.distanceTo(l);
            if (kmh > STOP_KMH && d > 1f && d < 200f) {
                float dk = d / 1000f;
                pendingKm += dk;
                if (trips.curStart > 0) trips.curKm += dk;
            }
        }
        if (prev == null || !l.hasAccuracy() || l.getAccuracy() < 30) prev = l;

        // Trajets
        long now = Clock.now();
        if (trips.curStart == 0 && kmh >= START_KMH) {
            trips.curStart = now;
            trips.curKm = 0;
            trips.curMax = 0;
            stopSince = 0;
        }
        if (trips.curStart > 0) {
            if (kmh > trips.curMax) trips.curMax = kmh;
            if (kmh < STOP_KMH) {
                if (stopSince == 0) { stopSince = nowUp; stopSinceWall = now; }
            } else stopSince = 0;
        }
        if (pendingKm > 0.5f) flushKm();
        if (++fixCount % 30 == 0 || !P.hasPos()) savePos(lat, lon);
        fire();
    }

    private void flushKm() {
        if (pendingKm <= 0) return;
        P.put(P.GPS_KM, P.f(P.GPS_KM, 0f) + pendingKm);
        pendingKm = 0;
    }

    private void endTrip(long end) {
        long start = trips.curStart;
        if (start == 0) return;
        float km = trips.curKm, max = trips.curMax;
        trips.curStart = 0;
        trips.curKm = 0;
        trips.curMax = 0;
        stopSince = 0;
        flushKm();
        if (km >= 0.2f && end > start) trips.add(new TripStore.Trip(start, end, km, max));
        // Voiture garée ici
        P.put(P.PARK_TIME, end);
        P.putPos(P.PARK_LAT, P.PARK_LON, lat, lon);
        savePos(lat, lon);
        fire();
    }

    /** Appelé quand l'autoradio s'endort : on clôt le trajet proprement. */
    void onSleep() {
        if (trips.curStart > 0) endTrip(stopSince > 0 ? stopSinceWall : (lastFixWall > 0 ? lastFixWall : Clock.now()));
        flushKm();
    }

    private void savePos(double la, double lo) {
        if (la == 0 && lo == 0) return;
        P.putPos(P.LAST_LAT, P.LAST_LON, la, lo);
    }

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void remove(Runnable r) { listeners.remove(r); }

    private void fire() { for (int i = 0; i < listeners.size(); i++) listeners.get(i).run(); }

    /** Kilométrage estimé : valeur saisie + km mesurés par le GPS depuis. */
    static long odometer() {
        int base = P.i(P.ODO_BASE, 0);
        if (base <= 0) return 0;
        float gps = P.f(P.GPS_KM, 0f) - P.f(P.ODO_GPS_AT, 0f);
        return base + Math.max(0, Math.round(gps));
    }

    // Ancienne interface LocationListener (obligatoire avant Android 10)
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
}

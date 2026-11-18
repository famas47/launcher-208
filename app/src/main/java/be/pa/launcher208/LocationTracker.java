package be.pa.launcher208;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;

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
    private Object gnssCb, nmeaCb;
    private HandlerThread nmeaThread;

    volatile float speedKmh;      // vitesse affichée
    volatile float bearing;
    volatile int sats, satsUsed;
    volatile long lastFixUptime;   // SystemClock.uptimeMillis() du dernier point GPS
    volatile double lat, lon;
    volatile float accuracy = -1;
    String street = "";            // rempli par la minimap

    /** Reçoit chaque position GPS, aussi souvent que la puce en donne (pour la carte du mode conduite). */
    interface FixListener {
        /**
         * @param monoMs   moment de la mesure (ms, horloge de System.nanoTime, comme les images de l'écran)
         * @param measured vrai si la vitesse vient de la puce (précise), faux si elle est calculée
         */
        void onFix(double monoMs, double lat, double lon, float mps, boolean measured, float bearing,
                   boolean bearingOk, float accuracy);
    }

    private final ArrayList<FixListener> fixListeners = new ArrayList<>();
    private final long[] fixTimes = new long[32];  // moments des dernières positions (pour la cadence du GPS)
    private int fixTimesN;
    private float calcKmh;          // vitesse calculée à partir des positions (puce sans vitesse)
    private long bearingAt;         // moment où le cap a été mis à jour
    volatile boolean simulating;    // trajet simulé en cours (tests) : le vrai GPS est ignoré

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
                // Aussi souvent que la puce le permet (beaucoup en donnent 1 par seconde, certaines 5 ou 10) :
                // la voiture suit alors la route encore plus précisément sur la carte.
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper());
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
        startNmea();
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
        if (Build.VERSION.SDK_INT >= 24 && nmeaCb != null) {
            try { lm.removeNmeaListener((OnNmeaMessageListener) nmeaCb); } catch (Exception ignored) { }
        }
        nmeaCb = null;
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
        if (simulating) return; // trajet simulé (tests) : on ignore le vrai GPS
        handle(l);
    }

    /** Position d'un trajet simulé (tests seulement). */
    void simFix(Location l) { handle(l); }

    private void handle(Location l) {
        long nowUp = SystemClock.uptimeMillis();
        Clock.onGpsTime(l.getTime());
        // Une puce qui donne plusieurs positions par seconde : la carte les reçoit toutes, mais les calculs
        // de vitesse (sans puce), de trajet et de kilomètres se font une fois par seconde, comme avant.
        boolean slow = prev == null || l.getTime() - prev.getTime() >= 900 || l.getTime() < prev.getTime();
        float kmh;
        boolean doppler = l.hasSpeed(); // vitesse mesurée par la puce GPS (précise même très lentement)
        if (doppler) kmh = l.getSpeed() * 3.6f;
        else if (!slow) kmh = calcKmh;
        else if (prev != null && l.getTime() > prev.getTime() && (!l.hasAccuracy() || l.getAccuracy() < 25)) {
            kmh = prev.distanceTo(l) / ((l.getTime() - prev.getTime()) / 1000f) * 3.6f; // GPS sans vitesse : on la calcule
            calcKmh = kmh;
        }
        else { kmh = 0; calcKmh = 0; }
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
            bearingAt = nowUp;
        } else if (slow && prev != null && l.getTime() > prev.getTime()) {
            float d = prev.distanceTo(l);
            float mps = d / ((l.getTime() - prev.getTime()) / 1000f);
            if (d > 8f && mps > 2f) {
                float b = prev.bearingTo(l);
                bearing = b < 0 ? b + 360f : b;
                bearingAt = nowUp;
            }
        }
        accuracy = l.hasAccuracy() ? l.getAccuracy() : -1;
        // moment réel de la mesure (la position arrive un peu après) : la carte et la vitesse en tiennent compte
        double nowMono = System.nanoTime() / 1e6, mono = nowMono;
        if (Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0) {
            double m = (l.getElapsedRealtimeNanos() - (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) / 1e6;
            if (m <= nowMono && nowMono - m < 1500) mono = m;
        }
        // vitesse affichée : toutes les mesures de la puce (jusqu'à 10 par seconde) ; la vitesse calculée
        // à partir des positions, une fois par seconde. Si la puce envoie ses trames NMEA, la vitesse a déjà
        // été lue dedans, plus tôt (voir plus bas) : la position ne sert alors qu'à mesurer cette avance.
        boolean nmeaOn = nmeaMono > 0 && nowMono - nmeaMono < 3000;
        lastLocEpoch = l.getTime();
        lastLocMono = mono;
        if (nmeaOn) {
            if (Math.abs(l.getTime() - nmeaEpoch) < 60 && !nmeaMatched) {
                nmeaMatched = true;
                double lead = mono - nmeaMono; // > 0 : la trame est arrivée avant la position
                nmeaLeadMs = nmeaLeadMs < -1e8 ? lead : nmeaLeadMs + 0.1 * (lead - nmeaLeadMs);
                // position datée plus tôt que la trame (puce bien faite) : on garde ce moment-là
                if (lead < 0) App.I.speedo.retime(nmeaMono, mono);
            }
        } else if (doppler || slow) {
            App.I.speedo.onFix(mono, kmh / 3.6);
            lastSpeedEpoch = l.getTime();
        }
        lat = l.getLatitude();
        lon = l.getLongitude();
        lastFixUptime = nowUp;
        lastFixWall = Clock.now();
        fixTimes[fixTimesN++ % fixTimes.length] = nowUp;
        if (!fixListeners.isEmpty()) {
            boolean bOk = kmh > 3 && nowUp - bearingAt < 2500;
            for (int i = 0; i < fixListeners.size(); i++) {
                fixListeners.get(i).onFix(mono, lat, lon, kmh / 3.6f, doppler, bearing, bOk, accuracy);
            }
        }
        if (!slow) return;

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

    // ---------- Trames NMEA : la vitesse avant la position ----------
    // Beaucoup de puces GPS d'autoradio envoient leurs trames de texte (NMEA) l'une après l'autre, assez
    // lentement, et Android ne donne la position qu'une fois toutes arrivées. La trame « RMC » (heure, état,
    // vitesse) arrive souvent bien avant : on y lit la vitesse dès qu'elle arrive.
    private volatile long nmeaEpoch = -1;        // heure GPS (ms UTC) de la dernière vitesse lue dans une trame
    private volatile double nmeaMono;            // moment où cette trame est arrivée (ms, System.nanoTime)
    private volatile boolean nmeaMatched;        // la position de ce moment est déjà arrivée
    private volatile long lastSpeedEpoch = -1;   // dernière heure GPS donnée à la vitesse affichée
    private volatile long lastLocEpoch = -1;     // heure GPS de la dernière position…
    private volatile double lastLocMono;         // … et moment de sa mesure
    volatile double nmeaLeadMs = -1e9;           // avance moyenne de la trame sur la position (mode test)
    volatile int nmeaCount;                      // trames de vitesse lues

    private void startNmea() {
        if (Build.VERSION.SDK_INT < 24 || nmeaCb != null || lm == null) return;
        try {
            if (nmeaThread == null) {
                nmeaThread = new HandlerThread("gps-nmea");
                nmeaThread.start();
            }
            OnNmeaMessageListener nl = new OnNmeaMessageListener() {
                @Override public void onNmeaMessage(String message, long timestamp) {
                    if (!simulating) onNmea(message);
                }
            };
            lm.addNmeaListener(nl, new Handler(nmeaThread.getLooper()));
            nmeaCb = nl;
        } catch (Exception ignored) { }
    }

    /** Pour le mode test : la vitesse est-elle lue dans les trames, et avec combien d'avance ? */
    String nmeaText() {
        if (nmeaCount == 0) return "trames NMEA : aucune";
        if (nmeaLeadMs < -1e8) return "vitesse lue dans les trames NMEA";
        long ms = Math.round(nmeaLeadMs);
        return ms >= 20 ? "vitesse lue dans les trames NMEA, " + ms + " ms avant la position"
                : "vitesse lue dans les trames NMEA (sans avance)";
    }

    /** Trame d'un trajet simulé (tests seulement). */
    void simNmea(String m) { onNmea(m); }

    private void onNmea(String m) {
        double mono = System.nanoTime() / 1e6;
        double[] r = parseRmc(m);
        if (r == null) return;
        long epoch = (long) r[0];
        if (epoch == lastSpeedEpoch || epoch == nmeaEpoch) return; // déjà lue (position arrivée avant)
        double kmh = r[1] * 1.852;
        if (kmh < Math.min(2f, P.driveKmh())) kmh = 0; // le petit « bougé » à l'arrêt
        boolean locFirst = epoch == lastLocEpoch; // position de ce moment déjà arrivée (puce bien faite)
        double when = locFirst ? Math.min(mono, lastLocMono) : mono;
        if (locFirst) {
            double lead = lastLocMono - mono;
            nmeaLeadMs = nmeaLeadMs < -1e8 ? lead : nmeaLeadMs + 0.1 * (lead - nmeaLeadMs);
        }
        nmeaEpoch = epoch;
        nmeaMatched = locFirst;
        nmeaMono = when;
        lastSpeedEpoch = epoch;
        nmeaCount++;
        App.I.speedo.onFix(when, kmh / 3.6);
    }

    /**
     * Trame RMC valable : {heure GPS en ms UTC, vitesse en nœuds}, sinon null.
     * $GPRMC,hhmmss.ss,A,lat,N,lon,E,nœuds,cap,jjmmaa,...*CS (GP, GN, GL, BD... selon les satellites)
     */
    static double[] parseRmc(String m) {
        try {
            if (m == null || m.length() < 20 || m.charAt(0) != '$' || !m.regionMatches(3, "RMC", 0, 3)) return null;
            int star = m.indexOf('*');
            if (star > 0 && star + 2 < m.length() + 1) {
                // somme de contrôle : une trame abîmée est ignorée
                int cs = 0;
                for (int i = 1; i < star; i++) cs ^= m.charAt(i);
                String hex = m.substring(star + 1, Math.min(m.length(), star + 3)).trim();
                if (hex.length() == 2 && Integer.parseInt(hex, 16) != cs) return null;
            }
            String[] f = (star > 0 ? m.substring(0, star) : m.trim()).split(",", -1);
            if (f.length < 10 || !"A".equals(f[2]) || f[7].isEmpty() || f[1].length() < 6 || f[9].length() != 6) return null;
            double knots = Double.parseDouble(f[7]);
            if (knots < 0 || knots > 200) return null;
            int hh = Integer.parseInt(f[1].substring(0, 2)), mi = Integer.parseInt(f[1].substring(2, 4));
            double ss = Double.parseDouble(f[1].substring(4));
            int dd = Integer.parseInt(f[9].substring(0, 2)), mo = Integer.parseInt(f[9].substring(2, 4));
            int yy = 2000 + Integer.parseInt(f[9].substring(4, 6));
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(yy, mo - 1, dd, hh, mi, 0);
            return new double[]{c.getTimeInMillis() + Math.round(ss * 1000), knots};
        } catch (Exception e) {
            return null;
        }
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

    void addFix(FixListener f) { if (!fixListeners.contains(f)) fixListeners.add(f); }
    void removeFix(FixListener f) { fixListeners.remove(f); }

    /** Positions GPS reçues par seconde (sur les 10 dernières secondes), 0 si plus de GPS. */
    float fixRate() {
        int n = Math.min(fixTimesN, fixTimes.length);
        if (n < 2) return 0;
        long now = SystemClock.uptimeMillis(), newest = fixTimes[(fixTimesN - 1) % fixTimes.length];
        if (now - newest > 3000) return 0;
        int c = 0;
        long oldest = newest;
        for (int i = 0; i < n; i++) {
            long t = fixTimes[(fixTimesN - 1 - i) % fixTimes.length];
            if (newest - t > 10000) break;
            oldest = t;
            c++;
        }
        return c < 2 || newest == oldest ? 0 : (c - 1) * 1000f / (newest - oldest);
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

package be.pa.launcher208;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Point de départ : objets partagés, créés une seule fois et rapidement. */
public class App extends Application {
    static App I;
    static long processStart;     // démarrage du processus (pour mesurer le démarrage)
    static long firstFrameMs = -1; // temps jusqu'au premier écran affiché
    static long appReady;          // fin de la préparation de l'appli (uptime)

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();

    TripStore trips;
    LocationTracker loc;
    Weather weather;
    AppsRepo apps;
    Volume vol;
    Media media;
    Doors doors;
    Night night;
    MapDl mapdl;
    Updater upd;
    volatile float simSpeed = -1; // mode test : vitesse simulée (-1 = non)

    // Contact mis : allumage de l'autoradio (ou réveil après sa veille). SystemClock.elapsedRealtime().
    volatile long contactAt;
    volatile boolean droveThisContact; // le mode conduite a déjà été affiché depuis ce contact

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        processStart = Build.VERSION.SDK_INT >= 24 ? Process.getStartUptimeMillis() : SystemClock.uptimeMillis();
        // Lancé dans les 10 minutes après l'allumage : on compte depuis l'allumage. Sinon (appli relancée
        // pendant que l'autoradio tournait déjà, ou au réveil), on compte depuis maintenant.
        long up = SystemClock.elapsedRealtime();
        contactAt = up < 10L * 60000L ? 0 : up;
        P.init(this);
        U.init(this);
        trips = new TripStore(this);
        loc = new LocationTracker(this, trips);
        weather = new Weather();
        apps = new AppsRepo(this);
        vol = new Volume(this);
        media = new Media(this);
        doors = new Doors();
        night = new Night(getContentResolver());
        mapdl = new MapDl();
        upd = new Updater();
        appReady = SystemClock.uptimeMillis();
        mark("application prête");
    }

    /** Chronométrage du démarrage, seulement dans l'APK de test (rien dans l'APK de la voiture). */
    static void mark(String what) {
        if (BuildConfig.TEST_HOOKS) {
            android.util.Log.i("Test208", "temps " + what + " " + (SystemClock.uptimeMillis() - processStart) + " ms");
        }
    }

    /** L'autoradio se réveille (contact remis) : nouveau départ pour « Activer après le contact ». */
    void contactOn() {
        contactAt = SystemClock.elapsedRealtime();
        droveThisContact = false;
    }

    long sinceContact() { return SystemClock.elapsedRealtime() - contactAt; }

    /** Une vitesse est disponible (GPS qui capte, ou vitesse simulée). */
    boolean speedKnown() { return simSpeed >= 0 || loc.hasFix(); }

    /** Vitesse utilisée par l'écran (GPS, ou simulée en mode test). */
    float speed() {
        float s = simSpeed;
        return s >= 0 ? s : loc.speedKmh;
    }

    /** Enregistre un récepteur d'annonces, compatible avec toutes les versions d'Android. */
    static void reg(Context c, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f);
    }
}

package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * « Écoute du volume » (mode test) : pendant 90 secondes, note tout ce qui change quand tu règles le son
 * avec les boutons de l'autoradio (barre du haut, volant, bouton rotatif). Ça montre comment ton autoradio
 * règle le vrai son, pour que le launcher puisse faire pareil. Rien n'est modifié, on regarde seulement.
 */
final class VolumeSpy {
    private static final long DURATION = 90000L;
    private static final ArrayList<String> events = new ArrayList<>();
    private static volatile long until;
    private static ContentObserver observer;
    private static BroadcastReceiver receiver;
    private static Context app;

    static boolean active() { return SystemClock.uptimeMillis() < until; }

    static int secondsLeft() { return (int) Math.max(0, (until - SystemClock.uptimeMillis()) / 1000L); }

    static synchronized void start(Context c) {
        stop();
        app = c.getApplicationContext();
        events.clear();
        until = SystemClock.uptimeMillis() + DURATION;
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean self, Uri uri) { onSetting(uri); }
        };
        try {
            app.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, observer);
            app.getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, observer);
            app.getContentResolver().registerContentObserver(Settings.Secure.CONTENT_URI, true, observer);
        } catch (Exception e) {
            add("réglages système : écoute impossible (" + e.getClass().getSimpleName() + ")");
        }
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (!active()) return;
                int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                int v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                int pv = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                if (v != pv) add("volume Android n°" + st + " : " + pv + " → " + v);
            }
        };
        App.reg(app, receiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { if (!active()) stop(); }
        }, DURATION + 500);
    }

    static synchronized void stop() {
        if (app == null) return;
        try { if (observer != null) app.getContentResolver().unregisterContentObserver(observer); } catch (Exception ignored) { }
        try { if (receiver != null) app.unregisterReceiver(receiver); } catch (Exception ignored) { }
        observer = null;
        receiver = null;
    }

    /** Touches reçues par le launcher pendant l'écoute (appelé par l'accueil). */
    static void onKey(KeyEvent e) {
        if (!active() || e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        add("touche reçue : " + KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", ""));
    }

    private static void onSetting(Uri uri) {
        if (!active() || uri == null) return;
        String key = uri.getLastPathSegment();
        String table = uri.getPathSegments().isEmpty() ? "?" : uri.getPathSegments().get(0);
        String v;
        try {
            if ("global".equals(table)) v = Settings.Global.getString(app.getContentResolver(), key);
            else if ("secure".equals(table)) v = Settings.Secure.getString(app.getContentResolver(), key);
            else v = Settings.System.getString(app.getContentResolver(), key);
        } catch (Exception e) {
            v = "(illisible)";
        }
        add("réglage " + table + " « " + key + " » = " + v);
    }

    private static synchronized void add(String e) {
        // même ligne répétée : on ne garde que la dernière valeur
        if (!events.isEmpty()) {
            String last = events.get(events.size() - 1);
            int cut = e.lastIndexOf(" = ");
            if (cut > 0 && last.startsWith(e.substring(0, cut + 3))) events.remove(events.size() - 1);
        }
        events.add(e);
        while (events.size() > 12) events.remove(0);
    }

    static synchronized String text() {
        if (events.isEmpty()) {
            return active() ? "rien pour l'instant : règle le son avec les boutons de l'autoradio" : "touche « Écouter », puis règle le son avec les boutons de l'autoradio";
        }
        StringBuilder sb = new StringBuilder();
        for (String e : events) sb.append(sb.length() > 0 ? "\n" : "").append(e);
        return sb.toString();
    }
}

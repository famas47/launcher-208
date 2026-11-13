package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import java.util.ArrayList;

/**
 * Volume (CarPlay, radio...).
 *
 * Selon les autoradios, le vrai son ne se règle pas au même endroit. On propose donc plusieurs façons,
 * à essayer dans les réglages jusqu'à entendre la différence :
 *  - « Android » : volume musique d'Android (marche sur la plupart des appareils) ;
 *  - « Auto »    : Android choisit le son qui joue en ce moment ;
 *  - « Tout »    : tous les volumes d'Android en même temps.
 * Le chiffre affiché reste celui du volume musique d'Android (le seul qu'on peut lire).
 */
final class Volume {
    static final int M_MUSIC = 0, M_AUTO = 1, M_ALL = 2;
    static final String[] NAMES = {"Android", "Auto", "Tout"};

    private final AudioManager am;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    volatile String lastError = "";

    Volume(Context c) {
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        App.reg(c, new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { fire(); }
        }, f);
    }

    int get() {
        try { return am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Exception e) { return 0; }
    }

    int max() {
        try { return Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); } catch (Exception e) { return 30; }
    }

    /** Volume d'Android bloqué par le fabricant (le vrai son se règle ailleurs). */
    boolean fixed() {
        try { return am.isVolumeFixed(); } catch (Exception e) { return false; }
    }

    /** Règle le volume (glissière de la bulle) et renvoie la valeur affichée. */
    int set(int v) {
        int t = Math.max(0, Math.min(max(), v));
        int m = P.volMethod();
        if (m == M_MUSIC) {
            setMusic(t);
        } else if (m == M_ALL) {
            setAll(t);
        } else {
            // « Auto » ne sait que monter ou descendre d'un cran : on répète
            int d = t - get();
            int n = Math.min(Math.abs(d), 20);
            for (int k = 0; k < n; k++) stepWith(m, d > 0 ? 1 : -1);
        }
        fire();
        return get();
    }

    void step(int d) {
        int m = P.volMethod();
        if (m == M_MUSIC) setMusic(get() + d);
        else if (m == M_ALL) setAll(get() + d);
        else stepWith(m, d);
        fire();
    }

    /** Pour le test des réglages : un cran avec une façon précise. */
    void stepWith(int m, int d) {
        try {
            if (m == M_AUTO) {
                am.adjustSuggestedStreamVolume(d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
                lastError = "";
            } else if (m == M_ALL) {
                setAll(get() + d);
            } else {
                setMusic(get() + d);
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        }
    }

    private void setMusic(int v) {
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max(), v)), 0);
            lastError = "";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        }
    }

    /** Tous les volumes d'Android à la même proportion que le volume musique demandé. */
    private void setAll(int v) {
        float frac = Math.max(0, Math.min(max(), v)) / (float) max();
        String err = "";
        for (int s = 0; s <= 20; s++) { // jusqu'aux volumes ajoutés par certains fabricants
            if (s == 10 || s == 11) continue; // accessibilité, assistant : réservés au système
            try {
                int mx = am.getStreamMaxVolume(s);
                if (mx <= 0) continue;
                am.setStreamVolume(s, Math.round(frac * mx), 0);
            } catch (Exception e) {
                if (s == AudioManager.STREAM_MUSIC) err = e.getClass().getSimpleName();
            }
        }
        lastError = err;
    }

    void capAtStart() {
        int cap = P.volStartMax();
        if (get() > cap) setMusic(cap);
    }

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void remove(Runnable r) { listeners.remove(r); }

    private void fire() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            App.I.main.post(new Runnable() {
                @Override public void run() { fire(); }
            });
            return;
        }
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
    }
}

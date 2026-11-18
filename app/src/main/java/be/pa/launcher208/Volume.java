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
 * Selon les autoradios, le vrai son ne se règle pas au même endroit. L'assistant « Régler le son »
 * (SoundWizard) essaie chaque façon pendant qu'une voix parle et garde celle qui change vraiment le son :
 *  - « musique »  : volume musique d'Android (marche sur la plupart des appareils) ;
 *  - « canal n »  : un autre volume d'Android, celui que bouge le bouton de l'autoradio ;
 *  - « auto »     : Android choisit le son qui joue en ce moment (crans + / −) ;
 *  - « tous »     : tous les volumes d'Android en même temps ;
 *  - « av_volume » : le réglage interne de certains autoradios (famille MTCD).
 * Le chiffre affiché est celui du volume choisi.
 */
final class Volume {
    static final int M_MUSIC = 0, M_AUTO = 1, M_ALL = 2, M_STREAM = 3, M_PARAM = 4;
    static final int FOUND_UNKNOWN = 0, FOUND_YES = 1, FOUND_NO = 2;

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

    /** Volume Android lu pour la façon choisie : celui du canal choisi, sinon celui de la musique. */
    private int stream() {
        int m = P.volMethod();
        return m == M_STREAM ? P.volStream() : AudioManager.STREAM_MUSIC;
    }

    int get() {
        if (P.volMethod() == M_PARAM) {
            int v = param();
            if (v >= 0) return v;
        }
        try { return am.getStreamVolume(stream()); } catch (Exception e) { return 0; }
    }

    int max() {
        if (P.volMethod() == M_PARAM && param() >= 0) return PARAM_MAX;
        try { return Math.max(1, am.getStreamMaxVolume(stream())); } catch (Exception e) { return 30; }
    }

    /** Volume d'Android bloqué par le fabricant (le vrai son se règle ailleurs). */
    boolean fixed() {
        try { return am.isVolumeFixed(); } catch (Exception e) { return false; }
    }

    /** Ce qui règle le son en ce moment, en mots simples (réglages, mode test). */
    static String label() {
        int m = P.volMethod();
        switch (m) {
            case M_STREAM: return "volume n°" + P.volStream() + " d'Android (" + streamName(P.volStream()) + ")";
            case M_AUTO: return "volume automatique d'Android";
            case M_ALL: return "tous les volumes d'Android";
            case M_PARAM: return "réglage interne « av_volume »";
            default: return "volume musique d'Android";
        }
    }

    static String streamName(int s) {
        switch (s) {
            case 0: return "appels";
            case 1: return "système";
            case 2: return "sonnerie";
            case 3: return "musique";
            case 4: return "alarme";
            case 5: return "notifications";
            case 6: return "Bluetooth";
            case 9: return "voix";
            case 10: return "accessibilité";
            case 11: return "assistant";
            default: return s >= 12 ? "propre à l'autoradio" : "autre";
        }
    }

    /** Règle le volume (glissière de la bulle) et renvoie la valeur affichée. */
    int set(int v) {
        int t = Math.max(0, Math.min(max(), v));
        int m = P.volMethod();
        if (m == M_AUTO) {
            // « Auto » ne sait que monter ou descendre d'un cran : on répète
            int d = t - get();
            int n = Math.min(Math.abs(d), 20);
            for (int k = 0; k < n; k++) stepWith(m, d > 0 ? 1 : -1);
        } else {
            setWith(m, P.volStream(), t);
        }
        fire();
        return get();
    }

    void step(int d) {
        int m = P.volMethod();
        if (m == M_AUTO) stepWith(m, d);
        else setWith(m, P.volStream(), get() + d);
        fire();
    }

    /** Un cran avec une façon précise (pour « Auto » : un vrai cran d'Android). */
    void stepWith(int m, int d) {
        try {
            if (m == M_AUTO) {
                am.adjustSuggestedStreamVolume(d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
                lastError = "";
            } else {
                setWith(m, P.volStream(), get() + d);
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        }
    }

    /** Met le volume à v avec une façon précise (assistant du son et boutons). */
    void setWith(int m, int stream, int v) {
        try {
            if (m == M_PARAM) {
                am.setParameters("av_volume=" + Math.max(0, Math.min(PARAM_MAX, v)));
            } else if (m == M_ALL) {
                setAll(v);
            } else {
                int s = m == M_STREAM ? stream : AudioManager.STREAM_MUSIC;
                am.setStreamVolume(s, Math.max(0, Math.min(am.getStreamMaxVolume(s), v)), 0);
            }
            lastError = "";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        }
    }

    /** Tous les volumes d'Android à la même proportion que le volume musique demandé. */
    private void setAll(int v) {
        int mx = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        float frac = Math.max(0, Math.min(mx, v)) / (float) mx;
        String err = "";
        for (int s = 0; s <= 20; s++) { // jusqu'aux volumes ajoutés par certains fabricants
            if (s == 10 || s == 11) continue; // accessibilité, assistant : réservés au système
            try {
                int smx = am.getStreamMaxVolume(s);
                if (smx <= 0) continue;
                am.setStreamVolume(s, Math.round(frac * smx), 0);
            } catch (Exception e) {
                if (s == AudioManager.STREAM_MUSIC) err = e.getClass().getSimpleName();
            }
        }
        lastError = err;
    }

    // ---------- Réglage interne « av_volume » (certains autoradios) ----------
    static final int PARAM_MAX = 30;

    /** Valeur du réglage interne « av_volume », -1 si l'autoradio ne le connaît pas. */
    int param() {
        try {
            String r = am.getParameters("av_volume");
            if (r == null) return -1;
            int eq = r.indexOf('=');
            String v = (eq >= 0 ? r.substring(eq + 1) : r).trim();
            if (v.isEmpty()) return -1;
            int n = Integer.parseInt(v);
            return n >= 0 && n <= 100 ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Volumes de tous les canaux d'Android (pour les remettre comme avant après un essai). */
    int[] snapshot() {
        int[] s = new int[21];
        for (int i = 0; i < s.length; i++) {
            try { s[i] = am.getStreamMaxVolume(i) > 0 ? am.getStreamVolume(i) : -1; } catch (Exception e) { s[i] = -1; }
        }
        return s;
    }

    void restore(int[] snap) {
        if (snap == null) return;
        for (int i = 0; i < snap.length; i++) {
            if (snap[i] < 0 || i == 10 || i == 11) continue;
            try { if (am.getStreamVolume(i) != snap[i]) am.setStreamVolume(i, snap[i], 0); } catch (Exception ignored) { }
        }
    }

    AudioManager audio() { return am; }

    void capAtStart() {
        int cap = P.volStartMax();
        if (get() > cap) setWith(P.volMethod() == M_AUTO ? M_MUSIC : P.volMethod(), P.volStream(), cap);
    }

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void remove(Runnable r) { listeners.remove(r); }

    void fire() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            App.I.main.post(new Runnable() {
                @Override public void run() { fire(); }
            });
            return;
        }
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
    }
}

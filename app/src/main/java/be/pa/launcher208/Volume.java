package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import java.util.ArrayList;

/** Volume de la musique (CarPlay, radio...). */
final class Volume {
    private final AudioManager am;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    String lastError = "";

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

    /** Règle le volume et renvoie la valeur réellement obtenue. */
    int set(int v) {
        int t = Math.max(0, Math.min(max(), v));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, t, 0);
            lastError = "";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
        }
        fire();
        return get();
    }

    void step(int d) { set(get() + d); }

    void capAtStart() {
        int cap = P.volStartMax();
        if (get() > cap) set(cap);
    }

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void remove(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
    }
}

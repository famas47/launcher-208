package be.pa.launcher208;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Voix du guidage : joue les phrases enregistrées à l'avance (dossier assets/voix, voix « Siwis »), l'une
 * après l'autre. Pendant qu'elle parle, la musique est baissée (si l'appli qui joue le permet).
 */
final class NavVoice {
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private MediaPlayer mp;
    private HashSet<String> clips;
    private Object focusReq;
    static volatile String last = ""; // dernière phrase (tests)

    /** La phrase existe-t-elle ? (nom sans « .ogg ») */
    boolean has(String clip) {
        if (clips == null) {
            clips = new HashSet<>();
            try {
                String[] l = App.I.getAssets().list("voix");
                if (l != null) for (String f : l) if (f.endsWith(".ogg")) clips.add(f.substring(0, f.length() - 4));
            } catch (Exception ignored) { }
        }
        return clips.contains(clip);
    }

    void say(String clip) {
        if (!P.navVoice() || !has(clip)) return;
        last = clip;
        if (BuildConfig.TEST_HOOKS) android.util.Log.i("Test208", "voix : " + clip);
        queue.add(clip);
        if (mp == null) next();
    }

    /** Essai (réglages) : même si la voix du guidage est coupée. */
    void test() {
        stop();
        last = "d300_droite";
        queue.add("d300_droite");
        next();
    }

    void stop() {
        queue.clear();
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            mp.release();
            mp = null;
        }
        abandon();
    }

    private void next() {
        String c = queue.poll();
        if (c == null) { abandon(); return; }
        focus();
        MediaPlayer p = new MediaPlayer();
        try {
            AssetFileDescriptor fd = App.I.getAssets().openFd("voix/" + c + ".ogg");
            if (Build.VERSION.SDK_INT >= 21) {
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(P.navVoiceMedia() ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            }
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    m.release();
                    mp = null;
                    next();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    m.release();
                    mp = null;
                    next();
                    return true;
                }
            });
            p.prepare();
            mp = p;
            p.start();
        } catch (Exception e) {
            p.release();
            mp = null;
            next();
        }
    }

    private void focus() {
        AudioManager am = (AudioManager) App.I.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq == null) {
                    focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .build();
                }
                am.requestAudioFocus((AudioFocusRequest) focusReq);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) { }
    }

    private void abandon() {
        AudioManager am = (AudioManager) App.I.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusReq != null) am.abandonAudioFocusRequest((AudioFocusRequest) focusReq);
            } else {
                am.abandonAudioFocus(null);
            }
        } catch (Exception ignored) { }
    }

    /** Phrase « Dans X mètres, action » avec la distance enregistrée la plus proche en dessous (ou null). */
    static String announce(String action, double d) {
        int[] ds = {2000, 1000, 800, 500, 400, 300, 200, 100};
        for (int x : ds) if (x <= d * 1.15 && x >= d * 0.55) return "d" + x + "_" + action;
        return null;
    }
}

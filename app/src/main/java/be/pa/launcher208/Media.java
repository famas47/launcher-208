package be.pa.launcher208;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.List;

/**
 * Commandes musique / radio (précédent, pause, suivant) envoyées comme les boutons au volant,
 * et lecture du nom de la station si l'autoradio le partage.
 */
final class Media {
    private final Context c;
    private final AudioManager am;

    Media(Context c) {
        this.c = c.getApplicationContext();
        this.am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Envoie une touche média. Si une session de la radio est connue, on l'utilise en priorité. */
    void key(int keyCode, String pkg) {
        MediaController mc = controller(pkg);
        if (mc != null) {
            try {
                MediaController.TransportControls t = mc.getTransportControls();
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { t.skipToNext(); return; }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { t.skipToPrevious(); return; }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    if (isPlaying(mc)) t.pause(); else t.play();
                    return;
                }
            } catch (Exception ignored) { }
        }
        try {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Exception ignored) { }
    }

    /** Vrai si l'autorisation « accès aux notifications » est donnée (nécessaire pour lire la station). */
    boolean accessGranted() {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(c.getPackageName());
        } catch (Exception e) { return false; }
    }

    MediaController controller(String pkg) {
        if (pkg == null || pkg.isEmpty() || !accessGranted()) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(c, NotifListener.class));
            for (MediaController mc : l) if (pkg.equals(mc.getPackageName())) return mc;
        } catch (Exception ignored) { }
        return null;
    }

    /** Liste des applis qui partagent leur lecture (pour le mode test). */
    String sessionsSummary() {
        if (!accessGranted()) return "accès aux notifications non donné";
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(c, NotifListener.class));
            if (l.isEmpty()) return "aucune";
            StringBuilder b = new StringBuilder();
            for (MediaController mc : l) {
                if (b.length() > 0) b.append(", ");
                b.append(mc.getPackageName());
                String t = title(mc);
                if (!t.isEmpty()) b.append(" (").append(t).append(")");
            }
            return b.toString();
        } catch (Exception e) {
            return "erreur : " + e.getClass().getSimpleName();
        }
    }

    static String title(MediaController mc) {
        try {
            MediaMetadata md = mc.getMetadata();
            if (md == null) return "";
            CharSequence t = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (t == null || t.length() == 0) t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            return t == null ? "" : t.toString().trim();
        } catch (Exception e) { return ""; }
    }

    static boolean isPlaying(MediaController mc) {
        try {
            PlaybackState s = mc.getPlaybackState();
            return s != null && s.getState() == PlaybackState.STATE_PLAYING;
        } catch (Exception e) { return false; }
    }
}

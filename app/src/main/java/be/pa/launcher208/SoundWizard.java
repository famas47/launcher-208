package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * « Régler le son » : trouve, une fois pour toutes, comment CET autoradio règle vraiment le son.
 *
 *  1. Écoute : tu tournes le bouton du volume de l'autoradio, l'appli note ce qui bouge dans Android.
 *  2. Essais : pendant qu'une voix parle, chaque façon de régler le son baisse le volume 2 secondes ;
 *     tu dis si tu as entendu la différence. Le volume ne monte jamais pendant les essais (pas de surprise
 *     pour les oreilles) et il est remis exactement comme avant après chaque essai.
 *  3. La façon qui marche est gardée : boutons du launcher, bulle sur CarPlay, volume au démarrage.
 *     Si aucune ne marche, l'écran le dit et affiche de quoi me l'envoyer en photo.
 */
final class SoundWizard {
    private final HomeActivity a;
    private final AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView stepTv, titleTv, textTv, detailTv;
    private LinearLayout btns;
    private MediaPlayer mp;
    static volatile SoundWizard live;

    // ----- Étape 1 : écoute du bouton -----
    private BroadcastReceiver rx;
    private ContentObserver obs;
    private int[] listen0;
    private long listenEnd;
    private final LinkedHashSet<Integer> movedStreams = new LinkedHashSet<>();
    private final LinkedHashSet<String> movedKeys = new LinkedHashSet<>();
    private final LinkedHashSet<String> keysSeen = new LinkedHashSet<>();

    // ----- Étape 2 : essais -----
    private final ArrayList<int[]> tries = new ArrayList<>(); // {façon, canal}
    private int tryIdx;
    private final ArrayList<String> results = new ArrayList<>();
    private int[] snap;
    private int paramSnap = -1, autoSteps;
    private int state; // pour les tests : 0 accueil, 1 écoute, 2 essai, 3 question, 4 trouvé, 5 pas trouvé
    static final int ST_INTRO = 0, ST_LISTEN = 1, ST_PLAY = 2, ST_ASK = 3, ST_FOUND = 4, ST_FAIL = 5;

    SoundWizard(HomeActivity a) {
        this.a = a;
        this.am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
    }

    View view() {
        if (root == null) root = build();
        return root;
    }

    int state() { return state; }

    private FrameLayout build() {
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(Pal.bg);
        f.setClickable(true); // rien ne passe à travers
        LinearLayout col = U.col(a);
        U.pad(col, 24, 20, 24, 20);

        LinearLayout top = U.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = U.box(a, 64, U.rr(Pal.card, 22), R.drawable.ic_chev_l, 30, Pal.text);
        U.shadow(back, 3);
        U.click(back, 22, new Runnable() {
            @Override public void run() { close(); }
        });
        back.setContentDescription("Fermer");
        top.addView(back, U.lp(U.s(64), U.s(64)));
        LinearLayout tc = U.col(a);
        stepTv = U.tv(a, "", 14, Pal.text2, 600);
        tc.addView(stepTv);
        tc.addView(U.tv(a, "Régler le son de l'autoradio", 26, Pal.text, 700));
        top.addView(tc, U.lpm(U.WR, U.WR, 18, 0, 0, 0));
        col.addView(top);

        LinearLayout card = U.col(a);
        card.setBackground(U.rr(Pal.card, 26));
        U.shadow(card, 3);
        U.pad(card, 28, 24, 28, 24);
        titleTv = U.tv(a, "", 24, Pal.text, 700);
        card.addView(titleTv);
        textTv = U.tv(a, "", 18, Pal.text, 400);
        textTv.setLineSpacing(U.s(4), 1f);
        card.addView(textTv, U.lpm(U.M, U.WR, 0, 12, 0, 0));
        ScrollView sc = new ScrollView(a);
        detailTv = U.tv(a, "", 14, Pal.text2, 400);
        detailTv.setTextIsSelectable(false);
        sc.addView(detailTv);
        LinearLayout.LayoutParams scl = U.lpm(U.M, 0, 0, 12, 0, 0);
        scl.weight = 1f;
        card.addView(sc, scl);
        btns = U.row(a);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(btns, U.lpm(U.M, U.s(64), 0, 14, 0, 0));
        LinearLayout.LayoutParams cl = U.lpm(U.M, 0, 0, 18, 0, 0);
        cl.weight = 1f;
        col.addView(card, cl);
        f.addView(col, new FrameLayout.LayoutParams(U.M, U.M));
        return f;
    }

    void onShow() {
        live = this;
        showIntro();
    }

    void close() {
        stopListen();
        stopSound();
        if (snap != null) restoreAll();
        h.removeCallbacksAndMessages(null);
        live = null;
        a.closeSoundWizard();
    }

    void destroy() {
        stopListen();
        stopSound();
        h.removeCallbacksAndMessages(null);
        if (live == this) live = null;
    }

    // ---------- Écrans ----------

    private void screen(String step, String title, String text, String detail) {
        stepTv.setText(step);
        titleTv.setText(title);
        textTv.setText(text);
        detailTv.setText(detail);
        btns.removeAllViews();
    }

    private TextView button(String label, boolean main, final Runnable r) {
        TextView t = U.tv(a, label, 18, main ? Pal.WHITE : Pal.text, 700);
        t.setGravity(Gravity.CENTER);
        t.setBackground(U.rr(main ? Pal.accent : Pal.card2, 20));
        U.pad(t, 26, 0, 26, 0);
        U.click(t, 20, r);
        if (btns.getChildCount() > 0) btns.addView(U.gap(a, 12, 1));
        btns.addView(t, U.lp(U.WR, U.M));
        return t;
    }

    private void showIntro() {
        state = ST_INTRO;
        String prev = P.volFound() == Volume.FOUND_YES ? "Réglage actuel : " + Volume.label() + " (trouvé)."
                : P.volFound() == Volume.FOUND_NO ? "Dernière recherche : aucune façon ne marchait." : "";
        screen("Étape 1 sur 3", "Trouvons comment ton autoradio règle le son",
                "1. Mets un peu de musique (CarPlay ou radio), pas trop fort.\n"
                        + "2. Reviens ici et touche « Commencer ».\n\n"
                        + "Pendant les essais, le son ne monte jamais : il baisse seulement 2 secondes, puis revient comme avant.",
                prev);
        button("Commencer", true, new Runnable() { @Override public void run() { startListen(); } });
        button("Fermer", false, new Runnable() { @Override public void run() { close(); } });
    }

    // ---------- Étape 1 : écoute ----------

    private void startListen() {
        state = ST_LISTEN;
        movedStreams.clear();
        movedKeys.clear();
        keysSeen.clear();
        listen0 = App.I.vol.snapshot();
        listenEnd = android.os.SystemClock.uptimeMillis() + 25000;
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                int v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                int pv = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                if (st >= 0 && v != pv) movedStreams.add(st);
            }
        };
        App.reg(a, rx, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean self, Uri uri) {
                if (uri == null) return;
                String k = uri.getLastPathSegment();
                String t = uri.getPathSegments().isEmpty() ? "?" : uri.getPathSegments().get(0);
                if (k != null) movedKeys.add(t + "/" + k);
            }
        };
        try {
            a.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, obs);
            a.getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, obs);
            a.getContentResolver().registerContentObserver(Settings.Secure.CONTENT_URI, true, obs);
        } catch (Exception ignored) { }
        screen("Étape 1 sur 3", "Tourne le bouton du volume",
                "Avec le bouton de l'autoradio (ou + et − au volant) :\nmonte un peu le son, puis redescends-le.\n\nTouche « C'est fait » quand c'est fini.",
                "");
        button("C'est fait", true, new Runnable() { @Override public void run() { endListen(); } });
        button("Passer", false, new Runnable() { @Override public void run() { endListen(); } });
        h.post(listenTick);
    }

    private final Runnable listenTick = new Runnable() {
        @Override public void run() {
            if (state != ST_LISTEN) return;
            // canaux qui bougent même sans annonce
            int[] now = App.I.vol.snapshot();
            for (int i = 0; i < now.length && listen0 != null; i++) if (now[i] >= 0 && now[i] != listen0[i]) movedStreams.add(i);
            long left = listenEnd - android.os.SystemClock.uptimeMillis();
            detailTv.setText(heardText() + (left > 0 ? "\n\nJ'écoute… (" + (left / 1000) + " s)" : ""));
            if (left <= 0) { endListen(); return; }
            h.postDelayed(this, 250);
        }
    };

    /** Touches reçues pendant l'écoute (appelé par l'accueil). */
    static void onKey(KeyEvent e) {
        SoundWizard w = live;
        if (w == null || w.state != ST_LISTEN || e.getAction() != KeyEvent.ACTION_DOWN) return;
        w.keysSeen.add(KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", ""));
    }

    private String heardText() {
        StringBuilder sb = new StringBuilder();
        for (int s : movedStreams) sb.append(sb.length() > 0 ? "\n" : "").append("Vu : volume n°").append(s).append(" (").append(Volume.streamName(s)).append(")");
        for (String k : movedKeys) sb.append(sb.length() > 0 ? "\n" : "").append("Vu : réglage ").append(k);
        for (String k : keysSeen) sb.append(sb.length() > 0 ? "\n" : "").append("Vu : touche ").append(k);
        return sb.length() == 0 ? "Rien vu pour l'instant." : sb.toString();
    }

    private void stopListen() {
        h.removeCallbacks(listenTick);
        try { if (rx != null) a.unregisterReceiver(rx); } catch (Exception ignored) { }
        try { if (obs != null) a.getContentResolver().unregisterContentObserver(obs); } catch (Exception ignored) { }
        rx = null;
        obs = null;
    }

    private void endListen() {
        if (state != ST_LISTEN) return;
        stopListen();
        // essais : d'abord les canaux que le bouton a bougés, puis les façons habituelles
        tries.clear();
        results.clear();
        for (int s : movedStreams) {
            if (s == 10 || s == 11) continue;
            add(s == AudioManager.STREAM_MUSIC ? Volume.M_MUSIC : Volume.M_STREAM, s);
        }
        add(Volume.M_MUSIC, AudioManager.STREAM_MUSIC);
        add(Volume.M_AUTO, AudioManager.STREAM_MUSIC);
        add(Volume.M_ALL, AudioManager.STREAM_MUSIC);
        if (App.I.vol.param() >= 0) add(Volume.M_PARAM, AudioManager.STREAM_MUSIC);
        tryIdx = 0;
        nextTry();
    }

    private void add(int m, int s) {
        for (int[] t : tries) if (t[0] == m && (m != Volume.M_STREAM || t[1] == s)) return;
        tries.add(new int[]{m, s});
    }

    private static String tryName(int[] t) {
        switch (t[0]) {
            case Volume.M_STREAM: return "volume n°" + t[1] + " (" + Volume.streamName(t[1]) + ")";
            case Volume.M_AUTO: return "volume automatique";
            case Volume.M_ALL: return "tous les volumes";
            case Volume.M_PARAM: return "réglage av_volume";
            default: return "volume musique";
        }
    }

    // ---------- Étape 2 : essais à l'oreille ----------

    private void nextTry() {
        if (tryIdx >= tries.size()) { fail(); return; }
        play(tries.get(tryIdx));
    }

    private void play(final int[] t) {
        state = ST_PLAY;
        screen("Étape 2 sur 3 · essai " + (tryIdx + 1) + " sur " + tries.size(), "Écoute bien",
                "Une voix va parler, puis le son va baisser pendant 2 secondes.\nÉcoute aussi ta musique si elle joue.",
                "Façon essayée : " + tryName(t));
        startSound(t);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (state != ST_PLAY) return;
                lower(t);
                detailTv.setText("Façon essayée : " + tryName(t) + "\n\nLe son baisse…");
            }
        }, 1800);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (state != ST_PLAY) return;
                restoreAll();
                detailTv.setText("Façon essayée : " + tryName(t) + "\n\nLe son revient.");
            }
        }, 4000);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (state != ST_PLAY) return;
                stopSound();
                ask(t);
            }
        }, 5200);
    }

    private void ask(final int[] t) {
        state = ST_ASK;
        screen("Étape 2 sur 3 · essai " + (tryIdx + 1) + " sur " + tries.size(), "As-tu entendu le son baisser ?",
                "Pendant 2 secondes, la voix (ou ta musique) était-elle moins forte ?", "Façon essayée : " + tryName(t));
        button("Oui, il a baissé", true, new Runnable() { @Override public void run() { answer(true); } });
        button("Non", false, new Runnable() { @Override public void run() { answer(false); } });
        button("Réécouter", false, new Runnable() { @Override public void run() { play(t); } });
    }

    /** Réponse à la question (boutons, ou tests). */
    void answer(boolean yes) {
        if (state != ST_ASK) return;
        int[] t = tries.get(tryIdx);
        results.add(tryName(t) + (yes ? " : oui" : " : non"));
        if (yes) { found(t); return; }
        tryIdx++;
        nextTry();
    }

    /** Fin de l'écoute (tests). */
    void listenDone() { endListen(); }

    /** Lance l'écoute (tests). */
    void begin() { if (state == ST_INTRO) startListen(); }

    /** Baisse le son avec une façon, sans jamais le monter. */
    private void lower(int[] t) {
        Volume vol = App.I.vol;
        snap = vol.snapshot();
        paramSnap = vol.param();
        autoSteps = 0;
        int m = t[0];
        try {
            if (m == Volume.M_AUTO) {
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                autoSteps = Math.max(3, Math.min(20, cur * 3 / 4));
                for (int k = 0; k < autoSteps; k++) {
                    am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
                }
            } else if (m == Volume.M_PARAM) {
                if (paramSnap >= 0) vol.setWith(m, 0, paramSnap / 4);
            } else {
                int s = m == Volume.M_STREAM ? t[1] : AudioManager.STREAM_MUSIC;
                int cur = am.getStreamVolume(s);
                vol.setWith(m, s, cur > 3 ? cur / 4 : 0);
            }
        } catch (Exception ignored) { }
    }

    /** Remet tous les volumes exactement comme avant l'essai. */
    private void restoreAll() {
        Volume vol = App.I.vol;
        try {
            for (int k = 0; k < autoSteps; k++) {
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, 0);
            }
        } catch (Exception ignored) { }
        autoSteps = 0;
        vol.restore(snap);
        if (paramSnap >= 0 && vol.param() != paramSnap) vol.setWith(Volume.M_PARAM, 0, paramSnap);
        snap = null;
        paramSnap = -1;
        vol.fire();
    }

    // ---------- Son d'essai : une phrase du GPS, en boucle ----------

    private void startSound(int[] t) {
        stopSound();
        try {
            MediaPlayer p = new MediaPlayer();
            AssetFileDescriptor fd = App.I.getAssets().openFd("voix/d300_droite.ogg");
            int s = t[0] == Volume.M_STREAM ? t[1] : AudioManager.STREAM_MUSIC;
            if (s != AudioManager.STREAM_MUSIC && s <= 9) {
                // le son d'essai passe par le canal essayé
                p.setAudioStreamType(s);
            } else if (Build.VERSION.SDK_INT >= 21) {
                p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            }
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            p.setLooping(true);
            p.prepare();
            p.start();
            mp = p;
        } catch (Exception ignored) { }
    }

    private void stopSound() {
        MediaPlayer p = mp;
        mp = null;
        if (p == null) return;
        try { p.stop(); } catch (Exception ignored) { }
        try { p.release(); } catch (Exception ignored) { }
    }

    // ---------- Étape 3 : résultat ----------

    private void found(int[] t) {
        state = ST_FOUND;
        P.put(P.VOL_METHOD, t[0]);
        P.put(P.VOL_STREAM, t[1]);
        P.put(P.VOL_FOUND, Volume.FOUND_YES);
        App.I.vol.fire();
        screen("Étape 3 sur 3", "Trouvé !",
                "Les boutons du launcher, la bulle sur CarPlay et le volume au démarrage règlent maintenant le vrai son.\n\n"
                        + "Essaie avec ta musique :",
                "Façon gardée : " + Volume.label());
        button("−", false, new Runnable() { @Override public void run() { App.I.vol.step(-2); } }).setMinWidth(U.s(90));
        button("+", false, new Runnable() { @Override public void run() { App.I.vol.step(2); } }).setMinWidth(U.s(90));
        button("Terminé", true, new Runnable() { @Override public void run() { close(); } });
    }

    private void fail() {
        state = ST_FAIL;
        P.put(P.VOL_METHOD, Volume.M_MUSIC);
        P.put(P.VOL_FOUND, Volume.FOUND_NO);
        screen("Étape 3 sur 3", "Ton autoradio garde le son pour lui",
                "Aucune façon d'Android ne change le son : ton autoradio le règle lui-même, sans passer par Android. "
                        + "Une appli comme le launcher ne peut donc pas le changer. Le bouton de l'autoradio, celui du volant "
                        + "et sa propre barre de volume, eux, marchent.\n\n"
                        + "Envoie-moi une photo de cet écran : je chercherai la façon propre à ton autoradio.",
                report());
        if (P.bubble()) {
            button("Retirer la bulle du volume", false, new Runnable() {
                @Override public void run() {
                    P.put(P.BUBBLE, false);
                    if (LauncherService.I != null) LauncherService.I.refreshBubble();
                    btns.getChildAt(0).setVisibility(View.GONE);
                }
            });
        }
        button("Fermer", true, new Runnable() { @Override public void run() { close(); } });
    }

    /** Ce qu'il faut pour chercher la façon propre à l'autoradio (lisible sur une photo). */
    private String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ").append(Build.HARDWARE).append(" / ")
                .append(Build.BOARD).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Bouton : ").append(heardText().replace('\n', ' ')).append('\n');
        sb.append("Essais : ");
        for (int i = 0; i < results.size(); i++) sb.append(i > 0 ? " · " : "").append(results.get(i));
        sb.append("\nAndroid : musique ").append(am.getStreamVolume(AudioManager.STREAM_MUSIC)).append('/')
                .append(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).append(App.I.vol.fixed() ? " (bloqué)" : "")
                .append(" · av_volume ").append(App.I.vol.param() >= 0 ? "oui" : "non").append('\n');
        sb.append("Applis : ").append(TestScreen.vendorApps(a));
        return sb.toString();
    }
}

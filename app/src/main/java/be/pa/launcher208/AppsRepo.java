package be.pa.launcher208;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Liste des applications de l'autoradio, reconnaissance de leur type et lancement. */
final class AppsRepo {
    static final int OTHER = 0, CARPLAY = 1, RADIO = 2, DAB = 3, MUSIC = 4, BTMUSIC = 5, PHONE = 6, CAMERA = 7,
            VIDEO = 8, GALLERY = 9, FILES = 10, CARINFO = 11, SETTINGS = 12, SOUND = 13, WHEEL = 14, AUX = 15,
            MANUAL = 16, MIC = 17, THEME = 18, BROWSER = 19, STORE = 20, MAPS = 21, YOUTUBE = 22, SEARCH = 23,
            LINK = 24, NAV = 25, CALC = 26, CLOCK = 27, WEATHER = 28;

    static final class AppInfo {
        final String pkg, cls, label, low;
        final int cat;
        Drawable icon; // icône officielle, chargée à la demande

        AppInfo(String pkg, String cls, String label, int cat) {
            this.pkg = pkg; this.cls = cls; this.label = label; this.cat = cat;
            this.low = (label + " " + pkg).toLowerCase(Locale.ROOT);
        }
    }

    private final Context c;
    private final PackageManager pm;
    private volatile List<AppInfo> apps = new ArrayList<>();
    private volatile boolean loaded;
    Runnable onLoaded;

    AppsRepo(Context c) {
        this.c = c.getApplicationContext();
        this.pm = c.getPackageManager();
    }

    List<AppInfo> list() { return apps; }
    boolean loaded() { return loaded; }

    /** Chargement en arrière-plan (ne retarde pas l'affichage de l'accueil). */
    void load() {
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<AppInfo> out = new ArrayList<>();
                try {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
                    ArrayList<String> homes = homePackages();
                    String me = c.getPackageName();
                    for (ResolveInfo r : l) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(me) || homes.contains(pkg)) continue;
                        CharSequence lab = r.loadLabel(pm);
                        String label = lab == null ? pkg : lab.toString().trim();
                        out.add(new AppInfo(pkg, r.activityInfo.name, label, categorize(label, pkg)));
                    }
                    final Collator col = Collator.getInstance(Locale.FRANCE);
                    Collections.sort(out, new Comparator<AppInfo>() {
                        @Override public int compare(AppInfo a, AppInfo b) {
                            int pa = order(a.cat), pb = order(b.cat);
                            if (pa != pb) return pa - pb;
                            return col.compare(a.label, b.label);
                        }
                    });
                } catch (Exception ignored) { }
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        apps = out;
                        loaded = true;
                        if (onLoaded != null) onLoaded.run();
                    }
                });
            }
        });
    }

    /** Ordre d'affichage : d'abord l'essentiel de la voiture, comme sur la maquette. */
    private static int order(int cat) {
        switch (cat) {
            case CARPLAY: return 0;
            case RADIO: return 1;
            case DAB: return 2;
            case MUSIC: return 3;
            case BTMUSIC: return 4;
            case PHONE: return 5;
            case VIDEO: return 6;
            default: return 10;
        }
    }

    ArrayList<String> homePackages() {
        ArrayList<String> out = new ArrayList<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) out.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        return out;
    }

    /** Écrans d'accueil installés, sauf le nôtre (= « écran d'origine »). */
    List<ResolveInfo> otherHomes() {
        ArrayList<ResolveInfo> out = new ArrayList<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                if (!r.activityInfo.packageName.equals(c.getPackageName())) out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static int categorize(String label, String pkg) {
        String l = label.toLowerCase(Locale.ROOT).trim(), p = pkg.toLowerCase(Locale.ROOT);
        String[] w = l.split("[^\\p{L}\\p{N}+]+");
        if (has(l + " " + p, "s-play", "splay", "s play", "carplay", "car play", "autokit", "carlink", "zlink")) return CARPLAY;
        if (has(l, "jlink", "j-link") || has(p, "jlink")) return LINK;
        if (word(w, "dab", "dab+") || has(l, "dab+")) return DAB;
        if (has(l, "musique bluetooth", "musique bt", "bt music", "bluetooth music", "a2dp")) return BTMUSIC;
        if (has(l, "réglages du son", "reglages du son", "égaliseur", "egaliseur", "equalizer") || word(w, "son", "sound", "audio", "dsp", "eq")) return SOUND;
        if (has(l, "réglage", "reglage", "setting", "paramètre", "parametre", "config")) return SETTINGS;
        if (has(l, "magnéto", "magneto", "recorder", "dictaphone")) return MIC;
        if (prefix(w, "radio") || word(w, "fm", "tuner") || has(p, ".radio", "radio.", "tuner")) return RADIO;
        if (has(l, "appel", "téléphone", "telephone", "phone", "dialer", "contacts") || word(w, "bt") || has(p, "dialer", "btphone", "bluetooth.phone")) return PHONE;
        if (has(l, "youtube")) return YOUTUBE;
        if (has(l, "musique", "music", "audio player", "lecteur audio") || word(w, "usb") || has(p, "music")) return MUSIC;
        if (has(l, "vidéo", "video", "film", "movie")) return VIDEO;
        if (has(l, "galerie", "gallery", "photo", "image")) return GALLERY;
        if (has(l, "fichier", "file", "explor", "gestionnaire")) return FILES;
        if (has(l, "caméra", "camera", "dvr", "avm", "recul", "dashcam") || word(w, "360")) return CAMERA;
        if (has(l, "véhicule", "vehicule", "vehicle", "car info", "canbus", "can bus", "infos voiture")) return CARINFO;
        if (has(l, "volant", "steering") || word(w, "swc")) return WHEEL;
        if (has(l, "entrée", "entree", "avin", "hdmi") || word(w, "aux", "input")) return AUX;
        if (has(l, "manuel", "manual", "guide") || word(w, "aide", "help")) return MANUAL;
        if (has(l, "thème", "theme", "fond d'écran", "wallpaper")) return THEME;
        if (has(l, "chrome", "navigateur", "browser", "firefox") || word(w, "internet")) return BROWSER;
        if (has(l, "play store", "store", "market", "boutique")) return STORE;
        if (has(l, "waze", "wego", "sygic", "navig", "tomtom") || word(w, "here", "gps", "igo")) return NAV;
        if (has(l, "maps") || word(w, "carte", "cartes", "plan", "plans")) return MAPS;
        if (l.equals("google") || has(l, "recherche", "search")) return SEARCH;
        if (has(l, "calcul")) return CALC;
        if (has(l, "horloge", "clock", "réveil", "alarme")) return CLOCK;
        if (has(l, "météo", "meteo", "weather")) return WEATHER;
        return OTHER;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** Vrai si un des mots du nom est exactement l'un des mots donnés. */
    private static boolean word(String[] words, String... keys) {
        for (String w : words) for (String k : keys) if (w.equals(k)) return true;
        return false;
    }

    /** Vrai si un des mots du nom commence par ce début (« radio » dans « Radio FM », pas dans « autoradio »). */
    private static boolean prefix(String[] words, String start) {
        for (String w : words) if (w.startsWith(start)) return true;
        return false;
    }

    static int glyph(int cat) {
        switch (cat) {
            case CARPLAY: return R.drawable.ic_carplay;
            case RADIO: return R.drawable.ic_radio;
            case DAB: return R.drawable.ic_antenna;
            case MUSIC: return R.drawable.ic_music;
            case BTMUSIC: return R.drawable.ic_bt;
            case PHONE: return R.drawable.ic_phone;
            case CAMERA: return R.drawable.ic_camera;
            case VIDEO: return R.drawable.ic_video;
            case GALLERY: return R.drawable.ic_image;
            case FILES: return R.drawable.ic_folder;
            case CARINFO: return R.drawable.ic_car;
            case SETTINGS: return R.drawable.ic_cog;
            case SOUND: return R.drawable.ic_eq;
            case WHEEL: return R.drawable.ic_wheel;
            case AUX: return R.drawable.ic_plug;
            case MANUAL: return R.drawable.ic_book;
            case MIC: return R.drawable.ic_mic;
            case THEME: return R.drawable.ic_palette;
            case BROWSER: return R.drawable.ic_globe;
            case STORE: return R.drawable.ic_bag;
            case MAPS: return R.drawable.ic_map;
            case YOUTUBE: return R.drawable.ic_play;
            case SEARCH: return R.drawable.ic_search;
            case LINK: return R.drawable.ic_link;
            case NAV: return R.drawable.ic_compass;
            case CALC: return R.drawable.ic_calc;
            case CLOCK: return R.drawable.ic_clock;
            case WEATHER: return R.drawable.ic_cloud_sun;
            default: return R.drawable.ic_app;
        }
    }

    /** Dégradé de la tuile [haut, bas], comme les couleurs de la maquette. */
    static int[] colors(int cat, String key) {
        switch (cat) {
            case CARPLAY: case NAV: case SEARCH: case FILES: return new int[]{0xFF4C8DFF, 0xFF2563EB};
            case RADIO: case DAB: return new int[]{0xFFFFB547, 0xFFF08A1C};
            case MUSIC: case BTMUSIC: case MIC: return new int[]{0xFFA78BFA, 0xFF7C3AED};
            case PHONE: case MAPS: case STORE: return new int[]{0xFF4ADE80, 0xFF16A34A};
            case CAMERA: case CARINFO: case SETTINGS: case SOUND: case WHEEL: case AUX: case MANUAL: case LINK: case CALC: case CLOCK:
                return new int[]{0xFF8C98A8, 0xFF4B5563};
            case VIDEO: case YOUTUBE: case GALLERY: case THEME: return new int[]{0xFFFB7185, 0xFFE11D48};
            case BROWSER: case WEATHER: return new int[]{0xFF2DD4BF, 0xFF0D9488};
            default:
                int[][] pal = {{0xFF4C8DFF, 0xFF2563EB}, {0xFF2DD4BF, 0xFF0D9488}, {0xFFA78BFA, 0xFF7C3AED},
                        {0xFFFFB547, 0xFFF08A1C}, {0xFF8C98A8, 0xFF4B5563}, {0xFF4ADE80, 0xFF16A34A}};
                return pal[Math.abs(key.hashCode()) % pal.length];
        }
    }

    Drawable officialIcon(AppInfo a) {
        if (a.icon == null) {
            try { a.icon = pm.getActivityIcon(new ComponentName(a.pkg, a.cls)); } catch (Exception e) { a.icon = null; }
        }
        return a.icon;
    }

    AppInfo byPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (AppInfo a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    AppInfo firstOf(int cat) {
        for (AppInfo a : apps) if (a.cat == cat) return a;
        return null;
    }

    /** Application choisie pour un rôle (réglage), sinon reconnue automatiquement. */
    String pkgFor(String prefKey, int cat) {
        String p = P.s(prefKey);
        if (!p.isEmpty() && (loaded ? byPkg(p) != null : installed(p))) return p;
        AppInfo a = firstOf(cat);
        if (a == null && cat == MUSIC) a = firstOf(BTMUSIC);
        return a == null ? "" : a.pkg;
    }

    String carplayPkg() { return pkgFor(P.PKG_CARPLAY, CARPLAY); }
    String radioPkg() { return pkgFor(P.PKG_RADIO, RADIO); }

    boolean installed(String pkg) {
        try { pm.getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    String label(String pkg) {
        AppInfo a = byPkg(pkg);
        if (a != null) return a.label;
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return pkg; }
    }

    /** Ouvre une application. Renvoie faux si elle n'existe pas. */
    boolean launch(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                AppInfo a = byPkg(pkg);
                if (a == null) return false;
                i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(a.pkg, a.cls));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void launch(Context ctx, AppInfo a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(a.pkg, a.cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Impossible d'ouvrir " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    static void openSettings(Context ctx, String action) {
        try {
            ctx.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(ctx, "Réglage introuvable sur cet autoradio", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(ctx, "Réglage introuvable sur cet autoradio", Toast.LENGTH_SHORT).show();
        }
    }

    static void appDetails(Context ctx, String pkg) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    boolean hasPkgPermission(String perm) {
        return pm.checkPermission(perm, c.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }
}

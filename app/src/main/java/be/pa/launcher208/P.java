package be.pa.launcher208;

import android.content.Context;
import android.content.SharedPreferences;

/** Réglages enregistrés (lecture rapide, écriture en arrière-plan). */
final class P {
    private static SharedPreferences sp;

    // Affichage
    static final String THEME = "theme";            // 0 auto, 1 jour, 2 nuit
    static final String NIGHT_BY = "nightBy";       // 0 phares, 1 soleil, 2 heures
    static final String NIGHT_FROM = "nightFrom";   // minutes depuis minuit
    static final String NIGHT_TO = "nightTo";
    static final String FLUID = "fluid";            // 0 max, 1 = 30 images/s
    static final String ICONS = "icons";            // 0 uniformes, 1 officielles
    static final String FULLSCREEN = "fullscreen";
    // Applications choisies
    static final String PKG_CARPLAY = "pkgCarplay";
    static final String PKG_RADIO = "pkgRadio";
    static final String PKG_MUSIC = "pkgMusic";
    static final String PKG_PHONE = "pkgPhone";
    static final String PKG_CAMERA = "pkgCamera";
    // CarPlay
    static final String CARPLAY_AT_START = "carplayAtStart";  // ancien réglage (oui/non), remplacé par CARPLAY_AUTO
    static final String CARPLAY_AUTO = "carplayAuto";        // 0 jamais, 1 au démarrage, 2 quand l'iPhone se connecte
    static final String BT_PHONE = "btPhone";                // adresse Bluetooth du téléphone choisi ("" = tout iPhone)
    static final String BT_PHONE_NAME = "btPhoneName";
    static final String CARPLAY_PKGS = "carplayPkgs";        // autres applis vues à l'écran quand CarPlay s'affiche
    static final String LAST_BOOT = "lastBoot";
    static final String BUBBLE = "bubble";
    static final String BUBBLE_X = "bubbleX";
    static final String BUBBLE_Y = "bubbleY";
    // Mode conduite
    static final String DRIVE = "drive";
    static final String DRIVE_RETURN = "driveReturn";  // secondes
    static final String DRIVE_AFTER = "driveAfter";    // minutes après le contact (0 = non)
    static final String DRIVE_KMH = "driveKmh";        // vitesse d'activation (km/h)
    static final String MINIMAP = "minimap";
    // Voiture
    static final String ODO_BASE = "odoBase";          // km saisis
    static final String ODO_GPS_AT = "odoGpsAt";       // km GPS au moment de la saisie
    static final String GPS_KM = "gpsKm";              // km GPS cumulés
    static final String VOL_START_MAX = "volStartMax";
    static final String CT_DATE = "ctDate";
    static final String OIL_KM = "oilKm";
    static final String TIRES_DATE = "tiresDate";
    static final String REMIND_TITLE = "remindTitle";
    static final String REMIND_DATE = "remindDate";
    // Position, stationnement
    static final String LAST_LAT = "lastLat";
    static final String LAST_LON = "lastLon";
    static final String PARK_TIME = "parkTime";
    static final String PARK_LAT = "parkLat";
    static final String PARK_LON = "parkLon";
    // Météo
    static final String W_TEMP = "wTemp";
    static final String W_CODE = "wCode";
    static final String W_DAY = "wDay";
    static final String W_TIME = "wTime";
    // Phares (luminosité)
    static final String BR_MAX = "brMax";
    static final String BR_MIN = "brMin";

    // Son
    static final String VOL_METHOD = "volMethod";      // façon de changer le volume (Volume.M_*)
    // Changements de réglages faits une seule fois lors d'une mise à jour
    private static final String MIGR_V8 = "migrV8", MIGR_V9 = "migrV9";
    static final String BT_AUTO = "btAuto";            // allumer le Bluetooth au démarrage

    static void init(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (!sp.getBoolean(MIGR_V8, false)) {
            // v8 : CarPlay de nouveau ouvert au démarrage, comme avant la v6 (c'est ce qui rallume le Bluetooth)
            sp.edit().putInt(CARPLAY_AUTO, PhoneWatch.AUTO_BOOT).putBoolean(MIGR_V8, true).apply();
        }
        if (!sp.getBoolean(MIGR_V9, false)) {
            // v9 : comme le logiciel d'origine. Le launcher allume le Bluetooth et ouvre CarPlay quand l'iPhone
            // se connecte (et l'ouvre au démarrage comme en v8 si le Bluetooth ne peut pas s'allumer).
            sp.edit().putInt(CARPLAY_AUTO, PhoneWatch.AUTO_PHONE).putBoolean(MIGR_V9, true).apply();
        }
    }

    static int i(String k, int d) { return sp.getInt(k, d); }
    static long l(String k, long d) { return sp.getLong(k, d); }
    static float f(String k, float d) { return sp.getFloat(k, d); }
    static boolean b(String k, boolean d) { return sp.getBoolean(k, d); }
    static String s(String k) { return sp.getString(k, ""); }
    static String s(String k, String d) { return sp.getString(k, d); }

    static void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    static void put(String k, long v) { sp.edit().putLong(k, v).apply(); }
    static void put(String k, float v) { sp.edit().putFloat(k, v).apply(); }
    static void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    static void put(String k, String v) { sp.edit().putString(k, v).apply(); }

    static double lat() { return Double.longBitsToDouble(sp.getLong(LAST_LAT, Double.doubleToLongBits(50.4542))); }
    static double lon() { return Double.longBitsToDouble(sp.getLong(LAST_LON, Double.doubleToLongBits(3.9567))); }
    static boolean hasPos() { return sp.contains(LAST_LAT); }
    static void putPos(String kLat, String kLon, double lat, double lon) {
        sp.edit().putLong(kLat, Double.doubleToLongBits(lat)).putLong(kLon, Double.doubleToLongBits(lon)).apply();
    }
    static double d(String k) { return Double.longBitsToDouble(sp.getLong(k, 0)); }

    // Valeurs par défaut regroupées ici pour être cohérentes partout
    static int theme() { return i(THEME, 0); }
    static int nightBy() { return i(NIGHT_BY, 0); }
    static boolean fluidMax() { return i(FLUID, 0) == 0; }
    static boolean officialIcons() { return i(ICONS, 0) == 1; }
    static boolean fullscreen() { return b(FULLSCREEN, true); }
    /** Ouverture automatique de CarPlay : par défaut quand l'iPhone se connecte (comme le logiciel d'origine). */
    static int carplayAuto() { return i(CARPLAY_AUTO, PhoneWatch.AUTO_PHONE); }
    static boolean btAuto() { return b(BT_AUTO, true); }
    static boolean bubble() { return b(BUBBLE, true); }
    static boolean drive() { return b(DRIVE, true); }
    static int driveReturn() { return i(DRIVE_RETURN, 60); }
    /** Mode conduite aussi N minutes après le contact, même sans vitesse (0 = seulement la vitesse). */
    static int driveAfter() { return i(DRIVE_AFTER, 0); }
    /** Vitesse à partir de laquelle le mode conduite s'active. */
    static int driveKmh() { return i(DRIVE_KMH, 1); }
    static boolean minimap() { return b(MINIMAP, true); }
    static int volMethod() { int m = i(VOL_METHOD, Volume.M_MUSIC); return m >= 0 && m < Volume.NAMES.length ? m : Volume.M_MUSIC; }
    static int volStartMax() { return i(VOL_START_MAX, 15); }
}

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
    static final String CARPLAY_AT_START = "carplayAtStart";
    static final String LAST_BOOT = "lastBoot";
    static final String BUBBLE = "bubble";
    static final String BUBBLE_X = "bubbleX";
    static final String BUBBLE_Y = "bubbleY";
    // Mode conduite
    static final String DRIVE = "drive";
    static final String DRIVE_RETURN = "driveReturn";  // secondes
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

    static void init(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static int i(String k, int d) { return sp.getInt(k, d); }
    static long l(String k, long d) { return sp.getLong(k, d); }
    static float f(String k, float d) { return sp.getFloat(k, d); }
    static boolean b(String k, boolean d) { return sp.getBoolean(k, d); }
    static String s(String k) { return sp.getString(k, ""); }

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
    static boolean carplayAtStart() { return b(CARPLAY_AT_START, true); }
    static boolean bubble() { return b(BUBBLE, true); }
    static boolean drive() { return b(DRIVE, true); }
    static int driveReturn() { return i(DRIVE_RETURN, 60); }
    static boolean minimap() { return b(MINIMAP, true); }
    static int volStartMax() { return i(VOL_START_MAX, 15); }
}

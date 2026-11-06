package be.pa.launcher208;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Météo (Open-Meteo, gratuit et sans compte). Mise à jour toutes les 30 minutes si Internet est là. */
final class Weather {
    private volatile boolean busy;
    Runnable onUpdate;

    boolean has() { return P.l(P.W_TIME, 0) > 0; }
    int temp() { return Math.round(P.f(P.W_TEMP, 0)); }
    int code() { return P.i(P.W_CODE, 3); }
    boolean isDay() { return P.b(P.W_DAY, true); }
    boolean fresh() { return System.currentTimeMillis() - P.l(P.W_TIME, 0) < 3L * 3600000L; }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) { return false; }
    }

    void maybeRefresh(final Context c) {
        if (busy) return;
        long age = System.currentTimeMillis() - P.l(P.W_TIME, 0);
        if (age >= 0 && age < 30L * 60000L) return;
        if (!online(c)) return;
        busy = true;
        final double lat = P.lat(), lon = P.lon();
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    String u = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&timezone=auto",
                            lat, lon);
                    JSONObject cur = new JSONObject(get(u)).getJSONObject("current");
                    P.put(P.W_TEMP, (float) cur.getDouble("temperature_2m"));
                    P.put(P.W_CODE, cur.getInt("weather_code"));
                    P.put(P.W_DAY, cur.optInt("is_day", 1) == 1);
                    P.put(P.W_TIME, System.currentTimeMillis());
                    App.I.main.post(new Runnable() {
                        @Override public void run() { if (onUpdate != null) onUpdate.run(); }
                    });
                } catch (Exception ignored) {
                } finally {
                    busy = false;
                }
            }
        });
    }

    static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(8000);
        h.setRequestProperty("User-Agent", "Launcher208");
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    static String label(int code) {
        if (code == 0) return "Ciel dégagé";
        if (code <= 2) return "Peu nuageux";
        if (code == 3) return "Nuageux";
        if (code == 45 || code == 48) return "Brouillard";
        if (code >= 51 && code <= 57) return "Bruine";
        if (code >= 61 && code <= 67) return "Pluie";
        if (code >= 71 && code <= 77) return "Neige";
        if (code >= 80 && code <= 82) return "Averses";
        if (code >= 85 && code <= 86) return "Averses de neige";
        if (code >= 95) return "Orage";
        return "Nuageux";
    }

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_sun : R.drawable.ic_moon;
        if (code <= 2) return day ? R.drawable.ic_cloud_sun : R.drawable.ic_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_snow;
        if (code >= 95) return R.drawable.ic_storm;
        if (code >= 51) return R.drawable.ic_rain;
        return R.drawable.ic_cloud;
    }
}

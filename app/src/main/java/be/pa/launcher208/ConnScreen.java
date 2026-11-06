package be.pa.launcher208;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

/**
 * Wi-Fi, Bluetooth, GPS et Internet : état + raccourcis vers les écrans d'origine de l'autoradio
 * (la recherche et l'appairage se font toujours là, comme avant).
 */
final class ConnScreen extends Screen {
    private final TextView[] state = new TextView[4], k1 = new TextView[4], v1 = new TextView[4], foot = new TextView[4];

    ConnScreen(HomeActivity a) { super(a); }

    @Override View build() {
        LinearLayout outer = U.col(a);
        U.card(outer, Pal.night ? 0xFF111316 : 0xFFF3F5F7, 28);
        U.pad(outer, 18, 16, 18, 16);

        LinearLayout head = U.row(a);
        View back = U.box(a, 40, U.oval(Pal.card), R.drawable.ic_chev_l, 20, Pal.text);
        U.click(back, 20, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_SETTINGS); }
        });
        head.addView(back);
        LinearLayout ht = U.col(a);
        ht.addView(U.tv(a, "Connexions et autoradio", 22, Pal.text, 700));
        ht.addView(U.tv(a, "La recherche Wi-Fi et l'appairage Bluetooth se font dans les écrans d'origine, comme avant", 12, Pal.text2, 400),
                U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        head.addView(ht, U.lpm(0, U.WR, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) ht.getLayoutParams()).weight = 1;
        outer.addView(head, U.lpm(U.M, U.WR, 0, 0, 0, 12));

        LinearLayout r1 = U.row(a), r2 = U.row(a);
        r1.setGravity(0);
        r2.setGravity(0);
        r1.addView(status(0, R.drawable.ic_wifi, Pal.chipB, Pal.chipBf, "Wi-Fi", "Réseau", "Réglages Wi-Fi", Settings.ACTION_WIFI_SETTINGS), U.lpW(1));
        r1.addView(U.gap(a, 12, 1));
        r1.addView(status(1, R.drawable.ic_bt, Pal.night ? 0xFF241C38 : 0xFFEDE7FE, Pal.night ? 0xFFB79CFF : 0xFF7C3AED, "Bluetooth", "Appareils", "Réglages Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS), U.lpW(1));
        r2.addView(status(2, R.drawable.ic_gps, Pal.chipG, Pal.chipGf, "GPS", "Précision", "Réglages de position", Settings.ACTION_LOCATION_SOURCE_SETTINGS), U.lpW(1));
        r2.addView(U.gap(a, 12, 1));
        r2.addView(status(3, R.drawable.ic_globe, Pal.card2, Pal.text, "Internet", "Réseau", "Se connecter au partage de l'iPhone", Settings.ACTION_WIFI_SETTINGS), U.lpW(1));
        outer.addView(r1, U.lpH(1));
        outer.addView(U.gap(a, -1, 12));
        outer.addView(r2, U.lpH(1));

        TextView t = U.tv(a, "RÉGLAGES DE L'AUTORADIO", 12, Pal.text2, 600);
        U.spacing(t, 0.4f, 12);
        outer.addView(t, U.lpm(U.WR, U.WR, 4, 14, 0, 8));
        LinearLayout sc = U.row(a);
        sc.addView(shortcut(R.drawable.ic_speaker, "Son", Settings.ACTION_SOUND_SETTINGS), U.lpW(1));
        sc.addView(U.gap(a, 10, 1));
        sc.addView(shortcut(R.drawable.ic_sun, "Luminosité", Settings.ACTION_DISPLAY_SETTINGS), U.lpW(1));
        sc.addView(U.gap(a, 10, 1));
        sc.addView(shortcut(R.drawable.ic_clock, "Date et heure", Settings.ACTION_DATE_SETTINGS), U.lpW(1));
        sc.addView(U.gap(a, 10, 1));
        View sleep = shortcut(R.drawable.ic_moon, "Mise en veille", null);
        sc.addView(sleep, U.lpW(1));
        sc.addView(U.gap(a, 10, 1));
        sc.addView(shortcut(R.drawable.ic_cog, "Tout le reste", Settings.ACTION_SETTINGS), U.lpW(1));
        outer.addView(sc, U.lp(U.M, U.s(56)));
        return outer;
    }

    private View status(int i, int icon, int bg, int fg, String title, String key, String foot, final String action) {
        LinearLayout c = U.col(a);
        U.card(c, Pal.card, 20);
        U.pad(c, 16, 14, 16, 12);
        LinearLayout h = U.row(a);
        h.addView(U.box(a, 40, U.oval(bg), icon, 20, fg));
        h.addView(U.tv(a, title, 16, Pal.text, 700), U.lpm(0, U.WR, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) h.getChildAt(1).getLayoutParams()).weight = 1;
        state[i] = U.tv(a, "", 12, Pal.ok, 600);
        U.pad(state[i], 10, 4, 10, 4);
        h.addView(state[i]);
        c.addView(h);
        c.addView(U.flexV(a));
        LinearLayout kv = U.row(a);
        k1[i] = U.tv(a, key, 13, Pal.text2, 400);
        kv.addView(k1[i], U.lpW(1));
        ((LinearLayout.LayoutParams) k1[i].getLayoutParams()).height = U.WR;
        v1[i] = U.tv(a, "", 13, Pal.text, 600);
        kv.addView(v1[i]);
        c.addView(kv);
        c.addView(U.flexV(a));
        LinearLayout f = U.row(a);
        f.setBackground(U.rr(Pal.card2, 12));
        U.pad(f, 14, 0, 12, 0);
        this.foot[i] = U.tv(a, foot, 13, Pal.text, 600);
        f.addView(this.foot[i], U.lpW(1));
        ((LinearLayout.LayoutParams) this.foot[i].getLayoutParams()).height = U.WR;
        f.addView(U.icon(a, R.drawable.ic_chev_r, 16, Pal.text2));
        U.click(f, 12, new Runnable() {
            @Override public void run() { AppsRepo.openSettings(a, action); }
        });
        c.addView(f, U.lp(U.M, U.s(42)));
        return c;
    }

    private View shortcut(int icon, String label, final String action) {
        LinearLayout l = U.row(a);
        l.setGravity(Gravity.CENTER);
        U.card(l, Pal.card, 16);
        l.addView(U.icon(a, icon, 18, Pal.text));
        l.addView(U.tv(a, label, 13, Pal.text, 600), U.lpm(U.WR, U.WR, 8, 0, 0, 0));
        U.click(l, 16, new Runnable() {
            @Override public void run() {
                if (action != null) { AppsRepo.openSettings(a, action); return; }
                // La mise en veille est dans les réglages propres à l'autoradio
                AppsRepo.AppInfo s = null;
                for (AppsRepo.AppInfo x : App.I.apps.list()) {
                    if (x.cat == AppsRepo.SETTINGS && !x.pkg.equals("com.android.settings")) { s = x; break; }
                }
                if (s != null) App.I.apps.launch(a, s);
                else AppsRepo.openSettings(a, Settings.ACTION_SETTINGS);
            }
        });
        return l;
    }

    private void set(int i, String st, boolean ok, String v) {
        state[i].setText(st);
        state[i].setTextColor(ok ? Pal.ok : Pal.text2);
        state[i].setBackground(U.rr(ok ? Pal.chipG : Pal.card2, 10));
        v1[i].setText(v);
    }

    @SuppressLint("MissingPermission")
    @Override void refresh() {
        if (root == null) return;
        // Wi-Fi
        try {
            WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean on = wm != null && wm.isWifiEnabled();
            String ssid = "—";
            if (on) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    String s = wi.getSSID();
                    ssid = s == null || s.contains("unknown") ? "Connecté" : s.replace("\"", "");
                } else ssid = "Non connecté";
            }
            set(0, on ? "Activé" : "Désactivé", on, ssid);
        } catch (Exception e) { set(0, "?", false, "—"); }
        // Bluetooth
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            boolean on = ba != null && ba.isEnabled();
            String dev = "—";
            boolean allowed = Build.VERSION.SDK_INT < 31
                    || a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (on && allowed) {
                Set<BluetoothDevice> b = ba.getBondedDevices();
                if (b != null && !b.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (BluetoothDevice d : b) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(d.getName());
                        if (sb.length() > 28) break;
                    }
                    dev = sb.toString();
                } else dev = "Aucun appareil enregistré";
            } else if (on) dev = "Touche ici pour voir";
            set(1, on ? "Activé" : "Désactivé", on, dev);
            if (on && !allowed) {
                v1[1].setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { a.requestPerm(Manifest.permission.BLUETOOTH_CONNECT, HomeActivity.REQ_BT); }
                });
            }
        } catch (Exception e) { set(1, "?", false, "—"); }
        // GPS
        LocationTracker l = App.I.loc;
        boolean gpsOn = false;
        try {
            LocationManager lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
            gpsOn = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) { }
        if (!l.permitted()) set(2, "Non autorisé", false, "—");
        else if (!gpsOn) set(2, "Désactivé", false, "—");
        else if (l.hasFix()) set(2, "Bon signal", true, l.accuracy > 0 ? "± " + Math.round(l.accuracy) + " m" : "OK");
        else set(2, "Recherche…", false, "—");
        foot[2].setText(l.satsUsed > 0 || l.sats > 0 ? l.satsUsed + " satellites utilisés sur " + l.sats + " · heure "
                + (Clock.fromGps() ? "à jour" : "en attente") : "Réglages de position");
        // Internet
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            boolean on = ni != null && ni.isConnected();
            String t = !on ? "Aucun" : ni.getType() == ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : ni.getTypeName();
            set(3, on ? "En ligne" : "Hors ligne", on, t);
        } catch (Exception e) { set(3, "?", false, "—"); }
    }

    @Override void tick() {
        // Mise à jour douce de l'état (toutes les 3 secondes)
        if (System.currentTimeMillis() / 1000 % 3 == 0) refresh();
    }
}

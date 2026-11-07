package be.pa.launcher208;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Ouvre CarPlay (S-Play) seulement quand TON iPhone se connecte en Bluetooth à l'autoradio.
 * Ainsi S-Play n'est plus ouvert trop tôt au démarrage (message « sd_carplay Ne pas courrir »),
 * et pas du tout si l'iPhone n'est pas dans la voiture.
 *
 * Utilise le Bluetooth standard d'Android. Si l'autoradio gère le Bluetooth avec un module à part,
 * Android ne voit pas la connexion : le mode test l'indique (« appareils jumelés vus : 0 »).
 */
final class PhoneWatch {
    static final int AUTO_NEVER = 0, AUTO_BOOT = 1, AUTO_PHONE = 2;

    static volatile String lastSeen = "";      // dernier appareil connecté (mode test)
    static volatile long lastSeenAt;           // elapsedRealtime
    static volatile String lastAction = "";    // ce que le launcher a fait (mode test)
    private static long lastOpen;

    private final Context c;
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) return;
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d != null) onConnected(d);
        }
    };
    private boolean registered;

    PhoneWatch(Context c) { this.c = c.getApplicationContext(); }

    void start() {
        if (registered) return;
        try {
            App.reg(c, rx, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
            registered = true;
        } catch (Exception ignored) { }
    }

    void stop() {
        if (!registered) return;
        try { c.unregisterReceiver(rx); } catch (Exception ignored) { }
        registered = false;
    }

    /** Android 12+ demande l'autorisation « Appareils à proximité » pour voir les connexions Bluetooth. */
    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** L'appareil est-il le téléphone choisi (ou, si rien n'est choisi, un iPhone) ? */
    static boolean isMyPhone(String address, String name) {
        String want = P.s(P.BT_PHONE, "");
        if (!want.isEmpty()) return want.equalsIgnoreCase(address);
        return name != null && name.toLowerCase(Locale.ROOT).contains("iphone");
    }

    private void onConnected(BluetoothDevice d) {
        String n = name(d);
        lastSeen = n;
        lastSeenAt = SystemClock.elapsedRealtime();
        if (P.carplayAuto() != AUTO_PHONE) { lastAction = "connexion vue (ouverture automatique désactivée)"; return; }
        if (!isMyPhone(d.getAddress(), n)) { lastAction = "« " + n + " » n'est pas le téléphone choisi"; return; }
        // seulement si l'écran d'accueil est devant (on n'interrompt pas la radio ou une autre appli),
        // et pas plus d'une fois toutes les 2 minutes (le Bluetooth se reconnecte parfois)
        if (!HomeActivity.visible) { lastAction = "iPhone vu, mais une autre appli est à l'écran : rien ouvert"; return; }
        long now = SystemClock.elapsedRealtime();
        if (lastOpen > 0 && now - lastOpen < 2L * 60000L) { lastAction = "iPhone vu (CarPlay déjà ouvert il y a peu)"; return; }
        String pkg = App.I.apps.carplayPkg();
        if (pkg.isEmpty()) { lastAction = "iPhone vu, mais aucune appli CarPlay choisie"; return; }
        lastOpen = now;
        boolean ok = App.I.apps.launch(c, pkg);
        lastAction = ok ? "iPhone connecté : CarPlay ouvert" : "iPhone connecté, mais CarPlay n'a pas pu s'ouvrir";
        if (ok && LauncherService.I != null) LauncherService.I.carplayOpened();
    }

    /** Appareils jumelés visibles par Android (pour le choix du téléphone et le mode test). */
    static ArrayList<String[]> bonded() {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return out;
            Set<BluetoothDevice> set = ad.getBondedDevices();
            if (set == null) return out;
            for (BluetoothDevice d : set) out.add(new String[]{d.getAddress(), name(d)});
        } catch (Exception ignored) { }
        return out;
    }
}

package be.pa.launcher208;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

/**
 * Allume le Bluetooth d'Android au démarrage, comme le faisait l'écran d'accueil d'origine de l'autoradio.
 * Ton iPhone peut alors se connecter tout seul, et le launcher ouvre CarPlay quand il le voit.
 *
 * Android ne laisse les applis allumer le Bluetooth que si elles sont faites pour Android 12 ou avant
 * (c'est pour ça que le launcher est « fait pour » Android 12, il fonctionne quand même sur Android 15).
 * S'il refuse quand même, CarPlay est ouvert au démarrage comme avant (c'est alors S-Play qui l'allume).
 */
final class BtPower {
    static final int ON = 0, TURNING_ON = 1, ABSENT = 2, NO_PERMISSION = 3, REFUSED = 4;

    static volatile String last = "pas encore essayé"; // pour le mode test

    private static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager m = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            return m == null ? null : m.getAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isOn(Context c) {
        BluetoothAdapter ad = adapter(c);
        try { return ad != null && ad.isEnabled(); } catch (Exception e) { return false; }
    }

    static boolean present(Context c) { return adapter(c) != null; }

    /** Allume le Bluetooth s'il est éteint. */
    @SuppressLint("MissingPermission") // vérifiée juste avant (PhoneWatch.permitted)
    static int ensureOn(Context c) {
        BluetoothAdapter ad = adapter(c);
        if (ad == null) { last = "pas de Bluetooth dans Android (puce à part)"; return ABSENT; }
        try {
            if (ad.isEnabled()) { last = "déjà allumé"; return ON; }
        } catch (Exception ignored) { }
        if (!PhoneWatch.permitted(c)) { last = "autorisation « Appareils à proximité » manquante"; return NO_PERMISSION; }
        try {
            @SuppressWarnings("deprecation") boolean ok = ad.enable();
            last = ok ? "allumé par le launcher" : "Android a refusé de l'allumer";
            return ok ? TURNING_ON : REFUSED;
        } catch (SecurityException e) {
            last = "Android a refusé de l'allumer (autorisation)";
            return REFUSED;
        } catch (Exception e) {
            last = "erreur : " + e.getClass().getSimpleName();
            return REFUSED;
        }
    }
}

package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Au démarrage de l'autoradio : volume limité (réglage « Volume au démarrage ») et Bluetooth allumé. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (App.I == null || i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        App.I.vol.capAtStart();
        if (P.btAuto()) BtPower.ensureOn(c);
    }
}

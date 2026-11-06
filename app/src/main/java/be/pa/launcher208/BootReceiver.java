package be.pa.launcher208;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Au démarrage de l'autoradio : volume limité (réglage « Volume au démarrage »). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (App.I == null) return;
        App.I.vol.capAtStart();
    }
}

package be.pa.launcher208;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * Service toujours actif (très léger) :
 * - GPS : vitesse, trajets, heure (aussi pendant CarPlay) ;
 * - repère quand CarPlay (S-Play) est à l'écran pour montrer la bulle du volume.
 */
public class LauncherService extends Service {
    static LauncherService I;
    private final Handler h = new Handler(Looper.getMainLooper());
    private VolumeBubble bubble;
    private long lastQuery;
    volatile String fgPkg = "";
    private boolean screenOn = true;
    private BroadcastReceiver screenRx;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, LauncherService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // Android peut refuser de démarrer le service depuis l'arrière-plan : il sera relancé par l'accueil.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        goForeground();
        App.I.loc.start();
        if (!App.I.apps.loaded()) App.I.apps.load();
        bubble = new VolumeBubble(this);
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    screenOn = false;
                    App.I.loc.onSleep();
                    bubble.hide();
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    screenOn = true;
                    App.I.vol.capAtStart();
                    App.I.loc.start();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        App.reg(this, screenRx, f);
        h.post(poll);
    }

    private void goForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("actif", "Launcher actif", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, "actif");
            } else {
                b = new Notification.Builder(this);
                b.setPriority(Notification.PRIORITY_MIN);
            }
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent open = PendingIntent.getActivity(this, 1,
                    new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
            b.setSmallIcon(R.drawable.ic_steer)
                    .setContentTitle("Launcher 208")
                    .setContentText("GPS et volume actifs")
                    .setOngoing(true)
                    .setContentIntent(open);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) {
                int type = 0;
                if (App.I.loc.permitted()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                try {
                    if (type == 0) startForeground(42, n);
                    else startForeground(42, n, type);
                } catch (Exception e) {
                    // Android a refusé le GPS dans ce service : on démarre au moins la bulle du volume.
                    if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    else startForeground(42, n);
                }
            } else {
                startForeground(42, n);
            }
        } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Le GPS a peut-être été autorisé depuis : on met à jour le type du service et on démarre le GPS.
        goForeground();
        App.I.loc.start();
        return START_STICKY;
    }

    /** Toutes les secondes : quelle appli est à l'écran ? (seulement si l'accès est donné) */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (screenOn && P.bubble()) {
                String fg = foreground();
                if (fg != null) fgPkg = fg;
                boolean show = !fgPkg.isEmpty() && fgPkg.equals(App.I.apps.carplayPkg()) && canOverlay(LauncherService.this);
                if (show) bubble.show(); else bubble.hide();
            } else {
                bubble.hide();
            }
            h.postDelayed(this, 1000);
        }
    };

    private String foreground() {
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long from = lastQuery == 0 ? now - 86400000L : Math.min(lastQuery, now - 2000); // 1re fois : dernières 24 h
            lastQuery = now;
            UsageEvents ev = um.queryEvents(from, now);
            if (ev == null) return null;
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static boolean usageAccess(Context c) {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int m = ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName());
            return m == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    void resetBubble() { if (bubble != null) bubble.resetPosition(); }

    void refreshBubble() { if (bubble != null) bubble.refresh(); }

    @Override public void onDestroy() {
        h.removeCallbacks(poll);
        if (bubble != null) bubble.hide();
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        App.I.loc.stop();
        I = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

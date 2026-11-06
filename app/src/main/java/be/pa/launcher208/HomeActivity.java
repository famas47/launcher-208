package be.pa.launcher208;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

/** L'écran d'accueil (launcher). Une seule activité : on change de « page » sans rien recharger. */
public class HomeActivity extends Activity {
    static final int S_HOME = 0, S_APPS = 1, S_SETTINGS = 2, S_CONN = 3, S_TEST = 4;
    static final int REQ_LOC = 1, REQ_BT = 2, REQ_MAP = 3;

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root, content;
    private LinearLayout mainRow;
    private Rail rail;
    private final Screen[] screens = new Screen[5];
    private int screen = S_HOME;
    private DriveScreen drive;
    private boolean driving, resumed, nightNow;
    private long stopSince;
    private int lastMinute = -1;
    private boolean firstFrameDone;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        nightNow = App.I.night.isNight(Clock.now());
        Pal.set(nightNow);
        root = new FrameLayout(this);
        build();
        setContentView(root);
        applyWindow();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!firstFrameDone) {
                    firstFrameDone = true;
                    if (App.firstFrameMs < 0) App.firstFrameMs = SystemClock.uptimeMillis() - App.processStart;
                    h.post(afterFirstFrame);
                }
                return true;
            }
        });
    }

    /** Tout ce qui n'est pas nécessaire pour afficher le premier écran est fait ensuite. */
    private final Runnable afterFirstFrame = new Runnable() {
        @Override public void run() {
            App.I.apps.onLoaded = new Runnable() {
                @Override public void run() {
                    for (Screen s : screens) if (s != null) s.refresh();
                    maybeOpenCarplayAtStart();
                }
            };
            App.I.apps.load();
            App.I.night.onChange = new Runnable() {
                @Override public void run() { checkNight(); }
            };
            App.I.night.start();
            App.I.weather.onUpdate = new Runnable() {
                @Override public void run() { refreshAll(); }
            };
            LauncherService.start(HomeActivity.this);
            if (!App.I.loc.permitted()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            }
            App.I.weather.maybeRefresh(HomeActivity.this);
            CarView.preload(HomeActivity.this);
        }
    };

    private void maybeOpenCarplayAtStart() {
        if (!P.carplayAtStart()) return;
        long boot = bootId();
        if (boot == P.l(P.LAST_BOOT, -1)) return;
        P.put(P.LAST_BOOT, boot);
        if (SystemClock.elapsedRealtime() > 10L * 60000L) return; // seulement juste après l'allumage
        String pkg = App.I.apps.carplayPkg();
        if (!pkg.isEmpty()) App.I.apps.launch(this, pkg);
    }

    private long bootId() {
        if (Build.VERSION.SDK_INT >= 24) {
            try { return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT); } catch (Exception ignored) { }
        }
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 120000L; // à 2 min près
    }

    // ---------- Construction ----------
    private void build() {
        for (Screen s : screens) if (s != null) s.destroy();
        for (int i = 0; i < screens.length; i++) screens[i] = null;
        if (drive != null) { drive.onHide(); drive.destroy(); drive = null; }
        root.removeAllViews();
        root.setBackgroundColor(Pal.bg);
        mainRow = U.row(this);
        mainRow.setGravity(0);
        U.pad(mainRow, 16, 16, 16, 16);
        rail = new Rail(this);
        mainRow.addView(rail.view(), U.lp(U.s(84), U.M));
        mainRow.addView(U.gap(this, 16, 1));
        content = new FrameLayout(this);
        mainRow.addView(content, U.lpW(1));
        root.addView(mainRow, new FrameLayout.LayoutParams(U.M, U.M));
        showScreen(screen);
        if (driving) {
            driving = false;
            enterDrive();
        }
    }

    Screen screen(int i) {
        if (screens[i] == null) {
            switch (i) {
                case S_HOME: screens[i] = new HomeScreen(this); break;
                case S_APPS: screens[i] = new AppsScreen(this); break;
                case S_SETTINGS: screens[i] = new SettingsScreen(this); break;
                case S_CONN: screens[i] = new ConnScreen(this); break;
                default: screens[i] = new TestScreen(this); break;
            }
        }
        return screens[i];
    }

    void showScreen(int i) {
        if (screens[screen] != null && screen != i) screens[screen].onHide();
        screen = i;
        content.removeAllViews();
        Screen s = screen(i);
        View v = s.view();
        if (v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(U.M, U.M));
        rail.select(i);
        s.onShow();
    }

    int current() { return screen; }

    HomeScreen home() { return (HomeScreen) screen(S_HOME); }

    void refreshAll() {
        Screen s = screens[screen];
        if (s != null) s.refresh();
        if (drive != null && driving) drive.refresh();
    }

    /** Thème jour/nuit : on reconstruit l'interface (une fois au coucher du soleil, invisible). */
    void checkNight() {
        boolean n = App.I.night.isNight(Clock.now());
        if (n != nightNow) {
            nightNow = n;
            Pal.set(n);
            build();
            applyWindow();
        }
    }

    void rebuildForSettings() {
        Pal.set(nightNow = App.I.night.isNight(Clock.now()));
        build();
        applyWindow();
    }

    private void applyWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Pal.bg);
        w.setNavigationBarColor(Pal.bg);
        int f = 0;
        if (P.fullscreen()) {
            f = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        } else if (!Pal.night) {
            f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        w.getDecorView().setSystemUiVisibility(f);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyWindow();
    }

    // ---------- Mode conduite ----------
    void enterDrive() {
        if (driving) return;
        driving = true;
        stopSince = 0;
        if (drive == null) drive = new DriveScreen(this);
        View v = drive.view();
        if (v.getParent() == null) root.addView(v, new FrameLayout.LayoutParams(U.M, U.M));
        mainRow.setVisibility(View.GONE);
        drive.onShow();
    }

    void exitDrive() {
        if (!driving) return;
        driving = false;
        if (drive != null) {
            drive.onHide();
            root.removeView(drive.view());
            drive.destroy();
            drive = null;
        }
        mainRow.setVisibility(View.VISIBLE);
        Screen s = screens[screen];
        if (s != null) s.refresh();
    }

    boolean driving() { return driving; }

    private void driveLogic() {
        if (!P.drive()) { if (driving) exitDrive(); return; }
        float v = App.I.speed();
        if (!driving) {
            if (v >= LocationTracker.START_KMH) enterDrive();
            return;
        }
        if (v < 5f) {
            long now = SystemClock.uptimeMillis();
            if (stopSince == 0) stopSince = now;
            else if (now - stopSince >= P.driveReturn() * 1000L) exitDrive();
        } else {
            stopSince = 0;
        }
    }

    // ---------- Tic chaque seconde (seulement quand l'accueil est visible) ----------
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            driveLogic();
            int m = (int) (Clock.now() / 60000L);
            if (m != lastMinute) {
                lastMinute = m;
                checkNight();
                App.I.weather.maybeRefresh(HomeActivity.this);
                refreshAll();
            }
            if (driving && drive != null) drive.tick();
            else {
                Screen s = screens[screen];
                if (s != null) s.tick();
            }
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable onDoors = new Runnable() {
        @Override public void run() {
            if (screens[S_HOME] != null) home().doorsChanged();
        }
    };

    private final Runnable onVolume = new Runnable() {
        @Override public void run() {
            if (screens[S_HOME] != null) home().volumeChanged();
            if (drive != null) drive.volumeChanged();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        App.I.doors.add(onDoors);
        App.I.vol.add(onVolume);
        lastMinute = -1;
        h.removeCallbacks(tick);
        h.post(tick);
        if (screens[screen] != null) screens[screen].onShow();
        if (drive != null && driving) drive.onShow();
        if (firstFrameDone) {
            LauncherService.start(this);
            CarView.preload(this); // ne fait rien si les vues sont déjà en mémoire
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Envoyé seulement quand l'autoradio manque vraiment de mémoire et que l'accueil est caché.
        if (level >= TRIM_MEMORY_BACKGROUND && !resumed) CarView.trim();
    }

    @Override protected void onPause() {
        resumed = false;
        h.removeCallbacks(tick);
        App.I.doors.remove(onDoors);
        App.I.vol.remove(onVolume);
        if (screens[screen] != null) screens[screen].onHide();
        if (drive != null && driving) drive.onHide();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Bouton « accueil » pressé alors qu'on est déjà dans le launcher : retour à la page principale.
        if (!driving && screen != S_HOME) showScreen(S_HOME);
        else if (!driving && screens[S_HOME] != null) home().tab(0);
    }

    @Override public void onBackPressed() {
        if (screen == S_CONN || screen == S_TEST) showScreen(S_SETTINGS);
        else if (screen != S_HOME) showScreen(S_HOME);
        else if (screens[S_HOME] != null) home().tab(0);
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        for (Screen s : screens) if (s != null) s.destroy();
        if (drive != null) drive.destroy();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_LOC && App.I.loc.permitted()) {
            LauncherService.start(this);
            App.I.loc.start();
        }
        refreshAll();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MAP && res == RESULT_OK && data != null && data.getData() != null) {
            App.I.mapdl.importFrom(this, data.getData());
            refreshAll();
        }
    }

    // ---------- Actions communes ----------
    void openCarplay() {
        String pkg = App.I.apps.carplayPkg();
        if (!App.I.apps.launch(this, pkg)) {
            Toast.makeText(this, "Choisis l'application CarPlay (S-Play)", Toast.LENGTH_LONG).show();
            chooseApp("Application CarPlay", P.PKG_CARPLAY, null);
        }
    }

    void openRole(String key, int cat, String title) {
        String pkg = App.I.apps.pkgFor(key, cat);
        if (!App.I.apps.launch(this, pkg)) {
            Toast.makeText(this, "Choisis l'application « " + title + " »", Toast.LENGTH_LONG).show();
            chooseApp(title, key, null);
        }
    }

    /** Liste des applications pour choisir celle d'un rôle (CarPlay, radio...). */
    void chooseApp(String title, final String key, final Runnable done) {
        final List<AppsRepo.AppInfo> l = App.I.apps.list();
        if (l.isEmpty()) {
            Toast.makeText(this, "Liste des applications en cours de chargement…", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[l.size()];
        for (int i = 0; i < names.length; i++) names[i] = l.get(i).label;
        dialog().setTitle(title).setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                P.put(key, l.get(which).pkg);
                refreshAll();
                if (done != null) done.run();
            }
        }).setNegativeButton("Annuler", null).show();
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, Pal.night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    /** « Écran d'origine » : ouvre l'ancien écran d'accueil, ou le choix de l'écran par défaut. */
    void originalHome() {
        final List<android.content.pm.ResolveInfo> homes = App.I.apps.otherHomes();
        dialog().setTitle("Revenir à l'écran d'origine")
                .setMessage("Rien n'est effacé. Tu peux ouvrir l'écran d'origine juste une fois, ou le remettre comme écran d'accueil par défaut.")
                .setPositiveButton("Choisir l'écran par défaut", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        AppsRepo.openSettings(HomeActivity.this, Settings.ACTION_HOME_SETTINGS);
                    }
                })
                .setNeutralButton("Ouvrir une fois", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (homes.isEmpty()) {
                            Toast.makeText(HomeActivity.this, "Écran d'origine introuvable", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        android.content.pm.ActivityInfo ai = homes.get(0).activityInfo;
                        try {
                            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                    .setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            Toast.makeText(HomeActivity.this, "Impossible d'ouvrir l'écran d'origine", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Annuler", null).show();
    }

    void pickMapFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, REQ_MAP);
        } catch (Exception e) {
            java.io.File f = MapDl.findOnUsb();
            if (f != null) App.I.mapdl.importFile(this, f);
            else Toast.makeText(this, "Mets belgium.map à la racine de la clé USB, puis réessaie", Toast.LENGTH_LONG).show();
        }
    }

    void requestPerm(String perm, int req) { requestPermissions(new String[]{perm}, req); }

    static Uri pkgUri(String p) { return Uri.parse("package:" + p); }
}

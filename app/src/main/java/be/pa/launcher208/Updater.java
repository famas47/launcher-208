package be.pa.launcher208;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Mises à jour depuis GitHub : le launcher regarde la dernière version fabriquée par GitHub,
 * la télécharge en Wi-Fi et demande à Android de l'installer par-dessus (réglages gardés).
 */
final class Updater {
    volatile boolean busy;
    volatile String status = "";
    volatile int remoteVersion;
    volatile String apkUrl = "";
    volatile int percent = -1;
    Runnable onChange;

    static int current() { return BuildConfig.VERSION_CODE; }

    static String repo() { return BuildConfig.GH_REPO; }

    private void changed() {
        App.I.main.post(new Runnable() {
            @Override public void run() { if (onChange != null) onChange.run(); }
        });
    }

    void check(final Context c) {
        if (busy) return;
        if (repo().isEmpty()) { status = "Version fabriquée hors GitHub : pas de mise à jour automatique"; changed(); return; }
        if (!Weather.online(c)) { status = "Pas de connexion Internet"; changed(); return; }
        busy = true;
        status = "Recherche d'une mise à jour…";
        changed();
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject j = new JSONObject(Weather.get("https://api.github.com/repos/" + repo() + "/releases/latest"));
                    String tag = j.optString("tag_name", "");
                    int v = 0;
                    try { v = Integer.parseInt(tag.replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { }
                    String url = "";
                    JSONArray assets = j.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) { url = a.optString("browser_download_url", ""); break; }
                        }
                    }
                    remoteVersion = v;
                    apkUrl = url;
                    if (v > current() && !url.isEmpty()) status = "Nouvelle version " + v + " disponible (tu as la " + current() + ")";
                    else status = "Tu as déjà la dernière version (" + current() + ")";
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if (m.contains("404")) status = "Aucune version publiée, ou projet GitHub privé";
                    else status = "Impossible de vérifier : " + Net.why(e);
                } finally {
                    busy = false;
                    changed();
                }
            }
        });
    }

    boolean available() { return remoteVersion > current() && !apkUrl.isEmpty(); }

    /** Vrai si Android autorise le launcher à installer des mises à jour. */
    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void askInstallPermission(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            AppsRepo.openSettings(c, Settings.ACTION_SECURITY_SETTINGS);
        }
    }

    void install(final Context c) {
        if (busy || !available()) return;
        if (!canInstall(c)) {
            status = "Autorise d'abord l'installation (écran qui vient de s'ouvrir), puis réessaie";
            changed();
            askInstallPermission(c);
            return;
        }
        busy = true;
        percent = 0;
        status = "Téléchargement de la mise à jour…";
        changed();
        final String url = apkUrl;
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                PackageInstaller.Session s = null;
                HttpURLConnection h = null;
                try {
                    h = Net.open(url);
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    if (h.getResponseCode() != 200) throw new Exception("téléchargement refusé (" + h.getResponseCode() + ")");
                    long len = h.getContentLength();
                    PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    sp.setAppPackageName(c.getPackageName());
                    int id = pi.createSession(sp);
                    s = pi.openSession(id);
                    InputStream in = h.getInputStream();
                    OutputStream out = s.openWrite("update.apk", 0, len > 0 ? len : -1);
                    byte[] b = new byte[64 * 1024];
                    int n;
                    long got = 0;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        got += n;
                        if (len > 0) {
                            int p = (int) (got * 100 / len);
                            if (p != percent) { percent = p; changed(); }
                        }
                    }
                    s.fsync(out);
                    out.close();
                    in.close();
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pend = PendingIntent.getBroadcast(c, 7, new Intent(c, InstallReceiver.class), flags);
                    status = "Installation… confirme sur l'écran d'Android";
                    s.commit(pend.getIntentSender());
                    s.close();
                    s = null;
                } catch (Exception e) {
                    status = "Échec : " + Net.why(e);
                    if (s != null) try { s.abandon(); } catch (Exception ignored) { }
                } finally {
                    if (h != null) h.disconnect();
                    busy = false;
                    percent = -1;
                    changed();
                }
            }
        });
    }
}

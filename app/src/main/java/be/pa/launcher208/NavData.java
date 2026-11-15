package be.pa.launcher208;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * Données de la navigation : réseau routier et adresses de Belgique (deux fichiers, environ 150 Mo en tout),
 * téléchargés une fois en Wi-Fi depuis la page du projet (fabriqués à partir d'OpenStreetMap).
 * Aussi : les dernières destinations choisies.
 */
final class NavData {
    static final String BASE = "https://github.com/famas47/launcher-208/releases/download/nav-donnees/";
    static final String[] FILES = {"belgique-routes.bin", "belgique-adresses.bin"};

    volatile boolean running;
    volatile long done, total;
    volatile String status = "";
    private volatile boolean cancel;
    Runnable onProgress;

    private NavGraph graph;
    private Router router;
    private AddrIndex index;
    private volatile String openError = "";

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    static File file(Context c, int i) { return new File(dir(c), FILES[i]); }

    static boolean available(Context c) {
        for (int i = 0; i < FILES.length; i++) if (!file(c, i).exists() || file(c, i).length() < 1000) return false;
        return true;
    }

    static long size(Context c) {
        long s = 0;
        for (int i = 0; i < FILES.length; i++) s += file(c, i).length();
        return s;
    }

    /** Ouvre les fichiers (une seule fois). À appeler hors du fil de l'écran. Null si impossible. */
    synchronized Router router() {
        if (router == null && open()) router = new Router(graph);
        return router;
    }

    synchronized AddrIndex index() {
        if (index == null) open();
        return index;
    }

    String error() { return openError; }

    private boolean open() {
        if (graph != null && index != null) return true;
        try {
            if (!available(App.I)) { openError = "données non téléchargées"; return false; }
            if (graph == null) graph = new NavGraph(file(App.I, 0));
            if (index == null) index = new AddrIndex(file(App.I, 1));
            openError = "";
            return true;
        } catch (Throwable t) {
            openError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " : " + t.getMessage());
            graph = null;
            index = null;
            return false;
        }
    }

    /** Après un nouveau téléchargement : on relira les fichiers. */
    private synchronized void forget() {
        graph = null;
        router = null;
        index = null;
    }

    // ---------- Téléchargement ----------

    private void progress() {
        App.I.main.post(new Runnable() {
            @Override public void run() { if (onProgress != null) onProgress.run(); }
        });
    }

    void cancel() { cancel = true; }

    void download(final Context c) {
        if (running) return;
        running = true;
        cancel = false;
        status = "Connexion…";
        progress();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // taille totale d'abord (pour la barre de progression et la place libre)
                    long[] sizes = new long[FILES.length];
                    long all = 0;
                    for (int i = 0; i < FILES.length; i++) { sizes[i] = remoteSize(BASE + FILES[i]); all += sizes[i]; }
                    total = all;
                    File d = dir(c);
                    if (!d.exists()) d.mkdirs();
                    long have = 0;
                    for (int i = 0; i < FILES.length; i++) have += Math.max(file(c, i).length(), part(c, i).length());
                    if (all > 0 && d.getUsableSpace() < all - have + 50L * 1024 * 1024) throw new Exception("pas assez de place sur l'autoradio");
                    done = 0;
                    for (int i = 0; i < FILES.length; i++) {
                        File dest = file(c, i);
                        if (dest.exists() && dest.length() == sizes[i]) { done += sizes[i]; continue; }
                        fetch(BASE + FILES[i], part(c, i), sizes[i]);
                        File p = part(c, i);
                        if (sizes[i] > 0 && p.length() != sizes[i]) throw new Exception("téléchargement incomplet, touche pour reprendre");
                        if (dest.exists()) dest.delete();
                        if (!p.renameTo(dest)) throw new Exception("impossible d'enregistrer le fichier");
                    }
                    forget();
                    status = "Prêt";
                } catch (Exception e) {
                    status = "Interrompu : " + Net.why(e);
                } finally {
                    running = false;
                    progress();
                }
            }
        }, "navigation").start();
    }

    private static File part(Context c, int i) { return new File(dir(c), FILES[i] + ".part"); }

    /** Ouvre l'adresse en suivant les redirections (GitHub renvoie vers son serveur de fichiers). */
    private static HttpURLConnection connect(String url, long from, String method) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = Net.open(url);
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            if (method != null) h.setRequestMethod(method);
            if (from > 0) h.setRequestProperty("Range", "bytes=" + from + "-");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                url = new java.net.URL(new java.net.URL(url), h.getHeaderField("Location")).toString();
                h.disconnect();
                continue;
            }
            return h;
        }
        throw new Exception("trop de redirections");
    }

    private static long remoteSize(String url) throws Exception {
        HttpURLConnection h = connect(url, 0, "HEAD");
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new Exception("le serveur répond " + code);
            long len = h.getContentLength();
            if (android.os.Build.VERSION.SDK_INT >= 24) len = h.getContentLengthLong();
            return len;
        } finally {
            h.disconnect();
        }
    }

    private void fetch(String url, File part, long size) throws Exception {
        long have = part.exists() ? part.length() : 0;
        if (size > 0 && have >= size) { done += have; return; }
        HttpURLConnection h = connect(url, have, null);
        try {
            int code = h.getResponseCode();
            boolean append = code == 206 && have > 0;
            if (code != 200 && code != 206) throw new Exception("le serveur répond " + code);
            done += append ? have : 0;
            InputStream in = h.getInputStream();
            OutputStream out = new FileOutputStream(part, append);
            byte[] buf = new byte[64 * 1024];
            int n;
            long lastUi = 0;
            try {
                while ((n = in.read(buf)) > 0) {
                    if (cancel) throw new Exception("arrêté");
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 500) {
                        lastUi = now;
                        status = "Téléchargement…";
                        progress();
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        } finally {
            h.disconnect();
        }
    }

    // ---------- Dernières destinations ----------

    /** Une destination : nom, précision (ville, catégorie), position. */
    static final class Place {
        String name, sub;
        double lat, lon;

        Place(String name, String sub, double lat, double lon) {
            this.name = name;
            this.sub = sub;
            this.lat = lat;
            this.lon = lon;
        }
    }

    static java.util.ArrayList<Place> recents() {
        java.util.ArrayList<Place> l = new java.util.ArrayList<>();
        try {
            JSONArray a = new JSONArray(P.s(P.NAV_RECENT, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new Place(o.getString("n"), o.optString("s"), o.getDouble("la"), o.getDouble("lo")));
            }
        } catch (Exception ignored) { }
        return l;
    }

    static void addRecent(Place p) {
        java.util.ArrayList<Place> l = recents();
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("n", p.name).put("s", p.sub).put("la", p.lat).put("lo", p.lon));
            int k = 1;
            for (Place o : l) {
                if (k >= 8) break;
                if (o.name.equals(p.name) && o.sub.equals(p.sub)) continue;
                a.put(new JSONObject().put("n", o.name).put("s", o.sub).put("la", o.lat).put("lo", o.lon));
                k++;
            }
        } catch (Exception ignored) { }
        P.put(P.NAV_RECENT, a.toString());
    }
}

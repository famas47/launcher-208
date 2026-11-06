package be.pa.launcher208;

import android.content.Context;
import android.net.Uri;

import org.mapsforge.map.reader.MapFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Téléchargement de la carte de Belgique (environ 500 Mo), avec reprise si la connexion coupe.
 * Aussi : import depuis une clé USB.
 */
final class MapDl {
    static final String URL_MAP = "https://download.mapsforge.org/maps/v5/europe/belgium.map";

    volatile boolean running;
    volatile long done, total;
    volatile String status = "";
    private volatile boolean cancel;
    Runnable onProgress;

    private void progress() {
        App.I.main.post(new Runnable() {
            @Override public void run() { if (onProgress != null) onProgress.run(); }
        });
    }

    File part(Context c) { return new File(MiniMap.file(c).getPath() + ".part"); }

    /** Octets déjà téléchargés (reprise possible). */
    long partial(Context c) { File p = part(c); return p.exists() ? p.length() : 0; }

    void cancel() { cancel = true; }

    void download(final Context c) {
        if (running) return;
        running = true;
        cancel = false;
        status = "Connexion…";
        progress();
        new Thread(new Runnable() {
            @Override public void run() {
                File part = part(c), dest = MiniMap.file(c);
                HttpURLConnection h = null;
                try {
                    File dir = part.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    long have = part.exists() ? part.length() : 0;
                    h = (HttpURLConnection) new URL(URL_MAP).openConnection();
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    h.setRequestProperty("User-Agent", "Launcher208");
                    if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
                    int code = h.getResponseCode();
                    if (code == 416 && have > 0) { // déjà tout reçu
                        h.disconnect();
                        h = null;
                        finish(part, dest);
                        return;
                    }
                    boolean append = code == 206 && have > 0;
                    if (code != 200 && code != 206) throw new Exception("le serveur répond " + code);
                    long len = h.getContentLength();
                    if (android.os.Build.VERSION.SDK_INT >= 24) len = h.getContentLengthLong();
                    total = append ? have + len : len;
                    done = append ? have : 0;
                    if (dir != null && total > 0 && dir.getUsableSpace() < total - done + 50L * 1024 * 1024) {
                        throw new Exception("pas assez de place sur l'autoradio");
                    }
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
                    if (total > 0 && part.length() < total) throw new Exception("téléchargement incomplet, touche pour reprendre");
                    finish(part, dest);
                } catch (Exception e) {
                    status = "Interrompu : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } finally {
                    if (h != null) h.disconnect();
                    running = false;
                    progress();
                }
            }
        }, "carte").start();
    }

    /** Copie depuis un fichier choisi (clé USB). */
    void importFrom(final Context c, final Uri uri) {
        if (running) return;
        running = true;
        cancel = false;
        status = "Copie depuis la clé USB…";
        progress();
        new Thread(new Runnable() {
            @Override public void run() {
                File part = part(c), dest = MiniMap.file(c);
                try {
                    InputStream in = c.getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("fichier illisible");
                    copy(in, part, -1);
                    finish(part, dest);
                } catch (Exception e) {
                    status = "Erreur : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    part.delete();
                } finally {
                    running = false;
                    progress();
                }
            }
        }, "carte-usb").start();
    }

    /** Copie depuis un chemin direct (autoradios sans sélecteur de fichiers). */
    void importFile(final Context c, final File src) {
        if (running) return;
        running = true;
        cancel = false;
        status = "Copie depuis la clé USB…";
        progress();
        new Thread(new Runnable() {
            @Override public void run() {
                File part = part(c), dest = MiniMap.file(c);
                try {
                    copy(new java.io.FileInputStream(src), part, src.length());
                    finish(part, dest);
                } catch (Exception e) {
                    status = "Erreur : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    part.delete();
                } finally {
                    running = false;
                    progress();
                }
            }
        }, "carte-usb").start();
    }

    /** Cherche belgium.map à la racine des clés USB / cartes SD visibles. */
    static File findOnUsb() {
        String[] roots = {"/storage", "/mnt/media_rw", "/mnt/usb", "/mnt"};
        for (String r : roots) {
            File[] l = new File(r).listFiles();
            if (l == null) continue;
            for (File d : l) {
                File f = new File(d, "belgium.map");
                if (f.isFile() && f.canRead() && f.length() > 1000000L) return f;
            }
        }
        return null;
    }

    private void copy(InputStream in, File part, long size) throws Exception {
        File dir = part.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        OutputStream out = new FileOutputStream(part, false);
        byte[] buf = new byte[64 * 1024];
        int n;
        done = 0;
        total = size;
        long lastUi = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                if (cancel) throw new Exception("arrêté");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) { lastUi = now; progress(); }
            }
        } finally {
            out.close();
            in.close();
        }
    }

    /** Vérifie que la carte s'ouvre bien avant de la mettre en place. */
    private void finish(File part, File dest) throws Exception {
        status = "Vérification de la carte…";
        progress();
        MapFile test = null;
        try {
            test = new MapFile(part, "fr");
        } catch (Throwable t) {
            part.delete();
            throw new Exception("fichier de carte invalide");
        } finally {
            if (test != null) try { test.close(); } catch (Throwable ignored) { }
        }
        if (dest.exists() && !dest.delete()) throw new Exception("impossible de remplacer l'ancienne carte");
        if (!part.renameTo(dest)) throw new Exception("impossible d'enregistrer la carte");
        status = "Carte installée";
    }

}

package be.pa.launcher208;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.Way;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.labels.ThreadedLabelLayer;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;

import java.io.File;

/**
 * Minimap hors ligne (carte OpenStreetMap de Belgique, dessinée sur l'autoradio).
 * Créée seulement quand le mode conduite s'affiche, détruite ensuite : rien ne tourne en dehors.
 *
 * Trois vues : 3D (inclinée, tourne avec la voiture, façon Tesla), à plat qui tourne, ou nord en haut.
 * Pour voir la voiture avancer en continu, la position est prolongée entre deux points GPS (vitesse et cap)
 * et lissée, environ 30 fois par seconde, seulement quand la voiture roule.
 */
final class MiniMap {
    static final int VIEW_3D = 0, VIEW_FLAT = 1, VIEW_NORTH = 2;
    static final String[] VIEW_NAMES = {"3D", "À plat", "Nord en haut"};

    /** Carte en couleurs de nuit (réglage « Couleurs de la carte » : auto = comme le reste de l'écran). */
    static boolean night() {
        int c = P.mapColors();
        return c == 1 ? false : c == 2 ? true : Pal.night;
    }

    static int bg(boolean night) { return night ? 0xFF25282E : 0xFFEEF0F2; }

    /** Appelé à chaque image avec le cap affiché (pour faire tourner la boussole). */
    interface HeadingListener { void onHeading(float degrees); }

    private int view = VIEW_NORTH;
    private float centerY = 0.5f;
    private HeadingListener headingListener;
    private static boolean graphicsReady;
    private MapView mv;
    private TileCache cache;
    private MapFile mapFile;
    private MapFile lookupFile; // utilisé seulement par le fil d'arrière-plan (App.bg, un seul fil)
    private boolean attached; // vrai quand la carte est confiée à la vue (elle la fermera)
    private double lastLookLat, lastLookLon;
    private volatile boolean looking, destroyed;
    String error = "";
    static volatile String lastError = ""; // pour le mode test

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new File(dir, "belgium.map");
    }

    static boolean available(Context c) {
        File f = file(c);
        return f.exists() && f.length() > 1000000L;
    }

    /** Conteneur qui empêche de déplacer la carte au doigt (elle suit la voiture). */
    static final class Frame extends FrameLayout {
        Frame(Context c) { super(c); }
        @Override public boolean onInterceptTouchEvent(MotionEvent ev) { return true; }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
    }

    /** Crée la vue carte (nord en haut), ou renvoie null si la carte n'est pas téléchargée ou illisible. */
    MapView create(Context c) { return create(c, VIEW_NORTH, Pal.night, 0.5f); }

    /**
     * Crée la vue carte, ou renvoie null si la carte n'est pas téléchargée ou illisible.
     * centerY : hauteur de la voiture dans la vue (0,5 = au milieu ; plus bas pour voir plus loin devant).
     */
    MapView create(Context c, int view, boolean night, float centerY) {
        this.view = view;
        this.centerY = view == VIEW_NORTH ? 0.5f : centerY;
        if (!available(c)) { error = "carte non téléchargée"; return null; }
        try {
            if (!graphicsReady) {
                AndroidGraphicFactory.createInstance(App.I);
                // Par défaut, Mapsforge dessine avec un fil par cœur du processeur + 1 (9 sur un autoradio à 8 cœurs) :
                // 2 suffisent pour une minimap, et l'écran garde tout le processeur dont il a besoin.
                Parameters.NUMBER_OF_THREADS = 2;
                graphicsReady = true;
            }
            mv = new MapView(c);
            mv.setClickable(false);
            mv.getMapScaleBar().setVisible(false);
            mv.setBuiltInZoomControls(false);
            mv.getModel().displayModel.setBackgroundColor(bg(night));
            boolean rotating = view != VIEW_NORTH;
            // Tuiles gardées en mémoire seulement : rien n'est écrit sur la mémoire de l'autoradio
            // (moins de travail pendant la conduite, et rien à effacer en sortant du mode conduite).
            int minCache = AndroidUtil.getMinimumCacheSize(c, mv.getModel().displayModel.getTileSize(),
                    mv.getModel().frameBufferModel.getOverdrawFactor(), 1f);
            // carte qui tourne : la zone dessinée est plus grande (diagonale), il faut plus de tuiles en mémoire
            cache = new InMemoryTileCache(rotating ? Math.max(minCache, 110) : minCache);
            mapFile = new MapFile(file(c), "fr");
            // Carte qui tourne : les noms de rues sont dessinés à part, pour rester toujours à l'endroit
            TileRendererLayer layer = new TileRendererLayer(cache, mapFile, mv.getModel().mapViewPosition,
                    false, !rotating, rotating, AndroidGraphicFactory.INSTANCE);
            layer.setXmlRenderTheme(new AssetsRenderTheme(c.getAssets(), "map/", night ? "theme-nuit.xml" : "theme.xml"));
            mv.getLayerManager().getLayers().add(layer);
            if (rotating) {
                mv.getLayerManager().getLayers().add(new ThreadedLabelLayer(AndroidGraphicFactory.INSTANCE, layer.getLabelStore()));
                mv.setMapViewCenterY(this.centerY);
            }
            attached = true;
            mv.setZoomLevelMin((byte) 12);
            mv.setZoomLevelMax((byte) 18);
            mv.getModel().mapViewPosition.setZoomLevel((byte) 16);
            LocationTracker loc = App.I.loc;
            dispLat = loc.lat != 0 ? loc.lat : P.lat();
            dispLon = loc.lon != 0 ? loc.lon : P.lon();
            dispBear = loc.bearing;
            mv.getModel().mapViewPosition.setCenter(new LatLong(dispLat, dispLon));
            error = "";
            lastError = "";
            return mv;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " : " + t.getMessage());
            lastError = error;
            destroy();
            return null;
        }
    }

    private double followLat, followLon;
    private byte followZoom;

    // ---------- Mouvement continu ----------
    private double dispLat, dispLon;   // position affichée (lissée)
    private float dispBear;            // cap affiché (lissé)
    private float targetBear;
    private long lastFrame, lastApply;
    private boolean animating;

    void setHeadingListener(HeadingListener l) { headingListener = l; }

    float heading() { return dispBear; }

    /** Appelé chaque seconde par l'écran de conduite : zoom selon la vitesse, nom de la rue, et relance du mouvement. */
    void follow(double lat, double lon, float kmh) {
        if (mv == null || (lat == 0 && lon == 0)) return;
        byte z = kmh > 90 ? (byte) 14 : kmh > 55 ? (byte) 15 : (byte) 16;
        if (z != followZoom) {
            followZoom = z;
            try {
                if (mv.getModel().mapViewPosition.getZoomLevel() != z) mv.getModel().mapViewPosition.setZoomLevel(z, true);
            } catch (Throwable ignored) { }
        }
        if (kmh > 4) targetBear = App.I.loc.bearing;
        if (lat != followLat || lon != followLon) {
            followLat = lat;
            followLon = lon;
            lookupStreet(lat, lon);
        }
        startAnimation();
    }

    private void startAnimation() {
        if (animating || mv == null) return;
        animating = true;
        lastFrame = 0;
        mv.postOnAnimation(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (mv == null || destroyed) { animating = false; return; }
            long now = SystemClock.uptimeMillis();
            // environ 30 images par seconde : assez pour un mouvement fluide, sans charger l'autoradio
            if (lastApply != 0 && now - lastApply < 30) { mv.postOnAnimation(this); return; }
            float dt = lastFrame == 0 ? 0.033f : Math.min(0.2f, (now - lastFrame) / 1000f);
            lastFrame = now;
            lastApply = now;
            boolean busy = step(now, dt);
            if (busy) mv.postOnAnimation(this);
            else animating = false; // voiture arrêtée et carte immobile : plus rien ne tourne
        }
    };

    /** Une image : prolonge la position GPS selon la vitesse, lisse, et place la carte. Renvoie vrai s'il faut continuer. */
    private boolean step(long now, float dt) {
        LocationTracker l = App.I.loc;
        if (l.lat == 0 && l.lon == 0) return false;
        float kmh = App.I.speed();
        // cap visé : lissé, par le chemin le plus court (359° → 1° tourne de 2°, pas de 358°)
        float db = ((targetBear - dispBear + 540f) % 360f) - 180f;
        dispBear = (dispBear + db * (1f - (float) Math.exp(-dt / 0.6f)) + 360f) % 360f;
        // position prévue : le dernier point GPS, avancé de la distance parcourue depuis (au plus 2 s)
        double pLat = l.lat, pLon = l.lon;
        if (kmh > 2 && l.lastFixUptime > 0) {
            double age = Math.min(2.0, (now - l.lastFixUptime) / 1000.0);
            double m = kmh / 3.6 * age, b = Math.toRadians(l.bearing);
            pLat += m * Math.cos(b) / 110540.0;
            pLon += m * Math.sin(b) / (111320.0 * Math.cos(Math.toRadians(l.lat)));
        }
        double dLat = pLat - dispLat, dLon = pLon - dispLon;
        double distM = Math.hypot(dLat * 110540.0, dLon * 111320.0 * Math.cos(Math.toRadians(pLat)));
        if (distM > 150) { dispLat = pLat; dispLon = pLon; }          // grand saut (GPS retrouvé) : pas d'animation
        else {
            double k = 1.0 - Math.exp(-dt / 0.35);
            dispLat += dLat * k;
            dispLon += dLon * k;
        }
        try {
            mv.getModel().mapViewPosition.setCenter(new LatLong(dispLat, dispLon));
            if (view != VIEW_NORTH) {
                mv.getModel().mapViewPosition.setRotation(new Rotation(-dispBear, mv.getWidth() * 0.5f, mv.getHeight() * centerY));
            }
        } catch (Throwable ignored) { }
        if (headingListener != null) headingListener.onHeading(dispBear);
        boolean moving = kmh > 1 || distM > 0.3 || Math.abs(db) > 0.3f;
        return moving;
    }

    /** Nom de la rue la plus proche, lu dans la carte (en arrière-plan, au plus tous les 25 m). */
    private void lookupStreet(final double lat, final double lon) {
        if (looking) return;
        if (Math.abs(lat - lastLookLat) < 0.00022 && Math.abs(lon - lastLookLon) < 0.00035) return;
        // Sens de la marche : depuis la recherche précédente (25 m à 300 m plus tôt), sinon le cap du GPS.
        double ux = 0, uy = 0;
        if (lastLookLat != 0 || lastLookLon != 0) {
            double dx = (lon - lastLookLon) * 111320.0 * Math.cos(Math.toRadians(lat)), dy = (lat - lastLookLat) * 110540.0;
            double n = Math.sqrt(dx * dx + dy * dy);
            if (n > 10 && n < 300) { ux = dx / n; uy = dy / n; }
        }
        if (ux == 0 && uy == 0 && App.I.loc.speedKmh > 8) {
            double b = Math.toRadians(App.I.loc.bearing);
            ux = Math.sin(b);
            uy = Math.cos(b);
        }
        final double dirX = ux, dirY = uy;
        lastLookLat = lat;
        lastLookLon = lon;
        looking = true;
        final File f = file(App.I);
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                String best = "";
                if (destroyed) { looking = false; return; } // mode conduite déjà quitté : on n'ouvre plus la carte
                try {
                    if (lookupFile == null) lookupFile = new MapFile(f, "fr");
                    byte zoom = 16;
                    int tx = MercatorProjection.longitudeToTileX(lon, zoom), ty = MercatorProjection.latitudeToTileY(lat, zoom);
                    Tile a = new Tile(Math.max(0, tx - 1), Math.max(0, ty - 1), zoom, 256);
                    Tile b = new Tile(tx + 1, ty + 1, zoom, 256);
                    MapReadResult r = lookupFile.readNamedItems(a, b);
                    double bestS = Double.MAX_VALUE;
                    StringBuilder debug = new StringBuilder();
                    if (r != null && r.ways != null) {
                        for (Way w : r.ways) {
                            String name = null;
                            boolean road = false;
                            for (Tag t : w.tags) {
                                if ("name".equals(t.key)) name = t.value;
                                else if ("highway".equals(t.key) && !isPath(t.value)) road = true;
                            }
                            if (!road || name == null || name.isEmpty() || w.latLongs == null) continue;
                            for (LatLong[] seg : w.latLongs) {
                                double dist = distance(lat, lon, seg);
                                if (dist > 45) continue; // rue trop loin (mètres)
                                double sc = score(lat, lon, seg, dirX, dirY);
                                if (BuildConfig.TEST_HOOKS) debug.append(String.format(java.util.Locale.ROOT, " | %s %.0fm note %.0f", name, dist, sc));
                                if (sc < bestS) { bestS = sc; best = name; }
                            }
                        }
                    }
                    if (BuildConfig.TEST_HOOKS) {
                        android.util.Log.i("Test208", String.format(java.util.Locale.ROOT, "rue %.5f,%.5f sens %.2f,%.2f -> %s%s",
                                lat, lon, dirX, dirY, best, debug.length() > 600 ? debug.substring(0, 600) : debug));
                    }
                } catch (Throwable ignored) {
                }
                final String street = best;
                looking = false;
                App.I.main.post(new Runnable() {
                    @Override public void run() { App.I.loc.street = street; }
                });
            }
        });
    }

    private static boolean isPath(String v) {
        return "footway".equals(v) || "path".equals(v) || "steps".equals(v) || "cycleway".equals(v) || "bridleway".equals(v);
    }

    /**
     * Note d'une rue (plus petite = meilleure) : distance en mètres, plus une pénalité jusqu'à 25 m si la rue
     * part en travers du sens de la marche. Ainsi, à un carrefour, c'est la rue qu'on suit qui gagne,
     * pas la rue qui la croise. Sans sens connu (ux = uy = 0), c'est simplement la distance.
     */
    static double score(double lat, double lon, LatLong[] line, double ux, double uy) {
        boolean dir = ux != 0 || uy != 0;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.length; i++) {
            double ax = (line[i].longitude - lon) * kx, ay = (line[i].latitude - lat) * ky;
            double bx = (line[i + 1].longitude - lon) * kx, by = (line[i + 1].latitude - lat) * ky;
            double dx = bx - ax, dy = by - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (dir) {
                double align = l2 == 0 ? 0 : Math.abs(dx * ux + dy * uy) / Math.sqrt(l2); // 1 = même sens, 0 = en travers
                d += 25 * (1 - align);
            }
            best = Math.min(best, d);
        }
        if (line.length == 1) best = distance(lat, lon, line) + (dir ? 25 : 0);
        return best;
    }

    /** Distance (m) d'un point à une ligne, approximation plane suffisante à cette échelle. */
    static double distance(double lat, double lon, LatLong[] line) {
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.length; i++) {
            double ax = (line[i].longitude - lon) * kx, ay = (line[i].latitude - lat) * ky;
            double bx = (line[i + 1].longitude - lon) * kx, by = (line[i + 1].latitude - lat) * ky;
            double dx = bx - ax, dy = by - ay;
            double l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
            double px = ax + t * dx, py = ay + t * dy;
            best = Math.min(best, Math.sqrt(px * px + py * py));
        }
        if (line.length == 1) {
            double ax = (line[0].longitude - lon) * kx, ay = (line[0].latitude - lat) * ky;
            best = Math.sqrt(ax * ax + ay * ay);
        }
        return best;
    }

    void destroy() {
        // destroyAll() ferme aussi la carte et vide le cache des tuiles qui lui ont été confiés
        try { if (mv != null) mv.destroyAll(); } catch (Throwable ignored) { }
        if (!attached) {
            try { if (cache != null) cache.destroy(); } catch (Throwable ignored) { }
            try { if (mapFile != null) mapFile.close(); } catch (Throwable ignored) { }
        }
        attached = false;
        mv = null;
        cache = null;
        mapFile = null;
        // fermée sur le même fil que la recherche de rue, donc toujours après elle (jamais pendant)
        destroyed = true;
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                MapFile lf = lookupFile;
                lookupFile = null;
                if (lf != null) try { lf.close(); } catch (Throwable ignored) { }
            }
        });
        try { if (graphicsReady) AndroidGraphicFactory.clearResourceMemoryCache(); } catch (Throwable ignored) { }
    }
}

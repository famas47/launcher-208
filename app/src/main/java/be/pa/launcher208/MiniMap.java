package be.pa.launcher208;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.Way;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;

import java.io.File;

/**
 * Minimap hors ligne (carte OpenStreetMap de Belgique, dessinée sur l'autoradio).
 * Créée seulement quand le mode conduite s'affiche, détruite ensuite : rien ne tourne en dehors.
 */
final class MiniMap {
    private static boolean graphicsReady;
    private MapView mv;
    private TileCache cache;
    private MapFile mapFile;
    private MapFile lookupFile;
    private boolean attached; // vrai quand la carte est confiée à la vue (elle la fermera)
    private double lastLookLat, lastLookLon;
    private volatile boolean looking;
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

    /** Crée la vue carte, ou renvoie null si la carte n'est pas téléchargée ou illisible. */
    MapView create(Context c) {
        if (!available(c)) { error = "carte non téléchargée"; return null; }
        try {
            if (!graphicsReady) {
                AndroidGraphicFactory.createInstance(App.I);
                graphicsReady = true;
            }
            mv = new MapView(c);
            mv.setClickable(false);
            mv.getMapScaleBar().setVisible(false);
            mv.setBuiltInZoomControls(false);
            mv.getModel().displayModel.setBackgroundColor(Pal.night ? 0xFF1B1E24 : 0xFFECEFF2);
            cache = AndroidUtil.createTileCache(c, "minimap", mv.getModel().displayModel.getTileSize(), 1f,
                    mv.getModel().frameBufferModel.getOverdrawFactor(), false);
            mapFile = new MapFile(file(c), "fr");
            TileRendererLayer layer = new TileRendererLayer(cache, mapFile, mv.getModel().mapViewPosition,
                    AndroidGraphicFactory.INSTANCE);
            layer.setXmlRenderTheme(new AssetsRenderTheme(c.getAssets(), "map/", Pal.night ? "theme-nuit.xml" : "theme.xml"));
            mv.getLayerManager().getLayers().add(layer);
            attached = true;
            mv.setZoomLevelMin((byte) 12);
            mv.setZoomLevelMax((byte) 18);
            mv.getModel().mapViewPosition.setZoomLevel((byte) 16);
            LocationTracker loc = App.I.loc;
            mv.getModel().mapViewPosition.setCenter(new LatLong(loc.lat != 0 ? loc.lat : P.lat(), loc.lon != 0 ? loc.lon : P.lon()));
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

    void follow(double lat, double lon, float kmh) {
        if (mv == null || (lat == 0 && lon == 0)) return;
        try {
            LatLong ll = new LatLong(lat, lon);
            if (P.fluidMax()) mv.getModel().mapViewPosition.animateTo(ll);
            else mv.getModel().mapViewPosition.setCenter(ll);
            byte z = kmh > 90 ? (byte) 14 : kmh > 55 ? (byte) 15 : (byte) 16;
            if (mv.getModel().mapViewPosition.getZoomLevel() != z) mv.getModel().mapViewPosition.setZoomLevel(z, P.fluidMax());
        } catch (Throwable ignored) { }
        lookupStreet(lat, lon);
    }

    /** Nom de la rue la plus proche, lu dans la carte (en arrière-plan, au plus tous les 25 m). */
    private void lookupStreet(final double lat, final double lon) {
        if (looking) return;
        if (Math.abs(lat - lastLookLat) < 0.00022 && Math.abs(lon - lastLookLon) < 0.00035) return;
        lastLookLat = lat;
        lastLookLon = lon;
        looking = true;
        final File f = file(App.I);
        App.I.bg.execute(new Runnable() {
            @Override public void run() {
                String best = "";
                try {
                    if (lookupFile == null) lookupFile = new MapFile(f, "fr");
                    byte zoom = 16;
                    int tx = MercatorProjection.longitudeToTileX(lon, zoom), ty = MercatorProjection.latitudeToTileY(lat, zoom);
                    Tile a = new Tile(Math.max(0, tx - 1), Math.max(0, ty - 1), zoom, 256);
                    Tile b = new Tile(tx + 1, ty + 1, zoom, 256);
                    MapReadResult r = lookupFile.readNamedItems(a, b);
                    double bestD = 45; // mètres
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
                                double d = distance(lat, lon, seg);
                                if (d < bestD) { bestD = d; best = name; }
                            }
                        }
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
        final MapFile lf = lookupFile;
        lookupFile = null;
        if (lf != null) {
            App.I.bg.execute(new Runnable() {
                @Override public void run() { try { lf.close(); } catch (Throwable ignored) { } }
            });
        }
        try { if (graphicsReady) AndroidGraphicFactory.clearResourceMemoryCache(); } catch (Throwable ignored) { }
    }
}

package be.pa.launcher208;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
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
import org.mapsforge.map.layer.overlay.FixedPixelCircle;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.model.MapViewPosition;
import org.mapsforge.map.reader.MapFile;

import java.io.File;
import java.util.Locale;

/**
 * Minimap hors ligne (carte OpenStreetMap de Belgique, dessinée sur l'autoradio).
 * Créée seulement quand le mode conduite s'affiche, détruite ensuite : rien ne tourne en dehors.
 *
 * Trois vues : 3D (inclinée, tourne avec la voiture, façon Tesla), à plat qui tourne, ou nord en haut.
 *
 * Pour que la voiture avance en continu, sans à-coups :
 * - chaque position GPS est collée sur la route (RoadSnap), et entre deux positions la voiture continue
 *   le long de cette route à la vitesse mesurée ; les écarts sont rattrapés en douceur (Glide) ;
 * - l'écran est mis à jour à chaque image (60 fois par seconde), au rythme exact de l'écran ;
 * - à chaque image, la carte déjà dessinée est seulement déplacée et tournée par la carte graphique
 *   (presque aucun travail). Elle n'est redessinée que de temps en temps (quand elle a glissé de quelques
 *   pixels ou tourné de quelques degrés), sur un point exact de la grille des pixels, pour ne jamais
 *   trembler d'un demi-pixel.
 */
final class MiniMap {
    static final int VIEW_3D = 0, VIEW_FLAT = 1, VIEW_NORTH = 2;
    static final String[] VIEW_NAMES = {"3D", "À plat", "Nord en haut"};
    /** Glissement (pixels) et rotation (degrés) faits par la carte graphique avant de redessiner la carte. */
    static final float MAX_SHIFT_PX = 16f, MAX_TURN = 4f;

    /** Carte en couleurs de nuit (réglage « Couleurs de la carte » : auto = comme le reste de l'écran). */
    static boolean night() {
        int c = P.mapColors();
        return c == 1 ? false : c == 2 ? true : Pal.night;
    }

    static int bg(boolean night) { return night ? 0xFF25282E : 0xFFEEF0F2; }

    /** Appelé à chaque image avec le cap affiché (pour faire tourner la boussole). */
    interface HeadingListener { void onHeading(float degrees); }

    private int view = VIEW_NORTH;
    private HeadingListener headingListener;
    private static boolean graphicsReady;
    private MapView mv;
    private TileCache cache;
    private MapFile mapFile;
    private boolean attached; // vrai quand la carte est confiée à la vue (elle la fermera)
    private volatile boolean destroyed;
    String error = "";
    static volatile String lastError = ""; // pour le mode test

    // Fil de la carte : lecture des routes (collage, nom de rue), jamais sur le fil de l'écran
    private HandlerThread thread;
    private Handler bgH;
    private MapFile readFile;   // utilisé seulement sur ce fil
    private RoadSnap snap;      // utilisé seulement sur ce fil
    private final Object pendingLock = new Object();
    private double[] pending;   // dernière position en attente de collage (on ne garde que la plus récente)
    private boolean pendingMeasured, pendingBearingOk;

    // Mouvement affiché. Gardé d'une carte à l'autre : si l'écran de conduite est quitté puis rouvert
    // (par exemple après CarPlay), la voiture continue son mouvement au lieu de repartir de la position GPS.
    private static Glide shared;
    private Glide glide = new Glide();
    private boolean animating;
    private long lastFrameNs;

    // Carte posée (dessinée) : point exact de la grille des pixels, rotation et zoom
    private boolean anchored;
    private double ax, ay, aRot;
    private byte aZoom;

    private double lastLookLat, lastLookLon;
    private volatile boolean looking;
    private volatile String snapName = "";
    private volatile long snapNameAt;
    private byte followZoom;
    private Polyline routeLine;
    private FixedPixelCircle destDot;
    private int routeVersion = -1;

    // Mesures (mode test et tests automatiques)
    private int frames, slowFrames, redraws, fixes, snapped;
    private long statStart;
    private static volatile MiniMap live;     // carte affichée en ce moment
    private static volatile String lastStats = "";

    /** Position, cap et moment (ms) de ce qui est affiché (tests automatiques), ou null sans carte. */
    static double[] displayed() {
        MiniMap m = live;
        if (m == null || !m.glide.ready()) return null;
        return new double[]{m.glide.lat(), m.glide.lon(), m.glide.heading(), m.glide.time()};
    }

    /** Mesures de la carte affichée (ou de la dernière), pour le mode test. */
    static String statsNow() {
        MiniMap m = live;
        return m != null ? m.stats() : lastStats;
    }

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
    MapView create(Context c) { return create(c, VIEW_NORTH, Pal.night); }

    /** Crée la vue carte, ou renvoie null si la carte n'est pas téléchargée ou illisible. */
    MapView create(Context c, int view, boolean night) {
        this.view = view;
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
            cache = new InMemoryTileCache(rotating ? Math.max(minCache, 120) : minCache);
            mapFile = new MapFile(file(c), "fr");
            // Carte qui tourne : les noms de rues sont dessinés à part, pour rester toujours à l'endroit
            TileRendererLayer layer = new TileRendererLayer(cache, mapFile, mv.getModel().mapViewPosition,
                    false, !rotating, rotating, AndroidGraphicFactory.INSTANCE);
            layer.setXmlRenderTheme(new AssetsRenderTheme(c.getAssets(), "map/", night ? "theme-nuit.xml" : "theme.xml"));
            mv.getLayerManager().getLayers().add(layer);
            // itinéraire de la navigation (sous les noms de rue) et point d'arrivée
            routeLine = new Polyline(paint(night ? 0xF04C9BFF : 0xE82F7BF6, U.f(9), true), AndroidGraphicFactory.INSTANCE);
            mv.getLayerManager().getLayers().add(routeLine);
            syncRoute();
            if (rotating) {
                mv.getLayerManager().getLayers().add(new ThreadedLabelLayer(AndroidGraphicFactory.INSTANCE, layer.getLabelStore()));
                // La zone dessinée est un carré de la taille de la diagonale (elle couvre toutes les rotations) ;
                // une petite marge en plus pour le glissement fait par la carte graphique entre deux dessins.
                mv.getModel().frameBufferModel.setOverdrawFactor(1.06);
            } else {
                mv.getModel().frameBufferModel.setOverdrawFactor(1.15);
            }
            attached = true;
            mv.setZoomLevelMin((byte) 12);
            mv.setZoomLevelMax((byte) 18);
            mv.getModel().mapViewPosition.setZoomLevel((byte) 16);
            LocationTracker loc = App.I.loc;
            double lat = loc.lat != 0 ? loc.lat : P.lat(), lon = loc.lon != 0 ? loc.lon : P.lon();
            mv.getModel().mapViewPosition.setCenter(new LatLong(lat, lon));
            double nowMs = System.nanoTime() / 1e6;
            if (shared != null && shared.ready() && nowMs - shared.time() < 15000) {
                glide = shared; // mouvement en cours : on continue
            } else {
                glide = new Glide();
                shared = glide;
                // point de départ : la dernière position connue (la voiture s'y pose sans animation)
                glide.onFix(nowMs, lat, lon, 0, true, loc.bearing, true, null, null);
            }
            startThread();
            loc.addFix(fixListener);
            statStart = System.nanoTime();
            live = this;
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

    void setHeadingListener(HeadingListener l) { headingListener = l; }

    float heading() { return (float) glide.heading(); }

    // ---------- Positions GPS ----------

    private final LocationTracker.FixListener fixListener = new LocationTracker.FixListener() {
        @Override public void onFix(double monoMs, double lat, double lon, float mps, boolean measured, float bearing,
                                    boolean bearingOk, float accuracy) {
            if (mv == null || destroyed || (lat == 0 && lon == 0)) return;
            fixes++;
            if (!P.mapSnap() || bgH == null) {
                double[][] np = mps >= Glide.STOP_MPS ? App.I.nav.pathAhead(lat, lon, 250) : null;
                if (np != null) glide.onFix(monoMs, np[0][0], np[1][0], mps, measured, bearing, bearingOk, np[0], np[1]);
                else glide.onFix(monoMs, lat, lon, mps, measured, bearing, bearingOk, null, null);
                startAnimation();
                return;
            }
            // Collage sur la route, sur le fil de la carte ; si le fil est encore occupé avec la position
            // précédente, seule la plus récente sera traitée.
            boolean post;
            synchronized (pendingLock) {
                post = pending == null;
                pending = new double[]{monoMs, lat, lon, mps, bearing, accuracy};
                pendingMeasured = measured;
                pendingBearingOk = bearingOk;
            }
            if (post) bgH.post(snapJob);
        }
    };

    private final Runnable snapJob = new Runnable() {
        @Override public void run() {
            final double[] f;
            final boolean measured, bOk;
            synchronized (pendingLock) {
                f = pending;
                pending = null;
                measured = pendingMeasured;
                bOk = pendingBearingOk;
            }
            if (f == null || destroyed) return;
            RoadSnap.Result r = null;
            try {
                if (snap == null) {
                    readFile = new MapFile(file(App.I), "fr");
                    snap = new RoadSnap(readFile);
                }
                boolean moving = f[3] >= Glide.HEAD_MPS && bOk;
                r = snap.snap(f[1], f[2], f[4], moving, f[5]);
            } catch (Throwable ignored) { }
            final RoadSnap.Result res = r;
            if (res != null && res.name != null && !res.name.isEmpty()) {
                snapName = res.name;
                snapNameAt = android.os.SystemClock.uptimeMillis();
            }
            App.I.main.post(new Runnable() {
                @Override public void run() {
                    if (mv == null || destroyed) return;
                    // navigation en cours : la voiture suit l'itinéraire prévu (elle tourne là où il tourne)
                    // (d'après la position GPS elle-même : une autre route toute proche ne « vole » pas la voiture)
                    double[][] np = f[3] >= Glide.STOP_MPS ? App.I.nav.pathAhead(f[1], f[2], 250, bOk ? f[4] : Double.NaN) : null;
                    // nettement plus près d'une autre route que de l'itinéraire : la voiture a pris un autre
                    // chemin, on la montre tout de suite sur la route où elle est vraiment
                    if (np != null && res != null && res.dist + 10 < App.I.nav.aheadD) np = null;
                    if (np != null) {
                        snapped++;
                        glide.onFix(f[0], np[0][0], np[1][0], f[3], measured, f[4], bOk, np[0], np[1]);
                    } else if (res != null) {
                        snapped++;
                        glide.onFix(f[0], res.lat, res.lon, f[3], measured, f[4], bOk,
                                f[3] >= Glide.STOP_MPS ? res.pathLat : null, f[3] >= Glide.STOP_MPS ? res.pathLon : null);
                    } else {
                        glide.onFix(f[0], f[1], f[2], f[3], measured, f[4], bOk, null, null);
                    }
                    startAnimation();
                }
            });
        }
    };

    private void startThread() {
        thread = new HandlerThread("carte", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        bgH = new Handler(thread.getLooper());
    }

    /** Prépare le moteur de dessin de Mapsforge (une seule fois). */
    static void ensureGraphics() {
        if (!graphicsReady) {
            AndroidGraphicFactory.createInstance(App.I);
            Parameters.NUMBER_OF_THREADS = 2;
            graphicsReady = true;
        }
    }

    static org.mapsforge.core.graphics.Paint paint(int color, float width, boolean stroke) {
        org.mapsforge.core.graphics.Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(color);
        p.setStyle(stroke ? org.mapsforge.core.graphics.Style.STROKE : org.mapsforge.core.graphics.Style.FILL);
        p.setStrokeWidth(width);
        p.setStrokeCap(org.mapsforge.core.graphics.Cap.ROUND);
        p.setStrokeJoin(org.mapsforge.core.graphics.Join.ROUND);
        return p;
    }

    /** Dessine (ou enlève) l'itinéraire de la navigation en cours, s'il a changé. */
    void syncRoute() {
        if (mv == null || routeLine == null) return;
        Nav nav = App.I.nav;
        if (nav.version == routeVersion) return;
        routeVersion = nav.version;
        Router.Route r = nav.active ? nav.route : null;
        routeLine.clear();
        if (destDot != null) {
            mv.getLayerManager().getLayers().remove(destDot);
            destDot = null;
        }
        if (r != null) {
            java.util.ArrayList<LatLong> pts = new java.util.ArrayList<>(r.lat.length);
            for (int i = 0; i < r.lat.length; i++) pts.add(new LatLong(r.lat[i], r.lon[i]));
            routeLine.addPoints(pts);
            destDot = new FixedPixelCircle(new LatLong(nav.dest.lat, nav.dest.lon), U.f(9),
                    paint(0xFFE5484D, 0, false), paint(0xFFFFFFFF, U.f(3.5f), true));
            int at = mv.getLayerManager().getLayers().indexOf(routeLine) + 1;
            mv.getLayerManager().getLayers().add(at, destDot);
        }
        routeLine.requestRedraw();
    }

    /** Appelé chaque seconde par l'écran de conduite : zoom selon la vitesse, nom de la rue, et relance du mouvement. */
    void follow(double lat, double lon, float kmh) {
        if (mv == null) return;
        syncRoute();
        if (lat == 0 && lon == 0) return;
        // Zoom selon la vitesse, avec une marge pour ne pas changer sans arrêt autour de 50 ou 90 km/h
        byte z = followZoom == 0 ? (kmh > 90 ? (byte) 14 : kmh > 55 ? (byte) 15 : (byte) 16) : followZoom;
        if (z == 16 && kmh > 62) z = 15;
        else if (z == 15 && kmh < 45) z = 16;
        else if (z == 15 && kmh > 97) z = 14;
        else if (z == 14 && kmh < 82) z = 15;
        if (z != followZoom) {
            followZoom = z;
            try {
                if (mv.getModel().mapViewPosition.getZoomLevel() != z) mv.getModel().mapViewPosition.setZoomLevel(z, true);
            } catch (Throwable ignored) { }
        }
        // Nom de la rue : celui de la route où la voiture est collée, sinon la rue la plus proche
        if (android.os.SystemClock.uptimeMillis() - snapNameAt < 3000 && !snapName.isEmpty()) {
            App.I.loc.street = snapName;
        } else {
            lookupStreet(lat, lon);
        }
        startAnimation();
    }

    // ---------- Animation, au rythme de l'écran ----------

    private void startAnimation() {
        if (animating || mv == null || destroyed) return;
        animating = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (mv == null || destroyed) { animating = false; return; }
            if (lastFrameNs != 0) {
                frames++;
                if (frameTimeNanos - lastFrameNs > 25_000_000L) slowFrames++;
            }
            lastFrameNs = frameTimeNanos;
            boolean busy = glide.step(frameTimeNanos / 1e6);
            boolean placed = render();
            if (busy || !placed || mv.getModel().mapViewPosition.animationInProgress()) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                animating = false; // voiture arrêtée et carte immobile : plus rien ne tourne
                lastFrameNs = 0;
            }
        }
    };

    /** Place la carte : la voiture au centre de la vue, la route devant elle en haut. Faux si pas encore possible. */
    private boolean render() {
        if (!glide.ready()) return true;
        float w = mv.getWidth(), h = mv.getHeight();
        if (w <= 0 || h <= 0) return false; // vue pas encore mesurée : on réessaie à l'image suivante
        try {
            MapViewPosition pos = mv.getModel().mapViewPosition;
            double lat = glide.lat(), lon = glide.lon();
            double head = view == VIEW_NORTH ? 0 : glide.heading();
            if (pos.animationInProgress()) {
                // changement de zoom en cours : Mapsforge l'anime ; on suit la voiture comme avant
                anchored = false;
                clearShift();
                pos.setCenter(new LatLong(lat, lon));
                if (view != VIEW_NORTH) pos.setRotation(new Rotation((float) -head, w * 0.5f, h * 0.5f));
            } else {
                byte z = pos.getZoomLevel();
                long size = MercatorProjection.getMapSize(z, mv.getModel().displayModel.getTileSize());
                double px = MercatorProjection.longitudeToPixelX(lon, size), py = MercatorProjection.latitudeToPixelY(lat, size);
                if (!anchored || z != aZoom) anchor(pos, px, py, head, z, size, w, h);
                double[] d = shift(px, py, head);
                if (Math.hypot(d[0], d[1]) > MAX_SHIFT_PX || Math.abs(d[2]) > MAX_TURN) {
                    anchor(pos, px, py, head, z, size, w, h);
                    d = shift(px, py, head);
                }
                // Le reste du mouvement (moins de quelques pixels et degrés) est fait par la carte graphique :
                // la vue tourne autour du point de la voiture et glisse pour le ramener au centre.
                float cx = w * 0.5f, cy = h * 0.5f;
                mv.setPivotX(cx + (float) d[0]);
                mv.setPivotY(cy + (float) d[1]);
                mv.setTranslationX((float) -d[0]);
                mv.setTranslationY((float) -d[1]);
                mv.setRotation((float) -d[2]);
            }
        } catch (Throwable ignored) { }
        if (headingListener != null) headingListener.onHeading((float) glide.heading());
        return true;
    }

    /** Redessine la carte centrée sur un point exact de la grille des pixels (pas de tremblement d'un demi-pixel). */
    private void anchor(MapViewPosition pos, double px, double py, double head, byte z, long size, float w, float h) {
        ax = Math.round(px);
        ay = Math.round(py);
        aRot = view == VIEW_NORTH ? 0 : head;
        aZoom = z;
        LatLong ll = new LatLong(MercatorProjection.pixelYToLatitude(ay, size), MercatorProjection.pixelXToLongitude(ax, size));
        if (view == VIEW_NORTH) pos.setMapPosition(new MapPosition(ll, z), false);
        else pos.setMapPosition(new MapPosition(ll, z, new Rotation((float) -aRot, w * 0.5f, h * 0.5f)), false);
        anchored = true;
        redraws++;
    }

    /** Écart entre la carte dessinée et la position affichée : glissement x, y (pixels de la vue) et rotation (°). */
    private double[] shift(double px, double py, double head) {
        double dx = px - ax, dy = py - ay;
        double r = Math.toRadians(-aRot); // la carte dessinée est tournée de -aRot (sens horaire positif)
        double c = Math.cos(r), s = Math.sin(r);
        return new double[]{dx * c - dy * s, dx * s + dy * c, Glide.wrap(head - aRot)};
    }

    private void clearShift() {
        if (mv == null) return;
        mv.setPivotX(mv.getWidth() * 0.5f);
        mv.setPivotY(mv.getHeight() * 0.5f);
        mv.setTranslationX(0);
        mv.setTranslationY(0);
        mv.setRotation(0);
    }

    /** Mesures depuis l'ouverture de la carte (mode test). */
    String stats() {
        double s = Math.max(0.001, (System.nanoTime() - statStart) / 1e9);
        return String.format(Locale.ROOT, "images %d (%.0f/s), lentes %d, redessins %d (%.1f/s), positions %d, collées %d",
                frames, frames / s, slowFrames, redraws, redraws / s, fixes, snapped);
    }

    // ---------- Nom de la rue (si la voiture n'est pas collée à une route nommée) ----------

    /** Nom de la rue la plus proche, lu dans la carte (sur le fil de la carte, au plus tous les 25 m). */
    private void lookupStreet(final double lat, final double lon) {
        if (looking || bgH == null) return;
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
        bgH.post(new Runnable() {
            @Override public void run() {
                String best = "";
                if (destroyed) { looking = false; return; } // mode conduite déjà quitté : on n'ouvre plus la carte
                try {
                    if (readFile == null) {
                        readFile = new MapFile(file(App.I), "fr");
                        snap = new RoadSnap(readFile);
                    }
                    byte zoom = 16;
                    int tx = MercatorProjection.longitudeToTileX(lon, zoom), ty = MercatorProjection.latitudeToTileY(lat, zoom);
                    Tile a = new Tile(Math.max(0, tx - 1), Math.max(0, ty - 1), zoom, 256);
                    Tile b = new Tile(tx + 1, ty + 1, zoom, 256);
                    MapReadResult r = readFile.readNamedItems(a, b);
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
                                if (BuildConfig.TEST_HOOKS) debug.append(String.format(Locale.ROOT, " | %s %.0fm note %.0f", name, dist, sc));
                                if (sc < bestS) { bestS = sc; best = name; }
                            }
                        }
                    }
                    if (BuildConfig.TEST_HOOKS) {
                        android.util.Log.i("Test208", String.format(Locale.ROOT, "rue %.5f,%.5f sens %.2f,%.2f -> %s%s",
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
        destroyed = true;
        if (live == this) {
            lastStats = stats();
            live = null;
        }
        try { App.I.loc.removeFix(fixListener); } catch (Throwable ignored) { }
        try { Choreographer.getInstance().removeFrameCallback(frame); } catch (Throwable ignored) { }
        animating = false;
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
        // la carte de lecture est fermée sur son propre fil, donc après la dernière lecture (jamais pendant)
        final HandlerThread t = thread;
        final Handler hd = bgH;
        thread = null;
        bgH = null;
        if (hd != null) {
            hd.post(new Runnable() {
                @Override public void run() {
                    MapFile rf = readFile;
                    readFile = null;
                    snap = null;
                    if (rf != null) try { rf.close(); } catch (Throwable ignored) { }
                    if (t != null) t.quitSafely();
                }
            });
        }
        try { if (graphicsReady) AndroidGraphicFactory.clearResourceMemoryCache(); } catch (Throwable ignored) { }
    }
}

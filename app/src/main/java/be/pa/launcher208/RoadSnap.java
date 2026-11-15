package be.pa.launcher208;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.Way;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Colle la position GPS sur la route la plus probable, lue dans la carte de Belgique, et donne la route
 * qui se trouve devant la voiture (pour que la voiture affichée suive les virages entre deux positions).
 *
 * Le GPS se trompe souvent de quelques mètres sur le côté : sur la carte, la voiture roulerait à côté de
 * la route. Ici, on choisit la route la plus proche qui va dans le même sens que la voiture, en préférant
 * celle où l'on était déjà (à un carrefour, la rue qui croise ne « vole » pas la voiture). Si aucune route
 * ne convient (parking, chemin absent de la carte), la position GPS est gardée telle quelle.
 *
 * Utilisé par un seul fil à la fois (le fil de la carte). Aucun lien avec Android.
 */
final class RoadSnap {
    static final byte ZOOM = 16;
    /** Distance maximale (m) entre la position GPS et la route. */
    static final double MAX_M = 25, MIN_M = 12;
    /** Écart maximal (degrés) entre le sens de la route et celui de la voiture. */
    static final double MAX_ANGLE = 40;
    /** Longueur de route donnée devant la voiture (m). */
    static final double AHEAD_M = 250;

    /** Une route de la carte. */
    static final class Road {
        final double[] lat, lon;
        final String name;
        final double penalty; // petites routes de service : un peu moins probables
        final String key;

        Road(double[] lat, double[] lon, String name, double penalty) {
            this.lat = lat;
            this.lon = lon;
            this.name = name;
            this.penalty = penalty;
            int n = lat.length;
            key = n + ":" + lat[0] + "," + lon[0] + ":" + lat[n - 1] + "," + lon[n - 1];
        }
    }

    /** Résultat : position collée à la route, route devant la voiture, nom de la rue. */
    static final class Result {
        double lat, lon, dist;
        int seg;              // segment de la route où se trouve le point
        double[] pathLat, pathLon;
        String name;
        Road road;
        int dir;
    }

    private final MapDataStore store;
    private final LinkedHashMap<Long, List<Road>> cache = new LinkedHashMap<Long, List<Road>>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<Road>> e) { return size() > 16; }
    };
    private Road lastRoad;
    private int lastDir = 1;
    int reads; // tuiles lues (pour le mode test)

    RoadSnap(MapDataStore store) { this.store = store; }

    // ---------- Lecture des routes dans la carte ----------

    static boolean drivable(String v) {
        if (v == null) return false;
        switch (v) {
            case "motorway": case "motorway_link": case "trunk": case "trunk_link":
            case "primary": case "primary_link": case "secondary": case "secondary_link":
            case "tertiary": case "tertiary_link": case "unclassified": case "residential":
            case "living_street": case "service": case "road":
                return true;
            default:
                return false;
        }
    }

    private List<Road> tile(int tx, int ty) {
        long k = ((long) tx << 32) | (ty & 0xffffffffL);
        List<Road> l = cache.get(k);
        if (l != null) return l;
        l = new ArrayList<>();
        try {
            MapReadResult r = store.readMapData(new Tile(tx, ty, ZOOM, 256));
            reads++;
            if (r != null && r.ways != null) {
                for (Way w : r.ways) {
                    String hw = null, name = null;
                    for (Tag t : w.tags) {
                        if ("highway".equals(t.key)) hw = t.value;
                        else if ("name".equals(t.key)) name = t.value;
                    }
                    if (!drivable(hw) || w.latLongs == null || w.latLongs.length == 0) continue;
                    LatLong[] p = w.latLongs[0];
                    if (p == null || p.length < 2) continue;
                    double[] la = new double[p.length], lo = new double[p.length];
                    for (int i = 0; i < p.length; i++) { la[i] = p[i].latitude; lo[i] = p[i].longitude; }
                    double pen = "service".equals(hw) ? 4 : "living_street".equals(hw) ? 1 : 0;
                    l.add(new Road(la, lo, name, pen));
                }
            }
        } catch (Throwable ignored) {
        }
        cache.put(k, l);
        return l;
    }

    /** Routes autour d'un point (dans un rayon d'environ r mètres), sans doublons. */
    List<Road> around(double lat, double lon, double r) {
        double dLat = r / 110540.0, dLon = r / (111320.0 * Math.cos(Math.toRadians(lat)));
        int x0 = MercatorProjection.longitudeToTileX(lon - dLon, ZOOM), x1 = MercatorProjection.longitudeToTileX(lon + dLon, ZOOM);
        int y0 = MercatorProjection.latitudeToTileY(lat + dLat, ZOOM), y1 = MercatorProjection.latitudeToTileY(lat - dLat, ZOOM);
        ArrayList<Road> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int tx = x0; tx <= x1; tx++)
            for (int ty = y0; ty <= y1; ty++)
                for (Road rd : tile(tx, ty)) if (seen.add(rd.key)) out.add(rd);
        return out;
    }

    /**
     * Colle une position GPS sur la route. Renvoie null si aucune route ne convient.
     * @param acc précision annoncée par le GPS (m), ou une valeur négative si inconnue
     */
    Result snap(double lat, double lon, double bearing, boolean moving, double acc) {
        double r = acc > 0 ? Math.max(MIN_M, Math.min(MAX_M, acc * 1.5)) : 20;
        List<Road> roads = around(lat, lon, r + 5);
        Result res = snap(roads, lat, lon, bearing, moving, r, lastRoad, lastDir);
        if (res == null) { lastRoad = null; return null; }
        lastRoad = res.road;
        lastDir = res.dir;
        path(res, roads);
        return res;
    }

    /** Choix de la route (logique pure, testée sur ordinateur). */
    static Result snap(List<Road> roads, double lat, double lon, double bearing, boolean moving, double maxM,
                       Road prev, int prevDir) {
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        double bestCost = Double.MAX_VALUE;
        Result best = null;
        for (Road rd : roads) {
            int n = rd.lat.length;
            for (int i = 0; i + 1 < n; i++) {
                double ax = (rd.lon[i] - lon) * kx, ay = (rd.lat[i] - lat) * ky;
                double bx = (rd.lon[i + 1] - lon) * kx, by = (rd.lat[i + 1] - lat) * ky;
                double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
                if (l2 < 1e-6) continue;
                double t = Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
                double qx = ax + t * sx, qy = ay + t * sy;
                double d = Math.sqrt(qx * qx + qy * qy);
                if (d > maxM) continue;
                double hs = Math.toDegrees(Math.atan2(sx, sy));
                int dir;
                double ang;
                if (moving) {
                    double fw = Math.abs(Glide.wrap(hs - bearing)), bw = Math.abs(Glide.wrap(hs + 180 - bearing));
                    dir = fw <= bw ? 1 : -1;
                    ang = Math.min(fw, bw);
                    if (ang > MAX_ANGLE) continue;
                } else {
                    dir = rd == prev ? prevDir : 1;
                    ang = 0;
                }
                double cost = d + 0.15 * ang + rd.penalty - (rd == prev ? 4 : 0);
                if (cost < bestCost) {
                    bestCost = cost;
                    if (best == null) best = new Result();
                    best.road = rd;
                    best.dir = dir;
                    best.dist = d;
                    best.lat = rd.lat[i] + t * (rd.lat[i + 1] - rd.lat[i]);
                    best.lon = rd.lon[i] + t * (rd.lon[i + 1] - rd.lon[i]);
                    best.name = rd.name;
                    best.seg = i;
                }
            }
        }
        return best;
    }

    /** Route devant la voiture, depuis le point collé, en continuant tout droit aux carrefours. */
    static void path(Result res, List<Road> roads) {
        int seg = res.seg;
        ArrayList<double[]> pts = new ArrayList<>();
        pts.add(new double[]{res.lat, res.lon});
        double kx = 111320.0 * Math.cos(Math.toRadians(res.lat)), ky = 110540.0;
        double len = 0;
        Road rd = res.road;
        int dir = res.dir;
        int k = dir > 0 ? seg + 1 : seg; // prochain sommet dans le sens de la marche
        HashSet<Road> used = new HashSet<>();
        used.add(rd);
        for (int hop = 0; hop < 4 && len < AHEAD_M; hop++) {
            int n = rd.lat.length;
            while (k >= 0 && k < n && len < AHEAD_M) {
                double[] last = pts.get(pts.size() - 1);
                len += Math.hypot((rd.lon[k] - last[1]) * kx, (rd.lat[k] - last[0]) * ky);
                pts.add(new double[]{rd.lat[k], rd.lon[k]});
                k += dir;
            }
            if (len >= AHEAD_M || pts.size() < 2) break;
            // fin de cette route : on continue sur la route la plus dans le prolongement (moins de 50°)
            double[] end = pts.get(pts.size() - 1), before = pts.get(pts.size() - 2);
            double inH = Math.toDegrees(Math.atan2((end[1] - before[1]) * kx, (end[0] - before[0]) * ky));
            Road nr = null;
            int nk = 0, nd = 0;
            double bestA = 50;
            for (Road o : roads) {
                if (used.contains(o)) continue;
                for (int j = 0; j < o.lat.length; j++) {
                    if (Math.hypot((o.lon[j] - end[1]) * kx, (o.lat[j] - end[0]) * ky) > 1.5) continue;
                    for (int dd = -1; dd <= 1; dd += 2) {
                        int j2 = j + dd;
                        if (j2 < 0 || j2 >= o.lat.length) continue;
                        double h = Math.toDegrees(Math.atan2((o.lon[j2] - o.lon[j]) * kx, (o.lat[j2] - o.lat[j]) * ky));
                        double a = Math.abs(Glide.wrap(h - inH));
                        if (a < bestA) { bestA = a; nr = o; nk = j2; nd = dd; }
                    }
                }
            }
            if (nr == null) break;
            rd = nr;
            k = nk;
            dir = nd;
            used.add(rd);
        }
        res.pathLat = new double[pts.size()];
        res.pathLon = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) { res.pathLat[i] = pts.get(i)[0]; res.pathLon[i] = pts.get(i)[1]; }
    }

    /** Oublie la route précédente (par exemple après un long arrêt du GPS). */
    void reset() { lastRoad = null; }

    int cached() { return cache.size(); }

    void clear() {
        cache.clear();
        lastRoad = null;
    }
}

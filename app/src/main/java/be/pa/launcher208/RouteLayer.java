package be.pa.launcher208;

import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Path;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.layer.Layer;

/**
 * Ligne bleue de l'itinéraire, qui s'efface derrière la voiture : elle commence toujours à la voiture affichée.
 *
 * Le début n'avance que vers l'avant, jusqu'au point de l'itinéraire le plus proche de la voiture (30 m au plus,
 * dans le même sens qu'elle) : un aller-retour sur la même route ou un GPS qui se décale ne le font pas reculer.
 * Si la voiture quitte l'itinéraire, la ligne reste comme elle est jusqu'au nouveau calcul.
 *
 * La carte est dessinée par un autre fil que l'écran : l'état est un objet qu'on remplace d'un coup (jamais
 * modifié), pour qu'un dessin ne voie jamais une ligne à moitié changée.
 */
final class RouteLayer extends Layer {
    private static final class State {
        final double[] lat, lon, cum;
        final int cutI;       // la ligne commence sur le segment cutI…
        final double cutT;    // … à cette fraction du segment
        State(double[] lat, double[] lon, double[] cum, int cutI, double cutT) {
            this.lat = lat; this.lon = lon; this.cum = cum; this.cutI = cutI; this.cutT = cutT;
        }
        double along() { return cum[cutI] + cutT * (cum[cutI + 1] - cum[cutI]); }
    }

    private final GraphicFactory gf;
    private final Paint paint;
    private volatile State state;

    RouteLayer(Paint paint, GraphicFactory gf) {
        this.paint = paint;
        this.gf = gf;
    }

    /** Nouvel itinéraire (ou aucun si r est null) : ligne entière, depuis le départ. */
    void set(Router.Route r) {
        state = r == null || r.lat.length < 2 ? null : new State(r.lat, r.lon, r.cum, 0, 0);
        requestRedraw();
    }

    /** Distance (m) depuis le départ de l'itinéraire où commence la ligne, -1 sans itinéraire. */
    double cutAlong() {
        State s = state;
        return s == null ? -1 : s.along();
    }

    /**
     * Avance le début de la ligne jusqu'à la voiture affichée.
     * @param head cap de la voiture (°), NaN s'il n'est pas connu
     * @param hint segment de l'itinéraire où la navigation voit la voiture (pour ne pas chercher partout)
     * @return vrai si la ligne a changé
     */
    boolean cut(double lat, double lon, double head, int hint) {
        State s = state;
        if (s == null) return false;
        int n = s.lat.length;
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        // depuis le début actuel (ou un peu avant la position de la navigation, si la carte était cachée)
        int from = Math.min(n - 2, Math.max(s.cutI, hint - 20));
        double lim = s.cum[from] + 700;
        int bestI = -1;
        double bestD = 30, bestT = 0;
        for (int i = from; i + 1 < n && s.cum[i] <= lim; i++) {
            double ax = (s.lon[i] - lon) * kx, ay = (s.lat[i] - lat) * ky;
            double bx = (s.lon[i + 1] - lon) * kx, by = (s.lat[i + 1] - lat) * ky;
            double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy;
            if (l2 < 1e-6) continue;
            // même sens que la voiture (sinon c'est l'autre côté d'un aller-retour)
            if (!Double.isNaN(head) && l2 > 4) {
                double segB = Math.toDegrees(Math.atan2(sx, sy));
                if (Math.abs(Glide.wrap(head - segB)) > 70) continue;
            }
            double t = Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
            double qx = ax + t * sx, qy = ay + t * sy, d = Math.sqrt(qx * qx + qy * qy);
            if (d < bestD - 0.5 || (d < bestD && bestI < 0)) { bestD = d; bestI = i; bestT = t; }
        }
        if (bestI < 0) return false;
        if (bestI < s.cutI || (bestI == s.cutI && bestT <= s.cutT)) return false; // jamais en arrière
        state = new State(s.lat, s.lon, s.cum, bestI, bestT);
        return true;
    }

    @Override
    public void draw(BoundingBox bb, byte zoomLevel, Canvas canvas, Point topLeft, Rotation rotation) {
        State s = state;
        if (s == null || paint == null) return;
        long mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.getTileSize());
        int i0 = s.cutI;
        double sLat = s.lat[i0] + s.cutT * (s.lat[i0 + 1] - s.lat[i0]);
        double sLon = s.lon[i0] + s.cutT * (s.lon[i0 + 1] - s.lon[i0]);
        float x = (float) (MercatorProjection.longitudeToPixelX(sLon, mapSize) - topLeft.x);
        float y = (float) (MercatorProjection.latitudeToPixelY(sLat, mapSize) - topLeft.y);
        Path path = gf.createPath();
        path.moveTo(x, y);
        float px = x, py = y;
        int n = s.lat.length;
        for (int i = i0 + 1; i < n; i++) {
            x = (float) (MercatorProjection.longitudeToPixelX(s.lon[i], mapSize) - topLeft.x);
            y = (float) (MercatorProjection.latitudeToPixelY(s.lat[i], mapSize) - topLeft.y);
            // points à moins d'un demi-pixel du précédent : invisibles, on les saute (sauf le dernier)
            if (i < n - 1 && Math.abs(x - px) < 0.5f && Math.abs(y - py) < 0.5f) continue;
            path.lineTo(x, y);
            px = x;
            py = y;
        }
        canvas.drawPath(path, paint);
    }
}

package be.pa.launcher208;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Calcul d'itinéraire en voiture, sur l'autoradio, sans Internet.
 *
 * Le trajet le plus rapide est cherché tronçon par tronçon (algorithme A*), en respectant les sens uniques,
 * les interdictions de tourner et les bornes. On ne fait jamais demi-tour au milieu d'un carrefour.
 * Loin du départ et de l'arrivée, seules les grandes routes sont essayées (bien plus rapide à calculer,
 * comme le font les GPS) ; si cela ne donne rien, tout le réseau est essayé.
 *
 * Aucun lien avec Android : vérifié par les tests sur ordinateur.
 */
final class Router {
    /** Vitesse maximale envisagée (m/s), pour l'estimation de la durée restante (doit rester optimiste). */
    static final double VMAX = 36;
    /** Distance (m) au départ et à l'arrivée au-delà de laquelle seules les grandes routes sont essayées. */
    static final double LOCAL_M = 12000;
    /** Rang maximal des grandes routes (0 autoroute ... 4 route tertiaire). */
    static final int BIG_RANK = 4;
    /** Demi-tour au départ (si la voiture roule dans l'autre sens), en secondes. */
    static final double UTURN_S = 40;

    final NavGraph g;
    private float[] dist;
    private int[] parent, stamp;
    private int cur;
    // tas binaire (état, priorité)
    private int[] hs = new int[1024];
    private float[] hk = new float[1024];
    private int hn;
    int lastExplored;

    Router(NavGraph g) { this.g = g; }

    /** Point d'une route le plus proche d'une position. */
    static final class Snap {
        int edge;
        double along;   // distance (m) depuis le début du tronçon (u)
        double lat, lon, dist;
        int seg;        // segment de la forme du tronçon
    }

    /**
     * Route la plus proche (à moins de maxM mètres), ou null. Si heading est un nombre, on préfère une route
     * dans ce sens (voiture qui roule).
     */
    Snap snap(double lat, double lon, double maxM, double heading) {
        Snap s = snap(lat, lon, maxM, heading, false);
        return s != null ? s : snap(lat, lon, maxM, heading, true);
    }

    private Snap snap(double lat, double lon, double maxM, double heading, boolean withIsolated) {
        double kx = 111320.0 * Math.cos(Math.toRadians(lat)), ky = 110540.0;
        int ring = Math.max(1, (int) Math.ceil(maxM / (g.step / 1e6 * kx)));
        int[] cand = g.near(lat, lon, ring);
        Snap best = null;
        double bestCost = Double.MAX_VALUE;
        for (int e : cand) {
            if (!withIsolated && g.isolated(e)) continue;
            int[] p = g.points(e);
            double along = 0;
            for (int i = 0; i + 3 < p.length; i += 2) {
                double ax = (p[i + 1] / 1e6 - lon) * kx, ay = (p[i] / 1e6 - lat) * ky;
                double bx = (p[i + 3] / 1e6 - lon) * kx, by = (p[i + 2] / 1e6 - lat) * ky;
                double sx = bx - ax, sy = by - ay, l2 = sx * sx + sy * sy, sl = Math.sqrt(l2);
                double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * sx + ay * sy) / l2));
                double qx = ax + t * sx, qy = ay + t * sy, d = Math.sqrt(qx * qx + qy * qy);
                if (d <= maxM) {
                    double cost = d;
                    if (!Double.isNaN(heading) && sl > 0.5) {
                        double h = Math.toDegrees(Math.atan2(sx, sy));
                        double fa = g.fwd(e) ? Math.abs(Glide.wrap(h - heading)) : 999;
                        double ba = g.bwd(e) ? Math.abs(Glide.wrap(h + 180 - heading)) : 999;
                        double a = Math.min(fa, ba);
                        cost += a > 60 ? 30 : a * 0.15;
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        if (best == null) best = new Snap();
                        best.edge = e;
                        best.seg = i / 2;
                        best.along = along + t * sl;
                        best.lat = lat + qy / ky;
                        best.lon = lon + qx / kx;
                        best.dist = d;
                    }
                }
                along += sl;
            }
        }
        if (best != null) {
            // la longueur calculée ici peut différer un peu de celle du fichier : on la ramène à l'échelle
            double geo = geoLen(best.edge, kx, ky);
            if (geo > 0) best.along = best.along * g.len(best.edge) / geo;
        }
        return best;
    }

    private double geoLen(int e, double kx, double ky) {
        int[] p = g.points(e);
        double s = 0;
        for (int i = 0; i + 3 < p.length; i += 2) {
            s += Math.hypot((p[i + 3] - p[i + 1]) / 1e6 * kx, (p[i + 2] - p[i]) / 1e6 * ky);
        }
        return s;
    }

    /** Un itinéraire : la ligne à dessiner et les tronçons parcourus. */
    static final class Route {
        double[] lat, lon;   // ligne de l'itinéraire, du départ à l'arrivée
        double[] cum;        // distance depuis le départ à chaque point (m)
        double[] cumT;       // durée prévue depuis le départ à chaque point (s)
        double meters, seconds;
        int[] edges;         // tronçons parcourus (2e = de u vers v, 2e+1 = de v vers u)
        int[] edgeFirstPoint;// indice du premier point de chaque tronçon dans lat/lon
        double[] edgeSeconds;// durée de chaque tronçon parcouru
        java.util.List<Guide.Step> steps; // instructions de guidage
        int explored;
    }

    /** Trajet le plus rapide, ou null si aucun trajet n'existe. */
    Route route(Snap from, Snap to, double heading) {
        Route r = search(from, to, heading, true);
        if (r == null) r = search(from, to, heading, false);
        return r;
    }

    private double hkx;
    private double tLat, tLon, sLat, sLon;

    private Route search(Snap from, Snap to, double heading, boolean hierarchy) {
        int ns = g.nEdges * 2;
        if (dist == null || dist.length < ns) {
            dist = new float[ns];
            parent = new int[ns];
            stamp = new int[ns];
            cur = 0;
        }
        cur++;
        if (cur == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); cur = 1; }
        hn = 0;
        tLat = to.lat;
        tLon = to.lon;
        sLat = from.lat;
        sLon = from.lon;
        hkx = 111320.0 * Math.cos(Math.toRadians(to.lat));
        int se = from.edge, te = to.edge;
        double sl = g.len(se), tl = g.len(te);
        double bestGoal = Double.MAX_VALUE;
        int goalState = -2; // état d'où l'on entre dans le tronçon d'arrivée ; -1 = départ et arrivée sur le même tronçon
        int goalDir = 0;
        // Départ et arrivée sur le même tronçon, dans le bon ordre
        if (se == te) {
            if (g.fwd(se) && to.along >= from.along) {
                double c = (to.along - from.along) / g.mps(se) + startPenalty(se, 0, heading, from);
                if (c < bestGoal) { bestGoal = c; goalState = -1; goalDir = 0; }
            }
            if (g.bwd(se) && to.along <= from.along) {
                double c = (from.along - to.along) / g.mps(se) + startPenalty(se, 1, heading, from);
                if (c < bestGoal) { bestGoal = c; goalState = -1; goalDir = 1; }
            }
        }
        // Départ : on finit le tronçon de départ dans un sens ou dans l'autre
        if (g.fwd(se)) push(2 * se, (sl - from.along) / g.mps(se) + startPenalty(se, 0, heading, from), -1);
        if (g.bwd(se)) push(2 * se + 1, from.along / g.mps(se) + startPenalty(se, 1, heading, from), -1);
        int explored = 0;
        while (hn > 0) {
            float key = hk[0];
            int s = pop();
            if (key >= bestGoal) break;
            float gs = dist[s];
            int e = s >> 1, dir = s & 1;
            int n = dir == 0 ? g.ev.get(e) : g.eu.get(e);
            double hcur = h(n);
            if (key > gs + hcur + 1e-3) continue; // entrée périmée du tas (le tronçon a été atteint plus vite depuis)
            explored++;
            if (g.blocked(n)) continue; // borne : on ne passe pas
            boolean far = hierarchy && hcur * VMAX > LOCAL_M && distFromStart(n) > LOCAL_M;
            int a = g.firstOut.get(n), b = g.firstOut.get(n + 1), deg = g.deg(n);
            for (int k = a; k < b; k++) {
                int o = g.out.get(k), e2 = o >> 1;
                if (e2 == e && deg > 1) continue;         // pas de demi-tour au carrefour
                if (far && g.rank(e2) > BIG_RANK) continue;
                if (g.nBans > 0 && g.banned(n, e, e2)) continue;
                if (e2 == te) {
                    // entrée dans le tronçon d'arrivée : on s'arrête au point d'arrivée
                    boolean forward = (o & 1) == 0;
                    double part = forward ? to.along : tl - to.along;
                    double c = gs + part / g.mps(te) + (deg > 2 ? turnCost(s, o) : 0);
                    if (c < bestGoal) { bestGoal = c; goalState = s; goalDir = o & 1; }
                }
                double c = gs + g.len(e2) / g.mps(e2) + (deg > 2 ? turnCost(s, o) : 0);
                if (stamp[o] != cur || c < dist[o]) {
                    stamp[o] = cur;
                    dist[o] = (float) c;
                    parent[o] = s;
                    int n2 = (o & 1) == 0 ? g.ev.get(e2) : g.eu.get(e2);
                    pushKey(o, (float) (c + h(n2)));
                }
            }
        }
        lastExplored = explored;
        if (goalState == -2) return null;
        // Tronçons parcourus, du départ à l'arrivée
        ArrayList<Integer> seq = new ArrayList<>();
        seq.add(2 * te + goalDir);
        if (goalState != -1) {
            int s = goalState;
            while (s >= 0) {
                seq.add(s);
                s = stamp[s] == cur ? parent[s] : -1;
            }
        }
        int[] edges = new int[seq.size()];
        for (int i = 0; i < edges.length; i++) edges[i] = seq.get(edges.length - 1 - i);
        Route r = build(edges, from, to);
        r.seconds = r.cumT[r.cumT.length - 1];
        r.explored = explored;
        return r;
    }

    /**
     * Temps perdu à un carrefour selon le virage : rien tout droit, un peu à droite, plus à gauche (il faut
     * laisser passer), beaucoup pour un demi-tour. Évite les trajets en zigzag par les petites rues.
     */
    private double turnCost(int in, int out) {
        double a = Glide.wrap(startBearing(out) - endBearing(in)); // + = à droite
        double x = Math.abs(a);
        if (x < 30) return 0;
        if (x < 60) return 1;
        if (x < 140) return a > 0 ? 3 : 7;
        return 25;
    }

    /** Direction en arrivant au bout du tronçon parcouru (dernier segment). */
    private double endBearing(int s) {
        int e = s >> 1, a = g.geoStart.get(e), b = g.geoStart.get(e + 1);
        int pLat, pLon, qLat, qLon;
        if ((s & 1) == 0) { // u -> v : du dernier point de forme (ou u) vers v
            int v = g.ev.get(e);
            qLat = g.nLat.get(v); qLon = g.nLon.get(v);
            if (b > a) { pLat = g.gLat.get(b - 1); pLon = g.gLon.get(b - 1); }
            else { int u = g.eu.get(e); pLat = g.nLat.get(u); pLon = g.nLon.get(u); }
        } else {            // v -> u : du premier point de forme (ou v) vers u
            int u = g.eu.get(e);
            qLat = g.nLat.get(u); qLon = g.nLon.get(u);
            if (b > a) { pLat = g.gLat.get(a); pLon = g.gLon.get(a); }
            else { int v = g.ev.get(e); pLat = g.nLat.get(v); pLon = g.nLon.get(v); }
        }
        return Math.toDegrees(Math.atan2((qLon - pLon) * 0.637, (qLat - pLat)));
    }

    /** Direction au départ du tronçon parcouru (premier segment). */
    private double startBearing(int s) {
        int e = s >> 1, a = g.geoStart.get(e), b = g.geoStart.get(e + 1);
        int pLat, pLon, qLat, qLon;
        if ((s & 1) == 0) {
            int u = g.eu.get(e);
            pLat = g.nLat.get(u); pLon = g.nLon.get(u);
            if (b > a) { qLat = g.gLat.get(a); qLon = g.gLon.get(a); }
            else { int v = g.ev.get(e); qLat = g.nLat.get(v); qLon = g.nLon.get(v); }
        } else {
            int v = g.ev.get(e);
            pLat = g.nLat.get(v); pLon = g.nLon.get(v);
            if (b > a) { qLat = g.gLat.get(b - 1); qLon = g.gLon.get(b - 1); }
            else { int u = g.eu.get(e); qLat = g.nLat.get(u); qLon = g.nLon.get(u); }
        }
        return Math.toDegrees(Math.atan2((qLon - pLon) * 0.637, (qLat - pLat)));
    }

    private double startPenalty(int e, int dir, double heading, Snap from) {
        if (Double.isNaN(heading)) return 0;
        int[] p = g.points(e);
        int i = Math.min(from.seg * 2, p.length - 4);
        double kx = 111320.0 * Math.cos(Math.toRadians(from.lat));
        double h = Math.toDegrees(Math.atan2((p[i + 3] - p[i + 1]) * kx, (p[i + 2] - p[i]) * 110540.0));
        if (dir == 1) h += 180;
        return Math.abs(Glide.wrap(h - heading)) > 100 ? UTURN_S : 0;
    }

    private double h(int n) {
        double dx = (g.nLon.get(n) / 1e6 - tLon) * hkx, dy = (g.nLat.get(n) / 1e6 - tLat) * 110540.0;
        return Math.sqrt(dx * dx + dy * dy) / VMAX;
    }

    private double distFromStart(int n) {
        double dx = (g.nLon.get(n) / 1e6 - sLon) * hkx, dy = (g.nLat.get(n) / 1e6 - sLat) * 110540.0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void push(int s, double c, int par) {
        if (stamp[s] == cur && dist[s] <= c) return;
        stamp[s] = cur;
        dist[s] = (float) c;
        parent[s] = par;
        int e = s >> 1;
        int n = (s & 1) == 0 ? g.ev.get(e) : g.eu.get(e);
        pushKey(s, (float) (c + h(n)));
    }

    private void pushKey(int s, float k) {
        if (hn == hs.length) { hs = Arrays.copyOf(hs, hn * 2); hk = Arrays.copyOf(hk, hn * 2); }
        int i = hn++;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (hk[p] <= k) break;
            hs[i] = hs[p];
            hk[i] = hk[p];
            i = p;
        }
        hs[i] = s;
        hk[i] = k;
    }

    private int pop() {
        int top = hs[0];
        hn--;
        if (hn > 0) {
            int s = hs[hn];
            float k = hk[hn];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= hn) break;
                if (c + 1 < hn && hk[c + 1] < hk[c]) c++;
                if (hk[c] >= k) break;
                hs[i] = hs[c];
                hk[i] = hk[c];
                i = c;
            }
            hs[i] = s;
            hk[i] = k;
        }
        return top;
    }

    /** Ligne de l'itinéraire : depuis le point de départ, tronçon par tronçon, jusqu'au point d'arrivée. */
    private Route build(int[] edges, Snap from, Snap to) {
        ArrayList<double[]> pts = new ArrayList<>();
        int[] firstPt = new int[edges.length];
        double[] secs = new double[edges.length];
        for (int k = 0; k < edges.length; k++) {
            int s = edges[k], e = s >> 1;
            boolean fw = (s & 1) == 0;
            int[] p = g.points(e);
            int n = p.length / 2;
            // points dans le sens de la marche
            double[] la = new double[n], lo = new double[n];
            for (int i = 0; i < n; i++) {
                int j = fw ? i : n - 1 - i;
                la[i] = p[2 * j] / 1e6;
                lo[i] = p[2 * j + 1] / 1e6;
            }
            double part = 1;
            boolean first = k == 0, last = k == edges.length - 1;
            // segment (dans le sens de la marche) où se trouvent le point de départ et le point d'arrivée
            int fs = fw ? from.seg : n - 2 - from.seg;
            int ts = fw ? to.seg : n - 2 - to.seg;
            int startIdx = first ? fs + 1 : 1;   // le point 0 d'un tronçon suivant est le carrefour déjà ajouté
            int endIdx = last ? ts : n - 1;
            firstPt[k] = first ? pts.size() : pts.size() - 1;
            if (first) pts.add(new double[]{from.lat, from.lon});
            for (int i = startIdx; i <= endIdx; i++) pts.add(new double[]{la[i], lo[i]});
            if (last) pts.add(new double[]{to.lat, to.lon});
            double L = g.len(e);
            if (first && last) part = Math.abs(to.along - from.along) / Math.max(0.1, L);
            else if (first) part = (fw ? L - from.along : from.along) / Math.max(0.1, L);
            else if (last) part = (fw ? to.along : L - to.along) / Math.max(0.1, L);
            secs[k] = L * part / g.mps(e);
        }
        Route r = new Route();
        int n = pts.size();
        r.lat = new double[n];
        r.lon = new double[n];
        r.cum = new double[n];
        for (int i = 0; i < n; i++) {
            r.lat[i] = pts.get(i)[0];
            r.lon[i] = pts.get(i)[1];
            if (i > 0) {
                double kx = 111320.0 * Math.cos(Math.toRadians(r.lat[i]));
                r.cum[i] = r.cum[i - 1] + Math.hypot((r.lon[i] - r.lon[i - 1]) * kx, (r.lat[i] - r.lat[i - 1]) * 110540.0);
            }
        }
        r.meters = r.cum[n - 1];
        // durée : chaque bout de ligne à la vitesse de son tronçon
        r.cumT = new double[n];
        int k = 0;
        for (int i = 1; i < n; i++) {
            while (k + 1 < edges.length && firstPt[k + 1] <= i - 1) k++;
            r.cumT[i] = r.cumT[i - 1] + (r.cum[i] - r.cum[i - 1]) / g.mps(edges[k] >> 1);
        }
        r.edges = edges;
        r.edgeFirstPoint = firstPt;
        r.edgeSeconds = secs;
        return r;
    }
}

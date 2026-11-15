package be.pa.launcher208;

import java.util.ArrayList;

/**
 * Instructions de guidage d'un itinéraire : où tourner, de combien, quelle sortie au rond-point, quand on
 * prend une sortie d'autoroute... Calculées une fois pour chaque itinéraire. Aucun lien avec Android.
 *
 * Les noms d'action (« droite », « rp2 », « sortie »...) sont aussi ceux des phrases enregistrées de la voix.
 */
final class Guide {
    /** Une instruction. */
    static final class Step {
        String action;    // droite, gauche, leg_droite, fort_gauche, garde_droite, rp1..rp6, sortie, demi_tour, arrivee
        double at;        // distance depuis le départ de l'itinéraire (m)
        int point;        // point de la ligne de l'itinéraire où a lieu la manœuvre
        double angle;     // virage (degrés, + = à droite), pour dessiner la flèche
        int exit;         // numéro de sortie au rond-point (0 sinon)
        String street;    // nom de la route prise ensuite

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%.0f m : %s%s", at, action, street.isEmpty() ? "" : " → " + street);
        }
    }

    /** Texte affiché pour une instruction. */
    static String text(Step s) {
        switch (s.action) {
            case "droite": return "Tourne à droite";
            case "gauche": return "Tourne à gauche";
            case "leg_droite": return "Légèrement à droite";
            case "leg_gauche": return "Légèrement à gauche";
            case "fort_droite": return "Fortement à droite";
            case "fort_gauche": return "Fortement à gauche";
            case "garde_droite": return "Garde la droite";
            case "garde_gauche": return "Garde la gauche";
            case "sortie": return "Prends la sortie";
            case "sortie_gauche": return "Prends la sortie à gauche";
            case "demi_tour": return "Fais demi-tour";
            case "arrivee": return "Arrivée";
            default:
                if (s.action.startsWith("rp")) return "Rond-point : " + ordinal(s.exit) + " sortie";
                return "Continue tout droit";
        }
    }

    static String ordinal(int n) {
        switch (n) {
            case 1: return "1re";
            default: return n + "e";
        }
    }

    static ArrayList<Step> build(NavGraph g, Router.Route r) {
        ArrayList<Step> out = new ArrayList<>();
        int[] ed = r.edges;
        int k = 0;
        while (k + 1 < ed.length) {
            int e1 = ed[k] >> 1, e2 = ed[k + 1] >> 1;
            int node = (ed[k] & 1) == 0 ? g.ev.get(e1) : g.eu.get(e1);
            int pi = r.edgeFirstPoint[k + 1];
            double in = bearingBefore(r, pi), outB = bearingAfter(r, pi);
            double a = Glide.wrap(outB - in);
            // Rond-point : on compte les sorties jusqu'à celle qu'on prend
            if (g.roundabout(e2) && !g.roundabout(e1)) {
                int exits = 0, j = k + 1;
                while (j + 1 < ed.length && g.roundabout(ed[j] >> 1)) {
                    int ej = ed[j] >> 1;
                    int nj = (ed[j] & 1) == 0 ? g.ev.get(ej) : g.eu.get(ej);
                    exits += exitsAt(g, nj);
                    if (!g.roundabout(ed[j + 1] >> 1)) break;
                    j++;
                }
                if (exits < 1) exits = 1;
                Step s = step("rp" + Math.min(6, exits), r.cum[pi], pi, Glide.wrap(bearingAfter(r, r.edgeFirstPoint[Math.min(j + 1, ed.length - 1)]) - in), g, ed, j + 1);
                s.exit = exits;
                out.add(s);
                k = j + 1;
                continue;
            }
            if (g.roundabout(e1) && g.roundabout(e2)) { k++; continue; }
            // Autres routes possibles à ce carrefour (sans demi-tour ni interdiction)
            int others = 0, nearestEdge = -1;
            double nearest = 999;
            int a0 = g.firstOut.get(node), b0 = g.firstOut.get(node + 1);
            for (int q = a0; q < b0; q++) {
                int o = g.out.get(q), e3 = o >> 1;
                if (e3 == e1 || e3 == e2) continue;
                if (g.nBans > 0 && g.banned(node, e1, e3)) continue;
                double ob = edgeBearing(g, o);
                double ao = Glide.wrap(ob - in);
                if (Math.abs(ao) > 170) continue;
                others++;
                if (Math.abs(ao - a) < Math.abs(nearest - a)) { nearest = ao; nearestEdge = e3; }
            }
            String act = null;
            boolean mainRoad = g.rank(e1) <= 1 && !g.link(e1);
            if (g.link(e2) && mainRoad) act = a >= -5 ? "sortie" : "sortie_gauche";
            else if (g.link(e1) && !g.link(e2) && g.rank(e2) <= 1) act = null; // on rejoint l'autoroute : rien à dire
            else if (others == 0 && Math.abs(a) < 120) act = null;           // simple virage de la route
            else if (Math.abs(a) < 28) {
                // embranchement : seulement si l'autre branche est aussi importante, ou si la route change de nom
                boolean sameRoad = !g.name(e1).isEmpty() && g.name(e1).equals(g.name(e2));
                boolean minorOther = nearestEdge >= 0 && g.rank(nearestEdge) > g.rank(e2);
                if (others > 0 && Math.abs(nearest) < 45 && Math.abs(nearest - a) < 70 && !(sameRoad || minorOther)) {
                    act = a > nearest ? "garde_droite" : "garde_gauche";
                }
            } else act = turn(a, e1 == e2);
            if (act != null) {
                Step s = step(act, r.cum[pi], pi, a, g, ed, k + 1);
                // deux virages dans le même sens à moins de 25 m (route à deux chaussées traversée) : un seul
                Step prev = out.isEmpty() ? null : out.get(out.size() - 1);
                if (prev != null && s.at - prev.at < 25 && !prev.action.startsWith("rp")
                        && Math.signum(prev.angle) == Math.signum(s.angle)) {
                    double sum = prev.angle + s.angle;
                    prev.angle = sum;
                    prev.street = s.street;
                    if (!prev.action.startsWith("sortie")) prev.action = turn(sum, false);
                } else {
                    out.add(s);
                }
            }
            k++;
        }
        Step end = new Step();
        end.action = "arrivee";
        end.at = r.meters;
        end.point = r.lat.length - 1;
        end.street = "";
        out.add(end);
        return out;
    }

    /** Type de virage selon l'angle (degrés, + = à droite). */
    static String turn(double a, boolean sameEdge) {
        double x = Math.abs(a);
        if (x < 28) return a >= 0 ? "garde_droite" : "garde_gauche";
        if (x < 60) return a > 0 ? "leg_droite" : "leg_gauche";
        if (x < 140) return a > 0 ? "droite" : "gauche";
        if (x < 172 && !sameEdge) return a > 0 ? "fort_droite" : "fort_gauche";
        return "demi_tour";
    }

    private static Step step(String act, double at, int pi, double angle, NavGraph g, int[] ed, int from) {
        Step s = new Step();
        s.action = act;
        s.at = at;
        s.point = pi;
        s.angle = angle;
        s.street = "";
        for (int j = from; j < ed.length && j < from + 4; j++) {
            String n = g.name(ed[j] >> 1);
            if (!n.isEmpty()) { s.street = n; break; }
        }
        return s;
    }

    /** Sorties d'un rond-point à ce carrefour (routes qui en partent). */
    private static int exitsAt(NavGraph g, int n) {
        int c = 0;
        for (int q = g.firstOut.get(n); q < g.firstOut.get(n + 1); q++) {
            int e = g.out.get(q) >> 1;
            if (!g.roundabout(e)) c++;
        }
        return c;
    }

    /** Direction (degrés) au départ d'un tronçon parcouru dans ce sens, sur ses 15 premiers mètres. */
    static double edgeBearing(NavGraph g, int o) {
        int[] p = g.points(o >> 1);
        int n = p.length / 2;
        boolean fw = (o & 1) == 0;
        double la0 = p[fw ? 0 : 2 * (n - 1)] / 1e6, lo0 = p[fw ? 1 : 2 * (n - 1) + 1] / 1e6;
        double kx = 111320.0 * Math.cos(Math.toRadians(la0));
        double d = 0, la = la0, lo = lo0;
        for (int i = 1; i < n; i++) {
            int j = fw ? i : n - 1 - i;
            double la2 = p[2 * j] / 1e6, lo2 = p[2 * j + 1] / 1e6;
            d += Math.hypot((lo2 - lo) * kx, (la2 - la) * 110540.0);
            la = la2;
            lo = lo2;
            if (d >= 15) break;
        }
        return Math.toDegrees(Math.atan2((lo - lo0) * kx, (la - la0) * 110540.0));
    }

    private static double bearingBefore(Router.Route r, int pi) {
        int i = pi;
        while (i > 0 && r.cum[pi] - r.cum[i] < 15) i--;
        return bearing(r, i, pi);
    }

    private static double bearingAfter(Router.Route r, int pi) {
        int i = pi, n = r.lat.length;
        while (i < n - 1 && r.cum[i] - r.cum[pi] < 15) i++;
        return bearing(r, pi, i);
    }

    private static double bearing(Router.Route r, int a, int b) {
        double kx = 111320.0 * Math.cos(Math.toRadians(r.lat[a]));
        return Math.toDegrees(Math.atan2((r.lon[b] - r.lon[a]) * kx, (r.lat[b] - r.lat[a]) * 110540.0));
    }
}

package be.pa.launcher208;

/**
 * Mouvement de la voiture affichée sur la carte, image par image, entre deux positions GPS.
 *
 * Le GPS ne donne qu'une position par seconde (parfois plus). Entre deux positions, la voiture affichée
 * continue d'avancer à la vitesse mesurée, le long de la route si elle est connue (elle suit alors les
 * virages), sinon tout droit dans la direction du GPS. Quand une nouvelle position arrive et que la
 * prévision était un peu fausse, la voiture ne saute pas : elle rattrape l'écart en douceur, comme
 * un ressort bien amorti. Sa vitesse change donc progressivement, sans à-coup, et elle ne recule jamais.
 * À l'arrêt, elle reste immobile malgré le petit « bougé » du GPS.
 *
 * Aucun lien avec Android : la logique est vérifiée par les tests sur ordinateur.
 */
final class Glide {
    /** Raideur des ressorts (1/s) : plus grand = rattrape plus vite, plus petit = plus doux. */
    static final double W_POS = 2.0, W_HEAD = 3.0;
    /** Au-delà de ce délai sans nouvelle position (tunnel, GPS perdu), la voiture ralentit et s'arrête. */
    static final double MAX_AGE = 2.0;
    /** En dessous (m/s), la voiture est considérée à l'arrêt. */
    static final double STOP_MPS = 0.7;
    /** En dessous (m/s), le cap du GPS n'est pas fiable : on garde le cap affiché. */
    static final double HEAD_MPS = 1.5;
    /** À l'arrêt, une position plus loin que ça (m) déplace quand même la voiture. */
    static final double HOLD_M = 12;
    /** Part de chaque nouvelle position GPS gardée (le reste vient de la prévision), pour une position par seconde. */
    static final double ALPHA = 0.35, ALPHA_SIDE = 0.6, BETA = 0.1, GAMMA = 0.5;

    // Repère local en mètres autour d'une origine : x vers l'est, y vers le nord.
    private double lat0 = Double.NaN, lon0, kx = 1, ky = 110540.0;

    // Dernière position reçue
    private boolean hasFix;
    private double fixMs;
    private double fx, fy, fv;         // position (collée à la route si possible) et vitesse (m/s)
    private double dx0 = 0, dy0 = 1;   // direction de la marche (unitaire) au point reçu
    private double s0;                 // la position estimée, le long de la route prévue (m depuis son début)
    private double[] px, py, cum;      // route devant la voiture, depuis la position reçue (null si inconnue)
    private boolean holding;           // à l'arrêt
    private double hx, hy;             // point d'arrêt

    // Ce qui est affiché
    private boolean init;
    private double lastMs;
    private double x, y, vx, vy;       // position et vitesse affichées
    private double h, hv;              // cap affiché (degrés, 0 = nord, sens horaire) et vitesse de rotation (°/s)

    private final double[] tmp = new double[4];

    boolean ready() { return init; }

    double lat() { return lat0 + y / ky; }

    double lon() { return lon0 + x / kx; }

    double heading() { return h; }

    /** Vitesse affichée (m/s), pour les tests. */
    double speed() { return Math.sqrt(vx * vx + vy * vy); }

    double x() { return x; }

    double y() { return y; }

    boolean holding() { return holding; }

    /** Coordonnées locales (m) d'un point, dans le repère de ce mouvement. */
    double toX(double lon) { return (lon - lon0) * kx; }

    double toY(double lat) { return (lat - lat0) * ky; }

    /** Nouveau repère centré ici ; ce qui est affiché reste exactement au même endroit. */
    private void origin(double lat, double lon) {
        boolean had = !Double.isNaN(lat0);
        double la = had ? lat() : lat, lo = had ? lon() : lon;
        double hLat = had ? lat0 + hy / ky : lat, hLon = had ? lon0 + hx / kx : lon;
        lat0 = lat;
        lon0 = lon;
        kx = 111320.0 * Math.cos(Math.toRadians(lat));
        x = (lo - lon0) * kx;
        y = (la - lat0) * ky;
        hx = (hLon - lon0) * kx;
        hy = (hLat - lat0) * ky;
    }

    /**
     * Nouvelle position GPS.
     * @param ms        moment de la mesure (ms, même horloge que {@link #step})
     * @param mps       vitesse (m/s)
     * @param measured  vrai si la vitesse est mesurée par la puce GPS (précise) ; faux si elle est calculée
     *                  à partir des positions (imprécise) : elle est alors estimée ici, plus finement
     * @param bearing   cap du GPS (degrés), utilisé seulement si bearingOk et sans route connue
     * @param pathLat   route devant la voiture, en commençant par la position reçue (collée à la route), ou null
     */
    void onFix(double ms, double lat, double lon, double mps, boolean measured, double bearing, boolean bearingOk,
               double[] pathLat, double[] pathLon) {
        if (Double.isNaN(lat0) || Math.abs(lat - lat0) > 0.05 || Math.abs(lon - lon0) > 0.07) origin(lat, lon);
        // 1. Où la voiture devrait être maintenant, d'après les positions précédentes
        double dtFix = (ms - fixMs) / 1000.0;
        boolean pred = hasFix && !holding && fv >= STOP_MPS && dtFix > 0.02 && dtFix < 3;
        double prx = 0, pry = 0, pdx = dx0, pdy = dy0;
        if (pred) {
            at(s0 + dist(dtFix), tmp);
            prx = tmp[0];
            pry = tmp[1];
            pdx = tmp[2];
            pdy = tmp[3];
        }
        // 2. La nouvelle position (et la route devant, si elle est connue)
        double mx = (lon - lon0) * kx, my = (lat - lat0) * ky;
        fixMs = ms;
        s0 = 0;
        px = py = cum = null;
        if (pathLat != null && pathLon != null && pathLat.length >= 2 && pathLat.length == pathLon.length) {
            int n = pathLat.length;
            px = new double[n];
            py = new double[n];
            cum = new double[n];
            for (int i = 0; i < n; i++) {
                px[i] = (pathLon[i] - lon0) * kx;
                py[i] = (pathLat[i] - lat0) * ky;
                if (i > 0) cum[i] = cum[i - 1] + Math.hypot(px[i] - px[i - 1], py[i] - py[i - 1]);
            }
            if (cum[n - 1] < 0.5) px = py = cum = null;
            else {
                // la route commence à la position reçue, collée à la route
                mx = px[0];
                my = py[0];
                int j = 1;
                while (j < n - 1 && cum[j] < 0.5) j++;
                double l = Math.hypot(px[j] - px[0], py[j] - py[0]);
                if (l > 0.01) { dx0 = (px[j] - px[0]) / l; dy0 = (py[j] - py[0]) / l; }
            }
        }
        if (px == null && bearingOk && mps >= HEAD_MPS) {
            double b = Math.toRadians(bearing);
            dx0 = Math.sin(b);
            dy0 = Math.cos(b);
        }
        // 3. Vitesse : celle de la puce si elle la mesure ; sinon estimée à partir de l'avance réelle
        double v = Math.max(0, mps);
        if (!measured && pred && v >= STOP_MPS) {
            // vitesse calculée par le suivi GPS (bruitée) : moyenne glissante, corrigée par l'avance réelle
            double r = (mx - prx) * pdx + (my - pry) * pdy;
            v = Math.max(STOP_MPS, fv + GAMMA * (v - fv) + BETA * r / dtFix);
        }
        fv = v;
        // 4. Position : un GPS se trompe de quelques mètres à chaque point. On garde une partie de la
        //    prévision (qui utilise la vitesse, bien plus régulière) : la voiture avance sans à-coups.
        fx = mx;
        fy = my;
        if (pred && fv >= STOP_MPS && Math.hypot(mx - prx, my - pry) < 25) {
            double k = 1 - Math.pow(1 - ALPHA, Math.min(2, dtFix));
            if (px != null) {
                double sp = project(prx, pry);
                if (!Double.isNaN(sp)) {
                    s0 = (1 - k) * sp;
                    at(s0, tmp);
                    fx = tmp[0];
                    fy = tmp[1];
                }
            } else {
                double kl = 1 - Math.pow(1 - ALPHA_SIDE, Math.min(2, dtFix));
                double ex = mx - prx, ey = my - pry;
                double a = ex * dx0 + ey * dy0, c = -ex * dy0 + ey * dx0;
                fx = prx + k * a * dx0 - kl * c * dy0;
                fy = pry + k * a * dy0 + kl * c * dx0;
            }
        }
        // À l'arrêt : on reste où on s'est arrêté, sauf si le GPS indique clairement un autre endroit
        if (fv < STOP_MPS) {
            if (!holding || Math.hypot(mx - hx, my - hy) > HOLD_M) {
                holding = true;
                hx = mx;
                hy = my;
            }
        } else holding = false;
        hasFix = true;
        if (!init) {
            init = true;
            x = fx;
            y = fy;
            vx = vy = 0;
            h = bearingOk ? norm(bearing) : Math.toDegrees(Math.atan2(dx0, dy0));
            hv = 0;
            lastMs = ms;
        } else if (Math.hypot(x - fx, y - fy) > 150) {
            // grand saut (GPS retrouvé loin d'ici) : pas d'animation
            x = fx;
            y = fy;
            vx = fv * dx0;
            vy = fv * dy0;
            if (fv >= HEAD_MPS) { h = Math.toDegrees(Math.atan2(dx0, dy0)); hv = 0; }
        }
    }

    /**
     * Abscisse (m) le long de la route prévue du point le plus proche de (x, y), en regardant aussi un peu
     * en arrière (valeur négative). NaN si le point est loin de la route.
     */
    private double project(double x, double y) {
        double best = 25, bs = Double.NaN;
        // en arrière du début : droite qui prolonge la route
        double a = (x - px[0]) * dx0 + (y - py[0]) * dy0;
        if (a < 0) {
            double d = Math.abs(-(x - px[0]) * dy0 + (y - py[0]) * dx0);
            if (d < best) { best = d; bs = a; }
        }
        for (int i = 1; i < px.length && cum[i - 1] < 60; i++) {
            double sx = px[i] - px[i - 1], sy = py[i] - py[i - 1], l2 = sx * sx + sy * sy;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - px[i - 1]) * sx + (y - py[i - 1]) * sy) / l2));
            double qx = px[i - 1] + t * sx - x, qy = py[i - 1] + t * sy - y;
            double d = Math.sqrt(qx * qx + qy * qy);
            if (d < best) { best = d; bs = cum[i - 1] + t * (cum[i] - cum[i - 1]); }
        }
        return bs;
    }

    /** Point à la distance s (m) le long de la route prévue (ou tout droit) : x, y, direction x, direction y. */
    private void at(double s, double[] out) {
        if (px == null) {
            out[0] = fx + s * dx0;
            out[1] = fy + s * dy0;
            out[2] = dx0;
            out[3] = dy0;
            return;
        }
        int n = px.length;
        if (s <= 0) {
            out[0] = px[0] + s * dx0;
            out[1] = py[0] + s * dy0;
            out[2] = dx0;
            out[3] = dy0;
            return;
        }
        int i = 1;
        while (i < n - 1 && cum[i] < s) i++;
        double seg = cum[i] - cum[i - 1];
        double ux, uy;
        if (seg > 1e-6) { ux = (px[i] - px[i - 1]) / seg; uy = (py[i] - py[i - 1]) / seg; }
        else { ux = dx0; uy = dy0; }
        double r = s - cum[i - 1]; // au-delà de la fin : on continue tout droit
        out[0] = px[i - 1] + r * ux;
        out[1] = py[i - 1] + r * uy;
        out[2] = ux;
        out[3] = uy;
    }

    /** Distance parcourue prévue au temps t (s depuis la dernière position) et vitesse prévue. */
    private double dist(double age) {
        if (age <= 0) return 0;
        return fv * Math.min(age, MAX_AGE);
    }

    private double vel(double age) { return age > MAX_AGE ? 0 : fv; }

    /** Cap prévu (degrés) autour de la distance s : direction d'une corde de part et d'autre (virages adoucis). */
    private double headAt(double s) {
        double a = Math.max(4, fv * 0.45);
        at(s - a, tmp);
        double ax = tmp[0], ay = tmp[1];
        at(s + a, tmp);
        double bx = tmp[0] - ax, by = tmp[1] - ay;
        if (bx * bx + by * by < 1e-6) return h;
        return Math.toDegrees(Math.atan2(bx, by));
    }

    /**
     * Avance l'affichage jusqu'au moment nowMs (une image). Renvoie vrai tant que quelque chose bouge encore.
     */
    boolean step(double nowMs) {
        if (!init || !hasFix) return false;
        double dt = (nowMs - lastMs) / 1000.0;
        if (dt <= 0) return !settled();
        if (dt > 0.5) dt = 0.5;
        double age0 = (nowMs - fixMs) / 1000.0 - dt, age1 = age0 + dt;

        // Cible : position prévue (ou point d'arrêt), et sa vitesse
        double t0x, t0y, v0x, v0y, t1x, t1y, v1x, v1y, dirx, diry;
        if (holding) {
            t0x = t1x = hx;
            t0y = t1y = hy;
            v0x = v0y = v1x = v1y = 0;
            dirx = diry = 0;
        } else {
            at(s0 + dist(age0), tmp);
            t0x = tmp[0]; t0y = tmp[1];
            double sp0 = vel(age0);
            v0x = sp0 * tmp[2]; v0y = sp0 * tmp[3];
            at(s0 + dist(age1), tmp);
            t1x = tmp[0]; t1y = tmp[1];
            double sp1 = vel(age1);
            v1x = sp1 * tmp[2]; v1y = sp1 * tmp[3];
            dirx = tmp[2]; diry = tmp[3];
        }

        // Ressort amorti (solution exacte : stable quelle que soit la durée de l'image)
        double w = W_POS, dec = Math.exp(-w * dt);
        double ex = x - t0x, ux = vx - v0x, ey = y - t0y, uy = vy - v0y;
        double cx = ux + w * ex, cy = uy + w * ey;
        double nex = (ex + cx * dt) * dec, nux = (ux - cx * w * dt) * dec;
        double ney = (ey + cy * dt) * dec, nuy = (uy - cy * w * dt) * dec;
        double nx = t1x + nex, ny = t1y + ney;
        double nvx = v1x + nux, nvy = v1y + nuy;

        // Jamais de marche arrière à l'écran : si la prévision était trop en avance, la voiture ralentit
        if (!holding && fv >= STOP_MPS) {
            double fwd = (nx - x) * dirx + (ny - y) * diry;
            if (fwd < 0) { nx -= fwd * dirx; ny -= fwd * diry; }
            double along = nvx * dirx + nvy * diry;
            if (along < 0) { nvx -= along * dirx; nvy -= along * diry; }
        }
        x = nx; y = ny; vx = nvx; vy = nvy;

        // Cap : suit la route prévue (corde de part et d'autre, pour arrondir les angles de la carte)
        double ht0, ht1, rate;
        if (!holding && fv >= HEAD_MPS) {
            ht0 = headAt(s0 + dist(age0));
            ht1 = headAt(s0 + dist(age1));
            rate = wrap(ht1 - ht0) / dt;
            if (Math.abs(rate) > 120) rate = 0; // saut de la route prévue (nouvelle position) : pas une vraie rotation
        } else {
            ht0 = ht1 = h;
            rate = 0;
        }
        double wh = W_HEAD, dech = Math.exp(-wh * dt);
        double eh = wrap(h - ht0), uh = hv - rate;
        double ch = uh + wh * eh;
        double neh = (eh + ch * dt) * dech, nuh = (uh - ch * wh * dt) * dech;
        h = norm(ht1 + neh);
        hv = rate + nuh;
        lastMs = nowMs;
        return !settled();
    }

    /** Vrai quand plus rien ne bouge (voiture arrêtée, ressorts au repos) : l'animation peut s'arrêter. */
    boolean settled() {
        if (!init) return true;
        boolean still = holding || !hasFix;
        if (!still) return false;
        double tx = holding ? hx : fx, ty = holding ? hy : fy;
        return Math.abs(x - tx) < 0.02 && Math.abs(y - ty) < 0.02 && Math.abs(vx) < 0.02 && Math.abs(vy) < 0.02
                && Math.abs(hv) < 0.05;
    }

    static double wrap(double d) {
        d = ((d + 180) % 360 + 360) % 360 - 180;
        return d;
    }

    static double norm(double d) { return ((d % 360) + 360) % 360; }
}

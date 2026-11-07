package be.pa.launcher208;

import android.media.session.MediaController;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mapsforge.map.android.view.MapView;

/**
 * Mode conduite : grande vitesse, minimap (ou 3 tuiles dont la radio), gros boutons.
 * S'affiche au-dessus de 10 km/h et revient à l'accueil après l'arrêt (1 min par défaut).
 */
final class DriveScreen extends Screen {
    private TextView speed, clock, weather, street, tripKm, tripMin, mapStreet, mapTrip, radioName, radioLabel, volVal, mapMsg;
    private Widgets.Bar volBar;
    private FrameLayout mapBox;
    private MiniMap mini;
    private Widgets.Marker marker;
    private ImageView playIcon;
    private boolean withMap, shown, mapPending;
    private int lastSpeed = -1, radioPoll;

    DriveScreen(HomeActivity a) { super(a); }

    @Override View build() {
        withMap = P.minimap() && MiniMap.available(a);
        LinearLayout row = U.row(a);
        row.setGravity(0);
        row.setBackgroundColor(Pal.bg);
        U.pad(row, 16, 16, 16, 16);
        row.addView(rail(), U.lp(U.s(104), U.M));
        row.addView(U.gap(a, 16, 1));
        row.addView(withMap ? centerMap() : centerTiles(), U.lpW(1));
        row.addView(U.gap(a, 16, 1));
        row.addView(right(), U.lp(U.s(400), U.M));
        return row;
    }

    // ---------- Barre de gauche : 4 grosses icônes avec leur nom ----------
    private View rail() {
        LinearLayout l = U.col(a);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        U.card(l, Pal.card, 28);
        U.pad(l, 0, 10, 0, 10);
        bigItem(l, R.drawable.ic_carplay, 0xFF4C8DFF, 0xFF2563EB, "CarPlay", 36, new Runnable() {
            @Override public void run() { a.openCarplay(); }
        });
        bigItem(l, R.drawable.ic_radio, 0xFFFFB547, 0xFFF08A1C, "Radio", 36, new Runnable() {
            @Override public void run() { a.openRole(P.PKG_RADIO, AppsRepo.RADIO, "Radio"); }
        });
        bigItem(l, R.drawable.ic_music, 0xFFA78BFA, 0xFF7C3AED, "Musique", 36, new Runnable() {
            @Override public void run() { a.openRole(P.PKG_MUSIC, AppsRepo.MUSIC, "Musique"); }
        });
        bigItem(l, R.drawable.ic_phone, 0xFF4ADE80, 0xFF16A34A, "Téléphone", 34, new Runnable() {
            @Override public void run() { a.openRole(P.PKG_PHONE, AppsRepo.PHONE, "Téléphone"); }
        });
        return l;
    }

    private void bigItem(LinearLayout parent, int icon, int c1, int c2, String label, float iconSize, Runnable click) {
        LinearLayout it = U.col(a);
        it.setGravity(Gravity.CENTER);
        FrameLayout b = U.box(a, 76, U.grad(c1, c2, 24), icon, iconSize, Pal.WHITE);
        U.shadow(b, 6);
        it.addView(b, U.lp(U.s(76), U.s(76)));
        it.addView(U.tv(a, label, 14, Pal.text, 600), U.lpm(U.WR, U.WR, 0, 8, 0, 0));
        U.click(it, 20, click);
        parent.addView(it, U.lpH(1));
    }

    // ---------- Centre avec minimap ----------
    private View centerMap() {
        LinearLayout c = U.col(a);
        c.setBackground(new Widgets.RadialBg(Pal.c0, Pal.c1, Pal.c2, 28, 0.5f, 0.26f, 0.70f, 0.50f));
        U.shadow(c, 8);
        U.pad(c, 16, 18, 16, 16);
        c.addView(topBar(), U.lpm(U.M, U.WR, 8, 0, 8, 0));
        c.addView(U.flexV(a));
        LinearLayout sp = U.row(a);
        sp.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        speed = U.tv(a, "0", 150, Pal.text, 300);
        U.spacing(speed, -6, 150);
        sp.addView(speed);
        sp.addView(U.tv(a, "km/h", 28, Pal.text2, 500), U.lpm(U.WR, U.WR, 14, 0, 0, 0)); // alignés sur la ligne de base
        c.addView(sp, U.lp(U.M, U.WR));
        c.addView(U.flexV(a));

        mapBox = new FrameLayout(a);
        mapBox.setBackground(U.rr(Pal.night ? 0xFF1B1E24 : 0xFFECEFF2, 22));
        mapBox.setClipToOutline(true);
        U.shadow(mapBox, 6);
        mapMsg = U.tvMulti(a, "Carte indisponible.\nRéglages → Carte de Belgique", 15, Pal.text2, 500, 2);
        mapMsg.setGravity(Gravity.CENTER);
        mapBox.addView(mapMsg, U.flm(U.M, U.WR, Gravity.CENTER, 20, 0, 20, 0));
        mapStreet = pill("", 14, 600);
        mapStreet.setMaxWidth(U.s(540));
        mapBox.addView(mapStreet, U.flm(U.WR, U.s(36), Gravity.TOP | Gravity.LEFT, 14, 14, 0, 0));
        TextView north = U.tv(a, "N", 15, 0xFFDC2626, 700);
        north.setGravity(Gravity.CENTER);
        north.setBackground(U.oval(Pal.card));
        U.shadow(north, 3);
        mapBox.addView(north, U.flm(U.s(42), U.s(42), Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));
        mapTrip = pill("", 15, 600);
        mapBox.addView(mapTrip, U.flm(U.WR, U.s(40), Gravity.BOTTOM | Gravity.LEFT, 14, 0, 0, 14));
        TextView osm = U.tv(a, "© OpenStreetMap", 11, Pal.text2, 400);
        osm.setBackground(U.rr(Pal.night ? 0xD916181C : 0xD9FFFFFF, 6));
        U.pad(osm, 7, 2, 7, 2);
        mapBox.addView(osm, U.flm(U.WR, U.WR, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 8));
        c.addView(mapBox, U.lp(U.M, U.s(360)));
        return c;
    }

    private TextView pill(String t, float size, int weight) {
        TextView p = U.tv(a, t, size, Pal.text, weight);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(U.rr(Pal.night ? 0xF016181C : 0xF0FFFFFF, 18));
        U.pad(p, 12, 0, 12, 0);
        return p;
    }

    private View topBar() {
        LinearLayout t = U.row(a);
        LinearLayout pill = U.row(a);
        pill.setBackground(U.rr(Pal.night ? 0xFFF2F3F5 : 0xFF15181C, 20));
        U.pad(pill, 12, 0, 16, 0);
        int fg = Pal.night ? 0xFF0A0B0D : Pal.WHITE;
        pill.addView(U.icon(a, R.drawable.ic_steer, 20, fg));
        pill.addView(U.tv(a, "Mode conduite", 15, fg, 600), U.lpm(U.WR, U.WR, 8, 0, 0, 0));
        t.addView(pill, U.lp(U.WR, U.s(40)));
        t.addView(U.flex(a));
        clock = U.tv(a, "", 30, Pal.text, 400);
        t.addView(clock);
        weather = U.tv(a, "", 17, Pal.text2, 400);
        t.addView(weather, U.lpm(U.WR, U.WR, 12, 0, 0, 0));
        return t;
    }

    // ---------- Centre sans minimap : 3 tuiles dont la radio ----------
    private View centerTiles() {
        FrameLayout f = new FrameLayout(a);
        f.setBackground(new Widgets.RadialBg(Pal.c0, Pal.c1, Pal.c2, 28, 0.5f, 0.40f, 0.70f, 0.55f));
        U.shadow(f, 8);
        f.addView(topBar(), U.flm(U.M, U.WR, Gravity.TOP, 26, 22, 26, 0));

        LinearLayout mid = U.col(a);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        speed = U.tv(a, "0", 210, Pal.text, 300);
        U.spacing(speed, -9, 210);
        mid.addView(speed);
        mid.addView(U.tv(a, "km/h", 28, Pal.text2, 500), U.lpm(U.WR, U.WR, 0, 6, 0, 0));
        street = U.tv(a, "", 18, Pal.text, 600);
        street.setGravity(Gravity.CENTER_VERTICAL);
        street.setBackground(U.rr(Pal.card, 23));
        U.shadow(street, 4);
        android.graphics.drawable.Drawable pin = U.dr(a, R.drawable.ic_pin, Pal.accent);
        if (pin != null) pin.setBounds(0, 0, U.s(22), U.s(22));
        street.setCompoundDrawablesRelative(pin, null, null, null);
        street.setCompoundDrawablePadding(U.s(8));
        U.pad(street, 14, 0, 20, 0);
        street.setMaxWidth(U.s(600));
        mid.addView(street, U.lpm(U.WR, U.s(46), 0, 22, 0, 0));
        f.addView(mid, U.flm(U.M, U.WR, Gravity.CENTER, 0, 70, 0, 172)); // centré entre la barre du haut et les tuiles

        LinearLayout tiles = U.row(a);
        tiles.setGravity(0);
        View t1 = stat(R.drawable.ic_route, "Trajet en cours");
        tripKm = (TextView) ((LinearLayout) t1).getChildAt(2);
        View t2 = stat(R.drawable.ic_timer, "Depuis le départ");
        tripMin = (TextView) ((LinearLayout) t2).getChildAt(2);
        tiles.addView(t1, new LinearLayout.LayoutParams(0, U.M, 1f));
        tiles.addView(U.gap(a, 12, 1));
        tiles.addView(t2, new LinearLayout.LayoutParams(0, U.M, 1f));
        tiles.addView(U.gap(a, 12, 1));
        tiles.addView(radioTile(), new LinearLayout.LayoutParams(0, U.M, 1.35f));
        f.addView(tiles, U.flm(U.M, U.s(134), Gravity.BOTTOM, 28, 0, 28, 28));
        return f;
    }

    private View stat(int icon, String label) {
        LinearLayout c = U.col(a);
        U.card(c, Pal.card, 22);
        U.pad(c, 20, 18, 20, 18);
        LinearLayout h = U.row(a);
        h.addView(U.icon(a, icon, 18, Pal.text2));
        h.addView(U.tv(a, label, 14, Pal.text2, 500), U.lpm(U.WR, U.WR, 8, 0, 0, 0));
        c.addView(h);
        c.addView(U.flexV(a));
        c.addView(U.tv(a, "", 36, Pal.text, 600));
        c.addView(U.flexV(a));
        return c;
    }

    private View radioTile() {
        LinearLayout c = U.col(a);
        U.card(c, Pal.card, 22);
        U.pad(c, 16, 14, 16, 14);
        LinearLayout top = U.row(a);
        top.addView(U.box(a, 30, U.grad(0xFFFFB547, 0xFFF08A1C, 9), R.drawable.ic_radio, 18, Pal.WHITE));
        radioName = U.tv(a, "Radio", 19, Pal.text, 700);
        top.addView(radioName, U.lpm(0, U.WR, 10, 0, 8, 0));
        ((LinearLayout.LayoutParams) radioName.getLayoutParams()).weight = 1;
        radioLabel = U.tv(a, "Radio", 13, Pal.text2, 500);
        radioLabel.setVisibility(View.GONE);
        top.addView(radioLabel);
        U.click(top, 10, new Runnable() {
            @Override public void run() { a.openRole(P.PKG_RADIO, AppsRepo.RADIO, "Radio"); }
        });
        c.addView(top);
        c.addView(U.flexV(a));
        LinearLayout b = U.row(a);
        b.addView(mediaBtn(R.drawable.ic_prev, KeyEvent.KEYCODE_MEDIA_PREVIOUS, false));
        b.addView(U.flex(a));
        View play = mediaBtn(R.drawable.ic_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true);
        playIcon = (ImageView) ((FrameLayout) play).getChildAt(0);
        b.addView(play);
        b.addView(U.flex(a));
        b.addView(mediaBtn(R.drawable.ic_next, KeyEvent.KEYCODE_MEDIA_NEXT, false));
        c.addView(b);
        return c;
    }

    private View mediaBtn(int icon, final int key, boolean main) {
        FrameLayout f = U.box(a, 64, main ? U.grad(0xFFFFB547, 0xFFF08A1C, 20) : U.rr(Pal.card2, 20), icon, 28, main ? Pal.WHITE : Pal.text);
        if (main) U.shadow(f, 6);
        U.click(f, 20, new Runnable() {
            @Override public void run() {
                App.I.media.key(key, App.I.apps.radioPkg());
                radioPoll = 0;
            }
        });
        return f;
    }

    // ---------- Colonne de droite : CarPlay + volume ----------
    private View right() {
        LinearLayout col = U.col(a);
        LinearLayout cp = U.col(a);
        cp.setBackground(U.gradDiag(0xFF2563EB, 0xFF1A47B8, 28));
        U.shadow(cp, 10);
        U.pad(cp, 26, 26, 26, 26);
        LinearLayout h = U.row(a);
        h.addView(U.box(a, 64, U.rr(Pal.WHITE, 20), R.drawable.ic_carplay, 32, Pal.BLUE));
        LinearLayout tt = U.col(a);
        tt.addView(U.tv(a, "CarPlay", 32, Pal.WHITE, 700));
        String pkg = App.I.apps.carplayPkg();
        tt.addView(U.tv(a, pkg.isEmpty() ? "Appli à choisir" : App.I.apps.label(pkg), 16, Pal.WHITE, 400), U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        h.addView(tt, U.lpm(U.WR, U.WR, 16, 0, 0, 0));
        cp.addView(h);
        cp.addView(U.flexV(a));
        TextView open = U.tv(a, "Ouvrir CarPlay", 24, Pal.BLUE_D, 700);
        open.setGravity(Gravity.CENTER);
        open.setBackground(U.rr(Pal.WHITE, 20));
        U.click(open, 20, new Runnable() {
            @Override public void run() { a.openCarplay(); }
        });
        cp.addView(open, U.lp(U.M, U.s(84)));
        col.addView(cp, U.lpH(1));
        col.addView(U.gap(a, -1, 16));

        LinearLayout v = U.col(a);
        U.card(v, Pal.card, 28);
        U.pad(v, 22, 20, 22, 20);
        LinearLayout vh = U.row(a);
        vh.addView(U.tv(a, "Volume", 16, Pal.text2, 500), U.lpW(1));
        ((LinearLayout.LayoutParams) vh.getChildAt(0).getLayoutParams()).height = U.WR;
        volVal = U.tv(a, "", 34, Pal.text, 600);
        vh.addView(volVal);
        v.addView(vh);
        v.addView(U.flexV(a));
        volBar = new Widgets.Bar(a, 0, Pal.accent);
        v.addView(volBar, U.lp(U.M, U.s(14)));
        v.addView(U.flexV(a));
        LinearLayout b = U.row(a);
        b.addView(volBtn(R.drawable.ic_vol_minus, -1), new LinearLayout.LayoutParams(0, U.s(84), 1f));
        b.addView(U.gap(a, 14, 1));
        b.addView(volBtn(R.drawable.ic_vol_plus, 1), new LinearLayout.LayoutParams(0, U.s(84), 1f));
        v.addView(b);
        col.addView(v, U.lp(U.M, U.s(236)));
        return col;
    }

    private View volBtn(int icon, final int step) {
        FrameLayout f = U.box(a, 84, U.rr(Pal.card2, 22), icon, 34, Pal.text);
        U.click(f, 22, new Runnable() {
            @Override public void run() { App.I.vol.step(step); }
        });
        return f;
    }

    // ---------- Vie de l'écran ----------
    @Override void onShow() {
        shown = true;
        if (withMap && mapBox != null && mini == null && !mapPending) {
            // L'écran de conduite s'affiche tout de suite ; la carte est préparée juste après sa première image.
            mapPending = true;
            final View v = mapBox;
            v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    v.getViewTreeObserver().removeOnPreDrawListener(this);
                    v.post(createMap);
                    return true;
                }
            });
        }
        if (mapMsg != null) mapMsg.setVisibility(withMap ? View.GONE : View.VISIBLE);
        refresh();
    }

    private final Runnable createMap = new Runnable() {
        @Override public void run() {
            if (!mapPending) return;
            mapPending = false;
            if (!shown || mini != null || mapBox == null) return;
            App.mark("conduite : création de la carte");
            mini = new MiniMap();
            MapView mv = mini.create(a);
            if (mv != null) {
                MiniMap.Frame fr = new MiniMap.Frame(a);
                fr.addView(mv, new FrameLayout.LayoutParams(U.M, U.M));
                mapBox.addView(fr, 0, new FrameLayout.LayoutParams(U.M, U.M));
                marker = new Widgets.Marker(a);
                mapBox.addView(marker, 1, U.flm(U.s(84), U.s(84), Gravity.CENTER, 0, 0, 0, 0));
                LocationTracker l = App.I.loc;
                mini.follow(l.lat, l.lon, App.I.speed());
            } else {
                mini = null;
            }
            if (mapMsg != null) mapMsg.setVisibility(mini == null ? View.VISIBLE : View.GONE);
            App.mark("conduite : carte en place");
        }
    };

    @Override void onHide() {
        shown = false;
        mapPending = false;
        if (mapBox != null) mapBox.removeCallbacks(createMap);
        if (mini != null) {
            mini.destroy();
            mini = null;
            if (mapBox != null) {
                // Enlève la carte et la flèche (on garde les étiquettes)
                while (mapBox.getChildCount() > 5) mapBox.removeViewAt(0);
            }
            marker = null;
        }
    }

    @Override void destroy() { onHide(); }

    @Override void tick() {
        float v = App.I.speed();
        int s = Math.round(v);
        if (s != lastSpeed) {
            lastSpeed = s;
            speed.setText(String.valueOf(s));
        }
        LocationTracker l = App.I.loc;
        if (mini != null) {
            mini.follow(l.lat, l.lon, v);
            if (marker != null) marker.setBearing(l.bearing);
        }
        String st = l.street;
        if (mapStreet != null) {
            U.text(mapStreet, st);
            mapStreet.setVisibility(st.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (street != null) {
            U.text(street, st);
            street.setVisibility(st.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
        TripStore t = App.I.trips;
        long mins = t.curStart > 0 ? Math.max(0, (Clock.now() - t.curStart) / 60000L) : 0;
        if (tripKm != null) {
            U.text(tripKm, HomeScreen.vu(Fmt.km(t.curKm), " km", 16));
            U.text(tripMin, HomeScreen.vu(String.valueOf(mins), " min", 16));
        }
        if (mapTrip != null) U.text(mapTrip, Fmt.km(t.curKm) + " km   ·   " + Fmt.dur(mins));
        if (radioName != null && radioPoll-- <= 0) {
            radioPoll = 3;
            updateRadio();
        }
    }

    private void updateRadio() {
        String pkg = App.I.apps.radioPkg();
        MediaController mc = App.I.media.controller(pkg);
        String title = mc == null ? "" : Media.title(mc);
        U.text(radioName, title.isEmpty() ? "Radio" : title);
        radioLabel.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
        boolean playing = mc == null || Media.isPlaying(mc);
        playIcon.setImageDrawable(U.dr(a, playing ? R.drawable.ic_pause : R.drawable.ic_play, Pal.WHITE));
    }

    @Override void refresh() {
        if (root == null) return;
        long now = Clock.now();
        clock.setText(Clock.trusted() ? Fmt.hhmm(now) : "--:--");
        Weather w = App.I.weather;
        weather.setText(w.has() && w.fresh() ? w.temp() + "°" : "");
        volumeChanged();
        lastSpeed = -1;
        tick();
    }

    void volumeChanged() {
        if (root == null) return;
        int v = App.I.vol.get(), mx = App.I.vol.max();
        volVal.setText(String.valueOf(v));
        volBar.set(v / (float) mx);
    }
}

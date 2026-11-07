package be.pa.launcher208;

import android.app.Dialog;
import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.view.MapView;

import java.util.ArrayList;

/** Page principale : ta 208, ses trajets, son entretien + CarPlay, heure, météo, vitesse, volume. */
final class HomeScreen extends Screen {
    private int tab;
    private FrameLayout carPane;
    private TripsPanel trips;
    private MaintPanel maint;
    private LinearLayout segBox;
    private TextView[] segTabs;
    private TextView subtitle;
    private CarView car;
    private View arrowL, arrowR, doorChip, topCard, permChip;
    private TextView hint, doorText, doorExtra;
    private TopView top;
    private TextView parkTitle, parkSub, dayTitle, maintTitle, maintSub;
    // Colonne de droite
    private TextView cpStatus, clock, date, wTemp, wLabel, speedVal, outVal, volVal, speedLbl;
    private ImageView wIcon;
    private View cpDot;
    private Widgets.Bar speedBar, outBar, volBar;
    private int lastSpeed = -1;

    HomeScreen(HomeActivity a) { super(a); }

    @Override View build() {
        LinearLayout row = U.row(a);
        row.setGravity(0);
        row.addView(center(), U.lpW(1));
        row.addView(U.gap(a, 16, 1));
        row.addView(right(), U.lp(U.s(336), U.M));
        return row;
    }

    // ================= Carte centrale =================
    private View center() {
        FrameLayout f = new FrameLayout(a);
        f.setBackground(new Widgets.RadialBg(Pal.c0, Pal.c1, Pal.c2, 28, 0.5f, 0.56f, 0.70f, 0.62f));
        U.shadow(f, 8);
        f.setClipToOutline(true);

        carPane = new FrameLayout(a);
        buildCarPane();
        f.addView(carPane, new FrameLayout.LayoutParams(U.M, U.M));

        trips = new TripsPanel(a);
        f.addView(trips.view(), U.flm(U.M, U.M, Gravity.TOP, 20, 96, 20, 20));
        maint = new MaintPanel(a, this);
        f.addView(maint.view(), U.flm(U.M, U.M, Gravity.TOP, 20, 96, 20, 20));

        // En-tête : titre + onglets
        LinearLayout head = U.row(a);
        head.setGravity(Gravity.TOP);
        LinearLayout titles = U.col(a);
        TextView t = U.tv(a, "Peugeot 208", 28, Pal.text, 700);
        U.spacing(t, -0.4f, 28);
        titles.addView(t);
        subtitle = U.tv(a, "5 portes · prête à rouler", 14, Pal.text2, 400);
        titles.addView(subtitle, U.lpm(U.WR, U.WR, 0, 4, 0, 0));
        head.addView(titles, U.lpW(1));
        // hauteur selon le contenu : sinon la rangée prend la hauteur des onglets et coupe le sous-titre en deux
        ((LinearLayout.LayoutParams) titles.getLayoutParams()).height = U.WR;
        segBox = Widgets.seg(a, new String[]{"Voiture", "Trajets", "Entretien"}, 0, new Widgets.IntCb() {
            @Override public void on(int v) { tab(v); }
        });
        segTabs = new TextView[3];
        for (int i = 0; i < 3; i++) segTabs[i] = (TextView) segBox.getChildAt(i);
        head.addView(segBox, U.lp(U.WR, U.WR));
        f.addView(head, U.flm(U.M, U.WR, Gravity.TOP, 28, 22, 22, 0));
        showTab();
        doorsChanged(); // après la création du sous-titre
        return f;
    }

    private void buildCarPane() {
        car = new CarView(a);
        carPane.addView(car, U.flm(U.s(720), U.s(360), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 104, 0, 0));

        arrowL = roundArrow(R.drawable.ic_chev_l, -1);
        carPane.addView(arrowL, U.flm(U.s(48), U.s(48), Gravity.TOP | Gravity.LEFT, 22, 266, 0, 0));
        arrowR = roundArrow(R.drawable.ic_chev_r, 1);
        carPane.addView(arrowR, U.flm(U.s(48), U.s(48), Gravity.TOP | Gravity.RIGHT, 0, 266, 22, 0));

        hint = U.tv(a, "Glisse le doigt pour la faire tourner", 13, Pal.text2, 400);
        hint.setGravity(Gravity.CENTER);
        carPane.addView(hint, U.flm(U.M, U.WR, Gravity.TOP, 0, 476, 0, 0));

        // Alerte portière
        LinearLayout chip = U.row(a);
        chip.setBackground(U.rrStroke(Pal.warnBg, 24, Pal.warnLine, 2));
        U.pad(chip, 14, 0, 20, 0);
        chip.addView(U.box(a, 30, U.oval(Pal.ORANGE), R.drawable.ic_alert, 18, Pal.WHITE));
        doorText = U.tv(a, "", 16, Pal.warnText, 700);
        chip.addView(doorText, U.lpm(U.WR, U.WR, 10, 0, 0, 0));
        doorExtra = U.tv(a, "", 14, Pal.warnText, 500);
        chip.addView(doorExtra, U.lpm(U.WR, U.WR, 8, 0, 0, 0));
        doorChip = chip;
        carPane.addView(chip, U.flm(U.WR, U.s(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 100, 0, 0));

        // Rappel : la bulle du volume sur CarPlay a besoin de 2 autorisations (données dans le mode test)
        LinearLayout pc = U.row(a);
        pc.setBackground(U.rrStroke(Pal.warnBg, 24, Pal.warnLine, 2));
        U.pad(pc, 14, 0, 20, 0);
        pc.addView(U.box(a, 30, U.oval(Pal.ORANGE), R.drawable.ic_alert, 18, Pal.WHITE));
        pc.addView(U.tv(a, "Bulle du volume : autorisations à donner · touche ici", 15, Pal.warnText, 600), U.lpm(U.WR, U.WR, 10, 0, 0, 0));
        U.click(pc, 24, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_TEST); }
        });
        permChip = pc;
        pc.setVisibility(View.GONE);
        carPane.addView(pc, U.flm(U.WR, U.s(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 100, 0, 0));

        LinearLayout tc = U.col(a);
        tc.setGravity(Gravity.CENTER_HORIZONTAL);
        U.card(tc, Pal.card, 20);
        U.pad(tc, 0, 10, 0, 10);
        tc.addView(U.tv(a, "Avant", 12, Pal.text2, 600));
        top = new TopView(a);
        tc.addView(top, U.lpm(U.s(96), U.s(176), 0, 4, 0, 4));
        tc.addView(U.tv(a, "Arrière", 12, Pal.text2, 600));
        topCard = tc;
        carPane.addView(tc, U.flm(U.s(128), U.WR, Gravity.TOP | Gravity.LEFT, 22, 160, 0, 0));

        // Trois raccourcis en bas
        LinearLayout chips = U.row(a);
        View c1 = chip(R.drawable.ic_pin, Pal.chipB, Pal.chipBf, new Runnable() {
            @Override public void run() { showPark(); }
        });
        parkTitle = (TextView) ((LinearLayout) ((LinearLayout) c1).getChildAt(1)).getChildAt(0);
        parkSub = (TextView) ((LinearLayout) ((LinearLayout) c1).getChildAt(1)).getChildAt(1);
        View c2 = chip(R.drawable.ic_route, Pal.chipG, Pal.chipGf, new Runnable() {
            @Override public void run() { tab(1); }
        });
        dayTitle = (TextView) ((LinearLayout) ((LinearLayout) c2).getChildAt(1)).getChildAt(0);
        ((TextView) ((LinearLayout) ((LinearLayout) c2).getChildAt(1)).getChildAt(1)).setText("Trajets du jour");
        View c3 = chip(R.drawable.ic_wrench, Pal.chipO, Pal.chipOf, new Runnable() {
            @Override public void run() { tab(2); }
        });
        maintTitle = (TextView) ((LinearLayout) ((LinearLayout) c3).getChildAt(1)).getChildAt(0);
        maintSub = (TextView) ((LinearLayout) ((LinearLayout) c3).getChildAt(1)).getChildAt(1);
        chips.addView(c1, U.lpW(1));
        chips.addView(U.gap(a, 12, 1));
        chips.addView(c2, U.lpW(1));
        chips.addView(U.gap(a, 12, 1));
        chips.addView(c3, U.lpW(1));
        carPane.addView(chips, U.flm(U.M, U.s(88), Gravity.BOTTOM, 20, 0, 20, 20));
    }

    private View roundArrow(int icon, final int dir) {
        FrameLayout f = U.box(a, 48, U.oval(Pal.card), icon, 22, Pal.text);
        U.shadow(f, 6);
        U.click(f, 24, new Runnable() {
            @Override public void run() { car.turn(dir); }
        });
        return f;
    }

    private View chip(int icon, int bg, int fg, Runnable click) {
        LinearLayout l = U.row(a);
        U.card(l, Pal.card, 20);
        U.pad(l, 16, 0, 16, 0);
        l.addView(U.box(a, 46, U.oval(bg), icon, 22, fg));
        LinearLayout t = U.col(a);
        t.addView(U.tv(a, "", 15, Pal.text, 600));
        t.addView(U.tv(a, "", 13, Pal.text2, 400), U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        l.addView(t, U.lpm(0, U.WR, 14, 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        U.click(l, 20, click);
        return l;
    }

    void tab(int t) {
        tab = t;
        if (segTabs != null) Widgets.segSelect(segTabs, t);
        showTab();
        if (subtitle != null && App.I.doors.mask() == 0) U.text(subtitle, tabSubtitle());
        refresh();
    }

    private void showTab() {
        carPane.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        trips.view().setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        maint.view().setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
    }

    // ================= Colonne de droite =================
    private View right() {
        LinearLayout col = U.col(a);

        // CarPlay
        LinearLayout cp = U.col(a);
        cp.setBackground(U.gradDiag(0xFF2563EB, 0xFF1A47B8, 28));
        U.shadow(cp, 10);
        U.pad(cp, 22, 22, 22, 22);
        LinearLayout top = U.row(a);
        top.addView(U.box(a, 52, U.rr(Pal.WHITE, 16), R.drawable.ic_carplay, 30, Pal.BLUE));
        LinearLayout tt = U.col(a);
        tt.addView(U.tv(a, "CarPlay", 26, Pal.WHITE, 700), U.lp(U.WR, U.WR)); // largeur explicite (sinon « CarP… »)
        LinearLayout st = U.row(a);
        cpDot = new View(a);
        cpDot.setBackground(U.oval(0xFF86EFAC));
        st.addView(cpDot, U.lp(U.s(9), U.s(9)));
        cpStatus = U.tv(a, "", 14, Pal.WHITE, 400);
        st.addView(cpStatus, U.lpm(U.WR, U.WR, 7, 0, 0, 0));
        tt.addView(st, U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        top.addView(tt, U.lpm(U.WR, U.WR, 14, 0, 0, 0));
        cp.addView(top);
        cp.addView(U.flexV(a));
        TextView open = U.tv(a, "Ouvrir CarPlay", 18, Pal.BLUE_D, 700);
        open.setGravity(Gravity.CENTER);
        open.setBackground(U.rr(Pal.WHITE, 16));
        U.click(open, 16, new Runnable() {
            @Override public void run() { a.openCarplay(); }
        });
        open.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                a.chooseApp("Application CarPlay", P.PKG_CARPLAY, null);
                return true;
            }
        });
        cp.addView(open, U.lp(U.M, U.s(56)));
        col.addView(cp, U.lp(U.M, U.s(184)));
        col.addView(U.gap(a, -1, 16));

        // Heure et météo
        LinearLayout cw = U.row(a);
        cw.setGravity(Gravity.TOP);
        U.card(cw, Pal.card, 28);
        U.pad(cw, 22, 20, 22, 20);
        LinearLayout tl = U.col(a);
        clock = U.tv(a, "--:--", 56, Pal.text, 300);
        U.spacing(clock, -2, 56);
        tl.addView(clock);
        date = U.tv(a, "", 15, Pal.text2, 400);
        tl.addView(date, U.lpm(U.WR, U.WR, 0, 12, 0, 0));
        cw.addView(tl, U.lpW(1));
        LinearLayout wr = U.col(a);
        wr.setGravity(Gravity.END);
        wIcon = U.icon(a, R.drawable.ic_cloud, 32, Pal.text);
        wr.addView(wIcon);
        wTemp = U.tv(a, "", 30, Pal.text, 600);
        wr.addView(wTemp, U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        wLabel = U.tv(a, "", 13, Pal.text2, 400);
        wr.addView(wLabel, U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        cw.addView(wr, U.lp(U.WR, U.WR));
        U.click(cw, 28, new Runnable() {
            @Override public void run() {
                // heure qui paraît fausse : on ouvre directement « Date et heure » d'Android pour la régler
                if (Clock.suspicious()) AppsRepo.openSettings(a, android.provider.Settings.ACTION_DATE_SETTINGS);
                else a.showScreen(HomeActivity.S_CONN);
            }
        });
        col.addView(cw, U.lp(U.M, U.s(150)));
        col.addView(U.gap(a, -1, 16));

        // Vitesse + extérieur
        LinearLayout r1 = U.row(a);
        r1.setGravity(0);
        speedBar = new Widgets.Bar(a, 0, Pal.accent);
        View sp = tile("Vitesse", speedBar);
        speedVal = (TextView) ((LinearLayout) sp).getChildAt(2);
        speedLbl = (TextView) ((LinearLayout) sp).getChildAt(0);
        outBar = new Widgets.Bar(a, 0, Pal.teal);
        View ou = tile("Extérieur", outBar);
        outVal = (TextView) ((LinearLayout) ou).getChildAt(2);
        r1.addView(sp, U.lpW(1));
        r1.addView(U.gap(a, 16, 1));
        r1.addView(ou, U.lpW(1));
        col.addView(r1, U.lpH(1));
        col.addView(U.gap(a, -1, 16));

        // Volume (toujours visible)
        LinearLayout v = U.col(a);
        U.card(v, Pal.card, 22);
        U.pad(v, 16, 14, 16, 14);
        LinearLayout vh = U.row(a);
        vh.addView(U.tv(a, "Volume", 13, Pal.text2, 500), U.lpW(1));
        ((LinearLayout.LayoutParams) vh.getChildAt(0).getLayoutParams()).height = U.WR;
        volVal = U.tv(a, "", 17, Pal.text, 600);
        vh.addView(volVal);
        v.addView(vh);
        v.addView(U.flexV(a));
        LinearLayout vr = U.row(a);
        vr.addView(volBtn(R.drawable.ic_vol_minus, -1));
        volBar = new Widgets.Bar(a, 0, Pal.accent);
        vr.addView(volBar, U.lpm(0, U.s(12), 12, 0, 12, 0));
        ((LinearLayout.LayoutParams) volBar.getLayoutParams()).weight = 1;
        vr.addView(volBtn(R.drawable.ic_vol_plus, 1));
        v.addView(vr);
        col.addView(v, U.lpH(1));
        return col;
    }

    /** Tuile : titre en haut, chiffre au milieu, barre en bas. Le chiffre est l'enfant n° 2. */
    private View tile(String label, Widgets.Bar bar) {
        LinearLayout l = U.col(a);
        U.card(l, Pal.card, 22);
        U.pad(l, 18, 16, 18, 16);
        l.addView(U.tv(a, label, 13, Pal.text2, 500));
        l.addView(U.flexV(a));
        l.addView(U.tv(a, "", 32, Pal.text, 600));
        l.addView(U.flexV(a));
        l.addView(bar, U.lp(U.M, U.s(5)));
        return l;
    }

    private View volBtn(int icon, final int step) {
        FrameLayout f = U.box(a, 56, U.rr(Pal.card2, 16), icon, 30, Pal.text);
        U.click(f, 16, new Runnable() {
            @Override public void run() { App.I.vol.step(step); }
        });
        return f;
    }

    /** « 48 km/h » avec l'unité plus petite et grisée. */
    static CharSequence vu(String value, String unit, float unitSize) {
        SpannableStringBuilder b = new SpannableStringBuilder(value);
        int s = b.length();
        b.append(unit);
        b.setSpan(new AbsoluteSizeSpan(U.s(unitSize)), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(Pal.text2), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    // ================= Mises à jour =================
    @Override void tick() {
        // « Vitesse · recherche GPS » tant que le GPS n'a pas trouvé sa position (la vitesse vient du GPS)
        boolean gps = App.I.simSpeed >= 0 || App.I.loc.hasFix();
        if (speedLbl != null) U.text(speedLbl, gps ? "Vitesse" : "Vitesse · recherche GPS");
        int s = Math.round(App.I.speed());
        if (s != lastSpeed) {
            lastSpeed = s;
            speedVal.setText(vu(String.valueOf(s), " km/h", 14));
            speedBar.set(s / 130f);
        }
    }

    @Override void refresh() {
        if (root == null) return;
        updatePermChip();
        long now = Clock.now();
        clock.setText(Fmt.hhmm(now));
        if (Clock.suspicious()) {
            long real = Clock.realOrUnknown();
            date.setText(real > 0 ? "Il est " + Fmt.hhmmBrussels(real) + " · touche pour régler"
                    : Clock.wrongZone() ? "Fuseau horaire à régler · touche ici" : "Heure à vérifier · touche pour régler");
            date.setTextColor(Pal.ORANGE_D);
        } else {
            date.setText(Fmt.dayLong(now));
            date.setTextColor(Pal.text2);
        }
        Weather w = App.I.weather;
        if (w.has() && w.fresh()) {
            wIcon.setImageDrawable(U.dr(a, Weather.icon(w.code(), w.isDay()), Pal.text));
            wTemp.setText(w.temp() + "°");
            wLabel.setText(Weather.label(w.code()));
            outVal.setText(vu(String.valueOf(w.temp()), " °C", 14));
            outBar.set((w.temp() + 10) / 50f);
        } else {
            wIcon.setImageDrawable(U.dr(a, R.drawable.ic_globe, Pal.text3));
            wTemp.setText("—");
            wLabel.setText("Météo sans Internet");
            outVal.setText(vu("—", " °C", 14));
            outBar.set(0);
        }
        String cpPkg = App.I.apps.carplayPkg();
        if (!App.I.apps.loaded()) cpStatus.setText("Prêt");
        else if (cpPkg.isEmpty()) cpStatus.setText("Appli à choisir (appui long)");
        else cpStatus.setText(App.I.apps.label(cpPkg) + " · prêt");
        cpDot.setBackground(U.oval(cpPkg.isEmpty() && App.I.apps.loaded() ? 0xFFFCA5A5 : 0xFF86EFAC));
        volumeChanged();
        lastSpeed = -1;
        tick();
        // Raccourcis du bas
        long pt = P.l(P.PARK_TIME, 0);
        if (pt > 0) {
            parkTitle.setText("Garée à " + Fmt.hhmm(pt));
            parkSub.setText(Fmt.dayStart(pt) == Fmt.dayStart(now) ? "Voir où sur la carte" : Fmt.dateShort(pt) + " · voir sur la carte");
        } else {
            parkTitle.setText("Stationnement");
            parkSub.setText("Noté à la fin du trajet");
        }
        ArrayList<TripStore.Trip> today = App.I.trips.since(Fmt.dayStart(now));
        dayTitle.setText(Fmt.km(TripStore.km(today)) + " km · " + Fmt.dur(TripStore.minutes(today)));
        String[] ms = maint.summary();
        maintTitle.setText(ms[0]);
        maintSub.setText(ms[1]);
        if (tab == 1) trips.refresh();
        if (tab == 2) maint.refresh();
    }

    void volumeChanged() {
        if (root == null) return;
        int v = App.I.vol.get(), mx = App.I.vol.max();
        volVal.setText(String.valueOf(v));
        volBar.set(v / (float) mx);
    }

    void doorsChanged() {
        if (car == null) return;
        int m = App.I.doors.mask();
        boolean open = m != 0;
        int d = Doors.main(m);
        car.showDoor(d);
        doorChip.setVisibility(open ? View.VISIBLE : View.GONE);
        updatePermChip();
        topCard.setVisibility(open ? View.VISIBLE : View.GONE);
        arrowL.setVisibility(open ? View.GONE : View.VISIBLE);
        arrowR.setVisibility(open ? View.GONE : View.VISIBLE);
        top.set(m);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) car.getLayoutParams();
        lp.topMargin = U.s(open ? 124 : 104);
        car.setLayoutParams(lp);
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) hint.getLayoutParams();
        hp.topMargin = U.s(open ? 490 : 476);
        hint.setLayoutParams(hp);
        if (open) {
            doorText.setText(Doors.message(m));
            String ex = Doors.count(m) == 1 ? Doors.extra(d) : "";
            doorExtra.setText(ex);
            doorExtra.setVisibility(ex.isEmpty() ? View.GONE : View.VISIBLE);
            int n = Doors.count(m);
            subtitle.setText(n == 1 ? (d == Doors.TRUNK ? "Coffre ouvert" : "1 portière ouverte") : n + " ouvertures");
            subtitle.setTextColor(Pal.ORANGE_D);
            hint.setText("La voiture a tourné toute seule pour montrer l'ouverture");
            if (tab != 0) tab(0);
        } else {
            subtitle.setText(tabSubtitle());
            subtitle.setTextColor(Pal.text2);
            hint.setText("Glisse le doigt pour la faire tourner");
        }
    }

    /** Bandeau « autorisations à donner » : bulle activée mais pas encore autorisée (et aucune porte ouverte). */
    private void updatePermChip() {
        if (permChip == null) return;
        boolean missing = P.bubble() && (!LauncherService.canOverlay(a) || !LauncherService.usageAccess(a));
        permChip.setVisibility(missing && App.I.doors.mask() == 0 ? View.VISIBLE : View.GONE);
    }

    /** Sous-titre sous « Peugeot 208 », selon l'onglet (comme sur les maquettes). */
    private String tabSubtitle() {
        return tab == 1 ? "Distances mesurées par le GPS" : tab == 2 ? "Rappels que tu renseignes toi-même" : "5 portes · prête à rouler";
    }

    // ================= Où est garée la voiture =================
    private void showPark() {
        if (P.l(P.PARK_TIME, 0) == 0) {
            Toast.makeText(a, "La position sera notée à la fin de ton prochain trajet", Toast.LENGTH_LONG).show();
            return;
        }
        if (!MiniMap.available(a)) {
            Toast.makeText(a, "Télécharge d'abord la carte : Réglages → Carte de Belgique", Toast.LENGTH_LONG).show();
            return;
        }
        final MiniMap mm = new MiniMap();
        MapView mv = mm.create(a);
        if (mv == null) {
            Toast.makeText(a, "Carte illisible : " + mm.error, Toast.LENGTH_LONG).show();
            return;
        }
        double la = P.d(P.PARK_LAT), lo = P.d(P.PARK_LON);
        mv.getModel().mapViewPosition.setCenter(new LatLong(la, lo));
        mv.getModel().mapViewPosition.setZoomLevel((byte) 17);
        FrameLayout box = new FrameLayout(a);
        box.setBackground(U.rr(Pal.card, 24));
        box.setClipToOutline(true);
        MiniMap.Frame frame = new MiniMap.Frame(a);
        frame.addView(mv, new FrameLayout.LayoutParams(U.M, U.M));
        box.addView(frame, new FrameLayout.LayoutParams(U.M, U.M));
        ImageView pin = U.icon(a, R.drawable.ic_pin_fill, 44, Pal.BLUE);
        box.addView(pin, U.flm(U.s(44), U.s(44), Gravity.CENTER, 0, 0, 0, 44)); // pointe de l'épingle au centre
        TextView cap = U.tv(a, "Garée à " + Fmt.hhmm(P.l(P.PARK_TIME, 0)) + " · touche pour fermer", 15, Pal.text, 600);
        cap.setBackground(U.rr(Pal.card, 18));
        U.pad(cap, 14, 8, 14, 8);
        box.addView(cap, U.flm(U.WR, U.WR, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(box, new android.view.ViewGroup.LayoutParams(U.s(900), U.s(560)));
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        frame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) { mm.destroy(); }
        });
        d.show();
    }

    @Override void onShow() { refresh(); }
}

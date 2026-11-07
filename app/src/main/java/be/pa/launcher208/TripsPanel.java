package be.pa.launcher208;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/** Onglet « Trajets » : distances mesurées par le GPS. */
final class TripsPanel {
    private final HomeActivity a;
    private final LinearLayout root;
    private final TextView[] big = new TextView[3], small = new TextView[3];
    private final LinearLayout list;
    private final Widgets.Chart chart;

    TripsPanel(HomeActivity a) {
        this.a = a;
        root = U.col(a);
        LinearLayout stats = U.row(a);
        stats.setGravity(0);
        String[] labels = {"Aujourd'hui", "Cette semaine", "Vitesse moyenne"};
        for (int i = 0; i < 3; i++) {
            LinearLayout c = U.col(a);
            U.card(c, Pal.card, 20);
            U.pad(c, 18, 14, 18, 14);
            c.addView(U.tv(a, labels[i], 13, Pal.text2, 500));
            big[i] = U.tv(a, "", 28, Pal.text, 600);
            c.addView(big[i], U.lpm(U.WR, U.WR, 0, 8, 0, 0));
            small[i] = U.tv(a, "", 13, Pal.text2, 400);
            c.addView(small[i], U.lpm(U.WR, U.WR, 0, 6, 0, 0));
            if (i > 0) stats.addView(U.gap(a, 12, 1));
            stats.addView(c, U.lpW(1));
            // hauteur selon le contenu : sinon la rangée (hauteur « selon le contenu ») s'écrase à 1 pixel
            ((LinearLayout.LayoutParams) c.getLayoutParams()).height = U.WR;
        }
        root.addView(stats, U.lp(U.M, U.WR));
        root.addView(U.gap(a, -1, 12));

        LinearLayout bottom = U.row(a);
        bottom.setGravity(0);
        LinearLayout lc = U.col(a);
        U.card(lc, Pal.card, 20);
        U.pad(lc, 18, 14, 18, 8);
        lc.addView(U.tv(a, "Trajets du jour", 14, Pal.text, 600));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        list = U.col(a);
        sv.addView(list);
        lc.addView(sv, U.lpm(U.M, 0, 0, 8, 0, 0));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).weight = 1;
        bottom.addView(lc, U.lpW(1.15f));
        bottom.addView(U.gap(a, 12, 1));

        LinearLayout cc = U.col(a);
        U.card(cc, Pal.card, 20);
        U.pad(cc, 18, 14, 18, 14);
        LinearLayout ch = U.row(a);
        ch.addView(U.tv(a, "7 derniers jours", 14, Pal.text, 600), U.lpW(1));
        ((LinearLayout.LayoutParams) ch.getChildAt(0).getLayoutParams()).height = U.WR;
        ch.addView(U.tv(a, "km par jour", 12, Pal.text2, 400));
        cc.addView(ch);
        chart = new Widgets.Chart(a);
        cc.addView(chart, U.lpm(U.M, 0, 0, 10, 0, 0));
        ((LinearLayout.LayoutParams) chart.getLayoutParams()).weight = 1;
        bottom.addView(cc, U.lpW(1));
        root.addView(bottom, U.lpH(1));
    }

    View view() { return root; }

    void refresh() {
        long now = Clock.now();
        long today = Fmt.dayStart(now);
        ArrayList<TripStore.Trip> d = App.I.trips.since(today);
        ArrayList<TripStore.Trip> w = App.I.trips.since(today - 6L * 86400000L);
        big[0].setText(HomeScreen.vu(Fmt.km(TripStore.km(d)), " km", 14));
        small[0].setText(Fmt.dur(TripStore.minutes(d)) + " de route");
        big[1].setText(HomeScreen.vu(Fmt.km(TripStore.km(w)), " km", 14));
        small[1].setText(Fmt.dur(TripStore.minutes(w)) + " de route");
        long mins = TripStore.minutes(w);
        int avg = mins > 0 ? Math.round(TripStore.km(w) / (mins / 60f)) : 0;
        big[2].setText(HomeScreen.vu(String.valueOf(avg), " km/h", 14));
        small[2].setText("Pointe à " + Math.round(TripStore.max(w)) + " km/h");

        list.removeAllViews();
        if (d.isEmpty()) {
            TextView e = U.tvMulti(a, App.I.loc.permitted() ? "Aucun trajet aujourd'hui pour l'instant.\nIls s'ajoutent tout seuls grâce au GPS."
                    : "Autorise la position (GPS) pour mesurer tes trajets.", 13, Pal.text2, 400, 3);
            list.addView(e, U.lpm(U.M, U.WR, 0, 8, 0, 0));
        }
        for (int i = d.size() - 1; i >= 0; i--) {
            TripStore.Trip t = d.get(i);
            LinearLayout r = U.row(a);
            U.pad(r, 0, 10, 0, 10);
            r.addView(U.box(a, 34, U.oval(Pal.chipB), R.drawable.ic_route, 18, Pal.chipBf));
            LinearLayout c = U.col(a);
            boolean live = App.I.trips.curStart == t.start;
            c.addView(U.tv(a, Fmt.hhmm(t.start) + " → " + (live ? "en cours" : Fmt.hhmm(t.end)), 14, Pal.text, 600));
            c.addView(U.tv(a, moment(t.start), 12, Pal.text2, 400), U.lpm(U.WR, U.WR, 0, 2, 0, 0));
            r.addView(c, U.lpm(0, U.WR, 12, 0, 0, 0));
            ((LinearLayout.LayoutParams) c.getLayoutParams()).weight = 1;
            LinearLayout v = U.col(a);
            v.setGravity(Gravity.END);
            v.addView(U.tv(a, Fmt.km(t.km) + " km", 14, Pal.text, 600), U.lp(U.WR, U.WR)); // largeur explicite (sinon « 12,… »)
            v.addView(U.tv(a, Fmt.dur(t.minutes()), 12, Pal.text2, 400), U.lpm(U.WR, U.WR, 0, 2, 0, 0));
            r.addView(v);
            list.addView(r);
            if (i > 0) list.addView(U.line(a));
        }

        float[] v = App.I.trips.last7(now);
        String[] l = new String[7];
        for (int i = 0; i < 7; i++) l[i] = Fmt.dayLetter(today - (6 - i) * 86400000L + 43200000L);
        chart.set(v, l);
    }

    private static String moment(long t) {
        int m = Fmt.minuteOfDay(t);
        if (m < 11 * 60) return "Matin";
        if (m < 14 * 60) return "Midi";
        if (m < 18 * 60) return "Après-midi";
        return "Soir";
    }
}

package be.pa.launcher208;

import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Onglet « Entretien » : rappels que tu renseignes toi-même. */
final class MaintPanel {
    private static final long DAY = 86400000L;
    private final HomeActivity a;
    private final HomeScreen home;
    private final LinearLayout root;
    private TextView odo, odoSub;
    private final Card ct, oil, tires, custom;

    private static final class Card {
        View v;
        TextView title, left, right;
        Widgets.Bar bar;
    }

    MaintPanel(HomeActivity a, HomeScreen home) {
        this.a = a;
        this.home = home;
        root = U.col(a);

        // Kilométrage
        LinearLayout k = U.row(a);
        U.card(k, Pal.card, 20);
        U.pad(k, 16, 0, 16, 0);
        k.addView(U.box(a, 40, U.oval(Pal.card2), R.drawable.ic_gauge, 22, Pal.text));
        LinearLayout kt = U.col(a);
        kt.addView(U.tv(a, "Kilométrage", 12, Pal.text2, 500));
        odo = U.tv(a, "", 22, Pal.text, 600);
        kt.addView(odo, U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        odoSub = U.tv(a, "", 12, Pal.text2, 400);
        kt.addView(odoSub, U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        k.addView(kt, U.lpm(0, U.WR, 14, 0, 0, 0));
        ((LinearLayout.LayoutParams) kt.getLayoutParams()).weight = 1;
        TextView fix = U.tv(a, "Corriger", 14, Pal.text, 600);
        fix.setGravity(Gravity.CENTER);
        fix.setBackground(U.rr(Pal.card2, 12));
        U.pad(fix, 16, 0, 16, 0);
        U.click(fix, 12, new Runnable() {
            @Override public void run() { askOdo(); }
        });
        k.addView(fix, U.lp(U.WR, U.s(42)));
        root.addView(k, U.lp(U.M, U.s(86)));
        root.addView(U.gap(a, -1, 12));

        ct = card(R.drawable.ic_shield, Pal.chipG, Pal.chipGf, "Contrôle technique", new Runnable() {
            @Override public void run() {
                Dlg.date(a, P.l(P.CT_DATE, 0), new Dlg.LongCb() {
                    @Override public void on(long v) { P.put(P.CT_DATE, v); changed(); }
                });
            }
        });
        oil = card(R.drawable.ic_oil, Pal.chipO, Pal.chipOf, "Vidange et filtres", new Runnable() {
            @Override public void run() {
                Dlg.number(a, "Prochaine vidange", "À combien de kilomètres ? (regarde ton carnet ou l'étiquette du garage)",
                        P.i(P.OIL_KM, 0), new Dlg.LongCb() {
                            @Override public void on(long v) { P.put(P.OIL_KM, (int) v); changed(); }
                        });
            }
        });
        tires = card(R.drawable.ic_tire, Pal.night ? 0xFF2E1A1A : 0xFFFDE2E2, Pal.bad, "Pression des pneus", new Runnable() {
            @Override public void run() {
                a.dialog().setTitle("Pression des pneus")
                        .setMessage("Tu viens de vérifier la pression ? Le rappel repartira pour un mois.")
                        .setPositiveButton("Oui, vérifiée aujourd'hui", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { P.put(P.TIRES_DATE, Clock.now()); changed(); }
                        })
                        .setNegativeButton("Annuler", null).show();
            }
        });
        custom = card(R.drawable.ic_bell, Pal.chipB, Pal.chipBf, "Ajouter un rappel", new Runnable() {
            @Override public void run() {
                Dlg.text(a, "Rappel (ex. : assurance, pneus hiver)", P.s(P.REMIND_TITLE), new Dlg.StrCb() {
                    @Override public void on(final String t) {
                        if (t.isEmpty()) {
                            P.put(P.REMIND_TITLE, "");
                            P.put(P.REMIND_DATE, 0L);
                            changed();
                            return;
                        }
                        Dlg.date(a, P.l(P.REMIND_DATE, 0), new Dlg.LongCb() {
                            @Override public void on(long v) {
                                P.put(P.REMIND_TITLE, t);
                                P.put(P.REMIND_DATE, v);
                                changed();
                            }
                        });
                    }
                });
            }
        });
        LinearLayout r1 = U.row(a);
        r1.setGravity(0);
        r1.addView(ct.v, U.lpW(1));
        r1.addView(U.gap(a, 12, 1));
        r1.addView(oil.v, U.lpW(1));
        LinearLayout r2 = U.row(a);
        r2.setGravity(0);
        r2.addView(tires.v, U.lpW(1));
        r2.addView(U.gap(a, 12, 1));
        r2.addView(custom.v, U.lpW(1));
        root.addView(r1, U.lpH(1));
        root.addView(U.gap(a, -1, 12));
        root.addView(r2, U.lpH(1));
    }

    View view() { return root; }

    private Card card(int icon, int bg, int fg, String title, Runnable click) {
        Card c = new Card();
        LinearLayout l = U.col(a);
        U.card(l, Pal.card, 20);
        U.pad(l, 16, 14, 16, 14);
        LinearLayout h = U.row(a);
        h.addView(U.box(a, 34, U.oval(bg), icon, 18, fg));
        c.title = U.tv(a, title, 14, Pal.text, 600);
        h.addView(c.title, U.lpm(U.WR, U.WR, 10, 0, 0, 0));
        l.addView(h);
        l.addView(U.flexV(a));
        LinearLayout b = U.row(a);
        c.left = U.tv(a, "", 13, Pal.text, 600);
        b.addView(c.left, U.lpW(1));
        ((LinearLayout.LayoutParams) c.left.getLayoutParams()).height = U.WR;
        c.right = U.tv(a, "", 12, Pal.text2, 400);
        b.addView(c.right);
        l.addView(b);
        c.bar = new Widgets.Bar(a, 0, Pal.ok);
        l.addView(c.bar, U.lpm(U.M, U.s(5), 0, 8, 0, 0));
        U.click(l, 20, click);
        c.v = l;
        return c;
    }

    private void changed() { refresh(); home.refresh(); }

    private void askOdo() {
        Dlg.number(a, "Kilométrage", "Recopie le kilométrage affiché sur ton tableau de bord.",
                LocationTracker.odometer(), new Dlg.LongCb() {
                    @Override public void on(long v) {
                        P.put(P.ODO_BASE, (int) v);
                        P.put(P.ODO_GPS_AT, P.f(P.GPS_KM, 0f));
                        changed();
                    }
                });
    }

    private void set(Card c, String left, String right, float frac, int color) {
        c.left.setText(left);
        c.left.setTextColor(color);
        c.right.setText(right);
        c.bar.color = color;
        c.bar.set(frac);
        c.bar.invalidate();
    }

    void refresh() {
        long now = Clock.now();
        long km = LocationTracker.odometer();
        if (km > 0) {
            odo.setText(HomeScreen.vu(Fmt.big(km), " km", 13));
            odoSub.setText("Mis à jour par le GPS depuis ta dernière saisie");
        } else {
            odo.setText("À renseigner");
            odoSub.setText("Touche « Corriger » et recopie ton compteur");
        }

        long ctd = P.l(P.CT_DATE, 0);
        if (ctd > 0) {
            long days = (ctd - now) / DAY;
            int col = days < 0 ? Pal.bad : days <= 30 ? Pal.ORANGE_D : Pal.ok;
            set(ct, days < 0 ? "Dépassé !" : "Dans " + inDays(days), Fmt.dateShort(ctd), 1f - Math.max(0, days) / 365f, col);
        } else set(ct, "Date à choisir", "Touche ici", 0, Pal.text3);

        int oilKm = P.i(P.OIL_KM, 0);
        if (oilKm > 0 && km > 0) {
            long left = oilKm - km;
            int col = left < 0 ? Pal.bad : left <= 1000 ? Pal.ORANGE_D : Pal.ok;
            set(oil, left < 0 ? "Dépassée de " + Fmt.big(-left) + " km" : "Dans " + Fmt.big(left) + " km",
                    "Prévue à " + Fmt.big(oilKm) + " km", 1f - Math.max(0, left) / 15000f, col);
        } else if (oilKm > 0) {
            set(oil, "À " + Fmt.big(oilKm) + " km", "Renseigne le kilométrage", 0, Pal.text3);
        } else set(oil, "Kilométrage à choisir", "Touche ici", 0, Pal.text3);

        long td = P.l(P.TIRES_DATE, 0);
        if (td > 0) {
            long since = (now - td) / DAY;
            int col = since > 30 ? Pal.bad : since > 23 ? Pal.ORANGE_D : Pal.ok;
            set(tires, since > 30 ? "À vérifier" : "Vérifiée il y a " + since + " j", "Tous les mois", Math.min(1f, since / 30f), col);
        } else set(tires, "À vérifier", "Tous les mois", 1f, Pal.bad);

        String rt = P.s(P.REMIND_TITLE);
        long rd = P.l(P.REMIND_DATE, 0);
        if (!rt.isEmpty() && rd > 0) {
            custom.title.setText(rt);
            long days = (rd - now) / DAY;
            int col = days < 0 ? Pal.bad : days <= 14 ? Pal.ORANGE_D : Pal.ok;
            set(custom, days < 0 ? "Dépassé !" : "Dans " + inDays(days), Fmt.dateShort(rd), 1f - Math.max(0, days) / 365f, col);
        } else {
            custom.title.setText("Ajouter un rappel");
            set(custom, "Assurance, pneus hiver…", "", 0, Pal.text3);
        }
    }

    private static String inDays(long d) {
        if (d == 0) return "aujourd'hui";
        if (d < 60) return d + " jour" + (d > 1 ? "s" : "");
        return (d / 30) + " mois";
    }

    /** Résumé pour le raccourci de l'accueil : [titre, détail]. */
    String[] summary() {
        long now = Clock.now();
        long ctd = P.l(P.CT_DATE, 0), rd = P.l(P.REMIND_DATE, 0), td = P.l(P.TIRES_DATE, 0);
        int oilKm = P.i(P.OIL_KM, 0);
        long km = LocationTracker.odometer();
        if (ctd == 0 && oilKm == 0 && rd == 0) return new String[]{"Entretien", "Dates à renseigner"};
        String best = null;
        long bestDays = Long.MAX_VALUE;
        if (ctd > 0) { long d = (ctd - now) / DAY; if (d < bestDays) { bestDays = d; best = "Contrôle technique"; } }
        if (rd > 0) { long d = (rd - now) / DAY; if (d < bestDays) { bestDays = d; best = P.s(P.REMIND_TITLE); } }
        if (td > 0 && (now - td) / DAY > 30) return new String[]{"Entretien", "Pression des pneus à vérifier"};
        if (oilKm > 0 && km > 0 && oilKm - km <= 1000) return new String[]{"Entretien", "Vidange dans " + Fmt.big(Math.max(0, oilKm - km)) + " km"};
        if (best == null) return new String[]{"Entretien", "Vidange à " + Fmt.big(oilKm) + " km"};
        return new String[]{"Entretien", best + (bestDays < 0 ? " : dépassé" : " dans " + inDays(bestDays))};
    }
}

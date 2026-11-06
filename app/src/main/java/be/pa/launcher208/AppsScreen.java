package be.pa.launcher208;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Toutes les applications de l'autoradio, avec des icônes uniformes (ou officielles). */
final class AppsScreen extends Screen {
    private static final int COLS = 7;
    private LinearLayout grid;
    private TextView count;
    private int shownFor = -1;
    private boolean shownOfficial;

    AppsScreen(HomeActivity a) { super(a); }

    @Override View build() {
        LinearLayout card = U.col(a);
        U.card(card, Pal.card, 28);
        U.pad(card, 26, 22, 26, 10);
        LinearLayout head = U.row(a);
        LinearLayout t = U.col(a);
        t.addView(U.tv(a, "Toutes les applications", 24, Pal.text, 700));
        t.addView(U.tv(a, "Touche pour ouvrir · appui long pour les infos de l'appli", 13, Pal.text2, 400), U.lpm(U.WR, U.WR, 0, 4, 0, 0));
        head.addView(t, U.lpW(1));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).height = U.WR;
        count = U.tv(a, "", 13, Pal.text2, 500);
        head.addView(count);
        card.addView(head);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        grid = U.col(a);
        U.pad(grid, 0, 14, 0, 14);
        sv.addView(grid);
        card.addView(sv, U.lpH(1));
        return card;
    }

    @Override void refresh() {
        if (root == null) return;
        List<AppsRepo.AppInfo> l = App.I.apps.list();
        boolean off = P.officialIcons();
        if (l.size() == shownFor && off == shownOfficial && grid.getChildCount() > 0) return;
        shownFor = l.size();
        shownOfficial = off;
        count.setText(App.I.apps.loaded() ? l.size() + " applications" : "Chargement…");
        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < l.size(); i++) {
            if (i % COLS == 0) {
                row = U.row(a);
                row.setGravity(Gravity.TOP);
                grid.addView(row, U.lpm(U.M, U.WR, 0, i == 0 ? 0 : 14, 0, 0));
            }
            row.addView(tile(l.get(i), off), U.lpW(1));
            ((LinearLayout.LayoutParams) row.getChildAt(row.getChildCount() - 1).getLayoutParams()).height = U.WR;
        }
        if (row != null) {
            for (int k = l.size() % COLS; k > 0 && k < COLS; k++) row.addView(U.gap(a, 0, 1), U.lpW(1));
        }
    }

    private View tile(final AppsRepo.AppInfo app, boolean official) {
        LinearLayout c = U.col(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        U.pad(c, 4, 10, 4, 10);
        View icon;
        Drawable od = official ? App.I.apps.officialIcon(app) : null;
        if (od != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(od);
            icon = iv;
        } else {
            int[] col = AppsRepo.colors(app.cat, app.pkg);
            FrameLayout f = U.box(a, 56, U.grad(col[0], col[1], 18), AppsRepo.glyph(app.cat), 28, Pal.WHITE);
            icon = f;
        }
        c.addView(icon, U.lp(U.s(56), U.s(56)));
        TextView t = U.tvMulti(a, app.label, 12, Pal.text, 500, 2);
        t.setGravity(Gravity.CENTER);
        c.addView(t, U.lpm(U.M, U.WR, 0, 8, 0, 0));
        U.click(c, 16, new Runnable() {
            @Override public void run() { App.I.apps.launch(a, app); }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                AppsRepo.appDetails(a, app.pkg);
                return true;
            }
        });
        return c;
    }
}

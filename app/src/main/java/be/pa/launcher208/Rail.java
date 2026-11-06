package be.pa.launcher208;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Barre de gauche : 8 grandes icônes réparties sans espace vide. */
final class Rail {
    private final HomeActivity a;
    private final LinearLayout l;
    private final FrameLayout home, settings, apps;

    Rail(final HomeActivity a) {
        this.a = a;
        l = U.col(a);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        U.card(l, Pal.card, 28);
        U.pad(l, 0, 10, 0, 10);

        home = item(R.drawable.ic_home, null, Pal.railOnFg, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_HOME); if (a.current() == HomeActivity.S_HOME) a.home().tab(0); }
        });
        roleItem(R.drawable.ic_carplay, 0xFF4C8DFF, 0xFF2563EB, P.PKG_CARPLAY, AppsRepo.CARPLAY, "Application CarPlay", new Runnable() {
            @Override public void run() { a.openCarplay(); }
        });
        roleItem(R.drawable.ic_radio, 0xFFFFB547, 0xFFF08A1C, P.PKG_RADIO, AppsRepo.RADIO, "Radio", null);
        roleItem(R.drawable.ic_music, 0xFFA78BFA, 0xFF7C3AED, P.PKG_MUSIC, AppsRepo.MUSIC, "Musique", null);
        roleItem(R.drawable.ic_phone, 0xFF4ADE80, 0xFF16A34A, P.PKG_PHONE, AppsRepo.PHONE, "Téléphone", null);
        roleItem(R.drawable.ic_camera, 0xFF8C98A8, 0xFF4B5563, P.PKG_CAMERA, AppsRepo.CAMERA, "Caméra", null);
        settings = item(R.drawable.ic_sliders, U.rr(Pal.card2, 21), Pal.text, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_SETTINGS); }
        });
        apps = item(R.drawable.ic_grid, U.rr(Pal.card2, 21), Pal.text, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_APPS); }
        });
    }

    View view() { return l; }

    private FrameLayout item(int icon, android.graphics.drawable.Drawable bg, int color, Runnable click) {
        FrameLayout f = U.box(a, 66, bg, icon, 30, color);
        U.click(f, 21, click);
        LinearLayout.LayoutParams p = U.lp(U.s(66), U.s(66));
        // Répartition régulière : chaque icône a la même part de hauteur, sans trou au milieu.
        LinearLayout wrap = new LinearLayout(a);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(f, p);
        l.addView(wrap, U.lpH(1));
        return f;
    }

    private void roleItem(int icon, int c1, int c2, final String key, final int cat, final String title, final Runnable click) {
        FrameLayout f = item(icon, U.grad(c1, c2, 21), Pal.WHITE, click != null ? click : new Runnable() {
            @Override public void run() { a.openRole(key, cat, title); }
        });
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                a.chooseApp(title + " : quelle application ?", key, null);
                return true;
            }
        });
    }

    void select(int screen) {
        set(home, screen == HomeActivity.S_HOME, R.drawable.ic_home);
        set(settings, screen == HomeActivity.S_SETTINGS || screen == HomeActivity.S_CONN || screen == HomeActivity.S_TEST, R.drawable.ic_sliders);
        set(apps, screen == HomeActivity.S_APPS, R.drawable.ic_grid);
    }

    private void set(FrameLayout f, boolean on, int icon) {
        f.setBackground(on ? U.rr(Pal.railOnBg, 21) : (f == home ? null : U.rr(Pal.card2, 21)));
        ImageView iv = (ImageView) f.getChildAt(0);
        iv.setImageDrawable(U.dr(a, icon, on ? Pal.railOnFg : Pal.text));
    }
}

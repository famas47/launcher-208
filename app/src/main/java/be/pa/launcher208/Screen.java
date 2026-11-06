package be.pa.launcher208;

import android.view.View;

/** Un écran du launcher (accueil, réglages, applications...). */
abstract class Screen {
    final HomeActivity a;
    View root;

    Screen(HomeActivity a) { this.a = a; }

    View view() {
        if (root == null) root = build();
        return root;
    }

    abstract View build();

    /** Données qui changent souvent (vitesse...) : appelé chaque seconde quand l'écran est visible. */
    void tick() { }

    /** Données plus lentes (heure, trajets...) : chaque minute et à l'affichage. */
    void refresh() { }

    void onShow() { refresh(); }

    void onHide() { }

    void destroy() { }
}

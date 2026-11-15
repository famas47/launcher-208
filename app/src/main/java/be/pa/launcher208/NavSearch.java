package be.pa.launcher208;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.overlay.FixedPixelCircle;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;

import java.util.ArrayList;
import java.util.Locale;

/**
 * « Aller à » : on tape une adresse, une rue, une ville ou un lieu, on choisit, et le trajet s'affiche
 * (carte, distance, durée, heure d'arrivée) avant de démarrer la navigation. S'affiche par-dessus tout.
 */
final class NavSearch {
    private final HomeActivity a;
    private FrameLayout root, mapHolder;
    private EditText field;
    private LinearLayout list, preview;
    private ScrollView scroll;
    private TextView msg, pvName, pvSub, pvTime, pvInfo, startBtn;
    private int seq;
    private NavData.Place chosen;
    private Router.Route route;
    private MapView pmv;

    NavSearch(HomeActivity a) { this.a = a; }

    View view() {
        if (root == null) root = build();
        return root;
    }

    private FrameLayout build() {
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(Pal.bg);
        f.setClickable(true); // rien ne passe à travers
        LinearLayout col = U.col(a);
        U.pad(col, 16, 16, 16, 16);

        // Barre du haut : retour + champ de recherche
        LinearLayout top = U.row(a);
        FrameLayout back = U.box(a, 64, U.rr(Pal.card, 22), R.drawable.ic_chev_l, 30, Pal.text);
        U.shadow(back, 3);
        U.click(back, 22, new Runnable() {
            @Override public void run() { if (preview.getVisibility() == View.VISIBLE) showSearch(); else close(); }
        });
        back.setContentDescription("Retour");
        top.addView(back, U.lp(U.s(64), U.s(64)));
        LinearLayout box = U.row(a);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(U.rr(Pal.card, 22));
        U.shadow(box, 3);
        U.pad(box, 18, 0, 8, 0);
        box.addView(U.icon(a, R.drawable.ic_search, 26, Pal.text2));
        field = new EditText(a);
        field.setHint("Adresse, rue, ville ou lieu…");
        field.setHintTextColor(Pal.text2);
        field.setTextColor(Pal.text);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, U.f(22));
        field.setTypeface(U.tf(500));
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { search(e.toString()); }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                // touche « Rechercher » du clavier : premier résultat
                if (list.getChildCount() > 0) list.getChildAt(0).performClick();
                return true;
            }
        });
        box.addView(field, U.lpm(0, U.M, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) field.getLayoutParams()).weight = 1;
        FrameLayout clear = new FrameLayout(a);
        TextView x = U.tv(a, "✕", 22, Pal.text2, 500);
        x.setGravity(Gravity.CENTER);
        clear.addView(x, new FrameLayout.LayoutParams(U.M, U.M));
        U.click(clear, 24, new Runnable() {
            @Override public void run() { field.setText(""); showSearch(); keyboard(true); }
        });
        clear.setContentDescription("Effacer");
        box.addView(clear, U.lp(U.s(48), U.s(48)));
        top.addView(box, U.lpm(0, U.s(64), 14, 0, 0, 0));
        ((LinearLayout.LayoutParams) box.getLayoutParams()).weight = 1;
        col.addView(top, U.lp(U.M, U.WR));

        FrameLayout body = new FrameLayout(a);
        // Résultats
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        list = U.col(a);
        scroll.addView(list, new FrameLayout.LayoutParams(U.M, U.WR));
        body.addView(scroll, new FrameLayout.LayoutParams(U.M, U.M));
        msg = U.tvMulti(a, "", 18, Pal.text2, 500, 4);
        msg.setGravity(Gravity.CENTER);
        body.addView(msg, U.flm(U.M, U.WR, Gravity.TOP, 40, 60, 40, 0));
        // Aperçu du trajet
        preview = U.row(a);
        preview.setVisibility(View.GONE);
        mapHolder = new FrameLayout(a);
        mapHolder.setBackground(U.rr(MiniMap.bg(MiniMap.night()), 24));
        mapHolder.setClipToOutline(true);
        U.shadow(mapHolder, 4);
        preview.addView(mapHolder, U.lpW(1.45f));
        LinearLayout card = U.col(a);
        U.card(card, Pal.card, 24);
        U.shadow(card, 4);
        U.pad(card, 26, 24, 26, 24);
        pvName = U.tvMulti(a, "", 26, Pal.text, 700, 2);
        card.addView(pvName);
        pvSub = U.tvMulti(a, "", 16, Pal.text2, 500, 2);
        card.addView(pvSub, U.lpm(U.WR, U.WR, 0, 4, 0, 0));
        card.addView(U.flexV(a));
        pvTime = U.tv(a, "", 48, Pal.text, 600);
        card.addView(pvTime);
        pvInfo = U.tvMulti(a, "", 18, Pal.text2, 500, 2);
        card.addView(pvInfo, U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        card.addView(U.flexV(a));
        startBtn = U.tv(a, "Démarrer", 26, Pal.WHITE, 700);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(U.grad(0xFF4C8DFF, 0xFF2563EB, 22));
        U.shadow(startBtn, 6);
        U.click(startBtn, 22, new Runnable() {
            @Override public void run() { start(); }
        });
        card.addView(startBtn, U.lp(U.M, U.s(84)));
        preview.addView(U.gap(a, 16, 1));
        preview.addView(card, U.lp(U.s(420), U.M));
        body.addView(preview, new FrameLayout.LayoutParams(U.M, U.M));
        col.addView(body, U.lpm(U.M, 0, 0, 16, 0, 0));
        ((LinearLayout.LayoutParams) body.getLayoutParams()).weight = 1;
        f.addView(col, new FrameLayout.LayoutParams(U.M, U.M));
        return f;
    }

    // ---------- Affichage ----------

    void onShow() {
        showSearch();
        if (field.getText().length() == 0) showRecents();
        else search(field.getText().toString());
        field.requestFocus();
        field.postDelayed(new Runnable() {
            @Override public void run() { keyboard(true); }
        }, 150);
    }

    private void keyboard(boolean show) {
        InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im == null) return;
        if (show) { field.requestFocus(); im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT); }
        else im.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    void close() {
        keyboard(false);
        destroyPreview();
        a.closeNav();
    }

    void destroy() { destroyPreview(); }

    private void showSearch() {
        destroyPreview();
        preview.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        if (list.getChildCount() == 0 && msg.getText().length() == 0) showRecents();
    }

    private void message(String m) {
        msg.setText(m);
        msg.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showRecents() {
        list.removeAllViews();
        if (!NavData.available(a)) {
            message("Les données de navigation ne sont pas encore téléchargées.\nRéglages → Navigation (Belgique) → Télécharger (Wi-Fi).");
            return;
        }
        ArrayList<NavData.Place> rec = NavData.recents();
        message(rec.isEmpty() ? "Tape une adresse (« rue de nimy 15 mons »), une ville ou un lieu (« gare charleroi », « pharmacie »)." : "");
        if (!rec.isEmpty()) {
            TextView h = U.tv(a, "Dernières destinations", 15, Pal.text2, 600);
            list.addView(h, U.lpm(U.WR, U.WR, 8, 6, 0, 8));
        }
        for (final NavData.Place p : rec) {
            list.addView(row(R.drawable.ic_clock, p.name, p.sub, km(p.lat, p.lon), new Runnable() {
                @Override public void run() { choose(p); }
            }));
        }
    }

    private static String km(double lat, double lon) {
        LocationTracker l = App.I.loc;
        double la = l.lat != 0 ? l.lat : P.lat(), lo = l.lon != 0 ? l.lon : P.lon();
        double d = Math.hypot((lon - lo) * 111320.0 * Math.cos(Math.toRadians(la)), (lat - la) * 110540.0) / 1000.0;
        return d < 10 ? String.format(Locale.FRANCE, "%.1f km", d) : Math.round(d) + " km";
    }

    private View row(int icon, String name, String sub, String dist, Runnable click) {
        LinearLayout r = U.row(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        U.card(r, Pal.card, 20);
        U.pad(r, 16, 12, 20, 12);
        r.addView(U.box(a, 48, U.rr(Pal.card2, 14), icon, 26, Pal.accent));
        LinearLayout t = U.col(a);
        TextView n = U.tv(a, name, 19, Pal.text, 600);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(n);
        TextView s = U.tv(a, sub, 15, Pal.text2, 400);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(s, U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        r.addView(t, U.lpm(0, U.WR, 14, 0, 12, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1;
        r.addView(U.tv(a, dist, 16, Pal.text2, 500));
        U.click(r, 20, click);
        LinearLayout wrap = U.col(a);
        wrap.addView(r, U.lpm(U.M, U.WR, 0, 0, 0, 10));
        return wrap;
    }

    // ---------- Recherche ----------

    private void search(final String q) {
        final int my = ++seq;
        if (q.trim().isEmpty()) { showRecents(); return; }
        if (!NavData.available(a)) { showRecents(); return; }
        if (preview.getVisibility() == View.VISIBLE) showSearch();
        final LocationTracker l = App.I.loc;
        final double la = l.lat != 0 ? l.lat : P.lat(), lo = l.lon != 0 ? l.lon : P.lon();
        App.I.nav.worker.execute(new Runnable() {
            @Override public void run() {
                if (my != seq) return; // déjà dépassé par une frappe plus récente
                AddrIndex ix = App.I.navData.index();
                final ArrayList<AddrIndex.Hit> hits = ix == null ? null : ix.search(q, la, lo, 12);
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        if (my != seq || root == null) return;
                        list.removeAllViews();
                        if (hits == null) { message("Données de navigation illisibles : " + App.I.navData.error()); return; }
                        message(hits.isEmpty() ? "Rien trouvé. Essaie avec moins de mots, ou le nom de la ville." : "");
                        for (final AddrIndex.Hit h : hits) {
                            int ic = h.type == AddrIndex.PLACE ? R.drawable.ic_map : h.type == AddrIndex.STREET ? R.drawable.ic_pin : R.drawable.ic_pin_fill;
                            list.addView(row(ic, h.name, h.sub, h.km < 10 ? String.format(Locale.FRANCE, "%.1f km", h.km) : Math.round(h.km) + " km", new Runnable() {
                                @Override public void run() { choose(new NavData.Place(h.name, h.sub, h.lat, h.lon)); }
                            }));
                        }
                        scroll.scrollTo(0, 0);
                        if (BuildConfig.TEST_HOOKS) {
                            StringBuilder sb = new StringBuilder();
                            for (AddrIndex.Hit h : hits) sb.append(" | ").append(h.name).append(" — ").append(h.sub);
                            android.util.Log.i("Test208", "recherche « " + q + " » :" + sb);
                        }
                    }
                });
            }
        });
    }

    /** Destination choisie : calcul du trajet depuis la position actuelle, puis aperçu. */
    void choose(final NavData.Place p) {
        keyboard(false);
        list.removeAllViews();
        message("Calcul du trajet vers " + p.name + "…");
        final int my = ++seq;
        final LocationTracker l = App.I.loc;
        final double la = l.lat != 0 ? l.lat : P.lat(), lo = l.lon != 0 ? l.lon : P.lon();
        final double head = l.speedKmh > 5 ? l.bearing : Double.NaN;
        App.I.nav.worker.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                Router.Route r = null;
                long t0 = System.nanoTime();
                try {
                    Router ro = App.I.navData.router();
                    if (ro == null) err = "Données de navigation illisibles : " + App.I.navData.error();
                    else {
                        Router.Snap from = ro.snap(la, lo, 300, head), to = ro.snap(p.lat, p.lon, 600, Double.NaN);
                        if (from == null) err = "Aucune route près de ta position.";
                        else if (to == null) err = "Aucune route près de cette destination.";
                        else {
                            r = ro.route(from, to, head);
                            if (r == null) err = "Aucun trajet en voiture trouvé jusque-là.";
                        }
                    }
                } catch (Throwable t) {
                    err = "Erreur : " + t;
                }
                final String e = err;
                final Router.Route res = r;
                final double ms = (System.nanoTime() - t0) / 1e6;
                App.I.main.post(new Runnable() {
                    @Override public void run() {
                        if (my != seq || root == null) return;
                        if (BuildConfig.TEST_HOOKS) {
                            android.util.Log.i("Test208", String.format(Locale.ROOT, "trajet vers %s : %s, %.0f ms",
                                    p.name, res == null ? e : String.format(Locale.ROOT, "%.1f km, %.0f min, %d tronçons",
                                            res.meters / 1000, res.seconds / 60, res.edges.length), ms));
                        }
                        if (res == null) { message(e); return; }
                        showPreview(p, res);
                    }
                });
            }
        });
    }

    private void showPreview(NavData.Place p, Router.Route r) {
        chosen = p;
        route = r;
        message("");
        scroll.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        pvName.setText(p.name);
        pvSub.setText(p.sub);
        pvTime.setText(Fmt.dur(Math.max(1, Math.round(r.seconds / 60))));
        pvInfo.setText(dist(r.meters) + " · arrivée " + Fmt.hhmm(Clock.now() + Math.round(r.seconds * 1000)));
        mapHolder.post(new Runnable() {
            @Override public void run() { buildPreview(); }
        });
    }

    static String dist(double m) {
        if (m < 950) return (Math.round(m / 10) * 10) + " m";
        double km = m / 1000;
        return km < 10 ? String.format(Locale.FRANCE, "%.1f km", km) : Math.round(km) + " km";
    }

    private void start() {
        if (chosen == null || route == null) return;
        App.I.nav.start(chosen, route);
        close();
        a.showNavigation();
    }

    // ---------- Tests automatiques ----------
    void testType(String q) { field.setText(q); field.setSelection(q.length()); }

    void testPick(int i) {
        // les rangées sont dans une enveloppe ; le titre « Dernières destinations » n'en est pas une
        int k = 0;
        for (int c = 0; c < list.getChildCount(); c++) {
            View v = list.getChildAt(c);
            if (!(v instanceof LinearLayout)) continue;
            if (k++ == i) { ((LinearLayout) v).getChildAt(0).performClick(); return; }
        }
    }

    void testStart() { if (preview.getVisibility() == View.VISIBLE) startBtn.performClick(); }

    // ---------- Petite carte de l'aperçu ----------

    private void buildPreview() {
        destroyPreview();
        if (route == null || !MiniMap.available(a) || mapHolder.getWidth() <= 0) return;
        try {
            MiniMap.ensureGraphics();
            boolean night = MiniMap.night();
            MapView mv = new MapView(a);
            mv.setClickable(false);
            mv.getMapScaleBar().setVisible(false);
            mv.setBuiltInZoomControls(false);
            mv.getModel().displayModel.setBackgroundColor(MiniMap.bg(night));
            InMemoryTileCache cache = new InMemoryTileCache(AndroidUtil.getMinimumCacheSize(a,
                    mv.getModel().displayModel.getTileSize(), mv.getModel().frameBufferModel.getOverdrawFactor(), 1f));
            TileRendererLayer layer = new TileRendererLayer(cache, new MapFile(MiniMap.file(a), "fr"),
                    mv.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
            layer.setXmlRenderTheme(new AssetsRenderTheme(a.getAssets(), "map/", night ? "theme-nuit.xml" : "theme.xml"));
            mv.getLayerManager().getLayers().add(layer);
            ArrayList<LatLong> pts = new ArrayList<>(route.lat.length);
            for (int i = 0; i < route.lat.length; i++) pts.add(new LatLong(route.lat[i], route.lon[i]));
            Polyline line = new Polyline(MiniMap.paint(night ? 0xF04C9BFF : 0xE82F7BF6, U.f(7), true), AndroidGraphicFactory.INSTANCE);
            line.addPoints(pts);
            mv.getLayerManager().getLayers().add(line);
            mv.getLayerManager().getLayers().add(new FixedPixelCircle(pts.get(0), U.f(8),
                    MiniMap.paint(0xFF2563EB, 0, false), MiniMap.paint(0xFFFFFFFF, U.f(3), true)));
            mv.getLayerManager().getLayers().add(new FixedPixelCircle(pts.get(pts.size() - 1), U.f(9),
                    MiniMap.paint(0xFFE5484D, 0, false), MiniMap.paint(0xFFFFFFFF, U.f(3.5f), true)));
            int w = mapHolder.getWidth(), h = mapHolder.getHeight();
            BoundingBox bb = new BoundingBox(pts);
            double pl = Math.max(0.002, (bb.maxLatitude - bb.minLatitude) * 0.12), po = Math.max(0.003, (bb.maxLongitude - bb.minLongitude) * 0.12);
            bb = new BoundingBox(bb.minLatitude - pl, bb.minLongitude - po, Math.min(85, bb.maxLatitude + pl), bb.maxLongitude + po);
            byte z = LatLongUtils.zoomForBounds(new Dimension(w, h), bb, mv.getModel().displayModel.getTileSize());
            z = (byte) Math.max(8, Math.min(17, z));
            mv.setZoomLevelMin((byte) 7);
            mv.setZoomLevelMax((byte) 18);
            mv.getModel().mapViewPosition.setMapPosition(new MapPosition(bb.getCenterPoint(), z));
            mapHolder.addView(mv, new FrameLayout.LayoutParams(U.M, U.M));
            TextView osm = U.tv(a, "© OpenStreetMap", 11, Pal.text2, 400);
            osm.setBackground(U.rr(Pal.night ? 0xD916181C : 0xD9FFFFFF, 6));
            U.pad(osm, 7, 2, 7, 2);
            mapHolder.addView(osm, U.flm(U.WR, U.WR, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 8));
            pmv = mv;
        } catch (Throwable t) {
            pmv = null;
        }
    }

    private void destroyPreview() {
        if (pmv != null) {
            try { pmv.destroyAll(); } catch (Throwable ignored) { }
            pmv = null;
        }
        if (mapHolder != null) mapHolder.removeAllViews();
    }
}

package be.pa.launcher208;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Réglages du launcher (les réglages de l'autoradio restent dans ses écrans d'origine). */
final class SettingsScreen extends Screen {
    private TextView phoneVal, cpApp, radioApp, musicApp, phoneApp, camApp, retVal, afterVal, kmhVal, odoVal, volVal, clockVal, nightRange, mapStatus, updStatus, version;
    private TextView mapBtn, updBtn;
    private View nightRangeRow;

    SettingsScreen(HomeActivity a) { super(a); }

    @Override View build() {
        LinearLayout outer = U.col(a);
        U.card(outer, Pal.night ? 0xFF111316 : 0xFFF3F5F7, 28);

        LinearLayout head = U.row(a);
        U.pad(head, 26, 20, 22, 8);
        head.addView(U.tv(a, "Réglages du launcher", 24, Pal.text, 700), U.lpW(1));
        ((LinearLayout.LayoutParams) head.getChildAt(0).getLayoutParams()).height = U.WR;
        LinearLayout link = U.row(a);
        link.setBackground(U.rr(Pal.card, 18));
        U.shadow(link, 3);
        U.pad(link, 14, 0, 12, 0);
        link.addView(U.icon(a, R.drawable.ic_wifi, 18, Pal.accent));
        link.addView(U.tv(a, "Wi-Fi, Bluetooth et autoradio", 14, Pal.text, 600), U.lpm(U.WR, U.WR, 8, 0, 6, 0));
        link.addView(U.icon(a, R.drawable.ic_chev_r, 16, Pal.text2));
        U.click(link, 18, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_CONN); }
        });
        head.addView(link, U.lp(U.WR, U.s(40)));
        outer.addView(head);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cols = U.row(a);
        cols.setGravity(Gravity.TOP);
        U.pad(cols, 18, 6, 18, 18);
        LinearLayout c1 = U.col(a), c2 = U.col(a);
        cols.addView(c1, new LinearLayout.LayoutParams(0, U.WR, 1));
        cols.addView(U.gap(a, 16, 1));
        cols.addView(c2, new LinearLayout.LayoutParams(0, U.WR, 1));
        sv.addView(cols);
        outer.addView(sv, U.lpH(1));

        // ----- Colonne 1 -----
        LinearLayout s = section(c1, "Affichage");
        add(s, row("Thème", "Auto : suit le réglage ci-dessous", Widgets.seg(a, new String[]{"Auto", "Jour", "Nuit"}, P.theme(), new Widgets.IntCb() {
            @Override public void on(int v) { P.put(P.THEME, v); a.rebuildForSettings(); }
        })), true);
        add(s, row("Passage en mode nuit", "Interface sombre", Widgets.seg(a, new String[]{"Phares", "Soleil", "Heures"}, P.nightBy(), new Widgets.IntCb() {
            @Override public void on(int v) { P.put(P.NIGHT_BY, v); a.rebuildForSettings(); }
        })), true);
        nightRange = U.tv(a, "", 15, Pal.text, 600);
        // La ligne de séparation est dans le même bloc : elle disparaît avec la rangée (pas de double trait)
        LinearLayout nightBox = U.col(a);
        nightBox.addView(row("Plage de nuit", "Touche pour changer", valueBtn(nightRange, new Runnable() {
            @Override public void run() { pickNightRange(); }
        })), U.lp(U.M, U.WR));
        nightBox.addView(U.line(a));
        nightRangeRow = nightBox;
        add(s, nightBox, false);
        add(s, row("Fluidité", "Images par seconde", Widgets.seg(a, new String[]{"Max", "30"}, P.i(P.FLUID, 0), new Widgets.IntCb() {
            @Override public void on(int v) { P.put(P.FLUID, v); }
        })), true);
        add(s, row("Icônes des applications", "Uniformes ou celles des applis", Widgets.seg(a, new String[]{"Uniformes", "Officielles"}, P.i(P.ICONS, 0), new Widgets.IntCb() {
            @Override public void on(int v) { P.put(P.ICONS, v); }
        })), true);
        add(s, row("Plein écran", "Cache les barres d'Android", new Widgets.Toggle(a, P.fullscreen(), new Widgets.BoolCb() {
            @Override public void on(boolean v) { P.put(P.FULLSCREEN, v); a.rebuildForSettings(); }
        })), false);

        s = section(c1, "CarPlay");
        cpApp = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Application CarPlay", "Celle qui affiche CarPlay", valueBtn(cpApp, chooser("Application CarPlay", P.PKG_CARPLAY))), true);
        add(s, row("Ouvrir CarPlay tout seul", "« iPhone » : comme le logiciel d'origine",
                Widgets.seg(a, new String[]{"Non", "Au démarrage", "iPhone"}, P.carplayAuto(), new Widgets.IntCb() {
            @Override public void on(int v) {
                P.put(P.CARPLAY_AUTO, v);
                if (v == PhoneWatch.AUTO_PHONE && !PhoneWatch.permitted(a)) askBluetooth();
            }
        })), true);
        phoneVal = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Ton iPhone", "Celui qui ouvre CarPlay en se connectant", valueBtn(phoneVal, new Runnable() {
            @Override public void run() { pickPhone(); }
        })), true);
        LinearLayout bubbleRight = U.row(a);
        TextView replace = U.tv(a, "Replacer la bulle", 14, Pal.text, 600);
        replace.setGravity(Gravity.CENTER);
        replace.setBackground(U.rr(Pal.card2, 11));
        U.pad(replace, 12, 0, 12, 0);
        U.click(replace, 11, new Runnable() {
            @Override public void run() {
                P.put(P.BUBBLE_X, -1);
                P.put(P.BUBBLE_Y, -1);
                if (LauncherService.I != null) LauncherService.I.resetBubble();
                android.widget.Toast.makeText(a, "Bulle remise en haut à droite", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        bubbleRight.addView(replace, U.lp(U.WR, U.s(38)));
        bubbleRight.addView(U.gap(a, 10, 1));
        bubbleRight.addView(new Widgets.Toggle(a, P.bubble(), new Widgets.BoolCb() {
            @Override public void on(boolean v) { P.put(P.BUBBLE, v); }
        }));
        add(s, row("Allumer le Bluetooth", "Au démarrage, pour que ton iPhone se connecte", new Widgets.Toggle(a, P.btAuto(), new Widgets.BoolCb() {
            @Override public void on(boolean v) { P.put(P.BT_AUTO, v); }
        })), true);
        add(s, row("Bulle volume", "Sur CarPlay, à glisser où tu veux", bubbleRight), false);

        s = section(c1, "Volume");
        add(s, row("Façon de régler le son", "Choisis celle qui change vraiment le son",
                Widgets.seg(a, Volume.NAMES, P.volMethod(), new Widgets.IntCb() {
            @Override public void on(int v) { P.put(P.VOL_METHOD, v); }
        })), true);
        LinearLayout tryBtns = U.row(a);
        TextView minus = actionBtn("−", Pal.card2, Pal.text, new Runnable() {
            @Override public void run() { App.I.vol.step(-2); }
        });
        TextView plus = actionBtn("+", Pal.card2, Pal.text, new Runnable() {
            @Override public void run() { App.I.vol.step(2); }
        });
        minus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, U.f(20));
        plus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, U.f(20));
        minus.setMinWidth(U.s(64));
        plus.setMinWidth(U.s(64));
        tryBtns.addView(minus, U.lp(U.WR, U.s(42)));
        tryBtns.addView(U.gap(a, 10, 1));
        tryBtns.addView(plus, U.lp(U.WR, U.s(42)));
        add(s, row("Essayer", "Mets de la musique et écoute", tryBtns), false);

        s = section(c1, "Applications des raccourcis");
        radioApp = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Radio", "Bouton orange", valueBtn(radioApp, chooser("Radio", P.PKG_RADIO))), true);
        musicApp = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Musique", "Bouton violet", valueBtn(musicApp, chooser("Musique", P.PKG_MUSIC))), true);
        phoneApp = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Téléphone", "Bouton vert", valueBtn(phoneApp, chooser("Téléphone", P.PKG_PHONE))), true);
        camApp = U.tv(a, "", 14, Pal.text2, 500);
        add(s, row("Caméra", "Bouton gris", valueBtn(camApp, chooser("Caméra", P.PKG_CAMERA))), false);

        s = section(c1, "À propos");
        version = U.tv(a, "", 13, Pal.text, 500);
        s.addView(version, U.lpm(U.WR, U.WR, 0, 4, 0, 0));
        TextView cred = U.tvMulti(a, "Modèle 3D « Peugeot 208 2017 » par luizantonix, licence CC BY-NC-SA · "
                + "Carte © les contributeurs d'OpenStreetMap · Moteur de carte Mapsforge (LGPL) · Météo Open-Meteo", 12, Pal.text2, 400, 4);
        s.addView(cred, U.lpm(U.M, U.WR, 0, 4, 0, 8));
        updStatus = U.tvMulti(a, "", 12, Pal.text2, 400, 2);
        s.addView(updStatus, U.lpm(U.M, U.WR, 0, 0, 0, 8));
        LinearLayout btns = U.row(a);
        updBtn = actionBtn("Mise à jour", Pal.BLUE, Pal.WHITE, new Runnable() {
            @Override public void run() {
                if (App.I.upd.available()) App.I.upd.install(a); else App.I.upd.check(a);
            }
        });
        btns.addView(updBtn, U.lp(U.WR, U.s(42)));
        btns.addView(U.gap(a, 10, 1));
        btns.addView(actionBtn("Mode test", Pal.card2, Pal.text, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_TEST); }
        }), U.lp(U.WR, U.s(42)));
        s.addView(btns, U.lpm(U.WR, U.WR, 0, 0, 0, 10));

        // ----- Colonne 2 -----
        s = section(c2, "Mode conduite");
        add(s, row("Mode conduite", "Par la vitesse ou le temps choisis ci-dessous", new Widgets.Toggle(a, P.drive(), new Widgets.BoolCb() {
            @Override public void on(boolean v) { P.put(P.DRIVE, v); }
        })), true);
        kmhVal = U.tv(a, "", 15, Pal.text, 600);
        add(s, row("S'active dès", "Vitesse de la voiture", valueBtn(kmhVal, new Runnable() {
            @Override public void run() {
                int[] opts = {1, 2, 3, 5, 10};
                int cur = P.driveKmh(), next = opts[0];
                for (int i = 0; i < opts.length; i++) if (opts[i] == cur) next = opts[(i + 1) % opts.length];
                P.put(P.DRIVE_KMH, next);
                refresh();
            }
        })), true);
        afterVal = U.tv(a, "", 15, Pal.text, 600);
        add(s, row("Activer après le contact", "Même si la vitesse n'arrive pas", valueBtn(afterVal, new Runnable() {
            @Override public void run() {
                int[] opts = {0, 1, 2, 3, 5, 10};
                int cur = P.driveAfter(), next = opts[1];
                for (int i = 0; i < opts.length; i++) if (opts[i] == cur) next = opts[(i + 1) % opts.length];
                P.put(P.DRIVE_AFTER, next);
                refresh();
            }
        })), true);
        retVal = U.tv(a, "", 15, Pal.text, 600);
        add(s, row("Retour à l'accueil", "Après un arrêt de", valueBtn(retVal, new Runnable() {
            @Override public void run() {
                int[] opts = {30, 60, 120, 300};
                int cur = P.driveReturn(), next = opts[0];
                for (int i = 0; i < opts.length; i++) if (opts[i] == cur) next = opts[(i + 1) % opts.length];
                P.put(P.DRIVE_RETURN, next);
                refresh();
            }
        })), true);
        add(s, row("Minimap", "Carte de la Belgique hors ligne", new Widgets.Toggle(a, P.minimap(), new Widgets.BoolCb() {
            @Override public void on(boolean v) { P.put(P.MINIMAP, v); }
        })), false);

        s = section(c2, "Carte de Belgique");
        mapStatus = U.tvMulti(a, "", 13, Pal.text2, 400, 3);
        s.addView(mapStatus, U.lpm(U.M, U.WR, 0, 6, 0, 10));
        LinearLayout mb = U.row(a);
        mapBtn = actionBtn("Télécharger (Wi-Fi)", Pal.BLUE, Pal.WHITE, new Runnable() {
            @Override public void run() { mapAction(); }
        });
        mb.addView(mapBtn, U.lp(U.WR, U.s(42)));
        mb.addView(U.gap(a, 10, 1));
        mb.addView(actionBtn("Clé USB", Pal.card2, Pal.text, new Runnable() {
            @Override public void run() { a.pickMapFile(); }
        }), U.lp(U.WR, U.s(42)));
        s.addView(mb, U.lpm(U.WR, U.WR, 0, 0, 0, 10));

        s = section(c2, "Heure et voiture");
        clockVal = U.tv(a, "", 14, Pal.text, 600);
        add(s, row("Heure", "Celle de l'autoradio (reprise par le tableau de bord)", valueBtn(clockVal, new Runnable() {
            @Override public void run() { AppsRepo.openSettings(a, android.provider.Settings.ACTION_DATE_SETTINGS); }
        })), true);
        odoVal = U.tv(a, "", 15, Pal.text, 600);
        add(s, row("Kilométrage", "Pour les rappels d'entretien", valueBtn(odoVal, new Runnable() {
            @Override public void run() {
                Dlg.number(a, "Kilométrage", "Recopie le kilométrage de ton tableau de bord.", LocationTracker.odometer(), new Dlg.LongCb() {
                    @Override public void on(long v) {
                        P.put(P.ODO_BASE, (int) v);
                        P.put(P.ODO_GPS_AT, P.f(P.GPS_KM, 0f));
                        refresh();
                    }
                });
            }
        })), true);
        volVal = U.tv(a, "", 15, Pal.text, 600);
        add(s, row("Volume au démarrage", "Reprend le dernier, sans dépasser", valueBtn(volVal, new Runnable() {
            @Override public void run() {
                Dlg.number(a, "Volume maximum au démarrage", "Entre 0 et " + App.I.vol.max(), P.volStartMax(), new Dlg.LongCb() {
                    @Override public void on(long v) { P.put(P.VOL_START_MAX, (int) Math.min(v, App.I.vol.max())); refresh(); }
                });
            }
        })), false);

        LinearLayout orig = U.row(a);
        orig.setBackground(U.rrStroke(Pal.warnBg, 20, 0xFFD97706, 2));
        U.pad(orig, 18, 14, 18, 14);
        LinearLayout ot = U.col(a);
        ot.addView(U.tv(a, "Revenir à l'écran d'origine", 15, Pal.text, 700));
        ot.addView(U.tvMulti(a, "Rien n'est effacé, tu peux revenir ici quand tu veux", 12, Pal.text2, 400, 2), U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        orig.addView(ot, U.lpW(1));
        ((LinearLayout.LayoutParams) ot.getLayoutParams()).height = U.WR;
        orig.addView(actionBtn("Ouvrir", 0xFFD97706, Pal.WHITE, new Runnable() {
            @Override public void run() { a.originalHome(); }
        }), U.lp(U.WR, U.s(48)));
        c2.addView(orig, U.lpm(U.M, U.WR, 0, 0, 0, 12));

        App.I.mapdl.onProgress = new Runnable() {
            @Override public void run() { refresh(); }
        };
        App.I.upd.onChange = new Runnable() {
            @Override public void run() { refresh(); }
        };
        return outer;
    }

    // ---------- Petits constructeurs ----------
    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout c = U.col(a);
        U.card(c, Pal.card, 20);
        U.pad(c, 18, 12, 18, 2);
        TextView t = U.tv(a, title.toUpperCase(Fmt.FR), 12, Pal.text2, 600);
        U.spacing(t, 0.4f, 12);
        c.addView(t, U.lpm(U.WR, U.WR, 0, 0, 0, 2));
        parent.addView(c, U.lpm(U.M, U.WR, 0, 0, 0, 12));
        return c;
    }

    private View row(String title, String sub, View right) {
        LinearLayout r = U.row(a);
        r.setMinimumHeight(U.s(56));
        LinearLayout t = U.col(a);
        t.addView(U.tv(a, title, 15, Pal.text, 600));
        if (sub != null) t.addView(U.tv(a, sub, 12, Pal.text2, 400), U.lpm(U.WR, U.WR, 0, 2, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, U.WR, 1));
        r.addView(right, right.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? right.getLayoutParams() : U.lp(U.WR, U.WR));
        return r;
    }

    private void add(LinearLayout section, View row, boolean line) {
        section.addView(row, U.lp(U.M, U.WR));
        if (line) section.addView(U.line(a));
    }

    private void askBluetooth() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            a.requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, HomeActivity.REQ_BT);
        }
    }

    /** Choix du téléphone parmi les appareils Bluetooth jumelés à l'autoradio. */
    private void pickPhone() {
        if (!PhoneWatch.permitted(a)) {
            android.widget.Toast.makeText(a, "Autorise « Appareils à proximité » pour voir ton iPhone", android.widget.Toast.LENGTH_LONG).show();
            askBluetooth();
            return;
        }
        final java.util.ArrayList<String[]> l = PhoneWatch.bonded();
        if (l.isEmpty()) {
            android.widget.Toast.makeText(a, "Android ne voit aucun appareil Bluetooth jumelé (voir Mode test)", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[l.size() + 1];
        items[0] = "N'importe quel iPhone (automatique)";
        for (int i = 0; i < l.size(); i++) items[i + 1] = l.get(i)[1];
        a.dialog().setTitle("Ton iPhone").setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                if (w == 0) { P.put(P.BT_PHONE, ""); P.put(P.BT_PHONE_NAME, ""); }
                else { P.put(P.BT_PHONE, l.get(w - 1)[0]); P.put(P.BT_PHONE_NAME, l.get(w - 1)[1]); }
                refresh();
            }
        }).setNegativeButton("Annuler", null).show();
    }

    private View valueBtn(TextView t, Runnable click) {
        t.setGravity(Gravity.CENTER);
        t.setBackground(U.rr(Pal.card2, 13));
        U.pad(t, 16, 0, 16, 0);
        U.click(t, 13, click);
        t.setLayoutParams(U.lp(U.WR, U.s(42)));
        return t;
    }

    private TextView actionBtn(String label, int bg, int fg, Runnable click) {
        TextView t = U.tv(a, label, 14, fg, 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(U.rr(bg, 13));
        U.pad(t, 16, 0, 16, 0);
        U.click(t, 13, click);
        return t;
    }

    private Runnable chooser(final String title, final String key) {
        return new Runnable() {
            @Override public void run() {
                a.chooseApp(title, key, new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        };
    }

    private void pickNightRange() {
        Dlg.time(a, P.i(P.NIGHT_FROM, 20 * 60), new Dlg.LongCb() {
            @Override public void on(final long from) {
                android.widget.Toast.makeText(a, "Et maintenant, l'heure de fin de la nuit", android.widget.Toast.LENGTH_SHORT).show();
                Dlg.time(a, P.i(P.NIGHT_TO, 7 * 60), new Dlg.LongCb() {
                    @Override public void on(long to) {
                        P.put(P.NIGHT_FROM, (int) from);
                        P.put(P.NIGHT_TO, (int) to);
                        a.rebuildForSettings();
                    }
                });
            }
        });
    }

    private void mapAction() {
        MapDl d = App.I.mapdl;
        if (d.running) { d.cancel(); return; }
        if (!Weather.online(a)) {
            android.widget.Toast.makeText(a, "Pas d'Internet : connecte l'autoradio au Wi-Fi d'abord", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        d.download(a);
    }

    private String appName(String key, int cat) {
        String p = App.I.apps.pkgFor(key, cat);
        return p.isEmpty() ? "À choisir" : App.I.apps.label(p);
    }

    @Override void refresh() {
        if (root == null) return;
        String ph = P.s(P.BT_PHONE_NAME);
        phoneVal.setText(ph.isEmpty() ? "Tout iPhone" : ph);
        cpApp.setText(appName(P.PKG_CARPLAY, AppsRepo.CARPLAY));
        radioApp.setText(appName(P.PKG_RADIO, AppsRepo.RADIO));
        musicApp.setText(appName(P.PKG_MUSIC, AppsRepo.MUSIC));
        phoneApp.setText(appName(P.PKG_PHONE, AppsRepo.PHONE));
        camApp.setText(appName(P.PKG_CAMERA, AppsRepo.CAMERA));
        int r = P.driveReturn();
        retVal.setText(r < 60 ? r + " s" : (r / 60) + " min");
        int am = P.driveAfter();
        afterVal.setText(am == 0 ? "Non" : am + " min");
        kmhVal.setText(P.driveKmh() + " km/h");
        long km = LocationTracker.odometer();
        odoVal.setText(km > 0 ? Fmt.big(km) + " km" : "À saisir");
        volVal.setText("Max " + P.volStartMax());
        nightRangeRow.setVisibility(P.nightBy() == 2 ? View.VISIBLE : View.GONE);
        nightRange.setText(Fmt.hm(P.i(P.NIGHT_FROM, 1200)) + " → " + Fmt.hm(P.i(P.NIGHT_TO, 420)));
        clockVal.setText(Clock.suspicious() ? "À régler" : "Régler");
        clockVal.setTextColor(Clock.suspicious() ? Pal.ORANGE_D : Pal.text);

        MapDl d = App.I.mapdl;
        if (d.running) {
            String pr = d.total > 0 ? (d.done * 100 / d.total) + " % · " + (d.done >> 20) + " / " + (d.total >> 20) + " Mo" : (d.done >> 20) + " Mo";
            mapStatus.setText(d.status + " " + pr);
            mapBtn.setText("Arrêter");
        } else if (MiniMap.available(a)) {
            long mb = MiniMap.file(a).length() >> 20;
            mapStatus.setText("Installée (" + mb + " Mo). " + (d.status.isEmpty() ? "Tu peux la retélécharger pour la mettre à jour." : d.status));
            mapBtn.setText("Mettre à jour (Wi-Fi)");
        } else {
            long part = d.partial(a) >> 20;
            String st = d.status.isEmpty() ? "Non téléchargée. Environ 500 Mo : utilise de préférence le Wi-Fi de chez toi (pas le partage de l'iPhone)."
                    : d.status;
            mapStatus.setText(st + (part > 0 ? " Déjà reçu : " + part + " Mo." : ""));
            mapBtn.setText(part > 0 ? "Reprendre" : "Télécharger (Wi-Fi)");
        }

        Updater u = App.I.upd;
        version.setText("Launcher 208 · version " + Updater.current());
        updStatus.setText(u.percent >= 0 ? u.status + " " + u.percent + " %" : u.status.isEmpty() ? "Touche « Mise à jour » pour vérifier sur GitHub." : u.status);
        updBtn.setText(u.available() ? "Installer la version " + u.remoteVersion : "Mise à jour");
    }

    @Override void destroy() {
        App.I.mapdl.onProgress = null;
        App.I.upd.onChange = null;
    }
}

package be.pa.launcher208;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mode test : vérifie sur TON autoradio ce qui marche (volume, bulle, radio, GPS...)
 * et permet de donner les autorisations. Prends une photo de cet écran si quelque chose ne va pas.
 */
final class TestScreen extends Screen {
    private final ArrayList<Runnable> updaters = new ArrayList<>();
    private TextView fpsVal;
    private int frames;
    private long fpsStart;
    private View spinner;

    TestScreen(HomeActivity a) { super(a); }

    @Override View build() {
        LinearLayout outer = U.col(a);
        U.card(outer, Pal.night ? 0xFF111316 : 0xFFF3F5F7, 28);
        LinearLayout head = U.row(a);
        U.pad(head, 18, 16, 22, 8);
        View back = U.box(a, 40, U.oval(Pal.card), R.drawable.ic_chev_l, 20, Pal.text);
        U.click(back, 20, new Runnable() {
            @Override public void run() { a.showScreen(HomeActivity.S_SETTINGS); }
        });
        head.addView(back);
        LinearLayout ht = U.col(a);
        ht.addView(U.tv(a, "Mode test", 22, Pal.text, 700));
        ht.addView(U.tv(a, "Vérifie ce qui marche sur ton autoradio. Envoie une photo de cet écran si besoin.", 12, Pal.text2, 400),
                U.lpm(U.WR, U.WR, 0, 3, 0, 0));
        head.addView(ht, U.lpm(0, U.WR, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) ht.getLayoutParams()).weight = 1;
        outer.addView(head);

        ScrollView sv = new ScrollView(a);
        LinearLayout cols = U.row(a);
        cols.setGravity(Gravity.TOP);
        U.pad(cols, 18, 6, 18, 18);
        LinearLayout c1 = U.col(a), c2 = U.col(a);
        cols.addView(c1, new LinearLayout.LayoutParams(0, U.WR, 1));
        cols.addView(U.gap(a, 16, 1));
        cols.addView(c2, new LinearLayout.LayoutParams(0, U.WR, 1));
        sv.addView(cols);
        outer.addView(sv, U.lpH(1));

        // ----- Autorisations -----
        LinearLayout s = section(c1, "Autorisations à donner une fois");
        check(s, "Écran d'accueil par défaut", new Status() {
            @Override public String get() { return isDefaultHome() ? "OK" : "Non : choisis « Launcher 208 »"; }
        }, "Choisir", new Runnable() {
            @Override public void run() { AppsRepo.openSettings(a, Settings.ACTION_HOME_SETTINGS); }
        });
        check(s, "Position (GPS)", new Status() {
            @Override public String get() { return App.I.loc.permitted() ? "OK" : "Non autorisée"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() {
                a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, HomeActivity.REQ_LOC);
            }
        });
        check(s, "Bulle par-dessus CarPlay", new Status() {
            @Override public String get() { return LauncherService.canOverlay(a) ? "OK" : "Non autorisée"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true); }
        });
        check(s, "Savoir si CarPlay est à l'écran", new Status() {
            @Override public String get() { return LauncherService.usageAccess(a) ? "OK (accès à l'utilisation)" : "Non : active « Launcher 208 »"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() { open(Settings.ACTION_USAGE_ACCESS_SETTINGS, false); }
        });
        check(s, "Nom de la station radio", new Status() {
            @Override public String get() { return App.I.media.accessGranted() ? "OK (accès aux notifications)" : "Non : active « Launcher 208 »"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() { open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS", false); }
        });
        check(s, "Bluetooth : voir ton iPhone et l'allumer", new Status() {
            @Override public String get() { return PhoneWatch.permitted(a) ? "OK" : "Non autorisé (« Appareils à proximité »)"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() { a.requestPerm(android.Manifest.permission.BLUETOOTH_CONNECT, HomeActivity.REQ_BT); }
        });
        check(s, "Installer les mises à jour", new Status() {
            @Override public String get() { return Updater.canInstall(a) ? "OK" : "Non autorisé"; }
        }, "Autoriser", new Runnable() {
            @Override public void run() { Updater.askInstallPermission(a); }
        });

        // ----- Rapidité -----
        s = section(c1, "Rapidité");
        check(s, "Démarrage du launcher", new Status() {
            @Override public String get() {
                long ms = App.firstFrameMs;
                return ms < 0 ? "—" : String.format(Locale.FRANCE, "%.2f s", ms / 1000f);
            }
        }, null, null);
        LinearLayout fr = row(s, "Fluidité (images par seconde)");
        fpsVal = U.tv(a, "Touche « Mesurer »", 13, Pal.text, 600);
        fr.addView(fpsVal);
        spinner = new View(a);
        spinner.setBackground(U.rr(Pal.accent, 4));
        fr.addView(spinner, U.lpm(U.s(14), U.s(14), 10, 0, 10, 0));
        fr.addView(btn("Mesurer", new Runnable() {
            @Override public void run() { measureFps(); }
        }));
        s.addView(U.line(a));

        // ----- CarPlay et volume -----
        s = section(c1, "CarPlay et volume");
        check(s, "Appli CarPlay reconnue", new Status() {
            @Override public String get() { String p = App.I.apps.carplayPkg(); return p.isEmpty() ? "Aucune : choisis-la" : App.I.apps.label(p) + " (" + p + ")"; }
        }, "Choisir", new Runnable() {
            @Override public void run() { a.chooseApp("Application CarPlay", P.PKG_CARPLAY, null); }
        });
        check(s, "iPhone en Bluetooth", new Status() {
            @Override public String get() {
                int n = PhoneWatch.bonded().size();
                String seen = PhoneWatch.lastSeen.isEmpty() ? "aucune connexion vue"
                        : "dernière connexion : " + PhoneWatch.lastSeen + " (il y a "
                        + ((android.os.SystemClock.elapsedRealtime() - PhoneWatch.lastSeenAt) / 60000L) + " min)";
                return "appareils jumelés vus par Android : " + n + " · " + seen
                        + (PhoneWatch.lastAction.isEmpty() ? "" : " · " + PhoneWatch.lastAction);
            }
        }, null, null);
        check(s, "Écrans CarPlay reconnus", new Status() {
            @Override public String get() {
                String l = P.s(P.CARPLAY_PKGS);
                return App.I.apps.carplayPkg() + (l.isEmpty() ? "" : " + " + l.replace(",", ", "));
            }
        }, null, null);
        check(s, "Dernière appli à l'écran", new Status() {
            @Override public String get() { LauncherService ls = LauncherService.I; return ls == null ? "service arrêté" : ls.fgPkg.isEmpty() ? "inconnue" : ls.fgPkg; }
        }, null, null);
        check(s, "Volume (musique Android)", new Status() {
            @Override public String get() {
                String e = App.I.vol.lastError;
                return App.I.vol.get() + " / " + App.I.vol.max() + " · façon : " + Volume.NAMES[Math.max(0, Math.min(Volume.NAMES.length - 1, P.volMethod()))]
                        + (App.I.vol.fixed() ? " · volume Android bloqué par l'autoradio" : "") + (e.isEmpty() ? "" : " · erreur " + e);
            }
        }, null, null);
        LinearLayout vr = row(s, "Test du volume : écoute si le son change (façon choisie dans Réglages)");
        vr.addView(btn("−", new Runnable() { @Override public void run() { App.I.vol.step(-1); refreshNow(); } }));
        vr.addView(U.gap(a, 8, 1));
        vr.addView(btn("+", new Runnable() { @Override public void run() { App.I.vol.step(1); refreshNow(); } }));
        s.addView(U.line(a));

        // ----- Autoradio : pour savoir quel système il utilise (Bluetooth, volume) -----
        s = section(c1, "Autoradio");
        final String model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · puce " + android.os.Build.HARDWARE
                + " / " + android.os.Build.BOARD + " · Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")";
        check(s, "Modèle", new Status() {
            @Override public String get() { return model; }
        }, null, null);
        final String vendor = vendorApps();
        check(s, "Applis du fabricant", new Status() {
            @Override public String get() { return vendor; }
        }, null, null, 6);
        check(s, "Bluetooth d'Android", new Status() {
            @Override public String get() {
                if (!BtPower.present(a)) return "absent (l'autoradio utilise sa propre puce)";
                return (BtPower.isOn(a) ? "allumé" : "éteint") + " · au démarrage : " + BtPower.last
                        + " · iPhone connu d'Android : " + (PhoneWatch.knowsMyPhone() ? "oui" : "non");
            }
        }, "Allumer", new Runnable() {
            @Override public void run() { BtPower.ensureOn(a); refreshNow(); }
        });

        // ----- Comment l'autoradio règle le vrai son -----
        s = section(c1, "Volume de l'autoradio");
        check(s, "Écoute du volume", new Status() {
            @Override public String get() {
                return VolumeSpy.active() ? "en cours (encore " + VolumeSpy.secondsLeft() + " s) : règle le son avec les boutons de l'autoradio"
                        : "touche « Écouter », puis monte et baisse le son avec les boutons de l'autoradio (barre du haut ou volant)";
            }
        }, "Écouter", new Runnable() {
            @Override public void run() { VolumeSpy.start(a); refreshNow(); }
        });
        check(s, "Ce qui a changé", new Status() {
            @Override public String get() { return VolumeSpy.text(); }
        }, null, null, 12);

        // ----- Colonne 2 -----
        s = section(c2, "Radio");
        check(s, "Appli radio reconnue", new Status() {
            @Override public String get() { String p = App.I.apps.radioPkg(); return p.isEmpty() ? "Aucune : choisis-la" : App.I.apps.label(p) + " (" + p + ")"; }
        }, "Choisir", new Runnable() {
            @Override public void run() { a.chooseApp("Radio", P.PKG_RADIO, null); }
        });
        check(s, "Lecture partagée", new Status() {
            @Override public String get() { return App.I.media.sessionsSummary(); }
        }, null, null);
        LinearLayout rr = btnRow(s, "Boutons radio (le son doit réagir)");
        rr.addView(btn("Précédente", mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        rr.addView(U.gap(a, 8, 1));
        rr.addView(btn("Pause / lecture", mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
        rr.addView(U.gap(a, 8, 1));
        rr.addView(btn("Suivante", mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)));
        s.addView(U.line(a));

        s = section(c2, "GPS, heure et nuit");
        check(s, "GPS", new Status() {
            @Override public String get() {
                LocationTracker l = App.I.loc;
                if (!l.permitted()) return "non autorisé";
                return (l.hasFix() ? "position OK" : "recherche…") + " · " + l.satsUsed + "/" + l.sats + " satellites · "
                        + Math.round(l.speedKmh) + " km/h";
            }
        }, null, null);
        check(s, "Heure", new Status() {
            @Override public String get() {
                long s = System.currentTimeMillis(), real = Clock.realOrUnknown(), gps = Clock.gpsNow();
                return "autoradio " + Fmt.dateShort(s) + " " + Fmt.hhmm(s)
                        + " (" + java.util.TimeZone.getDefault().getID() + ")"
                        + " · Internet " + (real > 0 ? Fmt.dateShort(real) + " " + Fmt.hhmmBrussels(real) : "inconnue")
                        + " · GPS " + (gps > 0 ? Fmt.hhmm(gps) : "aucune")
                        + (Clock.suspicious() ? " · À RÉGLER" : " · OK");
            }
        }, null, null);
        check(s, "Luminosité (phares)", new Status() {
            @Override public String get() {
                Night n = App.I.night;
                return "actuelle " + n.brightness() + " · min " + P.i(P.BR_MIN, -1) + " · max " + P.i(P.BR_MAX, -1)
                        + (n.headlightsKnown() ? " · phares détectés" : " · allume les phares pour tester");
            }
        }, null, null);
        check(s, "Mode nuit maintenant", new Status() {
            @Override public String get() { return App.I.night.isNight(Clock.now()) ? "nuit" : "jour"; }
        }, null, null);

        check(s, "Carte de Belgique (minimap)", new Status() {
            @Override public String get() {
                if (!MiniMap.available(a)) return "Non téléchargée (Réglages → Carte de Belgique)";
                String e = MiniMap.lastError;
                return "OK, " + (MiniMap.file(a).length() >> 20) + " Mo" + (e.isEmpty() ? "" : " · dernière erreur : " + e);
            }
        }, null, null);

        s = section(c2, "Portières (simulation)");
        check(s, "Source des portières", new Status() {
            @Override public String get() { return App.I.doors.source; }
        }, null, null);
        LinearLayout dr = btnRow(s, "Montrer sur l'accueil");
        dr.addView(btn("Av G", door(Doors.FL)));
        dr.addView(U.gap(a, 6, 1));
        dr.addView(btn("Ar G", door(Doors.RL)));
        dr.addView(U.gap(a, 6, 1));
        dr.addView(btn("Av D", door(Doors.FR)));
        dr.addView(U.gap(a, 6, 1));
        dr.addView(btn("Ar D", door(Doors.RR)));
        dr.addView(U.gap(a, 6, 1));
        dr.addView(btn("Coffre", door(Doors.TRUNK)));
        dr.addView(U.gap(a, 6, 1));
        dr.addView(btn("Fermer", door(0)));
        s.addView(U.line(a));
        check(s, "Applis du boîtier CAN", new Status() {
            @Override public String get() { return canApps(); }
        }, null, null);

        s = section(c2, "Mode conduite (simulation à l'arrêt)");
        LinearLayout sr = row(s, "Voir le mode conduite 15 secondes sans rouler");
        sr.addView(btn("Simuler 50 km/h", new Runnable() {
            @Override public void run() {
                App.I.simSpeed = 50;
                App.I.main.postDelayed(new Runnable() {
                    @Override public void run() {
                        App.I.simSpeed = -1;
                        a.exitDrive();
                    }
                }, 15000);
            }
        }));
        s.addView(U.line(a));
        check(s, "Contact mis", new Status() {
            @Override public String get() {
                int am = P.driveAfter();
                return "il y a " + (App.I.sinceContact() / 60000L) + " min · activer après le contact : "
                        + (am == 0 ? "non" : am + " min") + (App.I.droveThisContact ? " · mode conduite déjà affiché" : "");
            }
        }, null, null);
        return outer;
    }

    // ---------- Constructeurs ----------
    interface Status { String get(); }

    /** Paquets des applis système du fabricant (radio, Bluetooth...), regroupés : ils disent quel système c'est. */
    private String vendorApps() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.TreeSet<String> set = new java.util.TreeSet<>();
            for (android.content.pm.ResolveInfo r : a.getPackageManager().queryIntentActivities(i, 0)) {
                android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
                String p = ai.packageName;
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                if (p.startsWith("com.android.") || p.startsWith("com.google.") || p.equals(a.getPackageName())) continue;
                set.add(p);
            }
            if (set.isEmpty()) return "aucune";
            // regroupées par début de nom : « com.syu : bt, radio » (plus court à lire sur une photo)
            java.util.TreeMap<String, StringBuilder> groups = new java.util.TreeMap<>();
            for (String p : set) {
                String[] parts = p.split("\\.");
                String head = parts.length >= 3 ? parts[0] + "." + parts[1] : p;
                String tail = parts.length >= 3 ? p.substring(head.length() + 1) : "";
                StringBuilder g = groups.get(head);
                if (g == null) groups.put(head, g = new StringBuilder());
                if (!tail.isEmpty()) g.append(g.length() > 0 ? ", " : "").append(tail);
            }
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, StringBuilder> e : groups.entrySet()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.getKey());
                if (e.getValue().length() > 0) sb.append(" : ").append(e.getValue());
            }
            return sb.length() > 700 ? sb.substring(0, 700) + "…" : sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout c = U.col(a);
        U.card(c, Pal.card, 20);
        U.pad(c, 18, 12, 18, 2);
        TextView t = U.tv(a, title.toUpperCase(Fmt.FR), 12, Pal.text2, 600);
        U.spacing(t, 0.4f, 12);
        c.addView(t, U.lpm(U.WR, U.WR, 0, 0, 0, 4));
        parent.addView(c, U.lpm(U.M, U.WR, 0, 0, 0, 12));
        return c;
    }

    private LinearLayout row(LinearLayout s, String title) {
        LinearLayout r = U.row(a);
        r.setMinimumHeight(U.s(54));
        TextView t = U.tvMulti(a, title, 14, Pal.text, 600, 2);
        r.addView(t, new LinearLayout.LayoutParams(0, U.WR, 1));
        s.addView(r, U.lp(U.M, U.WR));
        return r;
    }

    /** Titre sur une ligne, boutons sur la ligne en dessous (pour les rangées de plusieurs boutons). */
    private LinearLayout btnRow(LinearLayout s, String title) {
        s.addView(U.tv(a, title, 14, Pal.text, 600), U.lpm(U.M, U.WR, 0, 10, 0, 8));
        LinearLayout r = U.row(a);
        s.addView(r, U.lpm(U.M, U.WR, 0, 0, 0, 10));
        return r;
    }

    private void check(LinearLayout s, String title, final Status st, String action, Runnable run) {
        check(s, title, st, action, run, 3);
    }

    private void check(LinearLayout s, String title, final Status st, String action, Runnable run, int lines) {
        LinearLayout r = U.row(a);
        r.setMinimumHeight(U.s(54));
        LinearLayout t = U.col(a);
        t.addView(U.tv(a, title, 14, Pal.text, 600));
        final TextView v = U.tvMulti(a, "", 12, Pal.text2, 400, lines);
        t.addView(v, U.lpm(U.M, U.WR, 0, 2, 0, 0));
        r.addView(t, new LinearLayout.LayoutParams(0, U.WR, 1));
        U.pad(t, 0, 6, 8, 6);
        if (action != null) r.addView(btn(action, run));
        s.addView(r, U.lp(U.M, U.WR));
        s.addView(U.line(a));
        updaters.add(new Runnable() {
            @Override public void run() {
                String x;
                try { x = st.get(); } catch (Exception e) { x = "erreur : " + e.getClass().getSimpleName(); }
                v.setText(x);
                v.setTextColor(x.startsWith("OK") ? Pal.ok : x.startsWith("Non") ? Pal.ORANGE_D : Pal.text2);
            }
        });
    }

    private TextView btn(String label, Runnable run) {
        TextView t = U.tv(a, label, 13, Pal.text, 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(U.rr(Pal.card2, 11));
        U.pad(t, 12, 0, 12, 0);
        U.click(t, 11, run);
        t.setLayoutParams(U.lp(U.WR, U.s(38)));
        return t;
    }

    private Runnable mediaKey(final int code) {
        return new Runnable() {
            @Override public void run() { App.I.media.key(code, App.I.apps.radioPkg()); }
        };
    }

    private Runnable door(final int m) {
        return new Runnable() {
            @Override public void run() {
                App.I.doors.set(m, m == 0 ? null : "simulation (mode test)");
                if (m != 0) a.showScreen(HomeActivity.S_HOME);
            }
        };
    }

    private void open(String action, boolean withPkg) {
        try {
            Intent i = withPkg ? new Intent(action, Uri.parse("package:" + a.getPackageName())) : new Intent(action);
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            AppsRepo.openSettings(a, Settings.ACTION_SETTINGS);
        }
    }

    private boolean isDefaultHome() {
        try {
            ResolveInfo r = a.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && a.getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    private String canApps() {
        StringBuilder b = new StringBuilder();
        try {
            List<android.content.pm.ApplicationInfo> l = a.getPackageManager().getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : l) {
                String p = ai.packageName.toLowerCase(Locale.ROOT);
                if (p.contains("canbus") || p.contains(".can.") || p.endsWith(".can") || p.contains("canbox") || p.contains("decoder")
                        || p.contains("carinfo") || p.contains("vehicle") || p.contains("syu") || p.contains("raise")) {
                    if (b.length() > 0) b.append(", ");
                    b.append(ai.packageName);
                }
            }
        } catch (Exception ignored) { }
        return b.length() == 0 ? "aucune trouvée" : b.toString();
    }

    // ---------- Mesure des images par seconde ----------
    private void measureFps() {
        frames = 0;
        fpsStart = 0;
        fpsVal.setText("Mesure…");
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override public void doFrame(long t) {
                if (fpsStart == 0) fpsStart = t;
                frames++;
                spinner.setRotation((spinner.getRotation() + 9) % 360);
                long el = t - fpsStart;
                if (el < 3000000000L) Choreographer.getInstance().postFrameCallback(this);
                else fpsVal.setText(Math.round((frames - 1) * 1e9 / el) + " images/s");
            }
        });
    }

    private void refreshNow() { for (Runnable r : updaters) r.run(); }

    @Override void refresh() { if (root != null) refreshNow(); }

    @Override void tick() {
        if (System.currentTimeMillis() / 1000 % 2 == 0) refreshNow();
    }
}

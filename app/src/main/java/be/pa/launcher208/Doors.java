package be.pa.launcher208;

import java.util.ArrayList;

/**
 * État des portières et du coffre.
 * L'information vient du boîtier CAN (Raise) par l'application de l'autoradio : la façon de la lire
 * dépend du fabricant et sera trouvée avec le mode test. En attendant, le mode test permet de simuler.
 */
final class Doors {
    static final int FL = 1, FR = 2, RL = 4, RR = 8, TRUNK = 16;
    private volatile int mask;
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    String source = "aucune (à trouver avec le mode test)";

    int mask() { return mask; }

    void set(int m, String from) {
        if (from != null) source = from;
        if (m == mask) return;
        mask = m;
        App.I.main.post(new Runnable() {
            @Override public void run() { for (int i = 0; i < listeners.size(); i++) listeners.get(i).run(); }
        });
    }

    void add(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }
    void remove(Runnable r) { listeners.remove(r); }

    static int count(int m) { return Integer.bitCount(m & 31); }

    /** Porte à montrer en premier (conducteur d'abord). */
    static int main(int m) {
        int[] order = {FL, RL, FR, RR, TRUNK};
        for (int d : order) if ((m & d) != 0) return d;
        return 0;
    }

    static String name(int d) {
        switch (d) {
            case FL: return "Portière avant gauche";
            case FR: return "Portière avant droite";
            case RL: return "Portière arrière gauche";
            case RR: return "Portière arrière droite";
            case TRUNK: return "Coffre";
            default: return "";
        }
    }

    static String extra(int d) {
        if (d == FL) return "(conducteur)";
        if (d == FR) return "(passager)";
        return "";
    }

    /** Message complet : « Portière avant gauche ouverte (conducteur) » ou « 2 ouvertures ». */
    static String message(int m) {
        int n = count(m);
        if (n == 0) return "";
        if (n == 1) {
            int d = main(m);
            return name(d) + (d == TRUNK ? " ouvert" : " ouverte");
        }
        return n + " ouvertures : " + list(m);
    }

    static String list(int m) {
        StringBuilder b = new StringBuilder();
        String[] s = {"avant gauche", "avant droite", "arrière gauche", "arrière droite", "coffre"};
        int[] d = {FL, FR, RL, RR, TRUNK};
        for (int i = 0; i < d.length; i++) {
            if ((m & d[i]) == 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(s[i]);
        }
        return b.toString();
    }
}

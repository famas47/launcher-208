package be.pa.launcher208;

/**
 * Quand entrer dans le mode conduite et quand en sortir (appelé chaque seconde par l'accueil).
 *
 * Entrée :
 *  - dès 5 km/h tenus 2 secondes (comme avant) ;
 *  - OU, si tu l'as choisi dans les réglages, un certain temps après avoir mis le contact,
 *    même si la vitesse ne vient pas (GPS qui ne capte pas). Une seule fois par démarrage.
 * Sortie :
 *  - après un arrêt (vitesse sous 3 km/h) de la durée réglée, mais seulement si la voiture a vraiment
 *    roulé depuis l'entrée. Entré par le minuteur sans vitesse : on reste jusqu'au bouton « Accueil ».
 *  - bouton « Accueil » : on sort, et on ne revient pas tout seul avant le prochain arrêt
 *    (ou le prochain contact).
 *
 * Aucune dépendance à Android : testé dans LogicTest.
 */
final class DriveRule {
    static final int NONE = 0, ENTER_SPEED = 1, ENTER_TIMER = 2, EXIT = -1;

    private int fastTicks;
    private long stopSince, outSince;
    private boolean moved;      // 5 km/h vus depuis l'entrée (la vitesse marche)
    private boolean manualOut;  // quitté avec le bouton « Accueil »

    /** Entrée faite (par la vitesse ou par le minuteur). */
    void entered(boolean bySpeed) {
        moved = bySpeed;
        manualOut = false;
        fastTicks = 0;
        stopSince = 0;
    }

    /** Bouton « Accueil » : pas de retour automatique avant que la voiture se soit arrêtée. */
    void leftByHand() {
        manualOut = true;
        outSince = 0;
        fastTicks = 0;
    }

    boolean waitingAfterHand() { return manualOut; }

    /**
     * @param driving            le mode conduite est affiché
     * @param kmh                vitesse actuelle (0 si le GPS ne donne rien)
     * @param now                horloge en ms qui ne saute pas (uptime)
     * @param sinceContactMs     temps depuis que le contact a été mis
     * @param afterMin           réglage « Activer après le contact » en minutes (0 = non)
     * @param alreadyThisContact le mode conduite a déjà été affiché depuis ce démarrage
     * @param returnSec          réglage « Retour à l'accueil après un arrêt de »
     */
    int tick(boolean driving, float kmh, long now, long sinceContactMs, int afterMin, boolean alreadyThisContact, int returnSec) {
        if (!driving) {
            // contact remis depuis (rien d'affiché pour ce contact) : la sortie à la main ne compte plus
            if (manualOut && !alreadyThisContact) manualOut = false;
            if (manualOut) {
                // on attend un vrai arrêt avant de se remettre à surveiller la vitesse
                if (kmh < LocationTracker.STOP_KMH) {
                    if (outSince == 0) outSince = now;
                    else if (now - outSince >= returnSec * 1000L) manualOut = false;
                } else {
                    outSince = 0;
                }
                fastTicks = 0;
                return NONE;
            }
            fastTicks = kmh >= LocationTracker.DRIVE_KMH ? fastTicks + 1 : 0;
            if (fastTicks >= 2) return ENTER_SPEED;
            if (afterMin > 0 && !alreadyThisContact && sinceContactMs >= afterMin * 60000L) return ENTER_TIMER;
            return NONE;
        }
        if (kmh >= LocationTracker.DRIVE_KMH) moved = true;
        if (!moved) { stopSince = 0; return NONE; } // vitesse jamais vue : on ne sort pas tout seul
        if (kmh < LocationTracker.STOP_KMH) {
            if (stopSince == 0) stopSince = now;
            else if (now - stopSince >= returnSec * 1000L) return EXIT;
        } else {
            stopSince = 0;
        }
        return NONE;
    }
}

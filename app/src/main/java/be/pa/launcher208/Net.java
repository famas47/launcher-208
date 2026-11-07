package be.pa.launcher208;

import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Connexions Internet qui marchent même quand l'autoradio n'est pas à la bonne date.
 *
 * Après un démarrage complet, l'autoradio repart parfois à une vieille date. Les certificats des sites
 * (GitHub, carte, météo) paraissent alors « pas encore valables » et Android refuse la connexion
 * (« Chain validation failed »). Dans ce cas seulement, on lit l'heure réelle dans l'en-tête « Date »
 * d'une réponse de GitHub (en clair, rien n'est envoyé) et on refait EXACTEMENT la même vérification
 * des certificats (signatures jusqu'à une autorité reconnue par Android, nom du site) à cette date-là.
 * On n'accepte donc jamais un certificat que la vérification normale aurait refusé pour une autre raison.
 */
final class Net {
    // Heure réelle mesurée, rattachée au chronomètre interne d'Android (qui ne saute jamais, même si
    // quelqu'un change l'heure de l'autoradio ensuite).
    private static volatile long realAtSync;     // heure réelle lue sur Internet (ms)
    private static volatile long knownAt;        // SystemClock.elapsedRealtime() au même moment
    private static volatile boolean known;
    private static SSLSocketFactory factory;
    private static PKIXParameters anchors;
    static volatile String lastTlsFix = "";  // pour le mode test

    private static final String[] TIME_URLS = {"http://github.com/", "http://connectivitycheck.gstatic.com/generate_204"};

    /** Ouvre une connexion (https protégé comme expliqué plus haut). */
    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        if (h instanceof HttpsURLConnection) ((HttpsURLConnection) h).setSSLSocketFactory(factory());
        h.setRequestProperty("User-Agent", "Launcher208");
        return h;
    }

    private static synchronized SSLSocketFactory factory() throws Exception {
        if (factory == null) {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager platform = null;
            for (TrustManager t : tmf.getTrustManagers()) if (t instanceof X509TrustManager) platform = (X509TrustManager) t;
            if (platform == null) throw new IllegalStateException("aucun gestionnaire de certificats");
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new DateTolerantTrust(platform)}, null);
            factory = ctx.getSocketFactory();
            // les redirections (GitHub → serveur de fichiers) passent aussi par là
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);
        }
        return factory;
    }

    // ---------- Heure réelle ----------

    /** Heure réelle si elle a été lue sur Internet, sinon l'heure de l'autoradio. */
    static long realNow() {
        return known ? realAtSync + (SystemClock.elapsedRealtime() - knownAt) : System.currentTimeMillis();
    }

    static boolean realKnown() { return known; }

    /** Écart entre l'heure réelle et celle de l'autoradio (ms), 0 si inconnu. */
    static long offset() { return known ? realNow() - System.currentTimeMillis() : 0; }

    /**
     * Lit l'heure réelle dans l'en-tête « Date » d'une réponse HTTP (au plus une fois par demi-heure).
     * À appeler hors du fil principal.
     */
    static boolean syncTime() {
        if (known && SystemClock.elapsedRealtime() - knownAt < 30L * 60000L) return true;
        for (String u : TIME_URLS) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(u).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setRequestMethod("HEAD");
                h.setUseCaches(false);
                h.setConnectTimeout(6000);
                h.setReadTimeout(6000);
                h.setRequestProperty("User-Agent", "Launcher208");
                long e0 = SystemClock.elapsedRealtime();
                h.getResponseCode();
                long date = h.getHeaderFieldDate("Date", 0);
                if (date > 1577836800000L) { // après 2020 : valeur plausible
                    // l'en-tête est à la seconde près (+0,5 s) ; on le rattache au milieu de l'aller-retour
                    realAtSync = date + 500;
                    knownAt = e0 + (SystemClock.elapsedRealtime() - e0) / 2;
                    known = true;
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (h != null) h.disconnect();
            }
        }
        return false;
    }

    // ---------- Vérification des certificats ----------

    private static final class DateTolerantTrust implements X509TrustManager {
        private final X509TrustManager platform;

        DateTolerantTrust(X509TrustManager platform) { this.platform = platform; }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            platform.checkClientTrusted(chain, authType);
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                platform.checkServerTrusted(chain, authType); // vérification normale d'Android
            } catch (CertificateException e) {
                // Refusé : peut-être seulement à cause de la date de l'autoradio. On lit l'heure réelle
                // et on refait la vérification complète à cette date. Si elle échoue aussi : refus.
                if (!syncTime() || Math.abs(offset()) < 60000L) throw e; // date déjà juste : vrai problème
                verifyAt(chain, new Date(realNow()), e);
                lastTlsFix = "certificat vérifié à la date réelle (autoradio décalé de " + Math.round(offset() / 3600000.0) + " h)";
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return platform.getAcceptedIssuers(); }
    }

    /**
     * Vérification complète d'une chaîne (signatures jusqu'à une autorité de confiance d'Android) à une date donnée.
     * Le serveur envoie parfois des certificats en trop (anciennes signatures croisées) : on essaie la chaîne
     * puis ses versions raccourcies, toujours depuis le certificat du site.
     */
    static void verifyAt(X509Certificate[] chain, Date at, CertificateException original) throws CertificateException {
        if (chain == null || chain.length == 0) throw original;
        Exception last = original;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPathValidator v = CertPathValidator.getInstance("PKIX");
            PKIXParameters p = (PKIXParameters) anchors().clone();
            p.setDate(at);
            for (int k = chain.length; k >= 1; k--) {
                List<X509Certificate> list = new ArrayList<>(Arrays.asList(chain).subList(0, k));
                X509Certificate end = list.get(list.size() - 1);
                // une racine envoyée par le serveur ne fait pas partie du chemin (elle doit être une ancre)
                if (list.size() > 1 && end.getSubjectX500Principal().equals(end.getIssuerX500Principal())) continue;
                try {
                    CertPath path = cf.generateCertPath(list);
                    v.validate(path, p);
                    return; // chaîne valable à la date réelle
                } catch (Exception e) {
                    last = e;
                }
            }
        } catch (Exception e) {
            last = e;
        }
        CertificateException ce = new CertificateException("certificat refusé même à la date réelle : " + last.getMessage());
        ce.initCause(last);
        throw ce;
    }

    private static synchronized PKIXParameters anchors() throws Exception {
        if (anchors == null) {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            PKIXParameters p = new PKIXParameters(ks);
            p.setRevocationEnabled(false);
            anchors = p;
        }
        return anchors;
    }

    /** Message d'erreur lisible, avec la cause précise (utile dans le mode test). */
    static String why(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Throwable c = e.getCause();
        if (c != null && c.getMessage() != null && !m.contains(c.getMessage())) m += " (" + c.getMessage() + ")";
        return m;
    }
}

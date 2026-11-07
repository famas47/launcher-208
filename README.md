# Launcher 208

Écran d'accueil (launcher) pour l'autoradio Android de ma Peugeot 208 : style CarPlay / Tesla, ma voiture en 3D
qui tourne à 360° au doigt (72 vues, `app/src/main/assets/car/v000…v355.webp`),
CarPlay (S-Play) en un geste, mode conduite avec minimap hors ligne, trajets, entretien, bulle de volume sur CarPlay.

## Comment ça marche

- À chaque changement du code, GitHub fabrique tout seul l'application (onglet **Actions**),
  puis la publie dans **Releases** sous le nom `Launcher208-vNN.apk`.
- Sur l'autoradio : **Réglages → Mise à jour** télécharge et installe la dernière version (réglages gardés).
- Le projet doit rester **public** pour que l'autoradio puisse télécharger les mises à jour.
- Le secret **SIGNING_KEY** (Settings → Secrets and variables → Actions) contient la clé de signature :
  sans elle, une nouvelle version ne peut pas s'installer par-dessus l'ancienne.

## Vérification automatique (facultatif)

Pousser du code sur la branche **verification** lance `Vérifier le launcher` (onglet Actions) : Android Lint,
puis un vrai test sur deux autoradios simulés 1280x720 (Android 10 et 14) avec la carte de Belgique :
démarrage, rotation de la voiture, portières, tous les écrans, mode conduite, bulle de volume, recherche de
fuites de mémoire, 1500 touchers au hasard et lecture du journal d'Android. Rien n'est publié : le rapport
arrive dans un brouillon de version privé, effacé automatiquement à la prochaine vraie version.
L'APK de test contient des commandes de test ; l'APK publié pour la voiture ne les contient pas.

## Revenir à l'écran d'origine

Réglages du launcher → **Revenir à l'écran d'origine**. Rien n'est effacé.

## Crédits

- Modèle 3D « Peugeot 208 2017 » par luizantonix, licence CC BY-NC-SA.
- Cartes © les contributeurs d'OpenStreetMap ; fichiers de carte Mapsforge (download.mapsforge.org).
- Moteur de carte [Mapsforge](https://github.com/mapsforge/mapsforge) 0.31.0, licence LGPL v3 (dossier `mapsforge/`).
- Météo : [Open-Meteo](https://open-meteo.com).

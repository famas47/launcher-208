# Launcher 208

Écran d'accueil (launcher) pour l'autoradio Android de ma Peugeot 208 : style CarPlay / Tesla, ma voiture en 3D,
CarPlay (S-Play) en un geste, mode conduite avec minimap hors ligne, trajets, entretien, bulle de volume sur CarPlay.

## Comment ça marche

- À chaque changement du code, GitHub fabrique tout seul l'application (onglet **Actions**),
  puis la publie dans **Releases** sous le nom `Launcher208-vNN.apk`.
- Sur l'autoradio : **Réglages → Mise à jour** télécharge et installe la dernière version (réglages gardés).
- Le projet doit rester **public** pour que l'autoradio puisse télécharger les mises à jour.
- Le secret **SIGNING_KEY** (Settings → Secrets and variables → Actions) contient la clé de signature :
  sans elle, une nouvelle version ne peut pas s'installer par-dessus l'ancienne.

## Revenir à l'écran d'origine

Réglages du launcher → **Revenir à l'écran d'origine**. Rien n'est effacé.

## Crédits

- Modèle 3D « Peugeot 208 2017 » par luizantonix, licence CC BY-NC-SA.
- Cartes © les contributeurs d'OpenStreetMap ; fichiers de carte Mapsforge (download.mapsforge.org).
- Moteur de carte [Mapsforge](https://github.com/mapsforge/mapsforge) 0.31.0, licence LGPL v3 (dossier `mapsforge/`).
- Météo : [Open-Meteo](https://open-meteo.com).

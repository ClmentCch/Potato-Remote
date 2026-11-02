# Potato Remote 1.0.1 🥔

Version corrective de l'application Android pour le partage d'écran.

## Correction

- Correction de l'erreur `No EGLSurface - can't make current` au démarrage du partage.
- Le contexte EGL de capture utilise maintenant un **pbuffer hors écran 1×1** (`CONFIG_PIXEL_BUFFER`) au lieu d'appeler `makeCurrent()` sans surface.
- L'APK passe en `versionCode 2` / `versionName 1.0.1`.

Le site Web est inchangé : le ZIP 1.0.0 reste compatible avec cette APK.

## Assets

- `PotatoRemote-Android-1.0.1.apk` — application Android signée, `minSdk 26`, `targetSdk 35`.
- `PotatoRemote-Web-1.0.0-o2switch.zip` — site PHP/HTML/CSS/JS prêt à extraire sur o2switch.
- `SHA256SUMS.txt` — empreintes des fichiers publiés.

## Vérifications

- Build Android release réussi.
- Signature APK et alignement vérifiés.
- Aucun test sur appareil physique n'a pu être exécuté dans l'environnement de build ; l'installation et le partage doivent être revalidés sur le téléphone cible.

## Limites importantes

- TURN n'est pas configuré dans le ZIP par défaut. Pour garantir les connexions entre réseaux restrictifs (4G/NAT strict), configurez un serveur TURN externe : voir `docs/TURN.md`.
- Le contrôle et le clavier nécessitent l'activation explicite du service d'accessibilité ; certaines applications refusent l'injection.
- L'APK est signé avec une clé de démonstration ; remplacez-la pour une distribution publique.

## Installation rapide

1. Installer l'APK 1.0.1, renseigner l'URL HTTPS du site dans les réglages.
2. Extraire le ZIP dans le dossier web o2switch et suivre `INSTALLATION.md`.
3. Créer une session sur le téléphone, puis se connecter depuis le navigateur avec le nom et le mot de passe.

Documentation complète : [README](https://github.com/ClmentCch/Potato-Remote#readme).

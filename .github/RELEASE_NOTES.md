# Potato Remote 1.0.0 🥔

Première version livrée de Potato Remote : partage et contrôle d'écran Android vers un navigateur, avec une identité patate originale.

## Assets

- `PotatoRemote-Android-1.0.0.apk` — application Android signée, `minSdk 26`, `targetSdk 35`.
- `PotatoRemote-Web-1.0.0-o2switch.zip` — site PHP/HTML/CSS/JS prêt à extraire sur o2switch.
- `SHA256SUMS.txt` — empreintes des deux fichiers.

## Fonctionnalités

- Capture Android via MediaProjection et transmission WebRTC.
- Une connexion et un cadrage indépendants par spectateur (`fit` / `fill`).
- Contrôle tactile et clavier distant via le service d'accessibilité Android.
- Authentification par nom de session et mot de passe, jetons révocables, anti-bruteforce.
- Site Web responsive pour iPad, ordinateur et smartphone.
- Signalisation PHP par long-polling, compatible mutualisé ; média WebRTC direct ou via TURN.

## Vérifications

- API PHP : 81/81 tests HTTP réussis.
- Build Android release réussi, signature APK v2 et alignement vérifiés.
- ZIP extrait et testé : lint PHP, `health`, création de session locale.

## Limites importantes

- TURN n'est pas configuré dans le ZIP par défaut. Pour garantir les connexions entre réseaux restrictifs (4G/NAT strict), configurez un serveur TURN externe : voir `docs/TURN.md`.
- Le contrôle et le clavier nécessitent l'activation explicite du service d'accessibilité ; certaines applications refusent l'injection.
- Aucun test sur téléphone Android, iPad, TURN ou o2switch réels n'a été effectué dans l'environnement de build.
- L'APK est signé avec une clé de démonstration ; remplacez-la pour une distribution publique.

## Installation rapide

1. Installer l'APK, renseigner l'URL HTTPS du site dans les réglages.
2. Extraire le ZIP dans le dossier web o2switch et suivre `INSTALLATION.md`.
3. Créer une session sur le téléphone, puis se connecter depuis le navigateur avec le nom et le mot de passe.

Documentation complète : [README](https://github.com/ClmentCch/Potato-Remote#readme).

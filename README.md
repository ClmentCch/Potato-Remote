# Potato Remote 🥔

**Partage et contrôle d'écran Android vers iPad, ordinateur ou autre téléphone, depuis un navigateur.**

Potato Remote est composé de deux logiciels complémentaires :

1. **Potato Remote Android** — une application Android native qui capture l'écran avec `MediaProjection`, l'encode avec WebRTC et accepte les commandes d'un spectateur autorisé.
2. **Potato Remote Web** — un site PHP autonome, compatible hébergement mutualisé o2switch, qui authentifie les sessions et relaie la signalisation WebRTC. La vidéo et les commandes passent directement entre les appareils par WebRTC (DTLS-SRTP + DataChannel), jamais par PHP.

## Livrables

| Livrable | Fichier |
|---|---|
| APK Android signé (release) | `dist/PotatoRemote-Android-1.0.1.apk` |
| Site web prêt pour o2switch | `dist/PotatoRemote-Web-1.0.0-o2switch.zip` |
| Empreintes SHA-256 | `dist/SHA256SUMS.txt` |
| Sources Android | `android/` |
| Sources Web | `web/` |
| Identité visuelle | `brand/`, `web/assets/img/`, `android/app/src/main/res/` |

## État réel du projet

Ce projet ne prétend pas avoir été validé sur un appareil physique dans cet environnement. Voici ce qui a réellement été vérifié :

| Élément | État |
|---|---|
| API PHP : sessions, auth, jetons, quotas, signalisation | **Testée — 81/81 tests HTTP** |
| Refus HTTP sans HTTPS | **Testé — 400 `https_required`** |
| Build Android release + signature APK | **Testé — BUILD SUCCESSFUL, signature v2 vérifiée** |
| Manifeste APK, permissions, services, icône, splash | **Vérifiés avec `aapt`** |
| Pages Web, CSS, JS, polices, favicon, manifeste PWA | **Vérifiés en HTTP local + analyse statique** |
| Test navigateur WebRTC automatisé | **Fourni (`tests/browser_test.cjs`) mais non exécuté ici** : bibliothèques Chromium absentes du conteneur et installation système impossible sans root |
| Vidéo WebRTC réelle Android → iPad | **Non testée sur matériel réel** |
| Gestes réels `AccessibilityService` | **Non testés sur matériel réel** |
| Saisie distante réelle dans les applications | **Non testée sur matériel réel** |
| TURN derrière NAT strict / 4G | **Non testé** : TURN doit être configuré (voir `docs/TURN.md`) |
| o2switch (Apache/PHP-FPM réel) | **Non testé sur l'hébergement** : remplacements et `.htaccess` fournis |

Les limites Android et hébergement sont détaillées dans `docs/TEST_REPORT.md` et `docs/ARCHITECTURE.md`.

## Démarrage rapide

### 1. Installer l'application Android

Transférez `dist/PotatoRemote-Android-1.0.1.apk` sur le téléphone, autorisez l'installation depuis cette source, puis ouvrez **Potato Remote**.

1. Ouvrez **Réglages** et saisissez l'adresse de votre site, par exemple `https://potato.mondomaine.fr` (sans `/api`).
2. Activez le service d'accessibilité **Potato Remote — contrôle distant** si vous voulez le contrôle tactile ou le clavier.
3. Revenez à l'accueil, **Démarrer le partage**, choisissez un nom et un mot de passe.
4. Android affiche sa demande d'autorisation de capture d'écran : acceptez.
5. La session est active ; la notification « Partage en cours » permet de l'arrêter immédiatement.

### 2. Déployer le site

Décompressez `dist/PotatoRemote-Web-1.0.0-o2switch.zip` dans le dossier web de votre hébergement o2switch, puis suivez `INSTALLATION.md` inclus dans le ZIP. En résumé :

- PHP 8.1 ou plus récent ;
- HTTPS actif ;
- `web/api/config.php` à ajuster (URL, TURN éventuel) ;
- dossier `data/` inscriptible par PHP ;
- vérification via `https://votre-domaine/api/index.php?action=health`.

### 3. Rejoindre la session

Depuis l'iPad ou l'ordinateur : ouvrez `https://votre-domaine/`, saisissez le **nom de session** et le **mot de passe** affichés sur le téléphone. Le navigateur négocie WebRTC, affiche l'écran et propose les commandes autorisées par l'hôte.

## Architecture en bref

```text
Android (MediaProjection)
  └─ capture OpenGL → un rendu par spectateur → encodeur WebRTC
        │
        │  WebRTC : vidéo DTLS-SRTP + DataChannel de contrôle
        ▼
Navigateur (iPad / PC / mobile)
        ▲
        │  HTTPS + long-polling de signalisation (offre/reponse/ICE)
        ▼
Site PHP o2switch ── stockage JSON verrouillé (data/)
```

- **Une PeerConnection et un encodeur par spectateur** : chaque spectateur peut avoir un cadrage différent sans impacter les autres.
- **Signalisation par long-polling HTTP** : aucun WebSocket permanent requis sur l'hébergement mutualisé.
- **TURN optionnel mais recommandé** : sans relais TURN, deux réseaux restrictifs peuvent ne pas se connecter. o2switch ne peut pas héberger ce relais ; voir `docs/TURN.md`.
- **Contrôle et clavier via `AccessibilityService`** : Android impose le consentement explicite du propriétaire ; certaines applications refusent l'injection.
- **Mots de passe hashés**, jetons hashés sur disque, anti-bruteforce, expiration, révocation.

## Sécurité

- HTTPS obligatoire pour toutes les actions sensibles.
- Aucun cookie : jetons porteurs révocables, stockés hachés côté serveur.
- Mots de passe hachés avec `password_hash()`.
- Verrouillage anti-bruteforce par IP et nom de session.
- Session expirée après 12 h par défaut ; téléphone considéré hors ligne après 30 s ; spectateur inactif retiré.
- Le contrôle et le clavier sont proposés à la création, mais restent entièrement révocables sur le téléphone, visibles dans la notification et dans l'application.
- Aucune commande n'est appliquée si le propriétaire a désactivé l'autorisation correspondante.

## Documentation

- [`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md) — compiler, signer, installer l'APK.
- [`docs/WEB_DEPLOY_O2SWITCH.md`](docs/WEB_DEPLOY_O2SWITCH.md) — déploiement pas à pas sur o2switch.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — architecture, adaptation par spectateur, sécurité, limites.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — API HTTP et protocole DataChannel.
- [`docs/TURN.md`](docs/TURN.md) — installer coturn et configurer les identifiants éphémères.
- [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) — résultats réels, limites et checklist de validation.
- [`web/INSTALLATION.md`](web/INSTALLATION.md) — notice incluse dans le ZIP web.
- [`tests/README.md`](tests/README.md) — exécuter les tests backend et navigateur.

## Développement

```bash
# Backend (test local avec allow_http=true)
PHP_CLI_SERVER_WORKERS=8 php -S 127.0.0.1:8088 -t web
python3 tests/backend_test.py

# Android
cd android
ANDROID_HOME=/chemin/sdk JAVA_HOME=/chemin/jdk ./gradlew assembleRelease
```

L'identité visuelle se régénère avec :

```bash
node brand/build-brand.mjs
```

## Identité

Le logo est un graphique original de patate (forme crème, pousses et ondes terracotta), décliné en icône adaptative Android, favicon, badge web et écran de démarrage. Aucun emoji n'est utilisé comme logo final.

## Licence et usage

Projet personnel fourni tel quel. L'APK de démonstration est signé avec une clé de test incluse dans `android/keystore/` ; pour une distribution publique, remplacez cette clé et ne publiez jamais son mot de passe.

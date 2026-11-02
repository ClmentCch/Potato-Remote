# Rapport de tests — Potato Remote 1.0.0

Date : 2 novembre 2026. Environnement : conteneur Linux Ubuntu 24.04, sans appareil Android physique ni iPad.

> **Correctif 1.0.1** : l'initialisation EGL de la capture a été corrigée (`No EGLSurface - can't make current`). Un pbuffer hors écran 1×1 est désormais créé avant `makeCurrent()`. L'APK 1.0.1 a été reconstruit et vérifié ; la validation sur appareil physique reste à faire.

## Résumé

| Vérification | Résultat |
|---|---|
| API PHP (sessions, auth, jetons, quotas, signalisation, long-poll) | **81/81 tests HTTP réussis** |
| Refus HTTP sans HTTPS en configuration de production | **400 `https_required`** |
| Lint PHP (7 fichiers) | **OK** |
| Syntaxe JavaScript (modules) | **OK** |
| Références HTML/CSS/JS/polices/icônes | **Aucune référence manquante** |
| Assets Web servis en HTTP local | **17/17 en 200** |
| Build Android release | **BUILD SUCCESSFUL** |
| APK signé / aligné | **Signature v2 vérifiée, zipalign OK** |
| Test navigateur WebRTC automatisé | **Non exécuté** : bibliothèques Chromium absentes, installation système impossible sans root |
| Test sur téléphone Android réel | **Non exécuté** |
| Test sur iPad réel | **Non exécuté** |
| Test TURN / 4G réel | **Non exécuté** |
| Déploiement Apache o2switch réel | **Non exécuté** |

**Conclusion honnête :** le backend PHP et la compilation Android sont validés par des tests réels. L'interface Web est vérifiée statiquement et servie correctement, mais le flux WebRTC complet, l'injection de gestes et la saisie dans de vraies applications n'ont pas pu être validés sur matériel dans cet environnement. Le produit est prêt pour une campagne de tests sur appareils, pas certifié « production » sans ces tests.

## 1. Tests backend

Commande :

```bash
cd web && PHP_CLI_SERVER_WORKERS=8 php -S 127.0.0.1:8088 -t web
# avec allow_http=true uniquement pour le test local
python3 tests/backend_test.py
```

Résultat : **81/81**.

Couverture réelle :

- `config`, `health`, méthode HTTP incorrecte, JSON invalide ;
- création de session, validation du nom/mot de passe, nom déjà pris, reprise avec `phone_token` ;
- auth correcte/incorrecte, non-énumération des sessions, viewport, présence ;
- heartbeat, stats, `using_control` et `using_keyboard` visibles sur le téléphone ;
- offre/réponse SDP, candidats ICE, mauvais rôle, SDP trop court, ICE invalide ;
- `phone.update` (contrôle/clavier/quota), messages `state` ;
- kick, refus `kicked` sur heartbeat/poll/session.info, disparition de la liste ;
- quota `session_full`, deux spectateurs avec viewports/modes distincts ;
- long-poll vide, long-poll réveillé par un message en moins de 5 s ;
- anti-bruteforce : 8 échecs puis verrouillage 429, même avec le bon mot de passe ;
- arrêt de session et révocation des jetons ;
- stockage : mots de passe hashés, aucun jeton brut, index de jetons sans secret.

Le test efface uniquement les compteurs `data/ratelimit` au démarrage pour être reproductible ; il ne doit pas être lancé contre une production.

### HTTPS

Avec `allow_http=false`, une requête HTTP avec un Host non local renvoie :

```json
{"ok":false,"error":{"code":"https_required","message":"Potato Remote exige une connexion HTTPS."}}
```

Code HTTP **400** vérifié. En local (`127.0.0.1`), HTTP reste autorisé pour le développement.

## 2. Tests Web statiques

- `php -l` sur `api/index.php`, `config.php` et les 5 fichiers `src/` : aucun syntaxe.
- `node --check` sur les modules JS (login, viewer, webrtc, controls, keyboard, api, ui) et le script de marque : OK.
- Analyse Python : toutes les références `src/href`, les imports de modules, les `getElementById`/`$('#…')` et les ressources du manifeste existent (après ajout du conteneur `#toasts` manquant).
- Serveur PHP local : `index.html`, `viewer.html`, les 3 CSS, les 7 JS, le logo SVG, le favicon, la police WOFF2, le manifeste et `config` répondent **200** avec le bon type MIME.
- Aucune erreur 500 dans les logs PHP pendant les tests backend.

Le `.htaccess` n'est pas interprété par le serveur PHP intégré : son comportement Apache/o2switch reste à valider sur l'hébergement.

## 3. Android

```bash
cd android
JAVA_HOME=… ANDROID_HOME=… ./gradlew assembleRelease
```

- Build release : **SUCCESSFUL**.
- APK final : `dist/PotatoRemote-Android-1.0.1.apk` (environ 52 Mo, 4 ABI, SHA-256 `f2582920686636252445d10f7aaa741874b90173827ebb1972280ad477587b8c`).
- `apksigner verify` : **v2 = true**, certificat `CN=Potato Remote`.
- `zipalign -c -v 4` : **OK**.
- Manifeste : `minSdk 26`, `targetSdk 35`, permissions Internet/réseau/notification/foreground/mediaProjection/wakeLock, service `ShareService` en `mediaProjection`, service d'accessibilité exporté avec permission système.
- Ressources vérifiées : `potato_logo`, `splash_background`, icônes adaptatives.
- Aucune installation sur appareil (pas d'ADB/appareil dans le conteneur).

## 4. Test navigateur WebRTC : non exécuté

Le script `tests/browser_test.cjs` est fourni. Il crée une session, ouvre un faux téléphone Chromium qui implémente la signalisation et le DataChannel, puis teste réellement :

- connexion de la page iPad et offre WebRTC ;
- réception vidéo et absence d'étirement ;
- activation du contrôle, gestes down/move/up, coordonnées normalisées ;
- clavier, accents, Entrée, retour arrière, envoi complet ;
- changement de format portrait et mode remplir ;
- rechargement de session ;
- départ et retrait côté serveur.

Dans ce conteneur, Playwright a téléchargé Chromium mais le lancement a échoué :

```text
libnss3.so, libnspr4.so, libatk-1.0.so.0, libatk-bridge-2.0.so.0,
libcups.so.2, libXcomposite.so.1, libXdamage.so.1, libXfixes.so.3,
libXrandr.so.2, libxkbcommon.so.0, libpango-1.0.so.0, libcairo.so.2,
libasound.so.2, libatspi.so.0 → not found
```

`sudo` est bloqué (`no new privileges`) et `apt-get download` n'a pas de listes de paquets : impossible d'installer les dépendances système. Ce n'est **pas** un échec du test applicatif ; il n'a simplement pas pu démarrer. Sur un poste de développement avec Playwright/Chromium fonctionnel :

```bash
npx playwright install chromium
POTATO_TEST_URL=http://127.0.0.1:8088 node tests/browser_test.cjs
```

## 5. Ce qui reste à valider sur matériel réel

### Android

- [ ] Installation de l'APK sur Android 8, 12, 14/15.
- [ ] Consentement MediaProjection et capture réelle.
- [ ] Vidéo reçue sur iPad/PC, latence et qualité.
- [ ] Rotation portrait/paysage et redimensionnement de fenêtre.
- [ ] Modes fit/fill : pas de déformation, bandes/recadrage corrects.
- [ ] Clic, appui long, glissement, défilement ; précision au pixel.
- [ ] Activation/désactivation du contrôle et du clavier depuis le téléphone.
- [ ] Saisie accentuée, Entrée, retour arrière, collage dans plusieurs applications.
- [ ] Comportement des champs protégés et des écrans `FLAG_SECURE`.
- [ ] Arrêt immédiat, kick, notification, révocation de la capture.
- [ ] Plusieurs spectateurs simultanés avec formats différents.

### Réseau

- [ ] Téléphone en 4G et iPad en Wi-Fi (autre réseau).
- [ ] Deux réseaux domestiques différents.
- [ ] Sans TURN : identifier les cas d'échec.
- [ ] Avec TURN coturn : candidats `relay`, vidéo et contrôle.
- [ ] Coupure réseau, reconnexion, rechargement de page.

### o2switch

- [ ] Extraction réelle du ZIP, `.htaccess` interprété, HTTPS forcé.
- [ ] `health` avec `dataWritable=true`.
- [ ] Création/auth depuis l'APK, session complète.
- [ ] Limites PHP-FPM avec 2–3 spectateurs.
- [ ] Logs PHP et comportement en cas d'expiration.

## 6. Bugs corrigés pendant le développement

- Page de connexion sans conteneur `#toasts` : les messages flash n'apparaissaient pas.
- `pagehide` envoyait `viewer.leave` à chaque rechargement, invalidant la session : supprimé ; reprise via `session.info` et curseur persistant.
- Clavier désactivé hors ligne qui ne se réactivait pas après reconnexion : l'état `allowed` est maintenant suivi.
- Clé serveur annoncée dans `config.php` mais absente de l'application Android : ajoutée aux préférences, aux écrans, au client API et au service.
- Écran de démarrage sans logo : `splash_background` + thème Android 12+.
- Continuité du geste long : chemin de maintien avec un déplacement infime pour éviter un refus Android.
- `VirtualDisplay` nul non détecté : exception explicite.
- Test anti-bruteforce non reproductible : nom de session unique et remise à zéro des compteurs dans le script local.
- 1.0.1 : `EglBase.makeCurrent()` était appelé sans `EGLSurface` (`No EGLSurface - can't make current`). Le contexte de capture utilise maintenant un pbuffer hors écran 1×1 (`CONFIG_PIXEL_BUFFER`).

## 7. Limites connues (à ne pas masquer)

1. **Accessibilité obligatoire** pour le contrôle et le clavier ; Android ne fournit pas d'alternative.
2. Certaines applications refusent `ACTION_SET_TEXT` ou masquent leur contenu.
3. TURN externe indispensable pour garantir les réseaux restrictifs ; o2switch ne l'héberge pas.
4. Long-polling = un processus PHP par appareil en attente ; limite de l'hébergement mutualisé.
5. Plusieurs spectateurs = CPU/encodeurs multiples sur le téléphone.
6. APK signé avec une clé de démonstration.
7. Aucun test matériel réel dans cet environnement : ne pas présenter le produit comme certifié.
8. Le test navigateur automatisé est fourni mais n'a pas démarré ici.

## 8. Statut recommandé

- **Backend et build : validés.**
- **Web : prêt à déployer, à valider sur Apache/o2switch.**
- **Android : APK installable, à valider sur appareils réels.**
- **WebRTC/TURN : implémentés, non validés en conditions réelles.**

## 9. Livrables finaux

| Fichier | SHA-256 |
|---|---|
| `dist/PotatoRemote-Android-1.0.1.apk` | `f2582920686636252445d10f7aaa741874b90173827ebb1972280ad477587b8c` |
| `dist/PotatoRemote-Web-1.0.0-o2switch.zip` | `b143467c95b6e7348b81a7788d637a6755f1a5b4a269fefb603acd3b4a3436b2` |

Le ZIP a été extrait et testé : `index.html`, `.htaccess`, `api/index.php`, `api/config.php`, `assets/js/viewer.js`, `data/.htaccess` et `INSTALLATION.md` présents ; lint PHP OK ; `health` OK ; création locale OK.

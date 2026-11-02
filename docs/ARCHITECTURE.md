# Architecture de Potato Remote

## Vue d'ensemble

```text
┌──────────────────────────── Android ────────────────────────────┐
│ MainActivity (Compose)                                          │
│   consentement MediaProjection ──► ShareService (foreground)    │
│                                        │                        │
│                    ┌───────────────────┼───────────────────┐    │
│                    ▼                   ▼                   ▼    │
│             ScreenCaptureEngine   ApiClient           ViewerSession × N
│             (MediaProjection)     (HTTPS)             (1 PeerConnection
│                    │                                    par spectateur)  │
│                    │ trames I420                              │      │
│                    └──────────────► encodeur WebRTC ◄────────┘      │
│                                           │                          │
│                    AccessibilityService ◄── DataChannel contrôle     │
└───────────────────────────────────────────┼──────────────────────────┘
                                            │ WebRTC (DTLS-SRTP + SCTP)
                                            ▼
┌──────────────────────────── Navigateur ──────────────────────────┐
│ index.html → login.js → API PHP                                 │
│ viewer.html → viewer.js                                         │
│   PotatoPeer (WebRTC, offreur, DataChannel potato-ctrl)         │
│   TouchController → gestes normalisés                            │
│   RemoteKeyboard → texte / touches                               │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTPS + long-polling
                                ▼
┌────────────────────── o2switch : PHP ────────────────────────────┐
│ api/index.php (routeur)                                          │
│   Api.php        sessions, auth, signalisation, quotas          │
│   Store.php      fichiers JSON verrouillés + index de jetons    │
│   Ice.php        STUN / TURN éphémère                            │
│   data/          sessions/, tokens/, names/, ratelimit/          │
└─────────────────────────────────────────────────────────────────┘
```

Le serveur PHP ne voit **jamais** la vidéo ni les commandes : il relaie uniquement le SDP et les candidats ICE. Une fois WebRTC établi, le média et le DataChannel sont chiffrés de bout en bout entre le téléphone et le navigateur.

## Pourquoi cette architecture

### Pas de WebSocket permanent sur mutualisé

o2switch est un hébergement mutualisé PHP/Apache : on ne peut pas y faire tourner un démon WebSocket permanent ni un serveur TURN. La signalisation utilise donc du **long-polling HTTP** :

- `signal.poll` reste ouvert jusqu'à 18 s (max 24 s) ;
- dès qu'un message arrive, la requête rend la main ;
- aucun processus permanent côté site ;
- compatible PHP-FPM et Apache.

Conséquence : chaque téléphone et chaque spectateur immobilise un processus PHP pendant l'attente. Pour quelques appareils, c'est acceptable ; pour une audience importante, il faut augmenter la limite PHP-FPM ou déporter la signalisation. Cette limite est documentée, pas masquée.

### Une PeerConnection par spectateur

Chaque spectateur envoie sa propre offre. Le téléphone crée une `ViewerSession` indépendante avec :

- sa propre `PeerConnection` ;
- son propre `VideoSource`/`VideoTrack` ;
- son propre rendu OpenGL au format demandé ;
- son propre DataChannel de contrôle.

Ainsi, un iPad en 4:3 et un ordinateur en 16:9 peuvent être servis simultanément avec des cadrages différents, sans modifier l'affichage de l'autre.

### Capture et adaptation

`ScreenCaptureEngine` :

1. crée un `VirtualDisplay` MediaProjection à la résolution de capture choisie (qualité auto/540p/720p/1080p, plafonnée) ;
2. dessine l'écran dans une texture OpenGL partagée ;
3. pour chaque `Attachment`, dessine la texture dans un FBO au format exact du spectateur :
   - **fit** : image entière, bandes de fond floutées et assombries ;
   - **fill** : l'image couvre le cadre, débordement recadré ;
4. convertit chaque FBO en I420 avec `YuvConverter` ;
5. pousse la trame dans le `VideoSource` WebRTC du spectateur.

Le format demandé est calculé à partir du `viewport` envoyé par le navigateur : `aspect = largeur / hauteur` de la zone vidéo réelle. Un redimensionnement de fenêtre ou une rotation déclenche un nouveau `viewport` ; le téléphone refait le rendu et l'encodeur du spectateur concerné.

Le téléphone ne déforme jamais l'image : il utilise `contain` (fit) ou `cover` (fill). Pour éviter une image étirée, le navigateur affiche la vidéo avec `object-fit: contain`.

### Correspondance tactile

Le navigateur convertit les coordonnées client en coordonnées normalisées `[0,1]` par rapport à la zone vidéo réellement affichée (`videoContentRect`). Le téléphone inverse exactement le cadrage :

- `fit` : `scale = min(frameW/screenW, frameH/screenH)` ;
- `fill` : `scale = max(...)` ;
- puis conversion inverse avec centrage et clamp sur `[0, screenW-1] × [0, screenH-1]`.

La capture, l'affichage et le mapping utilisent le même modèle mathématique, ce qui garantit qu'un clic correspond au pixel sélectionné, même avec des bandes ou un recadrage.

Les gestes sont maintenus par segments `continueStroke` : un doigt reste posé entre deux messages, ce qui permet l'appui long, le glissement et le défilement. Un chemin de secours est rejoué si Android refuse une continuation.

### Clavier distant

Le navigateur envoie sur le DataChannel :

- `insert` pour le texte ajouté en frappe live ;
- `backspace` avec un nombre de caractères ;
- `set` pour le collage, le remplacement ou l'envoi complet ;
- `key` pour Entrée, Retour, Accueil, Échap, Retour arrière.

Android utilise `AccessibilityService` :

- `ACTION_SET_TEXT` sur le nœud éditable focalisé ;
- `ACTION_SET_SELECTION` pour replacer le curseur ;
- `ACTION_IME_ENTER` pour Entrée (repli : retour à la ligne) ;
- `GLOBAL_ACTION_BACK` / `HOME` pour les touches système.

Les champs protégés, les WebView et certaines applications refusent ces actions. Le refus est renvoyé au navigateur (`keyboard-result`), jamais masqué.

### Sessions et stockage

`Store` utilise des fichiers JSON avec `flock` :

- `sessions/<id>.json` : nom, hash du mot de passe, hash du jeton téléphone, spectateurs, messages de signalisation ;
- `tokens/<sha256>.json` : index des jetons (rôle, session, spectateur) ;
- `names/<sha1>.json` : index des noms actifs ;
- `ratelimit/<sha1>.json` : compteurs anti-bruteforce.

Aucun mot de passe ni jeton brut n'est écrit. Les sessions et jetons expirés sont nettoyés par un GC probabiliste à chaque requête.

### Sécurité

- HTTPS obligatoire (`allow_http=false`) ;
- mots de passe `password_hash()` / `password_verify()` ;
- jetons aléatoires 256 bits, stockés hachés ;
- anti-bruteforce par IP + nom de session, verrouillage temporaire ;
- quota de création par IP ;
- vérification du rôle et de l'appartenance à chaque commande ;
- `kick` immédiat : le spectateur reçoit `kicked` et son jeton est refusé ;
- arrêt du téléphone : `phone.stop` supprime la session et révoque tous les jetons ;
- contrôle et clavier révocables sur le téléphone ;
- aucune commande si l'autorisation correspondante est désactivée ;
- pas de cookie, pas de CORS permissif, en-têtes de sécurité dans `.htaccess`.

## Limites structurelles

1. **Accessibilité obligatoire** pour le contrôle/clavier ; Android ne permet pas l'injection autrement.
2. **Certaines applications refusent** la saisie ou masquent leur contenu (`FLAG_SECURE`).
3. **TURN externe nécessaire** pour garantir les réseaux restrictifs ; o2switch ne peut pas l'héberger.
4. **Long-polling** consomme un processus PHP par appareil en attente.
5. **Plusieurs spectateurs** augmentent le coût CPU (conversion I420 et encodage par spectateur).
6. **Pas de test matériel réel** dans l'environnement de développement fourni ; voir `TEST_REPORT.md`.
7. **APK de démonstration** signé avec une clé de test à remplacer pour une distribution publique.

## Arborescence

```
android/            application Kotlin/Compose + WebRTC
  app/src/main/java/com/potatoremote/
    control/        AccessibilityService, gestes, texte
    service/        foreground service, consentement
    signaling/      client HTTP, modèles, configuration ICE
    ui/             thème, composants, écrans, état partagé
    webrtc/         capture OpenGL, session par spectateur
  keystore/         clé de démonstration
brand/              génération du logo et des déclinaisons
web/                site livrable (index, viewer, PHP, assets)
  api/src/          Api.php, Store.php, Ice.php, Http.php, bootstrap.php
  assets/           CSS, modules JS, polices, images
tests/              tests HTTP backend + test navigateur WebRTC
docs/               documentation technique
dist/               APK et ZIP finaux
```

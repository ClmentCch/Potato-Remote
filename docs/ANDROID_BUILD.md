# Compiler et installer Potato Remote Android

## Prérequis

| Outil | Version utilisée / recommandée |
|---|---|
| JDK | 17 (le projet compile avec Java 17) |
| Android SDK | Platform 35, Build-Tools 35.0.0 |
| Gradle | 8.9 (wrapper fourni) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| WebRTC Android | `io.github.webrtc-sdk:android:137.7151.05` |
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 35 (Android 15) |

Une connexion Internet est nécessaire au premier build pour télécharger les dépendances Gradle et Maven.

## Configuration du SDK

Deux méthodes :

```bash
export ANDROID_HOME=/chemin/vers/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=/chemin/vers/jdk-17
```

ou créez `android/local.properties` (fichier local, non versionné) :

```properties
sdk.dir=/chemin/vers/android-sdk
```

## Compiler l'APK release

```bash
cd android
./gradlew assembleRelease
```

L'APK est généré ici :

```
android/app/build/outputs/apk/release/app-release.apk
```

Build de développement (débogage, clé debug, HTTP local autorisé) :

```bash
./gradlew assembleDebug
```

L'APK debug utilise l'identifiant `com.potatoremote.debug` et peut cohabiter avec la version release.

## Signature

Le build release utilise :

- keystore : `android/keystore/potato-remote-release.jks`
- alias : `potatoremote`
- mots de passe : `potato-remote`

Ces valeurs sont dans `android/app/build.gradle.kts`. **C'est une clé de démonstration fournie pour que l'APK soit installable immédiatement.** Pour une distribution publique ou une mise à jour sur le Play Store, créez votre propre keystore et ne publiez jamais le mot de passe :

```bash
keytool -genkeypair -v -keystore potato-remote-release.jks \
  -alias potatoremote -keyalg RSA -keysize 2048 -validity 10000
```

Modifiez ensuite `signingConfigs` ou utilisez des variables d'environnement / `keystore.properties` ignoré par Git. Une APK signée avec une autre clé ne peut pas mettre à jour une APK installée avec l'ancienne.

Vérifier la signature :

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## Installer sur le téléphone

### Par fichier

1. Copiez `app-release.apk` sur le téléphone (câble, cloud, e-mail).
2. Ouvrez le fichier avec le gestionnaire de fichiers.
3. Autorisez l'installation depuis cette source si Android le demande.
4. Installez puis ouvrez **Potato Remote**.

### Par ADB

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.potatoremote/.MainActivity
```

## Première utilisation

1. **Réglages** → adresse du site (`https://…`, sans `/api`) ; testez la connexion.
2. Facultatif : **Clé serveur** si `create_secret` est défini dans `config.php`.
3. Activez le service d'accessibilité **Potato Remote — contrôle distant** :
   - Android 8–12 : Paramètres → Accessibilité → Services installés ;
   - Android 13+ : Paramètres → Accessibilité → Applications installées ;
   - ou bouton direct dans Potato Remote.
4. Accueil → **Démarrer le partage**.
5. Choisissez le nom de session, le mot de passe, les autorisations, la qualité et le nombre de spectateurs.
6. Acceptez la boîte de dialogue Android **« Commencer maintenant à capturer… »**.
7. La session passe en « Partage en cours ». Le lien et le nom sont affichés ; la notification permet d'arrêter.

L'écran de consentement MediaProjection est une obligation Android : sans lui, aucune capture n'est possible. Potato Remote ne peut pas le contourner.

## Autorisations demandées

- `INTERNET`, `ACCESS_NETWORK_STATE` : signalisation et WebRTC.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` : capture visible pendant que l'écran est partagé.
- `POST_NOTIFICATIONS` : notification de partage (Android 13+).
- `WAKE_LOCK` : maintenir la session pendant la diffusion.
- `BIND_ACCESSIBILITY_SERVICE` : uniquement pour le service d'accessibilité optionnel, déclaré par Android.

Aucune permission de stockage, de contacts, de localisation ou de micro n'est demandée.

## Fonctionnement du service d'accessibilité

Le contrôle tactile et le clavier utilisent `AccessibilityService` :

- `dispatchGesture()` pour les clics, appuis longs, glissements et défilements ;
- `ACTION_SET_TEXT`, `ACTION_SET_SELECTION` et `ACTION_IME_ENTER` pour le texte ;
- `GLOBAL_ACTION_BACK` / `HOME` pour les touches retour et accueil.

Android interdit l'injection sans ce service. Les applications qui protègent leurs champs (mots de passe, WebView, éditeurs personnalisés, certaines apps bancaires) peuvent refuser l'opération ; Potato Remote renvoie alors un refus explicite au spectateur au lieu de simuler un succès.

Le service peut être désactivé à tout moment dans les paramètres Android. Le partage vidéo continue, mais le contrôle et le clavier cessent immédiatement.

## Cycle de vie et arrêt

- Le partage tourne dans un foreground service avec notification persistante.
- Le bouton **Arrêter le partage** et l'action **Arrêter** de la notification terminent la session et révoquent les jetons.
- Si l'utilisateur révoque la capture depuis Android (notification système), la session est arrêtée et le serveur est prévenu.
- Une session abandonnée expire côté serveur (12 h par défaut) ; le téléphone est considéré hors ligne après 30 s.

## Dépannage

| Problème | Cause / solution |
|---|---|
| `Adresse du serveur invalide` | URL HTTPS incorrecte ; ne pas inclure `/api` |
| `Serveur injoignable` | DNS, SSL, site non déployé, ou téléphone hors Internet |
| `Ce nom de session est déjà utilisé` | session active avec le même nom ; changez de nom ou attendez |
| `invalid_server_key` | clé serveur absente ou différente de `create_secret` |
| Vidéo noire | capture refusée, encodeur indisponible, ou réseau bloqué ; tester avec TURN |
| Contrôle inactif | service d'accessibilité désactivé ou autorisation coupée sur le téléphone |
| Clavier sans effet | champ non éditable, application protégée, ou accessibilité inactive |
| L'APK refuse de s'installer | version Android < 8.0, ou APK debug/release avec une autre signature déjà installée (désinstaller l'ancienne) |
| Build : SDK introuvable | définir `ANDROID_HOME` ou `local.properties` |
| Build : Java introuvable | utiliser JDK 17 et `JAVA_HOME` |

## Limites connues

- La capture d'écran et l'injection exigent le consentement de l'utilisateur ; aucune application ne peut les contourner.
- Le contrôle ne fonctionne pas sur les écrans sécurisés (`FLAG_SECURE`) : la capture peut être noire.
- Le clavier dépend du champ actif et de l'application.
- Plusieurs spectateurs multiplient les conversions/encodages : privilégier 1–3 spectateurs en 720p sur un téléphone milieu de gamme.
- Sans TURN, les réseaux NAT symétriques/4G restrictifs peuvent échouer.
- L'APK fourni est signé avec une clé de test ; remplacez-la pour une vraie distribution.

# Tests Potato Remote

Deux suites sont fournies :

- `backend_test.py` : tests HTTP réels de l'API PHP (sessions, auth, jetons, quotas, signalisation, long-polling).
- `browser_test.cjs` : test navigateur Playwright avec un faux téléphone WebRTC (vidéo, DataChannel, gestes, clavier, rechargement).

## Tests backend

### Préparer un serveur local

Le serveur de test doit accepter HTTP en clair. Modifier temporairement `web/api/config.php` :

```php
'allow_http' => true,
```

Puis :

```bash
PHP_CLI_SERVER_WORKERS=8 php -S 127.0.0.1:8088 -t web
```

Dans un autre terminal :

```bash
python3 tests/backend_test.py
```

Le script :

- utilise `POTATO_TEST_URL` (défaut `http://127.0.0.1:8088`) ;
- remet à zéro uniquement les compteurs `web/data/ratelimit` pour être reproductible ;
- crée puis arrête ses sessions ;
- affiche `PASS`/`FAIL` et un code de sortie non nul en cas d'échec.

Résultat attendu : `81/81 tests réussis`.

**Ne lancez jamais ce script contre un site de production** : il crée des sessions et modifie les compteurs locaux.

Après le test :

- remettre `'allow_http' => false` ;
- supprimer les données de test (`web/data/sessions`, `tokens`, `names`, `ratelimit`) ;
- ne pas versionner `api/config.php` avec des secrets.

## Test navigateur

### Prérequis

- Node.js 18+ ;
- Playwright avec Chromium ;

```bash
mkdir -p ~/potato-tests && cd ~/potato-tests
npm init -y
npm install playwright
npx playwright install chromium
```

Sur Linux, Playwright peut avoir besoin des bibliothèques système (`libnss3`, `libatk`, `libcups`, `libpango`, etc.). Utiliser `npx playwright install-deps chromium` avec les droits administrateur, ou une image de développement Playwright.

### Exécution

Avec le serveur PHP local (`allow_http=true`) :

```bash
export PLAYWRIGHT_MODULE="$HOME/potato-tests/node_modules/playwright"
POTATO_TEST_URL=http://127.0.0.1:8088 node tests/browser_test.cjs
```

Si Playwright est installé dans `~/toolchain/pw`, le chemin par défaut fonctionne sans variable.

Le test :

1. crée une session ;
2. ouvre un faux téléphone dans une seconde page Chromium (canvas + WebRTC + relais de signalisation) ;
3. se connecte sur la page de connexion avec un mauvais mot de passe, vérifie l'erreur, puis avec le bon ;
4. attend la vidéo et vérifie que le format correspond au cadre ;
5. active le contrôle, simule un glissement et vérifie les messages `touch` ;
6. ouvre le clavier, tape des accents, Entrée, retour arrière et vérifie les messages ;
7. redimensionne en portrait et vérifie l'envoi du nouveau viewport ;
8. teste le bouton de cadrage ;
9. recharge la page et vérifie la reconnexion ;
10. quitte et vérifie la suppression du spectateur.

Il ne teste pas l'application Android réelle : le faux téléphone reproduit le protocole attendu.

### État dans l'environnement de développement fourni

Le test n'a pas pu démarrer : Chromium a été téléchargé, mais les bibliothèques système listées dans `docs/TEST_REPORT.md` manquent, et l'installation système est bloquée (`no new privileges`, pas de root). Le script est fourni pour être exécuté sur un poste disposant de Playwright fonctionnel.

## Vérifications statiques rapides

```bash
# Syntaxe JS
for f in web/assets/js/*.js brand/build-brand.mjs tests/browser_test.cjs; do node --check "$f"; done

# Lint PHP
for f in web/api/index.php web/api/config.php web/api/src/*.php; do php -l "$f"; done

# APK
apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
zipalign -c -v 4 android/app/build/outputs/apk/release/app-release.apk
```

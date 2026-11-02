# Installation de Potato Remote Web sur o2switch

Ce dossier est le site à héberger. Il ne nécessite **ni Node.js, ni Docker, ni serveur dédié** : uniquement PHP 8.1+ et Apache avec les modules habituels d'o2switch.

## 1. Contenu du ZIP

```
index.html                  page de connexion
viewer.html                 récepteur (chargé après authentification)
.htaccess                   HTTPS, sécurité, cache, en-têtes
assets/                     CSS, JS, polices, logo, favicon, icônes
api/index.php               point d'entrée unique de l'API
api/config.php              configuration à personnaliser
api/src/                    code PHP interne (non accessible)
api/.htaccess               protections API
data/.htaccess              protection du stockage
manifest.webmanifest        installation PWA
robots.txt
INSTALLATION.md             ce fichier
```

Le dossier `data/` peut rester vide : les sous-dossiers `sessions/`, `tokens/`, `names/`, `ratelimit/` sont créés automatiquement au premier appel.

## 2. Envoyer les fichiers

1. Dans cPanel o2switch, ouvrez **Gestionnaire de fichiers**.
2. Placez-vous dans le dossier racine du domaine (souvent `public_html`) ou dans un sous-dossier de votre choix.
3. Téléversez le ZIP puis utilisez **Extraire**.
4. Vérifiez que `index.html` est bien à la racine du site choisi.

Le site peut être installé dans un sous-dossier : les URL sont relatives et `potato_base_url()` détecte le préfixe.

## 3. PHP et permissions

- Dans cPanel → **MultiPHP Manager**, sélectionnez **PHP 8.1, 8.2 ou 8.3** pour le domaine.
- Extensions nécessaires : `openssl`, `mbstring`, `json` (standard).
- Le dossier `data/` doit être inscriptible par PHP : en général `755` suffit sur o2switch. Si l'écriture échoue, essayez `775` sans rendre le dossier public.
- Ne donnez jamais `777` à `data/`.

## 4. Configuration

Ouvrez `api/config.php` et ajustez au minimum :

```php
'base_url' => '', // laisser vide : détection automatique recommandée
'data_dir' => dirname(__DIR__) . '/data',
'allow_http' => false, // OBLIGATOIREMENT false en production
```

Paramètres utiles :

| Clé | Rôle |
|---|---|
| `session_ttl` | durée de vie d'une session (secondes), 43200 par défaut |
| `phone_offline_after` | délai avant de considérer le téléphone hors ligne |
| `viewer_idle_timeout` | retrait d'un spectateur inactif |
| `max_viewers_limit` / `max_viewers_default` | quota de spectateurs |
| `login_attempts_max` / `login_lock_seconds` | anti-bruteforce |
| `allow_public_create` | autorise la création de session par l'application |
| `create_secret` | clé facultative exigée pour créer une session |
| `ice_servers` | serveurs STUN |
| `turn` | relais TURN (voir plus bas) |

### Clé serveur facultative

Si vous renseignez `create_secret`, l'application Android doit saisir la même valeur dans **Réglages → Clé serveur** (ou à la création de session). Sans cette clé, la création renverra `invalid_server_key`. Laissez `create_secret` vide si vous ne voulez pas cette protection supplémentaire.

### Derrière Cloudflare ou un proxy

Si o2switch est derrière un proxy de confiance, mettez `trusted_proxy => true` pour que l'IP cliente (anti-bruteforce) et le protocole HTTPS soient lus dans `X-Forwarded-For` / `X-Forwarded-Proto`. Ne l'activez pas si vous n'êtes pas certain du proxy.

## 5. HTTPS

Le `.htaccess` force HTTPS et ajoute HSTS lorsque HTTPS est actif. Activez **AutoSSL** dans cPanel o2switch et vérifiez que le certificat couvre bien le domaine (et le sous-domaine si utilisé).

Testez :

```
https://votre-domaine/
```

Une redirection HTTP → HTTPS doit se produire. Si vous obtenez une erreur 500, vérifiez que `mod_rewrite` et `mod_headers` sont actifs (ils le sont normalement chez o2switch).

## 6. Vérifier l'installation

Ouvrez :

```
https://votre-domaine/api/index.php?action=health
```

Réponse attendue : `{"ok":true,"data":{...}}` avec `dataWritable: true`. Le champ `warnings` doit être vide, ou contenir uniquement les avertissements que vous acceptez (TURN absent, HTTPS local).

Puis :

```
https://votre-domaine/api/index.php?action=config
```

Vous devez voir `siteName`, `iceServers`, `turnConfigured` et `serverTime`.

## 7. Connecter l'application Android

Dans Potato Remote Android → **Réglages** :

- **Adresse du site** : `https://votre-domaine` (sans `/api`, sans slash final) ;
- **Clé serveur** : uniquement si `create_secret` est défini ;
- testez la connexion.

Créez ensuite une session dans l'application, puis ouvrez le site sur l'iPad avec le nom et le mot de passe.

## 8. TURN : indispensable dans certains réseaux

STUN suffit souvent entre deux réseaux domestiques. Il ne suffit pas en 4G/5G, derrière un NAT symétrique ou un réseau d'entreprise restrictif. Potato Remote affiche un avertissement visible lorsque TURN n'est pas configuré.

o2switch **ne peut pas héberger un serveur TURN** (service permanent UDP/TCP et ports dédiés). Il faut un serveur externe, par exemple coturn sur un VPS :

```php
'turn' => [
    'urls' => [
        'turn:turn.mondomaine.fr:3478?transport=udp',
        'turns:turn.mondomaine.fr:5349?transport=tcp',
    ],
    'shared_secret' => 'le-secret-static-auth-secret-de-coturn',
    'username' => '',
    'credential' => '',
    'ttl' => 3600,
],
```

Avec `shared_secret`, Potato Remote génère des identifiants TURN éphémères (HMAC-SHA1, TURN REST API). La procédure complète est dans `docs/TURN.md` du projet source.

Sans configuration TURN, la vidéo fonctionnera sur beaucoup de réseaux, mais **aucune garantie** n'est possible entre deux réseaux très restrictifs.

## 9. Long-polling et limites PHP

La signalisation utilise des requêtes HTTP maintenues ouvertes jusqu'à 24 secondes (`signal.poll`). Pendant ce temps, chaque téléphone et chaque spectateur occupe un processus PHP-FPM. Sur un hébergement mutualisé :

- vérifiez la limite de processus PHP de votre offre ;
- pour plusieurs spectateurs simultanés, augmentez si possible `max_children` (selon o2switch) ;
- sinon, limitez le nombre de spectateurs ou hébergez la signalisation sur un service séparé.

La vidéo et les commandes ne passent pas par PHP : seul le petit trafic de signalisation consomme ces processus.

## 10. Sécurité et exploitation

- Ne laissez jamais `allow_http => true` en production.
- Ne versionnez pas `api/config.php` avec un secret TURN réel.
- Sauvegardez `data/` si vous voulez conserver les sessions, mais elles expirent de toute façon.
- Surveillez les journaux PHP en cas d'erreur 500.
- Le dossier `data/` et `api/src/` sont bloqués par `.htaccess` ; ne retirez pas ces protections.
- Les jetons sont stockés hachés ; un vol de fichier `data/` ne donne pas directement les jetons.
- Le propriétaire du téléphone peut arrêter le partage à tout moment, retirer un spectateur et couper le contrôle ou le clavier.

## 11. Dépannage

| Symptôme | Piste |
|---|---|
| `https_required` | `allow_http` doit être `false` et le site doit être servi en HTTPS |
| `server_error` | consulter les logs PHP ; souvent `data/` non inscriptible |
| `Serveur injoignable` | URL incorrecte, SSL invalide, ou site pas encore déployé |
| `name_taken` | session existante avec le même nom ; attendez son expiration ou changez de nom |
| `invalid_token` après arrêt | normal : `phone.stop` révoque tous les jetons de la session |
| Vidéo noire mais contrôle OK | codec non négocié, TURN bloqué, ou appareil trop ancien |
| Connexion impossible en 4G | configurer TURN |
| Le clavier ne saisit rien | service d'accessibilité inactif, champ protégé, ou application qui refuse `ACTION_SET_TEXT` |
| 500 sur le site | Apache : vérifier `mod_rewrite`, `mod_headers`, `Require all denied` (Apache 2.4) |

## 12. Mise à jour

Remplacez les fichiers du site **sauf** `api/config.php` et le contenu de `data/` (ou sauvegardez-les avant). Vérifiez ensuite `health`, puis recréez une session de test.

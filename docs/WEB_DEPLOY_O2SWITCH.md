# Déployer Potato Remote Web sur o2switch

Ce document complète `web/INSTALLATION.md` avec les particularités cPanel/o2switch. Le ZIP livré est `dist/PotatoRemote-Web-1.0.0-o2switch.zip`.

## 1. Vérifications avant déploiement

- Domaine ou sous-domaine avec **HTTPS actif** (AutoSSL o2switch).
- **PHP 8.1+** sélectionné pour le domaine (MultiPHP Manager).
- Accès au **Gestionnaire de fichiers** cPanel.
- Aucun Node.js, Docker ou serveur dédié n'est nécessaire.

Le site fonctionne à la racine du domaine ou dans un sous-dossier. Évitez les caractères spéciaux dans le chemin.

## 2. Téléversement

1. cPanel → Gestionnaire de fichiers → `public_html` (ou sous-dossier).
2. Téléverser le ZIP.
3. Clic droit → **Extraire**.
4. Vérifier la présence de `index.html`, `.htaccess` (fichiers cachés activés), `api/`, `assets/`, `data/`.

Si le gestionnaire de fichiers n'affiche pas `.htaccess`, activez **Afficher les fichiers cachés**. Le ZIP contient bien ce fichier ; sans lui, HTTPS et les protections ne sont pas appliqués.

## 3. Configuration PHP

Éditer `api/config.php` :

```php
'base_url' => '',            // détection auto (recommandé)
'allow_http' => false,       // impératif en production
'session_ttl' => 43200,      // 12 h
'phone_offline_after' => 30,
'viewer_idle_timeout' => 600,
'max_viewers_default' => 4,
'allow_public_create' => true,
'create_secret' => '',
```

Pour une installation privée, définir `create_secret` avec une valeur aléatoire longue :

```bash
openssl rand -base64 32
```

et saisir la même valeur dans l'application Android (Réglages → Clé serveur). Cela empêche un tiers de créer des sessions même s'il connaît l'URL.

### Proxy / Cloudflare

Si un proxy de confiance termine HTTPS, mettre `trusted_proxy => true`. Sinon laisser `false` : l'IP client et le protocole seraient pris dans des en-têtes falsifiables.

## 4. Permissions

```bash
chmod 755 data
# si nécessaire selon l'utilisateur PHP d'o2switch :
chmod 775 data
```

Ne jamais utiliser `777`. Le dossier `data/` contient les sessions et les index de jetons ; il doit être inscriptible, mais pas accessible par le web (le `.htaccess` le bloque).

Si `health` renvoie `dataWritable: false`, vérifier le propriétaire/groupe avec le support o2switch ou le gestionnaire de permissions cPanel.

## 5. Vérification

```
https://votre-domaine/api/index.php?action=health
```

Attendu :

```json
{"ok":true,"data":{"ok":true,"php":"8.x","https":true,"dataWritable":true,"turnConfigured":false,"sessions":0,"warnings":[]}}
```

Puis `?action=config` doit renvoyer `siteName`, `iceServers`, `turnConfigured`, `serverTime`.

Tester la page de connexion et l'icône. En cas de 500, consulter les logs d'erreurs PHP (cPanel → Errors) : les causes fréquentes sont un `data/` non inscriptible, un PHP trop ancien, ou un `.htaccess` incompatible.

## 6. Long-polling et PHP-FPM

Chaque téléphone et chaque spectateur maintient un `signal.poll` ouvert jusqu'à 18 s. Un processus PHP-FPM est occupé pendant ce temps. Sur un mutualisé :

- vérifier la limite de processus de votre offre ;
- pour 1 téléphone + 2 iPad, prévoir au moins 4–5 processus ;
- pour davantage de spectateurs, augmenter `max_children` si l'offre le permet, ou déporter la signalisation.

La vidéo ne traverse pas PHP : seule la signalisation consomme ces processus.

## 7. TURN

o2switch ne peut pas héberger un serveur TURN permanent. Pour les connexions 4G/5G ou NAT strict, installer coturn sur un VPS et renseigner :

```php
'turn' => [
    'urls' => ['turn:turn.mondomaine.fr:3478?transport=udp', 'turns:turn.mondomaine.fr:5349?transport=tcp'],
    'shared_secret' => 'secret-coturn',
    'username' => '',
    'credential' => '',
    'ttl' => 3600,
],
```

Voir `docs/TURN.md`. Sans TURN, l'interface affiche un avertissement explicite et la connexion peut échouer entre réseaux restrictifs.

## 8. Sécurité et exploitation

- HTTPS obligatoire ; le `.htaccess` redirige HTTP → HTTPS et ajoute HSTS.
- `.htaccess` bloque `data/`, `api/src/`, `config.php` et les fichiers `.json/.md/.log/.sql/...`.
- Aucun secret dans le HTML ou le JS.
- Mots de passe hashés, jetons hashés, anti-bruteforce.
- Sessions expirées nettoyées automatiquement.
- Sauvegarder `data/` uniquement si nécessaire ; les sessions expirent.
- Après mise à jour, remplacer les fichiers sauf `api/config.php` et `data/`.

## 9. Dépannage o2switch

| Symptôme | Action |
|---|---|
| 403 sur tout le site | vérifier permissions fichiers/dossiers (644/755) |
| 500 à l'ouverture | PHP 8.1+ ? logs cPanel ; `.htaccess` incompatible ? |
| Redirection HTTPS en boucle | proxy mal configuré ; `trusted_proxy` et `X-Forwarded-Proto` |
| `https_required` | `allow_http` doit rester `false` ; accéder en HTTPS |
| `dataWritable:false` | corriger permissions `data/` |
| `Serveur injoignable` dans l'app | URL sans `/api`, SSL valide, DNS |
| `name_taken` | ancienne session active ; attendre expiration ou changer de nom |
| Vidéo noire | codec/TURN/écran sécurisé ; tester en local et avec TURN |
| Longues attentes / timeouts | limite PHP-FPM atteinte ; réduire les spectateurs |

## 10. Mise à jour et retour arrière

1. Sauvegarder `api/config.php` et `data/`.
2. Extraire la nouvelle version par-dessus, sans écraser la config.
3. Vérifier `health`, créer une session de test.
4. En cas de problème, restaurer l'ancien ZIP et la config.

Le ZIP ne contient aucune donnée de session : il est reproductible et peut être redéployé sans fuite.

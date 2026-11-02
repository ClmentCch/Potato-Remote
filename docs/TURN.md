# TURN : garantir les connexions entre réseaux différents

## Pourquoi TURN est nécessaire

STUN permet aux appareils de découvrir leur adresse publique et de tenter une connexion directe. Cela fonctionne souvent entre deux réseaux domestiques, mais échoue lorsque :

- le téléphone est en 4G/5G derrière un NAT symétrique ;
- l'entreprise bloque l'UDP sortant ou les connexions directes ;
- les deux réseaux appliquent un NAT restrictif ;
- un routeur ou un pare-feu refuse les flux entrants.

TURN relaie alors le média chiffré par un serveur public. C'est une dépendance **externe** : o2switch, hébergement mutualisé PHP/Apache, ne permet pas d'exécuter ce service permanent. Potato Remote affiche un avertissement si `turnConfigured` est faux.

## Prérequis

- Un VPS ou serveur dédié avec une adresse publique (IPv4, idéalement IPv6).
- Un domaine ou sous-domaine, par exemple `turn.mondomaine.fr`, pointant vers ce serveur.
- Ports ouverts :
  - `3478/udp` et `3478/tcp` (TURN) ;
  - `5349/tcp` (TURN over TLS) ;
  - une plage UDP pour les allocations, par exemple `49152–65535/udp`.
- Certificat TLS valide (Let's Encrypt) pour `turns:`.

## Installer coturn (Debian/Ubuntu)

```bash
sudo apt update
sudo apt install -y coturn
sudo systemctl enable coturn
```

Sur Debian/Ubuntu, activer le service dans `/etc/default/coturn` :

```bash
sudo sed -i 's/^#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
```

## Configuration

Éditer `/etc/turnserver.conf` :

```ini
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
use-auth-secret
static-auth-secret=REMPLACER_PAR_UN_SECRET_LONG
realm=turn.mondomaine.fr
server-name=turn.mondomaine.fr

cert=/etc/letsencrypt/live/turn.mondomaine.fr/fullchain.pem
pkey=/etc/letsencrypt/live/turn.mondomaine.fr/privkey.pem

min-port=49152
max-port=65535
no-cli
no-loopback-peers
no-multicast-peers
stale-nonce=600
```

Générer le secret :

```bash
openssl rand -hex 32
```

Ne pas utiliser `static-auth-secret` comme mot de passe utilisateur : c'est le secret partagé qui permet à Potato Remote de fabriquer des identifiants **éphémères**.

## Certificat TLS

Avec certbot en mode standalone (port 80 libre) :

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d turn.mondomaine.fr
```

Ajouter un hook de renouvellement pour redémarrer coturn :

```bash
sudo tee /etc/letsencrypt/renewal-hooks/deploy/restart-coturn.sh >/dev/null <<'EOF'
#!/bin/sh
systemctl restart coturn
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/restart-coturn.sh
```

Redémarrer et vérifier :

```bash
sudo systemctl restart coturn
sudo systemctl status coturn --no-pager
sudo ss -lunpt | grep -E '3478|5349'
```

## Pare-feu

Exemple avec UFW :

```bash
sudo ufw allow 3478/udp
sudo ufw allow 3478/tcp
sudo ufw allow 5349/tcp
sudo ufw allow 49152:65535/udp
sudo ufw reload
```

Adapter la plage d'allocations à `min-port`/`max-port`.

## Configurer Potato Remote

Dans `web/api/config.php` :

```php
'turn' => [
    'urls' => [
        'turn:turn.mondomaine.fr:3478?transport=udp',
        'turn:turn.mondomaine.fr:3478?transport=tcp',
        'turns:turn.mondomaine.fr:5349?transport=tcp',
    ],
    'shared_secret' => 'LE_SECRET_DE_COTURN',
    'username' => '',
    'credential' => '',
    'ttl' => 3600,
],
```

Potato Remote génère alors pour chaque client :

```text
username   = <timestamp_expiration>:potato
credential = base64(HMAC-SHA1(shared_secret, username))
```

C'est la convention TURN REST API de coturn. L'identifiant expire après `ttl` secondes ; inutile de créer des comptes.

Si vous préférez des identifiants statiques (moins recommandé), utilisez `username`/`credential` et laissez `shared_secret` vide. Un TURN ouvert sans authentification est un relais abusable : ne faites pas cela.

## Tester

1. Vérifier `https://votre-site/api/index.php?action=config` : `turnConfigured` doit être `true`.
2. Ouvrir un test Trickle ICE (page WebRTC) et vérifier qu'un candidat `relay` apparaît avec `turn:` ou `turns:`.
3. Depuis un téléphone en 4G et un iPad sur un autre réseau, créer une session et vérifier la vidéo. Les statistiques WebRTC doivent montrer une paire `relay` si la connexion directe échoue.
4. Tester aussi en coupant le Wi-Fi de l'iPad (4G) pour vérifier le relais.

Exemple de test coturn en ligne de commande (identifiants statiques ou éphémères générés) :

```bash
turnutils_uclient -v -u '<username>' -w '<credential>' -p 3478 turn.mondomaine.fr
```

## Sécurité et exploitation

- `no-multicast-peers`, `no-loopback-peers`, `no-cli` limitent les abus.
- Limiter les quotas (`user-quota`, `total-quota`) selon le nombre d'utilisateurs.
- Surveiller la bande passante : un TURN relaie tout le média, contrairement à STUN.
- Garder le secret hors du dépôt Git et hors du ZIP public ; il est dans `config.php` sur le serveur.
- Le média reste chiffré (DTLS-SRTP) entre les pairs ; le serveur TURN voit les métadonnées et relaie les paquets, il ne déchiffre pas le contenu.
- Un certificat TLS valide est obligatoire pour `turns:` ; un certificat auto-signé est refusé par les navigateurs.

## Alternatives

- **Coturn auto-hébergé** : recommandé si vous avez un VPS.
- **Service TURN managé** (fournisseur VoIP/WebRTC) : renseigner les URL et identifiants fournis ; utiliser des identifiants éphémères si le fournisseur le permet.
- **STUN seul** : suffisant dans certains réseaux, insuffisant pour garantir 4G/NAT strict. Potato Remote le signale clairement.

o2switch ne peut pas héberger ce service : la documentation o2switch ne doit donc pas promettre une connexion garantie sans dépendance externe.

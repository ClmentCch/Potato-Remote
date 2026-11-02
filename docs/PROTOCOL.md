# Protocole Potato Remote

Toutes les actions HTTP passent par `api/index.php?action=…`. Les réponses sont JSON et suivent :

```json
{"ok": true, "data": { ... }}
```

ou :

```json
{"ok": false, "error": {"code": "auth_failed", "message": "…", "retryAfter": 0}}
```

Aucun cookie. Les jetons sont envoyés par `Authorization: Bearer <token>`, `X-Potato-Token: <token>` ou le champ `token` du corps. L'application Android envoie les deux en-têtes ; le site envoie les deux aussi.

## Actions HTTP

| Action | Méthode | Jeton | Rôle |
|---|---|---|---|
| `config` | GET | — | configuration publique (STUN/TURN, limites) |
| `health` | GET | — | diagnostic PHP/HTTPS/écriture/TURN |
| `session.create` | POST | — ou téléphone | création/reprise d'une session |
| `session.auth` | POST | — | authentification d'un spectateur |
| `session.info` | POST | viewer | reprise après rechargement |
| `phone.status` | POST | phone | présence + liste des spectateurs |
| `phone.update` | POST | phone | réglages, quota, kick |
| `phone.stop` | POST | phone | fin de session + révocation |
| `viewer.heartbeat` | POST | viewer | présence, viewport, stats, activité |
| `viewer.leave` | POST | viewer | départ volontaire |
| `signal.send` | POST | phone/viewer | offre, answer, ICE, bye, notice |
| `signal.poll` | POST | phone/viewer | long-polling de signalisation |

### Création

```json
POST session.create
{
  "session": "ClementPhone",
  "password": "secret",
  "device": "Pixel 8",
  "phone_token": "…optionnel pour reprendre…",
  "server_key": "…optionnel si create_secret…",
  "screen": {"width":1080,"height":2400,"rotation":0,"density":2.75},
  "settings": {"control":true,"keyboard":true,"max_viewers":4,"quality":"auto"}
}
```

Réponse : `sessionId`, `name`, `phoneToken` (secret à conserver côté téléphone), `viewerUrl`, `iceServers`, `turnConfigured`, `expiresAt`, `cursor`, `settings`, `viewerCount`.

Le nom est insensible à la casse et aux accents pour l'index (`potato_name_key`), mais le libellé d'origine est conservé. Un nom déjà utilisé avec le même `phone_token` reprend la session ; sans le bon jeton, la création est refusée (`409 name_taken`) tant que le téléphone est récent.

### Authentification spectateur

```json
POST session.auth
{
  "session": "ClementPhone",
  "password": "secret",
  "device": "iPad Air",
  "viewport": {"w":1194,"h":834,"dpr":2}
}
```

Réponse : `viewerToken`, `viewerId`, `cursor`, `settings`, `phoneOnline`, `iceServers`, `expiresAt`, etc.

Le viewport `w/h` est fourni en pixels CSS × devicePixelRatio (borné par le serveur). L'aspect est recalculé côté serveur pour éviter les incohérences.

### Signalisation

```json
POST signal.send       (viewer)
{"type":"offer","payload":{"sdp":"v=0…","viewport":{"w":1194,"h":834},"mode":"fit"}}

POST signal.send       (phone, vers un spectateur)
{"type":"answer","to":"<viewerId>","payload":{"sdp":"v=0…"}}

POST signal.send       (viewer ou phone)
{"type":"ice","payload":{"candidate":"candidate:…","sdpMid":"0","sdpMLineIndex":0}}

POST signal.poll
{"cursor":42,"wait":18}
```

Le serveur valide la taille du SDP, les types autorisés par rôle et les candidats ICE. La réponse contient `messages` (seq, from, viewerId, type, payload, ts), le nouveau `cursor`, et un instantané de présence (`phoneOnline`, `viewerCount`, `settings`, `viewers` pour le téléphone).

Le long-poll attend jusqu'à `wait` secondes (max 24), avec un sommeil interne de 250 ms. Il est réveillé dès qu'un message destiné au rôle apparaît. Les messages sont conservés 90 s et limités à 200 par session.

### Réglages et expulsion

```json
POST phone.update
{"control":false,"keyboard":true,"allow_new_viewers":true,"max_viewers":3,"quality":"720p"}
```

ou :

```json
POST phone.update
{"kick":"<viewerId>"}
```

Un changement de réglages génère des messages `state` vers les spectateurs. Un kick marque le spectateur et lui envoie `bye` avec `reason:"kicked"` ; toutes ses requêtes suivantes sont refusées (`403 kicked`).

## DataChannel `potato-ctrl`

Le navigateur crée le canal (`ordered: true`). Le téléphone le reçoit via `onDataChannel`. Les messages sont du JSON texte UTF-8, taille maximale 64 Ko.

### Navigateur → téléphone

```json
{"t":"hello","viewerId":"…","label":"iPad · Safari","viewport":{"w":1194,"h":834,"dpr":2,"aspect":1.4317},"mode":"fit"}
{"t":"ping","ts":123.45}
{"t":"viewport","w":1194,"h":834,"dpr":2,"aspect":1.4317,"mode":"fill"}
{"t":"control","active":true}
{"t":"touch","a":"down","x":0.42,"y":0.61,"ts":...}
{"t":"touch","a":"move","x":0.45,"y":0.63}
{"t":"touch","a":"up","x":0.46,"y":0.64}
{"t":"touch","a":"cancel"}
{"t":"keyboard","active":true}
{"t":"text","op":"insert","text":"é"}
{"t":"text","op":"backspace","count":1}
{"t":"text","op":"set","text":"texte complet"}
{"t":"key","key":"enter"}  // enter, backspace, escape, back, home
{"t":"bye"}
```

Les coordonnées sont normalisées `[0,1]` par rapport à la zone vidéo affichée. Le téléphone inverse son cadrage fit/fill pour obtenir les pixels de l'écran.

### Téléphone → navigateur

```json
{"t":"pong","ts":123.45}
{"t":"control-result","ok":true}
{"t":"control-result","ok":false,"reason":"Activez le service d'accessibilité…"}
{"t":"keyboard-result","ok":true}
{"t":"keyboard-result","ok":false,"reason":"Cette application refuse la saisie distante."}
```

Le téléphone ne renvoie pas de contenu de champ ni de capture d'écran par le canal de contrôle.

## Séquence nominale

```text
Android                         PHP                         Navigateur
  │ create/poll ───────────────► │                              │
  │                              │ ◄──────────────── auth ────── │
  │                              │ ── settings/presence ──────► │
  │ ◄──── offer + viewport ───── │ ◄──────────── offer ───────── │
  │ setRemote/addVideo/answer    │                              │
  │ ───── answer + ICE ────────► │ ── answer + ICE ───────────► │
  │ ◄════════════ WebRTC vidéo + DataChannel ═══════════════════►│
  │ ◄──────────── touch / text (DataChannel) ────────────────── │
  │ ───────────── frames / pong / result ─────────────────────► │
  │ heartbeat/status ──────────► │ ◄──────── heartbeat ───────── │
```

## Codes d'erreur utiles

| Code | HTTP | Sens |
|---|---|---|
| `invalid_request` | 400 | paramètres manquants/invalides |
| `invalid_json` | 400 | corps JSON illisible |
| `https_required` | 400 | HTTP interdit en production |
| `unauthorized` / `missing_token` | 401 | jeton absent |
| `auth_failed` | 401 | nom/mot de passe incorrect |
| `invalid_token` | 401 | jeton inconnu ou révoqué |
| `forbidden` / `wrong_role` | 403 | rôle ou action interdite |
| `kicked` | 403 | spectateur retiré par l'hôte |
| `join_closed` | 403 | nouveaux spectateurs refusés |
| `session_full` | 403 | quota atteint |
| `name_taken` | 409 | nom déjà actif |
| `not_found` / `viewer_not_found` | 404 | session/spectateur inconnu |
| `session_ended` | 410 | session supprimée/expirée |
| `rate_limited` | 429 | anti-bruteforce/quota (header `Retry-After`) |
| `payload_too_large` | 413 | corps > 64 Ko ou signal > 32 Ko |

## Notes de compatibilité

- Les SDP sont relayés sans modification (hors validation de taille) ; les codecs sont négociés par WebRTC. Le navigateur préfère H.264 puis VP8/VP9 ; Android active l'encodeur matériel H.264 quand disponible.
- ICE : STUN/TURN sont transmis par `config`/`session.*`. Les candidats sont validés et relayés.
- `signal.poll` ne doit pas être mis en cache (`Cache-Control: no-store`).
- Les jetons sont révocables : un `phone.stop` ou un `kick` prend effet immédiatement, même si un poll est en cours.

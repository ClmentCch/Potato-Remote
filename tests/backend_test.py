#!/usr/bin/env python3
"""Tests d'intégration HTTP de l'API Potato Remote (serveur PHP local).

Prérequis :
    php -S 127.0.0.1:8088 -t web   (avec allow_http=true pour le test local)
    pottery_test.py s'exécute avec POTATO_TEST_URL (défaut http://127.0.0.1:8088).

Ce script ne teste pas WebRTC (impossible sans navigateur/appareil) : il teste
tout ce que le serveur PHP doit garantir (sessions, jetons, relais, quotas).
"""
from __future__ import annotations

import json
import os
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("POTATO_TEST_URL", "http://127.0.0.1:8088").rstrip("/")
DATA = Path(__file__).resolve().parent.parent / "web" / "data"
SDP = (
    "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\n"
    "t=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
)
RESULTS: list[tuple[bool, str, str]] = []


def call(action: str, body: dict | None = None, token: str | None = None,
         method: str | None = None, timeout: float = 30.0,
         raw: str | None = None) -> tuple[int, dict]:
    url = f"{BASE}/api/index.php?action={action}"
    data = None
    if raw is not None:
        data = raw.encode()
    elif body is not None:
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method or ("POST" if data is not None else "GET"))
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            payload = json.loads(response.read().decode() or "{}")
            return response.status, payload
    except urllib.error.HTTPError as error:
        raw_body = error.read().decode()
        try:
            payload = json.loads(raw_body)
        except json.JSONDecodeError:
            payload = {"raw": raw_body}
        return error.code, payload


def check(name: str, condition: bool, detail: str = "") -> None:
    RESULTS.append((bool(condition), name, detail))
    print(("PASS " if condition else "FAIL ") + name + (f"  [{detail}]" if detail and not condition else ""))


def error_code(payload: dict) -> str:
    return str((payload.get("error") or {}).get("code", ""))


def data(payload: dict) -> dict:
    return payload.get("data") or {}


def create(name: str, password: str = "patate-123", phone_token: str = "",
           settings: dict | None = None) -> tuple[int, dict]:
    return call("session.create", {
        "session": name,
        "password": password,
        "device": "Pixel 8 de test",
        "phone_token": phone_token,
        "screen": {"width": 1080, "height": 2400, "rotation": 0, "density": 2.75},
        "settings": settings or {"control": True, "keyboard": True, "max_viewers": 4},
    })


def auth(name: str, password: str = "patate-123", device: str = "iPad Air de test",
         viewport: dict | None = None) -> tuple[int, dict]:
    return call("session.auth", {
        "session": name,
        "password": password,
        "device": device,
        "viewport": viewport or {"w": 1194, "h": 834, "dpr": 2},
    })


def main() -> int:
    # Compteurs de débit laissés par une exécution précédente : ce script est
    # un test local, il remet donc uniquement ces compteurs à zéro. Les
    # sessions sont supprimées proprement par phone.stop en fin de test.
    rate_dir = DATA / "ratelimit"
    if rate_dir.is_dir():
        for path in rate_dir.glob("*.json"):
            path.unlink(missing_ok=True)

    # --- Diagnostic et configuration -------------------------------------
    status, payload = call("config")
    cfg = data(payload)
    check("GET config -> 200", status == 200, str(status))
    check("config.apiVersion == 1", cfg.get("apiVersion") == 1, str(cfg))
    check("config.iceServers contient STUN", any("stun:" in u for s in cfg.get("iceServers", []) for u in s.get("urls", [])), str(cfg.get("iceServers")))
    check("TURN non configuré signalé", cfg.get("turnConfigured") is False, str(cfg.get("turnConfigured")))

    status, payload = call("health")
    health = data(payload)
    check("GET health -> 200", status == 200, str(status))
    check("health.dataWritable", health.get("dataWritable") is True, str(health))
    check("health avertit HTTPS (HTTP local)", any("HTTPS" in w for w in health.get("warnings", [])), str(health.get("warnings")))
    check("health avertit TURN", any("TURN" in w for w in health.get("warnings", [])), str(health.get("warnings")))

    status, payload = call("session.create", {}, method="GET")
    check("GET session.create -> 405", status == 405 and error_code(payload) == "method_not_allowed", f"{status} {payload}")

    status, payload = call("session.auth", raw="{not json")
    check("JSON invalide -> 400", status == 400 and error_code(payload) == "invalid_json", f"{status} {payload}")

    # --- Création, validation, reprise ------------------------------------
    status, payload = create("ClementPhone")
    created = data(payload)
    phone_token = created.get("phoneToken", "")
    session_id = created.get("sessionId", "")
    check("session.create -> 200", status == 200, f"{status} {payload}")
    check("session.create renvoie un jeton téléphone", len(phone_token) == 64, str(len(phone_token)))
    check("session.create renvoie l'URL spectateur", created.get("viewerUrl", "").endswith("/?s=ClementPhone"), str(created.get("viewerUrl")))
    check("session.create renvoie cursor=0", created.get("cursor") == 0, str(created.get("cursor")))
    check("session.create renvoie les réglages", created.get("settings", {}).get("control") is True, str(created.get("settings")))

    status, payload = create("ClementPhone")
    check("Nom déjà pris -> 409 name_taken", status == 409 and error_code(payload) == "name_taken", f"{status} {payload}")

    status, payload = create("ClementPhone", phone_token=phone_token)
    resumed = data(payload)
    check("Reprise par phone_token -> 200", status == 200, f"{status} {payload}")
    check("Reprise conserve le sessionId", resumed.get("sessionId") == session_id, f"{resumed.get('sessionId')} != {session_id}")
    check("La reprise ne divulgue pas le jeton stocké", resumed.get("phoneToken") == phone_token, "jeton différent")

    status, payload = create("x")
    check("Nom trop court -> 400", status == 400 and error_code(payload) == "invalid_session_name", f"{status} {payload}")

    status, payload = create("MotDePasseCourt", password="abc")
    check("Mot de passe trop court -> 400", status == 400 and error_code(payload) == "invalid_password", f"{status} {payload}")

    # --- Authentification spectateur -------------------------------------
    status, payload = auth("ClementPhone", password="mauvais-mot-de-passe")
    check("Mauvais mot de passe -> 401", status == 401 and error_code(payload) == "auth_failed", f"{status} {payload}")

    status, payload = auth("InconnueSession", password="peu-importe")
    check("Session inconnue -> 401 (pas d'énumération)", status == 401 and error_code(payload) == "auth_failed", f"{status} {payload}")

    status, payload = auth("ClementPhone")
    viewer = data(payload)
    viewer_token = viewer.get("viewerToken", "")
    viewer_id = viewer.get("viewerId", "")
    check("session.auth -> 200", status == 200, f"{status} {payload}")
    check("auth renvoie un jeton spectateur", len(viewer_token) == 64, str(len(viewer_token)))
    check("auth renvoie viewerId", len(viewer_id) == 10, str(len(viewer_id)))
    check("auth considère le téléphone en ligne", viewer.get("phoneOnline") is True, str(viewer.get("phoneOnline")))
    check("auth renvoie le libellé du téléphone", viewer.get("phoneLabel") == "Pixel 8 de test", str(viewer.get("phoneLabel")))

    status, payload = call("viewer.heartbeat", {
        "viewport": {"w": 1194, "h": 834, "dpr": 2},
        "stats": {"rtt": 42, "fps": 30, "kbps": 2400, "width": 1280, "height": 720, "jitter": 3, "lost": 1},
    }, token=viewer_token)
    hb = data(payload)
    check("viewer.heartbeat -> 200", status == 200, f"{status} {payload}")
    check("heartbeat compte 1 spectateur", hb.get("viewerCount") == 1, str(hb.get("viewerCount")))

    status, payload = call("phone.status", {
        "screen": {"width": 1080, "height": 2400, "rotation": 0, "density": 2.75},
        "stats": {"rtt": 30, "fps": 30, "kbps": 2500, "width": 1280, "height": 720, "jitter": 2, "lost": 0},
    }, token=phone_token)
    phone_status = data(payload)
    viewer_list = phone_status.get("viewers") or []
    check("phone.status -> 200", status == 200, f"{status} {payload}")
    check("phone.status liste le spectateur", len(viewer_list) == 1 and viewer_list[0].get("label") == "iPad Air de test", str(viewer_list))
    if viewer_list:
        check("phone.status expose le viewport", abs(float((viewer_list[0].get("viewport") or {}).get("aspect", 0)) - 1.4317) < 0.001, str(viewer_list[:1]))
        check("phone.status expose les stats", (viewer_list[0].get("stats") or {}).get("rtt") == 42, str(viewer_list[:1]))
    else:
        check("phone.status expose le viewport", False, "liste vide")
        check("phone.status expose les stats", False, "liste vide")

    # --- Relais de signalisation -----------------------------------------
    status, payload = call("signal.send", {
        "type": "offer",
        "payload": {"sdp": SDP, "viewport": {"w": 1194, "h": 834, "dpr": 2}, "mode": "fill"},
    }, token=viewer_token)
    offer_seq = data(payload).get("seq")
    check("viewer envoie offer -> 200", status == 200 and isinstance(offer_seq, int), f"{status} {payload}")

    status, payload = call("signal.poll", {"cursor": 0, "wait": 0}, token=phone_token)
    poll = data(payload)
    messages = poll.get("messages") or []
    offers = [m for m in messages if m.get("type") == "offer"]
    check("phone reçoit l'offre", status == 200 and len(offers) == 1, f"{status} {messages}")
    if offers:
        offer = offers[0]
        check("offre associée au bon spectateur", offer.get("viewerId") == viewer_id, str(offer))
        check("offre conserve le viewport 1194x834", (offer.get("payload", {}).get("viewport") or {}).get("w") == 1194, str(offer.get("payload")))
        check("offre conserve le mode fill", offer.get("payload", {}).get("mode") == "fill", str(offer.get("payload")))
        check("phone reçoit le lecteur après relais", len(poll.get("viewers") or []) == 1, str(poll.get("viewers")))

    status, payload = call("signal.send", {
        "type": "answer", "to": viewer_id, "payload": {"sdp": SDP},
    }, token=phone_token)
    answer_seq = data(payload).get("seq")
    check("phone envoie answer -> 200", status == 200 and isinstance(answer_seq, int), f"{status} {payload}")

    status, payload = call("signal.poll", {"cursor": offer_seq, "wait": 0}, token=viewer_token)
    answers = [m for m in (data(payload).get("messages") or []) if m.get("type") == "answer"]
    check("viewer reçoit la réponse", status == 200 and len(answers) == 1, f"{status} {payload}")
    check("réponse adressée au bon spectateur", bool(answers) and answers[0].get("viewerId") == viewer_id, str(answers))

    status, payload = call("signal.send", {"type": "answer", "payload": {"sdp": SDP}}, token=viewer_token)
    check("viewer ne peut pas envoyer answer -> 403", status == 403 and error_code(payload) == "signal_type", f"{status} {payload}")

    status, payload = call("signal.send", {"type": "ice", "payload": {"candidate": 123}}, token=viewer_token)
    check("ICE invalide -> 400", status == 400 and error_code(payload) == "invalid_ice", f"{status} {payload}")

    status, payload = call("signal.send", {
        "type": "ice",
        "payload": {"candidate": "candidate:1 1 UDP 2122260223 192.0.2.1 50000 typ host", "sdpMid": "0", "sdpMLineIndex": 0},
    }, token=viewer_token)
    ice_seq = data(payload).get("seq")
    check("viewer envoie ICE -> 200", status == 200, f"{status} {payload}")

    status, payload = call("signal.poll", {"cursor": answer_seq, "wait": 0}, token=phone_token)
    ice_messages = [m for m in (data(payload).get("messages") or []) if m.get("type") == "ice"]
    check("phone reçoit le candidat ICE", status == 200 and len(ice_messages) == 1, f"{status} {payload}")

    status, payload = call("signal.send", {"type": "answer", "payload": {"sdp": "court"}}, token=phone_token)
    check("SDP trop court -> 400", status == 400 and error_code(payload) == "invalid_sdp", f"{status} {payload}")

    # --- Réglages, présence, expulsion ------------------------------------
    status, payload = call("phone.update", {
        "control": False, "keyboard": False, "max_viewers": 2,
    }, token=phone_token)
    updated = data(payload)
    check("phone.update -> 200", status == 200, f"{status} {payload}")
    check("phone.update désactive le contrôle", updated.get("settings", {}).get("control") is False, str(updated.get("settings")))
    check("phone.update désactive le clavier", updated.get("settings", {}).get("keyboard") is False, str(updated.get("settings")))

    status, payload = call("signal.poll", {"cursor": offer_seq, "wait": 0}, token=viewer_token)
    states = [m for m in (data(payload).get("messages") or []) if m.get("type") == "state"]
    check("viewer est notifié du changement d'état", status == 200 and bool(states), f"{status} {payload}")
    check("l'état reçu reflète control=false", bool(states) and states[-1].get("payload", {}).get("control") is False, str(states))

    status, payload = call("viewer.heartbeat", {
        "viewport": {"w": 834, "h": 1194, "dpr": 2},
        "using_control": True, "using_keyboard": True,
    }, token=viewer_token)
    check("heartbeat signale contrôle/clavier", status == 200, f"{status} {payload}")

    status, payload = call("phone.status", {"screen": {"width": 1080, "height": 2400, "rotation": 0, "density": 2.75}}, token=phone_token)
    flags = (data(payload).get("viewers") or [{}])[0]
    check("contrôle utilisé visible sur le téléphone", flags.get("controlUsedAt") is not None, str(flags))
    check("clavier utilisé visible sur le téléphone", flags.get("keyboardUsedAt") is not None, str(flags))

    status, payload = call("signal.poll", {"cursor": ice_seq, "wait": 2}, token=phone_token)
    check("long-poll vide revient correctement", status == 200 and data(payload).get("messages") == [], f"{status} {payload}")

    status, payload = call("phone.update", {"kick": viewer_id}, token=phone_token)
    check("phone.update kick -> 200", status == 200, f"{status} {payload}")

    status, payload = call("viewer.heartbeat", {"viewport": {"w": 1194, "h": 834}}, token=viewer_token)
    check("spectateur expulsé -> 403 kicked", status == 403 and error_code(payload) == "kicked", f"{status} {payload}")

    status, payload = call("signal.poll", {"cursor": 0, "wait": 0}, token=viewer_token)
    check("poll spectateur expulsé -> 403 kicked", status == 403 and error_code(payload) == "kicked", f"{status} {payload}")

    status, payload = call("session.info", {}, token=viewer_token)
    check("session.info après expulsion -> 403 kicked", status == 403 and error_code(payload) == "kicked", f"{status} {payload}")

    status, payload = call("phone.status", {"screen": {"width": 1080, "height": 2400}}, token=phone_token)
    check("spectateur expulsé absent de la liste", all(v.get("id") != viewer_id for v in (data(payload).get("viewers") or [])), str(data(payload).get("viewers")))

    # --- Quota de spectateurs ---------------------------------------------
    status, payload = create("LimiteTest", settings={"control": True, "keyboard": True, "max_viewers": 1})
    limit = data(payload)
    limit_token = limit.get("phoneToken", "")
    check("session limite créée", status == 200, f"{status} {payload}")
    status, first = auth("LimiteTest", device="Premier iPad")
    first_token = data(first).get("viewerToken", "")
    check("premier spectateur accepté", status == 200, f"{status} {first}")
    status, second = auth("LimiteTest", device="Second iPad")
    check("deuxième spectateur refusé -> 403 session_full", status == 403 and error_code(second) == "session_full", f"{status} {second}")
    call("signal.send", {"type": "bye", "payload": {"reason": "test"}}, token=first_token)
    call("phone.stop", {}, token=limit_token)

    # --- Multi-spectateurs et viewports indépendants ----------------------
    status, payload = create("AdaptTest", settings={"control": True, "keyboard": True, "max_viewers": 3})
    adapt = data(payload)
    adapt_token = adapt.get("phoneToken", "")
    check("session multi-spectateurs créée", status == 200, f"{status} {payload}")
    status, a = auth("AdaptTest", device="iPad paysage", viewport={"w": 1194, "h": 834, "dpr": 2})
    status2, b = auth("AdaptTest", device="iPad portrait", viewport={"w": 834, "h": 1194, "dpr": 2})
    a_token, b_token = data(a).get("viewerToken", ""), data(b).get("viewerToken", "")
    a_id, b_id = data(a).get("viewerId", ""), data(b).get("viewerId", "")
    check("deux spectateurs acceptés", status == 200 and status2 == 200, f"{status}/{status2}")
    call("signal.send", {"type": "offer", "payload": {"sdp": SDP, "viewport": {"w": 1194, "h": 834}, "mode": "fill"}}, token=a_token)
    call("signal.send", {"type": "offer", "payload": {"sdp": SDP, "viewport": {"w": 834, "h": 1194}, "mode": "fit"}}, token=b_token)
    status, payload = call("signal.poll", {"cursor": 0, "wait": 0}, token=adapt_token)
    adapt_offers = {m.get("viewerId"): m for m in (data(payload).get("messages") or []) if m.get("type") == "offer"}
    check("le téléphone reçoit une offre par spectateur", len(adapt_offers) == 2, str(list(adapt_offers)))
    check("viewports distincts relayés", (adapt_offers.get(a_id, {}).get("payload", {}).get("viewport", {}).get("w") == 1194 and
                                          adapt_offers.get(b_id, {}).get("payload", {}).get("viewport", {}).get("w") == 834), str(adapt_offers))
    check("modes distincts relayés", (adapt_offers.get(a_id, {}).get("payload", {}).get("mode") == "fill" and
                                      adapt_offers.get(b_id, {}).get("payload", {}).get("mode") == "fit"), str(adapt_offers))
    call("phone.stop", {}, token=adapt_token)

    # --- Long-polling réveillé par un message -----------------------------
    status, payload = create("PollTest", settings={"max_viewers": 2})
    poll_session = data(payload)
    poll_token = poll_session.get("phoneToken", "")
    status, viewer_payload = auth("PollTest", device="iPad long-poll")
    poll_viewer = data(viewer_payload).get("viewerToken", "")
    result: dict = {}

    def polling() -> None:
        started = time.monotonic()
        code, body = call("signal.poll", {"cursor": 0, "wait": 6}, token=poll_token)
        result["elapsed"] = time.monotonic() - started
        result["status"] = code
        result["body"] = body

    thread = threading.Thread(target=polling, daemon=True)
    thread.start()
    time.sleep(1.0)
    call("signal.send", {"type": "offer", "payload": {"sdp": SDP, "viewport": {"w": 1024, "h": 768}}}, token=poll_viewer)
    thread.join(timeout=10)
    check("long-poll réveillé par un message", result.get("status") == 200 and any(m.get("type") == "offer" for m in (data(result.get("body", {})).get("messages") or [])), str(result))
    check("long-poll répond en moins de 5 s", result.get("elapsed", 99) < 5, str(result.get("elapsed")))
    call("phone.stop", {}, token=poll_token)

    # --- Anti-bruteforce --------------------------------------------------
    # Nom unique : le compteur est volontairement persistant entre les tests
    # pour reproduire un vrai verrouillage, il ne doit pas polluer une autre
    # exécution.
    rate_name = f"RateLimit{int(time.time() * 1000) % 10000000}"
    status, payload = create(rate_name)
    rate = data(payload)
    rate_token = rate.get("phoneToken", "")
    check("session anti-bruteforce créée", status == 200, f"{status} {payload}")
    codes = []
    for _ in range(9):
        code, _ = auth(rate_name, password="mauvais")
        codes.append(code)
    check("8 mauvais essais -> 401", codes[:8] == [401] * 8, str(codes))
    check("9e essai verrouillé -> 429", codes[8] == 429, str(codes))
    status, payload = auth(rate_name)
    check("mot de passe correct bloqué pendant le verrouillage", status == 429, f"{status} {payload}")
    call("phone.stop", {}, token=rate_token)

    # --- Arrêt et jetons ---------------------------------------------------
    status, payload = call("phone.stop", {}, token=phone_token)
    check("phone.stop -> 200", status == 200 and data(payload).get("stopped") is True, f"{status} {payload}")

    status, payload = call("session.info", {}, token=viewer_token)
    # phone.stop supprime la session ET tous ses jetons : le spectateur reçoit
    # 401 invalid_token (révocation immédiate) plutôt qu'un 410 avec un jeton
    # encore valide. Les deux réponses signifient « la session n'existe plus ».
    check("session arrêtée -> jeton révoqué (401 ou 410)", status in (401, 410), f"{status} {payload}")

    status, payload = call("phone.status", {}, token=phone_token)
    check("jeton téléphone invalide après arrêt -> 401", status == 401 and error_code(payload) == "invalid_token", f"{status} {payload}")

    status, payload = call("signal.poll", {"cursor": 0, "wait": 0}, token="a" * 64)
    check("jeton inconnu -> 401 invalid_token", status == 401 and error_code(payload) == "invalid_token", f"{status} {payload}")

    status, payload = call("signal.send", {"type": "ice", "payload": {}}, token=phone_token)
    check("jeton téléphone révoqué -> 401", status == 401, f"{status} {payload}")

    # --- Stockage ---------------------------------------------------------
    files = list(DATA.glob("sessions/*.json"))
    for path in files:
        raw = path.read_text()
        session = json.loads(raw)
        check(f"{path.name} : mot de passe hashé", session.get("password_hash", "").startswith(("$2y$", "$argon2")), session.get("password_hash", "")[:12])
        check(f"{path.name} : aucun mot de passe en clair", "patate-123" not in raw, "mot de passe trouvé !")
        check(f"{path.name} : aucun jeton téléphone en clair", phone_token not in raw if phone_token else True, "jeton trouvé !")

    token_files = list(DATA.glob("tokens/*.json"))
    for path in token_files:
        meta = json.loads(path.read_text())
        check(f"{path.name} : index de jeton sans jeton", "token" not in meta and len(path.stem) == 64, str(meta))

    # --- Nettoyage des sessions de test restantes -------------------------
    for path in DATA.glob("sessions/*.json"):
        session = json.loads(path.read_text())
        if session.get("name", "").startswith(("RateLimit", "LimiteTest", "AdaptTest", "PollTest")):
            print(f"NOTE session de test restante : {session.get('name')} ({path.name})")

    failed = [r for r in RESULTS if not r[0]]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} tests réussis")
    if failed:
        print("Échecs :")
        for _, name, detail in failed:
            print(f"  - {name} [{detail}]")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

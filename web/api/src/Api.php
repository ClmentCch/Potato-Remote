<?php
declare(strict_types=1);

/**
 * Logique métier Potato Remote.
 *
 * Le serveur assure : création de session, authentification des spectateurs,
 * index des spectateurs, relais de signalisation WebRTC (offre/réponse/ICE),
 * présence et quotas. La vidéo et les commandes de contrôle ne transitent
 * jamais par PHP : elles passent par WebRTC (DTLS-SRTP + DataChannel).
 */
final class Api
{
    public function __construct(private array $config, private Store $store)
    {
    }

    private function now(): int
    {
        return potato_now();
    }

    private function ttlMs(): int
    {
        return max(600, (int) $this->config['session_ttl']) * 1000;
    }

    public function viewerUrl(string $name): string
    {
        return potato_base_url($this->config) . '/?s=' . rawurlencode($name);
    }

    // ------------------------------------------------------------------
    //  Configuration publique et diagnostic
    // ------------------------------------------------------------------

    public function publicConfig(): array
    {
        return [
            'siteName' => (string) $this->config['site_name'],
            'apiVersion' => (int) $this->config['api_version'],
            'iceServers' => Ice::servers($this->config),
            'turnConfigured' => Ice::configured($this->config),
            'limits' => [
                'maxViewers' => (int) $this->config['max_viewers_limit'],
                'sessionTtl' => (int) $this->config['session_ttl'],
            ],
            'serverTime' => $this->now(),
        ];
    }

    public function health(): array
    {
        $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
        if (!empty($this->config['trusted_proxy']) && !empty($_SERVER['HTTP_X_FORWARDED_PROTO'])) {
            $https = strtolower(trim(explode(',', (string) $_SERVER['HTTP_X_FORWARDED_PROTO'])[0])) === 'https';
        }

        $warnings = [];
        if (!Ice::configured($this->config)) {
            $warnings[] = 'TURN n\'est pas configuré : les connexions entre deux réseaux différents peuvent échouer (voir docs/TURN.md).';
        }
        if (!$https) {
            $warnings[] = 'HTTPS est inactif : les jetons et les flux ne doivent pas circuler en clair.';
        }
        foreach (['openssl', 'mbstring'] as $extension) {
            if (!extension_loaded($extension)) {
                $warnings[] = 'Extension PHP manquante : ' . $extension . '.';
            }
        }

        $probe = $this->store->dataDir() . '/.health';
        $writable = @file_put_contents($probe, 'ok') !== false;
        if ($writable) {
            @unlink($probe);
        }
        if (!$writable) {
            $warnings[] = 'Le dossier data/ n\'est pas inscriptible par PHP.';
        }

        return [
            'ok' => $writable,
            'php' => PHP_VERSION,
            'https' => $https,
            'dataWritable' => $writable,
            'turnConfigured' => Ice::configured($this->config),
            'sessions' => count($this->store->sessionIds()),
            'warnings' => $warnings,
        ];
    }

    // ------------------------------------------------------------------
    //  Création / reprise de session (téléphone)
    // ------------------------------------------------------------------

    public function createSession(array $body): array
    {
        potato_require_https($this->config);

        $ip = Http::clientIp($this->config);
        $rate = $this->store->rateHit(
            'create:' . $ip,
            (int) $this->config['create_max'],
            (int) $this->config['create_window'],
            0
        );
        if (!$rate['allowed']) {
            throw ApiException::tooMany('Trop de sessions créées récemment. Réessayez plus tard.', $rate['retryAfter']);
        }

        $secret = trim((string) $this->config['create_secret']);
        if ($secret !== '') {
            if (!hash_equals($secret, Http::string($body, 'server_key'))) {
                throw ApiException::forbidden('Clé serveur invalide.', 'invalid_server_key');
            }
        } elseif (empty($this->config['allow_public_create'])) {
            throw ApiException::forbidden('La création de session est désactivée sur ce serveur.', 'create_disabled');
        }

        $name = potato_text(Http::string($body, 'session'), 48);
        $password = Http::string($body, 'password');
        $device = potato_text(Http::string($body, 'device', 'Téléphone Android'), 48);
        if ($device === '') {
            $device = 'Téléphone Android';
        }
        $phoneToken = trim(Http::string($body, 'phone_token'));

        if (!potato_valid_session_name($name)) {
            throw ApiException::badRequest(
                'Le nom de session doit contenir 3 à 32 caractères (lettres, chiffres, espaces, - _ .).',
                'invalid_session_name'
            );
        }
        $passwordLength = mb_strlen($password);
        if ($passwordLength < 4 || $passwordLength > 128) {
            throw ApiException::badRequest('Le mot de passe doit contenir entre 4 et 128 caractères.', 'invalid_password');
        }

        $nameKey = potato_name_key($name);
        $settings = $this->sanitizeSettings($body['settings'] ?? []);
        $screen = $this->sanitizeScreen($body['screen'] ?? []);

        $existing = $this->store->findSessionByName($nameKey);
        if ($existing !== null) {
            $existingId = (string) $existing['id'];
            $storedHash = (string) ($existing['phone']['token_hash'] ?? '');
            $resume = $phoneToken !== '' && $storedHash !== '' && hash_equals($storedHash, hash('sha256', $phoneToken));

            if (!$resume) {
                $phoneSeen = (int) ($existing['phone']['last_seen'] ?? 0);
                if ($this->now() - $phoneSeen < (int) $this->config['phone_grace'] * 1000) {
                    throw new ApiException(409, 'name_taken', 'Ce nom de session est déjà utilisé. Choisissez-en un autre ou attendez son expiration.');
                }
                $this->store->deleteSession($existingId);
            } else {
                $updated = $this->store->mutateSession($existingId, function (array &$s) use ($settings, $screen, $device): void {
                    $s['phone']['online'] = true;
                    $s['phone']['last_seen'] = potato_now();
                    $s['phone']['label'] = $device;
                    if ($screen !== []) {
                        $s['phone']['screen'] = $screen;
                    }
                    $s['phone']['settings'] = array_merge((array) $s['phone']['settings'], $settings);
                });
                if ($updated === null) {
                    throw ApiException::gone();
                }
                return $this->createPayload($updated['session'], $phoneToken);
            }
        }

        $id = bin2hex(random_bytes(8));
        $token = bin2hex(random_bytes(32));
        $now = $this->now();

        $session = [
            'id' => $id,
            'name' => $name,
            'name_key' => $nameKey,
            'password_hash' => password_hash($password, PASSWORD_DEFAULT),
            'created_at' => $now,
            'updated_at' => $now,
            'expires_at' => $now + $this->ttlMs(),
            'phone' => [
                'token_hash' => hash('sha256', $token),
                'label' => $device,
                'online' => true,
                'last_seen' => $now,
                'screen' => $screen,
                'settings' => $settings,
            ],
            'viewers' => [],
            'messages' => [],
            'next_seq' => 1,
        ];

        $this->store->createSession($session);
        $this->store->putToken($token, $id, 'phone');
        $this->store->writeNameIndex($nameKey, $id, (int) $session['expires_at']);

        return $this->createPayload($session, $token);
    }

    private function createPayload(array $session, string $phoneToken): array
    {
        return [
            'sessionId' => (string) $session['id'],
            'name' => (string) $session['name'],
            'phoneToken' => $phoneToken,
            'viewerUrl' => $this->viewerUrl((string) $session['name']),
            'iceServers' => Ice::servers($this->config),
            'turnConfigured' => Ice::configured($this->config),
            'expiresAt' => (int) $session['expires_at'],
            'cursor' => max(0, (int) ($session['next_seq'] ?? 1) - 1),
            'settings' => $this->settingsOf($session),
            'viewerCount' => $this->onlineViewerCount($session),
            'serverTime' => $this->now(),
        ];
    }

    // ------------------------------------------------------------------
    //  Authentification d'un spectateur
    // ------------------------------------------------------------------

    public function authViewer(array $body): array
    {
        potato_require_https($this->config);

        $name = potato_text(Http::string($body, 'session'), 48);
        $password = Http::string($body, 'password');
        $label = $this->viewerLabel($body);

        if ($name === '' || $password === '') {
            throw ApiException::badRequest('Renseignez le nom de session et le mot de passe.', 'missing_credentials');
        }
        if (!potato_valid_session_name($name)) {
            // Même délai qu'une vérification réelle pour limiter l'énumération.
            password_verify($password, '$2y$10$usesomesillystringfore7hnbRJHxXVLeakoG8K30oukPsA.ztMG');
            throw ApiException::unauthorized('Nom de session ou mot de passe incorrect.', 'auth_failed');
        }

        $nameKey = potato_name_key($name);
        $ip = Http::clientIp($this->config);
        $limit = $this->store->rateHit(
            'login:' . $ip . ':' . $nameKey,
            (int) $this->config['login_attempts_max'],
            (int) $this->config['login_attempts_window'],
            (int) $this->config['login_lock_seconds']
        );
        if (!$limit['allowed']) {
            $minutes = max(1, (int) ceil($limit['retryAfter'] / 60));
            throw ApiException::tooMany('Trop de tentatives. Réessayez dans ' . $minutes . ' min.', $limit['retryAfter']);
        }

        $session = $this->store->findSessionByName($nameKey);
        $hash = is_array($session) ? (string) ($session['password_hash'] ?? '') : '$2y$10$usesomesillystringfore7hnbRJHxXVLeakoG8K30oukPsA.ztMG';
        if (!is_array($session) || !password_verify($password, $hash)) {
            throw ApiException::unauthorized('Nom de session ou mot de passe incorrect.', 'auth_failed');
        }

        $phoneSeen = (int) ($session['phone']['last_seen'] ?? 0);
        if ($this->now() - $phoneSeen > (int) $this->config['phone_grace'] * 1000) {
            throw ApiException::gone('Cette session a été abandonnée par le téléphone. Demandez un nouveau partage.');
        }

        $viewport = $this->sanitizeViewport($body['viewport'] ?? []);
        $viewerId = bin2hex(random_bytes(5));
        $viewerToken = bin2hex(random_bytes(32));

        $updated = $this->store->mutateSession((string) $session['id'], function (array &$s) use ($viewport, $label, $viewerId, $viewerToken): int {
            $now = potato_now();
            $this->pruneViewers($s);
            $settings = $this->settingsOf($s);
            if (empty($settings['allow_new_viewers'])) {
                throw ApiException::forbidden('L\'hôte n\'accepte plus de nouveaux spectateurs.', 'join_closed');
            }
            if ($this->onlineViewerCount($s) >= (int) $settings['max_viewers']) {
                throw ApiException::forbidden('Cette session est complète.', 'session_full');
            }

            $s['viewers'][$viewerId] = [
                'token_hash' => hash('sha256', $viewerToken),
                'label' => $label,
                'joined_at' => $now,
                'last_seen' => $now,
                'viewport' => $viewport,
                'stats' => null,
                'kicked' => false,
            ];
            return max(0, (int) ($s['next_seq'] ?? 1) - 1);
        });

        if ($updated === null) {
            throw ApiException::gone();
        }

        $this->store->putToken($viewerToken, (string) $updated['session']['id'], 'viewer', $viewerId);
        $this->store->rateReset('login:' . $ip . ':' . $nameKey);

        $s = $updated['session'];
        return [
            'viewerToken' => $viewerToken,
            'viewerId' => $viewerId,
            'cursor' => (int) $updated['result'],
            'sessionId' => (string) $s['id'],
            'name' => (string) $s['name'],
            'phoneOnline' => $this->isPhoneOnline($s),
            'phoneLabel' => (string) ($s['phone']['label'] ?? 'Téléphone Android'),
            'viewerCount' => $this->onlineViewerCount($s),
            'settings' => $this->settingsOf($s),
            'expiresAt' => (int) $s['expires_at'],
            'iceServers' => Ice::servers($this->config),
            'turnConfigured' => Ice::configured($this->config),
            'serverTime' => $this->now(),
        ];
    }

    /** Reconnexion d'un spectateur déjà authentifié (rechargement de page). */
    public function viewerInfo(array $body): array
    {
        $auth = $this->requireAuth($body, 'viewer');
        $s = $auth['session'];
        $viewer = $s['viewers'][$auth['viewer_id']] ?? null;

        return [
            'sessionId' => (string) $s['id'],
            'name' => (string) $s['name'],
            'viewerId' => $auth['viewer_id'],
            'cursor' => max(0, (int) ($s['next_seq'] ?? 1) - 1),
            'phoneOnline' => $this->isPhoneOnline($s),
            'phoneLabel' => (string) ($s['phone']['label'] ?? 'Téléphone Android'),
            'viewerCount' => $this->onlineViewerCount($s),
            'settings' => $this->settingsOf($s),
            'viewer' => $viewer === null ? null : $this->publicViewer($viewer, $this->now()),
            'expiresAt' => (int) $s['expires_at'],
            'iceServers' => Ice::servers($this->config),
            'turnConfigured' => Ice::configured($this->config),
            'serverTime' => $this->now(),
        ];
    }

    // ------------------------------------------------------------------
    //  Téléphone : présence, réglages, arrêt
    // ------------------------------------------------------------------

    public function phoneStatus(array $body): array
    {
        $auth = $this->requireAuth($body, 'phone');
        $screen = $this->sanitizeScreen($body['screen'] ?? []);
        $stats = $this->sanitizeStats($body['stats'] ?? null);

        $updated = $this->store->mutateSession((string) $auth['session']['id'], function (array &$s) use ($screen, $stats): void {
            $s['phone']['online'] = true;
            $s['phone']['last_seen'] = potato_now();
            if ($screen !== []) {
                $s['phone']['screen'] = $screen;
            }
            if ($stats !== null) {
                $s['phone']['stats'] = $stats;
            }
            $this->pruneViewers($s);
        });

        if ($updated === null) {
            throw ApiException::gone();
        }
        return $this->phonePayload($updated['session']);
    }

    public function phoneUpdate(array $body): array
    {
        $auth = $this->requireAuth($body, 'phone');
        $limit = (int) $this->config['max_viewers_limit'];

        $updated = $this->store->mutateSession((string) $auth['session']['id'], function (array &$s) use ($body, $limit): void {
            $now = potato_now();
            $s['phone']['online'] = true;
            $s['phone']['last_seen'] = $now;
            $before = $this->settingsOf($s);
            $patch = [];

            foreach (['control', 'keyboard', 'allow_new_viewers'] as $key) {
                if (array_key_exists($key, $body)) {
                    $patch[$key] = (bool) $body[$key];
                }
            }
            if (array_key_exists('max_viewers', $body)) {
                $patch['max_viewers'] = max(1, min($limit, (int) $body['max_viewers']));
            }
            if (array_key_exists('quality', $body)) {
                $quality = (string) $body['quality'];
                if (in_array($quality, ['auto', '540p', '720p', '1080p'], true)) {
                    $patch['quality'] = $quality;
                }
            }
            if (array_key_exists('viewer_limit', $body)) {
                $patch['max_viewers'] = max(1, min($limit, (int) $body['viewer_limit']));
            }
            $s['phone']['settings'] = array_merge((array) $s['phone']['settings'], $patch);
            $after = $this->settingsOf($s);

            $kick = Http::string($body, 'kick');
            if ($kick !== '' && isset($s['viewers'][$kick])) {
                $s['viewers'][$kick]['kicked'] = true;
                $s['viewers'][$kick]['kicked_at'] = $now;
                $this->appendMessage($s, 'phone', $kick, 'bye', ['reason' => 'kicked']);
            }

            if ($before !== $after) {
                foreach (array_keys($s['viewers']) as $viewerId) {
                    if (empty($s['viewers'][$viewerId]['kicked'])) {
                        $this->appendMessage($s, 'phone', (string) $viewerId, 'state', $after);
                    }
                }
            }
        });

        if ($updated === null) {
            throw ApiException::gone();
        }
        return $this->phonePayload($updated['session']);
    }

    public function phoneStop(array $body): array
    {
        $auth = $this->requireAuth($body, 'phone');
        $this->store->deleteSession((string) $auth['session']['id']);
        return ['stopped' => true];
    }

    private function phonePayload(array $session): array
    {
        $viewers = [];
        foreach ($session['viewers'] as $id => $viewer) {
            if (!empty($viewer['kicked'])) {
                continue;
            }
            $viewers[] = $this->publicViewer($viewer, $this->now()) + ['id' => (string) $id];
        }
        usort($viewers, static fn (array $a, array $b): int => ($a['joinedAt'] ?? 0) <=> ($b['joinedAt'] ?? 0));

        return [
            'sessionId' => (string) $session['id'],
            'name' => (string) $session['name'],
            'viewerUrl' => $this->viewerUrl((string) $session['name']),
            'viewers' => $viewers,
            'viewerCount' => $this->onlineViewerCount($session),
            'settings' => $this->settingsOf($session),
            'expiresAt' => (int) $session['expires_at'],
            'cursor' => max(0, (int) ($session['next_seq'] ?? 1) - 1),
            'serverTime' => $this->now(),
            'turnConfigured' => Ice::configured($this->config),
        ];
    }

    private function publicViewer(array $viewer, int $now): array
    {
        return [
            'label' => (string) ($viewer['label'] ?? 'Appareil'),
            'online' => $this->isViewerOnline($viewer, $now),
            'joinedAt' => (int) ($viewer['joined_at'] ?? 0),
            'lastSeen' => (int) ($viewer['last_seen'] ?? 0),
            'viewport' => $viewer['viewport'] ?? null,
            'stats' => $viewer['stats'] ?? null,
            'controlUsedAt' => isset($viewer['control_used_at']) ? (int) $viewer['control_used_at'] : null,
            'keyboardUsedAt' => isset($viewer['keyboard_used_at']) ? (int) $viewer['keyboard_used_at'] : null,
        ];
    }

    // ------------------------------------------------------------------
    //  Spectateur : présence et départ
    // ------------------------------------------------------------------

    public function viewerHeartbeat(array $body): array
    {
        $auth = $this->requireAuth($body, 'viewer');
        $viewport = $this->sanitizeViewport($body['viewport'] ?? []);
        $stats = $this->sanitizeStats($body['stats'] ?? null);
        $usingControl = Http::bool($body, 'using_control');
        $usingKeyboard = Http::bool($body, 'using_keyboard');

        $updated = $this->store->mutateSession((string) $auth['session']['id'], function (array &$s) use ($auth, $viewport, $stats, $usingControl, $usingKeyboard): void {
            $viewerId = (string) $auth['viewer_id'];
            if (!isset($s['viewers'][$viewerId])) {
                throw ApiException::forbidden('Spectateur inconnu.', 'viewer_removed');
            }
            $now = potato_now();
            $s['viewers'][$viewerId]['last_seen'] = $now;
            if ($viewport !== []) {
                $s['viewers'][$viewerId]['viewport'] = $viewport;
            }
            if ($stats !== null) {
                $s['viewers'][$viewerId]['stats'] = $stats;
            }
            if ($usingControl) {
                $s['viewers'][$viewerId]['control_used_at'] = $now;
            }
            if ($usingKeyboard) {
                $s['viewers'][$viewerId]['keyboard_used_at'] = $now;
            }
        });

        if ($updated === null) {
            throw ApiException::gone();
        }
        $s = $updated['session'];

        return [
            'name' => (string) $s['name'],
            'phoneOnline' => $this->isPhoneOnline($s),
            'phoneLabel' => (string) ($s['phone']['label'] ?? 'Téléphone Android'),
            'viewerCount' => $this->onlineViewerCount($s),
            'settings' => $this->settingsOf($s),
            'expiresAt' => (int) $s['expires_at'],
            'serverTime' => $this->now(),
        ];
    }

    public function viewerLeave(array $body): array
    {
        $auth = $this->requireAuth($body, 'viewer');
        $viewerId = (string) $auth['viewer_id'];
        $this->store->mutateSession((string) $auth['session']['id'], function (array &$s) use ($viewerId): void {
            unset($s['viewers'][$viewerId]);
        });
        $this->store->deleteToken(Http::token($body));
        return ['left' => true];
    }

    // ------------------------------------------------------------------
    //  Signalisation WebRTC (relais HTTP, compatible mutualisé)
    // ------------------------------------------------------------------

    public function signalSend(array $body): array
    {
        $token = Http::token($body);
        $meta = $this->store->getToken($token);
        if ($meta === null) {
            throw ApiException::unauthorized('Jeton invalide ou expiré.', 'invalid_token');
        }
        $role = (string) ($meta['role'] ?? '');
        if ($role !== 'phone' && $role !== 'viewer') {
            throw ApiException::forbidden('Rôle inconnu.', 'wrong_role');
        }
        $auth = $this->requireAuth($body, $role);

        $type = strtolower(Http::string($body, 'type'));
        $rawPayload = $body['payload'] ?? [];
        if (!is_array($rawPayload)) {
            throw ApiException::badRequest('Charge utile invalide.', 'invalid_payload');
        }
        $payload = $this->validateSignalPayload($type, $rawPayload, $role);
        $sessionId = (string) $auth['session']['id'];

        if ($role === 'viewer') {
            $viewerId = (string) $auth['viewer_id'];
            $updated = $this->store->mutateSession($sessionId, function (array &$s) use ($viewerId, $type, $payload): int {
                if (!isset($s['viewers'][$viewerId])) {
                    throw ApiException::forbidden('Spectateur inconnu.', 'viewer_removed');
                }
                return $this->appendMessage($s, 'viewer', $viewerId, $type, $payload);
            });
            if ($updated === null) {
                throw ApiException::gone();
            }
            return ['seq' => (int) $updated['result']];
        }

        $to = Http::string($body, 'to');
        $updated = $this->store->mutateSession($sessionId, function (array &$s) use ($to, $type, $payload): int {
            if ($to === '' || !isset($s['viewers'][$to])) {
                throw ApiException::notFound('Spectateur inconnu.', 'viewer_not_found');
            }
            return $this->appendMessage($s, 'phone', $to, $type, $payload);
        });
        if ($updated === null) {
            throw ApiException::gone();
        }
        return ['seq' => (int) $updated['result']];
    }

    /**
     * Long-polling : la requête reste ouverte jusqu'à `wait` secondes.
     * Compatible PHP-FPM mutualisé (aucun processus permanent requis).
     */
    public function signalPoll(array $body): array
    {
        $token = Http::token($body);
        $meta = $this->store->getToken($token);
        if ($meta === null) {
            throw ApiException::unauthorized('Jeton invalide ou expiré.', 'invalid_token');
        }
        $role = (string) ($meta['role'] ?? '');
        if ($role !== 'phone' && $role !== 'viewer') {
            throw ApiException::forbidden('Rôle inconnu.', 'wrong_role');
        }

        $sessionId = (string) ($meta['session'] ?? '');
        $viewerId = isset($meta['viewer']) ? (string) $meta['viewer'] : '';
        $cursor = max(0, Http::int($body, 'cursor', 0));
        $wait = max(0, min(24, Http::int($body, 'wait', 18)));
        $deadline = microtime(true) + $wait;
        $sentCursor = $cursor;
        $snapshot = null;

        ignore_user_abort(true);
        @set_time_limit(60);

        while (true) {
            $session = $this->store->getSession($sessionId);
            if ($session === null) {
                throw ApiException::gone();
            }
            if ((int) ($session['expires_at'] ?? 0) <= $this->now()) {
                $this->store->deleteSession($sessionId);
                throw ApiException::gone();
            }

            if ($role === 'viewer') {
                $viewer = $session['viewers'][$viewerId] ?? null;
                if ($viewer === null) {
                    throw ApiException::forbidden('Spectateur inconnu.', 'viewer_removed');
                }
                if (!empty($viewer['kicked'])) {
                    throw ApiException::forbidden('Vous avez été retiré de la session par l\'hôte.', 'kicked');
                }
            }

            $messages = [];
            foreach ((array) ($session['messages'] ?? []) as $message) {
                if ((int) ($message['seq'] ?? 0) <= $cursor) {
                    continue;
                }
                if ($role === 'phone' && ($message['from'] ?? '') === 'viewer') {
                    $messages[] = $this->publicMessage($message);
                } elseif ($role === 'viewer' && ($message['from'] ?? '') === 'phone' && ($message['viewer_id'] ?? '') === $viewerId) {
                    $messages[] = $this->publicMessage($message);
                }
            }

            if ($messages !== []) {
                $last = (int) end($messages)['seq'];
                $snapshot = $this->pollSnapshot($session, $role);
                $this->touch($sessionId, $role, $viewerId);
                return ['messages' => $messages, 'cursor' => $last] + $snapshot;
            }

            if (microtime(true) >= $deadline) {
                $snapshot = $this->pollSnapshot($session, $role);
                $this->touch($sessionId, $role, $viewerId);
                return ['messages' => [], 'cursor' => $sentCursor] + $snapshot;
            }

            usleep(250000);
        }
    }

    private function publicMessage(array $message): array
    {
        return [
            'seq' => (int) ($message['seq'] ?? 0),
            'from' => (string) ($message['from'] ?? ''),
            'viewerId' => isset($message['viewer_id']) ? (string) $message['viewer_id'] : null,
            'type' => (string) ($message['type'] ?? ''),
            'payload' => $message['payload'] ?? [],
            'ts' => (int) ($message['ts'] ?? 0),
        ];
    }

    private function pollSnapshot(array $session, string $role): array
    {
        $snapshot = [
            'phoneOnline' => $this->isPhoneOnline($session),
            'phoneLabel' => (string) ($session['phone']['label'] ?? 'Téléphone Android'),
            'settings' => $this->settingsOf($session),
            'viewerCount' => $this->onlineViewerCount($session),
            'expiresAt' => (int) $session['expires_at'],
            'serverTime' => $this->now(),
        ];
        if ($role === 'phone') {
            $viewers = [];
            foreach ($session['viewers'] as $id => $viewer) {
                if (!empty($viewer['kicked'])) {
                    continue;
                }
                $viewers[] = $this->publicViewer($viewer, $this->now()) + ['id' => (string) $id];
            }
            $snapshot['viewers'] = $viewers;
        }
        return $snapshot;
    }

    private function touch(string $sessionId, string $role, string $viewerId): void
    {
        $this->store->mutateSession($sessionId, function (array &$s) use ($role, $viewerId): void {
            $now = potato_now();
            if ($role === 'phone') {
                $s['phone']['online'] = true;
                $s['phone']['last_seen'] = $now;
                return;
            }
            if (isset($s['viewers'][$viewerId])) {
                $s['viewers'][$viewerId]['last_seen'] = $now;
            }
        });
    }

    private function validateSignalPayload(string $type, array $payload, string $role): array
    {
        $json = json_encode($payload, JSON_UNESCAPED_UNICODE);
        if (!is_string($json) || strlen($json) > 32768) {
            throw new ApiException(413, 'payload_too_large', 'Message de signalisation trop volumineux.');
        }

        if ($role === 'viewer' && !in_array($type, ['offer', 'ice', 'bye'], true)) {
            throw ApiException::forbidden('Type de signal non autorisé.', 'signal_type');
        }
        if ($role === 'phone' && !in_array($type, ['answer', 'ice', 'bye', 'notice'], true)) {
            throw ApiException::forbidden('Type de signal non autorisé.', 'signal_type');
        }

        if ($type === 'offer' || $type === 'answer') {
            $sdp = $payload['sdp'] ?? null;
            if (!is_string($sdp) || strlen($sdp) < 40 || strlen($sdp) > 30000) {
                throw ApiException::badRequest('Description de session invalide.', 'invalid_sdp');
            }
            $result = ['sdp' => $sdp];
            if ($type === 'offer') {
                // Le spectateur annonce son cadre : le téléphone adapte son flux.
                $viewport = $this->sanitizeViewport($payload['viewport'] ?? []);
                if ($viewport !== []) {
                    $result['viewport'] = $viewport;
                }
                $mode = $payload['mode'] ?? '';
                if (in_array($mode, ['fit', 'fill'], true)) {
                    $result['mode'] = $mode;
                }
            }
            return $result;
        }

        if ($type === 'ice') {
            $candidate = $payload['candidate'] ?? null;
            $sdpMid = $payload['sdpMid'] ?? null;
            $index = $payload['sdpMLineIndex'] ?? 0;
            if ($candidate === null) {
                return ['candidate' => null];
            }
            if (!is_string($candidate) || strlen($candidate) > 2000) {
                throw ApiException::badRequest('Candidat ICE invalide.', 'invalid_ice');
            }
            if (!is_string($sdpMid) || strlen($sdpMid) > 32) {
                $sdpMid = '0';
            }
            return [
                'candidate' => $candidate,
                'sdpMid' => $sdpMid,
                'sdpMLineIndex' => is_int($index) || is_numeric($index) ? (int) $index : 0,
            ];
        }

        if ($type === 'bye') {
            $reason = isset($payload['reason']) && is_string($payload['reason']) ? $payload['reason'] : 'closed';
            return ['reason' => mb_substr($reason, 0, 64)];
        }

        // notice
        $message = isset($payload['message']) && is_string($payload['message']) ? $payload['message'] : '';
        if ($message === '') {
            throw ApiException::badRequest('Message vide.', 'invalid_notice');
        }
        return [
            'message' => mb_substr($message, 0, 200),
            'level' => isset($payload['level']) && is_string($payload['level']) ? mb_substr($payload['level'], 0, 16) : 'info',
        ];
    }

    private function appendMessage(array &$session, string $from, ?string $viewerId, string $type, array $payload): int
    {
        $now = potato_now();
        $seq = (int) ($session['next_seq'] ?? 1);
        $session['next_seq'] = $seq + 1;
        $session['messages'][] = [
            'seq' => $seq,
            'from' => $from,
            'viewer_id' => $viewerId,
            'type' => $type,
            'payload' => $payload,
            'ts' => $now,
        ];

        $session['messages'] = array_values(array_filter(
            (array) $session['messages'],
            static fn (array $message): bool => ($now - (int) ($message['ts'] ?? 0)) < 90000
        ));
        if (count($session['messages']) > 200) {
            $session['messages'] = array_slice($session['messages'], -200);
        }
        return $seq;
    }

    // ------------------------------------------------------------------
    //  Authentification par jeton
    // ------------------------------------------------------------------

    /**
     * @return array{session:array,viewer_id:string,token:string}
     */
    private function requireAuth(array $body, string $role): array
    {
        $token = Http::token($body);
        if ($token === '') {
            throw ApiException::unauthorized('Jeton manquant.', 'missing_token');
        }
        $meta = $this->store->getToken($token);
        if ($meta === null) {
            throw ApiException::unauthorized('Jeton invalide ou expiré.', 'invalid_token');
        }
        if ((string) ($meta['role'] ?? '') !== $role) {
            throw ApiException::forbidden('Rôle incorrect.', 'wrong_role');
        }

        $session = $this->store->getSession((string) ($meta['session'] ?? ''));
        if ($session === null) {
            throw ApiException::gone();
        }
        if ((int) ($session['expires_at'] ?? 0) <= $this->now()) {
            $this->store->deleteSession((string) $session['id']);
            throw ApiException::gone();
        }

        $viewerId = isset($meta['viewer']) ? (string) $meta['viewer'] : '';
        if ($role === 'viewer') {
            $viewer = $session['viewers'][$viewerId] ?? null;
            if ($viewer === null) {
                throw ApiException::forbidden('Spectateur inconnu.', 'viewer_removed');
            }
            if (!empty($viewer['kicked'])) {
                throw ApiException::forbidden('Vous avez été retiré de la session par l\'hôte.', 'kicked');
            }
        }

        return ['session' => $session, 'viewer_id' => $viewerId, 'token' => $token];
    }

    // ------------------------------------------------------------------
    //  Utilitaires
    // ------------------------------------------------------------------

    private function settingsOf(array $session): array
    {
        $settings = is_array($session['phone']['settings'] ?? null) ? $session['phone']['settings'] : [];
        return [
            'control' => (bool) ($settings['control'] ?? true),
            'keyboard' => (bool) ($settings['keyboard'] ?? true),
            'allow_new_viewers' => (bool) ($settings['allow_new_viewers'] ?? true),
            'max_viewers' => (int) ($settings['max_viewers'] ?? $this->config['max_viewers_default']),
            'quality' => (string) ($settings['quality'] ?? 'auto'),
        ];
    }

    private function sanitizeSettings(mixed $input): array
    {
        if (!is_array($input)) {
            $input = [];
        }
        $quality = is_string($input['quality'] ?? null) ? $input['quality'] : 'auto';
        if (!in_array($quality, ['auto', '540p', '720p', '1080p'], true)) {
            $quality = 'auto';
        }
        $max = isset($input['max_viewers']) ? (int) $input['max_viewers'] : (int) $this->config['max_viewers_default'];
        $max = max(1, min((int) $this->config['max_viewers_limit'], $max));

        return [
            'control' => !array_key_exists('control', $input) || (bool) $input['control'],
            'keyboard' => !array_key_exists('keyboard', $input) || (bool) $input['keyboard'],
            'allow_new_viewers' => !array_key_exists('allow_new_viewers', $input) || (bool) $input['allow_new_viewers'],
            'max_viewers' => $max,
            'quality' => $quality,
        ];
    }

    private function sanitizeScreen(mixed $input): array
    {
        if (!is_array($input)) {
            return [];
        }
        $width = max(1, min(20000, (int) ($input['width'] ?? 0)));
        $height = max(1, min(20000, (int) ($input['height'] ?? 0)));
        if ($width === 0 || $height === 0) {
            return [];
        }
        $rotation = (int) ($input['rotation'] ?? 0);
        if (!in_array($rotation, [0, 90, 180, 270], true)) {
            $rotation = 0;
        }
        return [
            'width' => $width,
            'height' => $height,
            'rotation' => $rotation,
            'density' => max(0.5, min(8.0, (float) ($input['density'] ?? 1.0))),
        ];
    }

    private function sanitizeViewport(mixed $input): array
    {
        if (!is_array($input)) {
            return [];
        }
        $width = (int) ($input['w'] ?? 0);
        $height = (int) ($input['h'] ?? 0);
        if ($width < 120 || $height < 120) {
            return [];
        }
        $width = min(6000, $width);
        $height = min(6000, $height);
        return [
            'w' => $width,
            'h' => $height,
            'dpr' => max(0.5, min(4.0, (float) ($input['dpr'] ?? 1))),
            'aspect' => round($width / $height, 4),
        ];
    }

    private function sanitizeStats(mixed $input): ?array
    {
        if (!is_array($input)) {
            return null;
        }
        return [
            'rtt' => max(0, min(60000, (int) ($input['rtt'] ?? 0))),
            'fps' => max(0, min(240, (int) ($input['fps'] ?? 0))),
            'kbps' => max(0, min(1000000, (int) ($input['kbps'] ?? 0))),
            'width' => max(0, min(8000, (int) ($input['width'] ?? 0))),
            'height' => max(0, min(8000, (int) ($input['height'] ?? 0))),
            'jitter' => max(0, min(10000, (int) ($input['jitter'] ?? 0))),
            'lost' => max(0, min(100, (int) ($input['lost'] ?? 0))),
        ];
    }

    private function viewerLabel(array $body): string
    {
        $label = potato_text(Http::string($body, 'device'), 48);
        if ($label === '') {
            $ua = (string) ($_SERVER['HTTP_USER_AGENT'] ?? '');
            $label = potato_text($ua, 48);
        }
        return $label === '' ? 'Navigateur' : $label;
    }

    private function isPhoneOnline(array $session): bool
    {
        $lastSeen = (int) ($session['phone']['last_seen'] ?? 0);
        return $lastSeen > 0 && ($this->now() - $lastSeen) < (int) $this->config['phone_offline_after'] * 1000;
    }

    private function isViewerOnline(array $viewer, int $now): bool
    {
        if (!empty($viewer['kicked'])) {
            return false;
        }
        return ($now - (int) ($viewer['last_seen'] ?? 0)) < 45000;
    }

    private function onlineViewerCount(array $session): int
    {
        $now = $this->now();
        $count = 0;
        foreach ((array) ($session['viewers'] ?? []) as $viewer) {
            if ($this->isViewerOnline((array) $viewer, $now)) {
                $count++;
            }
        }
        return $count;
    }

    private function pruneViewers(array &$session): void
    {
        $now = $this->now();
        $idleMs = max(600, (int) $this->config['viewer_idle_timeout']) * 1000;
        foreach ((array) ($session['viewers'] ?? []) as $id => $viewer) {
            $kickedAt = (int) ($viewer['kicked_at'] ?? 0);
            if (!empty($viewer['kicked'])) {
                if ($kickedAt > 0 && $now - $kickedAt > 180000) {
                    unset($session['viewers'][$id]);
                }
                continue;
            }
            if ($now - (int) ($viewer['last_seen'] ?? 0) > $idleMs) {
                unset($session['viewers'][$id]);
            }
        }
    }
}

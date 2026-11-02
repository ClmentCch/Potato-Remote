<?php
declare(strict_types=1);

/**
 * Stockage fichier verrouillé (compatible hébergement mutualisé o2switch) :
 * aucune extension exotique, aucun serveur de base de données.
 *
 *  data/sessions/<id>.json   état d'une session
 *  data/tokens/<sha256>.json index des jetons (téléphone / spectateur)
 *  data/names/<sha1>.json    index des noms de session actifs
 *  data/ratelimit/<sha1>.json compteurs anti-bruteforce
 */
final class Store
{
    private string $dir;

    public function __construct(string $dir)
    {
        $this->dir = rtrim($dir, '/');
        foreach (['sessions', 'tokens', 'names', 'ratelimit'] as $sub) {
            $path = $this->dir . '/' . $sub;
            if (!is_dir($path) && !@mkdir($path, 0700, true) && !is_dir($path)) {
                throw new RuntimeException('Dossier de données inaccessible en écriture.');
            }
        }
        if (!is_writable($this->dir)) {
            throw new RuntimeException('Dossier de données inaccessible en écriture.');
        }
    }

    public function dataDir(): string
    {
        return $this->dir;
    }

    private static function readJson(string $path): ?array
    {
        $fp = @fopen($path, 'rb');
        if (!$fp) {
            return null;
        }
        $raw = false;
        try {
            if (!flock($fp, LOCK_SH)) {
                return null;
            }
            $raw = stream_get_contents($fp);
        } finally {
            @flock($fp, LOCK_UN);
            fclose($fp);
        }
        if (!is_string($raw) || $raw === '') {
            return null;
        }
        $data = json_decode($raw, true);
        return is_array($data) ? $data : null;
    }

    private static function writeJson(string $path, array $data): bool
    {
        $fp = @fopen($path, 'c+b');
        if (!$fp) {
            return false;
        }
        $ok = false;
        try {
            if (!flock($fp, LOCK_EX)) {
                return false;
            }
            $raw = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
            if (!is_string($raw)) {
                return false;
            }
            ftruncate($fp, 0);
            rewind($fp);
            $ok = fwrite($fp, $raw) !== false;
            fflush($fp);
        } finally {
            @flock($fp, LOCK_UN);
            fclose($fp);
            @chmod($path, 0600);
        }
        return $ok;
    }

    private static function validId(string $id): bool
    {
        return (bool) preg_match('/^[a-f0-9]{8,32}$/', $id);
    }

    public function sessionFile(string $id): string
    {
        return $this->dir . '/sessions/' . $id . '.json';
    }

    public function sessionExists(string $id): bool
    {
        return self::validId($id) && is_file($this->sessionFile($id));
    }

    public function createSession(array $session): void
    {
        $id = (string) ($session['id'] ?? '');
        if (!self::validId($id)) {
            throw new RuntimeException('Identifiant de session invalide.');
        }
        if (!$this->writeJson($this->sessionFile($id), $session)) {
            throw new RuntimeException('Impossible d\'écrire la session.');
        }
    }

    public function getSession(string $id): ?array
    {
        if (!self::validId($id)) {
            return null;
        }
        return self::readJson($this->sessionFile($id));
    }

    /**
     * Applique une modification atomique à une session.
     * @return array{session:array,result:mixed}|null
     */
    public function mutateSession(string $id, callable $fn): ?array
    {
        if (!self::validId($id)) {
            return null;
        }
        $path = $this->sessionFile($id);
        $fp = @fopen($path, 'c+b');
        if (!$fp) {
            return null;
        }
        try {
            if (!flock($fp, LOCK_EX)) {
                return null;
            }
            $raw = stream_get_contents($fp);
            $session = is_string($raw) && $raw !== '' ? json_decode($raw, true) : null;
            if (!is_array($session)) {
                return null;
            }
            /** @var mixed $result */
            $result = $fn($session);
            $session['updated_at'] = potato_now();
            $encoded = json_encode($session, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
            if (!is_string($encoded)) {
                return null;
            }
            ftruncate($fp, 0);
            rewind($fp);
            fwrite($fp, $encoded);
            fflush($fp);
            return ['session' => $session, 'result' => $result];
        } finally {
            @flock($fp, LOCK_UN);
            fclose($fp);
        }
    }

    public function deleteSession(string $id): void
    {
        $session = $this->getSession($id);
        if ($session !== null) {
            $this->deleteNameIndex((string) ($session['name_key'] ?? ''));
        }
        $this->deleteSessionTokens($id);
        @unlink($this->sessionFile($id));
    }

    /** @return string[] */
    public function sessionIds(): array
    {
        $ids = [];
        foreach (glob($this->dir . '/sessions/*.json') ?: [] as $path) {
            $ids[] = basename($path, '.json');
        }
        return $ids;
    }

    public function findSessionByName(string $nameKey): ?array
    {
        $path = $this->dir . '/names/' . sha1($nameKey) . '.json';
        $index = self::readJson($path);
        if (!is_array($index)) {
            return null;
        }
        $id = (string) ($index['session'] ?? '');
        if ($id === '') {
            return null;
        }
        $session = $this->getSession($id);
        if (!is_array($session)) {
            @unlink($path);
            return null;
        }
        if (!hash_equals((string) ($session['name_key'] ?? ''), $nameKey)) {
            return null;
        }
        if ((int) ($session['expires_at'] ?? 0) <= potato_now()) {
            return null;
        }
        return $session;
    }

    public function writeNameIndex(string $nameKey, string $sessionId, int $expiresAt): void
    {
        if ($nameKey === '') {
            return;
        }
        self::writeJson($this->dir . '/names/' . sha1($nameKey) . '.json', [
            'name_key' => $nameKey,
            'session' => $sessionId,
            'expires_at' => $expiresAt,
        ]);
    }

    public function deleteNameIndex(string $nameKey): void
    {
        if ($nameKey !== '') {
            @unlink($this->dir . '/names/' . sha1($nameKey) . '.json');
        }
    }

    public function putToken(string $token, string $sessionId, string $role, ?string $viewerId = null): void
    {
        if ($token === '' || ($role !== 'phone' && $role !== 'viewer')) {
            throw new RuntimeException('Jeton invalide.');
        }
        self::writeJson($this->dir . '/tokens/' . hash('sha256', $token) . '.json', [
            'session' => $sessionId,
            'role' => $role,
            'viewer' => $viewerId,
            'created_at' => potato_now(),
        ]);
    }

    public function getToken(string $token): ?array
    {
        if (strlen($token) < 20) {
            return null;
        }
        return self::readJson($this->dir . '/tokens/' . hash('sha256', $token) . '.json');
    }

    public function deleteToken(string $token): void
    {
        if ($token !== '') {
            @unlink($this->dir . '/tokens/' . hash('sha256', $token) . '.json');
        }
    }

    public function deleteSessionTokens(string $sessionId): void
    {
        foreach (glob($this->dir . '/tokens/*.json') ?: [] as $path) {
            $meta = self::readJson($path);
            if (!is_array($meta) || (string) ($meta['session'] ?? '') === $sessionId) {
                @unlink($path);
            }
        }
    }

    /**
     * Compteur anti-bruteforce.
     * @return array{allowed:bool,retryAfter:int,remaining:int,attempts:int}
     */
    public function rateHit(string $key, int $max, int $window, int $lockSeconds = 0): array
    {
        $now = time();
        $path = $this->dir . '/ratelimit/' . sha1($key) . '.json';
        $data = self::readJson($path) ?? ['hits' => [], 'locked_until' => 0];
        $hits = array_values(array_filter(
            is_array($data['hits'] ?? null) ? $data['hits'] : [],
            static fn ($ts): bool => is_int($ts) && $ts > $now - max(1, $window)
        ));
        $lockedUntil = (int) ($data['locked_until'] ?? 0);

        if ($lockedUntil > $now) {
            return ['allowed' => false, 'retryAfter' => $lockedUntil - $now, 'remaining' => 0, 'attempts' => count($hits)];
        }

        if (count($hits) >= $max) {
            $lockedUntil = $lockSeconds > 0 ? $now + $lockSeconds : 0;
            self::writeJson($path, ['hits' => $hits, 'locked_until' => $lockedUntil]);
            return [
                'allowed' => false,
                'retryAfter' => $lockSeconds > 0 ? $lockSeconds : max(1, $window),
                'remaining' => 0,
                'attempts' => count($hits),
            ];
        }

        $hits[] = $now;
        self::writeJson($path, ['hits' => $hits, 'locked_until' => 0]);
        return ['allowed' => true, 'retryAfter' => 0, 'remaining' => max(0, $max - count($hits)), 'attempts' => count($hits)];
    }

    public function rateReset(string $key): void
    {
        @unlink($this->dir . '/ratelimit/' . sha1($key) . '.json');
    }

    /**
     * Nettoyage opportuniste : sessions expirées, jetons orphelins, vieux compteurs.
     * Appelé avec une faible probabilité à chaque requête, sans planificateur.
     */
    public function gc(array $config): void
    {
        $now = potato_now();

        foreach ($this->sessionIds() as $id) {
            $session = $this->getSession($id);
            if (!is_array($session)) {
                @unlink($this->sessionFile($id));
                continue;
            }
            $expired = (int) ($session['expires_at'] ?? 0) <= $now;
            $phoneSeen = (int) ($session['phone']['last_seen'] ?? 0);
            $abandoned = $phoneSeen > 0 && ($now - $phoneSeen) > 1800000 && empty($session['viewers']);
            if ($expired || $abandoned) {
                $this->deleteSession($id);
            }
        }

        $ttl = max(3600, (int) ($config['session_ttl'] ?? 43200)) * 1000;
        foreach (glob($this->dir . '/tokens/*.json') ?: [] as $path) {
            $meta = self::readJson($path);
            if (!is_array($meta)) {
                @unlink($path);
                continue;
            }
            $session = $this->getSession((string) ($meta['session'] ?? ''));
            if (!is_array($session) || (int) ($session['expires_at'] ?? 0) <= $now) {
                @unlink($path);
                continue;
            }
            if ($now - (int) ($meta['created_at'] ?? 0) > $ttl + 60000) {
                @unlink($path);
            }
        }

        foreach (glob($this->dir . '/names/*.json') ?: [] as $path) {
            $index = self::readJson($path);
            if (!is_array($index) || !$this->sessionExists((string) ($index['session'] ?? ''))) {
                @unlink($path);
            }
        }

        foreach (glob($this->dir . '/ratelimit/*.json') ?: [] as $path) {
            $mtime = @filemtime($path);
            if ($mtime !== false && $mtime < time() - 172800) {
                @unlink($path);
            }
        }
    }
}

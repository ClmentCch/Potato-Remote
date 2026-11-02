<?php
declare(strict_types=1);

/**
 * Erreur métier renvoyée au client avec un code HTTP et un message lisible.
 */
final class ApiException extends RuntimeException
{
    public function __construct(
        public readonly int $status,
        public readonly string $errorCode,
        string $message,
        public readonly array $extra = []
    ) {
        parent::__construct($message);
    }

    public static function badRequest(string $message, string $code = 'invalid_request', array $extra = []): self
    {
        return new self(400, $code, $message, $extra);
    }

    public static function unauthorized(string $message = 'Authentification requise.', string $code = 'unauthorized'): self
    {
        return new self(401, $code, $message);
    }

    public static function forbidden(string $message, string $code = 'forbidden', array $extra = []): self
    {
        return new self(403, $code, $message, $extra);
    }

    public static function notFound(string $message = 'Session introuvable.', string $code = 'not_found'): self
    {
        return new self(404, $code, $message);
    }

    public static function gone(string $message = 'La session est terminée.', string $code = 'session_ended'): self
    {
        return new self(410, $code, $message);
    }

    public static function tooMany(string $message, int $retryAfter): self
    {
        return new self(429, 'rate_limited', $message, ['retryAfter' => $retryAfter]);
    }
}

/**
 * Couche HTTP : lecture de la requête et réponses JSON normalisées.
 * Enveloppe de succès : {ok:true, data:{...}}
 * Enveloppe d'erreur  : {ok:false, error:{code, message, ...}}
 */
final class Http
{
    private const MAX_BODY = 65536;

    public static function method(): string
    {
        return strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET'));
    }

    public static function requireMethod(string ...$allowed): void
    {
        if (!in_array(self::method(), $allowed, true)) {
            header('Allow: ' . implode(', ', $allowed));
            self::error(405, 'method_not_allowed', 'Méthode HTTP non autorisée.');
        }
    }

    public static function body(): array
    {
        $raw = file_get_contents('php://input');
        if ($raw === false) {
            return [];
        }
        if (strlen($raw) > self::MAX_BODY) {
            throw new ApiException(413, 'payload_too_large', 'Requête trop volumineuse.');
        }
        $raw = trim($raw);
        if ($raw === '') {
            return is_array($_POST) ? $_POST : [];
        }
        $decoded = json_decode($raw, true);
        if (!is_array($decoded)) {
            throw new ApiException(400, 'invalid_json', 'Corps JSON invalide.');
        }
        return $decoded;
    }

    public static function input(array $body, string $key, mixed $default = null): mixed
    {
        if (array_key_exists($key, $body)) {
            return $body[$key];
        }
        if (array_key_exists($key, $_GET)) {
            return $_GET[$key];
        }
        return $default;
    }

    public static function string(array $body, string $key, string $default = ''): string
    {
        $value = self::input($body, $key, $default);
        return is_string($value) ? $value : $default;
    }

    public static function int(array $body, string $key, int $default = 0): int
    {
        $value = self::input($body, $key, $default);
        if (is_int($value)) {
            return $value;
        }
        if (is_string($value) && preg_match('/^-?\d+$/', $value)) {
            return (int) $value;
        }
        if (is_float($value)) {
            return (int) $value;
        }
        return $default;
    }

    public static function bool(array $body, string $key, bool $default = false): bool
    {
        $value = self::input($body, $key, $default);
        if (is_bool($value)) {
            return $value;
        }
        if (is_int($value)) {
            return $value !== 0;
        }
        if (is_string($value)) {
            return in_array(strtolower($value), ['1', 'true', 'yes', 'on'], true);
        }
        return $default;
    }

    /** Jeton porteur : en-tête Authorization, X-Potato-Token ou champ `token`. */
    public static function token(array $body = []): string
    {
        $header = self::header('HTTP_AUTHORIZATION');
        if ($header !== null && preg_match('/^Bearer\s+(.+)$/i', $header, $m)) {
            return trim($m[1]);
        }
        $custom = self::header('HTTP_X_POTATO_TOKEN');
        if ($custom !== null && $custom !== '') {
            return trim($custom);
        }
        $field = $body['token'] ?? null;
        return is_string($field) ? trim($field) : '';
    }

    public static function header(string $key): ?string
    {
        $value = $_SERVER[$key] ?? null;
        if ($value === null && $key === 'HTTP_AUTHORIZATION' && function_exists('getallheaders')) {
            foreach (getallheaders() as $name => $headerValue) {
                if (strcasecmp((string) $name, 'Authorization') === 0) {
                    return (string) $headerValue;
                }
            }
        }
        return is_string($value) ? $value : null;
    }

    public static function clientIp(array $config): string
    {
        if (!empty($config['trusted_proxy'])) {
            $forwarded = self::header('HTTP_X_FORWARDED_FOR');
            if ($forwarded !== null && $forwarded !== '') {
                $first = trim(explode(',', $forwarded)[0]);
                if (filter_var($first, FILTER_VALIDATE_IP)) {
                    return $first;
                }
            }
        }
        $remote = (string) ($_SERVER['REMOTE_ADDR'] ?? '0.0.0.0');
        return filter_var($remote, FILTER_VALIDATE_IP) ? $remote : '0.0.0.0';
    }

    public static function ok(array $data = [], int $status = 200): never
    {
        self::send($status, ['ok' => true, 'data' => $data]);
    }

    public static function error(int $status, string $code, string $message, array $extra = []): never
    {
        self::send($status, [
            'ok' => false,
            'error' => array_merge(['code' => $code, 'message' => $message], $extra),
        ]);
    }

    private static function send(int $status, array $payload): never
    {
        if (!headers_sent()) {
            http_response_code($status);
            header('Content-Type: application/json; charset=utf-8');
            header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
            header('Pragma: no-cache');
            header('X-Content-Type-Options: nosniff');
            header('Referrer-Policy: no-referrer');
        }
        echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
        exit;
    }
}

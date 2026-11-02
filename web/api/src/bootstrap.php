<?php
/**
 * Potato Remote — amorçage de l'API.
 * Compatible PHP 8.1+ (o2switch : PHP 8.2 ou 8.3 recommandé).
 */
declare(strict_types=1);

if (!defined('POTATO_API')) {
    define('POTATO_API', true);
}

ini_set('display_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);
mb_internal_encoding('UTF-8');

require_once __DIR__ . '/Http.php';
require_once __DIR__ . '/Store.php';
require_once __DIR__ . '/Ice.php';
require_once __DIR__ . '/Api.php';

/**
 * Charge la configuration et applique les valeurs par défaut.
 */
function potato_config(): array
{
    static $config = null;
    if (is_array($config)) {
        return $config;
    }

    $file = dirname(__DIR__) . '/config.php';
    $loaded = is_file($file) ? require $file : [];
    if (!is_array($loaded)) {
        $loaded = [];
    }

    $defaults = [
        'site_name' => 'Potato Remote',
        'api_version' => 1,
        'base_url' => '',
        'data_dir' => dirname(__DIR__, 2) . '/data',
        'session_ttl' => 43200,
        'phone_offline_after' => 30,
        'viewer_idle_timeout' => 120,
        'phone_grace' => 600,
        'max_viewers_limit' => 8,
        'max_viewers_default' => 4,
        'login_attempts_window' => 600,
        'login_attempts_max' => 8,
        'login_lock_seconds' => 900,
        'create_window' => 3600,
        'create_max' => 20,
        'allow_public_create' => true,
        'create_secret' => '',
        'allow_http' => false,
        'trusted_proxy' => false,
        'ice_servers' => [['urls' => ['stun:stun.l.google.com:19302']]],
        'turn' => ['urls' => [], 'shared_secret' => '', 'username' => '', 'credential' => '', 'ttl' => 3600],
    ];

    $config = array_replace($defaults, $loaded);
    $config['turn'] = array_replace($defaults['turn'], is_array($loaded['turn'] ?? null) ? $loaded['turn'] : []);
    if (!is_array($config['ice_servers'])) {
        $config['ice_servers'] = $defaults['ice_servers'];
    }
    $config['data_dir'] = rtrim((string) $config['data_dir'], '/');

    return $config;
}

/** Horodatage courant en millisecondes. */
function potato_now(): int
{
    return (int) floor(microtime(true) * 1000);
}

/** Nettoie un libellé fourni par un client (nom d'appareil, etc.). */
function potato_text(string $value, int $maxLength): string
{
    $value = preg_replace('/[\x00-\x1F\x7F]/u', '', $value) ?? '';
    $value = trim(preg_replace('/\s+/u', ' ', $value) ?? '');
    if (mb_strlen($value) > $maxLength) {
        $value = mb_substr($value, 0, $maxLength);
    }
    return $value;
}

/** Valide un nom de session : 3 à 32 caractères, lettres/chiffres/espaces/-_. */
function potato_valid_session_name(string $name): bool
{
    $length = mb_strlen($name);
    if ($length < 3 || $length > 32) {
        return false;
    }
    return (bool) preg_match('/^[\p{L}\p{N}][\p{L}\p{N} ._-]*$/u', $name);
}

/** Clé de comparaison insensible à la casse et aux accents. */
function potato_name_key(string $name): string
{
    $key = mb_strtolower(trim($name), 'UTF-8');
    if (function_exists('iconv')) {
        $ascii = @iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $key);
        if (is_string($ascii) && $ascii !== '') {
            $key = strtolower($ascii);
        }
    }
    return preg_replace('/\s+/', ' ', $key) ?? $key;
}

/**
 * URL de base du site, avec détection automatique derrière un proxy o2switch.
 */
function potato_base_url(array $config): string
{
    $configured = trim((string) ($config['base_url'] ?? ''));
    if ($configured !== '') {
        return rtrim($configured, '/');
    }

    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
    $proto = $https ? 'https' : 'http';
    if (!empty($config['trusted_proxy']) && !empty($_SERVER['HTTP_X_FORWARDED_PROTO'])) {
        $proto = strtolower(trim(explode(',', (string) $_SERVER['HTTP_X_FORWARDED_PROTO'])[0]));
        $proto = $proto === 'https' ? 'https' : 'http';
    }

    $host = (string) ($_SERVER['HTTP_HOST'] ?? 'localhost');
    $script = (string) ($_SERVER['SCRIPT_NAME'] ?? '/api/index.php');
    $root = str_replace('\\', '/', dirname(dirname($script)));
    $root = ($root === '/' || $root === '.') ? '' : rtrim($root, '/');

    return $proto . '://' . $host . $root;
}

/** Vérifie que la requête arrive bien en HTTPS (hors développement local). */
function potato_require_https(array $config): void
{
    if (!empty($config['allow_http'])) {
        return;
    }
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
    if (!$https && !empty($config['trusted_proxy']) && !empty($_SERVER['HTTP_X_FORWARDED_PROTO'])) {
        $https = strtolower(trim(explode(',', (string) $_SERVER['HTTP_X_FORWARDED_PROTO'])[0])) === 'https';
    }
    $host = strtolower((string) ($_SERVER['HTTP_HOST'] ?? ''));
    $isLocal = $host !== '' && (str_starts_with($host, 'localhost') || str_starts_with($host, '127.0.0.1'));

    if (!$https && !$isLocal) {
        Http::error(400, 'https_required', 'Potato Remote exige une connexion HTTPS.');
    }
}

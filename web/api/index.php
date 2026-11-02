<?php
declare(strict_types=1);

/**
 * Potato Remote — point d'entrée unique de l'API.
 *
 * Compatible hébergement mutualisé o2switch : PHP-FPM, aucun service permanent.
 * Les actions sont passées en paramètre `action` (pas de réécriture requise).
 * Exemple : POST /api/index.php?action=session.create
 */
define('POTATO_API', true);
require __DIR__ . '/src/bootstrap.php';

$config = potato_config();
$action = (string) ($_GET['action'] ?? '');
$store = null;

try {
    // Le dossier data/ est créé automatiquement au premier appel.
    $store = new Store((string) $config['data_dir']);
    $api = new Api($config, $store);

    if (random_int(1, 100) <= 3) {
        $store->gc($config);
    }

    switch ($action) {
        case 'config':
            Http::requireMethod('GET');
            Http::ok($api->publicConfig());
            break;

        case 'health':
            Http::requireMethod('GET');
            Http::ok($api->health());
            break;

        case 'session.create':
            Http::requireMethod('POST');
            Http::ok($api->createSession(Http::body()));
            break;

        case 'session.auth':
            Http::requireMethod('POST');
            Http::ok($api->authViewer(Http::body()));
            break;

        case 'session.info':
            Http::requireMethod('POST');
            Http::ok($api->viewerInfo(Http::body()));
            break;

        case 'phone.status':
            Http::requireMethod('POST');
            Http::ok($api->phoneStatus(Http::body()));
            break;

        case 'phone.update':
            Http::requireMethod('POST');
            Http::ok($api->phoneUpdate(Http::body()));
            break;

        case 'phone.stop':
            Http::requireMethod('POST');
            Http::ok($api->phoneStop(Http::body()));
            break;

        case 'viewer.heartbeat':
            Http::requireMethod('POST');
            Http::ok($api->viewerHeartbeat(Http::body()));
            break;

        case 'viewer.leave':
            Http::requireMethod('POST');
            Http::ok($api->viewerLeave(Http::body()));
            break;

        case 'signal.send':
            Http::requireMethod('POST');
            Http::ok($api->signalSend(Http::body()));
            break;

        case 'signal.poll':
            Http::requireMethod('POST');
            Http::ok($api->signalPoll(Http::body()));
            break;

        default:
            Http::error(404, 'not_found', 'Action inconnue.');
    }
} catch (ApiException $exception) {
    if ($exception->errorCode === 'rate_limited' && isset($exception->extra['retryAfter'])) {
        header('Retry-After: ' . (int) $exception->extra['retryAfter']);
    }
    Http::error($exception->status, $exception->errorCode, $exception->getMessage(), $exception->extra);
} catch (Throwable $exception) {
    error_log('[potato-remote] ' . $exception->getMessage() . ' @ ' . $exception->getFile() . ':' . $exception->getLine());
    Http::error(500, 'server_error', 'Erreur interne du serveur. Consultez les journaux PHP.');
}

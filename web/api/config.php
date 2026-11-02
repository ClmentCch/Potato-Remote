<?php
/**
 * Potato Remote — configuration serveur.
 *
 * Ce fichier est le seul à modifier après l'installation sur o2switch.
 * Il n'est jamais servi en clair : il est exécuté par PHP.
 */
declare(strict_types=1);

return [
    // Nom affiché dans l'interface (marque).
    'site_name' => 'Potato Remote',

    // Version du protocole et de l'API (affichée par /api?action=config).
    'api_version' => 1,

    // URL publique du site. Laissez vide pour la détection automatique
    // (recommandé sur o2switch). Exemple : 'https://potato.example.com'.
    'base_url' => '',

    // Dossier de stockage des sessions (doit être inscriptible par PHP).
    'data_dir' => dirname(__DIR__) . '/data',

    // Durée de vie d'une session, en secondes (12 h par défaut).
    'session_ttl' => 43200,

    // Délai après lequel le téléphone est considéré hors ligne (secondes).
    'phone_offline_after' => 30,

    // Délai après lequel un spectateur inactif est retiré (secondes).
    'viewer_idle_timeout' => 600,

    // Un spectateur peut rejoindre une session dont le téléphone est absent
    // pendant au maximum cette durée (secondes).
    'phone_grace' => 600,

    // Nombre maximal de spectateurs (valeur plafond, configurable par le téléphone).
    'max_viewers_limit' => 8,
    'max_viewers_default' => 4,

    // Recherche de spectateur : fenêtre (s) et nombre d'essais, puis verrouillage.
    'login_attempts_window' => 600,
    'login_attempts_max' => 8,
    'login_lock_seconds' => 900,

    // Création de session par IP : fenêtre (s) et maximum.
    'create_window' => 3600,
    'create_max' => 20,

    // Autoriser la création de session sans authentification supplémentaire.
    // Si vous mettez une valeur dans create_secret, l'application Android
    // devra la fournir (champ « Clé serveur » dans les paramètres).
    'allow_public_create' => true,
    'create_secret' => '',

    // Accepter HTTP en clair (uniquement pour les tests locaux !).
    'allow_http' => false,

    // Le serveur est-il derrière un proxy de confiance (o2switch/CDN) ?
    // Si true, X-Forwarded-For / X-Forwarded-Proto sont pris en compte.
    'trusted_proxy' => false,

    /**
     * Serveurs STUN/TURN transmis aux clients WebRTC.
     *
     * STUN suffit lorsque les deux réseaux autorisent les connexions directes.
     * TURN est OBLIGATOIRE pour garantir le fonctionnement en 4G/5G, derrière
     * certains routeurs ou entre deux réseaux d'entreprise (relais chiffré).
     *
     * Deux méthodes pour TURN :
     *  1. Identifiants statiques :
     *     ['urls' => ['turn:turn.example.com:3478'], 'username' => '...', 'credential' => '...']
     *  2. Identifiants éphémères coturn (recommandé) : renseignez `turn` plus bas
     *     avec `shared_secret` (static-auth-secret de coturn), sans rien mettre ici.
     */
    'ice_servers' => [
        ['urls' => ['stun:stun.l.google.com:19302']],
        ['urls' => ['stun:stun1.l.google.com:19302']],
    ],

    /**
     * TURN éphémère (coturn avec static-auth-secret).
     * Laissez `urls` vide si vous n'utilisez pas TURN.
     * Documentation : docs/TURN.md
     */
    'turn' => [
        'urls' => [
            // 'turn:turn.example.com:3478?transport=udp',
            // 'turns:turn.example.com:5349?transport=tcp',
        ],
        'shared_secret' => '',
        'username' => '',
        'credential' => '',
        'ttl' => 3600,
    ],
];

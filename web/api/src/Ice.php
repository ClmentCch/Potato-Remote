<?php
declare(strict_types=1);

/**
 * Construction de la liste ICE envoyée aux clients WebRTC.
 * STUN : découverte d'adresse publique. TURN : relais chiffré indispensable
 * derrière les NAT symétriques (4G/5G, réseaux d'entreprise, certains Wi-Fi).
 */
final class Ice
{
    /** @return list<array{urls:list<string>,username?:string,credential?:string}> */
    public static function servers(array $config): array
    {
        $servers = [];

        foreach ((array) ($config['ice_servers'] ?? []) as $entry) {
            if (!is_array($entry)) {
                continue;
            }
            $urls = self::normalizeUrls($entry['urls'] ?? []);
            if ($urls === []) {
                continue;
            }
            $server = ['urls' => $urls];
            if (!empty($entry['username']) && is_string($entry['username'])) {
                $server['username'] = $entry['username'];
            }
            if (!empty($entry['credential']) && is_string($entry['credential'])) {
                $server['credential'] = $entry['credential'];
            }
            $servers[] = $server;
        }

        $turn = is_array($config['turn'] ?? null) ? $config['turn'] : [];
        $urls = self::normalizeUrls($turn['urls'] ?? []);
        if ($urls !== []) {
            $secret = trim((string) ($turn['shared_secret'] ?? ''));
            if ($secret !== '') {
                // Identifiants éphémères coturn (TURN REST API, HMAC-SHA1).
                $ttl = max(300, min(86400, (int) ($turn['ttl'] ?? 3600)));
                $username = (time() + $ttl) . ':potato';
                $credential = base64_encode(hash_hmac('sha1', $username, $secret, true));
                $servers[] = ['urls' => $urls, 'username' => $username, 'credential' => $credential];
            } elseif (trim((string) ($turn['username'] ?? '')) !== '') {
                $servers[] = [
                    'urls' => $urls,
                    'username' => (string) $turn['username'],
                    'credential' => (string) ($turn['credential'] ?? ''),
                ];
            }
        }

        return $servers;
    }

    /** Indique si au moins un serveur TURN utilisable est configuré. */
    public static function configured(array $config): bool
    {
        foreach (self::servers($config) as $server) {
            foreach ($server['urls'] as $url) {
                if (str_starts_with($url, 'turn:') || str_starts_with($url, 'turns:')) {
                    if (!empty($server['username']) && !empty($server['credential'])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param mixed $urls
     * @return list<string>
     */
    private static function normalizeUrls(mixed $urls): array
    {
        if (is_string($urls)) {
            $urls = [$urls];
        }
        if (!is_array($urls)) {
            return [];
        }
        $result = [];
        foreach ($urls as $url) {
            if (!is_string($url)) {
                continue;
            }
            $url = trim($url);
            if (preg_match('/^(stuns?|turns?):[^\s]{3,200}$/i', $url)) {
                $result[] = $url;
            }
        }
        return array_values(array_unique($result));
    }
}

<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, max-age=0');

$url = $_GET['url'] ?? '';
if (!filter_var($url, FILTER_VALIDATE_URL) || !preg_match('~^https?://~i', $url)) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid stream URL']);
    exit;
}

$parts = parse_url($url);
$host = strtolower($parts['host'] ?? '');
if ($host === '' || in_array($host, ['localhost', '127.0.0.1', '::1'], true) ||
    preg_match('/^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/', $host)) {
    http_response_code(400);
    echo json_encode(['error' => 'Host not allowed']);
    exit;
}

// Metadata jsou doplněk přehrávání. Pokud hostitel nemá cURL, nesmí to rozbít
// přehrávač ani vracet opakovanou chybu 500 do konzole.
if (!function_exists('curl_init')) {
    http_response_code(204);
    exit;
}

$metaInt = null;
$body = '';
$result = null;
$aborted = false;
$metadataBlocks = 0;

$ch = curl_init($url);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => false,
    CURLOPT_FOLLOWLOCATION => true,
    CURLOPT_MAXREDIRS => 3,
    CURLOPT_CONNECTTIMEOUT => 5,
    CURLOPT_TIMEOUT => 18,
    CURLOPT_HTTPHEADER => ['Icy-MetaData: 1', 'User-Agent: OpenRadio/1.0'],
    CURLOPT_HEADERFUNCTION => function($ch, $header) use (&$metaInt) {
        if (stripos($header, 'icy-metaint:') === 0) {
            $metaInt = (int)trim(substr($header, strlen('icy-metaint:')));
        }
        return strlen($header);
    },
    CURLOPT_WRITEFUNCTION => function($ch, $chunk) use (&$body, &$metaInt, &$result, &$aborted) {
        if ($metaInt === null) {
            if (strlen($body) < 8192) $body .= $chunk;
            return strlen($chunk);
        }

        $body .= $chunk;
        if (strlen($body) < $metaInt + 1) return strlen($chunk);

        // ICY metadata can arrive split across several cURL chunks. Keep the
        // bytes until one complete metadata block is available.
        while (strlen($body) >= $metaInt + 1) {
            $len = ord($body[$metaInt]) * 16;
            if (strlen($body) < $metaInt + 1 + $len) return strlen($chunk);

            $meta = substr($body, $metaInt + 1, $len);
            if (preg_match('/StreamTitle\\s*=\\s*[\'"]([^\'"]*)[\'"]\\s*;/i', $meta, $m)) {
                $raw = trim(preg_replace('/\\s+/', ' ', $m[1]));
            $parts = explode(' - ', $raw);
            if (count($parts) > 1) {
                $artist = trim(array_shift($parts));
                $title = trim(implode(' - ', $parts));
            } else {
                $artist = '';
                $title = $raw;
            }
                if ($raw !== '') {
                    $result = ['title' => $title, 'artist' => $artist, 'raw' => $raw];
                }
            }
            $metadataBlocks++;
            $body = substr($body, $metaInt + 1 + $len);
            if ($result || $metadataBlocks >= 8) {
                $aborted = true;
                return 0;
            }
        }
        return strlen($chunk);
    }
]);

curl_exec($ch);
curl_close($ch);

if ($result) {
    echo json_encode($result, JSON_UNESCAPED_UNICODE);
    exit;
}

// Stream bez ICY metadat nebo nedostupný stream není chyba přehrávače.
// 204 umožní klientovi tento zdroj potichu vypnout pro zbytek relace.
http_response_code(204);

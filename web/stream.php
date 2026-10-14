<?php
declare(strict_types=1);

$url = $_GET['url'] ?? '';
if (!filter_var($url, FILTER_VALIDATE_URL) || !preg_match('~^http://~i', $url)) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'Invalid HTTP stream URL';
    exit;
}

$parts = parse_url($url);
$host = strtolower($parts['host'] ?? '');
if ($host === '' || in_array($host, ['localhost', '127.0.0.1', '::1'], true) ||
    preg_match('/^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)/', $host)) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'Host not allowed';
    exit;
}

if (!function_exists('curl_init')) {
    http_response_code(500);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'cURL is required';
    exit;
}

set_time_limit(0);
ignore_user_abort(false);

$sentHeaders = false;

$ch = curl_init($url);
curl_setopt_array($ch, [
    CURLOPT_FOLLOWLOCATION => true,
    CURLOPT_MAXREDIRS => 5,
    CURLOPT_CONNECTTIMEOUT => 8,
    CURLOPT_TIMEOUT => 0,
    CURLOPT_HTTP_VERSION => CURL_HTTP_VERSION_1_1,
    CURLOPT_HTTPHEADER => [
        'Icy-MetaData: 1',
        'User-Agent: Sukadio/1.0',
        'Accept: audio/aac,audio/mpeg,audio/*;q=0.9,*/*;q=0.1',
        'Connection: keep-alive',
    ],
    CURLOPT_HEADERFUNCTION => function($ch, $header) use (&$sentHeaders) {
        $line = trim($header);
        if ($line === '') return strlen($header);

        if (stripos($line, 'HTTP/') === 0) {
            return strlen($header);
        }

        [$name, $value] = array_pad(explode(':', $line, 2), 2, '');
        $name = strtolower(trim($name));
        $value = trim($value);

        if ($name === 'content-type' && $value !== '') {
            header('Content-Type: ' . $value);
            $sentHeaders = true;
        } elseif ($name === 'icy-br' && $value !== '') {
            header('icy-br: ' . $value);
        } elseif ($name === 'icy-genre' && $value !== '') {
            header('icy-genre: ' . $value);
        } elseif ($name === 'icy-name' && $value !== '') {
            header('icy-name: ' . $value);
        } elseif ($name === 'icy-metaint' && $value !== '') {
            header('icy-metaint: ' . $value);
        } elseif ($name === 'icy-description' && $value !== '') {
            header('icy-description: ' . $value);
        }

        return strlen($header);
    },
    CURLOPT_WRITEFUNCTION => function($ch, $chunk) {
        echo $chunk;
        if (function_exists('ob_flush')) @ob_flush();
        flush();
        return strlen($chunk);
    },
]);

header('Cache-Control: no-cache, no-store, must-revalidate');
header('Pragma: no-cache');
header('Expires: 0');
header('X-Accel-Buffering: no');
header('Access-Control-Allow-Origin: *');

$result = curl_exec($ch);
$error = curl_error($ch);
$httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
$contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
curl_close($ch);

if ($result === false && connection_status() !== CONNECTION_NORMAL) {
    exit;
}

if ($error !== '' || $httpCode >= 400 || $httpCode === 0) {
    if (!headers_sent()) {
        http_response_code($httpCode >= 400 ? $httpCode : 502);
        header('Content-Type: text/plain; charset=utf-8');
        echo 'Stream proxy error';
    }
    exit;
}

if (!$sentHeaders && $contentType !== '') {
    header('Content-Type: ' . $contentType);
}

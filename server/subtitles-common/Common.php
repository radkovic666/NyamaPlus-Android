<?php
declare(strict_types=1);
namespace NyamaSubtitles;

final class ApiError extends \RuntimeException {
    public function __construct(public int $status, public string $reason) { parent::__construct($reason); }
}
function fail(int $status, string $reason): never { throw new ApiError($status, $reason); }
function label(string $text): string {
    $text = preg_replace('/[\p{C}<>]+/u', ' ', $text) ?? '';
    return mb_substr(trim(preg_replace('/\s+/u', ' ', $text) ?? ''), 0, 180);
}
function identity(array $input): array {
    $id = $input['imdb_id'] ?? '';
    if (!is_string($id) || !preg_match('/^tt[0-9]{7,10}$/D', $id) || ($input['language'] ?? '') !== 'bg') fail(400, 'invalid_input');
    $out = ['imdb_id' => $id, 'language' => 'bg'];
    if (array_key_exists('season', $input) || array_key_exists('episode', $input)) {
        foreach (['season' => [0,999], 'episode' => [1,9999]] as $key => [$min,$max]) {
            $raw = $input[$key] ?? null;
            if (!(is_int($raw) || (is_string($raw) && preg_match('/^[0-9]{1,4}$/D', $raw)))
                || (int)$raw < $min || (int)$raw > $max) fail(400, 'invalid_input');
            $out[$key] = (int)$raw;
        }
    }
    return $out;
}
function fileId(mixed $id): int {
    if (!is_int($id) || $id < 1 || $id > 9007199254740991) fail(400, 'invalid_input');
    return $id;
}
function offset(mixed $raw): float {
    if (!(is_int($raw) || is_float($raw)) || !is_finite((float)$raw) || $raw < -10 || $raw > 10 || $raw*2 != round($raw*2)) fail(400, 'invalid_input');
    return (float)$raw;
}
function stamp(string $text): int {
    if (!preg_match('/^(?:(\d{1,2}):)?([0-5]\d):([0-5]\d)[,.](\d{3})$/D', $text, $m)) fail(422, 'invalid_subtitle');
    $n = ((int)($m[1] ?: 0)*3600 + (int)$m[2]*60 + (int)$m[3])*1000 + (int)$m[4];
    if ($n > 259200000) fail(422, 'invalid_subtitle');
    return $n;
}
/** Normalize to inert UTF-8 VTT. Original times are retained for every later offset. */
function parseSubtitle(string $bytes): array {
    if ($bytes === '' || strlen($bytes) > 1048576) fail(422, 'invalid_subtitle');
    if (str_starts_with($bytes, "\xFF\xFE")) $bytes = mb_convert_encoding(substr($bytes,2), 'UTF-8', 'UTF-16LE');
    elseif (str_starts_with($bytes, "\xFE\xFF")) $bytes = mb_convert_encoding(substr($bytes,2), 'UTF-8', 'UTF-16BE');
    elseif (!mb_check_encoding($bytes, 'UTF-8')) $bytes = mb_convert_encoding($bytes, 'UTF-8', 'Windows-1251');
    $bytes = preg_replace('/^\xEF\xBB\xBF/', '', $bytes);
    $blocks = preg_split('/\n[\t ]*\n/', str_replace(["\r\n","\r"], "\n", trim($bytes)));
    $cues = []; $last = -1;
    foreach ($blocks as $block) {
        $lines = explode("\n", trim($block));
        if (preg_match('/^(WEBVTT|NOTE|STYLE|REGION)(\s|$)/', $lines[0])) continue;
        $index = str_contains($lines[0], '-->') ? 0 : 1;
        if (!isset($lines[$index]) || !preg_match('/^(\S+)\s+-->\s+(\S+)(?:\s+.*)?$/D', $lines[$index], $m)) fail(422, 'invalid_subtitle');
        $start = stamp($m[1]); $end = stamp($m[2]);
        if ($start < $last || $end <= $start) fail(422, 'invalid_subtitle');
        $last = $start;
        $text = implode("\n", array_slice($lines, $index+1));
        $text = html_entity_decode(strip_tags($text), ENT_QUOTES | ENT_HTML5, 'UTF-8');
        $text = trim(preg_replace(['/\{\\\\[^}]*\}/u', '/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F\x{202A}-\x{202E}\x{2066}-\x{2069}]/u'], '', $text) ?? '');
        if ($text === '' || mb_strlen($text) > 8192 || count($cues) >= 6000) fail(422, 'invalid_subtitle');
        $cues[] = [$start, $end, $text];
    }
    if (!$cues) fail(422, 'invalid_subtitle');
    return $cues;
}
function timecode(int $ms): string { return sprintf('%02d:%02d:%02d.%03d', intdiv($ms,3600000), intdiv($ms,60000)%60, intdiv($ms,1000)%60, $ms%1000); }
function shiftSubtitle(array $cues, float $offset): string {
    $out = "WEBVTT\n\n"; $delta = (int)round($offset*1000);
    foreach ($cues as [$start,$end,$text]) {
        if ($end+$delta <= 0) continue;
        $out .= timecode(max(0,$start+$delta)).' --> '.timecode($end+$delta)."\n"
            .htmlspecialchars($text, ENT_NOQUOTES | ENT_SUBSTITUTE, 'UTF-8')."\n\n";
        if (strlen($out) > 1048576) fail(422, 'invalid_subtitle');
    }
    return $out;
}
function publicIp(string $ip): bool {
    if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) return false;
    $binary = inet_pton($ip);
    if (strlen($binary) === 16) {
        // Global unicast only; exclude documentation/special-purpose allocation.
        return (ord($binary[0]) & 0xe0) === 0x20 && substr($binary,0,4)!==hex2bin('20010db8')
            && !str_starts_with(strtolower($ip),'2002:') && !(ord($binary[0])===0x20 && ord($binary[1])===0x01 && ord($binary[2])<2);
    }
    $n = unpack('N', $binary)[1];
    foreach (['0.0.0.0/8','100.64.0.0/10','192.0.0.0/24','192.0.2.0/24','198.18.0.0/15','198.51.100.0/24','203.0.113.0/24','224.0.0.0/4','240.0.0.0/4'] as $cidr) {
        [$net,$bits] = explode('/',$cidr); $mask = (0xffffffff << (32-(int)$bits)) & 0xffffffff;
        if (($n & $mask) === (ip2long($net) & $mask)) return false;
    }
    return true;
}
function allowedUrl(string $url, array $hosts): array {
    $u = parse_url($url);
    if (!$u || strlen($url)>4096 || ($u['scheme']??'') !== 'https' || isset($u['user']) || isset($u['pass'])
        || isset($u['fragment']) || (isset($u['port']) && $u['port']!==443)
        || !in_array($u['host']??'', $hosts, true) || !preg_match('/^[a-z0-9.-]+$/D',$u['host'])
        || filter_var($u['host'],FILTER_VALIDATE_IP) || preg_match('/[\x00-\x20\x7f\\\\]/',$url)) fail(502,'unsafe_upstream');
    return $u;
}
/** HTTPS, no redirects, exact allowlist, public DNS pinned for this transfer, bounded bytes. */
function http(string $method, string $url, array $headers, ?string $body, int $limit, array $hosts): array {
    [$status,$data] = httpsRequest($method,$url,$headers,$body,$limit,$hosts);
    if ($status===429 || $status===406) fail(429,'rate_limited');
    if ($status!==200) fail(503,'upstream_unavailable');
    return [$status,$data];
}
function httpsRequest(string $method, string $url, array $headers, ?string $body, int $limit, array $hosts): array {
    $u = allowedUrl($url,$hosts);
    if (!extension_loaded('curl')) fail(503,'unconfigured');
    // A forward proxy would perform its own DNS, defeating pinning. Do not silently bypass it.
    foreach (['HTTPS_PROXY','https_proxy','ALL_PROXY','all_proxy'] as $name) if (getenv($name)) fail(503,'proxy_requires_review');
    $dns = dns_get_record($u['host'], DNS_A | DNS_AAAA); $ips = [];
    foreach ($dns ?: [] as $record) {
        $ip = $record['ip'] ?? $record['ipv6'] ?? '';
        if (!publicIp($ip)) fail(502,'unsafe_upstream');
        $ips[] = $ip;
    }
    if (!$ips) fail(503,'upstream_unavailable');
    $ip = str_contains($ips[0],':') ? '['.$ips[0].']' : $ips[0];
    $c = curl_init($url); $data = ''; $responseHeaders = []; $headerBytes = 0;
    curl_setopt_array($c, [CURLOPT_CUSTOMREQUEST=>$method,CURLOPT_HTTPHEADER=>$headers,
        CURLOPT_FOLLOWLOCATION=>false,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_CONNECTTIMEOUT=>8,CURLOPT_TIMEOUT=>20,
        CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_RESOLVE=>[$u['host'].':443:'.$ip],
        CURLOPT_HEADERFUNCTION=>static function($c,string $line) use (&$responseHeaders,&$headerBytes): int {
            $headerBytes+=strlen($line);if ($headerBytes>16384) return 0;
            $p=strpos($line,':');if ($p!==false) {
                $name=strtolower(trim(substr($line,0,$p)));
                if (preg_match('/^(content-type|retry-after|location|(?:x-)?ratelimit-[a-z-]+)$/D',$name)) $responseHeaders[$name]=trim(substr($line,$p+1));
            }
            return strlen($line);
        },
        CURLOPT_WRITEFUNCTION=>static function($c,string $chunk) use (&$data,$limit): int {
            if (strlen($data)+strlen($chunk)>$limit) return 0; $data.=$chunk; return strlen($chunk);
        }]);
    if ($body !== null) curl_setopt($c,CURLOPT_POSTFIELDS,$body);
    try {
        if (curl_exec($c) === false) fail(503,'upstream_unavailable');
        $status = curl_getinfo($c,CURLINFO_RESPONSE_CODE);
        return [$status,$data,$responseHeaders];
    } finally { curl_close($c); }
}

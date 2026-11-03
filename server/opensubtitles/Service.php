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
    $c = curl_init($url); $data = '';
    curl_setopt_array($c, [CURLOPT_CUSTOMREQUEST=>$method,CURLOPT_HTTPHEADER=>$headers,
        CURLOPT_FOLLOWLOCATION=>false,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_CONNECTTIMEOUT=>8,CURLOPT_TIMEOUT=>20,
        CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_RESOLVE=>[$u['host'].':443:'.$ip],
        CURLOPT_WRITEFUNCTION=>static function($c,string $chunk) use (&$data,$limit): int {
            if (strlen($data)+strlen($chunk)>$limit) return 0; $data.=$chunk; return strlen($chunk);
        }]);
    if ($body !== null) curl_setopt($c,CURLOPT_POSTFIELDS,$body);
    try {
        if (curl_exec($c) === false) fail(503,'upstream_unavailable');
        $status = curl_getinfo($c,CURLINFO_RESPONSE_CODE);
        if ($status === 429 || $status === 406) fail(429,'rate_limited');
        if ($status !== 200) fail(503,'upstream_unavailable');
        return [$status,$data];
    } finally { curl_close($c); }
}

final class Service {
    private \PDO $db;
    private \Closure $transport;
    private \Closure $clock;
    public function __construct(private array $config, ?callable $transport=null, ?callable $clock=null) {
        foreach (['secret','api_key','os_token','user_agent','public_base','storage','download_hosts'] as $key)
            if (empty($config[$key])) fail(503,'unconfigured');
        if (strlen($config['secret'])<32 || !preg_match('~^https://nyama\.fun/[a-zA-Z0-9_/-]+/subtitles$~D',$config['public_base'])) fail(503,'unconfigured');
        foreach (['secret','api_key','os_token','user_agent'] as $key) if (preg_match('/[\r\n\x00]/',$config[$key])) fail(503,'unconfigured');
        $this->transport = \Closure::fromCallable($transport ?? __NAMESPACE__.'\\http');
        $this->clock = \Closure::fromCallable($clock ?? 'time');
        $dir = $config['storage'];
        if (!is_dir($dir) && !mkdir($dir,0700,true)) fail(503,'storage_unavailable');
        $this->db = new \PDO('sqlite:'.$dir.'/subtitles.sqlite',null,null,[\PDO::ATTR_ERRMODE=>\PDO::ERRMODE_EXCEPTION]);
        chmod($dir.'/subtitles.sqlite',0600);
        $this->db->exec('PRAGMA busy_timeout=5000');
        $this->db->exec('CREATE TABLE IF NOT EXISTS cache(k TEXT PRIMARY KEY, body TEXT NOT NULL, expires INTEGER NOT NULL)');
        $this->db->exec('CREATE TABLE IF NOT EXISTS quota(k TEXT PRIMARY KEY,n INTEGER NOT NULL,expires INTEGER NOT NULL)');
    }
    private function now(): int { return ($this->clock)(); }
    private function key(string $user, array $media, string $suffix=''): string {
        return hash_hmac('sha256',$user."\n".json_encode($media)."\n".$suffix,$this->config['secret']);
    }
    private function get(string $key): ?array {
        $q=$this->db->prepare('SELECT body FROM cache WHERE k=? AND expires>?');$q->execute([$key,$this->now()]);
        $raw=$q->fetchColumn();return $raw===false ? null : json_decode($raw,true,32,JSON_THROW_ON_ERROR);
    }
    private function put(string $key, array $data, int $ttl): void {
        $q=$this->db->prepare('INSERT OR REPLACE INTO cache VALUES(?,?,?)');$q->execute([$key,json_encode($data,JSON_THROW_ON_ERROR),$this->now()+$ttl]);
        $this->db->prepare('DELETE FROM cache WHERE expires<=?')->execute([$this->now()]);
        // Bound logical cache size to 64 MiB/1000 records; SQLite reuses freed pages.
        while ((int)$this->db->query('SELECT COALESCE(SUM(length(body)),0) FROM cache')->fetchColumn()>67108864
            || (int)$this->db->query('SELECT COUNT(*) FROM cache')->fetchColumn()>1000)
            $this->db->exec('DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY expires ASC LIMIT 1)');
    }
    private function limit(string $bucket, int $maximum, int $window): void {
        $now=$this->now();$key=hash_hmac('sha256',$bucket.':'.intdiv($now,$window),$this->config['secret']);
        $this->db->exec('BEGIN IMMEDIATE');
        try {
            $this->db->prepare('DELETE FROM quota WHERE expires<=?')->execute([$now]);
            $q=$this->db->prepare('SELECT n FROM quota WHERE k=?');$q->execute([$key]);$n=(int)$q->fetchColumn();
            if ($n >= $maximum) fail(429,'rate_limited');
            $this->db->prepare('INSERT OR REPLACE INTO quota VALUES(?,?,?)')->execute([$key,$n+1,$now+$window]);
            $this->db->exec('COMMIT');
        } catch (\Throwable $e) { $this->db->exec('ROLLBACK');throw $e; }
    }
    private function official(string $method,string $route,?array $body=null): array {
        $headers=['Api-Key: '.$this->config['api_key'],'User-Agent: '.$this->config['user_agent'],
            'Accept: application/json','Content-Type: application/json','Authorization: Bearer '.$this->config['os_token']];
        [, $data]=($this->transport)($method,'https://api.opensubtitles.com/api/v1/'.$route,$headers,
            $body===null?null:json_encode($body,JSON_THROW_ON_ERROR),1048576,['api.opensubtitles.com']);
        try { $decoded=json_decode($data,true,32,JSON_THROW_ON_ERROR); if (!is_array($decoded)) fail(502,'invalid_response');return $decoded; } catch (\Throwable $e) { fail(502,'invalid_response'); }
    }
    public function search(string $user,array $input): array {
        $media=identity($input);
        $this->limit('search:'.$user,15,60);$this->limit('search:global',120,60);
        $key=$this->key($user,$media,'search');$cached=$this->get($key);
        if ($cached && $cached['searched_at']>$this->now()-300) return ['ok'=>true,'media'=>$media,'results'=>$cached['results']];
        $query=['languages'=>'bg','order_by'=>'download_count','order_direction'=>'desc'];
        if (isset($media['season'])) {
            $query+=['type'=>'episode','parent_imdb_id'=>substr($media['imdb_id'],2),'season_number'=>$media['season'],'episode_number'=>$media['episode']];
        } else $query+=['type'=>'movie','imdb_id'=>substr($media['imdb_id'],2)];
        $data=$this->official('GET','subtitles?'.http_build_query($query,'','&',PHP_QUERY_RFC3986));
        $results=self::filter($data,$media);
        $this->put($key,['searched_at'=>$this->now(),'results'=>$results],1800);
        return ['ok'=>true,'media'=>$media,'results'=>$results];
    }
    public static function filter(array $data,array $media): array {
        $rows=$data['data']??null;if (!is_array($rows)) fail(502,'invalid_response');
        $out=[];
        foreach (array_slice($rows,0,100) as $row) {
            $a=$row['attributes']??[];$f=$a['feature_details']??[];
            if (($a['language']??'')!=='bg') continue;
            if (isset($media['season'])) {
                if (strtolower($f['feature_type']??'')!=='episode' || !isset($f['parent_imdb_id'],$f['season_number'],$f['episode_number'])
                    || (string)$f['parent_imdb_id']!==ltrim(substr($media['imdb_id'],2),'0')
                    || !preg_match('/^[0-9]{1,3}$/D',(string)$f['season_number']) || !preg_match('/^[0-9]{1,4}$/D',(string)$f['episode_number'])
                    || (int)$f['season_number']!==$media['season'] || (int)$f['episode_number']!==$media['episode']) continue;
            } elseif (strtolower($f['feature_type']??'')!=='movie' || (string)($f['imdb_id']??'')!==ltrim(substr($media['imdb_id'],2),'0')) continue;
            foreach (array_slice($a['files']??[],0,20) as $file) {
                $id=$file['file_id']??null;if (!is_int($id)||$id<1||$id>9007199254740991||isset($out[$id])) continue;
                $release=label((string)($a['release']??$file['file_name']??''));if ($release==='') continue;
                $r=['file_id'=>$id,'release'=>$release,'language'=>'bg'];
                foreach (['fps'=>'fps','ratings'=>'rating','download_count'=>'downloads'] as $source=>$target)
                    if (isset($a[$source])&&is_numeric($a[$source])&&is_finite((float)$a[$source])&&(float)$a[$source]>=0) $r[$target]=0+$a[$source];
                $ext=strtolower(pathinfo((string)($file['file_name']??''),PATHINFO_EXTENSION));if (in_array($ext,['srt','vtt'],true)) $r['format']=$ext;
                $out[$id]=$r;if (count($out)>=100) break 2;
            }
        }
        $out=array_values($out);usort($out,fn($a,$b)=>($b['downloads']??0)<=>($a['downloads']??0));return $out;
    }
    public function select(string $user,array $input): array {
        $media=identity($input);$id=fileId($input['file_id']??null);$shift=offset($input['offset_seconds']??0);
        $this->limit('select:'.$user,10,60);$this->limit('select:global',100,60);
        // Membership comes from an authenticated, exact-media search, never a client-supplied URL.
        $key=$this->key($user,$media,'download:'.$id);$cached=$this->get($key);
        if (!$cached) {
            $search=$this->get($this->key($user,$media,'search'));$release=null;
            foreach ($search['results']??[] as $r) if ($r['file_id']===$id) $release=$r;
            if (!$release) fail(409,'search_required');
            // Single flight across PHP workers: offsets reuse the same authorized original.
            $lock=fopen($this->config['storage'].'/download.lock','c');if (!$lock || !flock($lock,LOCK_EX|LOCK_NB)) fail(429,'busy');
            try {
                $cached=$this->get($key);
                if (!$cached) {
                    $this->limit('download:'.$user,(int)($this->config['downloads_per_user']??10),86400);
                    $this->limit('download:global',(int)($this->config['downloads_global']??100),86400);
                    $response=$this->official('POST','download',['file_id'=>$id,'sub_format'=>'srt']);
                    $url=$response['link']??'';if (!is_string($url)) fail(502,'invalid_response');
                    allowedUrl($url,$this->config['download_hosts']);
                    [, $bytes]=($this->transport)('GET',$url,[],null,1048576,$this->config['download_hosts']);
                    $cached=['release'=>$release['release'],'cues'=>parseSubtitle($bytes)];
                    $this->put($key,$cached,1800);
                }
            } finally { flock($lock,LOCK_UN);fclose($lock); }
        }
        $vtt=shiftSubtitle($cached['cues'],$shift);$opaque=bin2hex(random_bytes(32));$expires=$this->now()+900;
        $this->put('file:'.$opaque,['vtt'=>$vtt,'expires'=>$expires],900);
        $sig=hash_hmac('sha256',"v1\n$opaque\n$expires",$this->config['secret']);
        return ['ok'=>true,'media'=>$media,'file_id'=>$id,'offset_seconds'=>$shift,'release'=>$cached['release'],'expires'=>$expires,
            'url'=>$this->config['public_base'].'/file/'.$opaque.'?'.http_build_query(['expires'=>$expires,'sig'=>$sig],'','&',PHP_QUERY_RFC3986)];
    }
    public function file(string $opaque,mixed $expires,mixed $sig): string {
        if (!preg_match('/^[a-f0-9]{64}$/D',$opaque)||!is_string($expires)||!preg_match('/^[0-9]{10}$/D',$expires)
            || !is_string($sig)||!preg_match('/^[a-f0-9]{64}$/D',$sig)) fail(403,'invalid_signature');
        $now=$this->now();if ((int)$expires<=$now || (int)$expires>$now+900
            || !hash_equals(hash_hmac('sha256',"v1\n$opaque\n$expires",$this->config['secret']),$sig)) fail(403,'invalid_signature');
        $cached=$this->get('file:'.$opaque);
        if (!$cached || $cached['expires']!==(int)$expires) fail(404,'expired');return $cached['vtt'];
    }
}

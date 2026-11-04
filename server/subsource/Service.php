<?php
declare(strict_types=1);
namespace NyamaSubSource;
require_once dirname(__DIR__).'/subtitles-common/Store.php';
require_once __DIR__.'/Archive.php';
use function NyamaSubtitles\{fail,identity,fileId,offset,label,shiftSubtitle,allowedUrl};

/** Provider schema is strict. Unknown/missing identities never become loose title matches. */
final class Service extends \NyamaSubtitles\Store {
    private \Closure $transport;
    public function __construct(array $config,?callable $transport=null,?callable $clock=null) {
        parent::__construct($config,$clock);
        if(empty($config['api_key'])||preg_match('/[\r\n\x00]/',$config['api_key']))fail(503,'unconfigured');
        if(empty($config['terms_approved']))fail(503,'terms_not_confirmed');
        $this->db->exec('CREATE TABLE IF NOT EXISTS upstream_events(at INTEGER NOT NULL)');
        $this->transport=\Closure::fromCallable($transport??'NyamaSubtitles\\httpsRequest');
    }
    private function upstreamBudget(): void {
        $now=$this->now();$this->db->exec('BEGIN IMMEDIATE');
        try {
            $this->db->prepare('DELETE FROM upstream_events WHERE at<=?')->execute([$now-86400]);
            foreach([60=>60,3600=>1800,86400=>7200]as $window=>$limit) {
                $q=$this->db->prepare('SELECT COUNT(*) FROM upstream_events WHERE at>?');$q->execute([$now-$window]);
                if((int)$q->fetchColumn()>=$limit)fail(429,'rate_limited');
            }
            $this->db->prepare('INSERT INTO upstream_events VALUES(?)')->execute([$now]);$this->db->exec('COMMIT');
        }catch(\Throwable $e){$this->db->exec('ROLLBACK');throw $e;}
    }
    private function upstream(string $path,bool $archive=false): string {
        $blocked=$this->get('upstream:blocked');if($blocked)fail(429,'rate_limited');

        $url='https://api.subsource.net/api/v1/'.$path;$hosts=['api.subsource.net'];
        for($redirect=0;$redirect<=2;$redirect++) {
            allowedUrl($url,$hosts);
            $headers=['Accept: '.($archive?'application/zip, application/octet-stream':'application/json')];
            if(parse_url($url,PHP_URL_HOST)==='api.subsource.net') { $this->upstreamBudget(); $headers[]='X-API-Key: '.$this->config['api_key']; }
            [$status,$body,$response]=($this->transport)('GET',$url,$headers,null,$archive?8388608:1048576,$hosts);
            if($status===429) {
                $delay=60;$retry=$response['retry-after']??'';
                if(ctype_digit($retry))$delay=max(1,min(3600,(int)$retry));
                elseif($retry!==''&&($date=strtotime($retry))!==false)$delay=max(1,min(3600,$date-$this->now()));
                $this->put('upstream:blocked',['blocked'=>true],$delay);fail(429,'rate_limited');
            }
            if(in_array($status,[301,302,303,307,308],true)&&$archive&&$redirect<2) {
                $next=$response['location']??'';$hosts=array_unique(array_merge(['api.subsource.net'],$this->config['download_hosts']??[]));
                allowedUrl($next,$hosts);$url=$next;continue;
            }
            if($status!==200)fail(in_array($status,[400,401,403,404],true)?$status:503,
                in_array($status,[401,403],true)?'upstream_unauthorized':($status===404?'not_found':'upstream_unavailable'));
            $remaining=$response['x-ratelimit-remaining']??$response['ratelimit-remaining']??null;
            if($remaining==='0') {
                $reset=$response['x-ratelimit-reset']??$response['ratelimit-reset']??null;
                $delay=ctype_digit((string)$reset)?((int)$reset>$this->now()?(int)$reset-$this->now():(int)$reset):60;
                $this->put('upstream:blocked',['blocked'=>true],max(1,min(3600,$delay)));
            }
            $type=strtolower(trim(explode(';',$response['content-type']??'')[0]));
            if($archive?!in_array($type,['application/zip','application/x-zip-compressed','application/octet-stream'],true)
                :!in_array($type,['application/json','application/problem+json'],true))fail(502,'schema_changed');
            return $body;
        }
        fail(502,'unsafe_upstream');
    }
    private function json(string $path): array {
        try{$j=json_decode($this->upstream($path),true,32,JSON_THROW_ON_ERROR);}catch(\JsonException $e){fail(502,'schema_changed');}
        if(!is_array($j)||!array_key_exists('data',$j))fail(502,'schema_changed');return $j;
    }
    static function sameMovie(array $movie,array $media): bool {
        if(($movie['imdbId']??'')!==$media['imdb_id']||!is_int($movie['movieId']??null)||$movie['movieId']<1)return false;
        return isset($media['season']) ? in_array($movie['type']??'',['tvseries','series'],true) && ($movie['season']??null)===$media['season']
            : ($movie['type']??'')==='movie';
    }
    private function movie(string $user,array $media): int {
        $key=$this->key($user,$media,'movie');$cached=$this->get($key);if($cached)return $cached['id'];
        $query=['searchType'=>'imdb','imdb'=>$media['imdb_id']];
        if(isset($media['season']))$query['season']=$media['season'];
        $data=$this->json('movies/search?'.http_build_query($query));
        if(!is_array($data['data'])||!array_is_list($data['data'])||count($data['data'])>100)fail(502,'schema_changed');
        $ids=[];foreach($data['data']as $movie) {
            if(!is_array($movie)||!isset($movie['imdbId'],$movie['movieId'],$movie['type'])
                ||!is_int($movie['movieId'])||!is_string($movie['imdbId'])||!is_string($movie['type']))fail(502,'schema_changed');
            if(self::sameMovie($movie,$media))$ids[$movie['movieId']]=true;
        }
        if(!$ids)fail(404,'not_found');if(count($ids)!==1)fail(409,'movie_ambiguous');$id=(int)array_key_first($ids);
        $details=$this->json('movies/'.$id)['data'];
        if(!is_array($details)||!self::sameMovie($details,$media)||$details['movieId']!==$id)fail(502,'schema_changed');
        $this->put($key,['id'=>$id],1800);return $id;
    }
    static function release(array $row,array $media,int $movie): ?array {
        if(($row['movieId']??null)!==$movie||strtolower($row['language']??'')!=='bulgarian'
            ||!is_int($row['subtitleId']??null)||$row['subtitleId']<1||!is_array($row['releaseInfo']??null))return null;
        $labels=[];foreach($row['releaseInfo']as $text){if(!is_string($text))return null;$labels[]=label($text);}
        $release=label(implode(' · ',$labels));if($release==='')return null;
        if(isset($media['season'])) {
            $numbers=episodeNumbers(implode(' ',$labels));
            if($numbers && $numbers!==[[$media['season'],$media['episode']]])return null;
            preg_match_all('/(?<![a-z0-9])s(\d{1,3})(?![0-9e])/i',implode(' ',$labels),$seasons);
            foreach($seasons[1]as $season)if((int)$season!==$media['season'])return null;
        }
        $r=['subtitle_id'=>$row['subtitleId'],'release'=>$release,'language'=>'bg'];
        if(isset($row['downloads'])&&is_int($row['downloads'])&&$row['downloads']>=0)$r['downloads']=$row['downloads'];
        if(isset($row['framerate'])&&is_numeric($row['framerate'])&&(float)$row['framerate']>0&&(float)$row['framerate']<1000)$r['fps']=(float)$row['framerate'];
        // Vote counts are not a numeric star rating; never invent one from good/bad/total.
        if(isset($row['uploaderId'])&&is_int($row['uploaderId'])&&$row['uploaderId']>0)$r['uploader']='#'.$row['uploaderId'];
        return $r;
    }
    public function search(string $user,array $input): array {
        $media=identity($input);$page=$input['page']??1;
        if(!(is_int($page)||(is_string($page)&&ctype_digit($page)))||(int)$page<1||(int)$page>20)fail(400,'invalid_input');$page=(int)$page;
        $this->limit('search:'.$user,15,60);$this->limit('search:global',60,60);
        $key=$this->key($user,$media,'search:'.$page);$cached=$this->get($key);if($cached)return $cached;
        try { $movie=$this->movie($user,$media); }
        catch (\NyamaSubtitles\ApiError $e) {
            if($e->status!==404)throw $e;
            return ['ok'=>true,'provider'=>'subsource','media'=>$media,'page'=>$page,'next_page'=>0,'results'=>[]];
        }
        $reply=$this->json('subtitles?'.http_build_query(['movieId'=>$movie,'language'=>'bulgarian','page'=>$page,'limit'=>20,'sort'=>'popular']));
        $rows=$reply['data'];$pagination=$reply['pagination']??null;
        if(!is_array($rows)||!array_is_list($rows)||count($rows)>20||!is_array($pagination)
            ||($pagination['page']??null)!==$page||!is_int($pagination['pages']??null)||$pagination['pages']<0
            ||($pagination['limit']??null)!==20)fail(502,'schema_changed');
        $result=[];foreach($rows as $row) {
            if(!is_array($row)||!is_int($row['movieId']??null)||!is_int($row['subtitleId']??null)
                ||!is_string($row['language']??null)||!is_array($row['releaseInfo']??null))fail(502,'schema_changed');
            $r=self::release($row,$media,$movie);if(!$r)continue;
            $result[$r['subtitle_id']]=$r;
            $this->put($this->key($user,$media,'allowed:'.$r['subtitle_id']),['movie'=>$movie,'release'=>$r],1800);
        }
        $out=['ok'=>true,'provider'=>'subsource','media'=>$media,'page'=>$page,'next_page'=>$page<min(20,$pagination['pages'])?$page+1:0,'results'=>array_values($result)];
        $this->put($key,$out,300);return $out;
    }
    public function select(string $user,array $input): array {
        $media=identity($input);$id=fileId($input['subtitle_id']??null);$shift=offset($input['offset_seconds']??0);
        $this->limit('select:'.$user,10,60);$this->limit('select:global',100,60);
        $key=$this->key($user,$media,'original:'.$id);$cached=$this->get($key);
        if(!$cached) {
            $allowed=$this->get($this->key($user,$media,'allowed:'.$id));if(!$allowed)fail(409,'search_required');
            $lock=fopen($this->config['storage'].'/download.lock','c');if(!$lock||!flock($lock,LOCK_EX|LOCK_NB))fail(429,'busy');
            try {
                $cached=$this->get($key);
                if(!$cached) {
                    $row=$this->json('subtitles/'.$id)['data'];
                    $release=is_array($row)?self::release($row,$media,$allowed['movie']):null;
                    if(!$release||$release['subtitle_id']!==$id)fail(409,'episode_mismatch');
                    $zip=$this->upstream('subtitles/'.$id.'/download',true);$cues=archiveCues($zip,$media);
                    $cached=['release'=>$release['release'],'cues'=>$cues];$this->put($key,$cached,1800);
                }
            } finally {flock($lock,LOCK_UN);fclose($lock);}
        }
        $opaque=bin2hex(random_bytes(32));$expires=$this->now()+900;$vtt=shiftSubtitle($cached['cues'],$shift);
        $this->put('file:'.$opaque,['vtt'=>$vtt,'expires'=>$expires],900);
        $sig=hash_hmac('sha256',"subsource-v1\n$opaque\n$expires",$this->config['secret']);
        return ['ok'=>true,'provider'=>'subsource','media'=>$media,'subtitle_id'=>$id,'offset_seconds'=>$shift,'release'=>$cached['release'],'expires'=>$expires,
            'url'=>$this->config['public_base'].'/file/'.$opaque.'.vtt?'.http_build_query(['expires'=>$expires,'sig'=>$sig])];
    }
    public function file(string $opaque,mixed $expires,mixed $sig): string {
        if(!preg_match('/^[a-f0-9]{64}$/D',$opaque)||!is_string($expires)||!ctype_digit($expires)||strlen($expires)!==10
            ||!is_string($sig)||!preg_match('/^[a-f0-9]{64}$/D',$sig)||(int)$expires<=$this->now()||(int)$expires>$this->now()+900
            ||!hash_equals(hash_hmac('sha256',"subsource-v1\n$opaque\n$expires",$this->config['secret']),$sig))fail(403,'invalid_signature');
        $cached=$this->get('file:'.$opaque);if(!$cached||$cached['expires']!==(int)$expires)fail(404,'expired');return $cached['vtt'];
    }
}

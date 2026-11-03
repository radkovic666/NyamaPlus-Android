<?php
declare(strict_types=1);
require dirname(__DIR__).'/Service.php';
use NyamaSubtitles\Service;
use NyamaSubtitles\ApiError;
use function NyamaSubtitles\{identity,offset,parseSubtitle,shiftSubtitle,allowedUrl,publicIp};
$count=0;
function check(bool $ok,string $message): void { global $count;$count++;if (!$ok) throw new RuntimeException($message); }
function rejected(callable $call,int $status): void {
    try { $call(); } catch (ApiError $e) { check($e->status===$status,'Expected '.$status.', got '.$e->status.' '.$e->reason);return; }
    throw new RuntimeException('Expected rejection '.$status);
}
$dir=sys_get_temp_dir().'/nyama-subtitles-'.bin2hex(random_bytes(6));mkdir($dir,0700);
$now=1800000000;$calls=[];
$movie=['imdb_id'=>'tt0111161','language'=>'bg'];$episode=['imdb_id'=>'tt0944947','language'=>'bg','season'=>2,'episode'=>3];
function row(int $id,string $kind='Movie',int $season=2,int $episode=3,string $language='bg'): array {
    return ['attributes'=>['language'=>$language,'release'=>'Actual BG Release '.$id,'fps'=>23.976,'ratings'=>8.2,'download_count'=>100,
        'feature_details'=>['feature_type'=>$kind,'imdb_id'=>$kind==='Movie'?111161:99999,'parent_imdb_id'=>944947,'season_number'=>$season,'episode_number'=>$episode],
        'files'=>[['file_id'=>$id,'file_name'=>'actual.srt']]]];
}
$config=['secret'=>str_repeat('x',32),'api_key'=>'fixture-key','os_token'=>'fixture-token','user_agent'=>'NyamaPlus test',
    'public_base'=>'https://nyama.fun/api/subtitles','storage'=>$dir,'download_hosts'=>['download.example.com']];
$transport=function(string $method,string $url,array $headers,?string $body,int $limit,array $hosts) use (&$calls): array {
    $calls[]=[$method,$url,$headers,$body,$limit,$hosts];
    if (str_contains($url,'/subtitles?')) {
        parse_str(parse_url($url,PHP_URL_QUERY),$q);
        check(($q['languages']??'')==='bg','Bulgarian filter');
        if (($q['type']??'')==='episode') {
            check($q['parent_imdb_id']==='0944947'&&$q['season_number']==='2'&&$q['episode_number']==='3','Exact episode query');
            return [200,json_encode(['data'=>[row(321,'Episode'),row(322,'Episode',2,4),row(323,'Episode',3,3),row(324,'Episode',2,3,'en')]])];
        }
        check(($q['imdb_id']??'')==='0111161','Movie IMDb');
        return [200,json_encode(['data'=>[row(123),row(123),row(124,'Movie',2,3,'en')]])];
    }
    if (str_ends_with($url,'/download')) {
        check($method==='POST','Official download method');check(json_decode($body,true)['file_id']===123,'Authorized file ID');
        check(in_array('Api-Key: fixture-key',$headers,true)&&in_array('Authorization: Bearer fixture-token',$headers,true),'Server credentials');
        return [200,json_encode(['link'=>'https://download.example.com/temporary.srt'])];
    }
    check($url==='https://download.example.com/temporary.srt'&&$headers===[],'No API credentials sent to file host');
    check($limit===1048576,'File size bound');
    return [200,"1\n00:00:01,000 --> 00:00:03,000\nЗдравей <b>свят</b>\n\n2\n00:00:04,000 --> 00:00:05,000\nSecond\n"];
};
$service=new Service($config,$transport,static function()use(&$now){return $now;});
try {
    check(identity($episode)===$episode,'Identity round trip');
    foreach (['bad','tt123','tt1234567/../../x'] as $id) rejected(fn()=>identity(['imdb_id'=>$id,'language'=>'bg']),400);
    rejected(fn()=>identity($movie+['season'=>2]),400);rejected(fn()=>identity(array_replace($episode,['episode'=>-1])),400);
    rejected(fn()=>identity(array_replace($movie,['language'=>'en'])),400);rejected(fn()=>offset(.25),400);rejected(fn()=>offset(11),400);
    $search=$service->search('account-1',$movie);
    check(count($search['results'])===1&&$search['results'][0]['file_id']===123,'Dedupe + language filtering');
    check($search['results'][0]['fps']===23.976&&$search['results'][0]['format']==='srt','Actual metadata');
    $before=count($calls);$service->search('account-1',$movie);check(count($calls)===$before,'Search cache');
    $series=$service->search('account-1',$episode);check(array_column($series['results'],'file_id')===[321],'Other episodes rejected');
    $bad=row(1,'Episode');$bad['attributes']['feature_details']['season_number']='2garbage';
    check(Service::filter(['data'=>[$bad]],$episode)===[],'Malformed upstream identity rejected');
    rejected(fn()=>$service->select('account-1',$episode+['file_id'=>123]),409);
    rejected(fn()=>$service->select('account-2',$movie+['file_id'=>123]),409);
    rejected(fn()=>$service->select('account-1',$movie+['file_id'=>999999]),409);
    rejected(fn()=>$service->select('account-1',$movie+['file_id'=>123.5]),400);
    $chosen=$service->select('account-1',$movie+['file_id'=>123,'offset_seconds'=>0]);
    check($chosen['media']===$movie,'Selected media');check($chosen['expires']===$now+900,'Short signed expiry');
    parse_str(parse_url($chosen['url'],PHP_URL_QUERY),$query);$opaque=basename(parse_url($chosen['url'],PHP_URL_PATH));
    $vtt=$service->file($opaque,$query['expires'],$query['sig']);
    check(str_starts_with($vtt,"WEBVTT\n\n")&&str_contains($vtt,'00:00:01.000 --> 00:00:03.000'),'Valid signed fetch');
    check(str_contains($vtt,'Здравей свят')&&!str_contains($vtt,'<b>'),'Unicode and plain-text captions');
    rejected(fn()=>$service->file('../'.$opaque,$query['expires'],$query['sig']),403);
    rejected(fn()=>$service->file($opaque,$query['expires'],str_repeat('0',64)),403);
    rejected(fn()=>$service->file($opaque,(string)($now+7200),$query['sig']),403);
    $before=count($calls);$shifted=$service->select('account-1',$movie+['file_id'=>123,'offset_seconds'=>.5]);
    parse_str(parse_url($shifted['url'],PHP_URL_QUERY),$q2);$id2=basename(parse_url($shifted['url'],PHP_URL_PATH));
    check(str_contains($service->file($id2,$q2['expires'],$q2['sig']),'00:00:01.500 --> 00:00:03.500'),'Shifted actual cues');
    check(count($calls)===$before,'Timing spends no new download quota');
    check($service->file($opaque,$query['expires'],$query['sig'])===$vtt,'Original cache not mutated');
    $now+=901;rejected(fn()=>$service->file($opaque,$query['expires'],$query['sig']),403);$now-=901;
    foreach (['http://download.example.com/a','https://evil.example/a','https://download.example.com@127.0.0.1/a',
        'https://download.example.com:8443/a','https://download.example.com/a#fragment','https://download.example.com/a'."\r\n".'x:y',
        'https://127.0.0.1/a'] as $url) rejected(fn()=>allowedUrl($url,['download.example.com']),502);
    check(allowedUrl('https://download.example.com/a?sig=temporary',['download.example.com'])['host']==='download.example.com','Allowed HTTPS host');
    foreach (['127.0.0.1','10.0.0.1','169.254.169.254','100.64.0.1','192.168.1.1','198.18.0.1','::1','::ffff:127.0.0.1','fd00::1','2001:db8::1','2001:0db8::1','2002:7f00:1::'] as $ip) check(!publicIp($ip),'Reject nonpublic IP '.$ip);
    check(publicIp('8.8.8.8')&&publicIp('2606:4700:4700::1111'),'Public address validation');
    rejected(fn()=>parseSubtitle(str_repeat('x',1048577)),422);rejected(fn()=>parseSubtitle('<html>not subtitles</html>'),422);
    rejected(fn()=>parseSubtitle("1\n00:00:03,000 --> 00:00:01,000\nBad"),422);
    $plain=parseSubtitle("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n&lt;script&gt; &amp; text\n");
    check(str_contains(shiftSubtitle($plain,0),'&lt;script&gt; &amp; text'),'No cue markup injection');
    check(!str_contains(shiftSubtitle($plain,-10),'-->'),'Negative ended cues omitted');
    $cp=mb_convert_encoding("1\n00:00:01,000 --> 00:00:02,000\nБългарски",'Windows-1251','UTF-8');
    check(parseSubtitle($cp)[0][2]==='Български','Windows-1251 support');
    $badConfig=$config;$badConfig['secret']='';rejected(fn()=>new Service($badConfig,$transport),503);
    $malformed=new Service($config,fn()=>[200,'not json'],fn()=>$now);rejected(fn()=>$malformed->search('new-user',$movie),502);
    $limited=new Service($config,static function(){throw new ApiError(429,'rate_limited');},fn()=>$now);
    rejected(fn()=>$limited->search('rate-user',$movie),429);
    for($i=0;$i<15;$i++) $service->search('quota-user',$movie);
    rejected(fn()=>$service->search('quota-user',$movie),429);
    // Real HTTP router with pre-populated authorized cache; no upstream requests or live secrets.
    $adapter=$dir.'/auth.php';file_put_contents($adapter,'<?php return static fn($token,$id) => $token===str_repeat("b",64) && $id===str_repeat("a",64) ? "account-1" : null;');
    $env=['NYAMA_SUBTITLE_PUBLIC_BASE'=>$config['public_base'],'NYAMA_SUBTITLE_STORAGE'=>$dir,'NYAMA_SUBTITLE_AUTH_ADAPTER'=>$adapter,
        'NYAMA_SUBTITLE_SIGNING_SECRET'=>$config['secret'],'OPENSUBTITLES_API_KEY'=>'fixture-key','OPENSUBTITLES_BEARER_TOKEN'=>'fixture-token',
        'OPENSUBTITLES_USER_AGENT'=>'NyamaPlus test','OPENSUBTITLES_DOWNLOAD_HOSTS'=>'download.example.com'];
    // Router uses real time; create cache and signed fetch using the same clock.
    $real=new Service($config,$transport);$real->search('account-1',$movie);$realSelection=$real->select('account-1',$movie+['file_id'=>123]);
    $server=proc_open([PHP_BINARY,'-S','127.0.0.1:18937',dirname(__DIR__).'/public/index.php'],[0=>['pipe','r'],1=>['file',$dir.'/http.log','a'],2=>['file',$dir.'/http.log','a']],$pipes,null,$env);
    try {
        $ready=false;
        for($i=0;$i<50;$i++){ $socket=@fsockopen('127.0.0.1',18937,$errno,$errstr,.05);if($socket){fclose($socket);$ready=true;break;}usleep(20000); }
        check($ready,'Router started');
        $fetch=static function(string $path,array $headers=[]) {
            $ctx=stream_context_create(['http'=>['header'=>implode("\r\n",$headers),'ignore_errors'=>true,'timeout'=>3]]);
            $data=file_get_contents('http://127.0.0.1:18937'.$path,false,$ctx);return [$http_response_header[0],$data];
        };
        [$status,$body]=$fetch('/api/subtitles/search?imdb_id=tt0111161&language=bg');check(str_contains($status,'401'),'Unauthenticated search rejected');
        [$status,$body]=$fetch('/api/subtitles/search?imdb_id=tt0111161&language=bg',['Authorization: Bearer '.str_repeat('c',64),'X-Nyama-Activation-ID: '.str_repeat('a',64)]);
        check(str_contains($status,'403'),'Revoked token rejected by adapter');
        [$status,$body]=$fetch('/api/subtitles/search?imdb_id=tt0111161&language=bg',['Authorization: Bearer '.str_repeat('b',64),'X-Nyama-Activation-ID: '.str_repeat('a',64)]);
        check(str_contains($status,'200')&&json_decode($body,true)['results'][0]['file_id']===123,'Authenticated cached search through router');
        $path=parse_url($realSelection['url'],PHP_URL_PATH).'?'.parse_url($realSelection['url'],PHP_URL_QUERY);
        [$status,$body]=$fetch($path);check(str_contains($status,'200')&&str_starts_with($body,'WEBVTT'),'Signed unauthenticated provider fetch through router');
        [$status,$body]=$fetch($path.'wrong');check(str_contains($status,'403'),'Router rejects altered signature');
        check(!str_contains($body,'fixture-key')&&!str_contains($body,'fixture-token'),'No secret error response');
    } finally { proc_terminate($server);proc_close($server); }
    echo "PASS: $count backend checks (mocked OpenSubtitles + local HTTP router, no live API).\n";
} finally {
    foreach (glob($dir.'/*') as $file) unlink($file);rmdir($dir);
}

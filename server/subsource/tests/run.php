<?php
declare(strict_types=1);
require dirname(__DIR__).'/Service.php';
use NyamaSubSource\Service;
use NyamaSubtitles\ApiError;
use function NyamaSubSource\{archiveCues,episodeNumbers};
use function NyamaSubtitles\{shiftSubtitle,allowedUrl};
$checks=0;
function check(bool $ok,string $why):void{global $checks;$checks++;if(!$ok)throw new RuntimeException($why);}
function reject(callable $fn,int $status):void{try{$fn();}catch(ApiError $e){check($e->status===$status,'Expected '.$status.', got '.$e->status.' '.$e->reason);return;}throw new RuntimeException('Rejection missing '.$status);}
function zipBytes(array $files,int $method=ZipArchive::CM_STORE,?callable $edit=null):string{
 $tmp=tempnam(sys_get_temp_dir(),'testzip-');$z=new ZipArchive();$z->open($tmp,ZipArchive::CREATE|ZipArchive::OVERWRITE);
 foreach($files as $name=>$bytes){$z->addFromString($name,$bytes);$z->setCompressionName($name,$method);}if($edit)$edit($z);$z->close();$bytes=file_get_contents($tmp);unlink($tmp);return $bytes;
}
function row(int $id,int $movie=11,string $release='Film.Release',string $language='bulgarian'):array{
 return ['subtitleId'=>$id,'movieId'=>$movie,'language'=>$language,'releaseInfo'=>[$release],'framerate'=>'23.976','downloads'=>123,'uploaderId'=>98,'rating'=>['good'=>3,'bad'=>1,'total'=>4]];
}
$movie=['imdb_id'=>'tt1375666','language'=>'bg'];$episode=['imdb_id'=>'tt0944947','language'=>'bg','season'=>2,'episode'=>3];
$srt="1\n00:00:01,000 --> 00:00:03,000\nЗдравей, свят!\n";
$dir=sys_get_temp_dir().'/nyamasubsource-'.bin2hex(random_bytes(6));mkdir($dir,0700);
$now=1800000000;$calls=[];
$config=['secret'=>str_repeat('fixture-signing-',3),'api_key'=>'fixture-only-key','public_base'=>'https://nyama.fun/api/subtitles','storage'=>$dir,'terms_approved'=>true];
$transport=function(string $method,string $url,array $headers,?string $body,int $limit,array $hosts)use(&$calls,$srt):array{
 $calls[]=[$method,$url,$headers];check($method==='GET','All SubSource methods GET');
 check(in_array('X-API-Key: fixture-only-key',$headers,true),'Server-only API key header');check(!str_contains($url,'fixture-only-key'),'Key absent from URL');
 $json=['content-type'=>'application/json; charset=utf-8'];$path=parse_url($url,PHP_URL_PATH);parse_str(parse_url($url,PHP_URL_QUERY)??'',$q);
 if(str_ends_with($path,'movies/search'))return [200,json_encode(['data'=>($q['imdb']==='tt1375666'?
  [['movieId'=>11,'imdbId'=>'tt1375666','type'=>'movie'],['movieId'=>12,'imdbId'=>'tt9999999','type'=>'movie']]:
  [['movieId'=>21,'imdbId'=>'tt0944947','type'=>'tvseries','season'=>2],['movieId'=>22,'imdbId'=>'tt0944947','type'=>'tvseries','season'=>3]])]),$json];
 if(preg_match('~/movies/(11|21)$~',$path,$m))return [200,json_encode(['data'=>$m[1]==='11'?['movieId'=>11,'imdbId'=>'tt1375666','type'=>'movie']:['movieId'=>21,'imdbId'=>'tt0944947','type'=>'tvseries','season'=>2]]),$json];
 if(str_ends_with($path,'/subtitles')){
  check($q['language']==='bulgarian'&&$q['limit']==='20'&&$q['sort']==='popular','Verified explicit filters');
  $data=$q['movieId']==='11'?[row(101),row(101),row(102,11,'English','english')]:[row(201,21,'Show.S02E03'),row(202,21,'Show.S02E04'),row(203,21,'Show.S02.Complete')];
  if(($q['page']??'1')==='2')$data=[row(104,11,'Other release')];
  return [200,json_encode(['data'=>$data,'pagination'=>['page'=>(int)$q['page'],'limit'=>20,'pages'=>2,'total'=>23]]),$json];
 }
 if(preg_match('~/subtitles/(101|201|203)$~',$path,$m))return [200,json_encode(['data'=>$m[1]==='101'?row(101):row((int)$m[1],21,$m[1]==='201'?'Show.S02E03':'Show.S02.Complete')]),$json];
 if(preg_match('~/subtitles/(101|201|203)/download$~',$path,$m)){
  check($limit===8388608,'Compressed bound');
  $files=$m[1]==='101'?['Film.srt'=>$srt]:['Show.S02E03.srt'=>mb_convert_encoding($srt,'Windows-1251','UTF-8'),'Show.S02E04.srt'=>$srt];
  return [200,zipBytes($files),['content-type'=>'application/zip']];
 }
 throw new RuntimeException('Unexpected mock path');
};
$clock=static function()use(&$now){return $now;};$service=new Service($config,$transport,$clock);
try{
 $r=$service->search('account',$movie+['page'=>1]);check(count($r['results'])===1,'Dedupe, exact movie and Bulgarian');
 check($r['provider']==='subsource'&&$r['results'][0]['subtitle_id']===101&&$r['next_page']===2,'Provider ID and pagination');
 check($r['results'][0]['fps']===23.976&&$r['results'][0]['uploader']==='#98'&&!isset($r['results'][0]['rating']),'Actual metadata; votes not stars');
 $prior=count($calls);$service->search('account',$movie);check(count($calls)===$prior,'No repeated cached search');
 $more=$service->search('account',$movie+['page'=>2]);check($more['results'][0]['subtitle_id']===104&&$more['next_page']===0,'Explicit more page');
 $tv=$service->search('account',$episode);check(array_column($tv['results'],'subtitle_id')===[201,203],'Wrong episode removed, matching season pack retained');
 reject(fn()=>$service->select('account',$episode+['subtitle_id'=>101]),409);reject(fn()=>$service->select('other',$movie+['subtitle_id'=>101]),409);
 $selected=$service->select('account',$movie+['subtitle_id'=>101]);
 check(str_starts_with($selected['url'],'https://nyama.fun/api/subtitles/file/')&&str_contains($selected['url'],'.vtt?'),'Existing public HTTPS VTT suffix');
 check(!str_contains(json_encode($selected),'fixture-only-key'),'No key in client reply');
 parse_str(parse_url($selected['url'],PHP_URL_QUERY),$q);$opaque=basename(parse_url($selected['url'],PHP_URL_PATH),'.vtt');
 $vtt=$service->file($opaque,$q['expires'],$q['sig']);check(str_contains($vtt,'Здравей, свят!'),'UTF-8 survives');
 reject(fn()=>$service->file('../'.$opaque,$q['expires'],$q['sig']),403);reject(fn()=>$service->file($opaque,$q['expires'],str_repeat('0',64)),403);
 $before=count($calls);$shift=$service->select('account',$movie+['subtitle_id'=>101,'offset_seconds'=>.5]);
 parse_str(parse_url($shift['url'],PHP_URL_QUERY),$qs);$os=basename(parse_url($shift['url'],PHP_URL_PATH),'.vtt');
 check(str_contains($service->file($os,$qs['expires'],$qs['sig']),'00:00:01.500'),'Offset from original cues');check(count($calls)===$before,'No new download for timing');
 $now+=901;reject(fn()=>$service->file($opaque,$q['expires'],$q['sig']),403);$now-=901;
 $tvSelect=$service->select('account',$episode+['subtitle_id'=>203]);parse_str(parse_url($tvSelect['url'],PHP_URL_QUERY),$qtv);
 check(str_contains($service->file(basename(parse_url($tvSelect['url'],PHP_URL_PATH),'.vtt'),$qtv['expires'],$qtv['sig']),'Здравей'),'Season pack chooses exact CP1251 file');
 foreach(['../escape.srt','/absolute.srt','a/../../b.srt','a\\b.srt','a:b.srt','a/b/c/d/e/f.srt']as $name)reject(fn()=>archiveCues(zipBytes([$name=>$srt]),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['bad.exe'=>'executable']),$movie),422);reject(fn()=>archiveCues(zipBytes(['nested.zip'=>'PKnested']),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['dir/'=>'hidden data','caption.srt'=>$srt]),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['empty.srt'=>'']),$movie),422);reject(fn()=>archiveCues(zipBytes(['image.sup'=>'binary']),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['html.srt'=>'<html>fake</html>']),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['bomb.srt'=>str_repeat('a',600000)],ZipArchive::CM_DEFLATE),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['link.srt'=>$srt],ZipArchive::CM_STORE,fn($z)=>$z->setExternalAttributesName('link.srt',ZipArchive::OPSYS_UNIX,0120777<<16)),$movie),422);
 reject(fn()=>archiveCues(zipBytes(['A.S02E03.srt'=>$srt,'B.S02E03.srt'=>$srt]),$episode),409);
 check(archiveCues(zipBytes(['Show.S02E03-1080p.srt'=>$srt]),$episode)[0][2]==='Здравей, свят!','Resolution suffix is not a multi-episode range');
 reject(fn()=>archiveCues(zipBytes(['S02E04.srt'=>$srt]),$episode),409);reject(fn()=>archiveCues(zipBytes(['S02E03-E04.srt'=>$srt]),$episode),409);
 reject(fn()=>archiveCues(zipBytes(['unknown.srt'=>$srt]),$episode),409);
 $utf16="\xff\xfe".mb_convert_encoding($srt,'UTF-16LE','UTF-8');check(archiveCues(zipBytes(['Film.vtt'=>$utf16]),$movie)[0][2]==='Здравей, свят!','UTF-16 caption decode');
 $files=[];for($i=0;$i<201;$i++)$files['f'.$i.'.srt']=$srt;reject(fn()=>archiveCues(zipBytes($files),$movie),422);
 $badConfig=$config;$badConfig['api_key']='';reject(fn()=>new Service($badConfig,$transport,$clock),503);
 $terms=$config;$terms['terms_approved']=false;reject(fn()=>new Service($terms,$transport,$clock),503);
 // API errors, schema drift, content type and shared Retry-After suppression.
 foreach([400,401,403,404,500,429]as $status){
  $now+=4000;$error=new Service($config,fn()=>[$status,'', ['retry-after'=>'120']],$clock);
  if($status===404)check($error->search('error'.$status,$movie)['results']===[],'404 no results');
  else reject(fn()=>$error->search('error'.$status,$movie),$status===500?503:$status);
 }
 $attempts=0;$rate=new Service($config,function()use(&$attempts){$attempts++;return [200,'{}',['content-type'=>'application/json']];},$clock);
 reject(fn()=>$rate->search('blocked',$movie),429);check($attempts===0,'Retry-After prevents another upstream call');$now+=121;
 $malformed=new Service($config,fn()=>[200,'<html>',['content-type'=>'text/html']],$clock);reject(fn()=>$malformed->search('malformed',$movie),502);
 // Authoritative adapter calls the existing helper with revoke=false; never accepts an inactive renewal response.
 $loader=$dir.'/loader.php';file_put_contents($loader,'<?php return new PDO("sqlite::memory:");');putenv('NYAMA_ACTIVATION_PRIVATE_LOADER='.$loader);
 function nyama_activation_with_token(PDO $pdo,string $token,string $id,bool $revoke):array{check(!$revoke,'No revocation');return ['access_status'=>$token===str_repeat('a',64)?'active':'subscription_expired','account'=>['client_number'=>'42']];}
 $auth=require dirname(__DIR__).'/auth-adapter.php';check($auth(str_repeat('a',64),str_repeat('b',64))==='nyamacustomer:42','Actual helper adapter active contract');
 check($auth(str_repeat('c',64),str_repeat('b',64))===null,'Expired entitlement rejected');putenv('NYAMA_ACTIVATION_PRIVATE_LOADER');check($auth(str_repeat('a',64),str_repeat('b',64))===null,'Missing production loader fails closed');
 // Real local HTTP routing proves the signed .vtt path is fetchable without app headers.
 $httpDir=$dir.'/http';mkdir($httpDir,0700);$hc=$config;$hc['storage']=$httpDir;
 $real=new Service($hc,$transport);$real->search('http-account',$movie);$httpFile=$real->select('http-account',$movie+['subtitle_id'=>101]);
 $adapter=$dir.'/http-auth.php';file_put_contents($adapter,'<?php return static fn($token,$id)=>$token===str_repeat("a",64)&&$id===str_repeat("b",64)?"http-account":null;');
 $legacy=$dir.'/legacy.php';file_put_contents($legacy,'<?php namespace NyamaSubtitles; function fail(int $s,string $r): never {exit;} header("Content-Type: application/json"); echo json_encode(["ok"=>true,"legacy"=>true]);');
 $env=['NYAMA_LEGACY_SUBTITLE_ROUTER'=>$legacy,'SUBSOURCE_API_KEY'=>'fixture-only-key','NYAMA_SUBTITLE_PUBLIC_BASE'=>$config['public_base'],'NYAMA_SUBTITLE_SIGNING_SECRET'=>$config['secret'],
  'NYAMA_SUBSOURCE_STORAGE'=>$httpDir,'NYAMA_SUBTITLE_AUTH_ADAPTER'=>$adapter,'SUBSOURCE_TEMP_HOSTING_APPROVED'=>'1'];
 $process=proc_open([PHP_BINARY,'-S','127.0.0.1:18938',dirname(__DIR__).'/public/index.php'],[0=>['pipe','r'],1=>['file',$dir.'/http.log','a'],2=>['file',$dir.'/http.log','a']],$pipes,null,$env);
 try {
  $ready=false;for($i=0;$i<50;$i++){$sock=@fsockopen('127.0.0.1',18938,$errno,$err,.05);if($sock){fclose($sock);$ready=true;break;}usleep(20000);}check($ready,'HTTP service started');
  $fetch=static function(string $path,array $headers=[]){$context=stream_context_create(['http'=>['header'=>implode("\r\n",$headers),'timeout'=>3,'ignore_errors'=>true]]);$body=file_get_contents('http://127.0.0.1:18938'.$path,false,$context);return [$http_response_header[0],$body,$http_response_header];};
  [$legacyStatus,$legacyBody]=$fetch('/api/subtitles/search?imdb_id=tt1375666&language=bg');check(str_contains($legacyStatus,'200')&&json_decode($legacyBody,true)['legacy']===true,'Old standalone mobile handler dispatched before new helpers');
  $p=parse_url($httpFile['url'],PHP_URL_PATH).'?'.parse_url($httpFile['url'],PHP_URL_QUERY);
  [$status,$body,$headers]=$fetch($p);check(str_contains($status,'200')&&str_starts_with($body,'WEBVTT'),'Signed unauthenticated .vtt fetch');
  check((bool)array_filter($headers,fn($h)=>str_starts_with(strtolower($h),'content-type: text/vtt')),'VTT MIME');
  [$status]=$fetch($p.'bad');check(str_contains($status,'403'),'Tampered HTTP signature rejected');
  [$status]=$fetch('/api/subtitles/search?imdb_id=tt1375666&language=bg',['X-Nyama-Subtitle-Provider: subsource']);check(str_contains($status,'401'),'Search requires existing activation');
  [$status,$body]=$fetch('/api/subtitles/search?imdb_id=tt1375666&language=bg',['X-Nyama-Subtitle-Provider: subsource','Authorization: Bearer '.str_repeat('a',64),'X-Nyama-Activation-ID: '.str_repeat('b',64)]);
  check(str_contains($status,'200')&&json_decode($body,true)['provider']==='subsource','Authorized cache search through actual router');
  check(!str_contains($body,'fixture-only-key'),'API secret absent from HTTP output');
 } finally {proc_terminate($process);proc_close($process);}
 echo "PASS: $checks SubSource checks (mock API/ZIP fixtures, not live provider).\n";
}finally{$files=new RecursiveIteratorIterator(new RecursiveDirectoryIterator($dir,FilesystemIterator::SKIP_DOTS),RecursiveIteratorIterator::CHILD_FIRST);foreach($files as $p){if($p->isDir())rmdir($p->getPathname());else unlink($p->getPathname());}rmdir($dir);}

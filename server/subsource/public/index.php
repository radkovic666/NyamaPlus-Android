<?php
declare(strict_types=1);
// Dispatch a preexisting mobile handler BEFORE loading new helpers: older standalone
// releases define the same utility functions and must not be double-loaded.
$entryPath=parse_url($_SERVER['REQUEST_URI']??'',PHP_URL_PATH);
$entryProvider=$_SERVER['HTTP_X_NYAMA_SUBTITLE_PROVIDER']??'';
if(is_string($entryPath)&&preg_match('~^/api/subtitles/(search|select|file/[a-f0-9]{64})$~D',$entryPath)
    &&$entryProvider!=='subsource') {
    $legacy=getenv('NYAMA_LEGACY_SUBTITLE_ROUTER');
    if($legacy&&is_file($legacy)&&realpath($legacy)!==__FILE__){require $legacy;exit;}
    http_response_code(503);header('Content-Type: application/json; charset=utf-8');header('Cache-Control: no-store');
    echo '{"ok":false,"error":"unconfigured"}';exit;
}
require dirname(__DIR__).'/Service.php';
use NyamaSubSource\Service;
use function NyamaSubtitles\fail;

header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header("Content-Security-Policy: default-src 'none'");
try {
    $config = [
        'secret'=>getenv('NYAMA_SUBTITLE_SIGNING_SECRET'), 'api_key'=>getenv('SUBSOURCE_API_KEY'),
        'public_base'=>getenv('NYAMA_SUBTITLE_PUBLIC_BASE'), 'storage'=>getenv('NYAMA_SUBSOURCE_STORAGE'),
        'terms_approved'=>getenv('SUBSOURCE_TEMP_HOSTING_APPROVED')==='1',
        'download_hosts'=>array_values(array_filter(array_map('trim',explode(',',getenv('SUBSOURCE_DOWNLOAD_HOSTS')?:'')))),
    ];
    $method=$_SERVER['REQUEST_METHOD']??'';
    $base=parse_url($config['public_base']?:'',PHP_URL_PATH);
    $path=parse_url($_SERVER['REQUEST_URI']??'',PHP_URL_PATH);
    if (!is_string($base)||!is_string($path)||!str_starts_with($path,$base.'/')) fail(404,'not_found');
    $route=substr($path,strlen($base));
    if (!($method==='GET'&&($route==='/search'||preg_match('~^/file/[a-f0-9]{64}\.vtt$~D',$route)))
        && !($method==='POST'&&$route==='/select')) fail(404,'not_found');
    if (!is_string($config['storage']) || $config['storage']==='' || !str_starts_with($config['storage'],'/')) fail(503,'unconfigured');
    $storage=realpath($config['storage']);$public=realpath(__DIR__);
    $webroot=realpath($_SERVER['DOCUMENT_ROOT']??'')?:$public;
    if ($storage===false || $storage===$public || str_starts_with($storage,$public.'/') || $storage===$webroot || str_starts_with($storage,$webroot.'/') || is_link($config['storage'])) fail(503,'unconfigured');
    // Signed fetch alone is unauthenticated so VidAPI's server can retrieve exactly this file.
    if ($method==='GET'&&str_starts_with($route,'/file/')) {
        $service=new Service($config);
        $vtt=$service->file(substr($route,6,-4),$_GET['expires']??null,$_GET['sig']??null);
        header('Content-Type: text/vtt; charset=utf-8');header('Content-Disposition: inline; filename="subtitle.vtt"');
        header('Access-Control-Allow-Origin: *');echo $vtt;exit;
    }
    $auth=$_SERVER['HTTP_AUTHORIZATION']??'';$device=$_SERVER['HTTP_X_NYAMA_ACTIVATION_ID']??'';
    if (!preg_match('/^Bearer ([a-f0-9]{64})$/D',$auth,$match)||!preg_match('/^[a-f0-9]{64}$/D',$device)) fail(401,'unauthorized');
    $adapter=getenv('NYAMA_SUBTITLE_AUTH_ADAPTER');
    if (!$adapter||!is_file($adapter)||!str_starts_with($adapter,'/')||str_starts_with(realpath($adapter)?:'',__DIR__.'/')||str_starts_with(realpath($adapter)?:'',$webroot.'/')) fail(503,'unconfigured');
    $authenticate=require $adapter;
    if (!is_callable($authenticate)) fail(503,'unconfigured');
    // Must verify ACTIVE entitlement + unrevoked permanent token for this installation server-side.
    $user=$authenticate($match[1],$device);
    if (!is_string($user)||$user===''||strlen($user)>128) fail(403,'unauthorized');
    $service=new Service($config);
    header('Content-Type: application/json; charset=utf-8');
    if ($method==='GET') $result=$service->search($user,$_GET);
    else {
        if (stripos($_SERVER['CONTENT_TYPE']??'','application/json')!==0) fail(415,'invalid_input');
        $raw=file_get_contents('php://input',false,null,0,8193);if ($raw===false||strlen($raw)>8192) fail(400,'invalid_input');
        try { $input=json_decode($raw,true,16,JSON_THROW_ON_ERROR); } catch (\Throwable $e) { fail(400,'invalid_input'); }
        if (!is_array($input)) fail(400,'invalid_input');$result=$service->select($user,$input);
    }
    echo json_encode($result,JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
} catch (\Throwable $error) {
    $status=$error instanceof \NyamaSubtitles\ApiError?$error->status:503;
    $reason=$error instanceof \NyamaSubtitles\ApiError?$error->reason:'unavailable';
    // Never log provider responses, bearer headers, credentials, signed URLs or subtitle text.
    http_response_code($status);header('Content-Type: application/json; charset=utf-8');
    if ($status===429) header('Retry-After: 60');
    echo json_encode(['ok'=>false,'error'=>$reason]);
}

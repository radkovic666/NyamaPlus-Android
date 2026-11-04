<?php
/** Owner-run CLI smoke check. Requires a privately provisioned environment; never echoes its key.
 * First argument IMDb, optional season and episode. Optional --subtitle=ID downloads
 * that explicitly chosen ZIP and produces a signed URL, but prints only safe outcome metadata.
 */
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require __DIR__.'/Service.php';
try {
    $config=['api_key'=>getenv('SUBSOURCE_API_KEY'),'secret'=>getenv('NYAMA_SUBTITLE_SIGNING_SECRET'),
      'public_base'=>getenv('NYAMA_SUBTITLE_PUBLIC_BASE'),'storage'=>getenv('NYAMA_SUBSOURCE_STORAGE'),
      'terms_approved'=>getenv('SUBSOURCE_TEMP_HOSTING_APPROVED')==='1','download_hosts'=>array_filter(explode(',',getenv('SUBSOURCE_DOWNLOAD_HOSTS')?:''))];
    $service=new \NyamaSubSource\Service($config);
    $media=['imdb_id'=>$argv[1]??'','language'=>'bg'];
    if(isset($argv[2],$argv[3])&&ctype_digit($argv[2])&&ctype_digit($argv[3]))$media+=['season'=>(int)$argv[2],'episode'=>(int)$argv[3]];
    $chosen=null;foreach(array_slice($argv,2) as $arg)if(preg_match('/^--subtitle=([0-9]+)$/D',$arg,$match))$chosen=(int)$match[1];
    $result=$service->search('owner-cli-smoke',$media);
    echo json_encode(['ok'=>true,'provider'=>'subsource','media'=>$result['media'],'count'=>count($result['results']),'next_page'=>$result['next_page'],
       'releases'=>array_map(fn($r)=>['subtitle_id'=>$r['subtitle_id'],'release'=>$r['release']],$result['results'])],JSON_UNESCAPED_UNICODE|JSON_PRETTY_PRINT)."\n";
    if($chosen!==null) {
      $selection=$service->select('owner-cli-smoke',$media+['subtitle_id'=>$chosen]);
      echo json_encode(['zip_validated'=>true,'subtitle_id'=>$selection['subtitle_id'],'expires'=>$selection['expires'],'signed_file_ready'=>true])."\n";
    }
} catch(Throwable $e) {
    // No exception text, headers, response payload, signed capability URL or secret.
    fwrite(STDERR,json_encode(['ok'=>false,'error'=>$e instanceof \NyamaSubtitles\ApiError?$e->reason:'unavailable'])."\n");exit(1);
}

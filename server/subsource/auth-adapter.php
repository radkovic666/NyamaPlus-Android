<?php
declare(strict_types=1);
/** Copy OUTSIDE all public roots. The private loader must return the existing production PDO
 * and load the deployed activation_core.php / authoritative authorization helpers.
 * Does not implement a new token scheme or trust a token merely because its shape is valid.
 * Uses the supplied site's real nyama_activation_with_token contract (active vs renewal).
 */
return static function(string $bearer,string $installation): ?string {
    if(!preg_match('/^[a-f0-9]{64}$/D',$bearer)||!preg_match('/^[a-f0-9]{64}$/D',$installation))return null;
    $loader=getenv('NYAMA_ACTIVATION_PRIVATE_LOADER');
    if(!$loader||!str_starts_with($loader,'/')||!is_file($loader))return null;
    $root=realpath($_SERVER['DOCUMENT_ROOT']??'');$real=realpath($loader);
    if($root&&$real&&($real===$root||str_starts_with($real,$root.'/')))return null;
    try {
        $pdo=require $loader;
        if(!$pdo instanceof PDO||!function_exists('nyama_activation_with_token'))return null;
        $response=nyama_activation_with_token($pdo,$bearer,$installation,false);
        if(($response['access_status']??'')!=='active')return null;
        $id=$response['account']['client_number']??null;
        return is_string($id)&&preg_match('/^[0-9]{1,32}$/D',$id)?'nyamacustomer:'.$id:null;
    } catch(Throwable $e) { return null; } // No credentials, account data, or exception body reaches output/logs.
};

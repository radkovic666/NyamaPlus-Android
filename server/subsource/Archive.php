<?php
declare(strict_types=1);
namespace NyamaSubSource;
require_once dirname(__DIR__).'/subtitles-common/Common.php';
use function NyamaSubtitles\{fail,parseSubtitle};

/** A title may be a season pack, but a selected file must prove a single exact episode. */
function episodeNumbers(string $name): array {
    preg_match_all('/(?<![a-z0-9])(?:s(\d{1,3})[ ._-]*e(\d{1,4})|(\d{1,3})x(\d{1,4}))(?!\d)/i',$name,$all,PREG_SET_ORDER);
    $found=[];foreach($all as $m) $found[]= [(int)($m[1]!==''?$m[1]:$m[3]),(int)($m[2]!==''?$m[2]:$m[4])];
    // Ranges/multi-episode files are deliberately unsupported: no guessing across two episode clocks.
    if(preg_match('/(?:s\d{1,3}[ ._-]*e\d{1,4}|\d{1,3}x\d{1,4})[ ._-]*(?:e\d|[-+]\d{1,4}(?![\dp]))/i',$name)) return [[-1,-1]];
    return array_values(array_unique($found,SORT_REGULAR));
}
function archiveCues(string $bytes,array $media): array {
    if (strlen($bytes)>8388608 || !str_starts_with($bytes,"PK\x03\x04")) fail(422,'invalid_archive');
    if (!class_exists('ZipArchive')) fail(503,'unconfigured');
    $temp=tempnam(sys_get_temp_dir(),'nyama-archive-'); if($temp===false) fail(503,'storage_unavailable');
    chmod($temp,0600);file_put_contents($temp,$bytes);$zip=new \ZipArchive();$opened=false;
    try {
        if($zip->open($temp,\ZipArchive::CHECKCONS)!==true) fail(422,'invalid_archive');$opened=true;
        if($zip->numFiles<1||$zip->numFiles>200) fail(422,'archive_limits');
        $total=0;$compressed=0;$matches=[];
        for($i=0;$i<$zip->numFiles;$i++) {
            $s=$zip->statIndex($i);if(!$s)fail(422,'invalid_archive');$name=$s['name'];
            if(strlen($name)>512||!mb_check_encoding($name,'UTF-8')||preg_match('/[\x00-\x1f\x7f\\\\:]/',$name)||str_starts_with($name,'/')) fail(422,'unsafe_archive');
            $parts=explode('/',rtrim($name,'/'));
            if(count($parts)>5||array_intersect($parts,['..','.',''])) fail(422,'unsafe_archive');
            $zip->getExternalAttributesIndex($i,$system,$attrs);$kind=($attrs>>16)&0170000;
            if($kind!==0 && $kind!==0100000 && $kind!==0040000) fail(422,'unsafe_archive');
            if(str_ends_with($name,'/')){if($s['size']!==0)fail(422,'unsafe_archive');continue;}
            if($kind===0040000||($s['encryption_method']??0)!==0)fail(422,'unsafe_archive');
            $size=$s['size'];$packed=$s['comp_size'];$total+=$size;$compressed+=$packed;
            if($size<1||$size>1048576||$packed<1||$total>33554432||$size/$packed>100||$total/max(1,$compressed)>100)fail(422,'archive_limits');
            $ext=strtolower(pathinfo($name,PATHINFO_EXTENSION));
            if(!in_array($ext,['srt','vtt'],true))fail(422,'unsupported_format');
            if(isset($media['season'])) {
                $episodes=episodeNumbers($name); // Folder/filename conflicts are rejected together.
                if($episodes!==[[$media['season'],$media['episode']]])continue;
            }
            $matches[]=[$i,$size,$s['crc']];
        }
        if(count($matches)!==1)fail(409,count($matches)>1?'archive_ambiguous':'episode_mismatch');
        [$index,$size,$crc]=$matches[0];$data=$zip->getFromIndex($index,$size+1);
        if($data===false||strlen($data)!==$size||sprintf('%08x',$crc)!==hash('crc32b',$data))fail(422,'invalid_archive');
        return parseSubtitle($data);
    } finally {if($opened)$zip->close();unlink($temp);}
}

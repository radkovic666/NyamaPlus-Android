<?php
declare(strict_types=1);
namespace NyamaSubtitles;
require_once __DIR__.'/Common.php';
class Store {
    protected \PDO $db;
    protected \Closure $clock;
    public function __construct(protected array $config, ?callable $clock=null) {
        if (empty($config['secret']) || strlen($config['secret'])<32 || empty($config['storage'])
            || ($config['public_base']??'')!=='https://nyama.fun/api/subtitles') fail(503,'unconfigured');
        $dir=$config['storage']; if (!is_dir($dir)||!is_writable($dir)||is_link($dir)) fail(503,'storage_unavailable');
        $this->clock=\Closure::fromCallable($clock??'time');
        $this->db=new \PDO('sqlite:'.$dir.'/subsource.sqlite',null,null,[\PDO::ATTR_ERRMODE=>\PDO::ERRMODE_EXCEPTION]);
        chmod($dir.'/subsource.sqlite',0600); $this->db->exec('PRAGMA busy_timeout=5000');
        $this->db->exec('CREATE TABLE IF NOT EXISTS cache(k TEXT PRIMARY KEY,body TEXT NOT NULL,expires INTEGER NOT NULL)');
        $this->db->exec('CREATE TABLE IF NOT EXISTS quota(k TEXT PRIMARY KEY,n INTEGER NOT NULL,expires INTEGER NOT NULL)');
    }
    protected function now(): int { return ($this->clock)(); }
    protected function key(string $user, array $media, string $suffix=''): string {
        return hash_hmac('sha256',$user."\n".json_encode($media)."\n".$suffix,$this->config['secret']);
    }
    protected function get(string $key): ?array {
        $q=$this->db->prepare('SELECT body FROM cache WHERE k=? AND expires>?');$q->execute([$key,$this->now()]);
        $raw=$q->fetchColumn();return $raw===false ? null : json_decode($raw,true,32,JSON_THROW_ON_ERROR);
    }
    protected function put(string $key, array $data, int $ttl): void {
        $q=$this->db->prepare('INSERT OR REPLACE INTO cache VALUES(?,?,?)');$q->execute([$key,json_encode($data,JSON_THROW_ON_ERROR),$this->now()+$ttl]);
        $this->db->prepare('DELETE FROM cache WHERE expires<=?')->execute([$this->now()]);
        // Bound logical cache size to 64 MiB/1000 records; SQLite reuses freed pages.
        while ((int)$this->db->query('SELECT COALESCE(SUM(length(body)),0) FROM cache')->fetchColumn()>67108864
            || (int)$this->db->query('SELECT COUNT(*) FROM cache')->fetchColumn()>1000)
            $this->db->exec('DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY expires ASC LIMIT 1)');
    }
    protected function limit(string $bucket, int $maximum, int $window): void {
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
}

# Nyama+ optional OpenSubtitles service

This is an independently deployed PHP 8.2+ example, **not an existing nyama.fun endpoint**. Nothing in this task deploys it, alters a production database, or puts an OpenSubtitles secret in Android. The supplied APK leaves the feature disabled; native VidAPI Bulgarian remains available immediately.

## Install and configure

1. Obtain an approved OpenSubtitles.com application API key and a server-side bearer token for an account authorized to download. Use the official `/login` flow outside the APK, manage renewal securely, and supply a current token to PHP. This example deliberately does not store login passwords or implement token refresh. An expired/rejected token fails safely. Confirm your plan's quotas, redistribution/temporary hosting rights, attribution requirements and any paid usage before enabling the service. The local caps below are **not** OpenSubtitles plan entitlements.
2. Install PHP 8.2+ with `curl`, `pdo_sqlite`, `mbstring`, `openssl`, and 64-bit integers. Deploy `Service.php` and `public/index.php`; route only the three endpoints below to `public/index.php`. Keep all configuration, adapter code and SQLite storage outside **every** public document root. Give PHP exclusive write access to storage (directory 0700, database 0600). Use a local filesystem, not NFS. For example:

   ```sh
   install -d -m 0700 -o www-data -g www-data /var/lib/nyama-subtitles
   install -d -m 0750 -o root -g www-data /etc/nyama-subtitles
   ```

3. Copy `auth-adapter.example.php` to `/etc/nyama-subtitles/auth-adapter.php` and implement its documented callback with the deployed Nyama activation authorization helper. It must verify the permanent token, installation ID, ownership, revocation, verified email and current active viewing entitlement, including existing VIP/hourly/trial rules. Return a stable account ID string; return `null` on rejection. The provided adapter **always rejects**. No production schema is guessed, no activation is created/renewed, and no customer password is needed. Search/select are not ready until this adapter is implemented and tested against valid, expired, suspended and revoked activations.
4. Inject the variables listed in `.env.example` via your PHP-FPM pool/server environment. PHP does **not** automatically load `.env`; do not place it in the web root. Generate `NYAMA_SUBTITLE_SIGNING_SECRET` with `openssl rand -hex 32` and keep it only on the server. Set `NYAMA_SUBTITLE_PUBLIC_BASE=https://nyama.fun/api/subtitles`. Set `OPENSUBTITLES_USER_AGENT` to your registered application identifier/version. `OPENSUBTITLES_DOWNLOAD_HOSTS` must list exact approved hostnames observed in your authorized official `/download` responses; leave it unset until verified. No wildcard matching is used. The example rejects all redirects: a changed download host requires administrator review. Do not log API responses or temporary download links.
5. Serve through valid HTTPS on **nyama.fun**, so the Android app never forwards existing Nyama activation credentials to another host. A separately hosted PHP service can be privately reverse-proxied under this path; do not send app credentials across a redirect. The signed file URL must be publicly reachable by VidAPI's server without cookies or authorization. No CDN/edge cache of private API responses; suppress query-string access logging for `/file/` because its signature is a temporary capability.
6. Configure a build with this **public, nonsecret** origin only after deploying:

   ```sh
   ./gradlew :app:assembleDebug -PnyamaSubtitlesBackend=https://nyama.fun/api/subtitles
   ```

   Omit the property to build disabled. Unconfigured, legacy unactivated, unauthorized, unavailable and rate-limited states never initiate playback changes. No OpenSubtitles key/token belongs in Gradle or the APK. The new service uses existing secure activation credentials; it does not replace identifiers or mutate activation state.

Example nginx routing fragment (adapt the PHP socket and installation path to your server; keep existing site routes):

```nginx
# Define in http{}; edge limits also cover invalid unauthenticated requests.
limit_req_zone $binary_remote_addr zone=nyamasubs:10m rate=2r/s;

# In the existing HTTPS nyama.fun server{}:
location ~ ^/api/subtitles/(search|select|file/[a-f0-9]{64})$ {
    limit_req zone=nyamasubs burst=10 nodelay;
    client_max_body_size 8k;
    access_log off; # signed URLs, auth requests and searches must not enter access logs
    include fastcgi_params;
    fastcgi_param SCRIPT_FILENAME /opt/nyama-opensubtitles/public/index.php;
    fastcgi_param HTTP_AUTHORIZATION $http_authorization;
    fastcgi_param HTTP_X_NYAMA_ACTIVATION_ID $http_x_nyama_activation_id;
    fastcgi_pass unix:/run/php/php8.3-fpm.sock;
}
```

Use PHP-FPM `env[NAME] = value` or a secrets manager for the required variables; preserve the site's existing TLS and header handling. The service rejects an outbound HTTPS/ALL proxy because a proxy can defeat DNS pinning; it never disables a configured proxy to bypass a network restriction. Deploy with approved direct egress, or review/implement equivalent pinning at an approved outbound gateway. Never disable certificate verification. Run scheduled SQLite maintenance (`VACUUM` during idle time if needed) and monitor disk space/quotas without logging customer credentials or caption text.

## Routes and contract

All JSON replies contain `ok`. Errors expose a fixed category, never upstream bodies or secrets. Search/select require:

```text
Authorization: Bearer <existing permanent Nyama activation token>
X-Nyama-Activation-ID: <existing 64-hex activation installation ID>
Accept: application/json
```

- `GET /api/subtitles/search?imdb_id=tt0111161&language=bg` (movie).
- `GET /api/subtitles/search?imdb_id=tt0944947&language=bg&season=2&episode=3` (exact episode). Both numbers are required together. A generic series chooser cannot safely identify an episode; select one first.
- Search calls official HTTPS `GET /api/v1/subtitles` with `languages=bg`, movie `imdb_id`, or episode `parent_imdb_id`, `season_number`, `episode_number`, and `type`. It checks actual returned `feature_details`, language and every file ID. Missing/ambiguous/wrong episode details are excluded, never guessed. Only the first upstream page (at most 100 files) is returned; there is no automatic pagination/download burst. Actual release/file metadata is retained; missing FPS/format/rating is omitted.
- Search response: `{"ok":true,"media":{"imdb_id":"tt0111161","language":"bg"},"results":[{"file_id":123,"release":"Actual release","language":"bg","fps":23.976,"downloads":100,"rating":8.2,"format":"srt"}]}`. Metadata above is an illustrative fixture, not a live match.
- `POST /api/subtitles/select`, `Content-Type: application/json`, body `{"imdb_id":"tt0111161","language":"bg","file_id":123,"offset_seconds":0.5}`. Episodes include both numbers. File must belong to that authenticated account's recent verified search or its authorized cached original. A caller cannot submit an arbitrary URL or another episode's ID.
- Selection uses official `POST /api/v1/download` (`file_id`, `sub_format=srt`), then fetches the **actual** temporary HTTPS file with no API/Nyama credentials forwarded to the file host. It validates exact allowed host, public DNS, TLS, redirects, time/size bounds, content and cue timestamps. SRT/VTT becomes inert UTF-8 VTT; HTML/SSA styling is removed. Files/JSON are bounded to 1 MiB, 6,000 cues, 8,192 characters per cue and 72-hour cue times. Original cached cue times stay immutable. Offsets ±10 seconds, half-second steps, generate a new signed VTT from that original, without another official download while cached.
- Selection response: `ok`, same `media`, `file_id`, `offset_seconds`, actual `release`, Unix `expires`, `url`.
- `GET /api/subtitles/file/<64-hex-opaque-id>?expires=...&sig=...` is the **only** unauthenticated route. HMAC-SHA256 covers version, opaque ID and expiry. URLs last 15 minutes. No filename/path is accepted; data is retrieved by an exact opaque cache key. Wrong/expired signature fails 403, evicted content fails 404. Response `text/vtt; charset=utf-8`, `nosniff`, `no-store`, narrowly fetchable CORS. It contains no account or OpenSubtitles credentials. A valid signed URL can be fetched until expiry even if the account is subsequently revoked; keep TTL short and treat it as a temporary bearer capability. No video access is granted by it.

Search cache is fresh for five minutes and selection membership lasts 30 minutes. Cached authorized originals last 30 minutes; selecting after eviction/expiry may require a new search/download and quota. Per-account limits: 15 searches/minute, 10 selects/minute, configurable official downloads/day (default 10). Global: 120 searches/minute, 100 selects/minute, configurable downloads/day (default 100). Fixed windows, conservative failed-download accounting, no automatic retry. Logical cache is bounded to 64 MiB/1,000 entries with expiry cleanup; SQLite can retain freed pages until maintenance. A nonblocking global download lock prevents duplicate upstream downloads; another simultaneous download returns 429 rather than waiting. Real OpenSubtitles quotas may be lower; upstream 429/406 also fails safely. Use a single instance/shared local SQLite, or replace the quota/cache/lock layer for multiple hosts.

## Verification

Offline/mocked suite (no live API credentials):

```sh
php -l Service.php
php -l public/index.php
php tests/run.php
```

Tests use a mock official transport and a loopback-only PHP HTTP router with a test authorization adapter. They cover identity/language filtering, actual metadata, cache/quota, signed HTTP file fetch, expired/tampered signature, revoked authorization, path/SSRF restrictions, encoding, bounded parsing and offsets without additional download quota. They do not validate provider subtitle rendering or production authentication.

After deployment, use a test entitlement and server-held secrets. Search with real `Authorization`/`X-Nyama-Activation-ID` headers, select a returned exact-media file, fetch its signed HTTPS URL from an external network **without** headers, then verify actual VidAPI playback. Supply headers through a protected curl configuration/file, not shell history or committed scripts. Repeat with wrong episode/file ID, revoked token, missing auth, expired signature, unavailable upstream and exhausted quota. Inspect provider subtitle load behavior on TV and mobile, fullscreen menu focus, paused selection, position recovery and fallback. Check activation/playlist data is unchanged.

## Documentation and current verification limits

Implementation follows the route/parameter requirements in the task and official OpenSubtitles REST v1 model. The cloud denied both `https://vidapi.ru/api` and `https://ai.opensubtitles.com/api-docs` (proxy 403). We inspected the official OpenSubtitles-maintained [`vlsub-opensubtitles-com`](https://github.com/opensubtitles/vlsub-opensubtitles-com/tree/871b47131ecf9868930386140f22cfc9b15ce5d4) source to cross-check API-Key/Bearer headers, `/subtitles`, `/download`, release/files metadata and episode result fields; no SDK code is bundled. Current official documentation, plan terms, real download hosts, credentials and deployed VidAPI `sub_*` handling still require owner verification. No live end-to-end success is claimed.

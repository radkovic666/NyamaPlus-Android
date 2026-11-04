# SubSource subtitles on the EXISTING nyama.fun host

This package adds a PHP handler at **https://nyama.fun/api/subtitles** to the existing website/web server. No new DNS record, subdomain, certificate, public server or replacement vhost. Nothing here deploys remotely. Android TV 1.0.44 searches this exact route **only after a deliberate user request**, using the existing native picker. Playback starts immediately with `ds_lang=bg` and continues if the service is unavailable. Mobile's existing UI/protocol are preserved; an optional existing legacy handler may be dispatched by the same route, not a second public route.

## Verification status — read before enabling

The owner supplied the official endpoint/header/status/rate-limit documentation and confirmed Pukanki catalog `rating` is IMDb. The pasted API docs did **not** contain JSON models. Cloud proxy access to both `subsource.net/api-docs` and `api.subsource.net` was denied. Request/model field spelling was cross-checked against the public **unofficial** [.NET client](https://github.com/moviecollection/sub-source/tree/42d67f61fe96b5cd6a3eed2163e4891bf7e1dc89). This is corroboration, not a live provider schema certification. Models implemented strictly: movie `data[]`/detail `data` with `movieId`, `imdbId`, `type`, `season`; subtitle `subtitleId`, `movieId`, `language`, `releaseInfo[]`, optional `framerate`, `downloads`, `uploaderId`; pagination `page`, `limit`, `pages`. Unknown/mismatched shapes fail closed. Live request/ZIP schema and VidAPI playback must be tested after secure provisioning. No live end-to-end success is claimed.

The supplied API key was not stored or tested in this workspace. It must be injected privately on the existing server as `SUBSOURCE_API_KEY`. Rotate the temporary key after testing, as planned. Do not paste it into Android/Gradle, source control, commands containing literal values, access logs or test fixtures.

## Files and prerequisites

Deploy these sibling directories together from the tested commit:

- `server/subsource/`: handler, provider/ZIP processing, authorization adapter, smoke script and tests.
- `server/subtitles-common/`: HTTPS validation, bounded cue conversion and cache/quota helpers.
- Optional `server/opensubtitles/`: only if the preexisting mobile OpenSubtitles backend is already deployed and must remain available. It is not called by the new TV SubSource workflow.

Use existing PHP 8.2+ FPM (tested on PHP 8.3), 64-bit PHP, `curl`, `openssl`, `mbstring`, `pdo_sqlite` and `zip`. Cache is private local SQLite; no MySQL migration or account-table changes. Keep secrets, SQLite, the auth adapter and private activation loader outside **every** public root. Shared cache/quota requires one local service instance; multi-host operation needs a shared atomic store/lock implementation before scaling.

## Opt-in installation on the existing server

1. Back up the current subtitle handler and its small routing include. Keep your existing website/vhost intact. Install the versioned source, for example `/opt/nyama-subtitles/releases/1.0.44/server/`, owned by root and readable by PHP. Symlink `/opt/nyama-subtitles/current` to that release. Only `public/index.php` is routed; includes/tests/probe/config are not public endpoints.
2. Create private directories, adapting the existing PHP user/group:

   ```sh
   install -d -m 0700 -o www-data -g www-data /var/lib/nyama-subsource
   install -d -m 0750 -o root -g www-data /etc/nyama-subtitles
   install -m 0640 -o root -g www-data server/subsource/auth-adapter.php /etc/nyama-subtitles/auth-adapter.php
   ```

3. Create a **private** `activation-loader.php` that returns the deployed site's existing PDO and loads its authoritative activation helper. No new identity or entitlement implementation is needed. The adapter calls the supplied site's real `nyama_activation_with_token($pdo, $bearer, $installation, false)` and accepts only `access_status=active` with a valid account client number. This reuses email verification, revocation, suspension, expiry/VIP/hourly rules. A renewal response is rejected. The helper may update its ordinary `last_seen_at`; the adapter never invokes revoke, issues tokens or changes subscription/device limits. The private loader/production database connection are not available in Codex, so the adapter remains **disconnected and fail-closed** until the owner connects it and verifies the deployed helper's return contract. Do not copy live database credentials into this repository.
4. Inject environment variables from `.env.example`. Provision privately on the **existing nyama.fun PHP host only**:

   ```text
   SUBSOURCE_API_KEY=<PASTE_THE_API_KEY_FROM_OUR_CHAT_HERE>
   NYAMA_SUBTITLE_PUBLIC_BASE=https://nyama.fun/api/subtitles
   ```

   Also set `NYAMA_SUBTITLE_SIGNING_SECRET` to a server-generated secret of at least 32 bytes (`openssl rand -hex 32`), `NYAMA_SUBSOURCE_STORAGE`, `NYAMA_SUBTITLE_AUTH_ADAPTER`, and `NYAMA_ACTIVATION_PRIVATE_LOADER`. Confirm SubSource plan/temporary-hosting permissions and set `SUBSOURCE_TEMP_HOSTING_APPROVED=1` **only when approved**. This does not enable a public unauthenticated download proxy. `SUBSOURCE_DOWNLOAD_HOSTS` is an optional exact HTTPS redirect-host allowlist verified by the owner; leave empty until needed. No wildcard. Cross-host redirects never receive the API key.

   Merely placing values in `.env` does nothing: this package deliberately does not auto-load such files. Use your existing secrets manager, PHP-FPM pool environment, or a root-owned systemd `EnvironmentFile` loaded by the PHP-FPM service. For example, a protected environment file outside web roots, a PHP-FPM systemd drop-in referencing it, then pool `env[SUBSOURCE_API_KEY] = $SUBSOURCE_API_KEY` (and equivalent imports for the other names). Do not print values in diagnostics. Load changed environment by restarting the **existing** PHP-FPM service during a maintenance window; no replacement server is introduced. Rotate the temporary key in that secret store after testing.
5. Add **one narrow path include** to the existing HTTPS vhost, replacing only any prior subtitle-route include. Preserve its other application routes and TLS settings. Examples below are snippets, not full server configurations. Configure the same PHP-FPM pool with the new environment. Suppress query-string/body/auth-header logging for these routes because signed URLs are temporary bearer capabilities. Add equivalent edge request/body limits and keep existing security controls.
6. Run syntax/config checks (`php -l`, `nginx -t` or `apachectl configtest`) before an operator reloads the existing web server. Run the smoke checks below using a test entitlement before enabling for customers. **No Android rebuild is required for the fixed TV URL**. Mobile keeps its original optional `nyamaSubtitlesBackend` build setting.

### Nginx snippet

```nginx
# In existing http{} if no equivalent request limiter exists:
limit_req_zone $binary_remote_addr zone=nyama_subtitles:10m rate=2r/s;

# In existing nyama.fun HTTPS server{} ONLY:
location ~ ^/api/subtitles/(search|select|file/[a-f0-9]{64}(\.vtt)?)$ {
    limit_req zone=nyama_subtitles burst=10 nodelay;
    client_max_body_size 8k;
    access_log off;
    include fastcgi_params;
    fastcgi_param SCRIPT_FILENAME /opt/nyama-subtitles/current/server/subsource/public/index.php;
    fastcgi_param HTTP_AUTHORIZATION $http_authorization;
    fastcgi_param HTTP_X_NYAMA_ACTIVATION_ID $http_x_nyama_activation_id;
    fastcgi_param HTTP_X_NYAMA_SUBTITLE_PROVIDER $http_x_nyama_subtitle_provider;
    fastcgi_pass unix:/run/php/php8.3-fpm.sock;
}
```

Keep the actual existing PHP socket/version. Optional bare file paths support the existing mobile handler only. This snippet does not grant access to source/config files. For legacy mobile routing, set `NYAMA_LEGACY_SUBTITLE_ROUTER` to the existing legacy handler's absolute local filename. Requests without the new TV provider header go there; `.vtt` signed fetches always use SubSource. If legacy service was never configured, leave it empty and its old unavailable state remains. TV never falls back to an OpenSubtitles API search.

### Apache 2.4 snippet

Inside the existing nyama.fun HTTPS vhost (adapt PHP-FPM handler to your current setup):

```apache
AliasMatch "^/api/subtitles/(search|select|file/[a-f0-9]{64}(\.vtt)?)$" "/opt/nyama-subtitles/current/server/subsource/public/index.php"
<Directory "/opt/nyama-subtitles/current/server/subsource/public">
    Require all denied
    <Files "index.php">
        Require all granted
        SetHandler "proxy:unix:/run/php/php8.3-fpm.sock|fcgi://localhost"
    </Files>
    LimitRequestBody 8192
</Directory>
SetEnvIfNoCase Authorization "^(.*)$" HTTP_AUTHORIZATION=$1
SetEnvIf Request_URI "^/api/subtitles/" nyama_subtitles_request
# Add env=!nyama_subtitles_request to the existing CustomLog directive;
# do not create a second log that exposes signed query strings or auth headers.
```

Use your existing `proxy_fcgi`/PHP configuration and modules. Alias routing must precede any generic site catch-all. Do not add a new vhost, port, DNS name, certificate or broad file alias. Verify URI preservation (`REQUEST_URI`) and Authorization forwarding on the deployed server.

## Protocol and provider workflow

TV JSON requests use `Authorization: Bearer <existing permanent token>`, `X-Nyama-Activation-ID: <existing installation ID>`, `X-Nyama-Subtitle-Provider: subsource`. No app-supplied password, SubSource secret or arbitrary outbound URL. Search/select require the private authoritative adapter on every request; signed file fetch is the only unauthenticated operation.

- `GET /api/subtitles/search?imdb_id=tt1375666&language=bg&page=1`
- Exact episode adds `season=2&episode=3`. Both are required together; generic series cannot guess an episode.
- Server uses SubSource `GET /movies/search?searchType=imdb&imdb=tt...` (and season for TV), verifies one matching actual `movieId`, then verifies `/movies/{id}`. It requires exact IMDb, media type and season, not loose title similarity. An ambiguous internal ID set is rejected.
- `GET /subtitles?movieId=<internal-id>&language=bulgarian&page=...&limit=20&sort=popular`, one requested page only. At most 20 explicit pages. Checks actual language, IDs, release metadata and episode markers. A release containing another episode is excluded. A matching-season pack can be listed, but its file must still prove the exact episode on selection. Actual uploader **ID** is shown as `#id` when no name is provided; vote counts are not fabricated as star ratings. No inferred FPS, format or release labels.
- Response includes `ok`, `provider=subsource`, exact `media`, `page`, `next_page`, `results[]` with actual `subtitle_id`, `release`, `language=bg`, optional real `fps`, `downloads`, `uploader`. IDs belong to SubSource subtitles, never IMDb or an OpenSubtitles file.
- `POST /api/subtitles/select` with exact media, `subtitle_id`, optional `offset_seconds`. Only a file from that account's recent exact-media search or its authorized cached original is allowed. Detail `/subtitles/{id}` is verified before authenticated `/subtitles/{id}/download`. The API key is sent only to `api.subsource.net`; it is stripped from approved cross-host redirects. No arbitrary client URL fetch.
- ZIP is not assumed to be SRT: compressed limit 8 MiB, total advertised uncompressed 32 MiB, maximum 200 entries, 5 path components, ratio ≤100:1 and selected file ≤1 MiB. Absolute/traversal/backslash/control paths, links/special files, encryption, empty files, executables, nested archives, HTML and unsupported/image formats are rejected. Nothing is extracted to a public root. Only implemented `.srt`/`.vtt` text is converted. UTF-8, BOM UTF-16 and Windows-1251 preserve Cyrillic. Cue bounds: 6,000 cues, 8,192 characters/cue, valid ordered starts and timestamps ≤72 hours.
- For TV ZIPs, exact `S02E03`/`2x03` markers in paths must uniquely match; conflicting parent/filename markers, multiple matches, unknown episode filenames and multi-episode files are rejected. No secondary archive-file picker is implemented; ambiguity is an explicit rejection. A movie ZIP must have exactly one supported caption file. Mixed/unsupported extra files fail conservatively.
- Selected response includes exact media, actual `subtitle_id`, `offset_seconds`, actual release, Unix `expires`, signed `https://nyama.fun/api/subtitles/file/<opaque>.vtt?expires=...&sig=...`. It is directly fetchable without auth by VidAPI, returns UTF-8 `text/vtt`, `nosniff`, `no-store`. Signature covers provider version/opaque ID/expiry; no arbitrary path is accepted. TTL 15 minutes. Anyone holding the URL can fetch it until expiry, including after subsequent account revocation; it grants no streaming access. Do not log/share signed URLs.
- Timing −10…+10 seconds in 0.5 steps transforms the immutable cached original cues; it spends no new upstream download while cached. Original cache/membership TTL 30 minutes, search cache 5 minutes. Later cache expiry/eviction can require another explicit search/download. Logical cache ≤64 MiB/1,000 entries; SQLite may retain freed pages (schedule idle maintenance if needed). No filesystem subtitle paths enter responses.
- SubSource sliding global caps: 60 requests/60 seconds, 1,800/hour, 7,200/day across all API operations. Redirects back to its API also count. Cached operations do not call the provider. HTTP 429 honors bounded Retry-After (seconds/date); zero-remaining/reset headers also suppress new requests. No automatic retries. Per-account searches 15/minute, selections 10/minute (global 100/minute); edge limits cover unauthenticated traffic. A nonblocking download lock prevents duplicate simultaneous archive downloads. These caps do not override a plan's actual rights or quotas.

Outbound transport verifies TLS, exact allowed host, public DNS pinned for the transfer, bounded bodies/headers, and timeouts. It refuses a configured HTTPS/ALL forward proxy because that would perform independent DNS; it never silently disables a proxy to bypass network policy. Use approved direct server egress, or review equivalent SSRF protections for an approved gateway. No certificate-verification bypass.

## Smoke tests and health checks

Before deployment, offline fixture tests require PHP extensions above:

```sh
php -l server/subsource/public/index.php
php -l server/subsource/Service.php
php server/subsource/tests/run.php
php server/opensubtitles/tests/run.php   # regression, no live credentials
```

On the existing server with privately loaded environment, run `php server/subsource/probe.php tt1375666`. For exact TV, `php server/subsource/probe.php tt0944947 2 3`. Output contains only safe status, actual result IDs/releases/count and pagination, never the key or signed URL. An optional `--subtitle=<ID_FROM_SEARCH>` argument selects that explicitly chosen subtitle ID and verifies ZIP processing; do not run indiscriminate downloads. The probe bypasses app entitlement only as a **nonpublic operator CLI tool**, not an HTTP route; actual authenticated HTTP checks below remain mandatory.

HTTP smoke checks must verify:
1. Missing/revoked activation →401/403, expired/suspended/unverified entitlement →403, private loader absent →rejection. No token clearing or subscription/device mutations.
2. Valid test activation search → actual exact Bulgarian releases; explicit More → one next page, duplicates removed.
3. Exact selection → signed `.vtt` URL; externally fetch it **without auth/cookies**, valid TLS, correct MIME/Cyrillic/cues. Expired/tampered signature fails. Wrong media/episode/file ID fails.
4. Physical TV + VidAPI: explicit selection, pause/known resume, unknown-position confirmation, fullscreen DPAD menu, timing change, source fallback and episode switch. Observe video continuity under API/ZIP/429 failures. Reconfirm mobile UI, live TV, favorites and activation behavior.

Supply curl authorization headers through a protected configuration file, not shell history/output. Do not add a public health endpoint exposing secrets. Existing site HTTP health plus `php -l`, the safe CLI probe and authorized test-entitlement requests provide sufficient checks.

## Rollback

Keep the old release and routing include before changing anything. To disable custom search immediately, set `SUBSOURCE_TEMP_HOSTING_APPROVED=0` in the protected environment and reload/restart the existing PHP service as appropriate. TV continues VidAPI automatic Bulgarian configuration. To roll back code, repoint `/opt/nyama-subtitles/current` to the known prior release (or restore the prior handler path in the small include), validate configuration, then operator-reload the existing PHP/web server. Restore the prior private environment configuration separately; never copy secrets into the release tree. No SQL rollback or DNS change. Existing signed files can expire naturally; old signed URLs may become unavailable after rollback, and viewers can choose Automatic Bulgarian.

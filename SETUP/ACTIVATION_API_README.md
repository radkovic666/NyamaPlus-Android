# Nyama+ Android activation — nyama.fun server patch

This is a **new server-only API** based on the current `nyama.fun` registration and playlist backend. The Android version bump, QR UI, device-token encryption, integration with `Config`, and Gradle builds are **separate Codex work on the GitHub `test` branch**. Do not install this patch in the Android repo. Do not change `main`.

## Scope

Added files only:

- `activation_core.php`: validation, ABV/PHPMailer one-time codes, database-backed device tokens, account checks and configuration.
- `api/activation.php`: small POST JSON controller.
- `api/.htaccess`: disable directory listing and forward bearer Authorization to PHP on Apache.
- `activation_schema.sql`: three independent activation tables; existing registration and playlist tables are left unchanged.
- This README.

Existing site functionality (`register_now.php`, `index.php`, `auth_playlist.php`, Stripe, channels, APK downloads) is untouched.

## Install on the existing PHP site

1. **Back up** the current web directory and database. Test on staging first.
2. Ensure the **previous registration/SMTP patch** is deployed and functional. `registration_profiles` must exist. `registration_core.php` and `PHPMailer/src` must already be present.
3. Run `activation_schema.sql` **once** in the same MySQL/MariaDB DB configured in `config.php` (e.g. hosting phpMyAdmin > Import).
4. Upload `activation_core.php` to the directory containing `index.php`, plus the `api/` directory. Leave every existing file unchanged. The SQL and README are not required to be publicly served after applying the migration; preferably keep them off the public web root.
5. Keep `registration_settings.local.php` **private** (already used by registration). The key `registration_pepper` must be one fixed, random **64-character hexadecimal** value. Configure the ABV mailbox `nyamafun@abv.bg` and its password securely there. Never embed passwords or that key in APKs or source control.
6. Use **valid HTTPS** on `nyama.fun`. On Apache behind a reverse proxy, configure the proxy to set trusted `HTTPS=on` at the backend; the API deliberately does **not** trust client-provided `X-Forwarded-Proto`. Nginx/PHP-FPM should pass the `Authorization` header and the origin HTTPS scheme as trusted FastCGI params.
7. Test a valid registered, email-verified account that has **active trial/paid subscription** or VIP status.

There are no root `.htaccess`, homepage, database `users`, payment, or playlist modifications in this patch.

### Prerequisites

PHP **8.1+** (`array_is_list`), extensions `pdo_mysql`, `openssl`, and a working MySQL/MariaDB connection and PHPMailer installation. The current shell's PHP build may not include PDO MySQL; running `php -l` alone is not an integration test. SMTP credentials remain server-side.

### Endpoints

All requests: `POST https://nyama.fun/api/activation.php?action=...`; header `Content-Type: application/json`, JSON response, no browser cookies or API keys. All responses use `Cache-Control: no-store`. The Android app must validate TLS certificates, never bypass HTTPS/certificate errors, and never log tokens, verification codes, or private playlist URLs.

#### 1. Request email code

`?action=request-code`

```json
{"email":"customer@example.com","device_id":"<64 lowercase hex characters>"}
```

Success HTTP 200:

```json
{"ok":true,"challenge_id":"<32 lowercase hex characters>","expires_in":600,"resend_after":60}
```

A **six-digit one-time code**, valid 10 minutes, is sent via PHPMailer SMTP from `nyamafun@abv.bg`. The code is **not returned through the API**. A fresh request invalidates any previous open code for this user. Code hashes are stored with HMAC SHA-256 and the existing server-only registration pepper; plaintext codes are not stored. The response deliberately distinguishes `account_not_found` and `inactive_account` as required; this has an account-enumeration trade-off, mitigated with throttling (see below).

#### 2. Verify email code and activate this device

`?action=verify-code`

```json
{"challenge_id":"<challenge_id>","device_id":"<same 64 hex ID>","code":"012345"}
```

Success HTTP 200 (values are illustrative placeholders):

```json
{
  "ok": true,
  "activation_token": "<random unique 64-hex bearer token>",
  "token_expires_in": 7776000,
  "account": {"username":"customer","client_number":"123456","status":"active"},
  "config": {
    "playlist_url": "https://nyama.fun/playlist.m3u?username=<xtream_username>&password=<xtream_access_credential>",
    "epg_url":"https://is.gd/fullepg",
    "pukanki_base_url":"https://pukanki.fun",
    "username":"customer",
    "client_number":"123456",
    "account_status":"active",
    "subscription_expires_at":"2026-10-26 17:10:43",
    "vip":false,
    "subscription_payment_url":"https://nyama.fun/subscription_payment.php?token=<billing_token>"
  }
}
```

The `playlist_url` carries the existing, separate Xtream playlist access credential. **This is not the user's login password** and should only be returned to a successfully verified device. The Android app must treat that URL as sensitive and persist it using the existing Config system, as explicitly requested; do not log or share it. No DB, SMTP, pepper or Stripe credentials are returned.

**App steps:** generate and persist `device_id` once per installation using `SecureRandom` (32 bytes => lowercase 64-hex), then display request-code and code-entry screens. After successful verification, store `activation_token` in Keystore-backed encrypted app storage, save the three URL values using `Config.saveUrls(playlist_url, epg_url)` and `Config.setPukankiBaseUrl(pukanki_base_url)`, persist username/client number as necessary, satisfy the existing protected administrator-PIN requirement **without embedding a hardcoded PIN** or exposing URL setup to normal users, and navigate straight to the normal app without a restart.

Android app upgrades: if an existing installation has a working `Config`, **bypass new enrollment and preserve its settings**. Token flows apply to fresh activations; do not force legacy installs to enroll. On an activation failure, do not overwrite working legacy settings.

#### 3. Check activation and re-provision config on future launches

`?action=config`

Headers:

```text
Authorization: Bearer <64-hex activation_token>
Content-Type: application/json
```

Body:

```json
{"device_id":"<same 64 hex ID>"}
```

Success HTTP 200 returns `{"ok":true,"account":{...},"config":{...}}`. This call checks the token hash and device ID hash, token expiry/revocation, account status, subscription/trial validity, email verification, and underlying stream authorization. Use it at app launch and when refreshing app authorization; **only trust the token when it succeeds**. If it returns `activation_invalid` (HTTP 401), clear the saved activation token and show the QR screen. For `inactive_account` (HTTP 403), show the inactive/renewal state and offer payment/retry. For HTTP 503 or network errors, offer retry **without deleting the valid token or erasing existing config**.

#### 4. Revoke own device on sign-out (optional)

`?action=revoke` with the same bearer header/body as `config`. Returns `{"ok":true,"revoked":true}`. An administrator can revoke a device in the DB with `UPDATE nyama_activation_devices SET revoked_at=NOW() WHERE user_id=<id> AND device_hash='<stored-device-hash>';`. Repeated abuse requests can be blocked at edge/WAF level.

### Stable API error codes

| HTTP | JSON `error` | Meaning / app action |
|---|---|---|
| 400 | `invalid_input`, `invalid_device_id` | Correct input |
| 404 | `account_not_found` | Email isn't registered; go to registration QR |
| 403 | `email_unverified` | Confirm original web signup email first |
| 403 | `inactive_account` | Suspended, inactive, expired trial/subscription, or stream disabled |
| 400 | `wrong_code` | Incorrect code; allow retry, max 5 attempts per challenge |
| 400 | `code_expired` | Missing/used/expired code or attempts exhausted; request a new code |
| 403 | `device_limit_reached` | Existing maximum number of devices already activated |
| 401 | `activation_invalid` | Token missing, expired, revoked or belongs to another device; re-enroll |
| 429 | `rate_limited` | Wait before retrying |
| 503 | `mail_unavailable` | SMTP unavailable, retry later |
| 503 | `server_unavailable` | Server/DB/setup unavailable, retry later |
| 403 | `https_required` | HTTPS backend configuration error |

The client should distinguish network timeouts/DNS/TLS failures locally as `network_unavailable`; they won't carry a JSON error.

### Token and abuse protections

- Random 256-bit token issued **only after** email-code verification; HMAC-SHA-256 hash stored in DB, **never the plaintext token**.
- Token is bound to the **account + installation-generated 256-bit device ID** (also HMAC hashed in DB). Both are checked every launch. Server-side revocation and 90-day token expiry.
- This is a **per-installation bearer-token binding**, not hardware attestation. A copied token *and* copied device ID could be reused. For stronger clone resistance, a future update should include Android Keystore hardware-backed asymmetric challenge signing. Keeping token encrypted with Android Keystore is essential for this version.
- Maximum active API devices is checked against `users.max_devices`; separately, the existing playlist `device_bindings` rules still apply, unchanged.
- Six-digit code expires after 10 minutes, maximum 5 verification attempts; new requests invalidate previous unused codes.
- Limit email-code requests to **5/email/24 h**, **15/IP/h** and **one/email/minute**. IP and email in throttle logs are HMAC hashes. Do not disable rate limits for production. Optional web-server rate limits are recommended too.
- Periodically purge old rows, for example with a scheduled job:

```sql
DELETE FROM nyama_activation_requests WHERE created_at < NOW() - INTERVAL 8 DAY;
DELETE FROM nyama_activation_challenges WHERE expires_at < NOW() - INTERVAL 8 DAY;
```

### API testing checklist (staging)

- Install SQL + validate ABV SMTP manually by requesting a code and checking mailbox.
- Known active email receives a code; unknown email gives `account_not_found`.
- Correct code activates and returns username, client number, config, token.
- Wrong code gives `wrong_code`; expired/reused code gives `code_expired`; more than five tries blocked.
- Registration email unverified gives `email_unverified`.
- Suspended or expired trial/subscription gives `inactive_account`.
- Valid bearer + same device ID returns config; different device ID/revoked/expired token gives `activation_invalid`.
- Per-account device limits and concurrent activations respected.
- Network/SMTP/DB failures do not leak sensitive information in JSON response.
- Existing `auth_playlist.php` and `register_now.php` behavior unchanged.
- Android fresh-install TV remote navigation, mobile touch, and in-place upgrade of configured installations: **Codex/Android testing**, not runnable by this server patch.

### Existing app state in GitHub `test` (read-only inspection)

`AGENTS.md` requires starting on `test`, not changing `main`, and incrementing `versionName` and `versionCode`. At inspection, `app/build.gradle` declared **versionName 1.0.20 / versionCode 22**. Codex should choose the next numbers based on its current checkout (typically 1.0.21 / 23) and run `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug` in the Android workspace. **This server ZIP does not contain an Android patch or a built APK.**

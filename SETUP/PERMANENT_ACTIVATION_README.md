# Nyama.fun — permanent device activation (server-side only)

This targeted patch changes **one** website file, `activation_core.php`, and adds **one** SQL migration. It builds on the **activation renewal** patch (with `access_status: subscription_expired`). It is **not** an Android/Gradle patch; do not add PHP files to the Android GitHub repository.

## What changes

- Device activation tokens no longer have an automatic 90-day expiration. The `nyama_activation_devices.expires_at` column is nullable; `NULL` means non-expiring. Token values remain 256-bit random values; the database stores only their keyed hashes, not plaintext tokens.
- Existing device activations that have **not expired and have not been revoked** are migrated to `expires_at = NULL`. Expired or revoked tokens are **not** reactivated.
- API `verify-code` returns `token_expires_in: null` and `token_expires_at: null` for permanent device activations. Client code must understand `null` as “no scheduled token expiration”, not “expired”.
- API `config` and `revoke` accept valid unrevoked tokens when `expires_at IS NULL`, and still reject any legacy expired token (`expires_at <= NOW()`).
- Device limits count permanent tokens until the user/admin revokes them, even if the app is uninstalled from a lost device.
- A customer's **14-day trial, paid subscription validity, VIP entitlement, payment URL, suspended status and account/device revocation** are still enforced on every `/config` call. Email verification OTPs still expire in 10 minutes. Existing registration and playlists are untouched.

## Installation — order matters

1. **Back up** the website database and live `activation_core.php`. Confirm the earlier activation API, SQL tables and **renewal patch** have already been installed.
2. In phpMyAdmin select the database used by `nyama.fun/config.php`; import `SETUP/permanent_activation_migration.sql` **first**. It changes only the `nyama_activation_devices` table.
3. Upload `nyama.fun/activation_core.php` to your website root (alongside `registration_core.php`), replacing that file only. Do **not** replace `api/activation.php`, `index.php`, `register_now.php`, or any other file.
4. Confirm a previously activated device remains logged in; make a new test activation; test active trial, expired trial/payment page, VIP, suspended account, and revoked token. Also test a 2nd device against `max_devices`.
5. Give Codex this document so the APK treats `token_expires_in: null` as permanent. It must securely retain the token in Android Keystore-backed storage, maintain a stable installation ID across app upgrades, and call `/config` to enforce subscription status.

**Failure warning:** If you upload the PHP file before the SQL migration, new activation inserts that write `expires_at=NULL` may fail on the old `NOT NULL` column. Deploy SQL first.

**Revocation:** Permanent means no *automatic* token expiration, **not** that a token can never be revoked. `POST /api/activation.php?action=revoke` still revokes a token, and an administrator can revoke a lost-device activation by setting its `revoked_at` timestamp in `nyama_activation_devices` (after identifying the correct user/device). Do not delete all token rows or alter the registration pepper: changing the pepper invalidates every stored token hash and device hash.

**Device limit consideration:** Since activations do not age out automatically, losing or uninstalling an activated device may consume a device slot until it is revoked. Before broad public release, consider adding a protected admin "Manage devices / Revoke" feature; **this patch does not modify the admin UI**.

**Security:** Non-expiring bearer tokens remain security-sensitive credentials. Never expose them in logs, URLs, source code, or public repositories. Always validate server-side revocation and current entitlement; keep token storage encrypted in the APK, and prefer rate limiting on the activation endpoints. Account credentials and the `registration_pepper` must remain server-only.

## API contract differences

Endpoint paths and request formats are unchanged:

- `POST /api/activation.php?action=request-code` — same 10-minute email code, same rate limits.
- `POST /api/activation.php?action=verify-code` — same code verification and token issuance. Response now includes `"token_expires_in": null, "token_expires_at": null` (instead of 7,776,000 seconds).
- `POST /api/activation.php?action=config` — same `active` and `subscription_expired` states; checks the permanent token and account status on each request.
- `POST /api/activation.php?action=revoke` — same revocation behavior.

The `subscription_expired` response still omits usable stream credentials. After successful renewal, the *same* activation token can retrieve the active configuration. No APK change to the request URL is needed.

## Verification queries (optional, read-only)

```sql
-- See how many active permanent devices have been enrolled:
SELECT COUNT(*) AS permanent_devices
FROM nyama_activation_devices
WHERE expires_at IS NULL AND revoked_at IS NULL;

-- Check for already-expired legacy tokens (these must remain invalid):
SELECT COUNT(*) AS expired_legacy_devices
FROM nyama_activation_devices
WHERE expires_at IS NOT NULL AND expires_at <= NOW();
```

## Tests

Run `php -l activation_core.php` after upload if PHP CLI is available. MySQL schema migration, ABV SMTP delivery and Android device storage must be validated on your hosting/device; local PHP lint is not sufficient to verify them.

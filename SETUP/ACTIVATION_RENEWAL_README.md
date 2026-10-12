# Nyama.fun activation renewal patch (server only)

## Scope and prerequisites

Changes **only** the existing `nyama.fun/activation_core.php` file from the earlier `nyama_fun_android_activation_API_patch.zip`. All other site files and database tables remain unchanged. This file is NOT for the Android GitHub repository. Deploy only after the existing activation API (`api/activation.php`, the three SQL tables, PHP SMTP, and registration) is installed and functional. Back up your server copy of `activation_core.php` first and use staging before production.

Copy `nyama.fun/activation_core.php` into the website root next to `registration_core.php`, replacing only that file. No SQL migration or settings changes are needed. Existing `api/activation.php` will wrap the returned values with `"ok":true` as before.

## New server response — expired NORMAL subscription

When an otherwise active user has reached `paid_until`, with `subscription_type='subscription'` and a valid account-specific payment token, the API gives a **renewal-only state**, instead of generic `inactive_account`.

- `POST /api/activation.php?action=request-code`: still permits email OTP delivery (provided registration email verified, account active, stream enabled, long-subscription payment token present). The 6-digit verification code and existing rate limits are unchanged.
- `POST /api/activation.php?action=verify-code`: once the OTP is valid, returns HTTP 200 with `"ok":true`, `"access_status":"subscription_expired"`, `"activation_token"`, `"token_expires_in"`, `"account"`, and `"renewal"`. There is **NO `config`** (no playlist credentials) in this state.
- `POST /api/activation.php?action=config`: an existing valid device token returns HTTP 200 with `"ok":true`, `"access_status":"subscription_expired"`, `"account"`, and `"renewal"`. The device token remains valid and is **not deleted** merely because payment is due. There is **NO `config`** in this state.
- Upon payment processing / admin renewal / VIP upgrade, the existing `/config` endpoint will automatically return normal `"access_status":"active"` and the original `"config"` object again. No re-registration or fresh email OTP is required while the token remains valid.

Example renewal response body (`/config`, illustrative):

```json
{
  "ok": true,
  "access_status": "subscription_expired",
  "account": {"username":"sample","client_number":"123456","status":"active"},
  "renewal": {
    "subscription_payment_url":"https://nyama.fun/subscription_payment.php?token=<account billing token>",
    "username":"sample",
    "client_number":"123456",
    "subscription_expires_at":"2026-10-26 17:10:43"
  }
}
```

For **active** accounts, the previous response fields are unchanged: `"ok":true`, `"account"`, `"config"`, plus a new additive `"access_status":"active"`.

## What is not allowed

Do not mistake `"account.status":"active"` for current playback entitlement. Playback is allowed **only** with `access_status == "active"` and a valid returned `config`. In renewal state, the server MUST NOT send usable playback credentials. The native app should render its own payment QR from the returned payment URL on Android TV, and open the same URL on mobile; the shared `nopaid.m3u` is not required for this app state.

The renewal URL is **account-specific**. Never generate it by guessing tokens or replacing it with an hourly payment page. Treat it as private: no logging, telemetry, publicly hosted QR image service, or query-string logging.

Suspended users, users with unverified email, disabled streams, accounts lacking stream credentials, hourly accounts, and accounts without a billing token keep the original error behavior (`inactive_account`, `email_unverified`, etc.). The 90-day token expiry, device limits, revocation, rate limits, and six-digit OTP constraints are unchanged.

## Client integration note for Codex

Use the original `SETUP/ACTIVATION_API_README.md` for `request-code`, `verify-code`, `config` and `revoke` request formats and all authentication details. Additionally handle `access_status` (`active` / `subscription_expired`). On expired state, show native Bulgarian renewal UI with the account-specific payment URL/QR, with a manual `Проверка на плащането` action that calls `/config` with the existing token. Never delete a valid token or require OTP again because the subscription expired. A true `activation_invalid` (HTTP 401) remains a reason to clear the activation token and re-enroll. `inactive_account` (HTTP 403) remains a blocked/suspended state; do not invent a payment URL.

If this renewal patch has NOT been deployed, the old API still returns `inactive_account` (403) for expired customers. The app must not pretend it received a renewal link in that state. It can use a previously securely cached billing URL for a clearly identified expired subscription, where available, but should not infer that every `inactive_account` is eligible for renewal.

## Live validation

Test (1) active trial, (2) expired ordinary subscription, (3) payment transitions expired -> active, (4) VIP, (5) suspended account, (6) hourly account, (7) revoked/expired device token, (8) OTP on expired account, (9) account-specific subscription QR. Requires production-like MySQL and working ABV SMTP. Local PHP syntax checks are **not** a live integration test.

# Nyama+ experimental activation patch 1.0.21 (code 23)

Based on GitHub `test` commit `99138e8dd5d3e40baa11cc3e3985429dd9c495dd`.
Current local branch: `experimental/permanent-activation`. No commit, push, merge or
change to the stable `main` reference was performed. Both the original `work`
reference and `origin/main` remain `cb35cc05e7e1572d42c92e06e5293dcad8f9b718`.
Actual starting version was 1.0.20 / 22; the patch increments it to 1.0.21 / 23.

## Customer flow and security

Fresh installations show a native Bulgarian/English welcome screen with a locally
encoded `https://nyama.fun/register_now.php` QR. Phones can also open registration
in their browser. No technical URLs, website password or device-mode selector are
shown. Existing `TvDeviceGuard.hardwareLooksTvLike()` chooses TV/phone mode;
existing explicit device preferences take priority. The website alone owns
registration, email confirmation, trial start, device limits and subscription rules.

“I am registered already” opens email entry, then six-digit OTP entry. Requests use
POST JSON to `https://nyama.fun/api/activation.php?action=request-code` and
`?action=verify-code`, with normal TLS verification, no redirects, timeouts and
bounded response bodies. Request cooldown and challenge lifetime use server
fields; the server enforces five attempts/single use. Codes and challenges stay in
memory, are not saved to instance-state Bundles, and are never logged. Screen
rotation keeps the in-memory activation flow; losing the process loses the
challenge and requires a new code after the server's enforced cooldown.

A `SecureRandom` 32-byte installation ID is encoded as 64 lowercase hex characters.
It is separate from the existing Live TV streaming identity. The ID and activation
token are one AES-256-GCM encrypted record in `getNoBackupFilesDir()`, protected by
an Android Keystore key and atomic file writes. The existing manifest also disables
Android backup. A separate managed-mode marker prevents lost secure data from
falling back to cached legacy configuration. Missing/unusable secure credentials
require email verification; ordinary network errors retain the ID and token.

There is no local token expiry, 90-day countdown, rotation or token-renewal task.
`token_expires_in: null` and `token_expires_at: null` are accepted as permanent.
The APK cannot make an old server token permanent: the permanent server patch
and nullable expiry migration must actually be deployed by the backend owner.

Managed installations call bearer-authenticated `?action=config` before viewing
on subsequent launches/returns from background. A process-only authorization gate
also protects restored content activities and rejects cached playlist/PIN state as
entitlement. While continuously foregrounded, authorization is checked every five
minutes; navigation does not postpone that check. Thus remote changes are detected
at the next check, not pushed instantaneously. Background playlist/EPG workers skip
managed installations; existing foreground refresh stays available after successful
authorization. Legacy worker behavior stays unchanged.

Only explicit `access_status: active` plus a valid HTTPS config grants viewing.
Provisioning uses `Config.saveUrls()` and `Config.setPukankiBaseUrl()` and records
account username/client number. The server supplies playlist credentials; the APK
never manufactures them. Active trial, paid and VIP accounts use this same rule.

`subscription_expired` keeps the same token, blocks playback, and shows a native
renewal screen. TV renders the exact private `subscription_payment_url` locally as
a QR; mobile opens it in the browser. The URL must be the HTTPS Nyama.fun long-term
subscription page with a supplied token, never an hourly/fabricated link. Billing
URLs are not printed as text, logged or sent to external QR services. “Check
payment” calls `config` using the original token. Only fresh active configuration
returns immediately to normal viewing. Returning from a browser alone grants
nothing. Expired accounts can enroll into this renewal-only state.

`inactive_account` is blocked, never inferred to mean payment is due.
`activation_invalid` clears only the activation credential and reopens enrollment.
Suspension, revocation, network/DNS/TLS errors, cooldowns and server errors have
friendly Bulgarian/English handling. Network failures never authorize offline
viewing or erase an otherwise valid credential. No sign-out action was added, so
no revoke calls are made; the existing optional `?action=revoke` protocol is
unchanged and reserved for an explicit future deactivation action.

Configured legacy installations bypass enrollment and keep their original URLs,
PIN, favorites, last channel and device preferences. Managed playback readiness
can be satisfied without opening the technical setup form. Administrator settings
still require a PIN. If none exists, the authenticated device owner explicitly
chooses and confirms at least six digits from Settings, then separately verifies
that PIN before the protected form opens. No universal PIN is supplied, and
existing PINs cannot be overwritten by activation.

## Files

Created:

- `app/src/main/java/fun/nyama/tv/ActivationAccess.java`: foreground authorization gate/checks.
- `ActivationActivity.java` in the same directory: welcome, email/OTP, retry and renewal UI.
- `ActivationApi.java`: HTTPS POST JSON transport.
- `ActivationController.java`: tested API/credential lifetime boundary.
- `ActivationResponse.java`: strict entitlement/config/payment response validation.
- `ActivationStore.java`: Keystore encryption, stable ID and permanent token persistence.
- `app/src/test/java/fun/nyama/tv/ActivationControllerTest.java`: mocked protocol tests.
- `app/src/test/java/fun/nyama/tv/ActivationUiTest.java`: simulated Android UI/compatibility tests.
- `SETUP/ACTIVATION_API_README.md`, `SETUP/ACTIVATION_RENEWAL_README.md`,
  `SETUP/PERMANENT_ACTIVATION_README.md`: exact user-supplied backend references;
  the permanent contract takes precedence over earlier expiry notes.
- This report.

Modified:

- `app/build.gradle`: version increment, local QR library ZXing, JUnit/JSON/Robolectric tests.
- `app/src/main/AndroidManifest.xml`: internal activation activity and rotation handling.
- `Config.java`: provisioned readiness and account metadata, without bypassing administrator PIN checks.
- `NyamaTvApp.java`: register foreground authorization tracking.
- `MainActivity.java`, `HomeActivity.java`, `CatalogActivity.java`,
  `MoviePlayerActivity.java`, `TitleDetailActivity.java`: authorization guards around existing content.
- `SettingsActivity.java`, `AdminSettingsActivity.java`: guarded owner PIN setup/access.
- `PlaylistRefreshWorker.java`, `EpgRefreshWorker.java`: prevent managed background work from using cached authorization.

All Java files above are under `app/src/main/java/fun/nyama/tv/` unless indicated.
No PHP, database schema, SMTP, Stripe or private server configuration was added.
No playback controls, player providers, M3U parser, streaming identity, channel
ordering, favorites implementation, movie catalog, EPG implementation or self-update
logic was rewritten. Actual playback regression verification remains pending.
The bootstrap-generated `gradle/wrapper/gradle-wrapper.jar` is a verified local
setup artifact, not part of the application patch; its SHA-256 matches the existing
wrapper script's expected value.

## Validation

Run in the actual cloud machine:

```bash
source /workspace/.android-setup/env.sh
cd /workspace/NyamaPlus-Android
bash gradlew test assembleDebug --no-daemon --max-workers=3
bash gradlew lint --no-daemon --max-workers=3
```

The wrapper is invoked through Bash because the checkout's script is not executable;
its tracked permissions remain unchanged. `test` and `assembleDebug` pass. There
are 40 executed tests: 19 mocked contract tests and 21 Robolectric simulated UI/state
tests, zero failures/errors/skips. Resource and Java compilation pass. Simulated
API-24 enrollment passes. APK metadata confirms package `fun.nyama.plus`, minSdk 24,
target/compile SDK 36, version 1.0.21 / 23; `apksigner verify` passes.

Lint fails with the same two errors captured before application changes:

- `CatalogActivity.java`: `ResourceType` for arithmetic on hardcoded radio-button IDs.
- `res/values/styles.xml:7`: `NewApi` for `windowLightNavigationBar` (API 27) with minSdk 24.

These unrelated pre-existing defects were neither suppressed nor changed. Lint
must be corrected in a separately scoped change before claiming all checks pass.

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug signed, not a production update).
SHA-256: `8986399188312d796ad69c074478919d27c9a1f13f1eeb0e152370dca33599c9`.

## Requested scenario coverage

“Mock” exercises synthetic API responses/credentials on the host JVM. “Simulated”
uses Robolectric, not hardware. Source inspection does not prove Keystore or reboot
behavior. No live OTPs were sent and no real account/payment state was changed.

| # | Scenario | Evidence / remaining verification |
|---|---|---|
| 1 | Fresh TV install | Simulated TV welcome and focus; real TV installation pending |
| 2 | Fresh phone install | Simulated phone welcome; real phone installation pending |
| 3 | Automatic detection | Simulated Leanback, touchscreen, generic box and explicit preferences |
| 4 | Registration QR | Simulated local QR decoded to exact registration URL |
| 5 | Registration focus/touch | Simulated focus, D-pad center and button click; physical remote/touch pending |
| 6 | Registered email | Mock request response; live email delivery pending |
| 7 | Unknown email | Mock account_not_found |
| 8 | Unverified email | Mock email_unverified |
| 9 | Correct code | Mock verify-code with leading-zero six-digit code |
| 10 | Wrong code | Mock wrong_code; server attempts enforcement pending live test |
| 11 | Expired code | Mock code_expired; UI uses server lifetime |
| 12 | Rate limiting | Mock rate_limited/cooldown response; server enforcement pending |
| 13 | New activation | Mock issued token and entitlement; real enrollment pending |
| 14 | Keystore storage | Source reviewed AES-GCM/non-backup/atomic writes; actual Keystore test pending |
| 15 | Restart same token | Mock recreated controller retains original token; actual process restart pending |
| 16 | Device reboot | Not run: requires Android device and enrolled test account |
| 17 | APK upgrade | Not run: requires same signing certificate and earlier installed APK |
| 18 | Null token expiry | Mock null expiry accepted with same token reused |
| 19 | No 90-day renewal | Source review and mock repeated config never rotate/save a new token |
| 20 | Active trial | Mock active entitlement |
| 21 | Expired trial | Mock expired entitlement, no playlist exposed |
| 22 | Expired paid subscription | Mock expired entitlement, same token retained |
| 23 | Personalized renewal URL | Mock exact URL preservation and rejection of unsafe/hourly links |
| 24 | TV payment QR | Simulated local QR decoded to exact private server URL |
| 25 | Mobile payment button | Simulated browser intent with exact server URL, no QR requirement |
| 26 | Payment verification | Mock config request; real payment processing pending |
| 27 | Viewing restored with same token | Mock expired -> active, one token save only; live transition pending |
| 28 | Renewal-only enrollment | Mock expired verify response stores token without granting playback |
| 29 | VIP | Mock explicit active entitlement |
| 30 | Suspended user | Mock inactive_account preserves token and blocks entitlement |
| 31 | Revoked token | Mock activation_invalid clears token only; real revocation pending |
| 32 | Device limit | Mock device_limit_reached; server concurrency/limits pending |
| 33 | Offline registration | Mock request errors; actual offline UI run pending |
| 34 | Offline later launch | Mock DNS/timeout/TLS failures retain token; simulated cached state denied |
| 35 | Server unavailable | Mock server_unavailable preserves credential; malformed responses fail closed |
| 36 | Legacy upgrade | Simulated existing configured data bypasses enrollment; actual APK upgrade pending |
| 37 | Settings/favorites preservation | Simulated URL/PIN/favorite/last-channel/device preference preservation |
| 38 | Administrator PIN | Simulated matching PIN setup, separate verification, wrong PIN and guarded form |
| 39 | TV D-pad/OK | Simulated D-pad center; physical controls pending |
| 40 | Phone touch | Simulated button click/browser intent; physical touchscreen pending |
| 41 | Live TV regression | Compilation and guarded startup checked; actual streams/controls/EPG pending |
| 42 | Movies/Series regression | Compilation and guarded entry checked; actual playback/search/navigation pending |

## Environment and remaining actions

Android SDK 36, build-tools 36.0.0, platform-tools, JDK 21 and checksum-verified
Gradle 9.6.0 are installed. Java source/target remains 17. Reusable setup is outside
the repository in `/workspace/.android-setup`; retained Gradle cache is under
`/workspace/.gradle`. Java uses the environment's supported HTTPS proxy and
system-managed trust store; no TLS/checksum verification was disabled. The install
script was rerun successfully with cached SDK/JDK dependencies and a successful
build. No live process must survive publication for the APK build workflow.

The environment draft saves the install script, start instructions, the test-base
repository SHA/path, and an additive `nyama.fun` network allowance. Existing package
manager network presets are retained. Saving does not publish or apply runtime
changes. Review/save environment settings and publish the prepared environment.
Fresh-task restoration has not been independently verified.

A read-only HEAD probe of the activation endpoint was denied by the current proxy
(`CONNECT 403`, curl exit 56), before reaching the backend. The new network draft
has not been applied to this instance. Once applied, retry that read-only check.
It would still not establish SMTP, permanent migration, actual device limits or
payments. Confirm those server deployments, then validate on controlled phone/TV
devices with a staging account. This machine has no `/dev/kvm` or Android device;
Keystore, reboot, real APK upgrade and real playback have not been verified.

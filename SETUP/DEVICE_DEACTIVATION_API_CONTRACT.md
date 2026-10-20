# Atomic current-device deactivation

Both existing identities remain unchanged: `ActivationStore.deviceId()` is the 64-hex activation installation ID; `DeviceIdentity.deviceHeaderValue(context)` is the 32-hex playlist installation ID. Tokens remain in the existing secure store. Playlist User-Agent, URLs, fingerprint calculation, slot limits and the 30-minute Android refresh schedule remain unchanged.

## Capability and request

The managed-only Settings button requires BOTH strict JSON booleans in the existing authenticated authorization response:

```json
{"capabilities":{"revoke_current_device":true,"atomic_device_deactivation":true}}
```

Missing, false or coerced string/number values keep it disabled. The second flag distinguishes the complete shared server operation from the earlier API-only patch. There is no extra config request. Legacy/manual installations have no Deactivate section.

After the existing localized confirmation, use ActivationApi's HTTPS transport:

- POST `https://nyama.fun/api/activation.php?action=revoke`
- `Authorization: Bearer <current secure activation token>`
- JSON content type and accept headers.
- Body: `{"device_id":"<existing 64-hex activation ID>"}` only.

The APK sends no passwords, account IDs or binding IDs. The server rejects extra selectors, derives the account and activation from the token/device hash pair, and revalidates that pair under its account lock. Customer pages retain session authentication/CSRF; admin removal requires its authenticated session, CSRF and an exact binding row ID, never a reusable slot number.

A complete response requires both strict booleans, emitted only after commit:

```json
{"ok":true,"revoked":true,"playlist_binding_released":true}
```

## Explicit relationship and shared transaction

Reuse `nyama_device_links`: `activation_id` uniquely identifies the activation; nullable `binding_id` uniquely points to the same account's exact `device_bindings.id`; `(user_id, playlist_device_id)` is unique. The additive migration adds `device_bindings.playlist_device_id` and `nyama_device_links.deactivated_at`, plus unique constraints. All four state tables must use InnoDB and `users.device_count` must exist before support is advertised.

The ordinary authenticated M3U request records the existing `X-Nyama-Device-ID` header on its existing binding. Bearer-authenticated `device-link` matches that persisted ID within the same account and saves the exact binding ID. It cannot create a binding. Missing observations return `awaiting_playlist`; Android retries after an ordinary successful playlist refresh. No M3U download is triggered for linking. Later removal uses saved IDs, never IP, UA, username, model or last-seen inference.

One server operation, `nyama_device_deactivate`, serves APK revoke, `register_now.php`, admin single removal, admin hourly-plan cleanup and `my_devices.php` through its shared helper. It locks the account, verifies ownership and linkage, revokes the exact activation, clears its link's binding pointer while retaining a completion receipt, deletes that exact binding and recalculates `users.device_count`. Required failures roll back every mutation. Savepoints protect nested admin transactions. Active activation counts query real non-revoked, non-expired rows, including permanent tokens. Explicit account-wide reset remains a separate authenticated selector of the same operation; it is never a fallback for a single removal.

An awaiting activation can be released without deleting a binding only when every remaining binding has an explicitly observed different identity, proving absence; unknown rows fail safely. A repeat with the still-current credential pair and completed receipt confirms success without changing other records. An old token replaced by re-enrollment cannot revoke the new token. A known historical half-revoke may finish releasing its explicitly linked binding; it cannot authorize playback or relink. Playlist admission uses the same account lock and rejects the exact linked revoked identity, preventing an in-flight/late refresh from recreating that slot. Successful re-enrollment uses the existing activation flow and can bind again normally.

Unlinked external Kodi/IPTV clients retain ordinary credential/fingerprint/slot admission without activation tokens. Their bindings can be removed normally when no unresolved active Nyama+ relationship makes the target uncertain. For historical rows with unknown identity and unmapped activations on the same account, single removal fails safely until a normal header observation plus authenticated device-link establishes the relationship, or an administrator explicitly reconciles it. The existing deliberate reset-all operation remains available; no single-device request invokes it.

## Local success and failure

The existing process-owned background request survives Settings recreation, rejects duplicate presses and pauses authorization checks. It waits for existing playlist I/O before revoke; new downloads wait until completion. Only a complete server reply permits a compare-and-clear of the same stored activation/token, provisioned URLs/account fields and cached playlist credentials. It preserves newer credentials, both installation identities, managed mode, favorites, PIN, language, theme, player/clock/text preferences and last channel. It denies activation/device linking, stops playback and opens ActivationActivity without requiring restart or reinstall.

`device_link_required`, `device_link_conflict`, `device_link_ambiguous`, `deactivation_unavailable`, network/server errors and incomplete replies preserve local token/config and report a safe retryable error. No IDs or credentials are shown. `activation_invalid` remains the existing explicit stale/revoked credential exception for local cleanup; it is not a new claim that the APK released a binding. The new server never uses this error to conceal an unresolved known half-revoke. Known expired credentials cannot confirm release and fail without mutation; account/admin recovery remains available. Subscription and account data, other devices, VIP/hourly restrictions and external client access are not changed by deactivation.

## Deployment and migration

Backend source is delivered separately in `NyamaPlus-atomic-device-deactivation-server-patch.zip`; no production PHP/configuration is embedded in this Android repository. Deploy all nine changed/new PHP files and the explicit `20261020_atomic_device_deactivation.sql` migration as one reviewed release. The archive README contains the ordered maintenance procedure, complete file list, isolated test runner and rollback limitations. This replaces the previous two-file API-only server patch.

Back up the database and source, pause writers, run the additive migration explicitly, deploy the complete PHP set, validate and then resume. Do not deploy only the endpoint or enable capability flags manually. Existing explicit mappings alone are backfilled; historical UA/IP guesses are forbidden. Duplicate, orphan or cross-account mappings stop migration for administrator reconciliation without deleting device data. DDL is not transactional in MariaDB; additions are rerunnable. No production deployment, real credentials, live API tests or physical-device tests were performed in this workspace.

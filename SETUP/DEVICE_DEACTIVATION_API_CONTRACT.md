# Current-device deactivation

The Android button is shown only for managed installations, at the end of Settings. It stays disabled until an authenticated normal `config` response includes the strict JSON boolean:

```json
{"capabilities":{"revoke_current_device":true}}
```

There is no extra configuration request for this feature. Old servers omit the capability, so the APK must not send their token-only revoke action as a complete deactivation.

After confirmation, use the existing ActivationApi HTTPS transport:

- POST `https://nyama.fun/api/activation.php?action=revoke`
- `Authorization: Bearer <current secure activation token>`
- Existing JSON content type and accept headers.
- Body: `{"device_id":"<existing 64-hex activation ID>"}`.
- No password, account identifier, IP, UA or client-selected binding ID.

A complete remote success requires both strict booleans:

```json
{"ok":true,"revoked":true,"playlist_binding_released":true}
```

The server must authenticate the token/activation ID and resolve the same account's verified `nyama_device_links.activation_id` → `binding_id`. It must reject missing/ambiguous/conflicting linkage instead of guessing. A transaction revokes that activation, releases that binding, clears its link's binding pointer and recalculates the account's binding count. It must preserve subscription/account data and all other devices. Only transaction-capable, compatible schemas should advertise support.

`device_link_required`, `device_link_conflict`, `device_link_ambiguous`, network/server errors and incomplete responses preserve local activation state and allow retry. There are no forced playlist downloads. `activation_invalid` is the explicit already-revoked exception: clear the stale local credential without claiming a new binding release.

The process-owned background request survives Settings recreation and rejects duplicate presses. Authorization checks are paused during the operation. Existing playlist I/O finishes before revoke; new downloads wait until completion so an in-flight M3U request cannot recreate the just-released slot. Success compares the stored activation ID/token before clearing, preserves newer credentials, denies activation/device linking, stops Live TV/VOD and opens a fresh ActivationActivity task. Managed mode and both installation identities remain. Provisioned URLs/account fields and cached playlist credentials are removed; favorites, PIN and UI/player/clock preferences remain.

The supplied `nyamafun.zip` implementation only revoked the activation row. A separate server patch package is required: modified `api/activation.php` plus new `activation_deactivation_core.php`. Deploy the helper first, then the endpoint, after reviewing/testing against the production schema. No server PHP or production configuration is embedded in this Android repository. Until the server advertises the capability, deactivation remains disabled.

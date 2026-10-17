# Nyama+ experimental patch 1.0.23 / code 25

Base: latest GitHub `test`, `35e30909c8d6c8f1ea61a5da6417e4675a2fff2a`,
version 1.0.22 / code 24. New version: 1.0.23 / code 25. Existing activation,
automatic QR pairing, permanent tokens, renewal and server-side device limits are
preserved. Only the requested timer, TV focus/controls and return-to-TV paths change.

## Subscription and cooldown presentation

`ActivationApi` reads actual numeric `retry_after` and `resend_after`, together with
the existing server Retry-After header. Non-secret process-only deadlines use
`SystemClock.elapsedRealtime()`, survive Activity recreation, and never grant
access. Email request, verification and QR generation actions are disabled during
their applicable cooldown. The Bulgarian message includes the exact `MM:SS` or
`HH:MM:SS` countdown. Ticks align with the retained deadline rather than restarting
a one-second phase on UI rebuilding. Missing duration does not create a guessed
60-second or three-second wait; retry enforcement stays on the server.

Settings shows the server's `subscription.status`, `phase`, `remaining_seconds` and
`vip`. It displays trial/paid days, an hours/minutes/seconds countdown near expiry,
VIP unlimited status without a fabricated expiry, and a clear unknown-duration
state. The documented `subscription_expires_at` value is displayed unchanged when
present in subscription/account/renewal/config data; unzoned dates are not assigned
an invented timezone. Local ticks make no HTTP requests and cannot extend or
expire entitlement. Permanent device activation is separate from subscription time.

Reopening Settings refreshes through the existing bearer-authorized POST
`https://nyama.fun/api/activation.php?action=config`, with the existing installation
ID. Concurrent authorization checks deduplicate, stale foreground-session results
are discarded, and accepted explicit server expiration routes to the existing
renewal screen using the already returned response. The permanent token remains
stored. Payment refresh accepts fresh server duration/configuration. No permanent
countdown overlay appears over playback.

## TV controls and returning to Live TV

UP, DOWN, MENU/INFO/SETTINGS open a native remote-focusable control strip. LEFT/RIGHT
move between controls in this mode; OK activates the selected action. DOWN or BACK
closes the strip without arming movie exit. Playback mode retains OK play/pause and
LEFT/RIGHT ten-second seek, using the existing accessible HTML5 operation or native
provider key fallback. The strip stays visible while being navigated and scrolls
horizontally at smaller resolutions. Focused buttons have a visible outline.

Device music volume and mute use supported Android AudioManager operations and are
offered only when volume is not fixed. These are device-volume operations, not an
invented provider command. The fullscreen control changes the native application
window's fullscreen/immersive state. It does not pretend to force inaccessible
cross-origin HTML fullscreen. Existing phone controls and layout remain unchanged.

A native subtitle selector appears only after actual runtime discovery of
accessible standard HTML5 subtitle/caption `textTracks`. It offers the actual track
labels plus Off. Same-origin frames are supported; protected cross-origin frame
contents remain inaccessible. Bulgarian language tracks are preferred when really
available. The old broad observer/timer that repeatedly called `play()` and clicked
arbitrary subtitle menus is removed from the TV helper: it could override a user's
pause. No quality or Cast button is fabricated. Quality/Cast and cross-origin
subtitle/settings control require a supported provider integration and remain
limitations with inaccessible media.

BACK/ESC outside controls preserves two distinct-press confirmation and duplicate/
held-key guards. Confirmed TV exit destroys the movie WebView/media before starting
Live TV with CLEAR_TOP/SINGLE_TOP, preventing duplicate Activity instances and movie/
TV audio overlap. MainActivity reuses the loaded channel when still authorized and
the playlist configuration is unchanged, without reloading M3U or reparsing EPG.
An idle retained player is prepared before resuming. If MainActivity/player was
lost, existing cached channel loading and `Config.lastChannelKey()` restore the
channel; the return path avoids forcing a startup playlist refresh. HOME behavior,
channel/favorite preferences and normal startup remain unchanged.

## TV appearance

Movie/series card focus and mouse hover use the exact opaque `UiTheme.accent()`
(default/orange/green/blue), with black or white title/year chosen by WCAG sRGB
contrast. A contrast border and existing scale/elevation distinguish focus. Normal
appearance returns on focus/hover exit, and recycled cards reset hover state. Posters,
phone cards, catalogue loading, filtering, search, pagination and layout are retained.

## Provider and episode limitations

Provider research attempted the official `https://vidapi.ru/api`, VAPLayer movie
embed, existing StreamIMDB movie embed and Pukanki metadata host through the supported
proxy. Each was denied before origin with `curl (56) CONNECT tunnel failed, response
403`. Domain additions are saved in the environment draft; they are not applied to
the current instance. Review/save environment settings and publish the environment
to make the requested network configuration active.

The request supplies VAPLayer movie `/embed/movie/{id}` and TV episode
`/embed/tv/{id}/{season}/{episode}` examples, `ds_lang=bg` and `primaryColor`, but these
could not be independently verified against live official documentation. No listing
endpoint, per-series episode schema, control command or player event payload was
invented. Existing Pukanki catalogue counts do not establish individual episode
availability; its per-title metadata was also inaccessible.

Consequently native season/episode selectors and a direct VAPLayer switch are
**not implemented**. Existing series playback remains available rather than showing
fabricated seasons/episodes. Tests verify missing metadata preserves Watch, exact
IMDb identity and phone layout, including unrelated returned IMDb/TMDB metadata.
The existing outer routes remain `https://streamimdb.ru/embed/movie/{imdb}` and
`https://streamimdb.ru/embed/tv/{imdb}`; no unverified episode route or subtitle
parameter is appended to this provider.

Code inspection shows first and subsequent opens construct the same StreamIMDB URL
and a new WebView, while cookies and DOM storage persist. No application branch
alternates providers. The user's screenshot confirms a preferred VidAPI-labelled
interface but not its actual host/redirect chain. The real first/second-opening
cause is **not established or fixed** without provider access and runtime evidence.
A host-only main-frame debug diagnostic helps that investigation without logging
private URLs. Working provider/session state is retained; there is no blind cookie
wipe, reload loop or protected-frame bypass. Once access is available, record final
main-frame/iframe origins on clean and repeated opens, verify the supported preferred
embed, and obtain accurate per-title episode records before changing the route.

## Files

Modified production/build files:

- `app/build.gradle`: version 1.0.23 / 25.
- `app/src/main/AndroidManifest.xml`: normal MODIFY_AUDIO_SETTINGS permission.
- `ActivationApi.java`, `ActivationActivity.java`: server cooldown parsing/UI/actions.
- `ActivationResponse.java`, `ActivationAccess.java`: subscription snapshot and existing config refresh.
- `Config.java`, `SettingsActivity.java`: account metadata and Settings-only presentation.
- `CatalogActivity.java`, `UiTheme.java`: TV focus/hover and readable accent colors.
- `MoviePlayerActivity.java`, `MainActivity.java`: supported controls and immediate retained Live TV return.

New helpers: `ActivationCooldowns.java`, `SubscriptionInfo.java`.
All named Java files above are in `app/src/main/java/fun/nyama/tv/`.

New tests under `app/src/test/java/fun/nyama/tv/`:
`ActivationCountdownTest`, `ActivationTimersUiTest`, `SubscriptionInfoTest`,
`SubscriptionSettingsTest`, `CatalogThemeTest`, `MoviePlayerControlsTest`,
`MainActivityRestorationTest`, `SeriesPlaybackFallbackTest`.
Existing `ActivationUiTest`/`PairingUiTest` reset process-only deadline state between
isolated tests. This report is also new. No provider database, PHP, new backend
endpoint, private credential, generated APK or unrelated source change is included.

## Validation and publication

Commands are invoked through Bash because the checkout's wrapper is not executable:

```bash
source /workspace/.android-setup/env.sh
bash gradlew test --no-daemon --max-workers=3
bash gradlew lint --no-daemon --max-workers=3
bash gradlew assembleDebug --no-daemon --max-workers=3
```

The coordinated full suite executes 127 tests, with zero failures, errors or skips.
It includes existing activation/pairing regressions and new mocked/simulated checks
for timers, trial/paid/VIP/unknown/expired data, payment refresh, native remote controls,
subtitle capability, actual AudioManager API invocation, BACK/return order, retained
player fallback/authorization, title identity, theme contrast and preserved phone UI.
Robolectric's missing documented ADJUST_TOGGLE_MUTE branch is modeled explicitly in
the test shadow; actual hardware volume has not been verified.

Java/resource compilation and debug APK build pass. `lint` reproduces only the two
pre-existing errors (not suppressed): CatalogActivity arithmetic on numeric radio
IDs (`ResourceType`) and `styles.xml:7` windowLightNavigationBar requiring API 27
with minSdk 24 (`NewApi`). APK metadata confirms `fun.nyama.plus`, minSdk 24,
target/compile SDK 36, version 1.0.23 / 25; debug signature verification passes.

APK: `app/build/outputs/apk/debug/app-debug.apk`. This is debug-signed, not a
production upgrade artifact. Live provider compatibility, accurate episode
availability, first/reopened player identity, real remote/device playback, no-audio-
overlap timing, Cast/quality and actual subtitle tracks remain unverified. These
limitations are distinct from the passing simulated Android/JVM checks.

Commit message: `feat: improve TV player controls, episodes and subscription timers`.
The final tested SHA and verification that it reached `test` are returned in chat.
Only `HEAD:refs/heads/test` is pushed, without force. No commit is pushed or merged
into `main`. The actual remote test tip is checked again before publication so any
new remote work must be integrated rather than overwritten.

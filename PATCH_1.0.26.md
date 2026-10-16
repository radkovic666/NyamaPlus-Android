# Android TV refinement patch 1.0.26 / code 28

Base: GitHub `test`, `8a57354371031798c0d0cd4125897d09cc7c3e22`,
version **1.0.25 / code 27**. New version: **1.0.26 / code 28**.
This is a focused Android TV/Google TV/TV-box patch. Shared Activities gate the
new behavior on TV mode; mobile layouts and playback retain their existing path.

## Chronological selectors and complete episode information

Seasons and episodes are presented in ascending numeric order, left to right,
in Bulgarian and English. Specials remain after the normal seasons. Insertion,
focus IDs and physical LEFT/RIGHT dispatch use the same order. UP returns to the
season row; DOWN from a season restores its episode selection. Focused cards
request their full measured rectangle, including a small border margin, after
layout when necessary. Existing local selection persistence remains in use.

Horizontal cards keep concise previews. A separate native, scrollable information
panel follows the focused episode and displays its complete title and available
season, episode, source-provided runtime, original air date, full description and
still. Missing fields are omitted. DOWN from a card enters the panel; UP scrolls
back through the description and returns to that card at the top. LEFT/RIGHT
returns to the adjacent episode, so long text cannot trap remote navigation.
The panel has responsive height, unrestricted wrapping and a high-contrast
background. Episode focus still drives the existing TVmaze artwork crossfade.

The repository retains complete, plain-text source summaries with paragraph and
line breaks, and validates source calendar dates. HTML tags, scripts, styles,
embedded objects and external resource references are never rendered. Entities
are decoded once. The existing compact summary, metadata source priority, strict
IMDb matching, endpoints, rate gate, normal cache TTL and schema remain intact.
Optional fields are persisted in the existing cache; older TVmaze entries refresh
once for the fields and remain usable offline with their previously cached text.
Metadata without a confirmed air date can be inspected using the remote, while
the existing eligibility guard still prevents starting an unverified episode.

Partial-watch labels are now **▶ Започнат** / **▶ Started**. Completed labels,
flag placement, colors, storage, measured watched intervals and the 90% completion
rule are unchanged.

## Series catalog artwork

The existing TV Movies cinematic shell now also wraps the Series catalog. Focus
and genuine pointer hover use the same Pukanki poster endpoint and the same
`TvCinematicBackground` instance per screen. The existing **350 ms** crossfade,
dark scrim, aspect-preserving portrait blur, stale-request protection and bounded
artwork cache are unchanged. No new metadata request or image pipeline is added.
Series detail continues to use its separate focused-episode TVmaze artwork.

Artwork is visual only. No new player, audio-focus, Live TV, playlist or EPG calls
are made during browsing. The existing VOD launch and return paths remain in
charge of suspending and restoring Live TV. Real TV audio behavior still requires
device verification.

## Player initialization investigation and defensive fix

The intermittent physical-TV white-screen failure could not be reproduced in this
cloud environment. Its root cause is **not established**. Inspection showed that
the existing native root, WebView and captured public provider wrapper already
have dark backgrounds. No pixel-brightness detection or global hardware-
acceleration change is justified or included.

Confirmed gaps were a TV load before attachment/layout, no native render-commit
handling, no renderer-loss lifecycle, and global WebView timers remaining paused
when a retry rebuilt the view inside an already resumed Activity. The TV path now:

- Defers the selected document load until its surface is attached, sized and the
  Activity is resumed, with per-surface duplicate-load protection.
- Displays a nonfocusable dark loading surface and black default fullscreen
  video poster, then removes the cover after a current-document visual-state
  callback or the current fullscreen view is presented. Render submission is
  not represented as proof that cross-origin media is available or playing.
- Discards a failed renderer safely, retires its progress listener and attempts
  **one** automatic reconstruction using the identical selected URL. A second
  renderer failure exposes the existing Retry/Back UI. Recovery waits while the
  Activity is paused. Manual retry also resumes WebView timers before loading.
- Ignores callbacks from retired surfaces and logs fixed initialization stages
  without title IDs, private URLs, tokens or customer data.

No elapsed-time or bright-frame heuristic reloads a functioning player. Provider
URL construction, exact episode identity, subtitle preferences, remote shortcuts,
BACK confirmation and progress validation/storage are retained. No provider,
episode or generic-series fallback is introduced. Provider-side blank/stalled
content without an explicit renderer/error signal cannot be reliably diagnosed
from an Android wrapper; this patch does not claim to detect it.

## Validation and delivery

Executed with the existing configured SDK/JDK and the repository wrapper:

```sh
bash ./gradlew test lint assembleDebug --continue --no-daemon --max-workers=3
```

- **Unit tests: 261 passed, 0 failed, 0 errors, 0 skipped, across 20 suites.**
  Includes 37 player, 23 selector, 26 repository, 6 source-text, 11 catalog,
  28 progress and 10 cinematic-artwork tests. The mocked player checks include
  Android 7/API 24 initialization, exact episode/subtitle routes, remote input,
  render acknowledgement, paused initialization, stale callbacks, one automatic
  renderer recovery and explicit Retry. Existing account/pairing/subscription,
  catalog reset, progress, mobile and Live TV restoration tests also pass.
- **Debug compilation and assembleDebug: passed.** APK package and version
  checked with `aapt`; `apksigner verify` passed.
- **Lint: failed with 2 existing errors and 73 warnings.** Existing errors are
  `ResourceType` at `CatalogActivity.java:680` (sort RadioGroup ID arithmetic)
  and `NewApi` at `res/values/styles.xml:7` (`windowLightNavigationBar`, API 27,
  min SDK 24). Both were present before this patch. No new lint errors remain.
  The added warning is `ViewConstructor` for the programmatically constructed
  information panel, which requires its language and remote-navigation callback.
  No baseline or lint suppression is introduced. The combined command therefore
  exits nonzero for lint even though tests and APK assembly succeed.
- `git diff --check`: passed. Scope review leaves mobile behavior, Live TV,
  provider selection, subtitle construction and progress storage unchanged.

APK: `app/build/outputs/apk/debug/app-debug.apk` (7,577,216 bytes).

SHA-256:
`4eca7bb7e98030f5816faa49bbe376459a5586aa84f3d49fba3c932998e76bf9`.

The player test fixture models the measurement/frame callbacks omitted by
Robolectric's stub Chromium provider; it still exercises the production attached,
sized, foreground and generation checks. These are native/mock tests, not evidence
that a real cross-origin provider played media. No physical TV, real remote, live
media, subtitle availability, older-box performance or audible Live TV test is
claimed. The intermittent white-screen root cause and physical-device behavior
still require runtime traces/device testing. Android 7 compatibility was checked
with Robolectric, not a physical Android 7 device.

## Files in this patch

- `app/build.gradle`: version 1.0.26 / 28.
- `app/src/main/java/fun/nyama/tv/CatalogActivity.java`: extend the TV cinematic shell to Series.
- `app/src/main/java/fun/nyama/tv/MoviePlayerActivity.java`: TV initialization and bounded renderer recovery.
- `app/src/main/java/fun/nyama/tv/TvEpisodeCard.java`: Started labels, inspectable unavailable metadata, independent still drawable bounds.
- `app/src/main/java/fun/nyama/tv/TvEpisodeInformationView.java` (new): complete scrollable focused-episode information.
- `app/src/main/java/fun/nyama/tv/TvEpisodeText.java` (new): safe paragraph-preserving source text.
- `app/src/main/java/fun/nyama/tv/TvSeriesMetadata.java`: compatible complete-summary and original-date fields.
- `app/src/main/java/fun/nyama/tv/TvSeriesRepository.java`: retain and cache those source fields.
- `app/src/main/java/fun/nyama/tv/TvSeriesSelectionView.java`: chronological remote navigation and focused information binding.
- `app/src/test/java/fun/nyama/tv/MoviePlayerControlsTest.java`: synthetic render/lifecycle/recovery and mobile regressions.
- `app/src/test/java/fun/nyama/tv/TvCatalogRefinementTest.java`: Series artwork reuse and mobile parity.
- `app/src/test/java/fun/nyama/tv/TvEpisodeTextTest.java` (new): safe text and complete source preservation.
- `app/src/test/java/fun/nyama/tv/TvSeriesRepositoryTest.java`: complete source data and cache compatibility.
- `app/src/test/java/fun/nyama/tv/TvSeriesSelectionTest.java`: ordering, remote focus, full details, responsive sizing and labels.
- `PATCH_1.0.26.md` (new): implementation and validation record.

The playback URL builder, progress bridge/store/tracker, cinematic background and
artwork loader, MainActivity, Live TV, account/subscription code, Android resources,
dependencies, search/reset and pagination implementation are not modified.

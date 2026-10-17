# Nyama+ experimental Android TV patch 1.0.25 / code 27

Base: GitHub `test` commit `c77c2bf1cf25c1d244db96287acb207fa8040de6`,
version 1.0.24 / code 26. New version: **1.0.25 / code 27**. The existing
workspace changes and earlier activation, subscription, catalog and playback
features are preserved. This patch targets Android TV/Google TV/TV boxes only.

## Cinematic episode and movie artwork

`TvCinematicBackground` is a nonfocusable layer beneath the existing foreground
interface. Two center-crop image views perform a real **350 ms** crossfade with
an accelerate/decelerate interpolator. The outgoing image remains visible until
the next bitmap is ready; gradient scrims keep the foreground readable. Pending
loads use generation checks and cancellation, and only the latest pending image
can follow an active fade. No playback, audio-focus, provider or navigation calls
are part of the artwork pipeline.

Series detail uses the focused/hovered episode's `image.original` URL where
available. `stillUrl` remains the separate compact thumbnail source. The repository
retains the original URL in its existing cache format without replacing its
identity matching, season/episode records, source priority or normal one-hour TTL.
Older thumbnail-only TVmaze cache entries refresh once for original artwork when
online; their verified episode data remains usable as stale metadata offline.
Missing, failed or genuinely tiny stills fall back to the series poster treatment,
then the normal dark surface if the poster is also unavailable. They do not leave
another episode's artwork as the final background.

Movies catalog focus/hover uses the existing configured Pukanki poster endpoint.
`CatalogItem` has no landscape backdrop field, so catalog focus does not make a
new title-info API request. Detail receives the selected artwork and can prefer an
actual backdrop field from its existing title-info response after checking the
returned IMDb identity. Portrait fallback uses aspect-preserving center crop and
an inexpensive blur computed on a worker; no new metadata provider is introduced.

`TvArtworkLoader` is independent of episode thumbnails and Live TV images:

- Network and decoding run on bounded workers with at most one queued latest
  request per loader. A screen can load one current image and one fallback poster.
- Shared LRU bitmap cache: **24 MiB**, reduced to **10 MiB** for low-RAM/128 MiB
  heap devices. Source URL, treatment and display target form the cache key.
- Landscape targets cap at **1920×1080**, or **1280×720** on low-memory boxes,
  including on 4K screens. RGB565 decoding is requested; compressed bodies cap
  at 8 MiB, source dimensions are checked, and decode sampling bounds pixel count.
- Portrait blur works on at most 360×640 pixels. Non-portrait sources smaller
  than 640×360 use the poster fallback rather than enlarged thumbnails.
- HTTPS/TLS validation remains enabled. Credentials, local-file/content schemes,
  literal IP/private-host forms, unsafe ports and insecure redirects are rejected.
- Successes are reused; failures have a 60-second backoff. No full-resolution
  season download or adjacent-image prefetch is performed.
- Screen destruction cancels workers/animations and drops view bitmap references.
  Offscreen episode thumbnails are cleared so views cannot defeat the small cache.

## Larger horizontal episode selectors and attribution

Seasons and episode cards both scroll horizontally. Bulgarian reverses only the
visual order: episode/season 1 is at the right, LEFT moves to its visible left
neighbor, and RIGHT moves to its visible right neighbor. English uses the normal
left-to-right order. Episode identities are never reversed or renumbered.
UP from any episode returns to the selected season; DOWN from a season selects
that season and restores its selected episode. Row edges keep focus within the
selector, and focus requests scroll the actual card into view. OK still opens the
exact selected episode through the existing provider URL.

Cards are 280–360 dp wide, 292 dp high on compact displays and 324 dp on taller
displays. They provide a larger still, episode number/runtime, 20 sp title,
16 sp description, graceful truncation, a strong theme-accent border and readable
light text. Focus/hover updates the background without starting playback. The
original poster, title, metadata, Watch, Favorites and remembered episode state
remain. Returning from playback refreshes status flags immediately.

The TVmaze source button is removed from every selector. Attribution and working
TVmaze/CC BY-SA links now live in the **TV Settings → Information** card. Phone
Settings does not gain this card. A handled external attribution link suppresses
the existing leave hint once; missing browser handling restores normal HOME behavior.

## Reliable local episode progress

The integration uses VidAPI's documented `PLAYER_EVENT` messages, not TVmaze
runtime or cross-origin DOM access. The official documentation and public VAPLayer
wrapper inspected in the preceding patch document the payload and `iframe#pf`.
The receiver validates:

1. The exact VAPLayer HTTPS main-frame origin and selected episode path.
2. The real `pf.contentWindow` message source and exact observed player-frame
   origin/path (`https://nextgencloudfabric.com`).
3. `PLAYER_EVENT`, media type `tv`, exact IMDb/season/episode, supported status,
   and finite numeric playback position/measured duration within bounds.

An origin-restricted AndroidX WebKit `WEB_MESSAGE_LISTENER` bridge receives only
the bounded documented fields. It is installed only for explicit TV episodes.
There is no all-frame `addJavascriptInterface`, invented provider command,
protected-frame scraping or embedding restriction bypass. Unsupported WebViews,
missing events, generic series pages, movies and phone playback create no history.

**Completion logic:** merge the union of verified continuous viewing intervals
and divide its covered seconds by the measured provider duration. An interval
requires previous `playing` state, positive movement, a monotonic callback gap
of at most 15 seconds, and position movement compatible with elapsed real time.
Credited seconds never exceed elapsed time. Seek events/native seek commands,
large jumps, backgrounding, restart and material duration changes break continuity.
Rewatched overlaps count once; paused Activities reject late provider events.
The interval list caps at 256, discarding the shortest intervals if necessary;
this can undercount but never creates watched time.

- At least **10 seconds** verified viewing and under 90% coverage: `▶ В процес`
  / `▶ In progress`.
- At least **90% interval coverage**: `✓ Гледан` / `✓ Watched`.
- No reliable viewing evidence: no flag.

The documented `completed` status means only "reached the end". It is not accepted
by itself as evidence that the earlier content was watched. Seeking straight to
90% cannot create a completed flag.

Position, measured duration and intervals are saved in installation-local
`nyama_tv_episode_progress` SharedPreferences under IMDb/season/episode keys.
Writes are batched roughly every 15 seconds and flushed on pause/exit; identical
records skip a write. Normal app restart/APK upgrade retains the preferences.
No server, favorites replacement or account/device synchronization is added.
`resumeAt` and initial playback behavior are unchanged.

## Live TV audio and catalog reset

Catalog and detail browsing, card focus, metadata and artwork transitions never
pause, mute, stop or reload Live TV. Existing VOD launch remains the point where
`pausePlaybackForOnDemand()` pauses the retained live player. Explicit episode
exit now destroys VOD first, then calls the existing retained-channel resume
method before returning to series selection. This also avoids a delayed old
Activity destruction resuming audio over a newly launched episode. Movie return,
last-channel data, playlist/EPG handling and MainActivity implementation are unchanged.

The TV search X remains focusable even with empty text and resets the current
Movies/Series section: empty query/genres, Favorites-only off, default `reviews`
sorting, page 1, current total/page count and predictable first-poster focus.
It clears pending search work and rejects stale UI results. Complete validated
catalog data is reused; incomplete raw data uses the existing first-page and
background poster-validation paths. Immutable request snapshots prevent old warm-up
work from using new filters or contaminating another cache key. The normal page
size stays 30, and existing poster eligibility rules remain.

## Validation and limits

The repository wrapper is invoked with `bash gradlew` because its executable bit
is absent. Unit/Robolectric tests cover artwork selection, stale requests,
crossfade layers, cache/decode bounds, original-image cache upgrade, missing art,
horizontal Bulgarian/English navigation, 720p/1080p card sizing, exact launches,
attribution relocation, real-time interval coverage/seek rejection, persistence,
episode isolation, paused-event rejection, flags, reset during warming, stale
callbacks, Live TV release/resume order and unchanged phone layouts/routes.
Existing account, pairing, timers, subscription, catalog and player tests also run.

Lint retains the two baseline errors: `CatalogActivity` numeric radio-ID arithmetic
(`ResourceType`) and `values/styles.xml:7` API-27 `windowLightNavigationBar` with
minSdk 24 (`NewApi`). Neither is hidden by a baseline or suppression. No introduced
lint error remains. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Final verification: **230 tests in 19 suites, zero failures, zero skipped**.
`bash gradlew test assembleDebug --no-daemon --max-workers=3` completed successfully.
`lint` completed its analysis and failed with the two existing errors and 72
warnings. One repeated full run spun inside Robolectric's paused looper while
making the existing activation-timer test visible; that test worker was stopped,
and the fresh complete run passed without modifying account/timer code or tests.
`aapt` verifies package `fun.nyama.plus`, version 1.0.25 / code 27, minSdk 24.
Debug signature verification succeeds. APK SHA-256:
`308b279922bae7d581616bc4e308fb51a1dc586a03181066278c2077e1167c2d`.

No physical Android TV/remote, hardware audio, real provider progress messages,
real APK-upgrade installation or low-end-box performance test was performed.
Synthetic tests do not prove those scenarios. If the provider changes its wrapper,
origin, event identity or emits no reliable measurements, tracking safely produces
no new flags. Missing/slow artwork keeps the current image until fallback is ready.
Sparse events or long gaps can conservatively undercount viewing. Subtitle/provider
availability and original remote-shortcut limitations remain as documented in
the previous patch; their routes and behavior are preserved.

## Exact files changed

Modified:

- `app/build.gradle`: 1.0.25 / 27 and origin-scoped AndroidX WebKit support.
- `app/src/main/java/fun/nyama/tv/CatalogActivity.java`: TV movie artwork and X reset.
- `app/src/main/java/fun/nyama/tv/MoviePlayerActivity.java`: TV episode event lifecycle
  and release-before-live-audio resume; provider URLs/phone controls unchanged.
- `app/src/main/java/fun/nyama/tv/SettingsActivity.java`: TV-only Information credit.
- `app/src/main/java/fun/nyama/tv/TitleDetailActivity.java`: TV artwork and flag refresh.
- `app/src/main/java/fun/nyama/tv/TvSeriesMetadata.java`: separate original still URL.
- `app/src/main/java/fun/nyama/tv/TvSeriesRepository.java`: original URL cache support.
- `app/src/main/java/fun/nyama/tv/TvSeriesSelectionView.java`: horizontal TV controls.
- `app/src/test/java/fun/nyama/tv/TvSeriesRepositoryTest.java`.
- `app/src/test/java/fun/nyama/tv/TvSeriesSelectionTest.java`.

Added:

- `app/src/main/java/fun/nyama/tv/TvArtworkLoader.java`.
- `app/src/main/java/fun/nyama/tv/TvCinematicBackground.java`.
- `app/src/main/java/fun/nyama/tv/TvEpisodeCard.java`.
- `app/src/main/java/fun/nyama/tv/TvEpisodeProgressStore.java`.
- `app/src/main/java/fun/nyama/tv/TvEpisodeProgressTracker.java`.
- `app/src/main/java/fun/nyama/tv/TvPlayerProgressBridge.java`.
- `app/src/test/java/fun/nyama/tv/TvCatalogRefinementTest.java`.
- `app/src/test/java/fun/nyama/tv/TvCinematicArtworkTest.java`.
- `app/src/test/java/fun/nyama/tv/TvDetailPresentationTest.java`.
- `app/src/test/java/fun/nyama/tv/TvEpisodeProgressTest.java`.
- `PATCH_1.0.25.md`.

Mobile UI/player behavior, MainActivity, Live TV/M3U/EPG/channel data, authentication,
QR/email activation, tokens, entitlement, device limits, payments, countdowns,
administrator settings and updater source are unchanged. The required shared APK
version increment and packaged WebKit library do not add a phone feature.

The existing cloud Android/JDK setup was reused. Saved startup instructions were
refreshed to remove an obsolete fixed base SHA and document secure WebView feature
fallback. This saved environment draft is separate from the Git push: review/save
environment settings and publish the environment to activate the saved setup.

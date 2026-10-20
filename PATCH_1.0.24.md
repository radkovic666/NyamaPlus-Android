# Nyama+ Android TV experimental patch 1.0.24 / code 26

Base: GitHub `test`, `89b69a6001bebb08a3ffa8ddf646ea3de7d2bc1a`, version
1.0.23 / code 25. The new version is 1.0.24 / code 26. Existing completed
activation, pairing, subscription timer, theme, and Live TV changes are retained.
This patch changes the Android TV on-demand experience; the phone layout, six
player controls, StreamIMDB routes, and phone input/back behavior are preserved.

## Metadata and episode selection

Pukanki.Fun remains the catalog/title-information source. Its fetched Game of
Thrones title information contains aggregate seasons and ordinary title metadata,
but no individual episode records. The repository first inspects actual numbered
episode records in nested Pukanki structures; totals never generate fake rows.
If those records are missing, it uses the public TVmaze endpoints documented at
<https://www.tvmaze.com/api>:

- `/lookup/shows?imdb={imdb}`
- `/shows/{matched_id}/seasons`
- `/shows/{matched_id}/episodes`

The exact canonical IMDb ID must match the TVmaze `externals.imdb` field. There is
no title-only matching or guessed IMDb/TMDB conversion. Missing/invalid/mismatched
identities fail safely. Episode ownership links are checked when supplied.
Only HTTPS redirects within `api.tvmaze.com` are accepted (at most three), with
normal certificate validation. Requests and bodies are bounded, 429 Retry-After
is honored, and requests are spaced at least 600 ms apart. Successful results
are cached for one hour in memory (eight series) and Android's disk cache (64
series); a verified cache up to seven days old can be used during an outage.
Season changes use the already loaded immutable metadata without network calls.

Metadata loads on a worker. Lifecycle cancellation and generation checks discard
irrelevant results. Native TV controls provide horizontal seasons and a scrolling
episode list, with actual titles, durations and summaries when supplied. Optional
stills load only for visible rows, using a bounded two-worker loader, sampled
bitmaps, a 6 MiB cache, and cancellation on season change/detachment. No artwork or
duration is invented. Focus/selection uses the exact `UiTheme.accent()` and a
contrasting foreground/outline. The original poster, title, summary, ratings,
favorites and catalog layout remain.

Future episodes are omitted; unknown air dates are explicitly unconfirmed and
cannot be launched. Correctly identified Season 0 entries are separate specials.
Metadata establishes an aired episode, **not provider playback availability**;
the screen states that provider availability is unconfirmed. If no reliable
episode metadata can be loaded, the existing generic series Watch path remains.
An explicit episode never falls back to a different episode or generic series.

TVmaze data is visibly attributed as `TVmaze · CC BY-SA`, with a safe link to the
matched show. One-hour caching and conservative rate handling implement its known
public API policy. Current TVmaze API/documentation access in this cloud instance
is denied by the inherited proxy before origin (`CONNECT 403`), so the current
licensing/rate-limit wording and a live lookup could not be independently
rechecked. The additive environment draft includes `www.tvmaze.com`,
`api.tvmaze.com`, and `static.tvmaze.com`; saving a draft does not apply it to this
running instance. Review/save and publish the environment settings before a live
cloud recheck. The app itself uses normal HTTPS; it does not bypass a denial.

## Exact playback, subtitles and remote controls

The official <https://vidapi.ru/api> documentation and public VAPLayer embed HTML
were successfully inspected. TV movies now use the documented
`https://vaplayer.ru/embed/movie/{imdb}` route. Explicit TV episodes use
`https://vaplayer.ru/embed/tv/{imdb}/{season}/{episode}` with the exact selected
season/episode and matching parent IMDb ID. Reopening/retrying preserves that
route and provider. Generic series fallback without episode metadata and all
phone playback retain the original StreamIMDB routes.

Every new documented TV embed uses `Uri.Builder` to add `ds_lang=bg` or
`ds_lang=en` from `Config.language()`, `autoplay=1`, and the current theme's
`primaryColor`. These parameters are not blindly appended to StreamIMDB. A real
accessible HTML5 track in the preferred language is selected once if available,
without forcing play, repeatedly opening menus, or overriding a later choice.
Subtitle existence is not guaranteed. Cross-origin subtitles remain controlled
by the provider, and missing tracks do not block playback.

The entire native Nyama+ TV playback toolbar, volume/fullscreen/subtitle chooser,
and controls-navigation mode are removed. No replacement bar, floating menu,
mouse, cursor, TAB navigation, invisible focus buttons, or extra WebView is added.
TV playback opens in the existing immersive fullscreen WebView surface. Provider
controls remain enabled because their removal would require verified replacement
remote behavior on hardware.

- OK/DPAD_CENTER: one accessible HTML5 play/pause operation, or one native SPACE
  down/up pair to the provider frame.
- LEFT/RIGHT: accessible HTML5 seek by exactly -10/+10 seconds, or one native
  directional down/up pair to the provider frame.
- UP/DOWN/MENU/INFO: no custom playback UI.
- BACK/ESC: existing two-distinct-press exit confirmation, duplicate/repeat guards,
  and existing fullscreen-custom-view dismissal behavior.

Key-up/repeated key-down events do not cause duplicate playback actions. Stale
input callbacks and callbacks from destroyed WebViews cannot act on a retried
player. TV HTTP/network/TLS failures of the playback document show focusable
Retry/Back actions; Retry loads the identical selection. Normal TLS certificate
rejection is retained. Errors from unrelated images/ads do not tear down playback.
No stream scraping, DRM bypass, guessed provider commands, forged embedding
headers, or bypass of cross-origin protections is used.

The selected season/episode is saved per IMDb ID in TV-only preferences. Returning
from an explicit episode destroys its media and finishes to the existing series
screen, restoring selection/focus instead of restarting Season 1. Existing TV
movie/generic-series confirmed exit still returns to Live TV using the existing
retained-player path. No new viewing-progress backend is added.

## Verification and provider limits

Public VAPLayer movie and exact-episode pages returned HTTP 200 and propagated the
exact identity/season/episode and query to their public cross-origin player frame.
StreamIMDB uses a similar wrapper. This verifies the documented URL contract;
it does not prove an episode has a playable source or that Android WebView is
authorized for every deployment. Respect the provider's allowed-site, origin,
embedding and account restrictions. HTTP-200 semantic errors inside an
inaccessible provider frame remain provider UI; no undocumented availability API
or global 19,000-page episode scan is used. BACK returns to the selected episode.

Unit/Robolectric tests cover metadata priority, multiple/single seasons, exact
identity, missing/future/unknown episodes, redirects, cache reuse/offline fallback,
rate limiting/cancellation, TV selection/focus/persistence, deterministic routes,
both subtitle languages, no TV toolbar, single-dispatch remote shortcuts,
two-press BACK, retry races, phone routes/controls, and retained Live TV return.
Existing account/activation/pairing/subscription/catalog tests also run.

No physical TV/remote, emulator playback session, actual media availability,
cross-origin seek interval, subtitle availability or older-box performance was
tested. Native key routing is tested; provider execution remains dependent on its
legitimate shortcut support. In particular, the fallback directional keys cannot
guarantee a ten-second interval without provider verification. Public documentation
does not supply an inbound playback command API. These limitations are not marked
as successful acceptance tests.

Build commands use the repository wrapper via `bash gradlew` because its executable
bit is absent. `test` and `assembleDebug` pass. `lint` reports the two unchanged
baseline errors: `CatalogActivity.java` numeric radio-ID arithmetic (`ResourceType`)
and `values/styles.xml:7` API-27 `windowLightNavigationBar` with minSdk 24 (`NewApi`).
No baseline/suppression or unrelated UI change hides those errors.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Final run: **170 tests across 15 suites, zero failures, zero skipped**;
`bash gradlew test assembleDebug` completed successfully. The APK package is
`fun.nyama.plus`, version 1.0.24 / code 26, minSdk 24. Its debug signature passes
`apksigner verify`. APK SHA-256:
`3ecc787115b3f13a899c7e19cfd1f42a5a1f6cb60ac7e3c90dbadabd29f6a1e9`.

## Exact files in this patch

- `app/build.gradle`: version 1.0.24 / code 26.
- `app/src/main/java/fun/nyama/tv/MoviePlayerActivity.java`: TV player surface,
  route choice, subtitles, errors, input and episode return; phone behavior retained.
- `app/src/main/java/fun/nyama/tv/TitleDetailActivity.java`: TV selector integration
  and exact episode extras, with lifecycle-safe worker loading.
- `app/src/main/java/fun/nyama/tv/TvPlaybackSource.java`: validated documented TV URLs.
- `app/src/main/java/fun/nyama/tv/TvSeriesMetadata.java`: immutable verified metadata.
- `app/src/main/java/fun/nyama/tv/TvSeriesRepository.java`: metadata sources/cache.
- `app/src/main/java/fun/nyama/tv/TvSeriesSelectionView.java`: native TV episode UI.
- `app/src/main/java/fun/nyama/tv/TvEpisodeStillLoader.java`: optional async stills.
- `app/src/test/java/fun/nyama/tv/MoviePlayerControlsTest.java`: TV/phone regressions.
- `app/src/test/java/fun/nyama/tv/TvSeriesRepositoryTest.java`: mocked source tests.
- `app/src/test/java/fun/nyama/tv/TvSeriesSelectionTest.java`: native selector tests.
- `app/src/test/java/fun/nyama/tv/TvEpisodeStillLoaderTest.java`: optional artwork tests.
- `PATCH_1.0.24.md`: implementation, verification and limitations report.

No Live TV, M3U, EPG, channel favorites, account/activation/renewal, provisioning,
administrator settings, self-update, catalog/search/pagination or mobile layout
implementation is changed. The shared version increment is intentional.

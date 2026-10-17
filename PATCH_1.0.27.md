# Android TV seeking stability and modern VOD UI

Base: GitHub `test` at `6f432d30c8b721b9c0f8df4e8aca8ea85b93467e`,
version **1.0.26 / code 28**. New version: **1.0.27 / code 29**.
Changes apply to Android TV/Google TV/TV boxes. Phone UI and playback retain their
existing branches; Android resources, dependencies and account infrastructure are unchanged.

## Seeking investigation and control ordering

The reported reproduction is repeated RIGHT presses after playback has started.
No physical Android TV is available here, so that intermittent white screen was
not reproduced and its renderer/provider/decoder root cause is **not established**.

Confirmed implementation gaps in the old path:

- Each accepted press started an independent asynchronous JavaScript seek probe.
- An assignment to `currentTime` immediately returned success without checking
  readiness, seekable ranges, `seeking` or the resulting position.
- A failed probe could launch another asynchronous iframe-focus/native-key path,
  with no ordering between adjacent presses and no confirmed provider focus.

`TvSeekQueue` now keeps one seek operation in flight. Intentional presses retain
their signed ten-second increments. After direct HTML5 control is established,
consecutive same-direction requests can coalesce; direction changes remain in
order, including at the start/end of media. The first request runs immediately.

The direct path accesses only this document and legitimately accessible same-origin
frames. It selects visible media, checks finite measured duration/position,
readiness and seekable ranges, clamps the target, and confirms position readback
with `seeking == false`. While seeking is pending, a 50 ms probe checks those
signals without issuing another assignment. A 15-second confirmation limit only
reports an unavailable control and drops ambiguous queued commands; it never
reloads media or sends a second native seek. Duplicate/stale completions are ignored.

Only a definitely inaccessible media element selects the existing native shortcut
path. It sends one real key down/up pair per requested ten-second step. VAPLayer
iframe focus checks the existing `pf` frame's exact origin/path and confirms it is
the active element before dispatch. Focus is reused within a queued burst while
the native target keeps focus. A failed focus cannot turn RIGHT into outer-page
navigation. Paused/retired surfaces reject late callbacks. Probe, focus or partial
native-dispatch exceptions do not replay a possibly applied seek or reload the player.

Provider documentation describes outbound `PLAYER_EVENT` messages, but does not
document an incoming seek-command/acknowledgement protocol. No command API or
cross-origin DOM access is invented. Native key delivery is serialized; it does
not prove that the provider completed the seek or uses a particular shortcut
mapping on every WebView version. That still needs real-provider/device testing.

## Bounded renderer recovery

The existing one-recovery budget remains. Only explicit WebView renderer loss
automatically reconstructs a surface. No bright-frame, elapsed seek-time,
buffering-silence or missing-heartbeat heuristic reloads a working player.

On genuine renderer loss, recovery can append documented `resumeAt` seconds for
the last reliable position, without changing host, title, season, episode or
subtitle parameters. The position must be no older than 15 seconds and come from
either a confirmed direct HTML5 seek or the existing strictly validated exact-
episode provider events. A known paused state uses `autoplay=0` on recovery.
Unknown/stale positions are omitted. Cross-origin movie position is unavailable
when neither direct media access nor a supported observation exists; it is not guessed.

The event bridge only adds an ephemeral position snapshot. Its existing source,
origin, iframe, path and media-identity checks, stored intervals, periodic writes,
Started/Watched flags and 90% completion threshold remain intact. Seek jumps still
do not count as viewing. Old media/listeners/queued input are retired before a
replacement is built. A second renderer failure exposes Retry/Back.

## Context-aware exit

Confirmed movie exit releases VOD resources, resumes background Live TV through
the existing mechanism, and returns an Activity result. The existing movie detail
finishes to reveal the original Movies catalog with its query, filters, page and
focused poster. Normal movie return launches neither CatalogActivity nor MainActivity.

Episode exit finishes to the existing series detail. Its remembered season and
episode are restored, its progress flag refreshes, and BACK from details reveals
the original Series catalog. Originless legacy/direct player launches use a
section-specific `CLEAR_TOP | SINGLE_TOP` catalog fallback, never fullscreen Live TV.
Same-section fallback intents preserve the existing adapter/state without fetching
the catalog again. The clicked TV poster position is retained instead of the first
visible poster. Double-BACK confirmation remains unchanged.

Catalog/detail/artwork browsing adds no Live TV pause, mute, reload or audio-focus
calls. Live TV is paused when VOD actually starts and resumed after VOD destruction.
No MainActivity or playlist/EPG implementation is changed.

## Modern TV presentation and complete episode cards

- The TV Series Watch button is absent, including its hidden focus target.
  Favorites remains; UP/DOWN connects Favorites, Seasons and Episodes. Movies and
  phone layouts keep Watch. Missing episode metadata gives reopening/retry guidance
  rather than launching a guessed generic episode.
- Complete episode metadata now lives inside each horizontal card: pinned still,
  season/episode identity, available runtime/air date, full title, full sanitized
  description and the existing top-right Started/Watched flag. Missing data is omitted.
- Each card has a bounded internal text viewport. DOWN scrolls, UP scrolls toward
  the top and then returns to seasons; holding UP returns immediately to seasons.
  LEFT/RIGHT and OK remain card navigation/playback at any text offset. No title
  or description line cap/ellipsis is used in the full presentation.
- Cards respond to screen dimensions and are further capped to the actual row
  viewport, so focused cards can be fully revealed on narrow layouts. The duplicate
  episode-information panel and its unused class are removed. Both rows remain
  chronological left to right in Bulgarian and English.
- Movies and Series share rounded poster outlines, clearer search/status typography,
  more consistent spacing and theme-accent focus with a restrained 120 ms scale.
  Episode cards and season buttons use matching rounded high-contrast surfaces.
- The existing cinematic backgrounds, 350 ms crossfade, poster treatment and bounded
  image caches are reused unchanged. No new metadata/artwork request is triggered
  by these presentation refinements.

## Validation

Executed with the existing configured JDK/SDK:

```sh
bash ./gradlew test lint assembleDebug --continue --no-daemon --max-workers=3
python3 tools/check_tv_seek_script.py
```

- **290 unit tests passed**, 0 failures/errors/skips, across 21 suites.
  Includes delayed direct/native callbacks for ten rapid presses, signed queue
  ordering at boundaries, direct confirmation, pause/resume, stale callbacks,
  exception paths, unchanged URLs, renderer position/subtitle recovery, contextual
  exit, full card text, remote scrolling, 720p/1080p presentation and phone regressions.
- **14 V8 scenarios passed** against the actual Java-generated seek scripts and
  mock HTML5 media/DOM signals. Covers cumulative distance, start/end clamping,
  pause state, pre-existing seeking, ignored assignments, unready/nonseekable media,
  cross-origin protection, hidden/detached media and wrong operation tokens.
  The standalone checker requires a JDK, Python 3 and Node; it is separate from Gradle.
- Java/resource compilation and **assembleDebug passed**. `aapt` verified package
  `fun.nyama.plus`, version 1.0.27/code 29, min SDK 24. `apksigner verify` passed.
- **Lint fails on two pre-existing errors**, with 72 warnings: `ResourceType` in
  `CatalogActivity.java:707` (existing RadioGroup ID arithmetic, previously line 680),
  and `NewApi` in `res/values/styles.xml:7` (`windowLightNavigationBar`, API 27 vs
  min SDK 24). No new lint errors remain, and no baseline/suppression is added.
  The combined command exits nonzero for lint, despite passing tests and APK assembly.
- `git diff --check` passed. Provider URL builder, metadata repository/cache,
  progress tracker/store, cinematic pipeline, MainActivity, Live TV, resources,
  registration/subscriptions and dependencies are not modified.

APK: `app/build/outputs/apk/debug/app-debug.apk` (7,589,344 bytes).
SHA-256: `7f771ba443ac10fe486cb27596c73647571ad895b7efa0117d2e15367afe37ad`.

No physical remote, real provider playback, subtitle availability, audible Live TV
or old-box performance test is claimed. Mock tests establish control ordering and
lifecycle behavior; they do not conclusively establish that the intermittent
seek-induced white screen is fixed on customer hardware. A provider decoder stall
without a trustworthy failure signal cannot safely trigger automatic recovery.

## Exact files

- `app/build.gradle` — modified.
- `app/src/main/java/fun/nyama/tv/CatalogActivity.java` — modified.
- `app/src/main/java/fun/nyama/tv/MoviePlayerActivity.java` — modified.
- `app/src/main/java/fun/nyama/tv/TitleDetailActivity.java` — modified.
- `app/src/main/java/fun/nyama/tv/TvEpisodeCard.java` — modified.
- `app/src/main/java/fun/nyama/tv/TvEpisodeInformationView.java` — deleted, unused.
- `app/src/main/java/fun/nyama/tv/TvPlayerProgressBridge.java` — modified.
- `app/src/main/java/fun/nyama/tv/TvSeekQueue.java` — added.
- `app/src/main/java/fun/nyama/tv/TvSeriesSelectionView.java` — modified.
- `app/src/test/java/fun/nyama/tv/CatalogThemeTest.java` — modified.
- `app/src/test/java/fun/nyama/tv/MoviePlayerControlsTest.java` — modified.
- `app/src/test/java/fun/nyama/tv/SeriesPlaybackFallbackTest.java` — modified.
- `app/src/test/java/fun/nyama/tv/TvCatalogRefinementTest.java` — modified.
- `app/src/test/java/fun/nyama/tv/TvEpisodeProgressTest.java` — modified.
- `app/src/test/java/fun/nyama/tv/TvSeekQueueTest.java` — added.
- `app/src/test/java/fun/nyama/tv/TvSeriesSelectionTest.java` — modified.
- `tools/check_tv_seek_script.py` — added.
- `PATCH_1.0.27.md` — added.

# Android TV subtitles — nyamasubs

This experimental branch adds subtitle controls only to the Android TV Movies/Series player. Mobile, Live TV, provider routes, activation and account behavior retain their existing paths.

## Remote controls

- Press **CAPTIONS, MENU or INFO** during playback to open the Nyama+ subtitle dialog.
- Use D-pad/OK to select an action. **BACK closes the dialog**, without arming the playback-exit prompt.
- With the dialog closed, **OK still toggles play/pause; LEFT/RIGHT still seek**.
- **Use player subtitle controls** clicks a visible subtitle button when one can be found in an accessible document, or focuses the embedded player frame. D-pad input is then delegated to the player; MENU/CAPTIONS/INFO/BACK returns to the Nyama+ dialog. This does not certify that a third-party menu has opened or that it supports remote navigation.

## Real track selection

The dialog lists only subtitle/caption tracks actually exposed by the unique visible HTML5 video in the selected provider document or a same-origin frame. Labels and language codes come from the track. It does not invent releases or list every file a provider might have on its server.

Select an actual track, **Off**, or **Automatic**, which uses the existing app-language preference. Manual selections and Off are protected against the existing automatic policy. Non-caption metadata tracks are not disabled.

The code verifies the selected HTTPS provider origin/path, protects actions with a media-session token, and ignores stale Activity/WebView callbacks. Multiple visible videos fail closed instead of guessing. It does not read protected cross-origin frame DOM, inject a native interface into all origins, or replace the player.

## Timing

When a complete HTML text track or imported local file exposes mutable cues, controls offer **−5 to +5 seconds in 0.5-second steps**, plus Reset. Positive delay means captions appear later. Adjustments use original cue times rather than accumulating repeated changes. Cues shifted entirely before zero are suppressed and restored on reset.

The existing browser subtitle engine renders the cues against its own media clock, including pause and seeking. No native caption clock is extrapolated from five-second progress reports. No video current-time, rate, play or pause command is used to implement subtitle delay.

Delay is unavailable for inaccessible, not-yet-loaded or unsupported tracks. Read-only failures leave playback intact. Dual-caption ambiguity disables delay until one track is explicitly selected. Changed cue content does not inherit another file's saved delay. Constant delay cannot repair a different cut or progressively drifting frame-rate mismatch.

## Authorized local SRT/VTT files

When the active video supports adding text tracks, choose **Add local SRT/VTT file (Bulgarian/English)**. Android's system document picker grants access to the selected file. The file is parsed on a worker, sanitized to plain text, and added through the accessible video's own `TextTrack`/`VTTCue` support. Nyama+ makes no separate file upload or subtitle-service request. The selected third-party player page can access the added cue text. WebView file/content access remains disabled.

Limits: **1 MiB input, 6,000 cues**, bounded cue text and valid chronological timing. UTF-8, BOM-marked UTF-16, and legacy Windows-1251 Bulgarian text are supported. Basic SRT/WebVTT text is supported; advanced styling is not reproduced. The TV needs an installed document picker. Choose only subtitle files you are authorized to use.

Parsed copies are kept in private app cache, bounded to twelve files; Android may evict them. If a saved copy disappears, reselect the original file. No remote subtitle download API, third-party credentials or new backend is included.

## Remembered choices

Preferences are local and scoped to the provider plus exact IMDb movie or season/episode route. A generic Series chooser does not persist an unidentified episode's offset. Stable provider track choices use a hash of the track source; URLs and signed parameters are not stored. Saved provider delays additionally require an identical original-cue fingerprint. Local-file choices use the file's SHA-256 identity.

Restoring a disabled provider track can start an asynchronous subtitle load. There are at most four short fallback checks plus the existing bounded startup-assist opportunities; there is no endless polling. The explicit **Refresh track list** retries after a slower load. Provider URL changes, missing tracks, missing cached files or different cue fingerprints safely prevent old timing from being applied.

## Provider and test limits

Both selected providers remain supported as before, but native subtitle features are capability-based. A protected iframe may offer only the provider-controls action. No undocumented Player 1/VidAPI track-list, external-file parameter, timing or postMessage command is assumed. No DRM, anti-bot or account restriction is bypassed.

Real provider playback and remote/fullscreen behavior require hardware testing. Automated coverage uses JUnit/Robolectric and the production JavaScript executed in Node against synthetic DOM fixtures. Those checks do not establish live provider subtitle availability.

Validation commands:

```sh
python3 scripts/test-tv-subtitles.py
python3 scripts/test-tv-subtitle-controls.py
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

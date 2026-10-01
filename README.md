# Nyama+ 1.0.12

Nyama+ combines the Nyama TV 3.0.12 live-TV client with a native Movies / Series catalog backed by Pukanki.Fun.

## Android package

- Application ID: `fun.nyama.plus`
- Version: `1.0.12`
- Version code: `14`
- minSdk: 24 (Android 7.0)
- targetSdk / compileSdk: 36
- Java: 17
- Media3: 1.11.1
- No LibVLC or FFmpeg is bundled.

Nyama+ is a separate app and can be installed alongside Nyama TV.

## Startup and navigation

Nyama+ now launches directly into Live TV and restores the last watched channel through the existing Nyama TV channel key logic.

Top-level destinations are presented through a left Nyama+ navigation rail:

- Live TV
- Movies
- Series
- Settings

On Live TV the rail opens from the small `NY+` left-edge handle or with the remote MENU / SETTINGS key. Movies and Series keep the rail visible as the primary navigation surface.

## Live TV

The TV section preserves the Nyama TV 3.0.12 logic:

- authenticated M3U URL
- forced fresh playlist request on each fresh app start, with cache fallback
- 30-minute playlist refresh
- XMLTV EPG and 24-hour EPG cache
- category / channel browser
- wraparound channel navigation
- Favorites category at the bottom
- EPG current-program overlap fix
- configurable clock, themes, language and text size
- TV remote and phone touch controls
- stable per-device NyamaTV/3.0 network identity
- lightweight Media3 playback only

Switching from TV to Movies / Series / Settings explicitly stops and clears TV playback so no channel audio remains behind the next screen. Returning to TV restores the last channel.

## Pukanki.Fun integration

Default backend: `https://pukanki.fun`

The API base URL is configurable under Administrator settings.

Nyama+ uses:

- `/api/movies`
- `/api/series`
- `/api/genres?section=movies|series`
- `/title-info/{imdb_id}`
- `/posters/{imdb_id}.jpg`

Movies and Series are native Android views with search, genres, sorting, favorites, pagination and detail pages. v1.0.2 verifies poster availability and hides titles whose poster endpoint is unavailable.

## Movie / series playback

Player 1 is no longer used. All titles open Player 2:

- Movies: `https://streamimdb.ru/embed/movie/<IMDb ID>`
- Series: `https://streamimdb.ru/embed/tv/<IMDb ID>`

Playback stays inside `MoviePlayerActivity` using Android System WebView. Nyama+ blocks requested new windows and prevents external main-frame redirects from replacing the internal player. BACK first exits HTML5 full screen, returns through WebView history or attempts to dismiss a visible close control, and requires a second BACK before leaving the player when nothing else was handled.

Pukanki currently exposes an embedded HTML player, not a direct authorized media URL and subtitle-track API. Therefore Nyama+ cannot provide a native Media3 subtitle selector yet. Any subtitles available in v1.0.2 are controlled by Player 2 itself.

## First setup

Administrator setup requests device type, Nyama playlist URL, EPG URL, Pukanki.Fun API URL and administrator PIN.

## Self update

Nyama+ checks:

`https://nyama.fun/nyamaplus.apk`

The hosted APK must use package `fun.nyama.plus`, the same signing certificate, and a higher version code.

## Build

Open the project root in Android Studio, allow Gradle sync, then use:

`Build -> Generate App Bundles or APKs -> Generate APKs`

Debug output:

`app/build/outputs/apk/debug/app-debug.apk`

Use one permanent signing keystore for production updates.

## Build note

This source package was statically validated in the generation environment. A full Android build could not be run because the environment has no Android SDK, so build the project in Android Studio before distributing an APK.

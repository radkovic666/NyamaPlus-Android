## 1.0.18 / versionCode 20
- Android TV Movies/Series: OK, Enter, numpad Enter, and media play/pause now try direct HTML5 playback control and fall back to one complete native Space key press, matching StreamIMDB's verified shortcut in normal and fullscreen playback.
- Added temporary `NyamaPlayer` key-routing diagnostics, including key/action/repeat, focus/fullscreen state, and the selected playback strategy.
- Kept LEFT/RIGHT seeking at -10/+10 seconds, with native arrow delivery when a cross-origin player prevents direct HTML5 access.

## 1.0.16 / versionCode 18
- Android TV Movies/Series only: remote OK now falls back to forwarding the real DPAD_CENTER key into the embedded player when its HTML5 video is cross-origin/inaccessible, so OK can toggle play/pause just as LEFT/RIGHT already reach the provider.
- Removed the obsolete TV provider focus-navigation bridge; there is no TAB-key simulation or TAB-navigation code in the TV player path.
- Disabled WebView overscroll/scrollbars/default focus highlight and full-frame embed outlines to remove the thin edge-focus lines seen on some Android TV devices.
- LEFT/RIGHT seeking, BACK behavior, catalogue behavior, live TV and phone mode are otherwise unchanged.

## 1.0.12 / versionCode 14
- Based only on the supplied 1.0.11 / Code 13 project.
- Removed the Movies/Series catalogue pointer and the long-hold scrolling behavior completely.
- Restored real 30-title pagination while keeping the total catalogue count; cached full catalogues are sliced locally instead of being placed into one giant page.
- Next/Previous page changes focus the first poster in the first row of the newly displayed page on Android TV.
- Player 2 on Android TV uses a small player-only coordinate cursor to interact with the provider's own controls through WebView pointer events; no extra Nyama+ player menu is added.
- BACK handling now rejects duplicate callbacks from one physical press and requires a second distinct BACK press before leaving playback.
- Live TV, EPG, authentication, playlists, Settings, Favorites, and phone/mobile behavior are unchanged.

## 1.0.10 / versionCode 12
- Bug-fix patch based only on v1.0.9 Code 11.
- Android TV Movies/Series catalogue no longer collapses to 0 titles when full-catalog poster probing is rate-limited: TV poster filtering now uses bounded GET probes, lower concurrency, retry-on-transient behavior and avoids caching suspicious empty results.
- Posterless Movies/Series titles remain hidden and TV counts/pages are calculated from the filtered visible catalogue. Search uses the same corrected catalogue path.
- Android TV title detail removes the duplicated summary metadata; title/year/runtime/rating/votes/genre/series information and description/details are presented once.
- Android TV Player 2 adds real DPAD spatial focus/click navigation for UP/DOWN/LEFT/RIGHT/OK with normal key fallback for inaccessible frames. No TAB simulation or mouse/hover bridge is used.
- Android TV BACK first dismisses/falls back to ESCAPE and requires a second press before leaving playback.
- Mobile layout/control behavior, live TV, EPG, authentication, playlist and settings logic are unchanged.

## 1.0.8
- Android TV-only catalog card information area now shows year/runtime/rating, genres and available series/vote metadata without crushing the text under posters.
- Android TV title-detail WATCH/FAVORITE buttons are smaller.
- Android TV Player 2 remote handling moved to Activity dispatchKeyEvent so WebView cannot swallow DPAD/OK before Nyama+ handles it.
- Player 2 native control strip now has deterministic manual DPAD navigation and OK activation, including UP/DOWN.
- Android TV catalog sidebar uses properly sized vector icons and rail padding so icons are no longer clipped.
- Android TV Movies/Series search field includes an in-field X clear control.
- Phone/mobile UI behavior remains unchanged.

## 1.0.7
- Fixed Android TV left-sidebar remote navigation: UP/DOWN now explicitly moves through TV, Movies, Series and Settings.
- Added quick smooth progressive left/right folding in the Live TV browser.
- Moving right collapses navigation rails to give more room to channels/EPG; moving left unfolds them.
- Phone/mobile layout remains unchanged.

# Nyama+ changelog

## 1.0.1
- Live TV is now the launcher and restores the last watched channel.
- Added TV/Movies/Series/Settings left navigation.
- Player 2 only, with internal popup/redirect containment.
- Redesigned native movie/series catalog and hide posterless titles.


## 1.0.0

- New separate application ID: `fun.nyama.plus`.
- Based on Nyama TV 3.0.12 live-TV logic.
- Added Nyama+ native home navigation: Live TV / Movies / Series / Settings.
- Added native Pukanki.Fun Movies and Series catalogs.
- Added poster grid, search, genres, sorting and pagination.
- Added native title-information screen.
- Added local Movies/Series favorites.
- Added internal MoviePlayerActivity with Player 1 / Player 2 selection.
- Movie/series playback remains inside the app using Android System WebView.
- Added Pukanki.Fun API URL to PIN-protected Administrator settings.
- Nyama+ updater URL is `https://nyama.fun/nyamaplus.apk`.
- New Nyama+ app icon and Android TV banner.
- Preserved lightweight Media3-only live-TV playback; no LibVLC dependency.

## 1.0.5
- Removed the floating NY+ live-TV navigation handle/overlay.
- Moved Movies, Series and Settings into the normal channel browser category list.
- DOWN/OK opens one unified TV/app menu; MENU/SETTINGS focuses the same browser.
- Movie/series playback opens borderless fullscreen with no Player 2 label.
- Faster debounced catalog search and faster poster filtering.

## 1.0.9 / Code 10
- TV channel menu reduced to two fold states only.
- Movie/series vote totals use compact K/M formatting.
- Android TV Player 2: DPAD RIGHT dispatches a native TAB key event into the WebView.

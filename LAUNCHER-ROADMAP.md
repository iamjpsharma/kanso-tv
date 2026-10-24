# Launcher Analysis & Improvement Roadmap

**Scope:** the custom `com.kansotv.launcher` launcher on the TCL G13 (Android 14, 1 GB RAM,
1280×720). This is a plan, not a list of changes already made.

## 1. Current snapshot

The launcher is a native Java app with one Activity and a deliberately custom D-pad navigation model:

- **Home:** rotating local photo wallpapers, dark gradient scrim, large Outfit clock, date, greeting.
- **Dock:** 5 configurable apps (Netflix, Prime Video, JioHotstar, YouTube, Live TV).
- **Grid:** 5-column app grid, alphabetical, with banner art and a sampled colour glow.
- **Pills:** Inputs, Wi-Fi, Settings.
- **Phone remote:** page on port 8080 with pairing, trackpad, keyboard, volume, apps, inputs,
  photo upload/framing, Siri shortcuts.
- **Extras:** follower counters, wallpaper rotation, HDMI/AV switching, low-memory rendering.

Current measured footprint: ~41 MB PSS while the launcher is open; APK ~4.6 MB. That is healthy for a
1 GB TV.

### What is already strong

1. **Fast, predictable D-pad movement.** Focus is handled manually rather than relying on Android
   focus search, so Up/Down always moves one row and Back returns to the dock.
2. **No ads, no recommendations, no Google TV clutter.**
3. **Good visual identity.** Photos, glass highlight, sampled glows, and Outfit typography feel
   intentional rather than like a default TV launcher.
4. **Phone remote is unusually capable.** Trackpad/keyboard injection, photo framing, inputs, and
   Siri shortcuts are more than most launchers offer.
5. **Low dependency footprint.** No Play Services requirement for the launcher UI.

### Gaps and rough edges

| Area | Observation | Impact |
|---|---|---|
| Greeting | It had only morning/afternoon/evening; 00:00–04:59 fell into evening. | **Fixed:** added Good night (21:00–04:59). |
| Clock | 12-hour format shows `3:56` without AM/PM. | Ambiguous at a glance. |
| Layout configuration | Dock and hidden lists live in `config.xml`; changing them needs a rebuild. | High friction for normal use. |
| App organisation | Only one alphabetical grid; no favourites, recents, or categories. | Fine for 10 apps, limited as the list grows. |
| Search | No app search on the TV and no filter in the phone Apps tab. | Slow to find a less-used app. |
| Content discovery | No Recently used, Continue watching, or Watch Next row. | The home screen is app-first, not content-first. |
| Ambient mode | No screensaver/Daydream; wallpapers only rotate while Home is open. | Missed opportunity for photos and clock. |
| Context | No weather or other glanceable information. | The top area is mostly empty. |
| App actions | No long-press menu for App info, Hide, or Uninstall. | Management requires ADB/phone. |
| Accessibility | Custom text views have no content descriptions; UI strings are hardcoded English. | Weak TalkBack/localisation story. |
| Launch errors | `launch()` swallows exceptions silently. | A failed app gives no feedback. |
| Social counters | Scraped from public pages; can silently go stale. | Fragile, and not essential. |
| Target SDK | `targetSdk 28`. | Old; future Android TV compatibility risk. |

## 2. Research: what comparable launchers offer

Useful benchmarks, not features to copy blindly:

- **Projectivy Launcher** — categories, Watch Next, parental controls, icon packs, custom wallpapers,
  input shortcuts, boot app, backups, 24 languages.
  <https://projectivylauncher.com/>
- **FLauncher** — open source; custom categories, manual reordering, app info/uninstall, row/grid
  switch, wallpaper, navigation sounds.
  <https://github.com/nileshchakraborty/flauncher>
- **Arc Launcher** — OLED-friendly screensaver, favourites, data-usage widget, custom banners,
  accent colours, focus-indicator improvements.
  <https://github.com/realam/arclauncher>
- **Couchy Launcher** — single-screen, fully D-pad, 18 languages, local settings, no network.
  <https://github.com/conreo/couchy-launcher>

### Platform capabilities and constraints

- **Watch Next / Continue Watching** is a real Android TV surface, but it depends on apps publishing
  programs. A shell query on this TV returned no rows. A third-party launcher may also need TV
  provider access. Treat this as a **research spike**, not a promised feature.
  <https://developer.android.com/training/tv/discovery/watch-next-programs>
- **Global/app search** can be exposed through Android TV search providers and `ACTION_SEARCH`, but a
  small local app filter is simpler and more reliable than depending on Google TV search.
  <https://developer.android.com/training/tv/discovery/in-app-search>
- **Screensaver** can be implemented with a `DreamService`; Android TV exposes it as a screen saver.
  A custom photo/clock dream is feasible without root.
  <https://developer.android.com/reference/android/service/dreams/DreamService>
- **Recent apps** can use `UsageStatsManager`, but it needs a special user-granted permission.
  A simpler first version can remember apps launched from this launcher.
  <https://developer.android.com/reference/android/app/usage/UsageStatsManager>
- **Weather** can use Open-Meteo with no API key, non-commercial terms, and the launcher already has
  coarse-location permission. It still needs a privacy/toggle decision.
  <https://open-meteo.com/>
- **1 GB RAM** rules out heavy video wallpapers, large recommendation backfills, and many concurrent
  image loads. Prefer cached, small assets and lazy loading.

## 3. Recommended roadmap

### P0 — Quick wins (highest value per hour)

| # | Improvement | Why | Effort | Risk |
|---|---|---|---|---|
| 1 | **Greeting fix** | Correct at night; done and verified at 03:56. | Done | None |
| 2 | **Clock clarity** | Add AM/PM in 12-hour mode, or use 24-hour time consistently. | S | None |
| 3 | **Phone layout manager** | Hide/show apps, reorder the dock and grid, and edit social handles from the phone. Removes the rebuild loop. | M | Low |
| 4 | **Apps search/filter on phone** | Instant filter in the existing Apps tab. | S | None |
| 5 | **Launch-failure feedback** | Show a small toast when an app cannot open. | S | None |
| 6 | **Launcher settings menu** | Make the Settings pill open a small menu: Launcher settings, System settings, About. | S | Low |

### P1 — High-value features

| # | Improvement | Why | Effort | Risk |
|---|---|---|---|---|
| 7 | **Recently used row** | A “Recent” row from apps launched through this launcher; no special permission. | M | Low |
| 8 | **Weather pill** | Open-Meteo current temperature/condition; cache 30 min; optional toggle. | M | Low–medium |
| 9 | **Voice/Assistant pill** | Launch the system voice command/Assistant from a mic button. | S | Device-dependent |
| 10 | **TV app search overlay** | Type on the phone or remote to filter the grid without leaving Home. | M | Medium |
| 11 | **Long-press app actions** | App info, Hide, and optionally Uninstall. | M | Medium |
| 12 | **Photo screensaver** | A `DreamService` using the same wallpaper store, with a moving clock to avoid burn-in. | M–L | Medium |

### P2 — Bigger bets

| # | Improvement | Why | Effort | Risk |
|---|---|---|---|---|
| 13 | **Favourites + categories** | Group apps and keep a Favourites row above the grid. | L | Medium |
| 14 | **Watch Next / Continue watching** | Content-first row if the TV provider exposes data and permission works. | L (spike first) | High |
| 15 | **Custom banners/icon packs** | Let the phone replace a tile’s art or label. | M | Low |
| 16 | **Parental controls / app lock / bedtime** | Family use and schedule control. | L | Medium |
| 17 | **Localisation + accessibility** | Move strings to resources; add Hindi/Punjabi and content descriptions. | M | Low |
| 18 | **Settings backup/restore** | Export/import launcher preferences from the phone. | M | Low |
| 19 | **Raise target SDK** | Keep compatible with newer Android TV and Play requirements. | M | Medium |

## 4. Suggested first release: “P0.1”

The next build should contain only these, so testing stays simple:

1. Clock clarity (AM/PM or 24-hour).
2. Phone layout manager:
   - Hide/show apps.
   - Reorder dock and grid.
   - Edit greeting name and social handles.
3. Apps-tab filter.
4. Launch-failure toast.
5. Settings menu.

This removes the biggest daily friction without adding network or permission complexity.

### Acceptance criteria

- Changing a hidden app or dock order on the phone updates the TV without rebuilding the APK.
- BrowseHere and YouTube Music can be shown/hidden from the phone and the choice survives a reboot.
- The app grid remains smooth on the 1 GB TV; no new ANRs or visible jank.
- A failed launch shows a readable message instead of doing nothing.
- The clock is unambiguous at 03:56 and 15:56.
- Existing phone pairing, Live TV, USB storage, and Home-key behavior remain intact.

## 5. Decisions needed from you

1. **Clock:** 12-hour with AM/PM, or 24-hour time?
2. **Weather:** add it? If yes, use approximate location or a fixed city?
3. **Voice:** add an Assistant/mic button, or keep the top row to Inputs/Wi-Fi/Settings?
4. **Screensaver:** photo + clock screensaver, or leave the TV’s current ambient mode alone?
5. **Next build:** start with the “P0.1” list above, or pick individual items?

## 6. References

- Android TV navigation guidance: <https://developer.android.com/training/tv/get-started/navigation>
- Watch Next: <https://developer.android.com/training/tv/discovery/watch-next-programs>
- Searchable TV apps: <https://developer.android.com/training/tv/discovery/in-app-search>
- Daydream / DreamService: <https://developer.android.com/reference/android/service/dreams/DreamService>
- Usage stats: <https://developer.android.com/reference/android/app/usage/UsageStatsManager>
- Open-Meteo: <https://open-meteo.com/>
- Projectivy: <https://projectivylauncher.com/>
- FLauncher: <https://github.com/nileshchakraborty/flauncher>
- Arc Launcher: <https://github.com/realam/arclauncher>

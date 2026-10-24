# Kanso TV — TCL G13 Completion Record

**Status: complete (2026-10-21).** The custom launcher is Home, the phone remote is paired, Live TV
plays from the dock, the approved debloat is applied, and the Kingston adopted storage is intact.

**TV:** TCL G13, Android 14, MediaTek mt5867, 1280×720, 1 GB RAM, `armeabi-v7a`.

**Storage:** Kingston USB is still adopted/internal. `/storage/emulated` is ~14 GB, and the adopted
volume mounted correctly. The drive was not un-adopted, formatted, or reset.

## Delivered

### 1. Custom launcher as Home

- Package: `com.kansotv.launcher`
- Home resolves to `com.kansotv.launcher/.HomeActivity`.
- Dock: Netflix, Prime Video, JioHotstar, YouTube, Live TV.
- Grid: BrowseHere, File Manager+, TV, MMP, MX Player, NetMirror, Play Store, SonyLIV,
  YouTube Music, Z5.
- Greeting: **Jaiprakash Sharma**; handles `@iamjpsharma` / `iamjpsharma`.
- Time-aware greeting: **Good morning** (05–11:59), **Good afternoon** (12–16:59),
  **Good evening** (17–20:59), **Good night** (21–04:59).
- Personal wallpaper is active and rotates.
- TV, MMP, and Z5 were deliberately kept visible.

TCL's `com.tcl.ttvs` intercepts the Home key and explicitly starts the stock Google TV launcher, so
manifest priority and preferred-activity settings were not enough. The fix was to disable the stock
launcher and its setup fallback:

```sh
tools/tv shell "pm disable-user --user 0 com.google.android.apps.tv.launcherx"
tools/tv shell "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
```

Both are installed and re-enableable; they are not uninstalled.

### 2. Phone remote

- Page: `http://<TV_IP>:8080` (HTTP 200 verified after the final reboot).
- Pairing approved on the TV; the page is served by the launcher's `PhoneService`.
- Key injection uses the TV's local ADB at `127.0.0.1:5555`.
- `SYSTEM_ALERT_WINDOW` app-op is `allow` so the pairing prompt can appear over other apps.
- The pair button/overlay path was the issue at first; it was fixed and confirmed working.

### 3. Live TV

- Package: `com.nk.live_tv` (Flutter), installed from `apps/live-tv`.
- Dock tile **Live TV** opens it; categories and 1080p-only channels load.
- Playback was verified with channels; the player resumes the last channel when reopened.
- The 1 GB TV ran it successfully; no memory failure was observed during the test.

Two compatibility changes were needed for the installed Flutter 3.41 / Dart 3.11 SDK:

- `wakelock_plus` is constrained to `>=1.7.0 <1.8.0` (1.8.x requires Dart 3.12).
- `apps/live-tv/lib/screens/home.dart` uses `cacheExtent: rowExtent` instead of the newer
  `scrollCacheExtent` API. This preserves the same pixel cache behavior.

### 4. Safe TCL debloat

Profile: `profiles/tcl-g13.txt`. Applied with `./disable.sh profiles/tcl-g13.txt`.

Disabled packages (all reversible, nothing uninstalled):

| Package | Reason |
|---|---|
| `com.google.android.apps.tv.launcherx` | stock Google TV Home, replaced by custom launcher |
| `com.google.android.tungsten.setupwraith` | stock setup fallback, steals Home |
| `com.tcl.logkit` | TCL logging/telemetry |
| `com.google.android.feedback` | feedback/telemetry |
| `com.tcl.messagebox` | TCL message box |
| `com.mediatek.MtkDemo` | MediaTek demo app |
| `fusion.android.tv.demo` | demo app |

Deliberately kept: Play Movies (`com.google.android.videos`) and Play Games
(`com.google.android.play.games`), per user request. BrowseHere (`com.tcl.browser`) and YouTube Music
(`com.google.android.youtube.tvmusic`) are enabled and visible.

### 5. YouTube auto-start

Dropped at the user's request. The YouTube screen seen during the final check was opened manually by
the user, not started automatically. No TV Center components were disabled.

## Final verification

- Rebooted after the debloat; boot completed without a setup wizard or boot loop.
- Custom Home returned as the top activity.
- All seven disabled packages remained disabled.
- Kingston storage mounted; `/storage/emulated` available; data intact.
- Phone page returned HTTP 200.
- Live TV opened and played after reboot.
- `restore.sh` package re-enable and `HOME_ACTIVITY` override paths were tested with one package;
  the package was re-disabled afterward and the disabled count returned to seven.
- Fresh backup: `backup/20261021-033500/` (pre-debloat backup: `backup/20261021-030425/`).
- YouTube was opened manually during the final check; it did not auto-start.

## Rollback

Full restore (re-enables the profile, detects the stock launcher, and restores stock Home):

```sh
./restore.sh profiles/tcl-g13.txt
```

Remove the custom launcher too:

```sh
./restore.sh profiles/tcl-g13.txt --uninstall
```

Restore only stock Home (if the custom launcher is unusable):

```sh
tools/tv shell "pm enable com.google.android.apps.tv.launcherx"
tools/tv shell "pm enable com.google.android.tungsten.setupwraith"
tools/tv shell "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
```

Undo one debloat package:

```sh
tools/tv shell "pm enable com.tcl.logkit"
```

Uninstall Live TV:

```sh
tools/tv shell "pm uninstall com.nk.live_tv"
```

## USB recovery

The Kingston drive is adopted; never format it or run `sm set-force-adoptable`, `sm mount`, or other
vold-reset commands.

If it disappears:

1. Settings → System → Developer options → **Select USB Configuration**.
2. Switch **MIDI/Audio Source** → wait → **MTP**.
3. If it still does not mount, restart the TV and recheck. A restart recovered it once after the
   toggle alone did not.

## Compatibility notes

- TCL Home-key interception: `com.tcl.ttvs` starts the stock launcher directly; disabling
  `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith` is what makes
  the custom launcher survive Home presses and reboots.
- While those two are disabled there is no stock Home fallback. Keep ADB access available; use the
  rollback commands above.
- Phone remote: use only on trusted Wi-Fi; it has the same power as network ADB.
- Live TV uses public IPTV links, so individual channels can go offline. The app falls back to
  backup streams where available.

## Keep disabled

Do not disable the TV input services, TV Center, Settings, remote/Bluetooth services, keyboard,
Play Store/Services, or media/settings providers:

`com.mediatek.tv.service`, `com.mediatek.tvinput`, `com.mediatek.wwtv.tvcenter`,
`com.android.tv.settings`, `com.google.android.tv.inputplayer`, `com.android.bluetooth`,
`com.google.android.gms`, `com.google.android.gsf`, `com.android.vending`,
`com.android.packageinstaller`, `com.android.systemui`,
`com.google.android.tv.remote.service`, `com.google.android.tv.googletvremotepairer`.

## Repository status

Configure your own remote and push. The `origin` should point to your own fork.
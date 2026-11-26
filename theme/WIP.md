# Forge Theme and Forge Visualiser: work in progress

Relay task 1293 (2026-11-18). What is verified, and how; what is only reasoned
about; what is next.

## Verified

- Build: `build.sh theme visualiser` makes both APKs, signed and checked by
  apksigner (forge-theme.apk 4.6 MB, forge-visualiser.apk 150 KB), min SDK 26;
  Java compiles clean with `-Xlint:all`.
- `pytest phone/theme/tests`: 15 passed. `check_page.py`: 4/4 (shots looked
  at: Light and Showpiece looks draw; without WebGL 2 the Light look stays,
  Showpiece tiles are dimmed and a note says why).
- Android 14 emulator (no GPU, software GL; 2026-11-18):
  - Visualiser player: controls, settings, looks sheet, demo beat work.
  - Visualiser live wallpaper set on the home screen (dumpsys shows
    `app.forge.visualiser/.VizWallpaper`), drawing full screen; hidden behind
    another app its CPU falls from about 50-60% to 0% within about 10 s.
  - Forge particles wallpaper preview draws a theme picture with particles,
    held at 30 frames a second (app_time_stats about 33 ms).
  - Forge particles wallpaper set on the home screen (dumpsys shows
    `app.forge.theme/.ThemeWallpaper`; `emu-theme-home-page2.png`). Its setup
    logs `ForgeTheme set up 2560x1600 in 293-351 ms` (925x1600 preview: 131
    ms), so the earlier 15 s first frame was the fresh emulator, not us.
  - All four widgets added from the launcher's widget list and seen on the
    home screen (`emu-widgets-all.png`): clock in the theme colours, music
    keys, the particles window (two shots a second apart differ: it
    animates), the visualiser widget. Widget-list previews are readable
    (`emu-widget-picker.png`). The music widget's Next particles press
    restarted the wallpaper with the new kind (logcat `ForgeTheme set up`).
    The visualiser widget's Next look moves Ice ridges to Ember ridges on the
    first press, also from cleared data.
  - Shots: `outputs/checks/2026-11-18-1293/`.
- Particles.java: all 19 kinds step and draw without NaN or per-frame
  allocation in a JVM sweep with a stub canvas (helper's test, session 1).

## Calmer audio sensitivity and maxed-out looks (Relay task 1532, 2026-11-26)

- **Audio pipeline (`Sound.java`, `Prefs.java`, `host.js`, `index.html`, `host.css`)**:
  - Replaced `Sound.java`'s log-dB (`(db + 48) / 44`) + `MAX`-across-bins + post-clamp per-frame `peak` auto-gain with linear RMS across bins (skipping DC bin 0 and dividing `mags[0]` by `128f`), gentle linear high-frequency tilt (`1 + 2.3 * b / 63`), pre-clamp `dt`-based auto-gain with noise floor (`0.02`), soft-knee ceiling (`softCap`, linear to `0.65` then smooth `tanh` rolloff), calmer waveform headroom (`wavePeak` targeting `0.72` by `dt`), `Mic` FFT normalized by `256f` (was `64f`), and squared-energy beat detection. `stop()` resets `sens`, `wavePeak`, `bassAvg`, and `lastNs` while still setting `mode = Mode.OFF`.
  - Added a persistent **Sensitivity** control (`Prefs.SENSITIVITY = "sensitivity"`, default `"1"`) in Settings: 5 chips (`Gentle` 0.55, `Calm` 0.8, `Normal` 1, `Lively` 1.35, `High` 1.75) synced with a 30%–200% slider, shared by both the full-screen player and the live wallpaper (`prefsChanged()`), with `softCap` headroom in `PhoneSound`.
- **Maxed-out visualiser modes (`shared/visualiser/looks/`)**:
  - `chrome.js` (liquid): framed against `min(uRes.x, uRes.y)` with camera at `5.1` so portrait screens no longer clip the drop or hide its moons; softer ripple frequencies + soft input shaping + safer raymarch step (`0.52`) to prevent SDF overstepping/crumpling; calmer moon pull and filmic highlights.
  - `field.js`, `orb.js`, `warp.js`: scaled additive point/streak brightness (and density scaling in `orb.js`) with soft compression to eliminate solid `#ffffff` clipping on loud audio.
  - `skyline.js`, `flight.js`, `aurora.js`, `classic.js`: balanced window lighting contrast, soft-capped ridge height, stronger aurora tone-mapping (`0.52`), and proportional orbit sizing (`p.size * 0.75`) so `Forge orbit` stays inside short frames while keeping the desktop Size slider responsive.
- **Measured before/after on the same loud audio** (`outputs/checks/2026-11-26-1532-android-viz/{before,after-final}/metrics.json`):
  - Loud real EDM (`Riot.mp3`, 10 s, 60 fps): `band_mean` `0.590 -> 0.163`, `band >= 0.85` `2.34% -> 0.00%`, `level_mean` `0.590 -> 0.302`, `bass_mean` `0.756 -> 0.353`, `wave >= 0.95` `0.32% -> 0.00%`, beats `7 -> 21` (tracks 128 BPM cleanly).
  - Loud synthetic song (`sound.py`, 8 s): `band >= 0.85` `8.67% -> 0.00%`, `bass_mean` `0.800 -> 0.408`, `wave >= 0.95` `1.80% -> 0.00%`, beats `7 -> 15` (120 BPM). Silence rests at `0.0` (`silent_pct = 100%`).
  - Canvas white-clipping on loud audio: `field` `4.03% -> 0.00%`, `mono-field` `4.44% -> 0.00%`, `orb` `1.03% -> 0.00%`, `violet-orb` `1.98% -> 0.00%`, `warp` `0.76% -> 0.00%`.
- **Verified**: `forge_check.py` clean + `phone/theme/tests` 20 passed (incl. JVM `Sound.java` `-Xlint:all` loud/silence probe); `phone/theme/tests/check_page.py` 12/12 passed; `apps/visualiser` pytest 12 passed, Node tests 37 passed, `check_ui.py` 37/37 passed; `tests/test_desktop_visualiser.py` 7 passed; `tests/test_forge_loopback.py` 25 passed; fresh-context critic review (3 findings: collage em-dash font fixed, `classic.js` orbit Size dead zone fixed with proportional `0.75`, `warp.js` brightness lifted slightly). Shipped in Forge for Android 0.19 (versionCode 19).
- **Not verified**: live playback on Emerson's physical tablet (`Visualizer` session 0 + real microphone).

## Each look's own scale and the four new looks (Relay task 1308, 2026-11-18)

- `host.js` now multiplies the user's sharpness (`scale` / `wallScale`) by
  the look's `scale` from looks.json, as the desktop app does, and sets it
  again whenever a look changes. Before, heavy looks (Northern lights 0.75,
  Chrome 0.7) drew every screen pixel on a phone.
- Verified headless: `check_page.py` 10/10 (shots in
  `outputs/checks/2026-11-18-1308-viz-scale/`), new cases measure the
  canvas: Northern lights 0.75 in the player and 0.563 on the wallpaper
  (0.75 x 0.75), Chrome 0.7, Light looks 1. A control run on the previous
  host.js failed exactly those three cases (7/10). The four looks of task
  1297 (Ice tunnel, Night city, Warp, Northern lights) draw with no page
  errors; make_assets copies them (21 presets listed). pytest 15 passed.
- Not verified then: on a real phone or the emulator; it reached the APK in
  version 0.2 (task 1315, below).

## 90 and 120 frames a second (Relay task 1320, 2026-11-18)

- The full-screen chips come from the shared list (`ForgeViz.RATES` in
  `shared/visualiser/viz_core.js`): 30, 60, 90, 120 (the desktop's "as fast
  as the screen" left out for the battery). `Pacer.setWanted` caps at 120
  (was 60). A line under the chips (`#rateNote`) says plainly when the screen
  is slower than the pick; the page measures the screen as the player starts
  (`ForgeViz.refreshRate`). The battery rules still win (15 on Battery
  Saver or hot, a still picture under 15%).
- `Bridge.fps()` tells `PlayerActivity.rate(fps)` when the allowed rate
  changes; above 60 it asks Android for the screen mode at the present size
  with the lowest refresh rate that reaches it (`preferredDisplayModeId`;
  90 for 90 when the phone has it, else 120); 60 or less leaves it to the
  system. The wallpaper keeps its own setting (30 by default).
- PhoneSound's wave fade and the shared looks' trails now go by time, so a
  look moves at the same speed at 30 or 120.
- Verified: `check_page.py` 11/11 headless (new case: chips 30,60,90,120 and
  an allowed 120 named in the note; shots in
  `outputs/checks/2026-11-18-1320-visualiser/phone/`); the Java compiled
  in a scratch javac (aapt2 R + javac, exit 0). Not verified: a real 90 or
  120 Hz phone (none here), whether a phone's WebView follows the mode
  asked for, and an APK build with these files (task 1322's 0.6.x OTA
  carries it).

## Forge Visualiser 0.2 APK (Relay task 1315, 2026-11-18)

- `visualiser/AndroidManifest.xml` version 2 (0.2), so it installs over the
  0.1 sent to Emerson that morning (task 1293) as an update. Built with
  `build.sh visualiser` (167 KB): same signing certificate as 0.1 (apksigner
  SHA-256 0d58a9e7...), 13 looks and 21 presets inside, its `host.js` byte
  for byte the committed one with task 1308's scale.
- Verified on the Android 14 emulator (forge-tab, software GL): 0.1
  installed, then 0.2 installed over it with `adb install -r` (version 2,
  the granted microphone permission kept); the player starts; Ice tunnel,
  Night city, Warp and Northern lights picked from the Looks sheet each draw
  and move (two shots differ), the Showpiece tiles are not dimmed there
  (the emulator's WebView has WebGL 2 through SwiftShader), and logcat shows
  no ForgeViz warnings or crashes. Shots:
  `outputs/checks/2026-11-18-1315-viz-apk/emu-new-looks.png`, `emu-looks-sheet.png`.
  The looks are dim there because the emulator has no sound.
- `check_page.py` 10/10 again on this tree (same folder, player-*.png).
- Not verified: the canvas scale inside the APK's WebView (measured only in
  headless Chrome), the 0.2 wallpaper on the emulator, a real phone.

## Fixed after the widget check (2026-11-18, session 4)

- The widget panel was white before the theme tint arrived, so the widget
  list showed white text on white; it is now dark (`common/res/drawable/panel.xml`).
- The visualiser widget clipped its title at the launcher's 100 dp height;
  its layout is tighter and its smallest resize height is 100 dp.
- Next look on the visualiser widget did nothing on the first press when no
  look was saved yet (`Presets.after`).
- Opening the player redraws the visualiser widget (after Clear data its
  buttons did nothing until then).

## Critic review (2026-11-18) and what changed

A fresh reviewer read all the code. Fixed:

- Particles stopped reacting to music for good once the wallpaper had been
  hidden (Sound.stop kept its mode). Now stop() forgets it; a test guards it.
- Below 15% battery the wallpaper kept listening while showing a still
  picture, and did not move again after charging. Now listening follows
  moving frames, and a still picture looks again every 30 s.
- The drawing thread woke on every screen refresh between frames; it now
  sleeps until about the next frame and tells Android 11+ the frame rate.
- Particles widget pictures had guessable names in a readable provider
  (a frame can show your own picture): now 64 random bits per drawing.
- The particles widget keeps its process awake until its frames are written.
- Smaller: next particle kind from Off starts at the first kind; the clock
  widget no longer shows a greeting that could go stale; the settings screen
  relays out when the tablet turns; the music widget shows its Visualiser
  button once Forge Visualiser is installed (on opening Forge Theme); a
  still visualiser picture stops listening; quick look changes can no longer
  bring back an older look.
- Both wallpapers now hand Android their colours (the Forge theme's, or the
  visualiser look's), so Android 12+ colours the system to match.

Left as they are: the Visualizer giving silence is not detected (it cannot
tell silence from no music); the visualiser widget uses its own dark colours;
sound starts and stops on the main thread in the visualiser wallpaper (a
short hitch at most); no icon pack (not asked for).

## Not verified yet (reasoned only)

- Real audio: Visualizer on session 0 (this device's sound) and the
  microphone can only be proven on a real device; some devices give silence
  for session 0, then it falls back to the microphone.
- Real GPU: Showpiece looks (WebGL 2) were seen in desktop Chrome, not on the
  emulator (no WebGL 2 there without a GPU).
- Swiping home pages: the emulator's launcher had one page, so the wallpaper
  pan across pages was not seen (the code follows `onOffsetsChanged`).
- Touch sparks and music reaction of the particles wallpaper (no sound on the
  emulator).
- Additive glow (PorterDuff ADD) on Android 8 hardware canvases.

## Next

- On his tablet: real sound (phone output and microphone), Showpiece looks
  on the real GPU, page panning, battery over an evening.
- The Forge job-status widget (task 1301) is built into the main Forge app
  (`phone/android`, APK 0.5), which holds the computer pairing; it reuses
  `common/res/drawable/panel.xml` (build.sh there compiles `../theme/common/res`).

## How the emulator check was done

- Kit and AVD from task 1292: `ANDROID_AVD_HOME=~/.local/share/forge/android/avd
  emulator -avd forge-tab -no-window -no-audio -gpu swiftshader_indirect
  -no-snapshot -no-boot-anim`; adb is
  `~/.local/share/forge/android/sdk/platform-tools/adb -s emulator-5554`. One
  emulator at a time: ask on the crew board first; stop it with `adb emu kill`.
- Live wallpaper: `am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER
  --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT
  app.forge.theme/.ThemeWallpaper`, then tap Set wallpaper; check with
  `dumpsys wallpaper | grep mWallpaperComponent`.
- Widgets: long-press empty home space (`input swipe X Y X Y 1500`), tap
  Widgets (positions from `uiautomator dump`), tap the app in the list, then
  drag the preview with raw events: `input motionevent DOWN x y; sleep 1.5;
  input motionevent MOVE ...; input motionevent UP ...` (`input draganddrop`
  did not place widgets on this launcher). `uiautomator dump` fails with
  "could not get idle state" while a live wallpaper animates: use screenshots.
- Clean up: uninstall both apps (wallpaper and widgets go with them).
- More visualiser looks arrive from `shared/visualiser` (task 1294) with the
  next build.

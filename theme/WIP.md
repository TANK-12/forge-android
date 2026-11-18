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

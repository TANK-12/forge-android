# Forge Theme and Forge Visualiser (Android)

Two small Android apps that bring Forge's look to a phone or tablet's home
screen. They sit beside the main Forge app (`phone/android`, `app.forge.phone`)
and do not need it. Built for Emerson's tablet (MediaTek Dimensity 7300, 8 GB),
Android 8.0 (API 26) or newer. Relay task 1293.

**Folded into Forge for Android 0.6** (task 1316): `phone/android/build.sh`
compiles these same sources, resources and assets into `app.forge.phone`
(Visualiser and Wallpaper and widgets on its home page), so one install has
everything. The standalone apps below still build and work (they are no
longer needed); their resource names carry a `viz_` or `theme_` prefix so
both builds can share them, and the frames provider's authority follows the
package (`<package>.frames`) so both can be installed at once.

## Forge Theme (`app.forge.theme`)

- **Forge particles live wallpaper**: any of Forge's 19 particle kinds (ported
  from `shared/forge_particles.js`) over a Forge theme picture (43 themes from
  Forge's registries) or a picture of your own. The picture pans as you swipe
  across home-screen pages; a tap pushes particles and makes sparks; it can
  move to music (this device's own sound or the microphone, off by default).
- **Settings screen** with a live preview: theme, picture, particle kind,
  amount, size, speed, dimming, frame rate, sound, taps, then Set as wallpaper.
- **Widgets**: Forge clock (theme colours, no wake-ups), Forge particles (a
  small looping window), Forge music keys (play, pause, skip; works with any
  music app, no permission).

## Forge Visualiser (`app.forge.visualiser`, its own icon)

- **Full-screen player** for whatever is playing: the shared Forge visualiser
  looks (`shared/visualiser`, task 1294), grouped as **Light** (smooth on any
  tablet, easy on the battery) and **Showpiece** (rich 3D looks that want
  strong graphics, WebGL 2). Swipe or arrows for the next look, Looks and
  Settings sheets, music keys.
- **Live wallpaper** with the same looks behind the home screen.
- **Widget**: the look in use, next look at a tap, music keys, full screen.
- Sound comes from this device's own output (Android's Visualizer, which asks
  for the microphone permission although it only reads what is playing) or
  from the microphone. Nothing is recorded or sent anywhere.

## Battery

Both draw only while they can be seen (0% CPU when hidden, measured on the
emulator), at 30 frames a second at most (a setting; the visualiser's full
screen offers 30, 60, 90 or 120 and asks Android for a screen mode that fast,
task 1320), 15 on Battery Saver,
and a still picture under 15% battery. Sound is listened to only while
drawing. Showpiece looks draw at a lower resolution by default.

## Install

Copy `forge-theme.apk` and `forge-visualiser.apk` to the tablet and open them
(Android asks once to allow installs from that app). Then:

- Wallpaper: open Forge Theme, pick a look, tap Set as wallpaper (or long-press
  the home screen > Wallpapers > Live wallpapers > Forge particles / Forge
  visualiser).
- Widgets: long-press the home screen > Widgets > Forge Theme or Forge
  Visualiser.

## Building

`./phone/theme/build.sh theme visualiser` builds both into `phone/theme/build/`
without Gradle (aapt2, javac, d8, zipalign, apksigner from the Android kit in
`~/.local/share/forge/android`, signed with the main Forge APK's key in
`~/.config/forge/android`). `make_assets.py` builds the assets from Forge's
registries (themes, wallpaper pictures, particle kinds, the shared visualiser
looks), so new Forge themes or looks arrive with the next build.

Layout: `common/` (shared Java: `app.forge.look` with Sound, SceneLoop, Pacer,
Themes, Background, Particles, MediaKeys; shared drawables), `theme/` and
`visualiser/` (each app's manifest, res, src; the visualiser's page in
`visualiser/assets/host/`), `tests/`.

## Checks

- `phone/.venv/bin/python -m pytest -q phone/theme/tests` (assets, registries,
  manifests).
- `phone/.venv/bin/python phone/theme/tests/check_page.py [OUT]`: the
  visualiser page in headless Chrome with a pretend Android side
  (`tests/fake_android.js`): a Light and a Showpiece look in the player, the
  wallpaper mode, a screen without WebGL 2, the four looks of task 1297, and
  that each look draws at its own looks.json scale times the user's
  sharpness (task 1308).

Status and what is verified: `WIP.md`.

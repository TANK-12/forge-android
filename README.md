# Forge for Android

A small Android app for Forge, the desktop tool hub. One WebView.

**0.17:** Forge's wallpaper, particles and clock across a touch-friendly Home,
Apps, Wallpaper, Computers and Settings shell. Search all 19 bundled tools
and pin favourites to Home. Tablet rail, phone dock, and the same saved app
data and computer pairings.

Wallpaper offers a real animated preview **inside Forge**, separate from
applying it to Android. Wallpaper check shows this device's model/API,
possible system pickers, wallpaper policy, Android's selected service and
particle/battery settings. Try another Android screen explicitly retries a
fallback. A missing system picker remains a system limitation: a normal app
cannot silently install a privileged live-wallpaper picker. Still pictures
are always labelled separately. Android14 emulator application, changing
launcher frames and cancellation after process death were checked; Emerson's
physical BNCF tablet has not been checked. Current details: ../WIP.md.

The app includes:

- **Pair with your computer** (home Wi-Fi): on the computer open Forge,
  Devices, Phones, switch on the phone link and choose Show a pairing code.
  Scan the app's picture with the phone's camera (a `forge-phone://pair`
  link), paste that link, or type the address, check code and pairing code.
  The app trusts the computer's own certificate only when it matches the
  check code, and sends nothing before that; it keeps only the computer's
  address, that certificate's fingerprint and the sign-in cookie the
  computer gives it (no password). Home-network addresses only. The start
  page then lists the apps the computer offers this phone. If the computer
  answers with another certificate, the app refuses it: forget the computer
  and pair again. Forget this computer also signs the phone out there.
- **Your own laptop over Tailscale** (the owner's way, folded away): type the
  laptop's Tailscale name ending in `.ts.net`, then sign in with its
  password and authenticator code.
- **On this phone**: Forge apps bundled in the APK that run with no computer
  at all: Focus, Mind test and the local AI, plus MIDI Studio, Loops, Reps,
  Devotion, Bible, Prompts, Notes, Recall, Tokens and Line prints in 0.10.
  Their saves stay on the phone, separately from the computer. Local AI
  needs its model downloaded once; the other listed tools are bundled.
  Exported files offer a Download link, then Android asks where to save.
  MIDI uses the built-in synths; MIDI-file import/export, sampled instruments
  and Song to MIDI need the computer. Recall supports local pictures/sounds
  and CSV export; file imports and Anki packages need the computer.
- **Forge jobs widget** (0.5): a home-screen widget showing what a paired
  computer is working on: Relay tasks running, waiting and needing you, the
  last finished one, and a render or long job with its measured progress and
  time left. Tapping it opens that computer's Jobs page in the app; the arrows
  ask again, and with several computers a second key picks the next one. It
  asks when the app is open, on a tap, and otherwise at most every 30
  minutes, only on unmetered Wi-Fi with the battery not low. When the
  computer does not answer it says so in plain words and stops asking until
  the next tap or app open (no wake-ups). The computer must allow it: on
  the computer, Devices, Phones, tick "jobs" for this device. Same dark panel
  as the Forge Theme widgets, tinted with the computer's theme colours.
- **Visualiser** (0.6, folded in from Forge Visualiser): the big tile on the
  home page opens the player with all its looks and presets (Ice tunnel,
  Night city, Warp, Northern lights and the rest), drawn to the music the
  phone plays (it asks for the microphone permission once). Also a live
  wallpaper (Forge visualiser) and a home-screen widget.
- **Wallpaper and widgets** (0.6, folded in from Forge Theme): Forge's themes
  and particles as a live wallpaper, plus the clock, particles and music
  widgets. The theme picked there also colours the app's home page.
- **Updates over the air** (0.6): the About card shows the version and a
  Check for updates button; the app also looks by itself every 12 hours
  while it is open. It asks each paired computer, the owner's laptop and the
  latest release on this page's Releases. A newer build is downloaded,
  checked (size, SHA-256, same app, newer version, same signing key) and
  handed to Android's installer, which asks you to confirm (Android 12 may
  skip the question once Forge installed itself before). A build published
  with another signing key is skipped, and a file that turns out wrong is
  passed over next time, so another source's copy is offered. It installs over
  the old one, so computers, settings and the theme stay. The first time,
  Android asks once to allow installs from Forge.
- Links to other sites open in the phone's browser. Downloads from the
  computer go to the phone's Downloads folder.

Android 8.0 (API 26) or newer. No cleartext traffic, no backups of app data.

## Install

Download `forge.apk` from the [Releases](../../releases) page and open it.
Android asks once to allow installs from your browser.

From 0.6 on, later versions arrive inside the app (About, Check for
updates). 0.6 installs over 0.5 by opening its APK the usual way; the
separate Forge Visualiser and Forge Theme apps are no longer needed (set the
wallpaper and widgets again from Forge before removing them).

**Status (0.17):** built and checked in headless Chrome and an Android14
emulator; real tablet firmware/picker compatibility remains unverified.
Forge Settings → Check for updates installs over the old version with the
same signing key. Existing user data, pairings and preferences stay.

## Shipping a phone build

For whoever fixes something in the app (a Relay session included):

1. Raise `android:versionCode` (by one) and `android:versionName` in
   `phone/android/AndroidManifest.xml`. Phones are only offered a higher
   versionCode.
2. `phone/android/build.sh --publish "what changed, in plain words"`: builds
   and puts the APK where the computer's phone link hands it out
   (`phone/phone_updates.py`, folder `~/.local/share/forge/phone/updates`,
   `GET /hub/api/phone-app` for signed-in phones only). Paired phones at
   home see it at their next check.
3. For phones away from home: `phone/.venv/bin/python
   phone/android/publish.py v0.N "notes"` makes a GitHub release with
   `forge.apk` and `forge-android.json` (the app reads the latest release's).
   It scans for private text first; `--dry-run` stops after the scan.

Always the same signing key (`~/.config/forge/android/release.jks`, kept
out of every repo): a build signed with another key is refused by the app
and by Android. A change to `phone/hub.py` still goes through the usual
Forge hand-in (`release/drop.py`).

## Lend the phone to the laptop's AI (`termux/`)

`termux/lend.sh` (run in Termux) lends the phone's CPU to a llama.cpp model
split with the laptop over a TLS pre-shared-key link. It needs Python 3.13 or
newer in Termux. See the comments at the top of the script.

## Building

This source lives in the Forge tree (`phone/android`); `build.sh` builds the
APK there without Gradle (aapt2, javac, d8, zipalign, apksigner). The
Visualiser and Wallpaper and widgets come from `theme/` (`phone/theme` in the
Forge tree): the same Java, resources and assets the standalone Forge Theme
and Forge Visualiser apps are built from.

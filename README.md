# Forge for Android

A small Android app for Forge, the desktop tool hub. One WebView:

- **Start page**: type your laptop's private address (a Tailscale name ending
  in `.ts.net`), then sign in with the laptop's password and authenticator
  code. Nothing secret is kept on the phone; the laptop answers only a
  signed-in phone.
- **On this phone**: a few Forge apps bundled in the APK that run with no
  laptop at all (Arcade, Drift Peaks, Focus). Their saves stay on the phone.
- Links to other sites open in the phone's browser. Downloads from the laptop
  go to the phone's Downloads folder.

Android 8.0 (API 26) or newer. No cleartext traffic, no backups of app data.

## Install

Download `forge.apk` from the [Releases](../../releases) page and open it.
Android asks once to allow installs from your browser.

**Status**: built and signature-checked, not yet tested on a real phone or an
emulator. Please report anything that looks wrong.

## Lend the phone to the laptop's AI (`termux/`)

`termux/lend.sh` (run in Termux) lends the phone's CPU to a llama.cpp model
split with the laptop over a TLS pre-shared-key link. It needs Python 3.13 or
newer in Termux. See the comments at the top of the script.

## Building

This source lives in the Forge tree (`phone/android`); `build.sh` builds the
APK there without Gradle (aapt2, javac, d8, zipalign, apksigner).

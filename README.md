# Forge for Android

A small Android app for Forge, the desktop tool hub. One WebView:

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
  at all (Focus). Their saves stay on the phone.
- Links to other sites open in the phone's browser. Downloads from the
  computer go to the phone's Downloads folder.

Android 8.0 (API 26) or newer. No cleartext traffic, no backups of app data.

## Install

Download `forge.apk` from the [Releases](../../releases) page and open it.
Android asks once to allow installs from your browser.

**Status** (0.3): pairing, opening apps, a changed certificate being refused,
forgetting and pairing again were checked in an Android 14 emulator against
a test computer. Not yet tried on a real phone. Please report anything that
looks wrong.

## Lend the phone to the laptop's AI (`termux/`)

`termux/lend.sh` (run in Termux) lends the phone's CPU to a llama.cpp model
split with the laptop over a TLS pre-shared-key link. It needs Python 3.13 or
newer in Termux. See the comments at the top of the script.

## Building

This source lives in the Forge tree (`phone/android`); `build.sh` builds the
APK there without Gradle (aapt2, javac, d8, zipalign, apksigner).

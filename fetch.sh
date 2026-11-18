#!/bin/bash
# Fetch the Android build kit build.sh needs, from the official sources only:
# Eclipse Temurin JDK 17 (api.adoptium.net) and Google's command-line tools,
# then sdkmanager for platform 34, build-tools 34.0.0, platform-tools, and
# (with --emulator) the emulator plus an Android 14 x86_64 image for headless
# checks. About 1 GB without the emulator, 3.8 GB with it.
#   phone/android/fetch.sh [--emulator]   -> ~/.local/share/forge/android
# The signing key is not here: build.sh keeps it in ~/.config/forge/android.
set -euo pipefail
TOOLS="${FORGE_ANDROID:-$HOME/.local/share/forge/android}"
CMDLINE="commandlinetools-linux-11076708_latest.zip"
IMAGE="system-images;android-34;default;x86_64"
mkdir -p "$TOOLS"
DL="$(mktemp -d "$TOOLS/download.XXXX")"
trap 'rm -rf "${DL:?}"' EXIT

if [ ! -x "$TOOLS/jdk/bin/javac" ]; then
  curl -fsSL -o "$DL/jdk.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$DL/jdk" && tar -xzf "$DL/jdk.tar.gz" -C "$DL/jdk" --strip-components=1
  rm -rf "$TOOLS/jdk" && mv "$DL/jdk" "$TOOLS/jdk"
fi
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"

SDK="$TOOLS/sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o "$DL/cmdline.zip" "https://dl.google.com/android/repository/$CMDLINE"
  mkdir -p "$SDK/cmdline-tools" && python3 -m zipfile -e "$DL/cmdline.zip" "$DL/cl"
  rm -rf "$SDK/cmdline-tools/latest" && mv "$DL/cl/cmdline-tools" "$SDK/cmdline-tools/latest"
  chmod +x "$SDK/cmdline-tools/latest/bin/"*
fi
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
want=("platforms;android-34" "build-tools;34.0.0" "platform-tools")
if [ "${1:-}" = "--emulator" ]; then
  want+=("emulator" "$IMAGE")
fi
# The licences have to be accepted once to download; building the APK for
# Forge is the use they cover.
yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null || true
# its progress bar redraws one line with carriage returns: keep the last words only
"$SM" --sdk_root="$SDK" "${want[@]}" | tr '\r' '\n' | grep -v -e '^\[' -e '^ *$' | tail -3 || true
chmod +x "$SDK/build-tools/34.0.0/"* 2>/dev/null || true
ls "$SDK"

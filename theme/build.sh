#!/bin/bash
# Build Forge Theme and Forge Visualiser (Relay task 1293) with no Gradle,
# the same way phone/android/build.sh builds the main Forge APK: aapt2, javac,
# d8, zipalign and apksigner from ~/.local/share/forge/android (fetched by
# phone/android/fetch.sh) and the same signing key in ~/.config/forge/android.
#   phone/theme/build.sh [theme|visualiser ...]
#     -> phone/theme/build/forge-theme.apk, phone/theme/build/forge-visualiser.apk
# Each app is common/src and common/res (shared Java and drawables) plus its
# own folder (manifest, res, src, assets); both carry the assets make_assets.py makes from Forge's registries.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${FORGE_ANDROID:-$HOME/.local/share/forge/android}"
KEYS="$HOME/.config/forge/android"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
BT="$TOOLS/sdk/build-tools/34.0.0"
JAR="$TOOLS/sdk/platforms/android-34/android.jar"
OUT="$HERE/build"
PY="$HERE/../.venv/bin/python"
[ -x "$PY" ] || PY=python3
mkdir -p "$OUT"
apps=("$@")
[ ${#apps[@]} -gt 0 ] || apps=(theme visualiser)
if [ ! -f "$KEYS/release.jks" ]; then
  echo "No signing key in $KEYS: build the main APK once (phone/android/build.sh) to make it." >&2
  exit 1
fi

for app in "${apps[@]}"; do
  SRC="$HERE/$app"
  [ -f "$SRC/AndroidManifest.xml" ] || { echo "no app called $app" >&2; exit 1; }
  STAGE="$(mktemp -d "$OUT/stage-$app.XXXX")"
  trap 'rm -rf "${STAGE:?}"' EXIT
  mkdir -p "$STAGE/assets" "$STAGE/classes"
  "$PY" "$HERE/make_assets.py" "$STAGE/assets"
  if [ -d "$SRC/assets" ]; then cp -r "$SRC/assets/." "$STAGE/assets/"; fi
  if [ "$app" = theme ]; then rm -rf "$STAGE/assets/visualiser"; fi
  if [ "$app" = visualiser ]; then rm -rf "$STAGE/assets/walls"; fi
  # common/res (shared drawables) first, the app's own res over it
  "$BT/aapt2" compile --dir "$HERE/common/res" -o "$STAGE/common.zip"
  "$BT/aapt2" compile --dir "$SRC/res" -o "$STAGE/res.zip"
  # Forge Theme's particles widget: moving pictures per kind (task 1322)
  WRES=()
  if [ "$app" = theme ]; then
    "$PY" "$HERE/make_widget_res.py" "$STAGE/assets/particles.json" "$STAGE/wres"
    "$BT/aapt2" compile --dir "$STAGE/wres" -o "$STAGE/wres.zip"
    WRES=("$STAGE/wres.zip")
  fi
  "$BT/aapt2" link -o "$STAGE/base.apk" -I "$JAR" --manifest "$SRC/AndroidManifest.xml" \
    -A "$STAGE/assets" "$STAGE/common.zip" ${WRES[@]+"${WRES[@]}"} "$STAGE/res.zip" --java "$STAGE/gen" \
    --auto-add-overlay -0 png -0 jpg
  javac -Xlint:all -Xlint:-options --release 17 -classpath "$JAR" -d "$STAGE/classes" \
    $(find "$HERE/common/src" "$SRC/src" "$STAGE/gen" -name '*.java')
  "$BT/d8" --release --min-api 26 --lib "$JAR" --output "$STAGE" $(find "$STAGE/classes" -name '*.class')
  python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    "$STAGE/base.apk" "$STAGE/classes.dex"
  "$BT/zipalign" -f -p 4 "$STAGE/base.apk" "$STAGE/aligned.apk"
  "$BT/apksigner" sign --ks "$KEYS/release.jks" --ks-key-alias forge \
    --ks-pass "file:$KEYS/password" --out "$OUT/forge-$app.apk" "$STAGE/aligned.apk"
  "$BT/apksigner" verify "$OUT/forge-$app.apk"
  rm -rf "${STAGE:?}"
  trap - EXIT
  ls -l "$OUT/forge-$app.apk"
done

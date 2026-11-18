#!/bin/bash
# Build the Forge phone APK with no Gradle: aapt2, javac, d8, zipalign,
# apksigner from ~/.local/share/forge/android (fetch.sh puts them there).
# The release key lives in ~/.config/forge/android (0600, never in a repo).
#   phone/android/build.sh                  -> phone/android/build/forge.apk
#   phone/android/build.sh --publish NOTES  -> the same, then put where Forge for
#       Android looks for updates (phone/phone_updates.py publish; task 1316)
# Forge Theme and Forge Visualiser are folded in (task 1316): their Java,
# resources and assets come from phone/theme (common, theme, visualiser), the
# same files phone/theme/build.sh builds the standalone apps from.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${FORGE_ANDROID:-$HOME/.local/share/forge/android}"
KEYS="$HOME/.config/forge/android"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
BT="$TOOLS/sdk/build-tools/34.0.0"
JAR="$TOOLS/sdk/platforms/android-34/android.jar"
OUT="$HERE/build"
THEME="$HERE/../theme"
PUBLISH=""
if [ "${1:-}" = --publish ]; then PUBLISH="${2:?--publish needs the notes: what changed, in plain words}"; fi
mkdir -p "$OUT"
STAGE="$(mktemp -d "$OUT/stage.XXXX")"
trap 'rm -rf "${STAGE:?}"' EXIT

# The phone-only apps, fresh from the registry, plus the start page's list.
"$HERE/../.venv/bin/python" "$HERE/../build_offline.py"
mkdir -p "$STAGE/assets"
cp -r "$HERE/assets/start" "$STAGE/assets/start"
# the visualiser's looks, the theme pictures and particle kinds (from Forge's
# registries), and the visualiser's own page (host/)
"$HERE/../.venv/bin/python" "$THEME/make_assets.py" "$STAGE/assets"
cp -r "$THEME/visualiser/assets/." "$STAGE/assets/"
# device strength rules and code (shared/perf/device.json, Relay task 596)
cp "$HERE/../../shared/forge_device.js" "$STAGE/assets/start/device.js"
cp "$HERE/../../shared/perf/device.json" "$STAGE/assets/start/device.json"
"$HERE/../.venv/bin/python" - "$HERE/../offline" "$STAGE/assets" <<'PY'
import json, shutil, sys
from pathlib import Path
offline, assets = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
forge = offline.parent.parent
sys.path.insert(0, str(forge / "shared"))
import forge_presets
registry = json.loads((offline.parent / "phone_apps.json").read_text())["apps"]
# The published APK is for a released Forge: only apps a paired phone may
# have ("paired": true) that the public listing does not hide (Relay task
# 666; the games stay on the owner's own hub).
release = json.loads((forge / "release" / "release.json").read_text())
sections = {}
for m in (forge / "apps").glob("*/forge_app.json"):
    data = json.loads(m.read_text())
    sections[data.get("name") or m.parent.name] = str(data.get("section") or "")
hidden = forge_presets.unlisted(release, sections)
apps = []
for meta in sorted(offline.glob("*/forge_phone.json")):
    name = meta.parent.name
    entry = registry.get(name, {})
    if not entry.get("offline") or entry.get("paired") is not True or name in hidden:
        continue
    shutil.copytree(meta.parent / "www", assets / "offline" / name / "www")
    apps.append(json.loads(meta.read_text()) | {"name": name})
(assets / "start" / "apps.json").write_text(json.dumps(apps))
print("bundled:", ", ".join(a["name"] for a in apps))
PY

# shared drawables (the dark panel, music keys), Forge Theme's and the
# visualiser's own resources, then this app's; each name is used once
for part in common/res theme/res visualiser/res; do
  "$BT/aapt2" compile --dir "$THEME/$part" -o "$STAGE/${part%%/*}.zip"
done
"$BT/aapt2" compile --dir "$HERE/res" -o "$STAGE/res.zip"
"$BT/aapt2" link -o "$STAGE/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" \
  -A "$STAGE/assets" "$STAGE/common.zip" "$STAGE/theme.zip" "$STAGE/visualiser.zip" "$STAGE/res.zip" \
  --java "$STAGE/gen" --extra-packages app.forge.theme:app.forge.visualiser \
  --auto-add-overlay -0 png -0 jpg
mkdir -p "$STAGE/classes"
javac --release 17 -classpath "$JAR" -d "$STAGE/classes" \
  $(find "$HERE/src" "$THEME/common/src" "$THEME/theme/src" "$THEME/visualiser/src" "$STAGE/gen" -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$STAGE" $(find "$STAGE/classes" -name '*.class')
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
  "$STAGE/base.apk" "$STAGE/classes.dex"
"$BT/zipalign" -f -p 4 "$STAGE/base.apk" "$STAGE/aligned.apk"

if [ ! -f "$KEYS/release.jks" ]; then
  mkdir -p "$KEYS" && chmod 700 "$KEYS"
  (umask 077; head -c 24 /dev/urandom | base64 > "$KEYS/password")
  keytool -genkeypair -keystore "$KEYS/release.jks" -alias forge -keyalg RSA -keysize 3072 \
    -validity 10000 -dname "CN=Forge phone" -storepass "$(cat "$KEYS/password")" -noprompt
  chmod 600 "$KEYS/release.jks"
  echo "New signing key in $KEYS: back it up (losing it means no updates over this install)."
fi
"$BT/apksigner" sign --ks "$KEYS/release.jks" --ks-key-alias forge \
  --ks-pass "file:$KEYS/password" --out "$OUT/forge.apk" "$STAGE/aligned.apk"
"$BT/apksigner" verify "$OUT/forge.apk"
ls -l "$OUT/forge.apk"
if [ -n "$PUBLISH" ]; then
  "$HERE/../.venv/bin/python" "$HERE/../phone_updates.py" publish "$OUT/forge.apk" --notes "$PUBLISH"
fi

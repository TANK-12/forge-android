#!/data/data/com.termux/files/usr/bin/bash
# Lend this Android phone to the laptop's local models (Relay task 541),
# over the same locked link the two-PC split uses (shared/forge_link.py,
# task 524): llama.cpp's RPC server on the phone's 127.0.0.1 behind a gate
# that only the laptop's address and the pairing key get through.
#
# In Termux (from F-Droid), with Tailscale on and signed in on the phone:
#   curl -fsSLO https://raw.githubusercontent.com/TANK-12/forge-android/main/termux/lend.sh
#   bash lend.sh LAPTOP-ADDRESS [MAX-MB]
# LAPTOP-ADDRESS is the laptop's Tailscale (100.x) or home Wi-Fi address.
# First run: installs Python, builds llama.cpp at the laptop's build number
# (both ends of a split need the same one; 10-30 minutes on a phone) and asks
# for the pairing key (`forge_link.py pair` on the laptop prints it).
# Then the laptop runs: forge_link.py split MODEL --peer PHONE-ADDRESS
set -euo pipefail
LAPTOP="${1:?usage: bash lend.sh LAPTOP-ADDRESS [MAX-MB]}"
MAX_MB="${2:-2048}"
SRC="${FORGE_LINK_SRC:-https://raw.githubusercontent.com/TANK-12/forge-android/main/termux}"
HERE="$HOME/forge-link"
mkdir -p "$HERE"

pkg install -y python git cmake clang ninja >/dev/null
python - <<'PY' || exit 1
import ssl, sys
if sys.version_info < (3, 13) or not ssl.HAS_PSK:
    sys.exit("This Python is older than 3.13 or has no TLS pre-shared keys;"
             " run pkg upgrade and try again.")
PY

for f in forge_link.py forge_tunnel.py forge_gpu.py llama_backends.json; do
  curl -fsSL "$SRC/$f" -o "$HERE/$f.new" || { echo "Could not fetch $f from $SRC" >&2; exit 1; }
  mv "$HERE/$f.new" "$HERE/$f"
done
# Check the address before the long build.
(cd "$HERE" && python -c 'import sys, forge_tunnel; forge_tunnel.peer_ip(sys.argv[1])' "$LAPTOP") 2>/dev/null || {
  echo "$LAPTOP is not a Tailscale (100.x) or home-network address; use the laptop's number, not its name." >&2; exit 1; }
BUILD="$(python -c 'import json,sys; print(json.load(open(sys.argv[1]))["default_build"])' "$HERE/llama_backends.json")"
LLAMA="$HOME/llama.cpp-$BUILD"
if [ ! -x "$LLAMA/build/bin/ggml-rpc-server" ] || [ ! -x "$LLAMA/build/bin/llama-server" ]; then
  [ -d "$LLAMA/.git" ] || git clone --depth 1 --branch "$BUILD" https://github.com/ggml-org/llama.cpp "$LLAMA"
  cmake -S "$LLAMA" -B "$LLAMA/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DGGML_RPC=ON -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF
  cmake --build "$LLAMA/build" --target ggml-rpc-server llama-server
fi
export PATH="$LLAMA/build/bin:$PATH"

cd "$HERE"
KEY="$(
  if [ -f "$HOME/.config/forge/link/link.key" ]; then python -c 'import forge_tunnel; print(forge_tunnel.load_key().hex())'
  else read -rsp "Pairing key from the laptop (forge_link.py pair): " k; echo >&2
    [[ "$k" =~ ^[0-9a-fA-F]{64}$ ]] || { echo "That is not a pairing key (64 letters and digits)." >&2; exit 1; }
    echo "$k"; fi
)"
python forge_link.py pair --key "${KEY:?no pairing key}" >/dev/null
python forge_link.py peer add laptop "$LAPTOP"

# The phone's own address on the same network as the laptop's.
ME="$(python - "$LAPTOP" <<'PY'
import socket, sys
s = socket.socket(socket.AF_INET6 if ":" in sys.argv[1] else socket.AF_INET, socket.SOCK_DGRAM)
s.connect((sys.argv[1], 9))
print(s.getsockname()[0])
PY
)"
echo "This phone is $ME. On the laptop: forge_link.py peer add phone $ME"
echo "then: forge_link.py split MODEL --peer $ME"
command -v termux-wake-lock >/dev/null && termux-wake-lock
trap 'command -v termux-wake-unlock >/dev/null && termux-wake-unlock' EXIT
python forge_link.py rpc --backend cpu --host "$ME" --max-mb "$MAX_MB"

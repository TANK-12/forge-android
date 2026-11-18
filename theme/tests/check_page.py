"""Headless Chrome check of Forge Visualiser's page (Relay task 1293) without
Android: stages the APK's assets (make_assets.py plus visualiser/assets),
serves them on 127.0.0.1, swaps the Android side for tests/fake_android.js
and loads a Light look and a Showpiece (WebGL) look in the player and the
wallpaper mode. Each must draw on a canvas with no page errors; the player
must list every preset. A screen without WebGL 2 (nogl) must keep a Light look
showing, dim every Showpiece tile and say why. The canvas must draw at the
look's own share of the screen (looks.json scale) times the user's sharpness
(1 in the player, 0.75 on the wallpaper), as the desktop app does (task 1308);
the four looks of task 1297 (Tunnel, Skyline, Warp, Northern lights) run too.
The full-screen frame-rate chips come from the shared RATES list (30, 60, 90,
120) and a rate the Android side allows above the screen's shows in the
player's rate note (task 1320). Screenshots go to OUT (default
/tmp/1293-page-shots).

    python3 phone/theme/tests/check_page.py [OUT]
"""

from __future__ import annotations

import http.server
import json
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

THEME = Path(__file__).resolve().parent.parent
SHOT = THEME.parent.parent / "shared" / "forge_shot.py"
# mode, preset, the look name the player should show (None: any), shot, query,
# the canvas pixels per screen pixel (None: not judged)
CASES = [
    ("player", "ice-ridges", "Ice ridges", "player-light", "", 1.0),
    ("player", "orb", "Orb", "player-showpiece", "", 1.0),
    ("wallpaper", "halo", None, "wallpaper-light", "", 0.75),
    ("player", "orb", None, "player-nogl", "&nogl=1&sheet=looks", None),
    ("player", "ice-tunnel", "Ice tunnel", "player-tunnel", "", 1.0),
    ("player", "night-city", "Night city", "player-skyline", "", 1.0),
    ("player", "warp", "Warp", "player-warp", "", 1.0),
    ("player", "aurora", "Northern lights", "player-aurora", "", 0.75),
    ("wallpaper", "aurora", None, "wallpaper-aurora", "", 0.5625),
    ("player", "chrome", "Chrome", "player-chrome", "", 0.7),
    ("player", "halo", None, "player-120", "&fps=120&sheet=set", None),
]


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def stage(root: Path) -> tuple[int, int]:
    sys.path.insert(0, str(THEME))
    import make_assets  # noqa: E402

    counts = make_assets.build(root)
    shutil.copytree(THEME / "visualiser" / "assets", root, dirs_exist_ok=True)
    host = root / "host"
    shutil.copyfile(THEME / "tests" / "fake_android.js", host / "fake.js")
    page = (host / "index.html").read_text(encoding="utf-8")
    core = '<script src="/visualiser/viz_core.js"></script>'
    assert core in page
    (host / "test.html").write_text(
        page.replace(core, '<script src="fake.js"></script>\n' + core), "utf-8"
    )
    presets = json.loads((root / "visualiser" / "presets.json").read_text())
    looks = json.loads((root / "visualiser" / "looks.json").read_text())
    gl = {lk["id"] for lk in looks["looks"] if lk.get("engine") == "webgl"}
    assert counts["looks"]
    rows = presets["presets"]
    return len(rows), sum(r["look"] in gl for r in rows)


def passes(
    mode: str,
    name: str | None,
    query: str,
    got: dict,
    want: tuple[int, int],
    px: float | None = None,
) -> bool:
    """What a case must show; got is fake_android.js's __check(). Frames
    pulled (calls) are not judged: Chrome's DOM dump runs only 0-3 animation
    frames in its 2.5 s, so the screenshots are the proof that looks draw."""
    tiles, webgl = want
    fps = dict(x.split("=", 1) for x in query.split("&") if "=" in x).get("fps")
    if not got or not got["canvas"]:
        return False
    if "nogl" in query:
        # the look asked for cannot run: a note, warnings only, Light look kept
        return (
            all(e.startswith("warn ") for e in got["errs"])
            and got["off"] == webgl
            and got["tiles"] == tiles
            and "WebGL 2" in got["note"]
            and got["name"] not in ("", "Orb")
        )
    return (
        not got["errs"]
        and got["off"] == 0
        and got["tiles"] == (tiles if mode == "player" else 0)
        and (name is None or got["name"] == name)
        # the canvas rounds to whole pixels
        and (px is None or abs(got.get("px", 0) - px) < 0.01)
        # the frame-rate chips come from the shared list (task 1320)
        and (mode != "player" or got.get("rates") == "30,60,90,120")
        # ?fps=N: the rate the Android side allows shows in the note
        and (not fps or fps in got.get("rateNote", ""))
    )


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/1293-page-shots")
    out.mkdir(parents=True, exist_ok=True)
    failed = 0
    with tempfile.TemporaryDirectory(prefix="1293-page-") as tmp:
        root = Path(tmp)
        want = stage(root)
        server = http.server.ThreadingHTTPServer(
            ("127.0.0.1", 0),
            lambda *a: Quiet(*a, directory=str(root)),  # type: ignore[arg-type]
        )
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}/host/test.html"
        try:
            for mode, preset, name, shot, query, px in CASES:
                url = f"{base}?mode={mode}&preset={preset}{query}"
                dom = subprocess.run(
                    [sys.executable, str(SHOT), url, "--dom", "--size", "1280,800"],
                    capture_output=True,
                    text=True,
                    timeout=90,
                ).stdout
                found = re.search(r'<pre id="check"[^>]*>([^<]*)</pre>', dom)
                got = json.loads(found.group(1)) if found else {}
                ok = passes(mode, name, query, got, want, px)
                failed += not ok
                subprocess.run(
                    [sys.executable, str(SHOT), url, str(out / f"{shot}.png")]
                    + ["--size", "960,600"],
                    capture_output=True,
                    timeout=90,
                )
                print("ok  " if ok else "FAIL", mode, preset, got)
        finally:
            server.shutdown()
    print(f"{len(CASES) - failed}/{len(CASES)} passed; shots in {out}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

"""Build the assets the two Android apps carry, from Forge's own registries.

    phone/.venv/bin/python phone/theme/make_assets.py OUT_DIR

Writes into OUT_DIR:
  themes.json      every Forge theme (shared/themes/*.json): its colours, its
                   picture (walls/<file>) and the particle kind that suits it
  walls/           the theme pictures those entries name (shared/themes/walls)
  particles.json   every particle kind with its label and count, read from
                   shared/forge_theme.py (labels) and shared/forge_particles.js
                   (the KINDS table), so a kind added on the PC shows here
  visualiser/      the desktop visualiser's looks and presets (shared/visualiser,
                   task 1294: viz_core.js, looks.json, presets.json,
                   palettes.json, looks/, presets/), run in a WebView by Forge
                   Visualiser; skipped while absent
  <shared file>    what a look lists under "needs" (classic needs
                   ../forge_visual.js), at the same place relative to
                   visualiser/ so the look's own relative link works

Both apps get the same files; build.sh calls this once per build. Stdlib only.
"""

from __future__ import annotations

import ast
import json
import re
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FORGE = HERE.parent.parent
SHARED = FORGE / "shared"
HEX = re.compile(r"^#[0-9a-fA-F]{6}$")


def _dict_literal(source: str, name: str) -> dict:
    """A module-level `NAME = {...}` (or `NAME: T = {...}`) as a value."""
    for node in ast.parse(source).body:
        target = value = None
        if isinstance(node, ast.Assign) and len(node.targets) == 1:
            target, value = node.targets[0], node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            target, value = node.target, node.value
        if isinstance(target, ast.Name) and target.id == name and value is not None:
            return ast.literal_eval(value)
    raise KeyError(name)


def particle_kinds() -> list[dict]:
    """[{key, label, count}] in the order of the JS KINDS table."""
    js = (SHARED / "forge_particles.js").read_text(encoding="utf-8")
    block = js[js.index("KINDS: {") :]
    block = block[: block.index("\n  },")]
    counts = re.findall(r"^\s{4}(\w+): \{ count: (\d+)", block, re.M)
    labels = _dict_literal(
        (SHARED / "forge_theme.py").read_text(encoding="utf-8"), "PARTICLE_LABELS"
    )
    return [{"key": k, "label": labels.get(k, k), "count": int(n)} for k, n in counts]


def _mix(a: str, b: str, t: float) -> str:
    x, y = int(a[1:], 16), int(b[1:], 16)
    out = 0
    for sh in (16, 8, 0):
        ca, cb = (x >> sh) & 255, (y >> sh) & 255
        out |= round(ca + (cb - ca) * t) << sh
    return f"#{out:06x}"


def _suits(words: str, ambience: dict, kinds: set[str]) -> str:
    """The particle kind a theme's name, live scene and picture suggest
    (the same word list Forge uses for a wallpaper), else soft drift."""
    lower = words.lower()
    hits = {k: sum(w in lower for w in ws) for k, ws in ambience.items()}
    best = max(hits, key=lambda k: hits[k]) if hits else ""
    if best and hits[best] and best in kinds:
        return best
    return "soft"


def themes(walls_out: Path) -> list[dict]:
    ambience = _dict_literal(
        (SHARED / "forge_theme.py").read_text(encoding="utf-8"), "AMBIENCE_WORDS"
    )
    kinds = {k["key"] for k in particle_kinds()}
    walls_in = SHARED / "themes" / "walls"
    out = []
    for path in sorted((SHARED / "themes").glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        colours = data.get("colours") or {}
        accent, bg = colours.get("accent", ""), colours.get("bg", "")
        if not (HEX.match(accent) and HEX.match(bg)):
            continue  # a look-only theme with no colours of its own
        text = colours.get("text", "#ffffff")
        text = text if HEX.match(text) else "#ffffff"
        wall = ""
        walls = data.get("wallpaper") or []
        for item in walls if isinstance(walls, list) else [walls]:
            name = str(item)
            if name.startswith("forge:") and (walls_in / name[6:]).is_file():
                wall = name[6:]
                break
        if wall:
            walls_out.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(walls_in / wall, walls_out / wall)
        own = (data.get("settings") or {}).get("particles", "")
        # "forge" in a theme id names the app, not fire: leave it out
        words = " ".join(
            [path.stem.replace("forge", ""), str(data.get("live") or ""), wall]
        )
        out.append(
            {
                "id": path.stem,
                "label": str(data.get("label") or path.stem),
                "order": int(data.get("order") or 999),
                "accent": accent,
                "accent2": _mix(accent, text, 0.45),
                "bg": bg,
                "panel": colours.get("panel", bg),
                "text": text,
                "muted": colours.get("muted", text),
                "wall": f"walls/{wall}" if wall else "",
                "particles": own if own in kinds else _suits(words, ambience, kinds),
            }
        )
    out.sort(key=lambda t: (t["order"], t["label"]))
    return out


def build(out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    listed = themes(out / "walls")
    (out / "themes.json").write_text(json.dumps(listed), encoding="utf-8")
    kinds = particle_kinds()
    (out / "particles.json").write_text(json.dumps(kinds), encoding="utf-8")
    return {"themes": len(listed), "kinds": len(kinds), "looks": visualiser(out)}


def visualiser(out: Path) -> int:
    """Copy the shared visualiser (plain JS looks and JSON presets) for the
    WebView; the number of looks copied, 0 when it is not there yet."""
    src = SHARED / "visualiser"
    if not (src / "viz_core.js").is_file() or not (src / "looks.json").is_file():
        return 0
    dest = out / "visualiser"
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    for path in sorted(src.glob("*.js")) + sorted(src.glob("*.json")):
        shutil.copyfile(path, dest / path.name)
    for sub in ("looks", "presets"):
        if (src / sub).is_dir():
            shutil.copytree(
                src / sub,
                dest / sub,
                ignore=shutil.ignore_patterns("*.md", "__pycache__"),
            )
    looks = json.loads((src / "looks.json").read_text(encoding="utf-8"))
    rows = looks if isinstance(looks, list) else looks.get("looks", [])
    for row in rows:
        for need in row.get("needs", []):
            found = (src / need).resolve()
            if not found.is_relative_to(SHARED) or not found.is_file():
                raise ValueError(f"look {row.get('id')} needs {need}, not in shared/")
            target = out / found.relative_to(SHARED)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(found, target)
    return len(rows)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    print("assets:", build(Path(sys.argv[1])))

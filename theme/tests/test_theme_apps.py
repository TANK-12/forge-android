"""Checks for Forge Theme and Forge Visualiser (phone/theme, Relay task 1293)
that run without Android: the assets made from Forge's registries, and that
the manifests, widgets and Java agree with each other.

    phone/.venv/bin/python -m pytest -q phone/theme/tests
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
from pathlib import Path

import pytest

THEME = Path(__file__).resolve().parent.parent
FORGE = THEME.parent.parent
HEX = re.compile(r"^#[0-9a-f]{6}$", re.I)


def _make_assets():
    spec = importlib.util.spec_from_file_location(
        "make_assets", THEME / "make_assets.py"
    )
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules["make_assets"] = mod
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def assets(tmp_path_factory):
    out = tmp_path_factory.mktemp("assets")
    counts = _make_assets().build(out)
    return out, counts


def test_every_theme_has_colours_and_a_known_kind(assets):
    out, counts = assets
    themes = json.loads((out / "themes.json").read_text())
    kinds = {k["key"] for k in json.loads((out / "particles.json").read_text())}
    assert counts["themes"] == len(themes) >= 20
    for t in themes:
        for key in ("accent", "accent2", "bg", "panel", "text", "muted"):
            assert HEX.match(t[key]), (t["id"], key, t[key])
        assert t["particles"] in kinds, t
        if t["wall"]:
            assert (out / t["wall"]).is_file(), t["wall"]


def test_default_theme_exists(assets):
    out, _ = assets
    ids = {t["id"] for t in json.loads((out / "themes.json").read_text())}
    prefs = (THEME / "theme/src/app/forge/theme/Prefs.java").read_text()
    default = re.search(r'getString\(THEME, "([^"]+)"\)', prefs)
    assert default and default.group(1) in ids


def test_forge_named_theme_is_not_fire(assets):
    out, _ = assets
    themes = {t["id"]: t for t in json.loads((out / "themes.json").read_text())}
    if "forge-light" in themes:
        assert themes["forge-light"]["particles"] != "emberstorm"


def test_particle_kinds_match_the_desktop(assets):
    out, _ = assets
    kinds = [k["key"] for k in json.loads((out / "particles.json").read_text())]
    js = (FORGE / "shared/forge_particles.js").read_text()
    block = js[js.index("KINDS: {") :]
    block = block[: block.index("\n  },")]
    assert kinds == re.findall(r"^\s{4}(\w+): \{", block, re.M)
    labels = [k["label"] for k in json.loads((out / "particles.json").read_text())]
    assert all(lbl and lbl != key for lbl, key in zip(labels, kinds, strict=True))
    java = THEME / "common/src/app/forge/look/Particles.java"
    if java.is_file():
        for k in kinds:
            assert f'"{k}"' in java.read_text(), f"Particles.java has no {k}"


def _manifest_classes(app: str) -> tuple[str, list[str]]:
    text = (THEME / app / "AndroidManifest.xml").read_text()
    found = re.search(r'package="([^"]+)"', text)
    assert found
    package = found.group(1)
    names = re.findall(r'android:name="(\.[\w$]+)"', text)
    return package, names


@pytest.mark.parametrize("app", ["theme", "visualiser"])
def test_manifest_classes_exist(app):
    if not (THEME / app / "AndroidManifest.xml").is_file():
        pytest.skip(f"{app} has no manifest yet")
    package, names = _manifest_classes(app)
    src = THEME / app / "src" / package.replace(".", "/")
    for name in names:
        outer = name[1:].split("$")[0]
        java = src / f"{outer}.java"
        assert java.is_file(), name
        if "$" in name:
            inner = name.split("$")[1]
            assert re.search(rf"class {inner}\b", java.read_text()), name


def test_widget_layouts_use_only_remote_views():
    # home screens inflate widget layouts with a short list of allowed views
    allowed = {
        "FrameLayout",
        "LinearLayout",
        "RelativeLayout",
        "GridLayout",
        "ImageView",
        "ImageButton",
        "TextView",
        "TextClock",
        "ViewFlipper",
        "ProgressBar",
        "Button",
    }
    for xml in THEME.glob("*/res/layout/widget_*.xml"):
        tags = set(re.findall(r"<([A-Z]\w+)", xml.read_text()))
        assert tags <= allowed, (xml.name, tags - allowed)


def _widget_res():
    spec = importlib.util.spec_from_file_location(
        "make_widget_res", THEME / "make_widget_res.py"
    )
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    sys.modules["make_widget_res"] = mod
    spec.loader.exec_module(mod)
    return mod


def test_widget_pictures_cover_every_kind_and_shape(assets, tmp_path):
    """Task 1322: the particles widget is a picture plus animated vector layers
    the home screen runs itself (no flip-book frames from the app)."""
    out, _ = assets
    gen = _widget_res()
    kinds = [k["key"] for k in json.loads((out / "particles.json").read_text())]
    made = gen.build(out / "particles.json", tmp_path)
    assert made == len(kinds) * len(gen.SHAPES) * 2
    allowed = {"FrameLayout", "ImageView", "ProgressBar"}
    for kind in kinds:
        for shape in gen.SHAPES:
            for layer in "ab":
                xml = tmp_path / "drawable" / f"wp_{kind}_{shape}_{layer}.xml"
                assert "<animated-vector" in xml.read_text(), xml.name
            lay = (tmp_path / "layout" / f"widget_p_{kind}_{shape}.xml").read_text()
            assert set(re.findall(r"<([A-Z]\w+)", lay)) <= allowed, (kind, shape)
            for view in ("root", "wp_bg", "wp_a", "wp_b"):
                assert f'android:id="@id/{view}"' in lay, (kind, shape, view)
    # Widgets.java falls back to the soft kind; the provider's first layout is one
    assert "soft" in kinds
    info = (THEME / "theme/res/xml/widget_particles.xml").read_text()
    assert "@layout/widget_p_soft_w" in info
    ids = (THEME / "theme/res/values/ids.xml").read_text()
    for view in ("root", "wp_bg", "wp_a", "wp_b"):
        assert f'name="{view}"' in ids


def test_visualiser_bars_are_generated():
    gen = _widget_res()
    bars = THEME / "visualiser/res/drawable/viz_bars.xml"
    assert bars.read_text(encoding="utf-8") == gen.bars()


def test_old_flip_book_is_gone():
    assert not (THEME / "theme/src/app/forge/theme/FramesProvider.java").exists()
    for manifest in (
        THEME / "theme/AndroidManifest.xml",
        FORGE / "phone/android/AndroidManifest.xml",
    ):
        assert "FramesProvider" not in manifest.read_text()


def test_sound_stop_forgets_its_mode():
    """The critic's bug (task 1293): stop() kept the mode, so listen() saw
    mode == want after the wallpaper was hidden once and never started again."""
    java = (THEME / "common/src/app/forge/look/Sound.java").read_text()
    stop = java[java.index("public synchronized void stop()") :]
    stop = stop[: stop.index("\n  }\n")]
    assert "mode = Mode.OFF;" in stop


def test_no_shouting_in_strings():
    for xml in THEME.glob("*/res/values/strings.xml"):
        for text in re.findall(r">([^<]+)<", xml.read_text()):
            words = re.findall(r"\b[A-Z]{4,}\b", text)
            assert not words, (xml, words)


# ---- Forge Visualiser ----------------------------------------------------------

VIZ = THEME / "visualiser"
HAS_SHARED_VIZ = (FORGE / "shared/visualiser/viz_core.js").is_file()


@pytest.mark.skipif(not HAS_SHARED_VIZ, reason="shared/visualiser not there yet")
def test_visualiser_assets_carry_registries_and_needs(assets):
    out, counts = assets
    viz = out / "visualiser"
    for name in ("viz_core.js", "looks.json", "presets.json"):
        assert (viz / name).is_file(), name
    looks = json.loads((viz / "looks.json").read_text())["looks"]
    assert counts["looks"] == len(looks) > 0
    for row in looks:
        assert (viz / row["file"]).is_file(), row["id"]
        for need in row.get("needs", []):
            # where the look's own relative link points from visualiser/
            assert (viz / need).resolve().is_file(), (row["id"], need)
    presets = json.loads((viz / "presets.json").read_text())["presets"]
    ids = {row["id"] for row in looks}
    for p in presets:
        assert p["look"] in ids, p["id"]
        assert p.get("weight") in ("light", "heavy"), p["id"]


def test_visualiser_page_files_exist():
    host = VIZ / "assets" / "host"
    page = (host / "index.html").read_text()
    for src in re.findall(r'(?:src|href)="([^"]+)"', page):
        if src.startswith("/visualiser/"):
            assert HAS_SHARED_VIZ
            continue
        assert (host / src).is_file(), src
    assert "fake" not in page  # the test bridge stays out of the APK


def test_frame_packing_matches_both_sides():
    java = (VIZ / "src/app/forge/visualiser/Bridge.java").read_text()
    js = (VIZ / "assets/host/host.js").read_text()
    assert "SIZE = Sound.BANDS + Sound.WAVE + 6" in java
    assert "SIZE = NB + WAVE + 6" in js
    sound = (THEME / "common/src/app/forge/look/Sound.java").read_text()
    assert "BANDS = 64" in sound and "WAVE = 512" in sound


def test_every_setting_the_page_saves_is_allowed():
    java = (VIZ / "src/app/forge/visualiser/Prefs.java").read_text()
    consts = dict(re.findall(r'(\w+) = "(\w+)"', java))
    listed = re.search(r"Arrays\.asList\(([^)]*)\)", java, re.S)
    assert listed
    allowed = {consts[n.strip()] for n in listed.group(1).split(",")}
    page = (VIZ / "assets/host/index.html").read_text()
    js = (VIZ / "assets/host/host.js").read_text()
    wanted = set(re.findall(r'data-pref="(\w+)"', page))
    wanted |= set(re.findall(r'Key = wall \? "(\w+)" : "(\w+)"', js)[0])
    wanted |= set(re.findall(r'save\("(\w+)"', js))
    assert wanted - {"source"} <= allowed, wanted - allowed
    assert "source" in allowed


def test_page_only_served_from_the_apk():
    java = (VIZ / "src/app/forge/visualiser/Host.java").read_text()
    assert 'path.contains("..")' in java
    assert "setAllowFileAccess(false)" in java
    assert "shouldOverrideUrlLoading" in java


def test_pause_control_has_a_distinct_key_without_changing_toggle() -> None:
    """Each visible Pause control binds to its own key; toggles stay supported."""
    for folder, source in (
        ("theme", "theme/Widgets.java"),
        ("visualiser", "visualiser/VizWidget.java"),
    ):
        java = (THEME / folder / "src/app/forge" / source).read_text()
        layout = (
            THEME
            / folder
            / "res/layout"
            / f"widget_{'music' if folder == 'theme' else folder}.xml"
        ).read_text()
        assert "{R.id.pause, KeyEvent.KEYCODE_MEDIA_PAUSE}" in java
        assert "{R.id.play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}" in java
        assert 'android:id="@+id/pause"' in layout
        assert 'android:contentDescription="Pause"' in layout
    bridge = (VIZ / "src/app/forge/visualiser/Bridge.java").read_text()
    assert '"pause".equals(which) ? KeyEvent.KEYCODE_MEDIA_PAUSE' in bridge
    assert (
        'data-key="pause" aria-label="Pause"'
        in (VIZ / "assets/host/index.html").read_text()
    )
    assert (
        "isMusicActive"
        not in (THEME / "common/src/app/forge/look/MediaKeys.java").read_text()
    )

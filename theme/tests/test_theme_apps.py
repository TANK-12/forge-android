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


def test_sensitivity_setting_is_exposed_and_soft_capped() -> None:
    """Task 1532: Android visualiser has an adjustable sensitivity setting with soft headroom."""
    prefs = (VIZ / "src/app/forge/visualiser/Prefs.java").read_text()
    page = (VIZ / "assets/host/index.html").read_text()
    js = (VIZ / "assets/host/host.js").read_text()
    sound = (THEME / "common/src/app/forge/look/Sound.java").read_text()
    assert 'SENSITIVITY = "sensitivity"' in prefs
    assert 'data-pref="sensitivity"' in page
    assert 'id="sensSlider"' in page
    assert "phoneSound.sensitivity" in js
    assert "softCap" in js and "softCap" in sound


def test_sound_java_loud_music_stays_unpegged(tmp_path: Path) -> None:
    """Compile and run Sound.java on loud synthetic music and silence (task 1532)."""
    import subprocess

    jdk = Path.home() / ".local/share/forge/android/jdk/bin"
    if not (jdk / "javac").is_file():
        pytest.skip("Android JDK not installed")
    stubs = {
        "android/os/SystemClock.java": (
            "package android.os;\n"
            "public final class SystemClock {\n"
            "  public static long fakeNs = 1_000_000_000L;\n"
            "  public static long elapsedRealtimeNanos() { return fakeNs; }\n"
            "  public static void sleep(long ms) {}\n"
            "}\n"
        ),
        "android/media/AudioFormat.java": (
            "package android.media;\n"
            "public final class AudioFormat {\n"
            "  public static final int CHANNEL_IN_MONO = 16, ENCODING_PCM_16BIT = 2;\n"
            "}\n"
        ),
        "android/media/MediaRecorder.java": (
            "package android.media;\n"
            "public final class MediaRecorder {\n"
            "  public static final class AudioSource { public static final int MIC = 1; }\n"
            "}\n"
        ),
        "android/media/AudioRecord.java": (
            "package android.media;\n"
            "public final class AudioRecord {\n"
            "  public static final int STATE_INITIALIZED = 1;\n"
            "  public AudioRecord(int a, int b, int c, int d, int e) {}\n"
            "  public static int getMinBufferSize(int a, int b, int c) { return 4096; }\n"
            "  public int getState() { return STATE_INITIALIZED; }\n"
            "  public void startRecording() {}\n"
            "  public void stop() {}\n"
            "  public void release() {}\n"
            "  public int read(short[] b, int o, int l) { return 0; }\n"
            "}\n"
        ),
        "android/media/audiofx/Visualizer.java": (
            "package android.media.audiofx;\n"
            "public final class Visualizer {\n"
            "  public static final int SUCCESS = 0, SCALING_MODE_NORMALIZED = 0;\n"
            "  public static byte[] nextFft = new byte[1024], nextWave = new byte[1024];\n"
            "  public Visualizer(int s) {}\n"
            "  public static int[] getCaptureSizeRange() { return new int[] {128, 1024}; }\n"
            "  public int setEnabled(boolean e) { return SUCCESS; }\n"
            "  public int setCaptureSize(int n) { return SUCCESS; }\n"
            "  public int getCaptureSize() { return 1024; }\n"
            "  public int setScalingMode(int m) { return SUCCESS; }\n"
            "  public int getSamplingRate() { return 44100000; }\n"
            "  public int getFft(byte[] out) { System.arraycopy(nextFft, 0, out, 0, out.length); return SUCCESS; }\n"
            "  public int getWaveForm(byte[] out) { System.arraycopy(nextWave, 0, out, 0, out.length); return SUCCESS; }\n"
            "  public void release() {}\n"
            "}\n"
        ),
        "SoundProbe.java": (
            "import android.media.audiofx.Visualizer;\n"
            "import android.os.SystemClock;\n"
            "import app.forge.look.Sound;\n"
            "public final class SoundProbe {\n"
            "  public static void main(String[] args) {\n"
            "    Sound s = new Sound();\n"
            "    s.start(Sound.Mode.PHONE, true);\n"
            "    int pegged = 0, total = 0, beats = 0;\n"
            "    double lvlSum = 0, bassSum = 0;\n"
            "    float prevBeat = 0;\n"
            "    for (int f = 0; f < 360; f++) {\n"
            "      double t = f / 60.0;\n"
            "      double kick = Math.exp(-((t % 0.5) / 0.08));\n"
            "      java.util.Arrays.fill(Visualizer.nextFft, (byte) 0);\n"
            "      // Strong broadband loud music + 120 BPM bass kick + DC offset\n"
            "      Visualizer.nextFft[0] = 60;\n"
            "      for (int k = 1; k < 512; k++) {\n"
            "        int amp = k < 6 ? (int) Math.min(120, 28 + 85 * kick) : (k < 60 ? 22 : 8);\n"
            "        Visualizer.nextFft[2 * k] = (byte) amp;\n"
            "      }\n"
            "      for (int i = 0; i < 1024; i++) {\n"
            "        Visualizer.nextWave[i] = (byte) (128 + (int) (120 * Math.sin(i * 0.1 + f)));\n"
            "      }\n"
            "      SystemClock.fakeNs += 16_666_667L;\n"
            "      s.frame();\n"
            "      for (int b = 0; b < Sound.BANDS; b++) {\n"
            "        if (s.bands[b] >= 0.95f) pegged++;\n"
            "        total++;\n"
            "      }\n"
            "      lvlSum += s.level; bassSum += s.bass;\n"
            "      if (s.beat > 0.9f && prevBeat <= 0.9f) beats++;\n"
            "      prevBeat = s.beat;\n"
            "    }\n"
            "    double lvlMean = lvlSum / 360.0, bassMean = bassSum / 360.0;\n"
            "    // Then 2 seconds of silence\n"
            "    java.util.Arrays.fill(Visualizer.nextFft, (byte) 0);\n"
            "    java.util.Arrays.fill(Visualizer.nextWave, (byte) 128);\n"
            "    for (int f = 0; f < 120; f++) {\n"
            "      SystemClock.fakeNs += 16_666_667L;\n"
            "      s.frame();\n"
            "    }\n"
            "    s.stop();\n"
            '    System.out.printf(java.util.Locale.US, "%d %d %.4f %.4f %d %b %.4f%n",\n'
            "        pegged, total, lvlMean, bassMean, beats, s.silent, s.level);\n"
            "  }\n"
            "}\n"
        ),
    }
    src = tmp_path / "src"
    cls = tmp_path / "cls"
    cls.mkdir()
    files = []
    for rel, code in stubs.items():
        p = src / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(code, encoding="utf-8")
        files.append(str(p))
    files.append(str(THEME / "common/src/app/forge/look/Sound.java"))
    subprocess.run(
        [str(jdk / "javac"), "--release", "17", "-Xlint:all", "-d", str(cls), *files],
        check=True,
    )
    out = subprocess.run(
        [str(jdk / "java"), "-cp", str(cls), "SoundProbe"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    pegged, total, lvl_mean, bass_mean, beats, silent, end_lvl = out.split()
    assert int(pegged) == 0, f"pegged bands: {pegged}/{total}"
    assert 0.10 < float(lvl_mean) < 0.60, f"level_mean={lvl_mean}"
    assert 0.15 < float(bass_mean) < 0.65, f"bass_mean={bass_mean}"
    assert 6 <= int(beats) <= 15, f"beats={beats} in 6 s"
    assert silent == "true" and float(end_lvl) < 0.01, (silent, end_lvl)

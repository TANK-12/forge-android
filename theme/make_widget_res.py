"""Make the particles widget's moving pictures, one per particle kind.

    phone/.venv/bin/python phone/theme/make_widget_res.py PARTICLES_JSON OUT_RES_DIR
    phone/.venv/bin/python phone/theme/make_widget_res.py --bars   (the visualiser widget's bars)

A home-screen widget cannot run code, but it can hold a ProgressBar, and a
ProgressBar plays an animated vector drawable by itself, smoothly, in the home
screen's own drawing thread. So every particle kind becomes two animated
vector drawables (two layers, tinted with the theme's two accent colours on
Android 12 and newer) in four shapes (tall to wide, picked by the widget's
size) and a layout that stacks them over the theme picture:

  drawable/wp_<kind>_<shape>_<layer>.xml   shape t, s, m or w (SHAPES: tall
                                           to wide), layer a or b
  layout/widget_p_<kind>_<shape>.xml       picture + the two layers

Before task 1322 the widget was twelve PNG frames flipped four times a
second, drawn again on every settings change. The kinds come from
particles.json (make_assets.py), so a kind added on the PC gets a widget
picture too: one not in KINDS below moves like "soft". Stdlib only;
deterministic (the same input gives the same files).
"""

from __future__ import annotations

import json
import math
import random
import sys
import zlib
from pathlib import Path

# by the widget's width over height: tall, square, mid and wide (Widgets.java
# picks the nearest, so a particle is stretched by at most about a fifth)
SHAPES = {"t": (210, 280), "s": (260, 260), "m": (330, 220), "w": (400, 200)}

# most particles animated per layer (battery: critic, task 1322)
CAP = {"a": 12, "b": 9}

# kind: (motion, particle shape, count per layer (a, b), size range,
#        seconds to cross (or twinkle period), sideways sway range)
KINDS: dict[
    str,
    tuple[
        str,
        str,
        tuple[int, int],
        tuple[float, float],
        tuple[float, float],
        tuple[float, float],
    ],
] = {
    "soft": ("drift", "glow", (9, 7), (2.4, 5.0), (22, 34), (8, 18)),
    "lively": ("drift", "glow", (13, 10), (1.8, 3.8), (9, 15), (12, 24)),
    "embers": ("rise", "glow", (12, 9), (1.4, 3.2), (7, 12), (8, 18)),
    "constellation": ("twinkle", "glow", (16, 12), (1.4, 2.8), (1.6, 4.0), (0, 0)),
    "petals": ("fall", "petal", (9, 7), (4.0, 7.0), (12, 20), (20, 40)),
    "rain": ("fall", "streak", (20, 16), (10, 20), (0.9, 1.6), (0, 0)),
    "snow": ("fall", "glow", (16, 12), (1.6, 3.8), (10, 18), (8, 20)),
    "rays": ("rays", "ray", (4, 3), (14, 34), (5, 9), (0, 0)),
    "fireflies": ("drift", "blink", (9, 7), (2.2, 3.8), (18, 30), (14, 30)),
    "dust": ("drift", "glow", (18, 14), (0.9, 1.8), (25, 40), (6, 14)),
    "leaves": ("fall", "leaf", (8, 6), (5.0, 8.0), (10, 16), (25, 45)),
    "petalstorm": ("fall", "petal", (15, 12), (3.5, 6.0), (6, 10), (30, 50)),
    "bubbles": ("rise", "ring", (9, 7), (3.0, 7.0), (10, 16), (6, 14)),
    "stars": ("twinkle", "glow", (22, 16), (1.0, 2.4), (1.0, 4.0), (0, 0)),
    "emberstorm": ("rise", "glow", (18, 14), (1.2, 2.6), (4, 7), (10, 22)),
    "glitter": ("twinkle", "sparkle", (16, 12), (2.2, 4.6), (0.6, 1.6), (0, 0)),
    "lanterns": ("rise", "lantern", (5, 4), (6.0, 9.0), (18, 28), (6, 12)),
    "wisps": ("drift", "glow", (6, 5), (7.0, 12.0), (14, 22), (20, 40)),
    "galaxy": ("galaxy", "glow", (40, 30), (0.9, 2.2), (90, 120), (0, 0)),
}

ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
LINEAR = "@android:interpolator/linear"
EASE = "@android:interpolator/accelerate_decelerate"


def num(x: float) -> str:
    return f"{x:.2f}".rstrip("0").rstrip(".") if abs(x) >= 0.005 else "0"


def circle(r: float) -> str:
    return f"M{num(-r)},0a{num(r)},{num(r)} 0 1,0 {num(2 * r)},0a{num(r)},{num(r)} 0 1,0 {num(-2 * r)},0"


def glow_fill(r: float) -> str:
    return (
        '<aapt:attr name="android:fillColor"><gradient android:type="radial"'
        f' android:centerX="0" android:centerY="0" android:gradientRadius="{num(r)}">'
        '<item android:offset="0" android:color="#ffffffff"/>'
        '<item android:offset="0.3" android:color="#b3ffffff"/>'
        '<item android:offset="1" android:color="#00ffffff"/>'
        "</gradient></aapt:attr>"
    )


def particle(shape: str, size: float, name: str, alpha: float) -> str:
    """One particle drawn round (0, 0): a path named name."""
    a = f' android:name="{name}" android:fillAlpha="{num(alpha)}"'
    if shape in ("glow", "blink"):
        r = size * 2.2
        return f'<path{a} android:pathData="{circle(r)}">{glow_fill(r)}</path>'
    if shape == "sparkle":
        s, k = size, size * 0.2
        d = (
            f"M0,{num(-s)}L{num(k)},{num(-k)}L{num(s)},0L{num(k)},{num(k)}"
            f"L0,{num(s)}L{num(-k)},{num(k)}L{num(-s)},0L{num(-k)},{num(-k)}Z"
        )
        return f'<path{a} android:fillColor="#ffffffff" android:pathData="{d}"/>'
    if shape == "petal":
        s = size
        d = (
            f"M0,{num(-s)}C{num(s * .7)},{num(-s * .6)} {num(s * .55)},{num(s * .55)} 0,{num(s)}"
            f"C{num(-s * .55)},{num(s * .55)} {num(-s * .7)},{num(-s * .6)} 0,{num(-s)}Z"
        )
        return f'<path{a} android:fillColor="#ffffffff" android:pathData="{d}"/>'
    if shape == "leaf":
        s = size
        d = f"M0,{num(-s)}Q{num(s * .8)},0 0,{num(s)}Q{num(-s * .8)},0 0,{num(-s)}Z"
        return f'<path{a} android:fillColor="#ffffffff" android:pathData="{d}"/>'
    if shape == "streak":
        w = 0.7
        d = f"M{num(-w)},0L{num(w)},0L{num(w)},{num(size)}L{num(-w)},{num(size)}Z"
        return f'<path{a} android:fillColor="#ffffffff" android:pathData="{d}"/>'
    if shape == "ring":
        return (
            f'<path{a} android:strokeColor="#ffffffff" android:strokeAlpha="{num(alpha)}"'
            f' android:strokeWidth="0.8" android:pathData="{circle(size)}"/>'
        )
    if shape == "lantern":
        w, h = size * 0.6, size * 0.8
        d = f"M{num(-w)},{num(-h)}h{num(2 * w)}v{num(2 * h)}h{num(-2 * w)}Z"
        g = size * 2.4
        return (
            f'<path android:fillAlpha="{num(alpha * .45)}" android:pathData="{circle(g)}">{glow_fill(g)}</path>'
            f'<path{a} android:fillColor="#ffffffff" android:pathData="{d}"/>'
        )
    raise ValueError(shape)


def keys(prop: str, frames: list[tuple[float, float]]) -> str:
    inner = "".join(
        f'<keyframe android:fraction="{f:.4f}" android:value="{num(v)}"/>'
        for f, v in frames
    )
    return (
        f'<propertyValuesHolder android:propertyName="{prop}" android:valueType="floatType">'
        f"{inner}</propertyValuesHolder>"
    )


def looped(
    prop: str,
    start: float,
    end: float,
    phase: float,
    ms: int,
    fade: tuple[str, str, float],
) -> list[tuple[str, str]]:
    """prop runs start -> end over ms and starts over, beginning phase of the
    way along (so the particles are spread out from the first frame). The
    jump back to start happens off the picture, and fade (target name,
    property, full alpha) hides the particle for a moment round it: the home
    screen samples the motion 60 times a second, so the jump would otherwise
    cross the picture in one frame."""
    if phase < 0.002 or phase > 0.998:
        frames = [(0.0, start), (1.0, end)]
        wrap = 1.0
    else:
        wrap = 1.0 - phase
        here = start + (end - start) * phase
        frames = [(0.0, here), (wrap, end), (wrap + 0.0001, start), (1.0, here)]
    out = [("", animator(ms, keys(prop, frames)))]
    name, alpha_prop, top = fade
    d = max(0.004, 70.0 / ms)
    if wrap >= 1.0:
        alpha = [(0.0, 0.0), (d, 1.0), (1.0 - d, 1.0), (1.0, 0.0)]
    else:
        alpha = [(0.0, 1.0)]
        for f, v in (
            (wrap - d, 1.0),
            (wrap, 0.0),
            (wrap + 0.0001, 0.0),
            (wrap + d, 1.0),
            (1.0, 1.0),
        ):
            f = min(max(f, 0.0), 1.0)
            if f > alpha[-1][0]:
                alpha.append((f, v))
    out.append((name, animator(ms, keys(alpha_prop, [(f, v * top) for f, v in alpha]))))
    return out


def animator(ms: int, holder: str) -> str:
    return (
        f'<objectAnimator android:duration="{ms}" android:repeatCount="-1"'
        f' android:interpolator="{LINEAR}">{holder}</objectAnimator>'
    )


def swing(
    prop: str, low: float, high: float, ms: int, delay: int, ease: str = EASE
) -> str:
    return (
        f'<objectAnimator android:propertyName="{prop}" android:valueFrom="{num(low)}"'
        f' android:valueTo="{num(high)}" android:duration="{ms}" android:startOffset="{delay}"'
        f' android:repeatCount="-1" android:repeatMode="reverse" android:interpolator="{ease}"'
        ' android:valueType="floatType"/>'
    )


def drawable(kind: str, shape_key: str, layer: str) -> str:
    motion, shape, counts, size, secs, sway = KINDS.get(kind, KINDS["soft"])
    vw, vh = SHAPES[shape_key]
    rnd = random.Random(zlib.crc32(f"{kind}/{shape_key}/{layer}".encode()))
    # capped: each particle is a few animators the home screen runs every frame
    n = min(counts[0 if layer == "a" else 1], CAP[layer])
    groups: list[str] = []
    targets: list[tuple[str, str]] = []  # (target name, animator xml)
    margin = size[1] * 3 + 6
    if motion == "galaxy":
        cx, cy = vw / 2, vh / 2
        reach = min(vw, vh) * 0.62
        dots = []
        for i in range(n):
            arm = i % 2
            t = rnd.random()
            ang = arm * math.pi + t * 3.6 + rnd.uniform(-0.35, 0.35)
            dist = 6 + t * reach + rnd.uniform(-6, 6)
            x = cx + math.cos(ang) * dist * (1.4 if vw > vh * 1.2 else 1)
            y = cy + math.sin(ang) * dist * 0.62
            name = f"d{i}"
            alpha = rnd.uniform(0.45, 1.0)
            dots.append(
                f'<group android:translateX="{num(x)}" android:translateY="{num(y)}">'
                f"{particle('glow', rnd.uniform(*size), name, alpha)}</group>"
            )
            if i % 3 == 0:
                ms = int(rnd.uniform(1.5, 4.0) * 1000)
                targets.append(
                    (
                        name,
                        swing("fillAlpha", alpha * 0.25, alpha, ms, rnd.randrange(ms)),
                    )
                )
        ms = int(rnd.uniform(*secs) * 1000)
        groups.append(
            f'<group android:name="spin" android:pivotX="{num(cx)}" android:pivotY="{num(cy)}">'
            + "".join(dots)
            + "</group>"
        )
        turn = 360 if layer == "a" else -360
        targets.append(
            (
                "spin",
                f'<objectAnimator android:propertyName="rotation" android:valueFrom="0"'
                f' android:valueTo="{turn}" android:duration="{ms}" android:repeatCount="-1"'
                f' android:interpolator="{LINEAR}" android:valueType="floatType"/>',
            )
        )
    for i in range(0 if motion == "galaxy" else n):
        s = rnd.uniform(*size)
        alpha = rnd.uniform(0.5, 0.95)
        name = f"p{i}"
        g, sw, rot = f"g{i}", f"s{i}", f"r{i}"
        x, y = rnd.uniform(0, vw), rnd.uniform(0, vh)
        ms = int(rnd.uniform(*secs) * 1000)
        phase = rnd.random()
        if motion in ("fall", "rise"):
            top, bottom = -margin, vh + margin
            start, end = (top, bottom) if motion == "fall" else (bottom, top)
            fade = (name, "strokeAlpha" if shape == "ring" else "fillAlpha", alpha)
            targets += [
                (t or g, a)
                for t, a in looped("translateY", start, end, phase, ms, fade)
            ]
            inner = particle(shape, s, name, alpha)
            if shape in ("petal", "leaf"):
                spin = int(rnd.uniform(2.5, 6.0) * 1000)
                inner = f'<group android:name="{rot}" android:rotation="{rnd.randrange(360)}">{inner}</group>'
                targets.append(
                    (rot, swing("rotation", -70, 70, spin, rnd.randrange(spin)))
                )
            if shape == "streak":
                inner = f'<group android:rotation="12">{inner}</group>'
            amp = rnd.uniform(*sway) if sway[1] else 0
            groups.append(
                f'<group android:name="{g}" android:translateY="{num(start)}">'
                f'<group android:name="{sw}" android:translateX="{num(x - amp)}">{inner}</group></group>'
            )
            if amp:
                period = int(rnd.uniform(2.5, 5.5) * 1000)
                targets.append(
                    (
                        sw,
                        swing(
                            "translateX",
                            x - amp,
                            x + amp,
                            period,
                            rnd.randrange(period),
                        ),
                    )
                )
            if motion == "rise" and shape == "glow":
                flick = int(rnd.uniform(0.3, 0.9) * 1000)
                targets.append((rot, swing("scaleX", 0.7, 1.15, flick, 0)))
                targets.append((rot, swing("scaleY", 0.7, 1.15, flick, 0)))
                groups[-1] = groups[-1].replace(
                    inner, f'<group android:name="{rot}">{inner}</group>'
                )
        elif motion == "drift":
            left, right = -margin, vw + margin
            start, end = (left, right) if rnd.random() < 0.6 else (right, left)
            fade = (name, "fillAlpha", alpha)
            targets += [
                (t or g, a)
                for t, a in looped("translateX", start, end, phase, ms, fade)
            ]
            amp = rnd.uniform(*sway)
            yc = rnd.uniform(amp, vh - amp) if vh > 2 * amp else vh / 2
            groups.append(
                f'<group android:name="{g}" android:translateX="{num(start)}">'
                f'<group android:name="{sw}" android:translateY="{num(yc - amp)}">'
                f"{particle(shape, s, name, alpha)}</group></group>"
            )
            period = int(rnd.uniform(4, 9) * 1000)
            targets.append(
                (
                    sw,
                    swing(
                        "translateY", yc - amp, yc + amp, period, rnd.randrange(period)
                    ),
                )
            )
            if shape == "blink":
                blink = int(rnd.uniform(1.2, 3.0) * 1000)
                groups[-1] = groups[-1].replace(
                    f"{particle(shape, s, name, alpha)}",
                    f'<group android:name="{rot}">{particle(shape, s, name, alpha)}</group>',
                )
                targets.append(
                    (rot, swing("scaleX", 0.15, 1.0, blink, rnd.randrange(blink)))
                )
                targets.append(
                    (rot, swing("scaleY", 0.15, 1.0, blink, rnd.randrange(blink)))
                )
        elif motion == "twinkle":
            groups.append(
                f'<group android:translateX="{num(x)}" android:translateY="{num(y)}">'
                f"{particle(shape, s, name, alpha)}</group>"
            )
            period = int(rnd.uniform(*secs) * 1000)
            targets.append(
                (
                    name,
                    swing(
                        "fillAlpha", alpha * 0.12, alpha, period, rnd.randrange(period)
                    ),
                )
            )
        elif motion == "rays":
            width = rnd.uniform(*size)
            x0 = (i + rnd.uniform(0.1, 0.9)) * vw / max(n, 1) - vw * 0.15
            tilt = vh * rnd.uniform(0.35, 0.6)
            d = (
                f"M{num(x0)},-10L{num(x0 + width)},-10"
                f"L{num(x0 + width * 1.8 + tilt)},{num(vh + 10)}L{num(x0 + tilt)},{num(vh + 10)}Z"
            )
            groups.append(
                f'<group android:name="{g}" android:pivotX="{num(x0)}" android:pivotY="-10">'
                f'<path android:name="{name}" android:fillAlpha="0.3" android:pathData="{d}">'
                '<aapt:attr name="android:fillColor"><gradient android:type="linear"'
                f' android:startX="{num(x0)}" android:startY="0" android:endX="{num(x0 + tilt)}"'
                f' android:endY="{num(vh)}"><item android:offset="0" android:color="#ccffffff"/>'
                '<item android:offset="1" android:color="#00ffffff"/></gradient></aapt:attr></path></group>'
            )
            targets.append(
                (name, swing("fillAlpha", 0.08, 0.38, ms, rnd.randrange(ms)))
            )
            sway_ms = int(rnd.uniform(7, 12) * 1000)
            targets.append(
                (g, swing("rotation", -5, 5, sway_ms, rnd.randrange(sway_ms)))
            )
    by_target: dict[str, list[str]] = {}
    for t, a in targets:
        by_target.setdefault(t, []).append(a)
    target_xml = "".join(
        f'<target android:name="{t}"><aapt:attr name="android:animation">'
        + (anims[0] if len(anims) == 1 else "<set>" + "".join(anims) + "</set>")
        + "</aapt:attr></target>"
        for t, anims in by_target.items()
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<animated-vector xmlns:android="{ANDROID}" xmlns:aapt="{AAPT}">'
        f'<aapt:attr name="android:drawable"><vector android:width="{vw}dp" android:height="{vh}dp"'
        f' android:viewportWidth="{vw}" android:viewportHeight="{vh}">'
        + "".join(groups)
        + "</vector></aapt:attr>"
        + target_xml
        + "</animated-vector>\n"
    )


def bars() -> str:
    """The visualiser widget's moving bars (visualiser/res/drawable/viz_bars.xml,
    checked in: make_widget_res.py --bars rewrites it). Fourteen bars rise and
    fall at their own pace, played by the home screen like the particles."""
    rnd = random.Random(1322)
    vw, vh, n, width, gap = 168, 48, 14, 8, 4
    groups, targets = [], []
    for i in range(n):
        x = i * (width + gap) + gap / 2
        g = f"b{i}"
        d = (
            f"M{num(x)},{vh}L{num(x)},{num(3)}Q{num(x)},0 {num(x + width / 2)},0"
            f"Q{num(x + width)},0 {num(x + width)},{num(3)}L{num(x + width)},{vh}Z"
        )
        low = rnd.uniform(0.12, 0.35)
        high = rnd.uniform(0.55, 1.0)
        groups.append(
            f'<group android:name="{g}" android:pivotY="{vh}" android:scaleY="{num(low)}">'
            f'<path android:fillColor="#ffffffff" android:fillAlpha="0.92" android:pathData="{d}"/>'
            "</group>"
        )
        ms = int(rnd.uniform(0.38, 0.9) * 1000)
        targets.append((g, swing("scaleY", low, high, ms, rnd.randrange(ms))))
    target_xml = "".join(
        f'<target android:name="{t}"><aapt:attr name="android:animation">{a}</aapt:attr></target>'
        for t, a in targets
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- made by phone/theme/make_widget_res.py in bars mode (task 1322) -->\n"
        f'<animated-vector xmlns:android="{ANDROID}" xmlns:aapt="{AAPT}">'
        f'<aapt:attr name="android:drawable"><vector android:width="{vw}dp" android:height="{vh}dp"'
        f' android:viewportWidth="{vw}" android:viewportHeight="{vh}">'
        + "".join(groups)
        + "</vector></aapt:attr>"
        + target_xml
        + "</animated-vector>\n"
    )


BARS = (
    Path(__file__).resolve().parent / "visualiser" / "res" / "drawable" / "viz_bars.xml"
)


def layout(kind: str, shape_key: str) -> str:
    def bar(layer: str) -> str:
        return (
            f'<ProgressBar android:id="@id/wp_{layer}" android:layout_width="match_parent"'
            ' android:layout_height="match_parent" android:indeterminate="true"'
            ' android:indeterminateOnly="false" android:progressDrawable="@null"'
            f' android:indeterminateDrawable="@drawable/wp_{kind}_{shape_key}_{layer}"'
            ' android:indeterminateTint="#ffffffff" android:indeterminateTintMode="src_in"'
            ' android:minWidth="0dp" android:minHeight="0dp" android:maxWidth="4000dp"'
            ' android:maxHeight="4000dp" android:importantForAccessibility="no" />'
        )

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<FrameLayout xmlns:android="{ANDROID}" android:id="@id/root"'
        ' android:layout_width="match_parent" android:layout_height="match_parent"'
        ' android:padding="4dp">'
        '<FrameLayout android:layout_width="match_parent" android:layout_height="match_parent"'
        ' android:background="@drawable/panel">'
        '<ImageView android:id="@id/wp_bg" android:layout_width="match_parent"'
        ' android:layout_height="match_parent" android:scaleType="centerCrop"'
        ' android:contentDescription="@null" />'
        + bar("a")
        + bar("b")
        + "</FrameLayout></FrameLayout>\n"
    )


def build(particles_json: Path, out: Path) -> int:
    kinds = [k["key"] for k in json.loads(particles_json.read_text(encoding="utf-8"))]
    for k in kinds:
        if not k.replace("_", "").isalnum() or not k.islower():
            raise SystemExit(f"particle kind {k!r} cannot be a resource name")
    (out / "drawable").mkdir(parents=True, exist_ok=True)
    (out / "layout").mkdir(parents=True, exist_ok=True)
    made = 0
    for kind in kinds:
        for shape_key in SHAPES:
            for layer in "ab":
                path = out / "drawable" / f"wp_{kind}_{shape_key}_{layer}.xml"
                path.write_text(drawable(kind, shape_key, layer), encoding="utf-8")
                made += 1
            (out / "layout" / f"widget_p_{kind}_{shape_key}.xml").write_text(
                layout(kind, shape_key), encoding="utf-8"
            )
    return made


if __name__ == "__main__":
    if sys.argv[1:] == ["--bars"]:
        BARS.write_text(bars(), encoding="utf-8")
        print(f"wrote {BARS}")
        raise SystemExit(0)
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    print("widget pictures:", build(Path(sys.argv[1]), Path(sys.argv[2])))

"""Which GPU this computer has, which llama.cpp backend to run it with, how
much of its memory a model may take, and how to split one model's layers
over several machines by that memory (Relay task 524).

Works for NVIDIA, AMD and Intel cards and for integrated graphics (an Intel
Iris Xe like this laptop's, an AMD Radeon iGPU), on Linux and Windows. The
backends and their order live in llama_backends.json: CUDA when NVIDIA is
there, Vulkan for everything else (and the default), ROCm or SYCL only when
asked for by name, the CPU as the last resort.

How a device is found. The hardware comes from the system (Linux:
/sys/class/drm and nvidia-smi; Windows: Win32_VideoController). What
llama.cpp can really use, and how much memory is free right now, comes from
the build itself (`llama-server --list-devices`), because only that sees
what the driver gives it. On this laptop that probe runs only while nobody
else holds the shared iGPU lock (forge_ram; Jan and OpenVINO crash each
other on the Iris Xe), so a busy iGPU is reported, never touched.

Memory. A discrete card may take its free memory less keep_free_mb. An
integrated one shares the system RAM, so it gets share_of_free of what it
reports free and never so much that less than keep_ram_mb of RAM stays
free. Layers left on the CPU come out of the same RAM.

    forge_gpu.py detect [--json] [--backend ID]  what is here and the pick
    forge_gpu.py fetch [--backend ID] [--build B] the official llama.cpp build
    forge_gpu.py plan MODEL.gguf [--peer MB ...]  how its layers would split

Builds go to ~/.local/share/forge/llama.cpp/<build>/<backend>/ (downloaded
only by `fetch`, from the ggml-org/llama.cpp GitHub releases, and only a file
whose SHA-256 is in llama_backends.json). Jan's own
builds are used too when they fit, but they have no RPC server.
"""

from __future__ import annotations

import argparse
import ctypes
import functools
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REGISTRY = HERE / "llama_backends.json"
SETTING = "ai_backend"  # Settings > Graphics and processor
LIST_TIMEOUT = 30.0
DEVICE_RE = re.compile(
    r"^\s*(?P<id>[A-Za-z]+\d+):\s*(?P<name>.*?)\s*\((?P<total>\d+) MiB,"
    r"\s*(?P<free>\d+) MiB free\)\s*$"
)
VK_UMA_RE = re.compile(r"ggml_vulkan:\s*(\d+)\s*=\s*.*?\|\s*uma:\s*(\d)")
# Intel discrete (Arc) device ids: DG2/Alchemist 0x56xx, Battlemage 0xe2xx
PCI_IDS = (Path("/usr/share/hwdata/pci.ids"), Path("/usr/share/misc/pci.ids"))
WINDOWS = os.name == "nt"
ADAPTER_RE = re.compile(r"luid_(0x[0-9a-fA-F]+)_(0x[0-9a-fA-F]+)_phys_(\d+)")
# Intel discrete (Arc) device ids: DG2/Alchemist 0x56xx, Battlemage 0xe2xx
INTEL_DISCRETE = re.compile(r"^0x(56|e2)[0-9a-f]{2}$")
AMD_IGPU_NAME = re.compile(
    r"radeon(\(tm\))?\s+((\d{3,4}[ms]?\s+)?graphics\b|vega\s*\d+\s*graphics|\d{3}m\b)",
    re.I,
)


# Under pythonw (Petal's helper on Windows) a console program started
# without this opens a console window on the screen for a moment.
QUIET = (
    {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}
    if os.name == "nt"
    else {}
)


def os_name(plat: str | None = None) -> str:
    plat = plat or sys.platform
    if plat.startswith("win"):
        return "windows"
    return "mac" if plat == "darwin" else "linux"


def load_registry(path: Path = REGISTRY) -> dict:
    return json.loads(Path(path).read_text())


def exe_name(name: str, plat: str | None = None) -> str:
    return name + ".exe" if os_name(plat) == "windows" else name


# ---------------------------------------------------------------- hardware


def _read(p: Path) -> str:
    try:
        return p.read_text().strip()
    except OSError:
        return ""


def apu_locations(root: Path = Path("/sys/class/kfd/kfd/topology/nodes")) -> set:
    """PCI locations (domain, location_id) of AMD APUs from the amdkfd
    topology: an APU node has CPU cores and GPU SIMDs. Empty without amdkfd."""
    found = set()
    try:
        nodes = list(root.iterdir())
    except OSError:
        return found
    for node in nodes:
        props = {}
        for line in _read(node / "properties").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].isdigit():
                props[parts[0]] = int(parts[1])
        if props.get("cpu_cores_count", 0) > 0 and props.get("simd_count", 0) > 0:
            found.add((props.get("domain", 0), props.get("location_id", -1)))
    return found


def _pci_location(dev: Path) -> tuple[int, int] | None:
    """(domain, bus << 8 | devfn) from the device's PCI address."""
    m = re.fullmatch(
        r"([0-9a-f]{4}):([0-9a-f]{2}):([0-9a-f]{2})\.([0-7])",
        os.path.basename(os.path.realpath(dev)),
    )
    if not m:
        return None
    dom, bus, slot, fn = (int(g, 16) for g in m.groups())
    return dom, bus << 8 | slot << 3 | fn


def _amd_kind(dev: Path, total: int | None, kfd: set) -> str:
    """An APU (integrated) or a card. amdkfd says it exactly; without it an
    APU's "VRAM" is a small carve-out of the RAM, and 4 GB cards (RX 6400,
    6500 XT) exist, so only 2 GB or less counts as integrated. Vulkan's own
    unified-memory flag overrides this once llama.cpp lists the device."""
    if kfd:
        return "integrated" if _pci_location(dev) in kfd else "discrete"
    return "integrated" if total is not None and total <= 2048 else "discrete"


def linux_gpus(
    root: Path = Path("/sys/class/drm"),
    reg: dict | None = None,
    kfd_root: Path = Path("/sys/class/kfd/kfd/topology/nodes"),
    pci_ids: tuple = PCI_IDS,
) -> list:
    """The graphics devices in /sys/class/drm (one per card): vendor, kind,
    the name pci.ids gives it, whether it is an eGPU, and memory when the
    driver tells it (amdgpu does; i915 and nvidia do not: nvidia-smi adds
    an NVIDIA card's in system_gpus, and an iGPU's memory is the RAM)."""
    reg = reg or load_registry()
    out = []
    kfd = apu_locations(kfd_root)
    try:
        cards = sorted(p for p in root.iterdir() if re.fullmatch(r"card\d+", p.name))
    except OSError:
        return out
    for card in cards:
        dev = card / "device"
        vid, did = _read(dev / "vendor").lower(), _read(dev / "device").lower()
        if not vid:
            continue
        vendor = reg["vendors"].get(vid, "other")
        driver = os.path.basename(os.path.realpath(dev / "driver"))
        total = _mb(_read(dev / "mem_info_vram_total"))
        used = _mb(_read(dev / "mem_info_vram_used"))
        if vendor == "intel":
            kind = "discrete" if INTEL_DISCRETE.match(did) else "integrated"
        elif vendor == "amd":
            kind = _amd_kind(dev, total, kfd)
        else:
            kind = "discrete"
        raw = pci_name(vid, did, pci_ids)
        out.append(
            {
                "name": (
                    nice_name(raw, vendor)
                    if raw
                    else f"{vendor.upper() if vendor == 'amd' else vendor.title()}"
                    f" GPU {did}"
                ),
                "vendor": vendor,
                "kind": kind,
                "pci": f"{vid}:{did}",
                "driver": driver,
                "card": card.name,
                "pdev": os.path.basename(os.path.realpath(dev)),
                "total_mb": total,
                "free_mb": None if total is None or used is None else total - used,
                # behind a Thunderbolt/USB4 port: the kernel marks the device
                # removable (an eGPU); it works like any card of its kind
                "external": _read(dev / "removable") == "removable",
                "source": "sysfs",
            }
        )
    return out


def _mb(raw: str) -> int | None:
    return int(raw) // (1024 * 1024) if raw.isdigit() else None


def nvidia_smi(
    plat: str | None = None, env: dict | None = None, which=shutil.which
) -> str | None:
    """nvidia-smi's path: on PATH, else where NVIDIA's Windows driver puts it
    (System32 since the DCH drivers, NVSMI before them, and the driver
    store, which a fresh install sometimes leaves off PATH). The search off
    PATH runs once per process (the driver store is a big folder); a driver
    installed since is found after a restart."""
    found = which("nvidia-smi")
    if found:
        return found
    if os_name(plat) != "windows":
        return None
    vals = dict(os.environ) if env is None else env
    root = Path(vals.get("SystemRoot") or vals.get("windir") or "C:\\Windows")
    progs = vals.get("ProgramW6432") or vals.get("ProgramFiles") or "C:\\Program Files"
    key = (str(root), str(progs))
    if key in _SMI_FOUND:
        return _SMI_FOUND[key]
    places = [
        root / "System32" / "nvidia-smi.exe",
        Path(progs) / "NVIDIA Corporation" / "NVSMI" / "nvidia-smi.exe",
    ]
    try:
        store = root / "System32" / "DriverStore" / "FileRepository"
        places += sorted(
            store.glob("nv*/nvidia-smi.exe"),
            key=lambda q: q.stat().st_mtime,
            reverse=True,
        )
    except OSError:
        pass
    _SMI_FOUND[key] = next((str(q) for q in places if q.is_file()), None)
    return _SMI_FOUND[key]


# nvidia_smi's search off PATH, by (SystemRoot, Program Files)
_SMI_FOUND: dict[tuple[str, str], str | None] = {}


def nvidia_gpus(run=subprocess.run, exe: str | None = None) -> list:
    """NVIDIA cards from nvidia-smi (name, total and free memory, PCI bus)."""
    exe = exe or nvidia_smi()
    if not exe:
        return []
    try:
        done = run(
            [
                exe,
                "--query-gpu=name,memory.total,memory.free,pci.bus_id",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=10,
            **QUIET,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_nvidia_smi(done.stdout)


def parse_nvidia_smi(text: str) -> list:
    """One row per card. A memory nvidia-smi cannot read ([N/A], [Not
    Supported]) is None, and the card is kept."""
    out = []
    for line in text.splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) not in (3, 4) or not parts[0]:
            continue
        if not (parts[1].isdigit() or parts[1].startswith("[")):
            continue
        out.append(
            {
                "name": parts[0],
                "vendor": "nvidia",
                "kind": "discrete",
                "total_mb": int(parts[1]) if parts[1].isdigit() else None,
                "free_mb": int(parts[2]) if parts[2].isdigit() else None,
                "bus": normal_bus(parts[3]) if len(parts) == 4 else "",
                "source": "nvidia-smi",
            }
        )
    return out


def windows_gpus(run=subprocess.run, reg: dict | None = None) -> list:
    """The graphics adapters on Windows: DXGI first (name, the card's own
    memory as a 64-bit size, and what is in use from the "GPU Adapter
    Memory" counters Task Manager reads), PowerShell's Win32_VideoController
    when DXGI gives nothing (its AdapterRAM is 32-bit: no size over 4 GB)."""
    found = dxgi_gpus(reg=reg)
    if found:
        return found
    try:
        done = run(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-CimInstance Win32_VideoController | Select-Object"
                " Name,PNPDeviceID,AdapterRAM | ConvertTo-Json",
            ],
            capture_output=True,
            text=True,
            timeout=20,
            **QUIET,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_windows_cim(done.stdout, reg)


def parse_windows_cim(text: str, reg: dict | None = None) -> list:
    reg = reg or load_registry()
    try:
        rows = json.loads(text or "[]")
    except ValueError:
        return []
    rows = [rows] if isinstance(rows, dict) else rows
    out = []
    for r in rows if isinstance(rows, list) else []:
        if not isinstance(r, dict):
            continue
        pnp = str(r.get("PNPDeviceID") or "").upper()
        m = re.search(r"VEN_([0-9A-F]{4}).*?DEV_([0-9A-F]{4})", pnp)
        vid = f"0x{m.group(1).lower()}" if m else ""
        did = f"0x{m.group(2).lower()}" if m else ""
        vendor = reg["vendors"].get(vid, "other")
        if not m:
            continue  # remote desktop and other virtual adapters
        name = str(r.get("Name") or "")
        if vendor == "intel":
            # Arc cards are 0x56xx/0xe2xx; "Arc Graphics" alone is a Core Ultra iGPU
            kind = "discrete" if INTEL_DISCRETE.match(did) else "integrated"
        elif vendor == "amd":
            # AdapterRAM is 32-bit (4 GB and up read 0xFFFFFFFF), so the name
            # decides first: "Radeon(TM) Graphics", "780M", "8060S Graphics"
            ram = r.get("AdapterRAM") or 0
            kind = "integrated" if isinstance(ram, int) and ram <= 2**30 else "discrete"
            if AMD_IGPU_NAME.search(name):
                kind = "integrated"
        else:
            kind = "discrete"
        out.append(
            {
                "name": name,
                "vendor": vendor,
                "kind": kind,
                "pci": f"{vid}:{did}",
                "total_mb": None,
                "free_mb": None,
                "source": "windows",
            }
        )
    return out


def dxgi_gpus(
    adapters: list | None = None,
    used: dict | None = None,
    reg: dict | None = None,
) -> list:
    """windows_gpus' rows from DXGI adapters and the per-adapter dedicated
    memory in use ({luid: bytes}); both are read here when not given."""
    reg = reg or load_registry()
    adapters = dxgi_adapters() if adapters is None else adapters
    if not adapters:
        return []
    if used is None:
        pdh = Pdh({"dedicated": Pdh.COUNTERS["dedicated"]})
        try:
            used = adapter_memory(pdh.read().get("dedicated", []))
        finally:
            pdh.close()
    out = []
    for a in adapters:
        vendor = reg["vendors"].get(a["vendor_id"], "other")
        if vendor == "other" and a["dedicated_mb"] < 1:
            continue  # Basic Render, remote-desktop and other virtual adapters
        kind = kind_of(vendor, a["device_id"], a["name"], a["dedicated_mb"])
        total = int(a["dedicated_mb"]) or None
        busy = used.get(a["luid"])
        free = None
        if total and kind == "discrete" and busy is not None:
            free = max(0, total - int(busy / 2**20))
        out.append(
            {
                "name": a["name"],
                "vendor": vendor,
                "kind": kind,
                "pci": f"{a['vendor_id']}:{a['device_id']}",
                "total_mb": total if kind == "discrete" else None,
                "free_mb": free,
                "luid": a["luid"],
                "source": "dxgi",
            }
        )
    return out


def system_gpus(plat: str | None = None, reg: dict | None = None) -> list:
    reg = reg or load_registry()
    if os_name(plat) == "windows":
        found = windows_gpus(reg=reg)
    else:
        found = linux_gpus(reg=reg)
    return merge_smi(found, nvidia_gpus())


def merge_smi(found: list, smi: list) -> list:
    """nvidia-smi knows an NVIDIA card's name and memory (sysfs knows
    neither, DXGI not what is free): every card on the same PCI bus first,
    then the rows left over to the NVIDIA cards left over, in order. A
    memory nvidia-smi does not know leaves the system's own. A card
    nvidia-smi lists that the system did not is added."""
    smi = list(smi)
    pairs = []
    cards = [c for c in found if c["vendor"] == "nvidia"]
    for card in list(cards):
        same = next(
            (s for s in smi if s.get("bus") and s["bus"] == card.get("pdev")), None
        )
        if same:
            smi.remove(same)
            cards.remove(card)
            pairs.append((card, same))
    for card in cards:
        if not smi:
            break
        if card.get("pdev") and any(s.get("bus") for s in smi):
            continue  # a card with a known bus nvidia-smi did not list
        pairs.append((card, smi.pop(0)))
    for card, row in pairs:
        card["name"] = row["name"]
        for k in ("total_mb", "free_mb"):
            if row[k] is not None or k not in card:
                card[k] = row[k]
        card["source"] = f"{card.get('source', '')}+nvidia-smi".lstrip("+")
    return found + smi


# ------------------------------------------- names, DXGI and PDH (Windows)
# Moved here from apps/monitor/gpus.py (Relay task 1154) so Forge Models,
# Settings and Monitor read the same cards the same way.


@functools.lru_cache(maxsize=64)
def pci_name(vid: str, did: str, files=PCI_IDS) -> str | None:
    """'Alder Lake-P GT2 [Iris Xe Graphics]' for 0x8086:0x46a8, from the
    pci.ids list most Linux systems carry."""
    vid, did = vid.lower().removeprefix("0x"), did.lower().removeprefix("0x")
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        inside = False
        for line in lines:
            if not line or line.startswith("#"):
                continue
            if not line.startswith("\t"):
                inside = line[:4].lower() == vid
            elif inside and not line.startswith("\t\t") and line[1:5].lower() == did:
                return line[5:].strip()
    return None


def nice_name(raw: str | None, vendor: str) -> str:
    """The marketing name in brackets when pci.ids has one."""
    if not raw:
        return vendor.title() + " GPU"
    m = re.search(r"\[([^\]]+)\]\s*$", raw)
    inner = m.group(1) if m else raw
    brand = {"intel": "Intel", "amd": "AMD", "nvidia": "NVIDIA"}.get(vendor, "")
    return (
        inner if inner.lower().startswith(brand.lower()) else f"{brand} {inner}".strip()
    )


def normal_bus(bus: str) -> str:
    """nvidia-smi says 00000000:01:00.0, sysfs says 0000:01:00.0."""
    bus = bus.lower()
    return bus[-12:] if len(bus) >= 12 else bus


def luid_key(high: str | int, low: str | int) -> str:
    h = int(high, 16) if isinstance(high, str) else high
    lo = int(low, 16) if isinstance(low, str) else low
    return f"{h & 0xFFFFFFFF:08x}{lo & 0xFFFFFFFF:08x}"


def kind_of(vendor: str, did: str, name: str, dedicated_mb: float) -> str:
    """forge_gpu's rules, plus: under 512 MB of its own memory is integrated."""
    if vendor == "intel":
        return "discrete" if INTEL_DISCRETE.match(did) else "integrated"
    if vendor == "amd":
        if AMD_IGPU_NAME.search(name) or dedicated_mb <= 1024:
            return "integrated"
        return "discrete"
    if vendor == "nvidia":
        return "discrete"
    return "integrated" if dedicated_mb <= 512 else "discrete"


def dxgi_adapters() -> list[dict]:
    """[{name, vendor_id, device_id, dedicated_mb, shared_mb, luid}] through
    CreateDXGIFactory1 / EnumAdapters1 / GetDesc1 (COM vtables by index)."""
    if not WINDOWS:
        return []
    from ctypes import wintypes

    class LUID(ctypes.Structure):
        _fields_ = [("LowPart", wintypes.DWORD), ("HighPart", wintypes.LONG)]

    class DESC1(ctypes.Structure):
        _fields_ = [
            ("Description", ctypes.c_wchar * 128),
            ("VendorId", wintypes.UINT),
            ("DeviceId", wintypes.UINT),
            ("SubSysId", wintypes.UINT),
            ("Revision", wintypes.UINT),
            ("DedicatedVideoMemory", ctypes.c_size_t),
            ("DedicatedSystemMemory", ctypes.c_size_t),
            ("SharedSystemMemory", ctypes.c_size_t),
            ("AdapterLuid", LUID),
            ("Flags", wintypes.UINT),
        ]

    class GUID(ctypes.Structure):
        _fields_ = [
            ("a", wintypes.DWORD),
            ("b", wintypes.WORD),
            ("c", wintypes.WORD),
            ("d", ctypes.c_ubyte * 8),
        ]

    iid = GUID(
        0x770AAE78,
        0xF26F,
        0x4DBA,
        (ctypes.c_ubyte * 8)(*b"\xa8\x29\x25\x3c\x83\xd1\xb3\x87"),
    )
    out: list[dict] = []
    try:
        dxgi = ctypes.WinDLL("dxgi")  # type: ignore[attr-defined]
    except OSError:
        return out
    factory = ctypes.c_void_p()
    if dxgi.CreateDXGIFactory1(ctypes.byref(iid), ctypes.byref(factory)) != 0:
        return out

    def method(obj: ctypes.c_void_p, index: int, *argtypes):
        vtable = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p)))[0]
        proto = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, *argtypes)  # type: ignore[attr-defined]
        return proto(vtable[index])

    try:
        i = 0
        while i < 16:
            adapter = ctypes.c_void_p()
            enum = method(factory, 12, wintypes.UINT, ctypes.POINTER(ctypes.c_void_p))
            if enum(factory, i, ctypes.byref(adapter)) != 0:
                break
            desc = DESC1()
            get_desc = method(adapter, 10, ctypes.POINTER(DESC1))
            if get_desc(adapter, ctypes.byref(desc)) == 0 and not desc.Flags & 2:
                out.append(
                    {
                        "name": desc.Description,
                        "vendor_id": f"0x{desc.VendorId:04x}",
                        "device_id": f"0x{desc.DeviceId:04x}",
                        "dedicated_mb": desc.DedicatedVideoMemory / 2**20,
                        "shared_mb": desc.SharedSystemMemory / 2**20,
                        "luid": luid_key(
                            desc.AdapterLuid.HighPart, desc.AdapterLuid.LowPart
                        ),
                    }
                )
            method(adapter, 2)(adapter)  # Release
            i += 1
    finally:
        method(factory, 2)(factory)
    return out


def adapter_memory(items: list[tuple[str, float]]) -> dict:
    """'GPU Adapter Memory' instances -> {luid: bytes} (summed over phys)."""
    out: dict[str, float] = {}
    for instance, value in items:
        m = ADAPTER_RE.search(instance)
        if m and value is not None:
            key = luid_key(m.group(1), m.group(2))
            out[key] = out.get(key, 0.0) + value
    return out


class Pdh:
    """Wildcard performance counters read through pdh.dll."""

    COUNTERS = {
        "engine": r"\GPU Engine(*)\Utilization Percentage",
        "dedicated": r"\GPU Adapter Memory(*)\Dedicated Usage",
        "shared": r"\GPU Adapter Memory(*)\Shared Usage",
    }

    def __init__(self, counters: dict[str, str] | None = None) -> None:
        self.ok = False
        self.handles: dict[str, ctypes.c_void_p] = {}
        self.counters = self.COUNTERS if counters is None else counters
        if not WINDOWS:
            return
        try:
            self.pdh = ctypes.WinDLL("pdh")  # type: ignore[attr-defined]
            self.query = ctypes.c_void_p()
            if self.pdh.PdhOpenQueryW(None, None, ctypes.byref(self.query)) != 0:
                return
            for name, path in self.counters.items():
                handle = ctypes.c_void_p()
                if (
                    self.pdh.PdhAddEnglishCounterW(
                        self.query, ctypes.c_wchar_p(path), None, ctypes.byref(handle)
                    )
                    == 0
                ):
                    self.handles[name] = handle
            self.pdh.PdhCollectQueryData(self.query)
            self.ok = bool(self.handles)
        except (OSError, AttributeError):
            self.ok = False

    def close(self) -> None:
        """Free the query (a one-off reading opens one each time)."""
        query = getattr(self, "query", None)
        if query is not None and query.value:
            try:
                self.pdh.PdhCloseQuery(query)
            except (OSError, AttributeError):
                pass
            self.query = ctypes.c_void_p()
        self.ok, self.handles = False, {}

    def read(self) -> dict[str, list[tuple[str, float]]]:
        if not self.ok:
            return {}
        from ctypes import wintypes

        class VALUE(ctypes.Structure):
            _fields_ = [("CStatus", wintypes.DWORD), ("doubleValue", ctypes.c_double)]

        class ITEM(ctypes.Structure):
            _fields_ = [("szName", ctypes.c_wchar_p), ("FmtValue", VALUE)]

        if self.pdh.PdhCollectQueryData(self.query) != 0:
            return {}
        out = {}
        for name, handle in self.handles.items():
            size, count = wintypes.DWORD(0), wintypes.DWORD(0)
            self.pdh.PdhGetFormattedCounterArrayW(
                handle, 0x200, ctypes.byref(size), ctypes.byref(count), None
            )
            if not size.value:
                out[name] = []
                continue
            buf = (ctypes.c_byte * size.value)()
            status = self.pdh.PdhGetFormattedCounterArrayW(
                handle, 0x200, ctypes.byref(size), ctypes.byref(count), buf
            )
            if status != 0:
                out[name] = []
                continue
            items = ctypes.cast(buf, ctypes.POINTER(ITEM))
            rows = []
            for i in range(count.value):
                item = items[i]
                if item.FmtValue.CStatus in (0, 1):  # valid data, new data
                    rows.append((item.szName or "", item.FmtValue.doubleValue))
            out[name] = rows
        return out


# ------------------------------------------------------------------ builds


def builds_dir(reg: dict | None = None) -> Path:
    reg = reg or load_registry()
    return Path(os.path.expanduser(reg["builds_dir"]))


def backend_of_flavour(flavour: str) -> str:
    """Jan's build folders say their backend in the name."""
    for bid in ("cuda", "vulkan", "rocm", "hip", "sycl"):
        if bid in flavour:
            return "rocm" if bid == "hip" else bid
    return "cpu"


def find_builds(reg: dict | None = None, plat: str | None = None) -> list:
    """Every llama.cpp build here: Forge's own (newest build first), then
    Jan's (no RPC server). Each: backend, build, server, rpc_server."""
    reg = reg or load_registry()
    server, rpc = exe_name("llama-server", plat), exe_name("ggml-rpc-server", plat)
    out = []
    root = builds_dir(reg)
    try:
        numbered = sorted(
            (p for p in root.iterdir() if p.is_dir()),
            key=lambda p: int(p.name[1:]) if p.name[1:].isdigit() else -1,
            reverse=True,
        )
    except OSError:
        numbered = []
    for build in numbered:
        for b in reg["backends"]:
            for exe in sorted((build / b["id"]).glob(f"**/{server}")):
                r = exe.with_name(rpc)
                out.append(
                    {
                        "backend": b["id"],
                        "build": build.name,
                        "server": str(exe),
                        "rpc_server": str(r) if r.exists() else None,
                        "source": "forge",
                    }
                )
                break
    try:
        if str(HERE) not in sys.path:
            sys.path.insert(0, str(HERE))
        import forge_jan

        jan = forge_jan.JAN_DATA / "backends"
        for build in sorted(jan.iterdir(), reverse=True):
            for flav in sorted(build.iterdir()):
                exe = flav / "build" / "bin" / server
                if exe.exists():
                    out.append(
                        {
                            "backend": backend_of_flavour(flav.name),
                            "build": build.name,
                            "server": str(exe),
                            "rpc_server": None,
                            "source": "jan",
                        }
                    )
    except (ImportError, OSError):
        pass
    for exe in (shutil.which(server),):
        if exe:
            r = shutil.which(rpc)
            out.append(
                {
                    "backend": "unknown",
                    "build": "",
                    "server": exe,
                    "rpc_server": r,
                    "source": "path",
                }
            )
    return out


def build_env(server: str) -> dict:
    """Run a build with its own libraries found next to it."""
    env = dict(os.environ)
    lib = str(Path(server).parent)
    key = "PATH" if os_name() == "windows" else "LD_LIBRARY_PATH"
    env[key] = lib + os.pathsep + env.get(key, "") if env.get(key) else lib
    return env


def setting() -> str:
    """Forge's `ai_backend` setting (Settings > Graphics and processor),
    "auto" when unset or unreadable."""
    try:
        if str(HERE) not in sys.path:
            sys.path.insert(0, str(HERE))
        import forge_theme  # pyright: ignore[reportMissingImports]  # shared/

        value = forge_theme.load().get(SETTING)
    except Exception:  # an optional extra: no settings file means auto
        return "auto"
    return value if isinstance(value, str) and value else "auto"


def wanted(reg: dict, gpus: list, want: str | None = None) -> str | None:
    """The backend asked for (`want`, else the setting) when it can serve
    here: "cpu", or a known backend that suits one of these GPUs. None means
    the automatic pick."""
    want = want or setting()
    if want == "cpu":
        return want
    for b in reg["backends"]:
        if b["id"] == want and any(_suits(b, g) for g in gpus):
            return want
    return None


def best_backend(
    reg: dict | None = None, gpus: list | None = None, want: str | None = None
) -> str:
    """The build worth downloading for this computer's graphics: the one the
    ai_backend setting (or `want`) names when it suits a GPU here, else the
    first backend that suits one of its GPUs and wins over Vulkan, else the
    CPU."""
    reg = reg or load_registry()
    gpus = system_gpus(reg=reg) if gpus is None else gpus
    picked = wanted(reg, gpus, want)
    if picked:
        return picked
    return next(
        (
            b["id"]
            for b in reg["backends"]
            if b["prefer_over_vulkan"] and any(_suits(b, g) for g in gpus)
        ),
        "cpu",
    )


def fetch(backend: str, build: str | None = None, reg: dict | None = None) -> Path:
    """Download and unpack the official llama.cpp release for `backend` on
    this OS into builds_dir/<build>/<backend>/. Returns that folder."""
    reg = reg or load_registry()
    build = build or reg["default_build"]
    entry = next((b for b in reg["backends"] if b["id"] == backend), None)
    if not entry:
        raise SystemExit(f"unknown backend {backend!r}")
    osn = os_name()
    assets = [entry["build"].get(osn)] + [(entry.get("extra") or {}).get(osn)]
    if not assets[0]:
        raise SystemExit(f"no official {entry['name']} build for {osn}")
    dest = builds_dir(reg) / build / backend
    dest.mkdir(parents=True, exist_ok=True)
    # the pins are for default_build (cudart's name has no build number in it)
    pins = (reg.get("sha256") or {}) if build == reg["default_build"] else {}
    parts = []  # every file is checked before any is unpacked
    for asset in filter(None, assets):
        name = asset.format(build=build)
        url = reg["release"].format(build=build, asset=name)
        part = dest / (name + ".part")
        want = pins.get(name)
        if not want:  # never run a program whose bytes nobody wrote down
            for _, p in parts:
                p.unlink(missing_ok=True)
            raise SystemExit(
                f"{name} has no SHA-256 on record (only build"
                f" {reg['default_build']} is pinned), so it is not downloaded"
            )
        print("downloading", url, flush=True)
        digest = hashlib.sha256()
        try:
            with urllib.request.urlopen(url, timeout=60) as r, part.open("wb") as f:
                while chunk := r.read(1 << 20):
                    digest.update(chunk)
                    f.write(chunk)
        except BaseException:  # a broken download leaves no half file behind
            for _, p in parts + [(name, part)]:
                p.unlink(missing_ok=True)
            raise
        if digest.hexdigest() != want:
            for _, p in parts + [(name, part)]:
                p.unlink(missing_ok=True)
            raise SystemExit(f"{name} is not the file GitHub lists (sha256 differs)")
        parts.append((name, part))
    for name, part in parts:
        if name.endswith(".zip"):
            with zipfile.ZipFile(part) as z:
                for member in z.namelist():  # nothing outside dest
                    target = (dest / member).resolve()
                    if not target.is_relative_to(dest.resolve()):
                        raise SystemExit(f"unsafe path in {name}: {member}")
                z.extractall(dest)
        else:
            with tarfile.open(part) as t:
                t.extractall(dest, filter="data")
        part.unlink()
    return dest


# ------------------------------------------------------- llama.cpp's view


def parse_list_devices(text: str) -> list:
    """`llama-server --list-devices` (stdout and stderr together): each
    device with id, backend, name, total and free MiB, and integrated when
    the Vulkan backend said uma: 1 (shared memory)."""
    uma = {int(m.group(1)): m.group(2) == "1" for m in VK_UMA_RE.finditer(text)}
    out = []
    started = False
    for line in text.splitlines():
        if line.strip().startswith("Available devices"):
            started = True
            continue
        m = DEVICE_RE.match(line) if started else None
        if not m:
            continue
        did = m.group("id")
        prefix = re.match(r"[A-Za-z]+", did)
        backend = prefix.group(0) if prefix else did
        num = int(did[len(backend) :] or 0)
        dev = {
            "id": did,
            "backend": backend.lower(),
            "name": m.group("name"),
            "total_mb": int(m.group("total")),
            "free_mb": int(m.group("free")),
        }
        if backend == "Vulkan" and num in uma:
            dev["integrated"] = uma[num]
        out.append(dev)
    return out


def gpu_busy_by_other() -> dict | None:
    """Who else holds the shared iGPU lock (forge_ram), or None. Not
    ourselves: a job that took the lock may still probe."""
    try:
        sys.path.insert(0, str(HERE))
        import forge_ram

        holder = forge_ram.gpu_holder(
            forge_ram.gpu_lock_path(forge_ram.load_registry())
        )
    except (ImportError, OSError, ValueError, AttributeError):
        return None  # Windows (no fcntl) or no registry: no lock to respect
    if not holder:
        return None
    mine = {os.getpid(), os.getppid()}
    return None if holder["pid"] in mine else holder


def probe_under_lock(server: str, extra: list[str] | None = None) -> list:
    """list_devices for a caller that does not hold the shared iGPU lock (the
    Mission page): the lock is taken for the probe only, so nothing that
    takes it meanwhile (OpenVINO) meets llama.cpp on the Iris Xe."""
    if os.environ.get("FORGE_GPU_LOCK_HELD") == "1":
        return list_devices(server, extra)
    try:
        sys.path.insert(0, str(HERE))
        import forge_ram

        lock = forge_ram.gpu_lock_path(forge_ram.load_registry())
    except (ImportError, OSError, ValueError, AttributeError):
        return list_devices(server, extra)  # no lock here (Windows, other PC)
    fd = forge_ram.take_gpu(lock)
    if fd is None:
        raise OSError("the shared GPU lock was taken meanwhile")
    try:
        return list_devices(server, extra)
    finally:
        os.close(fd)


def list_devices(server: str, extra: list[str] | None = None) -> list:
    done = subprocess.run(
        [server, "--list-devices"] + (extra or []),
        capture_output=True,
        text=True,
        timeout=LIST_TIMEOUT,
        **QUIET,
        env=build_env(server),
    )
    return parse_list_devices(done.stdout + "\n" + done.stderr)


# ------------------------------------------------------------------ memory


def ram_available_mb() -> int:
    if os_name() == "windows":
        import ctypes

        class Status(ctypes.Structure):
            _fields_ = [("len", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                (n, ctypes.c_ulonglong)
                for n in ("total", "avail", "pt", "pa", "vt", "va", "ext")
            ]

        st = Status()
        st.len = ctypes.sizeof(Status)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st))  # type: ignore[attr-defined]
        return st.avail // (1024 * 1024)
    for line in _read(Path("/proc/meminfo")).splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // 1024
    return 0


def usable_mb(dev: dict, ram_free_mb: int, reg: dict | None = None) -> int:
    """How much of a device's memory a model may take (see the docstring)."""
    mem = (reg or load_registry())["memory"]
    if dev.get("backend") == "cpu":
        return max(0, ram_free_mb - mem["cpu"]["keep_ram_mb"])
    if dev.get("integrated"):
        rule = mem["integrated"]
        share = int(dev["free_mb"] * rule["share_of_free"])
        return max(0, min(share, ram_free_mb - rule["keep_ram_mb"]))
    return max(0, dev["free_mb"] - mem["discrete"]["keep_free_mb"])


# ------------------------------------------------------------------- pick


def _suits(entry: dict, gpu: dict) -> bool:
    s = entry["suits"]
    return gpu.get("vendor") in s["vendors"] and gpu.get("kind") in s["kinds"]


def pick(
    gpus: list,
    builds: list,
    want: str | None = None,
    reg: dict | None = None,
    probe=list_devices,
    busy=gpu_busy_by_other,
    need_rpc: bool = False,
) -> dict:
    """The backend and device to use here. Returns backend, build (one of
    builds), devices (llama.cpp's list for it), device (the one picked, or
    None for the CPU) and notes: one plain line per thing that could not be
    used and why."""
    reg = reg or load_registry()
    notes: list[str] = []
    order = [b for b in reg["backends"] if b["id"] != "cpu"]
    if want:
        order = [b for b in order if b["id"] == want]
        if not order and want != "cpu":
            notes.append(f"{want} is not a backend this knows")
    holder = busy()
    if holder:
        names = ", ".join(g["name"] for g in gpus) or "the GPU"
        notes.append(
            f"{names}: in use by {holder['name']} (the shared GPU lock), so it is"
            " not touched; the CPU is used until it is free"
        )
        order = []
    if not gpus and want != "cpu":
        notes.append("no graphics device found: the CPU is used")
    tried_vulkan_fallback = False
    for entry in order if want != "cpu" else []:
        fits = [g for g in gpus if _suits(entry, g)]
        if not fits:
            if entry["id"] == want and gpus:
                names = ", ".join(g["name"] for g in gpus)
                notes.append(f"{entry['name']} does not suit {names}: the CPU is used")
            continue
        if not entry["prefer_over_vulkan"] and entry["id"] != want:
            continue
        cand = [
            b
            for b in builds
            if b["backend"] == entry["id"] and (b["rpc_server"] or not need_rpc)
        ]
        if not cand:
            if entry["id"] != "vulkan" or not tried_vulkan_fallback:
                notes.append(
                    f"{fits[0]['name']}: no {entry['name']} build"
                    f"{' with an RPC server' if need_rpc else ''} here (get one with"
                    f" forge_gpu.py fetch --backend {entry['id']})"
                )
            continue
        for b in cand:
            try:
                devices = probe(b["server"])
            except (OSError, subprocess.TimeoutExpired) as exc:
                notes.append(f"{entry['name']} build would not run: {exc}")
                continue
            mine = [
                d for d in devices if d["backend"] == entry["device_prefix"].lower()
            ]
            if mine:
                for d in mine:
                    d.setdefault("integrated", fits[0]["kind"] == "integrated")
                return {
                    "backend": entry["id"],
                    "name": entry["name"],
                    "build": b,
                    "devices": mine,
                    "device": mine[0],
                    "notes": notes,
                }
            notes.append(
                f"{fits[0]['name']}: the {entry['name']} driver gives llama.cpp no"
                " device (is the driver installed?)"
            )
            break
        if entry["id"] == "vulkan":
            tried_vulkan_fallback = True
    cpu = [b for b in builds if b["rpc_server"] or not need_rpc]
    cpu.sort(key=lambda b: b["backend"] != "cpu")
    return {
        "backend": "cpu",
        "name": "CPU",
        "build": cpu[0] if cpu else None,
        "devices": [],
        "device": None,
        "notes": notes
        + ([] if cpu else ["no llama.cpp build here: forge_gpu.py fetch"]),
    }


def detect(want: str | None = None, need_rpc: bool = False, probe=list_devices) -> dict:
    """Everything above for this computer, as one JSON-ready dict. probe:
    probe_under_lock for a caller that does not hold the iGPU lock."""
    reg = load_registry()
    gpus = system_gpus(reg=reg)
    builds = find_builds(reg)
    # Settings' pick (ai_backend) when the caller names none: kept when it
    # serves here, else the automatic one with a note saying so
    asked = setting() if want is None else "auto"
    if asked != "auto":
        want = wanted(reg, gpus, asked)
    chosen = pick(gpus, builds, want, reg, probe=probe, need_rpc=need_rpc)
    if asked != "auto" and chosen["backend"] != asked:
        why = "does not suit the graphics here"
        if want is not None:  # it suits, but its build or driver failed now
            why = "could not start just now"
            notes = chosen["notes"]
            chosen = pick(gpus, builds, None, reg, probe=probe, need_rpc=need_rpc)
            chosen["notes"] = notes + chosen["notes"]
        names = {b["id"]: b["name"] for b in reg["backends"]}
        chosen["notes"].append(
            f"{names.get(asked, asked)} is set in Forge settings but {why};"
            f" using {chosen['name']}"
        )
    ram = ram_available_mb()
    dev = chosen["device"]
    if dev:
        dev["usable_mb"] = usable_mb(dev, ram, reg)
        dev["kind"] = "integrated" if dev.get("integrated") else "discrete"
    return {
        "os": os_name(),
        "gpus": gpus,
        "backend": chosen["backend"],
        "backend_name": chosen["name"],
        "build": chosen["build"],
        "device": dev,
        "devices": chosen["devices"],
        "cpu_usable_mb": usable_mb({"backend": "cpu"}, ram, reg),
        "ram_free_mb": ram,
        "notes": chosen["notes"],
    }


def summary(info: dict) -> str:
    """One plain line: device, backend and usable memory."""
    dev = info.get("device")
    if not dev:
        return f"CPU only ({info.get('cpu_usable_mb', 0) / 1024:.1f} GB RAM usable)"
    kind = "shared memory" if dev.get("integrated") else "its own memory"
    return (
        f"{dev['name']} with {info['backend_name']},"
        f" {dev.get('usable_mb', 0) / 1024:.1f} GB usable ({kind})"
    )


# -------------------------------------------------------------------- GGUF

GGUF_SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


def gguf_info(path: str | Path) -> dict:
    """architecture and block_count (layers) from a GGUF file's header,
    plus its size in MB."""
    path = Path(path)
    want: dict = {}
    with path.open("rb") as f:
        if f.read(4) != b"GGUF":
            raise ValueError(f"{path.name} is not a GGUF file")
        version, _tensors, count = struct.unpack("<IQQ", f.read(20))
        if version < 2:
            raise ValueError("GGUF version 1 is not supported")

        def string() -> str:
            (n,) = struct.unpack("<Q", f.read(8))
            return f.read(n).decode("utf-8", "replace")

        def skip(kind: int) -> object:
            if kind == 8:
                return string()
            if kind == 9:
                inner, n = struct.unpack("<IQ", f.read(12))
                if inner in GGUF_SIZES:
                    f.seek(GGUF_SIZES[inner] * n, 1)
                else:
                    for _ in range(n):
                        skip(inner)
                return None
            raw = f.read(GGUF_SIZES[kind])
            fmt = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?"}
            fmt |= {10: "Q", 11: "q", 12: "d"}
            return struct.unpack("<" + fmt[kind], raw)[0]

        for _ in range(count):
            key = string()
            (kind,) = struct.unpack("<I", f.read(4))
            value = skip(kind)
            if key == "general.architecture" or key.endswith(".block_count"):
                want[key] = value
    arch = want.get("general.architecture")
    layers = want.get(f"{arch}.block_count")
    if not isinstance(layers, int):
        raise ValueError(f"{path.name}: no layer count in its header")
    return {
        "architecture": arch,
        "layers": layers,
        "size_mb": path.stat().st_size // (1024 * 1024),
    }


# ------------------------------------------------------------------- split


def plan_split(
    model: dict, sides: list[dict], cpu_room_mb: int, reg: dict | None = None
) -> dict:
    """Layers per device, in proportion to each one's usable memory and
    never more than fit; what no device takes stays on this machine's CPU
    (out of cpu_room_mb). sides: [{"id", "name", "usable_mb"}] in the order
    llama-server lists them (this machine's devices, then RPC ones).
    model: gguf_info(). Returns layers per side, cpu_layers, fits, and the
    llama-server arguments."""
    over = (reg or load_registry())["memory"]["overhead"]
    n = int(model.get("layers") or 0)
    if n <= 0:
        raise ValueError("the model file gives no layer count")
    sides = [{**s, "usable_mb": max(0, int(s.get("usable_mb") or 0))} for s in sides]
    cpu_room_mb = max(0, cpu_room_mb)
    per_layer = (model["size_mb"] * (1 + over["fraction"]) + over["fixed_mb"]) / n
    cap = [min(n, int(s["usable_mb"] // per_layer)) for s in sides]
    total = sum(s["usable_mb"] for s in sides)
    take = min(n, sum(cap))
    want = [take * s["usable_mb"] / total if total else 0 for s in sides]
    layers = [min(c, int(w)) for c, w in zip(cap, want, strict=True)]
    # hand out what rounding left, largest remainder first, within each cap
    while sum(layers) < take:
        room = [i for i in range(len(sides)) if layers[i] < cap[i]]
        best = max(room, key=lambda i: (want[i] - layers[i], -i))
        layers[best] += 1
    cpu_layers = n - sum(layers)
    cpu_mb = int(cpu_layers * per_layer)
    fits = cpu_mb <= cpu_room_mb
    rows = [
        {**s, "layers": k, "mb": int(k * per_layer)}
        for s, k in zip(sides, layers, strict=True)
    ]
    used = [r for r in rows if r["layers"]]
    if used:
        args = ["--device", ",".join(r["id"] for r in used)]
        args += ["-ngl", str(sum(layers))]
        if len(used) > 1:
            args += ["--tensor-split", ",".join(str(r["layers"]) for r in used)]
    else:
        args = ["--device", "none", "-ngl", "0"]
    why = ""
    if not fits:
        why = (
            f"the model needs about {int(n * per_layer)} MB; the devices take"
            f" {int(sum(layers) * per_layer)} MB and this computer's RAM has room"
            f" for {cpu_room_mb} MB of the rest"
        )
    return {
        "layers": n,
        "per_layer_mb": round(per_layer, 1),
        "sides": rows,
        "cpu_layers": cpu_layers,
        "cpu_mb": cpu_mb,
        "fits": fits,
        "why": why,
        "args": args,
    }


# -------------------------------------------------------- PyTorch CUDA tuning


def configure_torch_cuda(
    torch_mod: object | None = None, *, fp16_accum: bool | None = None
) -> dict:
    """Turn on Ampere+ Tensor Core speedups in PyTorch when a CUDA GPU is
    present (Relay task 898).

    Consumer GeForce Ampere (sm_86, such as the RTX 3060) and Ada (sm_89)
    cards run FP16 matrix math at half speed when accumulating in FP32
    (cuBLAS's default, CUBLAS_COMPUTE_32F: ~27 TFLOPS on an RTX 3060 12 GB),
    and at full speed when accumulating in FP16 (CUBLAS_COMPUTE_16F: ~51
    TFLOPS). PyTorch 2.7+ exposes that switch as
    `torch.backends.cuda.matmul.allow_fp16_accumulation`. Setting it alongside
    TF32 and FP16 reduced-precision reductions gives the ~1.9x GEMM speedup in
    pure software without kernel or UEFI register patches.

    Pass `fp16_accum=False` or set `FORGE_TORCH_FP16_ACCUM=0` to keep FP32
    accumulation on a workload that overflows FP16 sums.
    """
    if torch_mod is None:
        try:
            import importlib

            torch_mod = importlib.import_module("torch")
        except ImportError:
            return {"available": False, "reason": "no torch"}
    cuda = getattr(torch_mod, "cuda", None)
    if cuda is None or not callable(getattr(cuda, "is_available", None)):
        return {"available": False, "reason": "no cuda"}
    try:
        if not cuda.is_available():
            return {"available": False, "reason": "cuda not available"}
        cur = getattr(cuda, "current_device", None)
        raw_dev = cur() if callable(cur) else 0
        dev = raw_dev if isinstance(raw_dev, int) else 0
        major, minor = cuda.get_device_capability(dev)
        name = str(cuda.get_device_name(dev))
    except Exception as exc:  # noqa: BLE001  # driver probe is optional
        return {"available": False, "reason": str(exc)}
    out: dict = {
        "available": True,
        "device_name": name,
        "capability": [int(major), int(minor)],
        "tf32": False,
        "fp16_reduced_precision": False,
        "fp16_accumulation": False,
    }
    backends = getattr(torch_mod, "backends", None)
    matmul = getattr(getattr(backends, "cuda", None), "matmul", None)
    cudnn = getattr(backends, "cudnn", None)
    if major >= 8:
        if matmul is not None:
            if hasattr(matmul, "allow_tf32"):
                matmul.allow_tf32 = True
                out["tf32"] = True
            if hasattr(matmul, "allow_fp16_reduced_precision_reduction"):
                matmul.allow_fp16_reduced_precision_reduction = True
                out["fp16_reduced_precision"] = True
        if cudnn is not None and hasattr(cudnn, "allow_tf32"):
            cudnn.allow_tf32 = True
    env = os.environ.get("FORGE_TORCH_FP16_ACCUM", "").strip().lower()
    if fp16_accum is not None:
        want_accum = bool(fp16_accum)
    elif env in ("0", "false", "no", "off"):
        want_accum = False
    elif env in ("1", "true", "yes", "on"):
        want_accum = True
    else:
        # Consumer GeForce Ampere (sm_86) and Ada (sm_89) cap FP32-accumulate
        # HMMA at half rate; datacentre sm_80 (A100) and sm_90 (H100) do not.
        want_accum = (major, minor) in ((8, 6), (8, 9))
    if matmul is not None and hasattr(matmul, "allow_fp16_accumulation"):
        matmul.allow_fp16_accumulation = want_accum
        out["fp16_accumulation"] = bool(matmul.allow_fp16_accumulation)
    return out


# --------------------------------------------------------------------- CLI


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("detect", help="the GPU here and the backend picked")
    d.add_argument("--json", action="store_true")
    d.add_argument("--backend")
    d.add_argument("--rpc", action="store_true", help="a build with an RPC server")
    f = sub.add_parser("fetch", help="download the official llama.cpp build")
    f.add_argument("--backend", help="default: the one detect would pick")
    f.add_argument("--build")
    p = sub.add_parser("plan", help="how a model's layers would split")
    p.add_argument("model")
    p.add_argument("--peer", type=int, nargs="*", default=[], help="peer usable MB")
    a = ap.parse_args(argv)
    if a.cmd == "fetch":
        print("unpacked into", fetch(a.backend or best_backend(), a.build))
        return 0
    info = detect(a.backend if a.cmd == "detect" else None, getattr(a, "rpc", False))
    if a.cmd == "plan":
        model = gguf_info(a.model)
        sides = []
        if info["device"]:
            sides.append(
                {
                    "id": info["device"]["id"],
                    "name": info["device"]["name"],
                    "usable_mb": info["device"]["usable_mb"],
                }
            )
        sides += [
            {"id": f"RPC{i}", "name": f"peer {i + 1}", "usable_mb": mb}
            for i, mb in enumerate(a.peer)
        ]
        room = info["cpu_usable_mb"] - sum(
            s["usable_mb"]
            for s in sides[:1]
            if info["device"] and info["device"].get("integrated")
        )
        print(json.dumps(plan_split(model, sides, max(0, room)), indent=2))
        return 0
    if a.json:
        print(json.dumps(info, indent=2))
        return 0
    print(
        "graphics:",
        ", ".join(f"{g['name']} ({g['kind']})" for g in info["gpus"]) or "none",
    )
    print("use:", summary(info))
    if info["build"]:
        print(
            "build:",
            info["build"]["build"],
            info["build"]["source"],
            info["build"]["server"],
        )
    for n in info["notes"]:
        print("note:", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())

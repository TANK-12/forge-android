"""A direct cable between this laptop and a second computer, for the local
models (Relay tasks 493 and 524): Thunderbolt/USB4 networking when both ends
have it, otherwise a plain ethernet cable (or a USB ethernet adapter).

Two ways to use the second PC:

* By role (the Local crew, the quick way): the other PC serves a whole
  model and only prompts and replies cross the cable, a few kilobytes a
  step. Gigabit ethernet is plenty.
* By layer (one model too big for either PC alone): llama.cpp's RPC server
  on the other PC holds some of the model's layers and this PC the rest,
  each sized by its own free memory (shared/forge_gpu.py). Every token
  crosses the cable, so Thunderbolt's speed helps here.

Any GPU works: forge_gpu picks CUDA on NVIDIA, Vulkan on AMD, Intel and
integrated graphics, the CPU as the last resort (llama_backends.json).

Private by design: every connection goes through forge_tunnel's lock (TLS
1.3 with a pairing key only Emerson's PCs hold, and only from loopback or
the cable addresses); the model servers themselves listen on 127.0.0.1
only. No prompt or reply is logged by any of this.

The addresses (one small private network per kind of cable):

    Thunderbolt/USB4   laptop 10.77.0.1   desktop 10.77.0.2   (thunderbolt0)
    ethernet           laptop 10.77.1.1   desktop 10.77.1.2   (wired port)

The ethernet profile also asks for DHCP, so the same port still works when
plugged into a router; the fixed address just sits beside the router's.

Commands (Linux and Windows unless marked; stdlib only):

    forge_link.py status                  what this machine has, what is up,
                                          whether the other end answers
    forge_link.py setup --side laptop     (Linux) load thunderbolt_net now
    forge_link.py setup --side desktop    and at boot, add the cable profiles
    forge_link.py pair [--key HEX]        make the pairing key (or store the
                                          one the other PC made)
    forge_link.py serve MODEL.gguf        serve a whole model to the link
    forge_link.py rpc                     lend this PC's GPU (or CPU) to a
                                          layer split
    forge_link.py split MODEL --peer IP   run a model split over this PC and
                                          the peers' rpc servers, served on
                                          127.0.0.1:18090 for the Local crew
    forge_link.py info --peer IP          what the other PC has and serves
    forge_link.py peer add NAME IP        let one more device on the link by
    forge_link.py peer remove NAME        its own address (a phone on
    forge_link.py peer list               Tailscale or the home Wi-Fi; it
                                          still needs the pairing key)
    --dry-run on setup prints the commands instead of running them.

The other PC needs forge_link.py, forge_gpu.py, forge_tunnel.py and
llama_backends.json from ~/Forge/shared (one folder), Python 3.13 or newer,
and the same llama.cpp build (forge_gpu.py fetch).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import forge_gpu  # noqa: E402
import forge_tunnel  # noqa: E402

LINKS = {
    # kind: (NetworkManager profile, interface match, laptop ip, desktop ip)
    "thunderbolt": ("forge-link-tb", "thunderbolt0", "10.77.0.1", "10.77.0.2"),
    "ethernet": ("forge-link-eth", "", "10.77.1.1", "10.77.1.2"),
}
PREFIX = 24
PORT = 1337  # Jan's port; the desktop serves on it too
INFO_PORT = 1339  # what this PC has and serves (forge_tunnel.peer_info)
RPC_PORT = 50052  # llama.cpp's own RPC port, here the locked gate in front
SERVE_INNER = 11337  # llama-server behind the gate, 127.0.0.1 only
RPC_INNER = 50152  # ggml-rpc-server behind the gate, 127.0.0.1 only
SPLIT_PORT = 18090  # a split model's API on this PC, 127.0.0.1 only
SPLIT_STATE = "forge-link-split.json"  # in the runtime dir while a split runs
CONTEXT = 8192
MODULES_FILE = Path("/etc/modules-load.d/thunderbolt-net.conf")
SYS_NET = Path("/sys/class/net")
SYS_TB = Path("/sys/bus/thunderbolt/devices")


def run(cmd: list[str], dry: bool = False, check: bool = True) -> str:
    if dry:
        print("would run:", " ".join(cmd))
        return ""
    done = subprocess.run(cmd, capture_output=True, text=True)
    if check and done.returncode != 0:
        raise SystemExit(f"{' '.join(cmd[:3])} failed: {done.stderr.strip()[:300]}")
    return done.stdout


def thunderbolt_hosts(root: Path = SYS_TB) -> list[dict]:
    """This machine's Thunderbolt/USB4 host routers: generation 3 is
    Thunderbolt 3, 4 is Thunderbolt 4/USB4."""
    out = []
    try:
        entries = sorted(root.iterdir())
    except OSError:
        return out
    for d in entries:
        if not d.name.endswith("-0") or ":" in d.name:
            continue  # a host router is N-0; N-0:x.y are its ports
        gen = _read(d / "generation")
        out.append(
            {
                "router": d.name,
                "generation": gen,
                "name": " ".join(
                    x for x in (_read(d / "vendor_name"), _read(d / "device_name")) if x
                ),
            }
        )
    return out


def _read(p: Path) -> str:
    try:
        return p.read_text().strip()
    except OSError:
        return ""


def module_loaded(name: str = "thunderbolt_net") -> bool:
    return Path("/sys/module", name).exists()


def module_available(name: str = "thunderbolt_net") -> bool:
    try:
        found = subprocess.run(["modinfo", "-n", name], capture_output=True, text=True)
    except OSError:
        return False  # not Linux
    return found.returncode == 0


def wired_ports(root: Path = SYS_NET) -> list[str]:
    """Ethernet ports, built in or USB, not Wi-Fi, bridges or Thunderbolt."""
    out = []
    try:
        names = sorted(p.name for p in root.iterdir())
    except OSError:
        return out
    for name in names:
        dev = root / name
        if name == "lo" or name.startswith("thunderbolt"):
            continue
        if (dev / "wireless").exists() or (dev / "bridge").exists():
            continue
        if not (dev / "device").exists() or _read(dev / "type") != "1":
            continue  # virtual, or not ethernet framing
        out.append(name)
    return out


def interfaces(root: Path = SYS_NET) -> list[dict]:
    """The cable interfaces with carrier and addresses."""
    names = wired_ports(root)
    try:
        names += sorted(
            p.name for p in root.iterdir() if p.name.startswith("thunderbolt")
        )
    except OSError:
        pass
    addrs = _addresses()
    return [
        {
            "name": n,
            "kind": "thunderbolt" if n.startswith("thunderbolt") else "ethernet",
            "carrier": _read(root / n / "carrier") == "1",
            "speed_mbit": _read(root / n / "speed"),
            "addresses": addrs.get(n, []),
        }
        for n in names
    ]


def _addresses() -> dict[str, list[str]]:
    try:
        raw = subprocess.run(["ip", "-j", "-4", "addr"], capture_output=True, text=True)
        table = json.loads(raw.stdout or "[]")
    except (OSError, ValueError):
        return {}
    return {
        i.get("ifname", ""): [
            f"{a['local']}/{a['prefixlen']}" for a in i.get("addr_info", [])
        ]
        for i in table
    }


def answers(ip: str, port: int = PORT, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def profiles() -> set[str]:
    if not shutil.which("nmcli"):
        return set()
    out = run(["nmcli", "-t", "-f", "NAME", "con", "show"], check=False)
    return {line.strip() for line in out.splitlines() if line.strip()}


def status(side: str) -> dict:
    other = "desktop" if side == "laptop" else "laptop"
    have = profiles()
    peers = {}
    for kind, (prof, _, lap, desk) in LINKS.items():
        ip = desk if other == "desktop" else lap
        peers[kind] = {"profile": prof, "set_up": prof in have, "other_end": ip}
        peers[kind]["answers"] = answers(ip, timeout=0.6)
    return {
        "thunderbolt_hosts": thunderbolt_hosts(),
        "thunderbolt_net": {
            "available": module_available(),
            "loaded": module_loaded(),
            "at_boot": MODULES_FILE.exists(),
        },
        "interfaces": interfaces(),
        "links": peers,
    }


def setup(side: str, dry: bool = False) -> None:
    """Load thunderbolt_net now and at boot, and add the two profiles."""
    if not shutil.which("nmcli"):
        raise SystemExit("NetworkManager (nmcli) is needed; see the guide for others")
    if module_available():
        if not MODULES_FILE.exists():
            write = f"echo thunderbolt_net | sudo tee {MODULES_FILE}"
            run(["sh", "-c", write + " >/dev/null"], dry)
        if not module_loaded():
            run(["sudo", "modprobe", "thunderbolt_net"], dry)
    else:
        print("thunderbolt_net is not in this kernel: only the ethernet link is set up")
    have = profiles()
    idx = 2 if side == "laptop" else 3
    prof, ifname, *_ = LINKS["thunderbolt"]
    ip = LINKS["thunderbolt"][idx]
    if prof not in have:
        run(
            ["nmcli", "con", "add", "type", "ethernet", "con-name", prof]
            + ["ifname", ifname, "ipv4.method", "manual"]
            + ["ipv4.addresses", f"{ip}/{PREFIX}", "ipv4.never-default", "yes"]
            + ["ipv6.method", "link-local", "connection.autoconnect", "yes"],
            dry,
        )
    prof = LINKS["ethernet"][0]
    ip = LINKS["ethernet"][idx]
    ports = wired_ports()
    if prof not in have and ports:
        # DHCP when a router is there, the fixed address on a bare cable
        run(
            ["nmcli", "con", "add", "type", "ethernet", "con-name", prof]
            + ["ifname", ports[0], "ipv4.method", "auto"]
            + ["ipv4.addresses", f"{ip}/{PREFIX}", "ipv4.may-fail", "yes"]
            + ["ipv4.dhcp-timeout", "infinity", "ipv6.method", "auto"]
            + ["connection.autoconnect", "yes"]
            # below his own wired profile (0): on a port that already has
            # one, his keeps winning and this one is the fallback
            + ["connection.autoconnect-priority", "-10"],
            dry,
        )
    elif not ports:
        print("No ethernet port found: plug in a USB ethernet adapter and run again")
    print(
        "Done. Plug the cable in; 'forge_link.py status' shows when the other"
        " end answers."
    )


def link_hosts() -> list[str]:
    """This PC's cable addresses that are up (either side's)."""
    want = [ip for (_, _, lap, desk) in LINKS.values() for ip in (lap, desk)]
    mine = {a.split("/")[0] for i in interfaces() for a in i["addresses"]}
    if not mine:  # Windows, or no `ip`: what the system resolves for us
        try:
            mine = {
                str(x[4][0])
                for x in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
            }
        except OSError:
            mine = set()
    return [ip for ip in want if ip in mine]


def _bind_host(host: str | None) -> str:
    if host:
        return host
    found = link_hosts()
    if not found:
        raise SystemExit(
            "No link address is up yet: run setup (or give the port its"
            " address, see the guide) and plug the cable in, or pass --host"
        )
    return found[0]


def pair(key: str | None = None, new: bool = False) -> str:
    """Store the given pairing key, or make one; returns it."""
    path = forge_tunnel.key_file()
    if key:
        forge_tunnel.save_key(key)
        return key.strip().lower()
    if path.exists() and not new:
        return forge_tunnel.load_key().hex()
    made = forge_tunnel.make_key()
    forge_tunnel.save_key(made)
    return made


class _Lock:
    """The shared iGPU lock (forge_ram) while this PC's iGPU holds a model;
    nothing on a PC without forge_ram (the other PC, Windows)."""

    def __init__(self) -> None:
        self.fd: int | None = None

    def take(self) -> bool:
        if os.environ.get("FORGE_GPU_LOCK_HELD") == "1":
            return True  # forge_ram.py run --gpu took it for this job
        try:
            import forge_ram

            lock = forge_ram.gpu_lock_path(forge_ram.load_registry())
        except (ImportError, OSError, ValueError):
            return True
        self.fd = forge_ram.take_gpu(lock)
        return self.fd is not None

    def release(self) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


def model_path(name: str) -> str:
    """A .gguf path as given, or a Jan model's folder name (Qwen3_5-4B-IQ4_XS)."""
    if Path(name).is_file():
        return name
    try:
        import forge_jan

        jan = Path(forge_jan.JAN_MODELS) / name / "model.gguf"
    except (ImportError, AttributeError):
        jan = None
    if jan is not None and jan.is_file():
        return str(jan)
    raise SystemExit(
        f"{name}: no such model file (give a .gguf path or a Jan model name)"
    )


def _room(need_mb: int) -> str:
    """forge_ram's model_load rule, where forge_ram is (this laptop)."""
    try:
        import forge_ram
    except ImportError:
        return ""
    return forge_ram.headroom("model_load", need_mb)


def local_device(backend: str | None, need_rpc: bool = False) -> tuple[dict, _Lock]:
    """forge_gpu.detect with the iGPU lock taken first, so the probe and
    the model that follows run under it; a busy iGPU means the CPU."""
    lock = _Lock()
    got = lock.take() if backend != "cpu" else False
    info = forge_gpu.detect(backend if got or backend == "cpu" else "cpu", need_rpc)
    if not got and backend != "cpu":
        info["notes"].insert(0, "the shared GPU lock is taken: the CPU is used")
    dev = info["device"]
    if not dev or not dev.get("integrated"):
        lock.release()  # only an iGPU shares the laptop's GPU lock
    return info, lock


def _flags(server: str) -> str:
    try:
        return subprocess.run(
            [server, "--help"],
            capture_output=True,
            text=True,
            timeout=20,
            env=forge_gpu.build_env(server),
        ).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""


def _server_args(server: str) -> list[str]:
    """Quiet, private defaults: no web page, no slot listing (it shows the
    prompts being worked on), our split kept as planned."""
    have = _flags(server)
    out = ["-c", str(CONTEXT), "-fa", "on"]
    out += ["--no-webui"] if "--no-webui" in have else []
    out += ["--no-slots"] if "--no-slots" in have else []
    out += ["--fit", "off"] if "--fit " in have else []
    return out


def _sides(info: dict) -> list[dict]:
    dev = info["device"]
    if not dev:
        return []
    return [{"id": dev["id"], "name": dev["name"], "usable_mb": dev["usable_mb"]}]


def _cpu_room(info: dict, plan_sides: list[dict]) -> int:
    """The RAM left for CPU layers: an iGPU's share comes out of it too."""
    room = info["cpu_usable_mb"]
    dev = info["device"]
    if dev and dev.get("integrated"):
        room -= dev.get("usable_mb", 0)
    return max(0, room)


def describe(info: dict, serving: dict | None = None) -> dict:
    """What InfoGate answers: this PC's device, backend and what it serves."""
    dev = info.get("device") or {}
    return {
        "host": socket.gethostname(),
        "os": info.get("os"),
        "backend": info.get("backend"),
        "backend_name": info.get("backend_name"),
        "device": {
            k: dev.get(k)
            for k in ("id", "name", "integrated", "free_mb", "usable_mb")
            if k in dev
        }
        or None,
        "cpu_usable_mb": info.get("cpu_usable_mb"),
        "summary": forge_gpu.summary(info),
        "notes": info.get("notes", []),
        "build": (info.get("build") or {}).get("build"),
        "serving": serving or {},
    }


def _state_path() -> Path:
    import tempfile

    return (
        Path(os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir()) / SPLIT_STATE
    )


def split_state(path: Path | None = None) -> dict | None:
    """The split running on this PC (model, layers per side), or None. Only
    the plan is kept there, never a prompt."""
    path = path or _state_path()
    try:
        state = json.loads(path.read_text())
        os.kill(int(state["pid"]), 0)
    except (OSError, ValueError, KeyError, TypeError):
        return None
    return state if isinstance(state, dict) else None


def _run_child(cmd: list[str], server: str, gates: list, lock: _Lock) -> int:
    """Run the model server until it ends (or Ctrl-C / SIGTERM), then close
    the gates. Its output goes to this terminal only, never to a file. The
    server inherits the GPU lock, so it stays held while the server runs
    even if this process is killed."""

    def stop(*_):
        raise KeyboardInterrupt  # SIGTERM too ends the server and the gates

    signal.signal(signal.SIGTERM, stop)
    keep = (lock.fd,) if lock.fd is not None else ()
    proc = subprocess.Popen(cmd, env=forge_gpu.build_env(server), pass_fds=keep)
    try:
        return proc.wait()
    except KeyboardInterrupt:
        return 130
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(20)
            except subprocess.TimeoutExpired:
                proc.kill()
        for g in gates:
            g.close()


def _plan_or_exit(model: str, sides: list[dict], room: int) -> dict:
    plan = forge_gpu.plan_split(forge_gpu.gguf_info(model), sides, room)
    if not plan["fits"]:
        raise SystemExit("This model does not fit: " + plan["why"])
    return plan


def _show(plan: dict) -> None:
    for s in plan["sides"]:
        print(f"  {s['name']} ({s['id']}): {s['layers']} layers, about {s['mb']} MB")
    if plan["cpu_layers"]:
        print(
            f"  this PC's CPU: {plan['cpu_layers']} layers, about {plan['cpu_mb']} MB"
        )


def serve(
    model: str,
    port: int = PORT,
    backend: str | None = None,
    host: str | None = None,
    dry: bool = False,
) -> int:
    """Serve one whole model to the link (run on the other PC): llama-server
    on 127.0.0.1 behind the locked gate on the cable address."""
    host = _bind_host(host)
    key = forge_tunnel.load_key()
    info, lock = local_device(backend)
    build = info["build"]
    if not build:
        raise SystemExit("No llama.cpp build here: python3 forge_gpu.py fetch")
    plan = _plan_or_exit(model, _sides(info), _cpu_room(info, _sides(info)))
    why = _room(sum(s["mb"] for s in plan["sides"]) + plan["cpu_mb"])
    if why:
        raise SystemExit(f"Not now: {why}")
    exe = build["server"]
    cmd = [exe, "-m", model, "--host", "127.0.0.1", "--port", str(SERVE_INNER)]
    cmd += plan["args"] + _server_args(exe)
    print("device:", forge_gpu.summary(info))
    _show(plan)
    for n in info["notes"]:
        print("note:", n)
    print(f"serving on https://{host}:{port}/v1 (locked; pairing key only)")
    if dry:
        print("would run:", " ".join(cmd))
        lock.release()
        return 0
    serving = {"kind": "model", "model": Path(model).name, "port": port}
    gates = [
        forge_tunnel.Gate(host, port, key, target=("127.0.0.1", SERVE_INNER)).start(),
        forge_tunnel.Gate(
            host,
            INFO_PORT,
            key,
            handler=forge_tunnel.info_handler(lambda: describe(info, serving)),
        ).start(),
    ]
    try:
        return _run_child(cmd, exe, gates, lock)
    finally:
        lock.release()


def rpc(
    backend: str | None = None,
    host: str | None = None,
    port: int = RPC_PORT,
    threads: int | None = None,
    dry: bool = False,
    max_mb: int | None = None,
) -> int:
    """Lend this PC's GPU (or CPU) to a layer split: ggml-rpc-server on
    127.0.0.1 behind the locked gate. The other side asks INFO_PORT how
    much memory it may use (this PC's own rule, forge_gpu.usable_mb)."""
    host = _bind_host(host)
    key = forge_tunnel.load_key()
    info, lock = local_device(backend, need_rpc=True)
    build = info["build"]
    if not build or not build.get("rpc_server"):
        raise SystemExit(
            "No llama.cpp build with an RPC server here: python3 forge_gpu.py fetch"
        )
    exe = build["rpc_server"]
    dev = info["device"]
    cmd = [exe, "-H", "127.0.0.1", "-p", str(RPC_INNER)]
    cmd += ["-d", dev["id"] if dev else "CPU"]
    cmd += ["-t", str(threads or max(1, (os.cpu_count() or 2) - 2))]
    print("lending:", forge_gpu.summary(info))
    for n in info["notes"]:
        print("note:", n)
    print(f"rpc on {host}:{port} (locked; pairing key only)")
    if dry:
        print("would run:", " ".join(cmd))
        lock.release()
        return 0
    usable = dev["usable_mb"] if dev else info["cpu_usable_mb"]
    if max_mb is not None:
        usable = min(usable, max_mb)  # lend no more than he said
    serving = {"kind": "rpc", "port": port, "usable_mb": usable}
    gates = [
        forge_tunnel.Gate(host, port, key, target=("127.0.0.1", RPC_INNER)).start(),
        forge_tunnel.Gate(
            host,
            INFO_PORT,
            key,
            handler=forge_tunnel.info_handler(lambda: describe(info, serving)),
        ).start(),
    ]
    try:
        return _run_child(cmd, exe, gates, lock)
    finally:
        lock.release()


def _peer_addr(peer: str) -> tuple[str, int, int]:
    """ "ip", "ip:rpcport" or "ip:rpcport:infoport"."""
    parts = peer.split(":")
    rpc_port = int(parts[1]) if len(parts) > 1 else RPC_PORT
    info_port = int(parts[2]) if len(parts) > 2 else INFO_PORT
    return parts[0], rpc_port, info_port


def split(
    model: str,
    peers: list[str],
    port: int = SPLIT_PORT,
    backend: str | None = None,
    dry: bool = False,
) -> int:
    """One model over this PC and the peers' rpc servers: layers in
    proportion to each side's usable memory, the rest on this PC's CPU.
    Served on 127.0.0.1 only (the Local crew's "split" host)."""
    key = forge_tunnel.load_key()
    remote = []
    for peer in peers:
        ip, rport, iport = _peer_addr(peer)
        try:
            about = forge_tunnel.peer_info(ip, iport, key)
        except (OSError, ValueError) as exc:
            raise SystemExit(f"{ip} does not answer on the locked link: {exc}")
        got = about.get("serving") or {}
        if got.get("kind") != "rpc":
            raise SystemExit(
                f"{ip} is not lending its GPU: run forge_link.py rpc there"
            )
        remote.append((ip, rport, about, got))
    info, lock = local_device(backend, need_rpc=True)  # --rpc needs the RPC library
    build = info["build"]
    if not build:
        raise SystemExit("No llama.cpp build here: python3 forge_gpu.py fetch")
    sides = _sides(info) + [
        {
            "id": f"RPC{i}",
            "name": f"{about.get('host') or ip} ({about.get('summary', '')})",
            "usable_mb": int(got.get("usable_mb") or 0),
        }
        for i, (ip, _, about, got) in enumerate(remote)
    ]
    plan = _plan_or_exit(model, sides, _cpu_room(info, sides))
    local_mb = sum(s["mb"] for s in plan["sides"][: len(_sides(info))])
    why = _room(local_mb + plan["cpu_mb"])
    if why:
        lock.release()
        raise SystemExit(f"Not now: {why}")
    print("this PC:", forge_gpu.summary(info))
    _show(plan)
    tunnels = [
        forge_tunnel.Tunnel((ip, rport), key).start() for ip, rport, *_ in remote
    ]
    exe = build["server"]
    cmd = [exe, "-m", model, "--host", "127.0.0.1", "--port", str(port)]
    cmd += ["--rpc", ",".join(f"127.0.0.1:{t.port}" for t in tunnels)]
    cmd += plan["args"] + _server_args(exe)
    print(f"serving the split on http://127.0.0.1:{port}/v1")
    if dry:
        print("would run:", " ".join(cmd))
        for t in tunnels:
            t.close()
        lock.release()
        return 0
    state = _state_path()
    state.write_text(
        json.dumps(
            {
                "pid": os.getpid(),
                "model": Path(model).stem,
                "port": port,
                "backend": info["backend"],
                "sides": [
                    {"name": r["name"], "layers": r["layers"]} for r in plan["sides"]
                ],
                "cpu_layers": plan["cpu_layers"],
            }
        )
    )
    try:
        return _run_child(cmd, exe, tunnels, lock)
    finally:
        state.unlink(missing_ok=True)
        lock.release()


def peer(action: str, name: str | None = None, ip: str | None = None) -> int:
    """Add, remove or list the named peers forge_tunnel lets through (each
    one address only, and the pairing key is still needed)."""
    peers = forge_tunnel.load_peers()
    if action == "add":
        if not name or not ip:
            raise SystemExit("peer add needs a name and an address")
        try:
            peers[name] = forge_tunnel.peer_ip(ip)
        except ValueError as exc:
            raise SystemExit(str(exc))
        forge_tunnel.save_peers(peers)
        print(f"{name} ({peers[name]}) may reach this machine's link ports")
    elif action == "remove":
        if peers.pop(name or "", None) is None:
            raise SystemExit(f"no peer called {name}")
        forge_tunnel.save_peers(peers)
        print(f"{name} removed")
    else:
        for n, addr in sorted(peers.items()):
            print(f"{n}  {addr}")
        if not peers:
            print("no named peers (only the cable and this PC)")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    st = sub.add_parser("status", help="what this machine has and what is up")
    st.add_argument("--side", choices=("laptop", "desktop"), default="laptop")
    st.add_argument("--json", action="store_true")
    su = sub.add_parser("setup", help="module at boot and the two cable profiles")
    su.add_argument("--side", choices=("laptop", "desktop"), required=True)
    su.add_argument("--dry-run", action="store_true")
    pa = sub.add_parser("pair", help="make or store the pairing key")
    pa.add_argument("--key", help="the key the other PC printed")
    pa.add_argument("--new", action="store_true", help="replace the key here")
    sv = sub.add_parser("serve", help="serve a whole model to the link")
    sv.add_argument("model")
    sv.add_argument("--port", type=int, default=PORT)
    rp = sub.add_parser("rpc", help="lend this PC's GPU to a layer split")
    rp.add_argument("--port", type=int, default=RPC_PORT)
    rp.add_argument("--threads", type=int)
    rp.add_argument("--max-mb", type=int, help="lend at most this much memory")
    sp = sub.add_parser("split", help="run a model split with the peers")
    sp.add_argument("model")
    sp.add_argument("--peer", action="append", required=True, help="ip[:rpc[:info]]")
    sp.add_argument("--port", type=int, default=SPLIT_PORT)
    inf = sub.add_parser("info", help="what the other PC has and serves")
    inf.add_argument("--peer", required=True, help="ip[:infoport]")
    pe = sub.add_parser("peer", help="name a device (a phone) allowed on the link")
    pe.add_argument("action", choices=("add", "remove", "list"))
    pe.add_argument("name", nargs="?")
    pe.add_argument("ip", nargs="?", help="its Tailscale or home-network address")
    for p in (sv, rp, sp):
        p.add_argument("--backend", help="cuda, vulkan, rocm, sycl or cpu")
        p.add_argument("--dry-run", action="store_true")
    for p in (sv, rp):
        p.add_argument("--host", help="address to listen on (default: the cable)")
    a = ap.parse_args(argv)
    if a.cmd == "setup":
        setup(a.side, a.dry_run)
    elif a.cmd == "pair":
        made = pair(a.key, a.new)
        print(f"pairing key stored in {forge_tunnel.key_file()}")
        if not a.key:
            print("On the other PC run:  python3 forge_link.py pair --key " + made)
    elif a.cmd == "serve":
        return serve(model_path(a.model), a.port, a.backend, a.host, a.dry_run)
    elif a.cmd == "rpc":
        return rpc(a.backend, a.host, a.port, a.threads, a.dry_run, a.max_mb)
    elif a.cmd == "split":
        return split(model_path(a.model), a.peer, a.port, a.backend, a.dry_run)
    elif a.cmd == "peer":
        return peer(a.action, a.name, a.ip)
    elif a.cmd == "info":
        ip, *rest = a.peer.split(":")
        about = forge_tunnel.peer_info(
            ip, int(rest[0]) if rest else INFO_PORT, forge_tunnel.load_key()
        )
        print(json.dumps(about, indent=2))
    else:
        info = status(a.side)
        if a.json:
            print(json.dumps(info, indent=2))
            return 0
        tb = info["thunderbolt_hosts"]
        print(
            "Thunderbolt/USB4:",
            ", ".join(
                f"{h['name'] or h['router']} (generation {h['generation']})" for h in tb
            )
            or "none",
        )
        net = info["thunderbolt_net"]
        print(
            "thunderbolt_net driver:",
            "in the kernel" if net["available"] else "missing",
            "/ loaded" if net["loaded"] else "/ not loaded",
            "/ loads at boot" if net["at_boot"] else "/ not set to load at boot",
        )
        for i in info["interfaces"]:
            plug = "cable in" if i["carrier"] else "no cable"
            print(
                f"  {i['name']} ({i['kind']}): {plug}, {', '.join(i['addresses']) or 'no address'}"
            )
        for kind, p in info["links"].items():
            print(
                f"{kind} link: profile {'set up' if p['set_up'] else 'missing'},"
                f" other end {p['other_end']} {'answers' if p['answers'] else 'does not answer'}"
                f" on port {PORT}"
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""The locked, encrypted link between Emerson's own computers (Relay task
524). Everything that crosses the cable for the local models goes through
here: prompts and replies to a model served on the other PC, and the
llama.cpp RPC traffic of a model split layer by layer.

How it is locked. Both computers hold the same random 256-bit pairing key
(made once with `forge_link.py pair`, copied to the other PC by hand, kept
in a file only the user can read). Every connection is TLS 1.3 with that
key as a pre-shared key: a machine without the key cannot connect or read
anything, and the traffic is encrypted (ChaCha20-Poly1305 or AES-GCM, with
a fresh key exchange per connection). No certificates, no accounts, no
outside server, nothing public: the gates also refuse any address outside
loopback, the cable subnets and the peers named one by one in peers.json
(a phone on Tailscale or the home Wi-Fi, task 541) before the handshake. Only standard library
(ssl's PSK callbacks need Python 3.13 or newer on both ends).

What is logged: nothing about the content. A gate counts connections and
writes one line to stderr when it refuses one (the address and why).

    Gate(listen, target)      accept locked connections, pass them on to a
                              server that only listens on 127.0.0.1
    Tunnel(listen, peer)      a local port that leads through the lock to a
                              gate on the other PC
    opener(key)               urllib opener for https://<peer> URLs through
                              a gate (the Local crew's requests)
    InfoGate / peer_info()    a gate that answers what the PC has (its GPU,
                              backend, what it serves) as JSON
"""

from __future__ import annotations

import ipaddress
import json
import os
import secrets
import selectors
import socket
import ssl
import subprocess
import sys
import threading
import urllib.request
from pathlib import Path

IDENTITY = "forge-link"
KEY_BYTES = 32
HANDSHAKE_TIMEOUT = 10.0
CHUNK = 1 << 16
BUFFER_MAX = 4 << 20  # stop reading a side while this much waits to go out
IDLE_CHECK = 30.0
ALLOWED = ("127.0.0.0/8", "::1/128", "10.77.0.0/24", "10.77.1.0/24")
# Where a named peer (a phone) may be: Tailscale's ranges or a home network.
PEER_RANGES = (
    "100.64.0.0/10",
    "fd7a:115c:a1e0::/48",
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "fc00::/7",
)
INFO_ASK = b"FORGE-INFO 1\n"
INFO_MAX = 1 << 20


def key_file() -> Path:
    if os.environ.get("FORGE_LINK_KEY"):
        return Path(os.environ["FORGE_LINK_KEY"])
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA") or Path.home())
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "forge" / "link" / "link.key"


def peers_file() -> Path:
    """Named peers beside the key: {"phone": "100.101.102.103"}."""
    return key_file().parent / "peers.json"


def peer_ip(ip: str) -> str:
    """One address inside PEER_RANGES, normalised; ValueError otherwise."""
    addr = ipaddress.ip_address(ip.strip())
    if not any(addr in ipaddress.ip_network(n) for n in PEER_RANGES):
        raise ValueError(f"{ip} is not a Tailscale or home-network address")
    return str(addr)


def load_peers(path: Path | None = None) -> dict[str, str]:
    """The named peers; a broken file or entry counts as no peer."""
    try:
        data = json.loads((path or peers_file()).read_text())
    except (OSError, ValueError):
        return {}
    out = {}
    for name, ip in data.items() if isinstance(data, dict) else ():
        try:
            out[str(name)] = peer_ip(str(ip))
        except ValueError:
            continue
    return out


def save_peers(peers: dict[str, str], path: Path | None = None) -> Path:
    path = path or peers_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(peers, indent=2) + "\n")
    os.replace(tmp, path)
    return path


def link_nets() -> tuple[str, ...]:
    """ALLOWED plus each named peer as a single address."""
    return ALLOWED + tuple(
        f"{ip}/{32 if ':' not in ip else 128}" for ip in load_peers().values()
    )


def make_key() -> str:
    return secrets.token_hex(KEY_BYTES)


def save_key(hexkey: str, path: Path | None = None) -> Path:
    """Write the pairing key readable by this user only."""
    hexkey = hexkey.strip().lower()
    if len(hexkey) != KEY_BYTES * 2 or any(c not in "0123456789abcdef" for c in hexkey):
        raise ValueError(f"a pairing key is {KEY_BYTES * 2} hex digits")
    path = path or key_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass
    tmp = path.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)  # a leftover could carry looser permissions
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(hexkey + "\n")
    os.replace(tmp, path)
    if sys.platform.startswith("win"):
        _owner_only_windows(path)
    return path


def _owner_only_windows(path: Path) -> None:
    """Windows has no 0600: drop inherited access and leave this user only."""
    user = os.environ.get("USERNAME")
    if not user:
        return
    subprocess.run(
        ["icacls", str(path), "/inheritance:r", "/grant:r", f"{user}:F"],
        capture_output=True,
        check=False,
    )


def load_key(path: Path | None = None) -> bytes:
    path = path or key_file()
    try:
        raw = path.read_text().strip()
    except OSError:
        raise SystemExit(
            f"No pairing key at {path}: run forge_link.py pair on one PC and"
            " forge_link.py pair --key <the key> on the other"
        )
    if not sys.platform.startswith("win") and path.stat().st_mode & 0o077:
        raise SystemExit(f"{path} is readable by others: chmod 600 it")
    try:
        key = bytes.fromhex(raw)
    except ValueError:
        key = b""
    if len(key) != KEY_BYTES:
        raise SystemExit(
            f"{path} is not a pairing key ({KEY_BYTES * 2} hex digits):"
            " run forge_link.py pair again"
        )
    return key


def _need_psk() -> None:
    if not getattr(ssl, "HAS_PSK", False) or not hasattr(
        ssl.SSLContext, "set_psk_client_callback"
    ):
        raise SystemExit("The locked link needs Python 3.13 or newer with OpenSSL PSK")


def server_context(key: bytes) -> ssl.SSLContext:
    _need_psk()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3

    def find(identity: str | None) -> bytes:
        # an empty key makes the handshake fail: wrong name, no way in
        return key if identity == IDENTITY else b""

    ctx.set_psk_server_callback(find, IDENTITY)
    return ctx


def client_context(key: bytes) -> ssl.SSLContext:
    _need_psk()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    # The key, not a certificate, proves who it is. CERT_REQUIRED with no CA
    # loaded refuses every certificate handshake, so a server without the key
    # (an impostor on the cable with a self-signed certificate) never gets in.
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.set_psk_client_callback(lambda hint: (IDENTITY, key))
    return ctx


def allowed(addr: str, nets: tuple[str, ...] | None = None) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return any(ip in ipaddress.ip_network(n) for n in nets or link_nets())


def join(a: socket.socket, b: socket.socket) -> None:
    """Copy both ways until either side closes, then close both. One thread
    and non-blocking sockets: an SSL socket must not be read in one thread
    while another writes to it (Python 3.14 serialises the two, so a
    blocked read would stall every write: a deadlock for request/reply
    traffic like llama.cpp's RPC)."""
    out: dict[socket.socket, bytearray] = {a: bytearray(), b: bytearray()}
    other = {a: b, b: a}
    ended = False
    for s in (a, b):
        s.setblocking(False)
        try:
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
    sel = selectors.DefaultSelector()
    try:
        while True:
            for s in (a, b):  # read what fits, write what waits
                want = 0
                if not ended and len(out[other[s]]) < BUFFER_MAX:
                    want |= selectors.EVENT_READ
                if out[s]:
                    want |= selectors.EVENT_WRITE
                try:
                    sel.modify(s, want) if want else sel.unregister(s)
                except KeyError:
                    if want:
                        sel.register(s, want)
            if ended and not out[a] and not out[b]:
                return
            if not sel.get_map():
                return
            ready = sel.select(timeout=IDLE_CHECK)
            for key, mask in ready:
                s = key.fileobj
                assert isinstance(s, socket.socket)
                if mask & selectors.EVENT_READ:
                    ended = _drain(s, out[other[s]]) or ended
                if mask & selectors.EVENT_WRITE and out[s]:
                    try:
                        n = s.send(out[s])
                        del out[s][:n]
                    except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                        pass
                    except BlockingIOError:
                        pass
    except OSError:
        return
    finally:
        sel.close()
        a.close()
        b.close()


def _drain(s: socket.socket, into: bytearray) -> bool:
    """Read all s has now (an SSL socket may hold decrypted bytes the
    selector cannot see); True at end of stream."""
    while True:
        try:
            data = s.recv(CHUNK)
        except (ssl.SSLWantReadError, ssl.SSLWantWriteError, BlockingIOError):
            return False
        if not data:
            return True
        into += data
        if isinstance(s, ssl.SSLSocket) and s.pending():
            continue
        if len(into) >= BUFFER_MAX:
            return False


class _Listener:
    def __init__(self, host: str, port: int):
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        self.sock = socket.create_server((host, port), family=family, backlog=32)
        self.host, self.port = self.sock.getsockname()[:2]
        self.accepted = 0
        self.refused = 0
        self._stop = threading.Event()

    def start(self) -> _Listener:
        threading.Thread(target=self._loop, daemon=True).start()
        return self

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                conn, addr = self.sock.accept()
            except OSError:
                break
            threading.Thread(target=self._one, args=(conn, addr), daemon=True).start()

    def _one(self, conn: socket.socket, addr) -> None:
        raise NotImplementedError

    def close(self) -> None:
        self._stop.set()
        self.sock.close()


class Gate(_Listener):
    """Takes locked connections on (host, port) and hands each one to
    target (a server on this machine) or, with handler, answers it here."""

    def __init__(self, host, port, key, target=None, handler=None, nets=None):
        super().__init__(host, port)
        self.ctx = server_context(key)
        self.target, self.handler, self.nets = target, handler, nets

    def _refuse(self, conn: socket.socket, addr, why: str) -> None:
        self.refused += 1
        print(f"forge link: refused {addr[0]}: {why}", file=sys.stderr, flush=True)
        conn.close()

    def _one(self, conn: socket.socket, addr) -> None:
        if not allowed(addr[0], self.nets):
            return self._refuse(conn, addr, "not one of the link addresses")
        conn.settimeout(HANDSHAKE_TIMEOUT)
        try:
            tls = self.ctx.wrap_socket(conn, server_side=True)
        except (OSError, ssl.SSLError):
            return self._refuse(conn, addr, "no pairing key or the wrong one")
        tls.settimeout(None)
        self.accepted += 1
        if self.handler:
            try:
                self.handler(tls)
            finally:
                tls.close()
            return
        if self.target is None:
            tls.close()
            return
        try:
            inner = socket.create_connection(self.target, timeout=HANDSHAKE_TIMEOUT)
            inner.settimeout(None)
        except OSError:
            tls.close()
            return
        join(tls, inner)


class Tunnel(_Listener):
    """A port on 127.0.0.1 that leads to a Gate on the peer."""

    def __init__(self, peer: tuple[str, int], key: bytes, port: int = 0):
        super().__init__("127.0.0.1", port)
        self.peer = peer
        self.ctx = client_context(key)

    def _one(self, conn: socket.socket, addr) -> None:
        if not allowed(self.peer[0]):
            conn.close()
            return
        try:
            raw = socket.create_connection(self.peer, timeout=HANDSHAKE_TIMEOUT)
            tls = self.ctx.wrap_socket(raw)
            tls.settimeout(None)
        except (OSError, ssl.SSLError):
            conn.close()
            return
        join(conn, tls)


def connect(host: str, port: int, key: bytes, timeout: float = 5.0) -> ssl.SSLSocket:
    if not allowed(host):
        raise OSError(f"{host} is not on the cable link, a named peer or this PC")
    raw = socket.create_connection((host, port), timeout=timeout)
    try:
        return client_context(key).wrap_socket(raw)
    except BaseException:
        raw.close()
        raise


def info_handler(get_info):
    """A Gate handler answering INFO_ASK with get_info() as JSON."""

    def handle(tls: ssl.SSLSocket) -> None:
        tls.settimeout(HANDSHAKE_TIMEOUT)
        if tls.recv(len(INFO_ASK)) != INFO_ASK:
            return
        tls.sendall(json.dumps(get_info()).encode() + b"\n")

    return handle


def peer_info(host: str, port: int, key: bytes, timeout: float = 2.0) -> dict:
    with connect(host, port, key, timeout) as s:
        s.settimeout(timeout)
        s.sendall(INFO_ASK)
        buf = b""
        while not buf.endswith(b"\n") and len(buf) < INFO_MAX:
            chunk = s.recv(CHUNK)
            if not chunk:
                break
            buf += chunk
    data = json.loads(buf.decode() or "{}")
    return data if isinstance(data, dict) else {}


def opener(key: bytes) -> urllib.request.OpenerDirector:
    """urllib for https:// URLs that end at a Gate (no proxy: the request
    must go straight down the cable)."""
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=client_context(key)),
    )

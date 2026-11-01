"""Put the phone APK on GitHub: the source (this folder, without build/),
termux/ (lend.sh plus the link files it fetches) and a release with
build/forge.apk. Run build.sh first.

    phone/.venv/bin/python phone/android/publish.py v0.1 "release notes"

Stops before anything leaves the laptop when a staged file holds a private
string: a tailnet name, a home path, a private key, an API key, this
laptop's host name or the hub's saved address.
"""

from __future__ import annotations

import argparse
import re
import shutil
import socket
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PHONE = HERE.parent
FORGE = PHONE.parent
sys.path.insert(0, str(PHONE))
import build_offline  # noqa: E402

REPO = "TANK-12/forge-android"
ABOUT = "Forge for Android: a phone app that pairs with Forge on your computer over home Wi-Fi"
LINK_FILES = ["forge_link.py", "forge_tunnel.py", "forge_gpu.py", "llama_backends.json"]
PRIVATE = build_offline.PRIVATE[1:] + [re.compile(r"tail[0-9a-f]{6}\.ts\.net", re.I)]


def stage(into: Path) -> None:
    shutil.copytree(
        HERE, into, ignore=shutil.ignore_patterns("build", "__pycache__", ".*")
    )
    (into / "termux").mkdir()
    shutil.copy2(PHONE / "termux" / "lend.sh", into / "termux" / "lend.sh")
    for name in LINK_FILES:
        shutil.copy2(FORGE / "shared" / name, into / "termux" / name)


def private_hits(root: Path) -> list[str]:
    words = [socket.gethostname(), build_offline.laptop_host()]
    patterns = PRIVATE + [re.compile(re.escape(w), re.I) for w in words if w]
    hits = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix in {".png", ".jks"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern in patterns:
            found = pattern.search(text)
            if found:
                hits.append(f"{path.relative_to(root)}: {found.group(0)[:40]}")
    return hits


def run(*cmd: str, cwd: Path | None = None) -> str:
    return subprocess.run(
        cmd, cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("tag")
    ap.add_argument("notes")
    ap.add_argument("--dry-run", action="store_true", help="stage and scan only")
    a = ap.parse_args()
    apk = HERE / "build" / "forge.apk"
    if not apk.is_file():
        raise SystemExit("No build/forge.apk: run build.sh first")
    with tempfile.TemporaryDirectory(prefix="forge-android-") as tmp:
        work = Path(tmp) / "repo"
        stage(work)
        hits = private_hits(work)
        if hits:
            raise SystemExit("Private text, nothing published:\n  " + "\n  ".join(hits))
        print(
            "staged",
            sum(1 for p in work.rglob("*") if p.is_file()),
            "files, scan clean",
        )
        if a.dry_run:
            return 0
        if subprocess.run(["gh", "repo", "view", REPO], capture_output=True).returncode:
            run("gh", "repo", "create", REPO, "--public", "--description", ABOUT)
        clone = Path(tmp) / "clone"
        run("gh", "repo", "clone", REPO, str(clone))
        for old in clone.iterdir():
            if old.name != ".git":
                shutil.rmtree(old) if old.is_dir() else old.unlink()
        shutil.copytree(work, clone, dirs_exist_ok=True)
        run("git", "add", "-A", cwd=clone)
        if run("git", "status", "--porcelain", cwd=clone):
            run("git", "commit", "-q", "-m", f"Forge for Android {a.tag}", cwd=clone)
            run("git", "branch", "-M", "main", cwd=clone)
            run("git", "push", "-q", "-u", "origin", "main", cwd=clone)
        if subprocess.run(
            ["gh", "release", "view", a.tag, "-R", REPO], capture_output=True
        ).returncode:
            run(
                "gh",
                "release",
                "create",
                a.tag,
                str(apk),
                "-R",
                REPO,
                "--title",
                f"Forge for Android {a.tag}",
                "--notes",
                a.notes,
            )
        else:
            run("gh", "release", "upload", a.tag, str(apk), "-R", REPO, "--clobber")
    print(f"https://github.com/{REPO}/releases/download/{a.tag}/forge.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())

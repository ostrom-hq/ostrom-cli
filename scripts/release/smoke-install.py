#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Installs a packed release on the machine it runs on, through its own installer, before anything is published.

Usage: smoke-install.py --dist DIR --version V [--dry-run-cosign-pub FILE]

DIR is the release's `dist/` (archives, SHA256SUMS and its signature, latest.json and its signature, install.sh,
install.ps1), served on 127.0.0.1 as the releases are (`download/<V>/...`, `latest/download/latest.json`). The installer
of this system (install.sh, or install.ps1 on Windows) runs as a user would, with a private HOME, into a managed
installation (OSTROM_INSTALL_ROOT, OSTROM_NO_MODIFY_PATH=1: the runner's PATH is left alone).

On a tag, the release is signed with the keys the installers and the binaries pin, so it must install: the launcher
runs the version, `current.json` selects it, a second run of the installer installs it again, and a payload changed
after its installation is refused by the launcher. On a dry run (--dry-run-cosign-pub: the throwaway cosign key that
signed SHA256SUMS, put in place of the pinned one), the installer's own checks pass, and the binary it hands over to
must refuse the manifest, which the throwaway Ed25519 key signed and no binary trusts: nothing is selected.
"""
import argparse
import functools
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

WINDOWS = os.name == "nt"
# the installers and the binary print ✓ / ✗: read and write UTF-8 whatever the console's code page (Windows)
TEXT = {"text": True, "encoding": "utf-8", "errors": "replace"}
PINNED = re.compile(r"-----BEGIN PUBLIC KEY-----.*?-----END PUBLIC KEY-----", re.S)


def fail(message):
    raise SystemExit(f"smoke-install: {message}")


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def serve(site):
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=str(site)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def installer(dist, work, cosign_pub):
    """The installer of this system from the release, with the throwaway cosign key of a dry run in place."""
    name = "install.ps1" if WINDOWS else "install.sh"
    text = (dist / name).read_text(encoding="utf-8")
    if cosign_pub is not None:
        key = cosign_pub.read_text(encoding="utf-8").strip()
        text, count = PINNED.subn(lambda _: key, text, count=1)
        if count != 1:
            fail(f"no pinned key found in {name}")
    path = work / name
    path.write_text(text, encoding="utf-8")
    return path


def run_installer(script, environment):
    if WINDOWS:
        shell = shutil.which("pwsh") or shutil.which("powershell")
        command = [shell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)]
    else:
        command = ["sh", str(script)]
    result = subprocess.run(command, env=environment, capture_output=True, timeout=600, **TEXT)
    output = result.stdout + result.stderr
    print(output)
    return result.returncode, output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--dry-run-cosign-pub", type=Path)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    dist = args.dist.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="ostrom-smoke-") as directory:
        work = Path(directory)
        site = work / "site"
        shutil.copytree(dist, site / "download" / args.version)
        shutil.copytree(dist, site / "latest" / "download")
        home = work / "home"
        home.mkdir()
        root = work / "root"
        server = serve(site)
        environment = {
            **os.environ,
            "HOME": str(home),
            "USERPROFILE": str(home),
            "LOCALAPPDATA": str(home / "AppData" / "Local"),
            "APPDATA": str(home / "AppData" / "Roaming"),
            "XDG_DATA_HOME": str(home / ".local" / "share"),
            "XDG_CONFIG_HOME": str(home / ".config"),
            "OSTROM_DOWNLOAD_BASE": f"http://127.0.0.1:{server.server_address[1]}",
            "OSTROM_VERSION": args.version,
            "OSTROM_INSTALL_ROOT": str(root),
            "OSTROM_NO_MODIFY_PATH": "1",
            "OSTROM_DISABLE_AUTOUPDATE": "1",
        }
        environment.pop("OSTROM_INSTALL_DIR", None)
        try:
            script = installer(dist, work, args.dry_run_cosign_pub)
            code, output = run_installer(script, environment)
            if args.dry_run_cosign_pub is not None:
                dry_run(code, output, root)
            else:
                release(code, script, environment, root, args.version)
        finally:
            server.shutdown()
    print("smoke-install: ok")


def dry_run(code, output, root):
    if code == 0:
        fail("a manifest signed with a throwaway key was installed")
    if "Signature verified" not in output:
        fail("the installer did not get as far as its own signature check")
    if "release signature does not match" not in output:
        fail("the binary handed over to did not refuse the manifest's signature")
    if (root / "current.json").exists():
        fail("something was selected")


def release(code, script, environment, root, version):
    if code != 0:
        fail(f"the installer failed (exit {code})")
    launcher = root / "bin" / ("ostrom.exe" if WINDOWS else "ostrom")
    current = json.loads((root / "current.json").read_text(encoding="utf-8"))["current"]
    if current["version"] != version:
        fail(f"current.json selects {current['version']}, not {version}")
    shown = subprocess.run([str(launcher), "--version"], env=environment, capture_output=True, timeout=60, **TEXT)
    if shown.returncode != 0 or not re.match(rf"ostrom {re.escape(version)} ", shown.stdout):
        fail(f"the launcher did not run {version}: {shown.stdout}{shown.stderr}")
    code, _ = run_installer(script, environment)
    if code != 0:
        fail(f"a second run of the installer failed (exit {code})")
    payload = root / "versions" / current["manifestSha256"] / launcher.name
    os.chmod(payload, 0o700)
    with open(payload, "ab") as binary:
        binary.write(b"\0")
    refused = subprocess.run([str(launcher), "--version"], env=environment, capture_output=True, timeout=60, **TEXT)
    if refused.returncode == 0 or "release_binary_invalid" not in refused.stderr:
        fail(f"a changed payload was run: {refused.stdout}{refused.stderr}")


if __name__ == "__main__":
    sys.exit(main())

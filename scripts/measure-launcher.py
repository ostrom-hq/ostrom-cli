#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Measures `ostrom hook gate` through the launcher of a managed installation (`ostrom install`), against the bare
binary.

Usage: measure-launcher.py --binary PATH --target TARGET --label LABEL [--runs N] [--max-hook-ms MS]
                           [--max-overhead-ms MS]

The hooks and MCP servers of an agent name the stable launcher (`<root>/bin/ostrom`), which checks the selected
payload against its attestation and runs it in its place: that check is on the path of every tool call. The script
builds the layout `ostrom install` writes around --binary in a temporary root (the launcher and one payload, both
copies of --binary, their attestation, the selection), then:

1. runs the launcher once: it checks the payload in full and must leave `launch-cache.json`;
2. measures the hook through the launcher (median and p95 of --runs warm starts) and on the bare binary, and exits 1
   when the launcher's median is above --max-hook-ms or its overhead (launcher median minus bare median) above
   --max-overhead-ms;
3. rewrites the payload with one byte changed, its size kept: the launcher must refuse it (`release_binary_invalid`),
   so the cache never runs a payload that changed since its full check.

The layout is written by hand because a CI build is not signed by a release key that `ostrom install` trusts; it is
the layout `InstallationStore` reads (cli/update/InstallationStore.kt). Results go to stdout and, in GitHub Actions,
to the job summary.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("measure", os.path.join(HERE, "measure.py"))
measure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measure)


def private_directory(path):
    os.makedirs(path, mode=0o700, exist_ok=True)
    os.chmod(path, 0o700)


def private_file(path, data, mode=0o600):
    with open(path, "wb") as handle:
        handle.write(data)
    os.chmod(path, mode)


def layout(binary, root, target, version):
    """Writes a managed installation of [binary] under [root]; returns the launcher and the payload's paths."""
    name = "ostrom.exe" if target.startswith("windows-") else "ostrom"
    manifest = json.dumps({"version": version, "assets": {}}).encode()
    manifest_sha256 = hashlib.sha256(manifest).hexdigest()
    with open(binary, "rb") as handle:
        data = handle.read()
    attested = {
        "version": version, "target": target, "manifestSha256": manifest_sha256,
        "binarySha256": hashlib.sha256(data).hexdigest(), "size": len(data),
    }
    payload_directory = os.path.join(root, "versions", manifest_sha256)
    for folder in (root, os.path.join(root, "bin"), os.path.join(root, "versions"), payload_directory):
        private_directory(folder)
    launcher, payload = os.path.join(root, "bin", name), os.path.join(payload_directory, name)
    private_file(launcher, data, 0o700)
    private_file(os.path.join(root, "bin", "launcher.json"), json.dumps(attested).encode())
    private_file(payload, data, 0o700)
    private_file(os.path.join(payload_directory, "latest.json"), manifest)
    private_file(os.path.join(payload_directory, "installed.json"), json.dumps(attested).encode())
    state = {"schemaVersion": 1, "current": attested, "previous": None}
    private_file(os.path.join(root, "current.json"), json.dumps(state).encode())
    return launcher, payload


def version_of(binary):
    result = subprocess.run([binary, "--version"], capture_output=True)
    measure.checked([binary, "--version"], result.returncode, result.stderr, (0,))
    return result.stdout.decode().split()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--target", required=True, help="release target of --binary (linux-x64, macos-arm64…)")
    parser.add_argument("--label", required=True)
    parser.add_argument("--runs", type=int, default=100)
    parser.add_argument("--max-hook-ms", type=float, help="the hook through the launcher (median)")
    parser.add_argument("--max-overhead-ms", type=float, help="the launcher's median minus the bare binary's")
    args = parser.parse_args()
    binary = os.path.abspath(args.binary)
    env = dict(os.environ, OSTROM_HOOK_TIMING="1")
    hook = ["hook", "gate"]
    failed = []
    with tempfile.TemporaryDirectory(prefix="ostrom-launcher-") as work:
        root = os.path.join(os.path.realpath(work), "ostrom")
        launcher, payload = layout(binary, root, args.target, version_of(binary))
        first = subprocess.run([launcher, *hook], input=measure.EVENT, capture_output=True, env=env)
        measure.checked([launcher, *hook], first.returncode, first.stderr, (0, 2))
        if not os.path.isfile(os.path.join(root, "launch-cache.json")):
            failed.append("the launcher checked the payload in full but wrote no launch-cache.json")
        through, _ = measure.run_many([launcher, *hook], args.runs, stdin=measure.EVENT, env=env, accepted=(0, 2))
        bare, _ = measure.run_many([binary, *hook], args.runs, stdin=measure.EVENT, env=env, accepted=(0, 2))
        with open(payload, "r+b") as handle:
            byte = handle.read(1)
            handle.seek(0)
            handle.write(bytes([byte[0] ^ 1]))
        changed = subprocess.run([launcher, *hook], input=measure.EVENT, capture_output=True, env=env)
        if changed.returncode != 1 or b"release_binary_invalid" not in changed.stderr:
            failed.append(f"a changed payload was not refused: exit {changed.returncode}, "
                          f"{changed.stderr.decode(errors='replace').strip()}")
    through_median, through_p95 = measure.describe(through)
    bare_median, bare_p95 = measure.describe(bare)
    overhead = through_median - bare_median
    lines = [
        f"### Launcher of a managed installation, {args.label}",
        "",
        "| ostrom hook gate | median | p95 |",
        "|---|---|---|",
        f"| through the launcher | {through_median:.2f} ms | {through_p95:.2f} ms |",
        f"| bare binary | {bare_median:.2f} ms | {bare_p95:.2f} ms |",
        f"| launcher overhead | {overhead:.2f} ms | |",
    ]
    print("\n".join(lines))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as handle:
            handle.write("\n".join(lines) + "\n\n")
    if args.max_hook_ms is not None and through_median > args.max_hook_ms:
        failed.append(f"ostrom hook gate through the launcher takes {through_median:.2f} ms (median), "
                      f"above {args.max_hook_ms} ms")
    if args.max_overhead_ms is not None and overhead > args.max_overhead_ms:
        failed.append(f"the launcher adds {overhead:.2f} ms (median), above {args.max_overhead_ms} ms")
    if failed:
        raise SystemExit("\n".join(failed))


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Measures a built `ostrom` binary: start-up of `ostrom hook gate` and `ostrom version`, and binary sizes.

Usage: measure.py --binary PATH --label TARGET [--runs N] [--max-hook-ms MS] [--max-push-ms MS] [--sizes FILE...]
                  [--max-size-mb MB] [--max-hook-p95-ms MS] [--max-version-p95-ms MS] [--cold-runs N]
                  [--max-cold-ms MS] [--max-hook-memory-mb MB]

Besides the warm starts (100 runs, median and p95), it measures a first start of the day: the system's file cache
emptied before each of --cold-runs starts (`drop_caches` on Linux, `purge` on macOS, through `sudo -n`; skipped where
it cannot), and the hook's peak resident memory (`wait4`; skipped on Windows).

The wall time includes Python's own process spawn (well under a millisecond on Linux, more on Windows), so the
figures are an upper bound of the binary's start-up. `inside` is the time the hook reports itself
(OSTROM_HOOK_TIMING=1: from entering the hook runner to the reply written). Results go to stdout and, in GitHub
Actions, to the job summary. Exits 1 when the median hook time is above --max-hook-ms, the median `git push` time
above --max-push-ms when given, or a file of --sizes above --max-size-mb when given (the binary a developer downloads:
15 MB, perf pass of 02/11/2026).

The budget is measured on the hook of an ordinary tool call (`ls`), which is most of an agent's calls. Outside a
session, as here, there is no relay to read Ostrom's signed policy from, so the gate holds the call (exit 2) after
reading and parsing it: the figure is the hook's start-up. The same hook in a real session (the relay asked, the
policy's signature checked) is held to the same budget by e2e/run.py. In a session a `git push` runs `git` to read the
branch it sends, so it has a budget of its own, --max-push-ms (40 ms, held on the Linux runner only, decision of
30/10/2026 08:10). Outside a session, as here, the gate holds the push before running `git`: the figure is a ceiling
check of that path, not the cost of `git` in a large repository.
"""
import argparse
import os
import re
import statistics
import subprocess
import sys
import time

def event(command):
    return (
        '{"hook_event_name":"PreToolUse","session_id":"s_8f2a","cwd":"/repo","tool_name":"Bash",'
        '"tool_input":{"command":"%s","description":"A step"}}' % command
    ).encode()


EVENT = event("ls -la")
PUSH = event("git push origin main")


def run_many(command, runs, stdin=None, env=None, accepted=(0,)):
    walls, insides = [], []
    for index in range(runs + 5):
        start = time.perf_counter()
        result = subprocess.run(command, input=stdin, capture_output=True, env=env)
        wall = (time.perf_counter() - start) * 1000
        if result.returncode not in accepted:
            raise SystemExit(f"{command} exited {result.returncode}: {result.stderr.decode(errors='replace')}")
        if index < 5:
            continue  # warm-up: page cache, first-run checks of the OS
        walls.append(wall)
        found = re.search(rb"ostrom hook \S+: ([0-9.]+) ms", result.stderr)
        if found:
            insides.append(float(found.group(1)))
    return walls, insides


def describe(values):
    """The median and the 95th percentile (nearest rank) of [values]."""
    ordered = sorted(values)
    return statistics.median(ordered), ordered[max(0, -(-len(ordered) * 95 // 100) - 1)]


def drop_file_cache():
    """Empties the system's file cache, so that the next start reads the binary from the disk: False where it cannot."""
    if sys.platform.startswith("linux"):
        command = ["sudo", "-n", "sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches"]
    elif sys.platform == "darwin":
        command = ["sudo", "-n", "purge"]
    else:
        return False
    return subprocess.run(command, capture_output=True).returncode == 0


def run_cold(command, runs, stdin=None, env=None, accepted=(0,)):
    """Wall times of [command] each started with the file cache empty (a first start of the day); [] where it cannot."""
    walls = []
    for _ in range(runs):
        if not drop_file_cache():
            return []
        start = time.perf_counter()
        result = subprocess.run(command, input=stdin, capture_output=True, env=env)
        walls.append((time.perf_counter() - start) * 1000)
        if result.returncode not in accepted:
            raise SystemExit(f"{command} exited {result.returncode}: {result.stderr.decode(errors='replace')}")
    return walls


def peak_memory_mb(command, runs, stdin=None, env=None):
    """The largest resident memory of [command] over [runs] starts, in MB; None where the system does not say."""
    if not hasattr(os, "wait4"):
        return None
    peaks = []
    for _ in range(runs):
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, env=env)
        process.stdin.write(stdin or b"")
        process.stdin.close()
        _, status, usage = os.wait4(process.pid, 0)
        process.returncode = os.waitstatus_to_exitcode(status)
        # ru_maxrss is in bytes on macOS, in kilobytes on Linux
        peaks.append(usage.ru_maxrss / (1e6 if sys.platform == "darwin" else 1e3))
    return max(peaks)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--runs", type=int, default=100)
    parser.add_argument("--max-hook-ms", type=float, default=15.0)
    parser.add_argument("--max-push-ms", type=float, default=None)
    parser.add_argument("--sizes", nargs="*", default=[])
    parser.add_argument("--max-size-mb", type=float, default=None)
    parser.add_argument("--max-hook-p95-ms", type=float, default=None)
    parser.add_argument("--max-version-p95-ms", type=float, default=None)
    parser.add_argument("--cold-runs", type=int, default=5)
    parser.add_argument("--max-cold-ms", type=float, default=None)
    parser.add_argument("--max-hook-memory-mb", type=float, default=None)
    args = parser.parse_args()

    env = dict(os.environ, OSTROM_HOOK_TIMING="1")
    hook_walls, hook_insides = run_many([args.binary, "hook", "gate"], args.runs, stdin=EVENT, env=env, accepted=(0, 2))
    push_walls, _ = run_many([args.binary, "hook", "gate"], args.runs, stdin=PUSH, env=env, accepted=(0, 2))
    push_median, push_p95 = describe(push_walls)
    version_walls, _ = run_many([args.binary, "version"], args.runs)
    hook_median, hook_p95 = describe(hook_walls)
    inside_median, _ = describe(hook_insides) if hook_insides else (float("nan"), None)
    version_median, version_p95 = describe(version_walls)
    cold_hook = run_cold([args.binary, "hook", "gate"], args.cold_runs, stdin=EVENT, env=env, accepted=(0, 2))
    cold_version = run_cold([args.binary, "version"], args.cold_runs)
    memory = peak_memory_mb([args.binary, "hook", "gate"], 20, stdin=EVENT, env=env)

    lines = [
        f"### ostrom on {args.label}",
        "",
        "| Measure | Median (ms) | p95 (ms) |",
        "| --- | --- | --- |",
        f"| `ostrom hook gate`, ordinary call (wall, {args.runs} runs) | {hook_median:.2f} | {hook_p95:.2f} |",
        f"| `ostrom hook gate`, `git push` (wall, runs `git`) | {push_median:.2f} | {push_p95:.2f} |",
        f"| `ostrom hook gate` (inside the hook) | {inside_median:.3f} | |",
        f"| `ostrom version` (wall) | {version_median:.2f} | {version_p95:.2f} |",
    ]
    for name, walls in (("ostrom hook gate", cold_hook), ("ostrom version", cold_version)):
        if walls:
            lines.append(f"| `{name}`, file cache emptied first ({len(walls)} runs, max) | "
                         f"{statistics.median(walls):.2f} | {max(walls):.2f} |")
    if memory is not None:
        lines += ["", "| Measure | Peak (MB) |", "| --- | --- |"]
        lines.append(f"| `ostrom hook gate`, resident memory (20 runs) | {memory:.2f} |")
    if args.sizes:
        lines += ["", "| Binary | Size (MB) |", "| --- | --- |"]
        for path in sorted(args.sizes):
            lines.append(f"| `{path}` | {os.path.getsize(path) / 1e6:.2f} |")
    text = "\n".join(lines) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")
    failed = []
    if hook_median > args.max_hook_ms:
        failed.append(f"ostrom hook gate takes {hook_median:.2f} ms (median), above {args.max_hook_ms} ms")
    if args.max_hook_p95_ms is not None and hook_p95 > args.max_hook_p95_ms:
        failed.append(f"ostrom hook gate takes {hook_p95:.2f} ms at p95, above {args.max_hook_p95_ms} ms")
    if args.max_version_p95_ms is not None and version_p95 > args.max_version_p95_ms:
        failed.append(f"ostrom version takes {version_p95:.2f} ms at p95, above {args.max_version_p95_ms} ms")
    slowest = max(cold_hook + cold_version, default=0.0)
    if args.max_cold_ms is not None and slowest > args.max_cold_ms:
        failed.append(f"a start with the file cache emptied takes {slowest:.2f} ms, above {args.max_cold_ms} ms")
    if args.max_hook_memory_mb is not None and memory is not None and memory > args.max_hook_memory_mb:
        failed.append(f"ostrom hook gate holds {memory:.2f} MB, above {args.max_hook_memory_mb} MB")
    too_large = [path for path in args.sizes if args.max_size_mb is not None
                 and os.path.getsize(path) / 1e6 > args.max_size_mb]
    if too_large:
        failed.append(f"{', '.join(sorted(too_large))}: above {args.max_size_mb} MB")
    if args.max_push_ms is not None and push_median > args.max_push_ms:
        failed.append(f"ostrom hook gate on a git push takes {push_median:.2f} ms (median), "
                      f"above {args.max_push_ms} ms")
    if failed:
        raise SystemExit("\n".join(failed))


if __name__ == "__main__":
    main()

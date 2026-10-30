#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Measures a built `ostrom` binary: start-up of `ostrom hook gate` and `ostrom version`, and binary sizes.

Usage: measure.py --binary PATH --label TARGET [--runs N] [--max-hook-ms MS] [--max-push-ms MS] [--sizes FILE...]

The wall time includes Python's own process spawn (well under a millisecond on Linux, more on Windows), so the
figures are an upper bound of the binary's start-up. `inside` is the time the hook reports itself
(OSTROM_HOOK_TIMING=1: from entering the hook runner to the reply written). Results go to stdout and, in GitHub
Actions, to the job summary. Exits 1 when the median hook time is above --max-hook-ms, or the median `git push` time
above --max-push-ms when given.

The budget is measured on the hook of an ordinary tool call (`ls`), which is most of an agent's calls. Outside a
session, as here, there is no relay to read Ostrom's signed policy from, so the gate holds the call (exit 2) after
reading and parsing it: the figure is the hook's start-up. The same hook in a real session (the relay asked, the
policy's signature checked) is held to the same budget by e2e/run.py. A `git push` runs `git` to read the branch it
sends, so it has a budget of its own, --max-push-ms (40 ms on the Linux runner, decision of 30/10/2026 08:10).
"""
import argparse
import os
import re
import statistics
import subprocess
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
    ordered = sorted(values)
    return statistics.median(ordered), ordered[int(len(ordered) * 0.9) - 1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--runs", type=int, default=100)
    parser.add_argument("--max-hook-ms", type=float, default=15.0)
    parser.add_argument("--max-push-ms", type=float, default=None)
    parser.add_argument("--sizes", nargs="*", default=[])
    args = parser.parse_args()

    env = dict(os.environ, OSTROM_HOOK_TIMING="1")
    hook_walls, hook_insides = run_many([args.binary, "hook", "gate"], args.runs, stdin=EVENT, env=env, accepted=(0, 2))
    push_walls, _ = run_many([args.binary, "hook", "gate"], args.runs, stdin=PUSH, env=env, accepted=(0, 2))
    push_median, push_p90 = describe(push_walls)
    version_walls, _ = run_many([args.binary, "version"], args.runs)
    hook_median, hook_p90 = describe(hook_walls)
    inside_median, _ = describe(hook_insides) if hook_insides else (float("nan"), None)
    version_median, version_p90 = describe(version_walls)

    lines = [
        f"### ostrom on {args.label}",
        "",
        "| Measure | Median (ms) | p90 (ms) |",
        "| --- | --- | --- |",
        f"| `ostrom hook gate`, ordinary call (wall, {args.runs} runs) | {hook_median:.2f} | {hook_p90:.2f} |",
        f"| `ostrom hook gate`, `git push` (wall, runs `git`) | {push_median:.2f} | {push_p90:.2f} |",
        f"| `ostrom hook gate` (inside the hook) | {inside_median:.3f} | |",
        f"| `ostrom version` (wall) | {version_median:.2f} | {version_p90:.2f} |",
    ]
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
    if hook_median > args.max_hook_ms:
        raise SystemExit(f"ostrom hook gate takes {hook_median:.2f} ms (median), above {args.max_hook_ms} ms")
    if args.max_push_ms is not None and push_median > args.max_push_ms:
        raise SystemExit(
            f"ostrom hook gate on a git push takes {push_median:.2f} ms (median), above {args.max_push_ms} ms"
        )


if __name__ == "__main__":
    main()

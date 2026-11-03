#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Measures a built `ostrom` binary: start-up of `ostrom hook gate` and `ostrom version`, and binary sizes.

Usage: measure.py --binary PATH --label TARGET [--runs N] [--max-hook-ms MS] [--max-push-ms MS] [--sizes FILE...]
                  [--max-size-mb MB] [--size-budget TARGET=MB ...] [--max-hook-p95-ms MS]
                  [--max-version-p95-ms MS] [--cold-runs N] [--max-cold-ms MS] [--max-hook-memory-mb MB]

Besides the warm starts (100 runs, median and p95), it measures a first start of the day: the system's file cache
emptied before each of --cold-runs starts (`drop_caches` on Linux, `purge` on macOS, through `sudo -n`; skipped where
it cannot), and the hook's peak resident memory (`wait4`; skipped on Windows).

The wall time includes Python's own process spawn (well under a millisecond on Linux, more on Windows), so the
figures are an upper bound of the binary's start-up. `inside` is the time the hook reports itself
(OSTROM_HOOK_TIMING=1: from entering the hook runner to the reply written). Results go to stdout and, in GitHub
Actions, to the job summary. Exits 1 when the median hook time is above --max-hook-ms, the median `git push` time
above --max-push-ms when given, or a file of --sizes above its budget: --max-size-mb (the binary a developer downloads,
15 MB, perf pass of 02/11/2026), or --size-budget for a target whose folder (`bin/<target>/`) has one of its own.
It also exits 1, when they are given, above --max-hook-p95-ms and --max-version-p95-ms (the p95 of the warm starts),
--max-cold-ms (each command's slowest start with the file cache emptied) and --max-hook-memory-mb, and when one of
these is given but cannot be measured here. It lists every budget exceeded, not only the first. On Linux the peak
memory read for a child is never below this script's own (see `peak_memory_mb`): the summary shows that floor, and
the memory budget holds as long as the floor stays under it.

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
import shutil
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
MEMORY_RUNS = 20


def checked(command, code, errors, accepted):
    """Stops the measure when [command] exited with a code outside [accepted]: a broken binary is never measured."""
    if code not in accepted:
        raise SystemExit(f"{command} exited {code}: {errors.decode(errors='replace')}")


def run_many(command, runs, stdin=None, env=None, accepted=(0,)):
    walls, insides = [], []
    for index in range(runs + 5):
        start = time.perf_counter()
        result = subprocess.run(command, input=stdin, capture_output=True, env=env)
        wall = (time.perf_counter() - start) * 1000
        checked(command, result.returncode, result.stderr, accepted)
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
    """Empties the system's file cache, so that the next start reads the binary from the disk.

    Returns None when it is done, else why it cannot be done here."""
    if sys.platform.startswith("linux"):
        command = ["sudo", "-n", "sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches"]
    elif sys.platform == "darwin":
        command = ["sudo", "-n", "purge"]
    else:
        return f"no way to empty the file cache on {sys.platform}"
    try:
        result = subprocess.run(command, capture_output=True)
    except FileNotFoundError:
        return "no `sudo` here"
    return None if result.returncode == 0 else f"`{' '.join(command[:3])}` refused"


def run_cold(command, runs, stdin=None, env=None, accepted=(0,)):
    """Wall times of [command] each started with the file cache empty (a first start of the day).

    Returns the times and None, or no time and why they cannot be measured here."""
    walls = []
    for _ in range(runs):
        reason = drop_file_cache()
        if reason:
            return [], reason
        start = time.perf_counter()
        result = subprocess.run(command, input=stdin, capture_output=True, env=env)
        walls.append((time.perf_counter() - start) * 1000)
        checked(command, result.returncode, result.stderr, accepted)
    return walls, None


def peak_memory_mb(command, runs, stdin=None, env=None, accepted=(0,)):
    """The largest resident memory of [command] over [runs] starts, in MB; None where the system does not say.

    On Linux, `exec` keeps in the child's high-water mark the memory of the process it replaces, which after the fork
    is this Python script: the figure is the larger of the two, never their sum. `harness_floor_mb` measures that floor.
    """
    if not hasattr(os, "wait4"):
        return None
    peaks = []
    for _ in range(runs):
        with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, env=env) as process:
            try:
                process.stdin.write(stdin or b"")
                process.stdin.close()
            except BrokenPipeError:
                pass  # the command exited before reading: its exit code below says why
            errors = process.stderr.read()
            _, status, usage = os.wait4(process.pid, 0)
            # reaped here: the returncode keeps Popen from waiting for it again when the block closes the pipes
            process.returncode = os.waitstatus_to_exitcode(status)
        checked(command, process.returncode, errors, accepted)
        # ru_maxrss is in bytes on macOS, in kilobytes on Linux
        peaks.append(usage.ru_maxrss / (1e6 if sys.platform == "darwin" else 1e3))
    return max(peaks)


def harness_floor_mb(runs):
    """The resident memory read for `true` started the same way: the least any command can show here, in MB."""
    true = shutil.which("true")
    return peak_memory_mb([true], runs) if true else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--runs", type=int, default=100)
    parser.add_argument("--max-hook-ms", type=float, default=15.0)
    parser.add_argument("--max-push-ms", type=float, default=None)
    parser.add_argument("--sizes", nargs="*", default=[])
    parser.add_argument("--max-size-mb", type=float, default=None)
    parser.add_argument("--size-budget", action="append", default=[], metavar="TARGET=MB")
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
    cold_hook, cold_hook_reason = run_cold([args.binary, "hook", "gate"], args.cold_runs, stdin=EVENT, env=env,
                                           accepted=(0, 2))
    cold_version, cold_version_reason = run_cold([args.binary, "version"], args.cold_runs)
    floor = harness_floor_mb(MEMORY_RUNS)  # first: the script's own memory only grows
    memory = peak_memory_mb([args.binary, "hook", "gate"], MEMORY_RUNS, stdin=EVENT, env=env, accepted=(0, 2))

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
    colds = (("ostrom hook gate", cold_hook, cold_hook_reason), ("ostrom version", cold_version, cold_version_reason))
    if cold_hook or cold_version:
        lines += ["", "| Measure | Median (ms) | Max (ms) |", "| --- | --- | --- |"]
    for name, walls, _ in colds:
        if walls:
            lines.append(f"| `{name}`, file cache emptied first ({len(walls)} runs) | "
                         f"{statistics.median(walls):.2f} | {max(walls):.2f} |")
    if memory is not None:
        lines += ["", "| Measure | Peak (MB) |", "| --- | --- |"]
        lines.append(f"| `ostrom hook gate`, resident memory ({MEMORY_RUNS} runs) | {memory:.2f} |")
        if floor is not None:
            lines.append(f"| `true` started the same way: the least this harness can show | {floor:.2f} |")
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
    if args.max_cold_ms is not None:
        for name, walls, reason in colds:
            if not walls:
                failed.append(f"{name} cannot be started with the file cache emptied here ({reason}), "
                              f"so --max-cold-ms {args.max_cold_ms} cannot be held")
            elif max(walls) > args.max_cold_ms:
                failed.append(f"{name} takes {max(walls):.2f} ms with the file cache emptied, "
                              f"above {args.max_cold_ms} ms")
    if args.max_hook_memory_mb is not None:
        if memory is None:
            failed.append(f"this system does not give a process's peak memory (wait4), "
                          f"so --max-hook-memory-mb {args.max_hook_memory_mb} cannot be held")
        elif floor is None:
            failed.append(f"`true` is not on the PATH, so the floor of this harness is unknown and "
                          f"--max-hook-memory-mb {args.max_hook_memory_mb} cannot be held")
        elif floor >= args.max_hook_memory_mb:
            failed.append(f"`true` alone shows {floor:.2f} MB here, so --max-hook-memory-mb "
                          f"{args.max_hook_memory_mb} cannot be held")
        elif memory > args.max_hook_memory_mb:
            failed.append(f"ostrom hook gate holds {memory:.2f} MB, above {args.max_hook_memory_mb} MB")
    own = {target: float(mb) for target, mb in (budget.split("=", 1) for budget in args.size_budget)}

    def budget_of(path):
        return next((mb for target, mb in own.items() if f"/{target}/" in path), args.max_size_mb)

    failed += [f"{path}: above {budget_of(path)} MB" for path in sorted(args.sizes)
               if budget_of(path) is not None and os.path.getsize(path) / 1e6 > budget_of(path)]
    if args.max_push_ms is not None and push_median > args.max_push_ms:
        failed.append(f"ostrom hook gate on a git push takes {push_median:.2f} ms (median), "
                      f"above {args.max_push_ms} ms")
    if failed:
        raise SystemExit("\n".join(failed))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Where the ~550 ms of a first `ostrom version` on a macOS runner go (probe, not a budget).

Each figure is the median of --runs starts, the conditions alternating so that the runner's drift is shared:
  warm          the binary started again, nothing emptied
  purge         `sudo purge` (the whole file cache emptied), then the binary
  purge-true    `sudo purge`, then /usr/bin/true: what the runner pays for any program after a purge
  purge-same    `sudo purge`, then a C program linked with the same libraries as ostrom (otool -L)
  purge-read    `sudo purge`, the binary's file read once (cat), then the binary: the system's share alone
  copy-first    a fresh copy of the binary (new file, signature never seen), first start, cache warm
  copy-second   the same copy, second start
  adhoc-first   a fresh copy signed again ad hoc (`codesign -s -`), first start
  runtime-first a fresh copy signed ad hoc with the hardened runtime (as the release's Developer ID), first start
  quarantine-first / -second  a fresh ad hoc copy with the quarantine attribute a browser sets, two starts
Plus the page-ins of each purge start (/usr/bin/time -l), the libraries dyld loads (DYLD_PRINT_LIBRARIES) and the
syspolicyd / amfid lines `log stream` printed while the copies started.
"""
import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time


def timed(command, env=None):
    start = time.perf_counter()
    result = subprocess.run(command, capture_output=True, env=env)
    return (time.perf_counter() - start) * 1000, result.returncode


def purge():
    subprocess.run(["sudo", "-n", "purge"], check=True)


def page_ins(command):
    result = subprocess.run(["/usr/bin/time", "-l", *command], capture_output=True, text=True)
    found = re.search(r"(\d+)\s+page faults", result.stderr)
    return int(found.group(1)) if found else None


def same_libraries(binary, work):
    listed = subprocess.run(["otool", "-L", binary], capture_output=True, text=True, check=True).stdout
    flags = []
    for line in listed.splitlines()[1:]:
        path = line.strip().split(" (")[0]
        framework = re.search(r"/([^/]+)\.framework/", path)
        if framework:
            flags += [f"-Wl,-needed_framework,{framework.group(1)}"]
        elif path.startswith("/usr/lib/lib") and path.endswith(".dylib"):
            flags += [f"-Wl,-needed-l{os.path.basename(path)[3:-6]}"]
    source = os.path.join(work, "same.c")
    with open(source, "w") as out:
        out.write('#include <stdio.h>\nint main(void){puts("0");return 0;}\n')
    program = os.path.join(work, "same")
    subprocess.run(["clang", "-O2", source, "-o", program, *flags], check=True)
    return program, listed


def copy(binary, work, name, sign=None, quarantine=False):
    path = os.path.join(work, f"{name}-{time.monotonic_ns()}")
    shutil.copyfile(binary, path)
    os.chmod(path, 0o755)
    if sign is not None:
        subprocess.run(["codesign", "--force", *sign, "--sign", "-", path], check=True, capture_output=True)
    if quarantine:
        stamp = format(int(time.time()), "x")
        subprocess.run(["xattr", "-w", "com.apple.quarantine", f"0081;{stamp};Safari;", path], check=True)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--out")
    args = parser.parse_args()
    binary = os.path.abspath(args.binary)
    work = tempfile.mkdtemp(prefix="ostrom-cold-")
    same, listed = same_libraries(binary, work)
    times = {}
    codes = {}
    faults = {"purge": [], "purge-same": []}

    def add(name, wall_code):
        times.setdefault(name, []).append(wall_code[0])
        codes.setdefault(name, set()).add(wall_code[1])

    timed([binary, "version"])
    logs = subprocess.Popen(
        ["log", "stream", "--style", "compact", "--predicate",
         'process == "syspolicyd" OR process == "amfid" OR subsystem == "com.apple.syspolicy"'],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    for _ in range(args.runs):
        add("warm", timed([binary, "version"]))
        purge(); add("purge", timed([binary, "version"]))
        purge(); faults["purge"].append(page_ins([binary, "version"]))
        purge(); add("purge-true", timed(["/usr/bin/true"]))
        purge(); add("purge-same", timed([same]))
        purge(); faults["purge-same"].append(page_ins([same]))
        purge(); subprocess.run(["cat", binary], stdout=subprocess.DEVNULL, check=True)
        add("purge-read", timed([binary, "version"]))
        fresh = copy(binary, work, "copy")
        add("copy-first", timed([fresh, "version"])); add("copy-second", timed([fresh, "version"]))
        add("adhoc-first", timed([copy(binary, work, "adhoc", sign=[]), "version"]))
        add("runtime-first", timed([copy(binary, work, "runtime", sign=["--options", "runtime"]), "version"]))
        held = copy(binary, work, "quarantine", sign=[], quarantine=True)
        add("quarantine-first", timed([held, "version"])); add("quarantine-second", timed([held, "version"]))
    time.sleep(2)
    logs.terminate()
    log_lines = logs.communicate()[0].splitlines()

    loaded = subprocess.run([binary, "version"], capture_output=True, text=True,
                            env={**os.environ, "DYLD_PRINT_LIBRARIES": "1"}).stderr.splitlines()
    signature = subprocess.run(["codesign", "-dv", binary], capture_output=True, text=True).stderr
    report = {
        "median_ms": {k: round(statistics.median(v), 1) for k, v in times.items()},
        "all_ms": {k: [round(x, 1) for x in v] for k, v in times.items()},
        "exit_codes": {k: sorted(v) for k, v in codes.items()},
        "page_faults_median": {k: statistics.median([x for x in v if x is not None] or [0]) for k, v in faults.items()},
        "dyld_libraries": len([line for line in loaded if "dyld" in line and "loaded" in line]),
        "otool_L": listed.strip().splitlines(),
        "signature": [line for line in signature.splitlines() if line.startswith(("Signature", "flags", "CodeDirectory"))],
        "size_mb": round(os.path.getsize(binary) / 1e6, 2),
        "log_stream_lines": len(log_lines),
        "log_stream_sample": log_lines[:60],
    }
    rows = ["| Condition | Median (ms) | Exit codes |", "| --- | --- | --- |"]
    rows += [f"| {k} | {report['median_ms'][k]} | {report['exit_codes'][k]} |" for k in times]
    summary = "\n".join(["### macOS first start, where the time goes", "", *rows, "",
                         f"page faults after purge: {report['page_faults_median']}",
                         f"dyld libraries loaded: {report['dyld_libraries']}", ""])
    print(summary)
    print(json.dumps(report, indent=1))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(summary + "\n")
    if args.out:
        with open(args.out, "w") as out:
            json.dump(report, out, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())

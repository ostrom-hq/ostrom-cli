# SPDX-License-Identifier: Apache-2.0
"""What a hook costs the agent, which pays it at every tool call: the median of 20 of each figure, on the release
binary, in a real session (`ostrom claude`, the local relay, Ostrom's signed policy checked) against
e2e/contract_server.py.

    python3 e2e/perf.py --binary cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe --label linuxX64 [budgets]

It measures:
- a first start of the day, the system's file cache emptied before each start (scripts/measure.py's way): `ostrom
  version` (the root has no `--version`), and the before-tool hook outside a session (no relay: it reads nothing,
  the empty hook's start-up);
- the before-tool hook on a harmless tool in a session, 20 calls in a row, with a signed policy of no action (the empty
  hook), then with 50 actions that gate nothing and the deployment's gate (50 rules and a gate: the hook matches the
  call against all of them);
- the requests the hook makes to Ostrom: the server's requests that arrived while a hook ran (the log's arrival time
  against the hook's start and end), 20 calls in a row, then 3 calls 31 s apart (the relay keeps the signed policy 30
  s, POLICY_FRESH_FOR: past that, a hook waits on the network);
- the size of the binary.

Results go to stdout, to the job summary in GitHub Actions, and to --out (JSON). Exits 1 when a figure is above the
budget given for it, listing every budget exceeded; a budget that cannot be measured here (no `sudo` to empty the
file cache) is said and fails too.
"""
import argparse
import json
import os
import statistics
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "scripts"))

from measure import run_cold  # noqa: E402
from run import launch, setup  # noqa: E402

RUNS = 20
# The relay keeps the signed policy 30 s (relay/RelayModule.kt, POLICY_FRESH_FOR): one second more, each call is past it
SPACED = "3:31"
HOOK_EVENT = json.dumps({"hook_event_name": "PreToolUse", "session_id": "s", "cwd": "/repo", "tool_name": "Bash",
                         "tool_input": {"command": "ls -la"}}).encode()


def start_server(work, actions):
    """The contract server signing a policy of [actions] (e2e/contract_server.py `--actions`), with its request log."""
    log, ready = os.path.join(work, "requests.jsonl"), os.path.join(work, "ready.txt")
    command = [sys.executable, os.path.join(HERE, "contract_server.py"), "--log", log, "--ready", ready,
               "--package", os.path.join(HERE, "vectors", "package.json"), "--actions", str(actions)]
    process = subprocess.Popen(command)
    for _ in range(100):
        if os.path.exists(ready) and open(ready).read():
            return process, open(ready).read(), log
        time.sleep(0.05)
    process.kill()
    raise SystemExit("the contract server did not start")


def session(binary, actions, spec):
    """
    A session whose agent fires [spec] (`<calls>:<seconds>`) harmless before-tool hooks under a policy of [actions]:
    each hook's wall time, exit code and the server's requests that arrived while it ran; or, for a session that does
    not end, why (what `ostrom` printed last).
    """
    with tempfile.TemporaryDirectory(prefix="ostrom-perf-") as work:
        process, server, log = start_server(work, actions)
        try:
            home, agents, repo = setup(work)
            try:
                _, report = launch(binary, server, home, agents, repo, work, "claude", ["-p", "perf"],
                                   {"E2E_WINDOWS": spec})
            except subprocess.TimeoutExpired as late:
                said = late.stderr.decode(errors="replace") if isinstance(late.stderr, bytes) else late.stderr or ""
                return None, f"the session did not end in 120 s: {said.strip()[-300:] or 'nothing on stderr'}"
            with open(log) as handle:
                arrived = [json.loads(line)["at"] for line in handle if line.strip()]
        finally:
            process.kill()
    hooks = report.get("windows") or []
    return [{"millis": h["millis"], "code": h["code"],
             "requests": sum(1 for at in arrived if h["start"] <= at <= h["end"])} for h in hooks], None


def cold(command, stdin=None):
    """The median of RUNS starts of [command] with the file cache emptied, or why it cannot be measured here."""
    environment = {"PATH": os.environ["PATH"], "HOME": tempfile.gettempdir()}
    walls, reason = run_cold(command, RUNS, stdin=stdin, env=environment, accepted=(0, 2))
    return (round(statistics.median(walls), 1), None) if walls else (None, reason)


def per_hook(hooks):
    """The server's requests per hook, or None without a hook measured."""
    return round(sum(h["requests"] for h in hooks) / len(hooks), 2) if hooks else None


def median(hooks, key):
    return round(statistics.median(h[key] for h in hooks), 1) if hooks else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--out")
    parser.add_argument("--max-hook-ms", type=float, help="median of a hook in a session, either policy")
    parser.add_argument("--max-cold-version-ms", type=float)
    parser.add_argument("--max-cold-hook-ms", type=float)
    parser.add_argument("--max-size-mb", type=float)
    parser.add_argument("--max-burst-requests", type=float, help="requests to Ostrom per hook, 20 calls in a row")
    parser.add_argument("--max-spaced-requests", type=float, help="requests to Ostrom per hook, calls 31 s apart")
    args = parser.parse_args()
    binary = os.path.abspath(args.binary)

    version, version_why = cold([binary, "version"])
    hook, hook_why = cold([binary, "hook", "gate"], HOOK_EVENT)
    empty, empty_why = session(binary, 0, f"{RUNS}:0")
    ruled, ruled_why = session(binary, 50, f"{RUNS}:0")
    spaced, spaced_why = session(binary, 50, SPACED)
    # the first call follows the session's own hooks: not 31 s apart
    spaced = spaced[1:] if spaced else None
    unmeasured = empty is None or ruled is None or spaced is None
    empty, ruled, spaced = empty or [], ruled or [], spaced or []
    size = round(os.path.getsize(binary) / 1024 / 1024, 2)
    figures = {
        "label": args.label,
        "cold_version_ms": version, "cold_hook_ms": hook,
        "hook_empty_ms": median(empty, "millis"), "hook_50_rules_gate_ms": median(ruled, "millis"),
        "requests_per_hook_burst": per_hook(empty + ruled),
        "requests_per_hook_spaced": per_hook(spaced),
        "spaced_ms": [h["millis"] for h in spaced],
        "size_mb": size,
        "codes": sorted({h["code"] for h in empty + ruled + spaced}),
    }
    rows = [
        ("`ostrom version`, file cache emptied (median of 20)", version, "ms", version_why),
        ("hook outside a session, file cache emptied (median of 20)", hook, "ms", hook_why),
        ("hook in a session, policy without action (median of 20)", figures["hook_empty_ms"], "ms", empty_why),
        ("hook in a session, 50 actions and a gate (median of 20)", figures["hook_50_rules_gate_ms"], "ms", ruled_why),
        ("requests to Ostrom per hook, 20 in a row", figures["requests_per_hook_burst"], "", empty_why or ruled_why),
        ("requests to Ostrom per hook, 31 s apart", figures["requests_per_hook_spaced"], "", spaced_why),
        ("binary size", size, "MB", None),
    ]
    lines = [f"### Hook cost, {args.label}", "", "| Figure | Value |", "| --- | --- |"]
    lines += [f"| {what} | {value if value is not None else f'not measured: {why}'} {unit} |"
              for what, value, unit, why in rows]
    lines += ["", f"Hooks 31 s apart: {figures['spaced_ms']} ms; exit codes {figures['codes']}."]
    text = "\n".join(lines)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as handle:
            handle.write(text + "\n")
    if args.out:
        with open(args.out, "w") as handle:
            json.dump(figures, handle, indent=2)

    over = []
    budgets = [
        (args.max_hook_ms, max(filter(None, [figures["hook_empty_ms"], figures["hook_50_rules_gate_ms"]]),
                               default=None), "hook in a session (median)"),
        (args.max_cold_version_ms, version, f"`ostrom version` with the file cache emptied ({version_why})"),
        (args.max_cold_hook_ms, hook, f"the hook with the file cache emptied ({hook_why})"),
        (args.max_size_mb, size, "binary size"),
        (args.max_burst_requests, figures["requests_per_hook_burst"], "requests per hook, 20 in a row"),
        (args.max_spaced_requests, figures["requests_per_hook_spaced"], "requests per hook, 31 s apart"),
    ]
    for budget, value, what in budgets:
        if budget is not None and (value is None or value > budget):
            over.append(f"{what}: {value} > {budget}")
    if unmeasured:
        over.append("a session was not measured (see the table)")
    elif figures["codes"] != [0]:
        over.append(f"a harmless tool was not let through (exit codes {figures['codes']})")
    if over:
        print("over budget:\n  " + "\n  ".join(over))
        sys.exit(1)
    print("every budget holds")


if __name__ == "__main__":
    main()

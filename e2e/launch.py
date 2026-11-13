# SPDX-License-Identifier: Apache-2.0
"""5.9: what a launch costs from `ostrom run claude` to the decision on the first gated tool, end to end.

N successive launches (E2E_LAUNCH_ONLY of the fake agent: its start time, SessionStart, one `kubectl apply` that the
gate holds with exit 2). Each is cut into:
- the launcher's own part: from the start of the `ostrom run claude` process to the start of the agent (the fake agent's
  Python start-up, some tens of ms, is in it);
- SessionStart, and the first gated PreToolUse: the hook's wall time as the agent sees it;
- the server's part: the sum of `duration_ms` of its `request served` log lines (--server-log-command) that appeared
  between the launch's start and its end, by route. Lines are told apart by their `request_id`, so no clock is needed.
It measures only: no budget, but every launch must have decided (exit 2 on the gated command).
The hook alone is 5.8's; the hooks' budget in a session is CLI-SETUP's perf.py.
"""
import json
import math
import os
import subprocess
import time


def rank(values, p):
    """Nearest rank percentile of [values], or None."""
    values = sorted(values)
    return round(values[max(0, math.ceil(len(values) * p / 100) - 1)], 1) if values else None


def served(command):
    """The `request served` entries the server's logs hold now, by request id."""
    done = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=120)
    found = {}
    for line in (done.stdout + "\n" + done.stderr).splitlines():
        if "request served" not in line or "{" not in line:
            continue
        try:
            entry = json.loads(line[line.index("{"):])
        except ValueError:
            continue
        if entry.get("duration_ms") is not None and entry.get("request_id"):
            found[entry["request_id"]] = entry
    return found


class LaunchLatency:
    def __init__(self, journey, maya):
        self.journey = journey
        self.maya = maya

    def run(self):
        journey, runs = self.journey, self.journey.options.launch_runs
        command = journey.options.server_log_command
        journey.begin("5.9", f"the launch, end to end: {runs} times from ostrom run claude to the first gated tool")
        seen = set(served(command)) if command else set()
        series, routes, first_run = [], {}, None
        for i in range(runs):
            report = os.path.join(journey.work, f"launch-{i}.json")
            if os.path.exists(report):
                os.remove(report)
            began = time.time()
            started = journey.claude(self.maya, env={"E2E_LAUNCH_ONLY": "1", "E2E_REPORT": report})
            try:
                started["process"].wait(timeout=180)
            except subprocess.TimeoutExpired:
                started["process"].kill()
                started["process"].wait()
            ended = time.time()
            stderr = ""
            for name in ("out", "err"):
                started[name].seek(0)
                text = started[name].read()
                started[name].close()
                stderr = text if name == "err" else stderr
            if not os.path.exists(report):
                journey.check(False, f"launch {i + 1}: the agent reported nothing (exit {started['process'].returncode})"
                                     f": {stderr.strip()[-300:]}")
                break
            with open(report) as handle:
                seen_by_agent = json.load(handle)
            start, gated = seen_by_agent["hooks"]
            if not journey.check(start["code"] == 0 and gated["code"] == 2,
                                 f"launch {i + 1}: SessionStart exits 0 and the gated tool is held with exit 2 "
                                 f"({start['code']}, {gated['code']}): {gated['stderr'].strip()[-200:]}"):
                break
            row = {"cli": (seen_by_agent["started"] - began) * 1000,
                   "session_start": (start["end"] - start["begin"]) * 1000,
                   "gated_tool": (gated["end"] - gated["begin"]) * 1000,
                   "to_decision": (gated["end"] - began) * 1000, "to_exit": (ended - began) * 1000}
            if command:
                fresh = {k: v for k, v in served(command).items() if k not in seen}
                seen.update(fresh)
                by_route = {}
                for entry in fresh.values():
                    key = f"{entry.get('method')} {entry.get('route')}"
                    by_route.setdefault(key, []).append(entry["duration_ms"])
                    routes.setdefault(key, []).append(entry["duration_ms"])
                row["server"] = float(sum(sum(v) for v in by_route.values()))
                row["requests"] = sum(len(v) for v in by_route.values())
            series.append(row)
        if not series:
            return
        parts = [("end to end, to the decision", "to_decision"), ("launcher (CLI) to the agent's start", "cli"),
                 ("SessionStart hook", "session_start"), ("first gated PreToolUse hook", "gated_tool"),
                 ("whole process, to exit", "to_exit")]
        if command:
            parts.append(("server, sum of request durations", "server"))
        launch = {"runs": len(series)}
        print(f"  {'':40} {'p50':>8} {'p95':>8} {'max':>8}   (ms, {len(series)} launches)", flush=True)
        for label, key in parts:
            values = [row[key] for row in series]
            launch[key] = {"p50": rank(values, 50), "p95": rank(values, 95), "max": round(max(values), 1)}
            print(f"  {label:40} {launch[key]['p50']:8.1f} {launch[key]['p95']:8.1f} {max(values):8.1f}", flush=True)
        if command:
            launch["requests_per_launch"] = rank([row["requests"] for row in series], 50)
            launch["routes"] = {}
            for key, values in sorted(routes.items(), key=lambda item: -sum(item[1])):
                launch["routes"][key] = {"calls": len(values), "p50": rank(values, 50), "p95": rank(values, 95),
                                         "total_per_launch": round(sum(values) / len(series), 1)}
                print(f"    {key:52} {len(values):4} calls  p50 {rank(values, 50):6.1f}  p95 {rank(values, 95):6.1f}"
                      f"  {sum(values) / len(series):7.1f} ms per launch", flush=True)
        else:
            print("  (no --server-log-command: the server's part is not shown)", flush=True)
        launch["samples"] = [{k: round(v, 1) for k, v in row.items()} for row in series]
        journey.step["launch"] = launch
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as handle:
                handle.write(f"\n### 5.9 The launch, end to end ({len(series)} launches, ms)\n\n| Part | p50 | p95 | max |\n"
                             "|---|---:|---:|---:|\n")
                for label, key in parts:
                    handle.write(f"| {label} | {launch[key]['p50']} | {launch[key]['p95']} | {launch[key]['max']} |\n")
                if command:
                    handle.write("\n| Server route | calls | p50 | p95 | ms per launch |\n|---|---:|---:|---:|---:|\n")
                    for key, row in launch["routes"].items():
                        handle.write(f"| `{key}` | {row['calls']} | {row['p50']} | {row['p95']} | "
                                     f"{row['total_per_launch']} |\n")

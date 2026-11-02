# SPDX-License-Identifier: Apache-2.0
"""A coding agent as `ostrom <agent>` sees one: it reads the harness Ostrom laid down for the session the way Claude
Code (`--plugin-dir` → hooks/hooks.json) or Codex (`-c hooks.<Event>=[…]`) reads it, then plays the hooks a real
session fires: session start, a harmless tool, a gated deployment (and, when the gate says so, `ostrom wait` as its own
command, the way a model obeys the refusal; $E2E_OSTROM names the binary), the after-tool hook, session end. With
$E2E_HOLD, the session waits there for the yes: it writes what it fired to $E2E_HOLD.held, waits for $E2E_HOLD to
appear, then runs `ostrom wait` again and the same command, as the model does once told it is approved. With
$E2E_PERF (a number of calls), it then times the before-tool hook three ways ([perf]). What it
saw and what each hook answered go to $E2E_REPORT. With a package, it then rewrites the package's hook view and fires
one more tool, which the hooks must hold (S2-33); it exits with $E2E_EXIT (3 by default), which `ostrom` must hand
back.

Installed by the run as `claude` and `codex` (the name it is started under picks the dialect).
"""
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import tempfile
import time


def claude_hooks(arguments):
    plugin = arguments[arguments.index("--plugin-dir") + 1]
    with open(os.path.join(plugin, "hooks", "hooks.json")) as handle:
        hooks = json.load(handle)["hooks"]
    return {event: entries[0]["hooks"][0]["command"] for event, entries in hooks.items()}


CODEX_OVERRIDE = re.compile(r'^hooks\.(\w+)=\[\{hooks=\[\{type="command",command=("(?:[^"\\]|\\.)*"),timeout=\d+}]}]$')


def codex_hooks(arguments):
    commands = {}
    for flag, value in zip(arguments, arguments[1:]):
        match = CODEX_OVERRIDE.match(value) if flag == "-c" else None
        if match:
            commands[match.group(1)] = json.loads(match.group(2))
    return commands


def fire(command, event):
    started = time.monotonic()
    done = subprocess.run(command, shell=True, input=json.dumps(event), capture_output=True, text=True, timeout=60)
    return {"event": event["hook_event_name"], "tool": event.get("tool_input", {}).get("command"),
            "code": done.returncode, "stdout": done.stdout, "stderr": done.stderr,
            "millis": round((time.monotonic() - started) * 1000, 1)}


def timed(command, event, runs):
    """The wall time of [runs] calls of a hook, started without a shell as the agent starts it: milliseconds each."""
    return [sample[0] for sample in sampled(command, lambda _: (event, None), runs, inside=False)]


def sampled(command, case, runs, inside=True):
    """
    [runs] calls of a hook as the agent starts it (no shell), [case] giving the i-th call its event and extra
    environment: (milliseconds, exit code, milliseconds the hook says it spent inside, or None) each. Without [inside],
    the hook runs as an agent runs it, without OSTROM_HOOK_TIMING.
    """
    argv, samples = shlex.split(command), []
    for i in range(runs):
        event, extra = case(i)
        # OSTROM_HOOK_TIMING: the hook says on stderr how long it took from its runner to its reply
        environment = {**os.environ, **({"OSTROM_HOOK_TIMING": "1"} if inside else {}), **(extra or {})}
        started = time.perf_counter()
        done = subprocess.run(argv, input=json.dumps(event), capture_output=True, text=True, timeout=60,
                              env=environment)
        wall = (time.perf_counter() - started) * 1000
        inside = re.search(r"ostrom hook \S+: ([0-9.]+) ms", done.stderr)
        samples.append((wall, done.returncode, float(inside.group(1)) if inside else None))
    return samples


def percentiles(samples):
    """p50, p95, p99 (nearest rank), min and max of [samples] (milliseconds, exit code), with the exit codes seen."""
    times = sorted(sample[0] for sample in samples)
    insides = sorted(sample[2] for sample in samples if sample[2] is not None)

    def rank(values, p):
        return round(values[max(0, -(-len(values) * p // 100) - 1)], 1) if values else None
    return {"runs": len(times), "p50": rank(times, 50), "p95": rank(times, 95), "p99": rank(times, 99),
            "min": round(times[0], 1), "max": round(times[-1], 1), "codes": sorted({sample[1] for sample in samples}),
            "inside_p50": rank(insides, 50), "inside_p95": rank(insides, 95)}


def perf(hook, base, runs):
    """
    The hook's latency in this session, by what it waits on (E2E_PERF):
    - local: a harmless tool, the relay answering the signed policy from its memory (the decision is the hook's);
    - miss: the same, each call for another repository than the last (the relay keeps one policy), so the relay asks
      Ostrom for the policy every time, the first call included;
    - gated: one command under the gate, each call a request to Ostrom (POST /gates through the relay) that finds the
      gate the first one opened: one gate and one mail to the approver, not [runs].
    """
    def tool(command):
        return {**base, "hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_input": {"command": command}}
    repository = os.environ.get("OSTROM_REPO", "")
    return {
        "local": percentiles(sampled(hook, lambda _: (tool("ls -la"), None), runs)),
        "miss": percentiles(sampled(hook, lambda i: (tool("ls -la"),
                                                     {"OSTROM_REPO": repository + ("" if i % 2 else "-perf")}), runs)),
        "gated": percentiles(sampled(hook, lambda i: (tool("kubectl apply -f perf.yaml"), None), runs)),
    }


def after_the_yes(hook, event, gate, fired):
    """
    The session held while the journey answers. `ostrom wait` starts at once, as a model does once told to, and is
    already waiting when the yes comes (Ostrom holds its read, ?wait=30); then the same command again. The wall clock
    of its end and of the retry go to the report: the journey measures from its yes to the action passing (5.5).
    """
    hold = os.environ["E2E_HOLD"]
    with open(hold + ".tmp", "w") as handle:
        json.dump({"hooks": fired}, handle)
    os.replace(hold + ".tmp", hold + ".held")
    # files, not pipes: nobody reads them while it waits. Its own limit is the journey's (the steps before the yes
    # sign two people in), and a journey that ends without a yes releases it below.
    out, err = (tempfile.TemporaryFile("w+") for _ in range(2))
    waiting = subprocess.Popen([os.environ["E2E_OSTROM"], "wait", gate, "--timeout", "280"], stdout=out, stderr=err,
                               text=True)
    deadline, released = time.monotonic() + 300, None
    # a journey that ends without a yes releases the session: the wait gets one held read more, then stops
    while waiting.poll() is None and time.monotonic() < deadline:
        if released is None and os.path.exists(hold):
            released = time.monotonic()
        if released is not None and time.monotonic() - released > 40:
            break
        time.sleep(0.02)
    if waiting.poll() is None:
        waiting.kill()
    waiting.wait()
    answered = time.time()
    out.seek(0)
    err.seek(0)
    waited = {"event": "wait-after", "tool": gate, "code": waiting.returncode, "stdout": out.read(),
              "stderr": err.read(), "at": answered}
    # the action passes when the hook lets the command through: its end, the hook's own run included
    retry = {**fire(hook, event), "event": "retry"}
    retry["at"] = time.time()
    while not os.path.exists(hold) and time.monotonic() < deadline:
        time.sleep(0.2)
    return [waited, retry]


def main():
    name = os.path.basename(sys.argv[0]).split(".")[0]
    arguments = sys.argv[1:]
    hooks = claude_hooks(arguments) if name == "claude" else codex_hooks(arguments)
    cwd = os.getcwd()
    session = f"{name}-session-1"
    base = {"session_id": session, "cwd": cwd, "transcript_path": os.path.join(cwd, "transcript.jsonl")}
    fired = [fire(hooks["SessionStart"], {**base, "hook_event_name": "SessionStart", "source": "startup"})]
    for command in ("ls -la", "kubectl apply -f prod.yaml"):
        tool = {"tool_name": "Bash", "tool_input": {"command": command}}
        fired.append(fire(hooks["PreToolUse"], {**base, "hook_event_name": "PreToolUse", **tool}))
        asked = re.search(r"ostrom wait ([A-Za-z0-9_-]+)", fired[-1]["stdout"] + fired[-1]["stderr"])
        if asked and os.environ.get("E2E_OSTROM"):
            done = subprocess.run([os.environ["E2E_OSTROM"], "wait", asked.group(1), "--timeout", "5"],
                                  capture_output=True, text=True, timeout=60)
            fired.append({"event": "wait", "tool": asked.group(1), "code": done.returncode, "stdout": done.stdout,
                          "stderr": done.stderr})
            if os.environ.get("E2E_HOLD"):
                fired.extend(after_the_yes(hooks["PreToolUse"], {**base, "hook_event_name": "PreToolUse", **tool},
                                           asked.group(1), fired))
    # the budget of a hook in a real session: the relay asked, Ostrom's signed policy checked, at every tool call
    harmless = {**base, "hook_event_name": "PreToolUse", "tool_name": "Bash", "tool_input": {"command": "ls -la"}}
    runs = int(os.environ.get("E2E_TIMED_RUNS", "0"))
    timing = timed(hooks["PreToolUse"], harmless, runs) if runs else []
    perf_runs = int(os.environ.get("E2E_PERF") or 0)
    measured = perf(hooks["PreToolUse"], base, perf_runs) if perf_runs > 0 else None
    tool = {"tool_name": "Bash", "tool_input": {"command": "ls -la"}, "tool_response": {"stdout": ""}}
    fired.append(fire(hooks["PostToolUse"], {**base, "hook_event_name": "PostToolUse", **tool}))
    package = os.environ.get("OSTROM_PACKAGE")
    if package:
        # S2-33: the agent rewrites a file of the package Ostrom laid down; the next hook holds every action
        with open(package, "w") as handle:
            handle.write("{}")
        tool = {"tool_name": "Bash", "tool_input": {"command": "echo after-tampering"}}
        fired.append(fire(hooks["PreToolUse"], {**base, "hook_event_name": "PreToolUse", **tool}))
    fired.append(fire(hooks["SessionEnd"], {**base, "hook_event_name": "SessionEnd", "reason": "exit"}))
    environment = {key: value for key, value in os.environ.items() if key.startswith(("OSTROM_", "ANTHROPIC_"))}
    report = {"agent": name, "arguments": arguments, "environment": environment, "cwd": cwd, "hooks": fired,
              "perf": measured,
              "path": os.environ.get("PATH", ""),
              "timing": {"median": statistics.median(timing), "p95": sorted(timing)[int(len(timing) * 0.95) - 1]}
              if timing else None}
    with open(os.environ["E2E_REPORT"], "w") as handle:
        json.dump(report, handle, indent=2)
    sys.exit(int(os.environ.get("E2E_EXIT", "3")))


if __name__ == "__main__":
    main()

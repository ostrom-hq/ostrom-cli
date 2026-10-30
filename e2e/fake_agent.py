# SPDX-License-Identifier: Apache-2.0
"""A coding agent as `ostrom <agent>` sees one: it reads the harness Ostrom laid down for the session the way Claude
Code (`--plugin-dir` → hooks/hooks.json) or Codex (`-c hooks.<Event>=[…]`) reads it, then plays the hooks a real
session fires: session start, a harmless tool, a gated deployment, the after-tool hook, session end. What it saw and
what each hook answered go to $E2E_REPORT. With a package, it then rewrites the package's hook view and fires one more
tool, which the hooks must hold (S2-33); what; it exits with $E2E_EXIT (3 by default), which `ostrom` must hand back.

Installed by the run as `claude` and `codex` (the name it is started under picks the dialect).
"""
import json
import os
import re
import subprocess
import sys
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
              "path": os.environ.get("PATH", "")}
    with open(os.environ["E2E_REPORT"], "w") as handle:
        json.dump(report, handle, indent=2)
    sys.exit(int(os.environ.get("E2E_EXIT", "3")))


if __name__ == "__main__":
    main()

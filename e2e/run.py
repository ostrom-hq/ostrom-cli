# SPDX-License-Identifier: Apache-2.0
"""End to end: the real `ostrom` binary launches a fake Claude Code and a fake Codex against a server of the contract,
and the hooks they fire reach the server through the local relay. Then the time budgets of the hook and of start-up.

    python3 e2e/run.py --binary cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe

e2e/contract_server.py stands in for ostrom-server until it serves launches, packages and sessions. Exit 0 when
every check holds.
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

HERE = os.path.dirname(os.path.abspath(__file__))
HOOK_BUDGET_MS = 15
START_BUDGET_MS = 30
failures = []


def check(condition, what):
    print(("  ok    " if condition else "  FAIL  ") + what)
    if not condition:
        failures.append(what)


def start_server(work, package):
    log = os.path.join(work, "requests.jsonl")
    ready = os.path.join(work, "ready.txt")
    command = [sys.executable, os.path.join(HERE, "contract_server.py"), "--log", log, "--ready", ready]
    if package:
        command += ["--package", os.path.join(HERE, "vectors", "package.json")]
    process = subprocess.Popen(command)
    for _ in range(100):
        if os.path.exists(ready) and open(ready).read():
            return process, open(ready).read(), log
        time.sleep(0.05)
    process.kill()
    raise SystemExit("the contract server did not start")


def requests(log):
    with open(log) as handle:
        return [json.loads(line) for line in handle if line.strip()]


def setup(work):
    """A developer's machine: OSTROM_HOME with the device's credentials, a repository, the fake agents on PATH."""
    home = os.path.join(work, "ostrom-home")
    os.makedirs(home)
    agents = os.path.join(work, "agents")
    os.makedirs(agents)
    for name in ("claude", "codex"):
        # the name the fake agent is started under picks the dialect
        script = os.path.join(agents, f"{name}.py")
        shutil.copy(os.path.join(HERE, "fake_agent.py"), script)
        path = os.path.join(agents, name)
        with open(path, "w") as handle:
            handle.write(f"#!/bin/sh\nexec {sys.executable} {script} \"$@\"\n")
        os.chmod(path, 0o755)
    repo = os.path.join(work, "acme-api")
    os.makedirs(repo)
    subprocess.run(["git", "init", "-q", repo], check=True)
    return home, agents, repo


def launch(binary, server, home, agents, repo, work, agent, arguments, extra=None):
    report = os.path.join(work, f"report-{agent}.json")
    environment = {
        "PATH": agents + os.pathsep + os.environ["PATH"],
        "HOME": work,
        "OSTROM_HOME": home,
        "OSTROM_URL": server,
        "TMPDIR": work,
        "E2E_REPORT": report,
        **(extra or {}),
    }
    with open(os.path.join(home, "credentials.json"), "w") as handle:
        json.dump({server: {"token": "ost_e2e_device", "login": "maya"}}, handle)
    done = subprocess.run([os.path.abspath(binary), agent, *arguments], cwd=repo, env=environment,
                          capture_output=True, text=True, timeout=120)
    print(done.stderr.rstrip())
    with open(report) as handle:
        return done, json.load(handle)


def scenario_claude(binary, work):
    print("ostrom claude, with a signed session package")
    server_process, server, log = start_server(work, package=True)
    try:
        home, agents, repo = setup(work)
        done, report = launch(binary, server, home, agents, repo, work, "claude", ["-p", "hello"],
                              {"E2E_TIMED_RUNS": "30"})
        check(done.returncode == 3, f"the agent's exit code is ostrom's (got {done.returncode})")
        environment = report["environment"]
        check("OSTROM_TOKEN" not in environment, "the device token never reaches the agent")
        check("OSTROM_URL" not in environment, "nor does the server's address")
        check(environment.get("OSTROM_RELAY_URL", "").startswith("http://127.0.0.1:"), "the relay is on the loopback")
        check(environment.get("OSTROM_REPO") == "acme-api", "the repository is the folder of the git top")
        check(report["arguments"][:1] == ["--plugin-dir"], "Claude Code gets the session's plugin")
        check(report["arguments"][2:4] == ["-p", "hello"], "the developer's arguments are passed on")
        hooks = {(hook["event"], hook["tool"]): hook for hook in report["hooks"]}
        start = hooks[("SessionStart", None)]
        check(start["code"] == 0 and "e2e rules" in start["stdout"], "the session hook adds the rules")
        check(hooks[("PreToolUse", "ls -la")]["code"] == 0, "a harmless tool runs")
        gated = hooks[("PreToolUse", "kubectl apply -f prod.yaml")]
        # S2-20: Ostrom's signed policy gates a deployment; the package's copy, which observes it, only adds
        check(gated["code"] == 2,
              f"the package never removes what Ostrom's signed policy gates (exit {gated['code']}: "
              f"{gated['stderr'][:200]})")
        timing = report["timing"]
        check(timing["median"] < HOOK_BUDGET_MS,
              f"in a session, ostrom hook gate on a harmless tool (relay asked, signed policy checked): median "
              f"{timing['median']:.1f} ms (p95 {timing['p95']:.1f}) < {HOOK_BUDGET_MS} ms")
        tampered = hooks[("PreToolUse", "echo after-tampering")]
        check(tampered["code"] == 2 and "not the one Ostrom signed and laid down" in tampered["stderr"],
              f"a package rewritten by the agent holds every action (exit {tampered['code']}: "
              f"{tampered['stderr'][:200]})")
        check(environment.get("OSTROM_RELAY_TOKEN_FILE", "").endswith("relay-token"),
              "the hooks' capability is in a private file (S2-25)")
        check(environment.get("OSTROM_INSTANCE_KEYS", "").startswith('{"keys"'), "the instance's keys are given")
        check(len(environment.get("OSTROM_PACKAGE_SEAL", "")) == 64, "the package's seal is given (S2-33)")
        seen = requests(log)
        signed = [r for r in seen if r["path"] == "/api/v1/gates/catalog/signed"]
        check(bool(signed) and all(r["query"] == "repo=acme-api" and r["token"] == "agt_e2e_launch" for r in signed),
              "the hooks read Ostrom's signed policy for the repository through the relay")
        check(any(r["path"] == "/api/v1/instance/keys" for r in seen), "the launcher read the instance's keys")
        device = {(r["method"], r["path"]) for r in seen if r["token"] == "ost_e2e_device"}
        agent = {(r["method"], r["path"]) for r in seen if r["token"] == "agt_e2e_launch"}
        check(("POST", "/api/v1/launches") in device, "the launch is created with the device token")
        check(("POST", "/api/v1/sessions") in agent, "the session hook reaches Ostrom with the launch's token")
        check(("POST", "/api/v1/gates") in agent, "the gate request goes through the relay")
        check(not any(r["path"].startswith(("/api/v1/sessions", "/api/v1/gates")) and r["token"] == "ost_e2e_device"
                      for r in seen), "no hook call carries the device token")
        ends = [r for r in seen if r["path"].endswith("/end")]
        check(len(ends) == 1 and json.loads(ends[0]["body"]).get("exitCode") == 3, "the end of the session says 3")
        check(any(r["path"].endswith("/workflow-receipt") for r in seen), "the workflow receipt is asked for")
        check(os.path.exists(os.path.join(home, "receipts", "W-e2e.json")), "the receipt is kept for ostrom verify")
        check(os.path.exists(os.path.join(home, "server-keys.json")), "the server's key is pinned")
        offline(binary, work, home)
        logs(binary, server, work, home)
        check(not any(name.startswith("ostrom-run-") for name in os.listdir(work)), "the session's files are removed")
    finally:
        server_process.kill()


def ostrom_on(binary, server, work, home, *arguments):
    environment = {"PATH": os.environ["PATH"], "HOME": work, "OSTROM_HOME": home, "OSTROM_URL": server}
    return subprocess.run([os.path.abspath(binary), *arguments], env=environment, capture_output=True, text=True,
                          timeout=60)


def logs(binary, server, work, home):
    """`ostrom logs`: the thread of the session the launch just ran, read back with the device's token."""
    thread = ostrom_on(binary, server, work, home, "logs")
    lines = thread.stdout.splitlines()
    check(thread.returncode == 0 and lines[:1] and lines[0].startswith("Session ") and "@maya" in lines[0],
          f"ostrom logs heads the developer's last session (exit {thread.returncode}: {thread.stdout[:300]} "
          f"{thread.stderr[:300]})")
    said = "\n".join(lines)
    check("session started" in said and "check" in said and "Run the tests · passed" in said,
          "the thread has the start and the receipt's checks")
    # the token of `ostrom login` may not read gates on ostrom-server (CLI_SCOPES): the request shows by its id
    check(re.search(r"asked for a yes +G-e2e", said) is not None, "and the request for a yes, by its id")
    ended = [line for line in lines if "session ended" in line]
    check(len(ended) == 1 and "exit 3" in ended[0], f"and the end, with the agent's code ({ended})")
    session = lines[0].split(" ")[1] if lines else ""
    followed = ostrom_on(binary, server, work, home, "logs", session, "-f")
    check(followed.returncode == 0 and followed.stdout == thread.stdout,
          "-f on an ended session prints the thread once and returns")
    kept = ostrom_on(binary, server, work, home, "logs", session, "--json")
    body = json.loads(kept.stdout) if kept.returncode == 0 else {}
    check(body.get("session", {}).get("id") == session and [g.get("id") for g in body.get("gates", [])] == ["G-e2e"],
          f"--json is the server's answer with the gate's request (exit {kept.returncode})")
    missing = ostrom_on(binary, server, work, home, "logs", "no-such-session")
    check(missing.returncode == 66 and "No session no-such-session" in missing.stderr,
          f"no such session is 66 (exit {missing.returncode}: {missing.stderr[:200]})")


def logs_unserved(binary, work):
    """`ostrom logs` on a server that does not serve the sessions' reads: said, 69."""
    print("ostrom logs, on a server without the sessions' reads")
    log = os.path.join(work, "requests.jsonl")
    ready = os.path.join(work, "ready.txt")
    process = subprocess.Popen([sys.executable, os.path.join(HERE, "contract_server.py"), "--log", log,
                                "--ready", ready, "--without-session-reads"])
    try:
        for _ in range(100):
            if os.path.exists(ready) and open(ready).read():
                break
            time.sleep(0.05)
        server = open(ready).read()
        home = os.path.join(work, "ostrom-home")
        os.makedirs(home)
        with open(os.path.join(home, "credentials.json"), "w") as handle:
            json.dump({server: {"token": "ost_e2e_device", "login": "maya"}}, handle)
        for arguments in (["logs"], ["logs", "seg-1", "-f"]):
            done = ostrom_on(binary, server, work, home, *arguments)
            check(done.returncode == 69 and "does not serve the sessions' reads" in done.stderr,
                  f"ostrom {' '.join(arguments)} says the server does not serve them (exit {done.returncode}: "
                  f"{done.stderr[:200]})")
    finally:
        process.kill()


def offline(binary, work, home):
    """`ostrom verify` and `ostrom receipts list --local` on the kept receipt, with no server at all."""
    environment = {"PATH": os.environ["PATH"], "HOME": work, "OSTROM_HOME": home}

    def ostrom(*arguments):
        return subprocess.run([os.path.abspath(binary), *arguments], env=environment, capture_output=True, text=True,
                              timeout=30)

    verified = ostrom("verify", "W-e2e")
    check(verified.returncode == 0 and "VERIFIED offline" in verified.stdout,
          f"ostrom verify W-e2e holds with the pinned key (exit {verified.returncode}: "
          f"{verified.stdout[-400:]}{verified.stderr[-200:]})")
    report = ostrom("verify", "W-e2e", "--json")
    check(report.returncode == 0 and json.loads(report.stdout).get("result") == "verified", "--json says verified")
    with open(os.path.join(home, "receipts", "W-e2e.json")) as handle:
        bundle = json.load(handle)
    bundle["receipt"]["nodes"][1]["outcome"] = "yes from mallory"
    tampered = os.path.join(work, "tampered.json")
    with open(tampered, "w") as handle:
        json.dump(bundle, handle)
    broken = ostrom("verify", tampered)
    check(broken.returncode == 1 and "NOT VALID" in broken.stdout,
          f"a tampered receipt is not valid (exit {broken.returncode})")
    kept = ostrom("receipts", "list", "--local")
    check(kept.returncode == 0 and kept.stdout.startswith("● W-e2e  workflow"),
          f"the receipt is listed as kept here (exit {kept.returncode}: {kept.stdout[:200]}{kept.stderr[-200:]})")


def scenario_codex(binary, work):
    print("ostrom codex exec, without a package")
    server_process, server, log = start_server(work, package=False)
    try:
        home, agents, repo = setup(work)
        done, report = launch(binary, server, home, agents, repo, work, "codex", ["exec", "fix it"],
                              {"E2E_EXIT": "0", "E2E_OSTROM": os.path.abspath(binary)})
        check(done.returncode == 0, f"codex ends with 0 (got {done.returncode})")
        check(report["arguments"][0] == "exec", "the subcommand comes first")
        check(report["arguments"][-1] == "fix it", "the prompt comes last")
        check("OSTROM_SESSION_END_MS" not in report["environment"], "the end is the launcher's, not the hook's")
        check(os.path.isabs(report["environment"].get("OSTROM_GIT", "")), "the hooks run the git the launcher found")
        hooks = {(hook["event"], hook["tool"]): hook for hook in report["hooks"]}
        gated = hooks[("PreToolUse", "kubectl apply -f prod.yaml")]
        check(gated["code"] == 2,
              f"without a package Ostrom's catalogue gates (exit {gated['code']}: {gated['stderr'][:200]})")
        check("--dangerously-bypass-hook-trust" not in report["arguments"], "no package: no hook trust asked")
        waited = hooks.get(("wait", "G-e2e"))
        check(waited is not None and waited["code"] == 0
              and waited["stdout"].startswith("approved by lena (receipt R-gate). Run the same command again now."),
              f"ostrom wait reads the yes (got {waited})")
        end = [hook for hook in report["hooks"] if hook["event"] == "SessionEnd"][0]
        check(end["code"] == 0, "the end hook answers")
        seen = requests(log)
        check(any(r["path"].endswith("/end") for r in seen), "the session is ended")
        check(any(r["path"] == "/api/v1/gates/catalog/signed" for r in seen),
              "the relay asks Ostrom for its signed policy")
        read = [r for r in seen if r["path"] == "/api/v1/gates/G-e2e"]
        check(len(read) == 1 and read[0]["query"] == "wait=5" and read[0]["token"] == "agt_e2e_launch",
              f"ostrom wait asks through the relay, with the launch's token (got {read})")
    finally:
        server_process.kill()


def budgets(binary, work):
    """Start-up. Outside a session the gate holds every call (no relay): its budget in a session is checked by
    scenario_claude."""
    print("time budgets")
    event = json.dumps({"hook_event_name": "PreToolUse", "session_id": "s", "cwd": work,
                        "tool_name": "Read", "tool_input": {"file_path": "README.md"}})
    environment = {"PATH": os.environ["PATH"], "HOME": work}

    def median(command, stdin):
        times = []
        for _ in range(30):
            started = time.perf_counter()
            subprocess.run(command, input=stdin, capture_output=True, text=True, env=environment)
            times.append((time.perf_counter() - started) * 1000)
        return statistics.median(times), sorted(times)[int(len(times) * 0.95) - 1]

    hook, hook95 = median([binary, "hook", "gate"], event)
    check(hook < HOOK_BUDGET_MS,
          f"ostrom hook gate outside a session: median {hook:.1f} ms (p95 {hook95:.1f}) < {HOOK_BUDGET_MS} ms")
    start, start95 = median([binary, "version"], "")
    check(start < START_BUDGET_MS, f"ostrom version: median {start:.1f} ms (p95 {start95:.1f}) < {START_BUDGET_MS} ms")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()
    binary = os.path.abspath(args.binary)
    for scenario in (scenario_claude, scenario_codex, logs_unserved, budgets):
        with tempfile.TemporaryDirectory(prefix="ostrom-e2e-") as work:
            scenario(binary, work)
    if failures:
        print(f"{len(failures)} check(s) failed")
        sys.exit(1)
    print("every check holds")


if __name__ == "__main__":
    main()

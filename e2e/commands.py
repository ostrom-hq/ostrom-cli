# SPDX-License-Identifier: Apache-2.0
"""End to end: every command of the real `ostrom` binary, against the contract's server (e2e/contract_server.py).

    python3 e2e/commands.py --binary cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe

1. `--help` of every command, read from the binary itself (its PowerShell completion lists every visible command,
   `ostrom help plumbing` the hidden ones): exit 0, its usage, examples and page of the docs, never a colour in a pipe.
2. A line per behaviour with the exit code of `ostrom help exit-codes` it must give: the doctor against the server,
   another server and none; sign-in, inbox, receipts, verify, config; usage errors; commands not built yet.
3. Colours (NO_COLOR, --no-color, `color always`) and the completion scripts loaded by bash, and PowerShell when the
   machine has it.

Exit 0 when every check holds.
"""
import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = "https://ostrom.run/docs/"
ESCAPE = "\x1b["
failures = []


def check(condition, what):
    print(("  ok    " if condition else "  FAIL  ") + what)
    if not condition:
        failures.append(what)


class Machine:
    """A developer's machine: a home, the CLI folder, a git repository, fake agents on PATH, and the server."""

    def __init__(self, binary, work, server):
        self.binary = os.path.abspath(binary)
        self.work = work
        self.server = server
        self.home = os.path.join(work, "ostrom-home")
        os.makedirs(self.home)
        agents = os.path.join(work, "agents")
        os.makedirs(agents)
        for name in ("claude", "codex"):
            path = os.path.join(agents, name)
            with open(path, "w") as handle:
                handle.write("#!/bin/sh\nexit 0\n")
            os.chmod(path, 0o755)
        self.repo = os.path.join(work, "acme-api")
        os.makedirs(self.repo)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        self.path = agents + os.pathsep + os.environ["PATH"]

    def sign_in(self):
        with open(os.path.join(self.home, "credentials.json"), "w") as handle:
            json.dump({self.server: {"token": "ost_e2e_device", "login": "maya"}}, handle)

    def sign_out(self):
        credentials = os.path.join(self.home, "credentials.json")
        if os.path.exists(credentials):
            os.remove(credentials)

    def run(self, *arguments, server=None, extra=None):
        environment = {
            "PATH": self.path,
            "HOME": self.work,
            "OSTROM_HOME": self.home,
            "OSTROM_URL": server or self.server,
            "TMPDIR": self.work,
            "OSTROM_CONSOLE_URL": "http://127.0.0.1:9",
            **(extra or {}),
        }
        return subprocess.run([self.binary, *arguments], cwd=self.repo, env=environment, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=60)


def start_server(work):
    log = os.path.join(work, "requests.jsonl")
    ready = os.path.join(work, "ready.txt")
    command = [sys.executable, os.path.join(HERE, "contract_server.py"), "--log", log, "--ready", ready,
               "--package", os.path.join(HERE, "vectors", "package.json")]
    process = subprocess.Popen(command)
    for _ in range(100):
        if os.path.exists(ready) and open(ready).read():
            return process, open(ready).read()
        time.sleep(0.05)
    process.kill()
    raise SystemExit("the contract server did not start")


def not_ostrom():
    """A web server that is not Ostrom (a captive portal, a wrong URL): a page of HTML for every request."""

    class Page(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            page = b"<html><body>Welcome to the hotel network</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def closed_port():
    """A loopback URL nothing listens on."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return f"http://127.0.0.1:{probe.getsockname()[1]}"


def commands(machine):
    """Every command path, from the binary: the PowerShell completer's table, then the plumbing it hides."""
    script = machine.run("completion", "powershell").stdout
    visible = [path for path in re.findall(r"^        '((?:[^']|'')*)' = @\(", script, re.M) if path]
    plumbing = re.findall(r"^  ostrom ([a-z]+) ", machine.run("help", "plumbing").stdout, re.M)
    return visible + plumbing


def scenario_help(machine):
    print("--help of every command")
    paths = commands(machine)
    check(len(paths) > 40, f"the binary lists its commands ({len(paths)})")
    for path in paths:
        done = machine.run(*path.split(" "), "--help")
        help = done.stdout
        good = (done.returncode == 0 and f"Usage: ostrom {path}" in help and "\nExamples:\n  ostrom" in help
                and f"Docs: {DOCS}" in help and ESCAPE not in help)
        check(good, f"ostrom {path} --help (exit {done.returncode})")


# Each line: what it shows, the command line, the exit code, a text its stdout or stderr holds, and the setting.
def lines(machine, other, nowhere):
    return [
        ("the home screen", [], 0, "Not signed in. Next: ostrom login", None),
        ("the version", ["version"], 0, "ostrom ", None),
        ("the exit codes", ["help", "exit-codes"], 0, "   64  Bad usage", None),
        ("an unknown command", ["nope"], 64, "Run 'ostrom --help' for usage.", None),
        ("an unknown option", ["doctor", "--bad"], 64, "Run 'ostrom doctor --help' for usage.", None),
        ("an unknown help topic", ["help", "nope"], 64, "Topics: plumbing, exit-codes.", None),
        ("a shell without completion", ["completion", "tcsh"], 64, "tcsh", None),
        ("an option not built yet", ["claude", "--cloud"], 64, "not in this version of ostrom yet", None),
        ("a command not built yet", ["status"], 70, "not implemented yet", None),
        ("a group not built yet", ["members"], 70, "not implemented yet", None),
        ("wait without a request", ["wait"], 64, "Run 'ostrom wait --help' for usage.", None),
        ("the doctor, with an Ostrom server", ["doctor"], 0, "reachable", None),
        ("which says the server's version", ["doctor"], 0, "· Ostrom 0.1.0-e2e", None),
        ("the doctor, for a script", ["doctor", "--json"], 0, '"problems":0', None),
        ("the doctor, with no Ostrom at the URL", ["doctor"], 1, "but not as an Ostrom server", other),
        ("the doctor, with nothing at the URL", ["doctor"], 1, "unreachable", nowhere),
        ("the inbox, signed out", ["inbox"], 65, "ostrom login", "signed-out"),
        ("the inbox", ["inbox"], 0, "Nothing waits for you.", None),
        ("the inbox, for a script", ["inbox", "--json"], 0, "[]", None),
        ("the receipts", ["receipts", "list"], 0, "", None),
        ("a receipt that does not exist", ["receipts", "show", "R-none"], 66, "R-none", None),
        ("a receipt pulled", ["receipts", "pull", "W-e2e"], 0, "Saved W-e2e", None),
        ("and verified offline", ["verify", "W-e2e"], 0, "", None),
        ("a setting", ["config", "set", "color", "never"], 0, "", None),
        ("read back", ["config", "get", "color"], 0, "never", None),
        ("a setting that cannot be", ["config", "set", "color", "purple"], 64, "auto, always or never", None),
        ("usage statistics", ["telemetry"], 0, "", None),
        ("an import without its database", ["import", "teamcode"], 64, "--db", None),
        ("sign out", ["logout"], 0, "", None),
        ("and signed out", ["inbox"], 65, "ostrom login", None),
    ]


def scenario_exit_codes(machine, other, nowhere):
    print("exit codes, against the contract's server")
    documented = {int(code) for code in re.findall(r"^ +(\d+)  ", machine.run("help", "exit-codes").stdout, re.M)}
    machine.sign_in()
    for what, arguments, code, text, setting in lines(machine, other, nowhere):
        server = None
        if setting == "signed-out":
            machine.sign_out()
        elif setting is not None:
            server = setting
        done = machine.run(*arguments, server=server)
        said = done.stdout + done.stderr
        check(code in documented, f"exit {code} is in ostrom help exit-codes")
        check(done.returncode == code and text in said,
              f"{what}: ostrom {' '.join(arguments)} exits {code} (got {done.returncode}) and says {text!r}"
              + ("" if text in said else f" (said {said.strip()[-300:]!r})"))
        if setting == "signed-out":
            machine.sign_in()


def scenario_colours(machine):
    print("colours")
    machine.run("config", "set", "color", "auto")
    check(ESCAPE not in machine.run("--help").stdout, "no colour in a pipe")
    machine.run("config", "set", "color", "always")
    check(ESCAPE in machine.run("--help").stdout, "color always forces them")
    check(ESCAPE not in machine.run("--no-color", "--help").stdout, "--no-color wins over color always")
    check(ESCAPE not in machine.run("--help", extra={"NO_COLOR": "1"}).stdout, "NO_COLOR wins over color always")
    json_out = machine.run("doctor", "--json").stdout
    check(ESCAPE not in json_out and json.loads(json_out)["problems"] == 0, "--json never has a colour")
    machine.run("config", "unset", "color")


def scenario_completion(machine):
    print("completion scripts")
    for shell in ("bash", "zsh", "fish", "powershell"):
        done = machine.run("completion", shell)
        check(done.returncode == 0 and "receipts" in done.stdout, f"ostrom completion {shell}")
    script = os.path.join(machine.work, "ostrom.bash")
    with open(script, "w") as handle:
        handle.write(machine.run("completion", "bash").stdout)
    loaded = subprocess.run(["bash", "-c", f"source {script} && complete -p ostrom"], capture_output=True, text=True)
    check(loaded.returncode == 0 and "ostrom" in loaded.stdout, "bash loads it and completes ostrom")
    for words, expected, never in (
            (["ostrom", ""], ["receipts", "doctor"], ["hook", "wait", "mcp", "check"]),
            (["ostrom", "completion", ""], ["bash", "zsh", "fish", "powershell"], []),
            (["ostrom", "receipts", "--server", "list", ""], ["list", "show"], ["--local"]),
            (["ostrom", "doctor", "--j"], ["--json"], ["--help"])):
        line = " ".join(f"'{word}'" for word in words)
        probe = (f"source {script}; COMP_WORDS=({line}); COMP_CWORD={len(words) - 1}; _ostrom; "
                 'printf "%s\\n" "${COMPREPLY[@]}"')
        offered = subprocess.run(["bash", "-c", probe], capture_output=True, text=True).stdout.split()
        good = all(word in offered for word in expected) and not any(word in offered for word in never)
        check(good, f"bash completes {' '.join(words)!r} (offered {offered[:8]})")
    fish = shutil.which("fish")
    if fish is None:
        print("  skip  fish is not on this machine")
    else:
        probe = f"{machine.binary} completion fish | source; complete -C 'ostrom completion '"
        offered = subprocess.run([fish, "--no-config", "-c", probe], capture_output=True, text=True).stdout.split()
        check("powershell" in offered, f"fish completes 'ostrom completion ' (offered {offered[:8]})")
    pwsh = shutil.which("pwsh")
    if pwsh is None:
        print("  skip  PowerShell is not on this machine")
        return
    script = os.path.join(machine.work, "ostrom.ps1")
    with open(script, "w") as handle:
        handle.write(machine.run("completion", "powershell").stdout)
    for typed, expected in (("ostrom rec", "receipts"), ("ostrom receipts ", "list"), ("ostrom doctor --j", "--json")):
        probe = (f". '{script}'; (TabExpansion2 -inputScript '{typed}' -cursorColumn {len(typed)})"
                 ".CompletionMatches | ForEach-Object { $_.CompletionText }")
        done = subprocess.run([pwsh, "-NoProfile", "-NonInteractive", "-Command", probe], capture_output=True,
                              text=True, timeout=60)
        offered = done.stdout.split()
        check(expected in offered, f"PowerShell completes {typed!r} with {expected} (offered {offered[:8]})")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()
    work = tempfile.mkdtemp(prefix="ostrom-commands-")
    process, server = start_server(work)
    try:
        machine = Machine(args.binary, work, server)
        machine.sign_in()
        scenario_help(machine)
        portal, other = not_ostrom()
        scenario_exit_codes(machine, other=other, nowhere=closed_port())
        portal.shutdown()
        scenario_colours(machine)
        scenario_completion(machine)
    finally:
        process.kill()
        shutil.rmtree(work, ignore_errors=True)
    if failures:
        print(f"\n{len(failures)} check(s) failed")
        return 1
    print("\nevery command holds")
    return 0


if __name__ == "__main__":
    sys.exit(main())

# SPDX-License-Identifier: Apache-2.0
"""6.2 of demo/PARCOURS.md on the CLI side: a session in Ostrom's cloud, started and attached to from the terminal.

`ostrom claude --cloud`, with the token of `ostrom login`, asks the server for a cloud session (no repository: the
machine's folder has no GitHub origin), waits until it runs, then gives it this terminal: the session's bytes come to
stdout. Ctrl-] (0x1d on stdin) detaches and leaves it running. `ostrom attach <id>` gives it the terminal again, and
the keys typed reach the session: Ctrl-C ends Claude Code (no account in the CI: it waits in its first-run
screens), the session's script leaves a login shell, and a command typed in it comes back computed (`$((6*7))` read
as 42), which only the session's shell can have done. It detaches the same way. The owner stops it
(`DELETE /api/v1/cloud/sessions/{id}`, as the console's Stop does), and `ostrom attach` then says it is terminated. Every state is read back from the server.

The server must run cloud sessions somewhere: ostrom-server's cli-journey.yml gives it a runtime driver (the Docker of
the CI's machine, as its console-journey.yml) and says so with --cloud-sessions; without it, the step is skipped and
says why (failed with --strict, as 2.9 without its fake MCP server).
"""
import json
import os
import re
import shlex
import subprocess
import threading
import time
import urllib.parse

ROUTES = (("POST", "/api/v1/cloud/sessions"), ("GET", "/api/v1/cloud/sessions"),
          ("GET", "/api/v1/cloud/sessions/journey"), ("DELETE", "/api/v1/cloud/sessions/journey"))
STARTED = re.compile(r"in Ostrom's cloud \(session (\S+)\)")
DETACH = b"\x1d"
INTERRUPT = b"\x03"
# what the session's script says when the agent ends (ostrom-server runner/RunnerConfig.kt, SessionScript.FIRST)
AGENT_EXITED = b"The agent exited."
# a session's image is on the machine already; its three containers start in about a minute, the CLI waits up to 10
START_S = 420
OUTPUT_S = 120
STOP_S = 180


class Terminal:
    """A command that takes the terminal: its stdin is ours to type in, its stdout and stderr are read as they come."""

    def __init__(self, journey, maya, *arguments):
        self.journey = journey
        self.maya = maya
        self.shown = "ostrom " + " ".join(shlex.quote(a) for a in arguments)
        self.process = subprocess.Popen([journey.binary, *arguments], cwd=maya["repo"],
                                        env=journey.environment(maya), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE)
        self.output = bytearray()
        self.said = []
        self.readers = [threading.Thread(target=self.read_output, daemon=True),
                        threading.Thread(target=self.read_said, daemon=True)]
        for reader in self.readers:
            reader.start()

    def read_output(self):
        while True:
            chunk = os.read(self.process.stdout.fileno(), 4096)
            if not chunk:
                return
            self.output += chunk

    def read_said(self):
        for line in self.process.stderr:
            self.said.append(line.decode(errors="replace"))

    def wait_until(self, condition, seconds):
        """True once [condition] holds; False when the command ends, or [seconds] pass, before."""
        deadline = time.monotonic() + seconds
        while not condition():
            if self.process.poll() is not None or time.monotonic() > deadline:
                return condition()
            time.sleep(0.2)
        return True

    def type(self, data):
        try:
            self.process.stdin.write(data)
            self.process.stdin.flush()
        except (BrokenPipeError, ValueError):
            pass

    def end(self, seconds=30):
        """Its exit code once it ends (killed after [seconds]), recorded in the step; the code, or None if killed."""
        try:
            code = self.process.wait(timeout=seconds)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
            code = None
        for reader in self.readers:
            reader.join(5)
        out = self.output.decode(errors="replace")
        self.journey.record(self.maya, self.shown, code if code is not None else f"killed after {seconds} s",
                            out[-2000:], "".join(self.said))
        return code

    def stderr(self):
        return "".join(self.said)


class Cloud:
    """6.2, run by the journey once the owner's organisation is set up (2.1) and the developer signed in (5.1)."""

    def __init__(self, journey, maya):
        self.journey = journey
        self.maya = maya
        self.session = None
        self.terminals = []

    def terminal(self, *arguments):
        """A command given the terminal, killed at the end of the step if it is still running."""
        started = Terminal(self.journey, self.maya, *arguments)
        self.terminals.append(started)
        return started

    def run(self):
        j = self.journey
        j.begin("6.2", "ostrom claude --cloud and ostrom attach: a cloud session, detached, attached again, stopped")
        if not j.options.cloud_sessions:
            j.step["missing"] = ["--cloud-sessions (a runtime driver for the server's cloud sessions)"]
            if j.options.strict:
                j.check(False, "the server runs cloud sessions (--cloud-sessions)")
            else:
                j.step["status"] = "skipped"
                print("  skip  the server runs no cloud session here (--cloud-sessions not given)", flush=True)
            return
        if not j.needs(*ROUTES, on_main=True, org=True):
            return
        try:
            if self.started() and self.attached():
                self.stopped()
        finally:
            for left in self.terminals:
                if left.process.poll() is None:
                    left.process.kill()
                    left.process.wait()
            if self.session:
                # whatever happened, the session does not outlive the step (an error here never hides the step's own)
                try:
                    j.call("DELETE", self.path(), token=self.maya["token"], org=j.org_id)
                except OSError as error:
                    print(f"  note  the cloud session {self.session} could not be stopped: {error}", flush=True)

    def path(self):
        return f"/api/v1/cloud/sessions/{urllib.parse.quote(self.session)}"

    def state(self):
        status, body, _ = self.journey.call("GET", self.path(), token=self.maya["token"], org=self.journey.org_id)
        return status, body if isinstance(body, dict) else {}

    def started(self):
        """`ostrom claude --cloud` with the device's token: the session starts, its terminal shows here, Ctrl-]."""
        j = self.journey
        claude = self.terminal("claude", "--cloud")
        named = claude.wait_until(lambda: STARTED.search(claude.stderr()), 60)
        found = STARTED.search(claude.stderr())
        if named and found:
            self.session = found.group(1)
        if not j.check(named, f"ostrom claude --cloud asks Ostrom for a session ({self.session})"):
            claude.type(DETACH)
            claude.end(10)
            return False
        shown = claude.wait_until(lambda: len(claude.output) > 0, START_S)
        j.check(shown, f"it waits until the session runs, then its terminal shows here ({len(claude.output)} bytes): "
                       f"{claude.stderr().strip()[-300:]}")
        status, body = self.state()
        j.check(status == 200 and body.get("status") == "running" and body.get("agent") == "claude",
                f"the server has it running, with Claude Code ({status}: {body.get('status')}, {body.get('agent')})")
        status, listed, _ = j.call("GET", "/api/v1/cloud/sessions", token=self.maya["token"], org=j.org_id)
        items = listed.get("items", []) if isinstance(listed, dict) else []
        j.check(status == 200 and any(s.get("id") == self.session for s in items),
                f"and lists it among the person's cloud sessions ({status}, {len(items)})")
        claude.type(DETACH)
        code = claude.end()
        return j.check(code == 0 and f"Detached: session {self.session} keeps running" in claude.stderr(),
                       f"Ctrl-] detaches, exit 0 ({code}): {claude.stderr().strip()[-300:]}") and shown

    def attached(self):
        """The session ran on after the detach; `ostrom attach` gives it the terminal again, then Ctrl-]."""
        j = self.journey
        status, body = self.state()
        j.check(status == 200 and body.get("status") == "running",
                f"the session keeps running after the detach ({status}: {body.get('status')})")
        attach = self.terminal("attach", self.session)
        shown = attach.wait_until(lambda: len(attach.output) > 0, OUTPUT_S)
        j.check(shown, f"ostrom attach {self.session} shows the session again ({len(attach.output)} bytes)")
        # Ctrl-C twice ends Claude Code; again every few seconds until the session's script says so
        deadline = time.monotonic() + OUTPUT_S
        while AGENT_EXITED not in attach.output and attach.process.poll() is None and time.monotonic() < deadline:
            attach.type(INTERRUPT)
            time.sleep(0.3)
            attach.type(INTERRUPT)
            attach.wait_until(lambda: AGENT_EXITED in attach.output, 5)
        # said, not checked: the proof is the command below, whichever shell takes it
        print(f"  note  Claude Code ended by Ctrl-C: {AGENT_EXITED in attach.output} ({tail(attach.output)})",
              flush=True)
        computed = f"ostrom-{6 * 7}-{j.stamp}".encode()
        typed_at = len(attach.output)
        attach.type(f"echo ostrom-$((6*7))-{j.stamp}\r".encode())
        ran = attach.wait_until(lambda: computed in bytes(attach.output[typed_at:]), OUTPUT_S)
        j.check(ran, f"a command typed through ostrom attach runs in the session's shell ({computed.decode()} came "
                     f"back): {tail(attach.output[typed_at:])}")
        attach.type(DETACH)
        code = attach.end()
        return j.check(code == 0 and f"Detached: session {self.session} keeps running" in attach.stderr(),
                       f"Ctrl-] detaches again, exit 0 ({code}): {attach.stderr().strip()[-300:]}") and shown and ran

    def stopped(self):
        """The owner stops it, as the console's Stop does; `ostrom attach` then says it is over."""
        j = self.journey
        status, body, _ = j.call("DELETE", self.path(), token=self.maya["token"], org=j.org_id)
        j.check(status == 202, f"the owner stops it ({status}): {json.dumps(body)[:200]}")
        deadline = time.monotonic() + STOP_S
        state = None
        while time.monotonic() < deadline:
            state = self.state()[1].get("status")
            if state == "terminated":
                break
            time.sleep(2)
        if not j.check(state == "terminated", f"the server has it terminated ({state})"):
            return
        attach = self.terminal("attach", self.session)
        code = attach.end(60)
        j.check(code == 1 and f"session {self.session} is terminated" in attach.stderr(),
                f"ostrom attach says it is terminated, exit 1 ({code}): {attach.stderr().strip()[-300:]}")
        self.session = None


def tail(output):
    """The end of a terminal's bytes, readable in a check: escape sequences dropped."""
    text = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07", "", bytes(output).decode(errors="replace"))
    return " ".join(text.split())[-200:]

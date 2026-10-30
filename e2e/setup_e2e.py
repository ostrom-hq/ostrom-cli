# SPDX-License-Identifier: Apache-2.0
"""End to end for the setup commands: the real `ostrom` binary runs `init`, `invite`, `members`, `policy` and `harness`
against a small server of the contract, in a real git repository with a fake Claude Code on the PATH.

    python3 e2e/setup_e2e.py --binary cli/build/bin/linuxX64/releaseExecutable/ostrom.kexe

The server checks what the contract asks of each call (the device's token, the `Ostrom-Org` header) and answers with
the contract's shapes. The CLI scenarios against the real ostrom-server live in ostrom-e2e. Exit 0 when every check
holds.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

DEVICE_TOKEN = "ost_e2e_device"
CONSOLE_TOKEN = "otk_e2e_admin"
failures = []
calls = []


def check(condition, what):
    print(("  ok    " if condition else "  FAIL  ") + what)
    if not condition:
        failures.append(what)


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.models = {"allowed": ["sonnet"], "default": "sonnet"}
        self.workflows = [{"id": "w1", "name": "git-safety", "status": "proposed", "version": 1,
                           "nodes": [{"kind": "trigger", "label": "git push"},
                                     {"kind": "gate", "label": "Push to main", "approvers": ["lena"]}]}]
        self.rules = [{"id": "r1", "statement": "No push to main", "status": "proposed", "strength": "required"},
                      {"id": "r2", "statement": "Tests before push", "status": "proposed", "strength": "recommended"}]


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def _answer(self, status, body):
            data = json.dumps(body).encode() if body is not None else b""
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _error(self, status, message):
            self._answer(status, {"error": {"type": "e2e", "message": message}})

        def _handle(self, method):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode() if length else ""
            url = urlsplit(self.path)
            auth = self.headers.get("Authorization", "")
            token = auth[len("Bearer "):] if auth.startswith("Bearer ") else ""
            org = self.headers.get("Ostrom-Org")
            with state.lock:
                calls.append({"method": method, "path": url.path, "query": url.query, "org": org, "body": raw,
                              "console": token == CONSOLE_TOKEN})
                if token not in (DEVICE_TOKEN, CONSOLE_TOKEN):
                    return self._error(401, "sign in")
                if url.path == "/api/v1/orgs":
                    return self._answer(200, {"items": [{"id": "o1", "slug": "acme", "name": "Acme", "role": "admin",
                                                         "createdAt": "2026-10-30T00:00:00Z"}]})
                if org != "o1":
                    return self._error(400, "Ostrom-Org is missing")
                self._route(method, url.path, token, json.loads(raw) if raw else None, parse_qs(url.query))

        def _route(self, method, path, token, body, query):
            route = (method, path)
            # the device's token never holds an organisation's write scopes (S2-04): only a console token writes
            if method != "GET" and token != CONSOLE_TOKEN:
                return self._error(403, "orgs:write is needed")
            if route == ("POST", "/api/v1/orgs/o1/members/invites"):
                return self._answer(201, {"invited": [i["email"].lower() for i in body["invites"]]})
            if route == ("GET", "/api/v1/orgs/o1/members"):
                return self._answer(200, {"items": [{"login": "maya", "name": "Maya", "role": "admin",
                                                     "joinedAt": "2026-10-01T09:00:00Z"}]})
            if route == ("GET", "/api/v1/repos"):
                return self._answer(200, {"items": [{"name": "acme/acme-api"}]})
            if route == ("GET", "/api/v1/workflows"):
                return self._answer(200, {"items": state.workflows})
            if route == ("POST", "/api/v1/workflows"):
                saved = dict(body, id=f"w{len(state.workflows) + 1}", version=1)
                state.workflows.append(saved)
                return self._answer(201, saved)
            if path.startswith("/api/v1/workflows/"):
                return self._workflow(method, path.rsplit("/", 1)[1], body)
            if route == ("GET", "/api/v1/rules"):
                status = query.get("status", [None])[0]
                return self._answer(200, {"items": [r for r in state.rules if status in (None, r["status"])]})
            if route == ("POST", "/api/v1/rules/bulk"):
                for rule in state.rules:
                    if rule["id"] in body["ids"]:
                        rule["status"] = body["status"]
                return self._answer(200, {"updated": len(body["ids"])})
            if route == ("GET", "/api/v1/policy/models"):
                return self._answer(200, state.models)
            if route == ("PUT", "/api/v1/policy/models"):
                state.models = body
                return self._answer(200, body)
            if route == ("GET", "/api/v1/policy/agents"):
                agent = query.get("agent", [""])[0]
                if agent == "claude-code":
                    return self._answer(200, {"allowed": True, "note": "e2e policy"})
                return self._answer(200, {"allowed": False, "reason": f"{agent} is not allowed in Acme"})
            return self._error(404, f"no route {method} {path}")

        def _workflow(self, method, workflow_id, body):
            found = next((w for w in state.workflows if w["id"] == workflow_id), None)
            if found is None:
                return self._error(404, "no such workflow")
            if method == "GET":
                return self._answer(200, found)
            if method == "PUT":
                if body.get("expectedVersion") != found["version"]:
                    return self._error(409, "the workflow changed")
                found.clear()
                found.update(body["workflow"], version=body["expectedVersion"] + 1)
                return self._answer(200, found)
            return self._error(405, "method")

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

        def do_PUT(self):
            self._handle("PUT")

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    binary = os.path.abspath(parser.parse_args().binary)
    state = State()
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_address[1]}"
    with tempfile.TemporaryDirectory() as work:
        home = os.path.join(work, "ostrom-home")
        os.makedirs(home)
        with open(os.path.join(home, "credentials.json"), "w") as handle:
            json.dump({url: {"token": DEVICE_TOKEN, "login": "maya"}}, handle)
        agents = os.path.join(work, "agents")
        os.makedirs(agents)
        with open(os.path.join(agents, "claude"), "w") as handle:
            handle.write("#!/bin/sh\nexit 0\n")
        os.chmod(os.path.join(agents, "claude"), 0o755)
        repo = os.path.join(work, "acme-api")
        subprocess.run(["git", "init", "-q", repo], check=True)
        base = {"PATH": agents + os.pathsep + os.environ["PATH"], "HOME": work, "OSTROM_HOME": home,
                "OSTROM_URL": url, "SHELL": "/bin/bash", "TMPDIR": work}

        def ostrom(*arguments, extra=None, cwd=repo):
            done = subprocess.run([binary, *arguments], cwd=cwd, env={**base, **(extra or {})},
                                  capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
            return done.returncode, done.stdout, done.stderr

        print("ostrom init")
        admin = {"OSTROM_TOKEN": CONSOLE_TOKEN}
        code, out, err = ostrom("init", "--yes")
        check(code == 0, f"init --yes sets up the repository (exit {code}: {err.strip()})")
        check("Accept them on the console" in out, "with the device's token, init says where rules are accepted")
        check(all(r["status"] == "proposed" for r in state.rules), "and accepts none")
        check("✓ Acme (acme)" in out and "acme-api · covered" in out, "init links Acme and finds the repository")
        check("git-safety (proposed)" in out, "init shows the harness of the repository")
        config = open(os.path.join(home, "config.toml")).read()
        check('org = "acme"' in config, "init keeps the organisation in config.toml")
        bashrc = open(os.path.join(work, ".bashrc")).read()
        check("alias claude='ostrom claude'" in bashrc, "init aliases the agent it found to ostrom")
        code, out, err = ostrom("init", "--yes", extra=admin)
        check(code == 0 and "✓ 2 rules accepted" in out, f"with a console token, init accepts them ({err.strip()})")
        check(all(r["status"] == "accepted" for r in state.rules), "the server has them accepted")
        check(sorted(os.listdir(repo)) == [".git"], "the repository holds only its .git")
        code, out, _ = ostrom("init")
        check(code == 0 and "aliases in" in out and "none proposed yet" in out, "a second init sees it all done")
        check(open(os.path.join(work, ".bashrc")).read() == bashrc, "a second init leaves the aliases as they are")
        code, _, err = ostrom("init", extra={"OSTROM_LAUNCH_ID": "L-e2e"})
        check(code == 64 and "agent's session" in err, "init does not run inside an agent's session")

        print("ostrom invite and members")
        code, _, err = ostrom("invite", "lena@acme.dev")
        check(code == 77 and "OSTROM_TOKEN" in err, "the device's token cannot invite, and the CLI says where to")
        code, out, err = ostrom("invite", "lena@acme.dev", "--role", "approver", extra={"OSTROM_TOKEN": CONSOLE_TOKEN})
        check(code == 0 and "Invited lena@acme.dev to Acme as approver" in out, f"a console token invites ({err})")
        check(CONSOLE_TOKEN not in out + err, "the token is never printed")
        code, _, _ = ostrom("invite", "lena")
        check(code == 64, "an invite without an e-mail address is a usage error")
        code, out, _ = ostrom("members", "list", "--json")
        check(code == 0 and json.loads(out)["items"][0]["login"] == "maya", "members list --json is the server's")

        print("ostrom policy")
        code, out, _ = ostrom("policy", "show")
        check(code == 0 and "sonnet · default sonnet" in out, "policy show reads the models")
        check("Self-approval  not on this server yet" in out, "a route not served is said, not failed")
        code, out, _ = ostrom("policy", "set", "models", "sonnet,opus", extra=admin)
        check(code == 0 and state.models["allowed"] == ["sonnet", "opus"], "policy set models saves the models")
        check(state.models["default"] == "sonnet", "the default is kept")
        code, out, _ = ostrom("policy", "check", "claude")
        check(code == 0 and "may start Claude Code" in out, "policy check says what ostrom claude is told")
        code, _, err = ostrom("policy", "check", "codex")
        check(code == 77 and "codex is not allowed in Acme" in err, "a refused agent is 77 with the reason")
        code, out, _ = ostrom("policy", "rules", "list", "--status", "accepted")
        check(code == 0 and "2 rules in Acme" in out, "policy rules list reads the rules")

        print("ostrom harness")
        with open(os.path.join(work, "deploy.json"), "w") as handle:
            json.dump({"name": "deploy", "nodes": [{"kind": "gate", "label": "Deploy"}]}, handle)
        code, out, err = ostrom("harness", "add", os.path.join(work, "deploy.json"), "--yes", extra=admin)
        check(code == 0 and "✓ Added deploy (w2), on trial" in out, f"harness add proposes a workflow ({err})")
        check(state.workflows[-1]["status"] == "proposed", "on trial until enforced")
        code, out, _ = ostrom("harness", "enforce", "w2", extra=admin)
        check(code == 0 and state.workflows[-1]["status"] == "accepted", "harness enforce ends the trial")
        check(state.workflows[-1]["version"] == 2, "at the version read")
        code, out, _ = ostrom("harness", "list", "--all")
        check(code == 0 and "deploy  v2  enforced  1 step  1 approval  w2" in out, "harness list shows it enforced")
        code, _, _ = ostrom("harness", "remove", "w2")
        check(code == 64, "harness remove without a terminal asks for --yes")

        check(all(c["org"] == "o1" for c in calls if c["path"] != "/api/v1/orgs"), "every call names the organisation")
    server.shutdown()
    if failures:
        print(f"{len(failures)} check(s) failed")
        return 1
    print("every check holds")
    return 0


if __name__ == "__main__":
    sys.exit(main())

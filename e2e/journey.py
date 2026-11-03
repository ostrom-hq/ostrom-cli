# SPDX-License-Identifier: Apache-2.0
"""The customer's journey on the CLI side, against a REAL Ostrom server (demo/PARCOURS.md, in its order):

    5.1 ostrom login (the device flow, approved from a browser session)  2.1 ostrom init
    2.6 ostrom invite, 2.7 the invitation accepted, ostrom members        3.4 and 3.3 ostrom policy
    3.1 ostrom policy rules                                               3.2 and 4.1 ostrom harness
    5.2 ostrom claude (a fake Claude Code plays the hooks)                5.3 the deployment it tries is held
    5.6 the asker's own yes refused, 5.4 the second person's yes          5.5 ostrom wait lets it through
    5.7 the signed receipt, pulled and verified                         6.1 ostrom logs
    2.9 ostrom mcp: a server of the organisation, called through the session's relay

    python3 e2e/journey.py --binary ostrom --server http://127.0.0.1:4600 --mail-dir /tmp/mail \\
        --seed "docker run --rm --network host --env-file server.env ghcr.io/ostrom-hq/ostrom-server:edge \\
                seed-test-account"

Nothing is faked on the server side: people sign in by their e-mailed link (read from the server's OSTROM_MAIL_DIR),
the owner is made by `ostrom-server seed-test-account` (its personal token stands for a token made on the console,
OSTROM_TOKEN), and every command is the real binary. A step whose route the server does not serve yet (its router's
generic 404) is skipped with the route named, so the step switches itself on the day the route lands on main;
--strict makes such a skip a failure. The routes already on main fail the run when they are missing.

For the film (DEMO-LOCAL): --show prints each command as typed and what it printed, --pace waits between steps, and
--report writes every step, its commands and its outcome as JSON. Tokens are never printed: they travel in the
environment of the commands, never in their arguments.
"""
import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request

from mcp_relay import McpRelay

HERE = os.path.dirname(os.path.abspath(__file__))
GENERIC_404 = "Not found."
MAIL_WAIT_S = 20


class Journey:
    def __init__(self, options):
        self.options = options
        self.server = options.server.rstrip("/")
        self.binary = os.path.abspath(options.binary)
        self.steps = []
        self.step = None
        self.work = options.work or tempfile.mkdtemp(prefix="ostrom-journey-")
        self.stamp = f"{int(time.time()):x}{os.getpid() % 4096:03x}"
        self.org_resolved = False
        self.invitee = None
        self.owner = None
        self.org_id = None
        self.admin_token = None
        self.held_writes = None
        self.owner_session = None
        self.temporary = options.work is None

    # ---- what the run says -------------------------------------------------------------------------------------

    def begin(self, number, title):
        self.step = {"step": number, "title": title, "status": "ok", "checks": [], "commands": [], "missing": []}
        self.steps.append(self.step)
        print(f"\n{number}  {title}", flush=True)
        if self.options.pace:
            time.sleep(self.options.pace)

    def check(self, condition, what):
        self.step["checks"].append({"ok": bool(condition), "what": what})
        print(("  ok    " if condition else "  FAIL  ") + what, flush=True)
        if not condition:
            self.step["status"] = "failed"
        return bool(condition)

    def guarded(self, act, *arguments):
        """Runs [act]; an error fails the step and the journey goes on (the steps after it still clean up)."""
        try:
            act(*arguments)
        except Exception as error:  # noqa: BLE001 - said in the step, the run goes on
            traceback.print_exc()
            self.check(False, f"the step stopped: {type(error).__name__}: {str(error)[:200]}")

    def needs(self, *routes, on_main=False, org=False):
        """
        True when the server serves every route (and, with [org], resolves the organisation of a token bound to none
        from `Ostrom-Org`); else the step is skipped, or failed when a route of main is missing, or with --strict.
        """
        routes_missing = [f"{method} {path}" for method, path in routes if not self.served(method, path)]
        missing = routes_missing + ([ORG_HEADER] if org and not self.org_resolved else [])
        if not missing:
            return True
        self.step["missing"] = missing
        if (on_main and routes_missing) or self.options.strict:
            self.check(False, f"the server serves {', '.join(missing)}")
        else:
            self.step["status"] = "skipped"
            print(f"  skip  waits for: {', '.join(missing)}", flush=True)
        return False

    # ---- the server ----------------------------------------------------------------------------------------------

    def call(self, method, path, body=None, token=None, session=None, org=None, cookie=None):
        headers = {"accept": "application/json"}
        if cookie:
            headers["cookie"] = cookie
        if org:
            headers["ostrom-org"] = org
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["content-type"] = "application/json"
        if token:
            headers["authorization"] = f"Bearer {token}"
        if session:
            headers["cookie"] = session["cookie"]
            headers["origin"] = self.server
            if method not in ("GET", "HEAD"):
                headers["x-csrf-token"] = session["csrf"]
        request = urllib.request.Request(self.server + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, read_json(response.read()), response.headers
        except urllib.error.HTTPError as error:
            return error.code, read_json(error.read()), error.headers

    def served(self, method, path):
        """False on the router's generic 404 only: a 401, a 405 or a domain 404 mean the route is there."""
        status, body, _ = self.call(method, path, {} if method not in ("GET", "HEAD", "DELETE") else None)
        if status != 404:
            return True
        return error_of(body) != GENERIC_404

    def mail_token(self, email, page, since):
        """The token of the newest mail to [email] whose link opens [page] (`/auth/email`, `/invite`)."""
        pattern = re.compile(re.escape(page) + r"#token=([A-Za-z0-9._~%-]+)")
        deadline = time.time() + MAIL_WAIT_S
        while time.time() < deadline:
            found = []
            for name in os.listdir(self.options.mail_dir):
                path = os.path.join(self.options.mail_dir, name)
                if not os.path.isfile(path) or os.path.getmtime(path) < since - 1:
                    continue
                with open(path, encoding="utf-8", errors="replace") as handle:
                    text = handle.read()
                head = text.split("\n\n", 1)[0].lower().splitlines()
                to = any(line.startswith("to:") and email.lower() in line for line in head)
                match = pattern.search(text + "\n")
                if to and match:
                    found.append((os.path.getmtime(path), urllib.parse.unquote(match.group(1))))
            if found:
                return max(found)[1]
            time.sleep(0.5)
        return None

    def sign_in(self, email):
        """A browser session of [email], by the link the server e-mailed: its cookie and CSRF token."""
        since = time.time()
        status, _, asked = self.call("POST", "/api/v1/auth/email", {"email": email})
        if not self.check(status < 300, f"a sign-in link is e-mailed to {who(email)} ({status})"):
            return None
        # one browser: the link opens only where it was asked for, by the cookie POST /auth/email sets
        # (ostrom_email_link, server#176; without it, 403 email_link_other_browser)
        jar = cookies(asked)
        token = self.mail_token(email, "/auth/email", since)
        if not self.check(token is not None, f"the link reached {self.options.mail_dir}"):
            return None
        status, body, headers = self.call("POST", "/api/v1/auth/email/verify", {"token": token}, cookie=jar)
        cookie = cookies(headers)
        csrf = body.get("csrfToken") if isinstance(body, dict) else None
        if not self.check(status == 200 and cookie and csrf, f"{who(email)} has a browser session ({status})"):
            return None
        return {"cookie": cookie, "csrf": csrf}

    def seed(self, email):
        """The owner: `ostrom-server seed-test-account <email>`, or the account of --seeded."""
        if self.options.seeded:
            with open(self.options.seeded) as handle:
                return json.loads(handle.read().strip().splitlines()[-1])
        done = subprocess.run(shlex.split(self.options.seed) + [email], capture_output=True, text=True, timeout=120)
        lines = [line for line in done.stdout.splitlines() if line.startswith("{")]
        if done.returncode != 0 or not lines:
            # the seed's own error, never its output (it holds the token)
            sys.exit(f"seed-test-account failed ({done.returncode}): {done.stderr.strip()[-500:]}")
        return json.loads(lines[-1])

    # ---- the CLI -------------------------------------------------------------------------------------------------

    def machine(self, name):
        """A developer's machine: a home, Ostrom's folder, a bash, and a git repository acme-api."""
        home = os.path.join(self.work, name)
        repo = os.path.join(home, "acme-api")
        os.makedirs(repo, exist_ok=True)
        subprocess.run(["git", "init", "-q", repo], check=True)
        return {"name": name, "home": home, "ostrom": os.path.join(home, ".ostrom"), "repo": repo, "token": None}

    def environment(self, machine, console=False, extra=None):
        environment = {
            "PATH": os.environ["PATH"], "HOME": machine["home"], "OSTROM_HOME": machine["ostrom"],
            "SHELL": "/bin/bash", "TMPDIR": self.work, "LANG": "C.UTF-8", "OSTROM_URL": self.server,
        }
        if console:
            # a token made on the console (here: the seeded owner's personal token), never the device's
            environment["OSTROM_TOKEN"] = machine["token"]
        return {**environment, **(extra or {})}

    def ostrom(self, machine, *arguments, console=False, extra=None, cwd=None, timeout=120):
        shown = "ostrom " + " ".join(shlex.quote(a) for a in arguments)
        prefix = "OSTROM_TOKEN=… " if console else ""
        try:
            done = subprocess.run([self.binary, *arguments], cwd=cwd or machine["repo"], capture_output=True,
                                  text=True, env=self.environment(machine, console, extra), stdin=subprocess.DEVNULL,
                                  timeout=timeout)
        except subprocess.TimeoutExpired as late:
            # what it printed before the timeout is the report's only trace of a command that does not return
            out, err = [part.decode(errors="replace") if isinstance(part, bytes) else part or ""
                        for part in (late.stdout, late.stderr)]
            self.record(machine, prefix + shown, f"still running after {timeout} s", out, err)
            raise
        self.record(machine, prefix + shown, done.returncode, done.stdout, done.stderr)
        return done

    def record(self, machine, command, code, out, err):
        """A command in the step's report, and on the console with --show."""
        self.step["commands"].append({"who": machine["name"], "command": command, "exit": code, "stdout": out,
                                      "stderr": err})
        if self.options.show:
            print(f"  {machine['name']}$ {command}")
            for line in (out + err).rstrip().splitlines():
                print(f"    {line}")
            print(f"    (exit {code})", flush=True)

    def login(self, machine, email, admin=False):
        """`ostrom login` (`--admin`: the governance token): the code it shows is approved from [email]'s browser
        session, as on the console; a fresh one, as approving an admin token takes a sign-in of less than 5 minutes."""
        arguments = ["login", "--server", self.server] + (["--admin"] if admin else [])
        process = subprocess.Popen([self.binary, *arguments], cwd=machine["repo"],
                                   env=self.environment(machine), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        said = []
        code = threading.Event()
        reader = threading.Thread(target=lambda: [said.append(line) or (CODE.search(line) and code.set())
                                                  for line in process.stderr], daemon=True)
        reader.start()
        if not self.check(code.wait(30), f"ostrom {' '.join(arguments[:1] + arguments[3:])} shows a one-time code"):
            process.kill()
            return False
        user_code = next(CODE.search(line).group(1) for line in said if CODE.search(line))
        session = self.sign_in(email)
        if session is None:
            process.kill()
            return False
        # an admin token is bound to the organisation it is approved in
        org = self.org_id if admin else None
        status, body, _ = self.call("GET", f"/api/v1/auth/device?userCode={user_code}", session=session, org=org)
        self.check(status == 200 and body.get("userCode") == user_code, f"the console finds the request ({status})")
        status, body, _ = self.call("POST", "/api/v1/auth/device/answer", {"userCode": user_code, "approve": True},
                                    session=session, org=org)
        self.check(status in (200, 204), f"{who(email)} says yes on the console ({status}): {error_of(body)[:200]}")
        # stderr is the reader's alone; stdout (two short lines) is read once the login has ended
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        out = process.stdout.read()
        reader.join(5)
        shown = "ostrom " + " ".join(arguments)
        self.step["commands"].append({"who": machine["name"], "command": shown, "exit": process.returncode,
                                      "stdout": out, "stderr": "".join(said)})
        if self.options.show:
            print(f"  {machine['name']}$ {shown}")
            for line in ("".join(said) + out).rstrip().splitlines():
                print(f"    {line}")
        signed = "Admin token for @" if admin else "Signed in as @"
        self.check(process.returncode == 0 and signed in out, f"{shown} exits 0 ({process.returncode})")
        return process.returncode == 0

    # ---- the journey ---------------------------------------------------------------------------------------------

    def run(self):
        owner_email = f"owner-{self.stamp}@e2e.ostrom.test"
        tom_email = f"tom-{self.stamp}@e2e.ostrom.test"
        maya = self.machine("maya")

        self.begin("5.1", "ostrom login: the device flow, approved on the console")
        if not self.needs(("POST", "/api/v1/auth/device"), ("POST", "/api/v1/auth/device/token"),
                          ("POST", "/api/v1/auth/device/answer"), ("POST", "/api/v1/auth/email"),
                          ("POST", "/api/v1/auth/email/verify"), on_main=True):
            return
        seeded = self.seed(owner_email)
        owner_email = seeded.get("email", owner_email)
        maya["email"] = owner_email
        maya["token"] = seeded["token"]
        if not self.login(maya, owner_email):
            return
        orgs = self.call("GET", "/api/v1/orgs", token=maya["token"])[1]
        org = next((o for o in (orgs or {}).get("items", []) if o.get("id") == seeded.get("organizationId")), None)
        if not self.check(org is not None, "the owner's organisation is listed"):
            return
        self.org_id = org["id"]
        # A token made on the console is bound to no organisation: the server reads which one from Ostrom-Org.
        status, body, _ = self.call("GET", "/api/v1/workflows", token=maya["token"], org=org["id"])
        self.org_resolved = 200 <= status < 300
        if not self.org_resolved:
            print(f"  note  GET /api/v1/workflows with Ostrom-Org answers {status} \"{error_of(body)}\": "
                  f"{ORG_HEADER}", flush=True)

        self.begin("2.1", "ostrom init: the organisation, the repository, the agents' hooks, the starting rules")
        if self.needs(("GET", "/api/v1/orgs"), ("GET", "/api/v1/workflows"), on_main=True, org=True):
            self.init(maya, org)
        self.begin("5.1 status", "ostrom status: who, in which organisation, this repository, this device")
        if self.needs(("GET", "/api/v1/auth/me"), ("GET", "/api/v1/orgs"), on_main=True):
            self.guarded(self.status, maya, org)
        self.team(maya, tom_email, org)

    def status(self, maya, org):
        # who is signed in, as the server says it: not read back from what status printed
        me = self.call("GET", "/api/v1/auth/me", token=maya["token"])[1]
        me = me if isinstance(me, dict) else {}
        login = (me.get("dev") or {}).get("login") or me.get("login")
        done = self.ostrom(maya, "status", "--json")
        said = read_json(done.stdout)
        said = said if isinstance(said, dict) else {}
        self.check(done.returncode == 0, f"ostrom status exits 0 ({done.returncode}): {done.stderr.strip()[:300]}")
        self.check(login and said.get("login") == login, f"it says who is signed in (@{said.get('login')}, @{login})")
        self.check(org.get("role") and said.get("role") == org.get("role"),
                   f"with the role the organisation lists ({said.get('role')}, {org.get('role')})")
        self.check(said.get("organization") == org.get("name"),
                   f"in the owner's organisation ({said.get('organization')})")
        self.check(said.get("repository") == "acme-api", f"in this repository ({said.get('repository')})")
        self.check(said.get("server") == self.server, f"on this server ({said.get('server')})")
        self.check(bool(said.get("version")), f"with this version ({said.get('version')})")
        missing = sorted(STATUS_KEYS - said.keys())
        self.check(not missing, f"with every key, null when unknown (missing: {missing})")
        text = self.ostrom(maya, "status")
        self.check(text.returncode == 0 and text.stdout.startswith(f"Signed in   @{login}"),
                   f"and says it in words ({text.returncode}): {text.stdout.strip()[:200]}")

    def init(self, maya, org):
        done = self.ostrom(maya, "init", "--yes", "--json")
        summary = (read_json(done.stdout) if done.returncode == 0 else None) or {}
        self.check(done.returncode == 0, f"ostrom init exits 0 ({done.returncode}): {done.stderr.strip()[:300]}")
        self.check((summary.get("org") or {}).get("id") == org["id"], "init links the owner's organisation")
        self.check(summary.get("repo") == "acme-api", "init finds the repository")
        if summary.get("hooks") is not None:
            bashrc = os.path.join(maya["home"], ".bashrc")
            written = os.path.exists(bashrc) and "# >>> ostrom:" in open(bashrc, encoding="utf-8").read()
            self.check(written, "the agents start with Ostrom's hooks (~/.bashrc)")
        # init again, as the owner who governs: ostrom login --admin first (the governance token, approved on the
        # console), then init with the device's token, which reads the rules
        self.admin_signed_in(maya)
        again = self.ostrom(maya, "init", "--yes")
        self.check(again.returncode == 0 and "Rules" in again.stdout,
                   f"init again exits 0 and says the rules ({again.returncode}): "
                   f"{(again.stdout + again.stderr).strip()[-300:]}")
        # the personal token holds no rules:read: init says so and the rest is done, exit 0 (cli#83)
        personal = self.ostrom(maya, "init", "--yes", console=True)
        self.check(personal.returncode == 0 and "Rules       this token cannot read them" in personal.stdout,
                   f"init with a personal token exits 0 and says it cannot read the rules ({personal.returncode}): "
                   f"{(personal.stdout + personal.stderr).strip()[-300:]}")

    def team(self, maya, tom_email, org):
        """2.6, 2.7, then the policy, the rules, the harness and the launch."""
        self.begin("2.6", "ostrom invite and ostrom members")
        members = f"/api/v1/orgs/{org['id']}/members"
        if self.needs(("POST", f"{members}/invites"), ("GET", members), on_main=True):
            device = self.ostrom(maya, "invite", tom_email)
            self.check(device.returncode == 77 and "console" in device.stderr,
                       f"the device's token cannot invite: the console can ({device.returncode})")
            since = time.time()
            done = self.ostrom(maya, "invite", tom_email, "--role", "member", console=True)
            self.check(done.returncode == 0, f"a console token invites {who(tom_email)} ({done.returncode}): "
                                             f"{done.stderr.strip()[:300]}")
            self.begin("2.7", "the invitation, accepted by the person invited")
            if self.needs(("POST", "/api/v1/invitations/accept"), on_main=True):
                token = self.mail_token(tom_email, "/invite", since)
                session = self.check(token is not None, f"the invitation reached {who(tom_email)}") and \
                    self.sign_in(tom_email)
                if session:
                    status, body, _ = self.call("POST", "/api/v1/invitations/accept", {"token": token}, session=session)
                    self.check(status in (200, 201, 204), f"{who(tom_email)} accepts ({status}): {str(body)[:200]}")
            self.begin("2.6", "ostrom members: the team, a role changed")
            done = self.ostrom(maya, "members", "list", "--json")
            members = (read_json(done.stdout) or {}).get("items", [])
            self.check(done.returncode == 0 and len(members) == 2, f"members list shows the two of them "
                                                                   f"({done.returncode}, {len(members)})")
            tom_login = next((m.get("login") for m in members if m.get("role") != "owner"), None)
            self.owner = next((m.get("login") for m in members if m.get("role") == "owner"), None)
            self.invitee = tom_login
            text = self.ostrom(maya, "members", "list")
            self.check(text.returncode == 0 and "2 members" in text.stdout and f"@{tom_login}" in text.stdout,
                       f"and says it in words ({text.returncode}): {text.stdout.strip()[:200]}")
            if tom_login:
                device = self.ostrom(maya, "members", "role", tom_login, "admin")
                self.check(device.returncode == 77 and "console" in device.stderr,
                           f"the device's token cannot change a role: the console can ({device.returncode})")
                done = self.ostrom(maya, "members", "role", tom_login, "admin", console=True)
                self.check(done.returncode == 0 and f"@{tom_login} is now admin" in done.stdout,
                           f"a console token makes @{tom_login} admin ({done.returncode}): "
                           f"{done.stderr.strip()[:300]}")
                # as the server says it, not as the CLI printed it
                self.check(self.role_of(maya, org, tom_login) == "admin",
                           f"the server lists @{tom_login} as admin ({self.role_of(maya, org, tom_login)})")

        self.begin("3.4", "ostrom policy: the models and who may start which agent")
        if self.needs(("GET", "/api/v1/policy/models"), org=True):
            # the model policy is an admin's read (inference:read is not among the scopes of ostrom login's token)
            shown = self.ostrom(maya, "policy", "show", "--json", console=True)
            self.check(shown.returncode == 0, f"policy show exits 0 ({shown.returncode}): {shown.stderr.strip()[:300]}")
            allowed = ((read_json(shown.stdout) or {}).get("models") or {}).get("allowed") or []
            models = ",".join(allowed or ["sonnet"])
            # a personal token (OSTROM_TOKEN) never governs: the policy takes ostrom login --admin
            refused = self.ostrom(maya, "policy", "set", "models", models, console=True)
            self.check(refused.returncode == 77, f"a personal token cannot set the models ({refused.returncode})")
            if self.admin_signed_in(maya):
                done = self.governed(maya, "policy", "set", "models", models)
                self.check(done == 0, f"policy set models, with the admin token ({done})")
        self.begin("3.4", "ostrom policy check: what ostrom claude is told")
        if self.needs(("GET", "/api/v1/policy/agents"), org=True):
            done = self.ostrom(maya, "policy", "check", "claude")
            self.check(done.returncode in (0, 77), f"policy check answers yes or no ({done.returncode}): "
                                                   f"{(done.stdout + done.stderr).strip()[:300]}")
        self.begin("3.3", "ostrom policy set self-approval")
        if self.needs(("GET", "/api/v1/gates/policy"), ("PUT", "/api/v1/gates/policy"), org=True):
            refused = self.ostrom(maya, "policy", "set", "self-approval", "never", console=True)
            self.check(refused.returncode == 77, f"a personal token cannot set self-approval ({refused.returncode})")
            if self.admin_signed_in(maya):
                done = self.governed(maya, "policy", "set", "self-approval", "never")
                self.check(done == 0, f"self-approval never, with the admin token ({done})")
                if self.holds():
                    # refused on the console: nothing is written, the CLI says so (77)
                    done = self.governed(maya, "policy", "set", "self-approval", "sole-approver", answer="refuse")
                    self.check(done == 77, f"a change refused on the console exits 77 ({done})")
                # 5.6 relies on it
                shown = self.ostrom(maya, "policy", "show", "--json", console=True)
                gates = (read_json(shown.stdout) or {}).get("gates") or {}
                self.check(gates.get("selfApproval") == "never", f"self-approval is never ({gates})")

        self.begin("3.1", "ostrom policy rules: what the analysis proposed")
        if self.needs(("GET", "/api/v1/rules"), org=True):
            done = self.ostrom(maya, "policy", "rules", "list", "--json")
            self.check(done.returncode == 0, f"rules list exits 0 ({done.returncode}): {done.stderr.strip()[:300]}")

        self.begin("3.2", "ostrom harness: a workflow added on trial, enforced, shown, removed")
        if self.needs(("GET", "/api/v1/workflows"), ("POST", "/api/v1/workflows"), on_main=True, org=True):
            self.harness(maya)

        gated = None
        if self.invitee and self.owner:
            self.begin("5.3 setup", "a workflow gates the deployment: the two of them its approvers")
            if self.needs(("POST", "/api/v1/workflows"), on_main=True, org=True):
                gated = self.gate_workflow(maya)

        self.begin("5.2", "ostrom claude: the launch, the session, the hooks")
        if self.needs(*SESSION_ROUTES, org=True):
            if gated:
                self.gated_session(maya, tom_email)
            else:
                self.claude_ended(self.claude(maya))
        if gated:
            self.begin("5.3 cleanup", "the gating workflow, removed on the console")
            status, body, _ = self.call("DELETE", f"/api/v1/workflows/{gated}", session=self.owner_signed_in(maya),
                                        org=self.org_id)
            if status == 409 and "workflow_in_use" in json.dumps(body):
                # the session's package pinned it: the server keeps a compiled workflow, and the stamp keeps runs apart
                print(f"  note  the gating workflow stays, pinned by the session's package: {error_of(body)[:200]}",
                      flush=True)
            else:
                self.check(status in (200, 204), f"the owner removes it ({status}): {error_of(body)[:200]}")

        self.begin("6.1", "ostrom logs: the thread of the session that just ran")
        # the session read is the one ostrom claude just ran: the routes of 5.2 too
        if self.needs(*SESSION_ROUTES, ("GET", "/api/v1/sessions"), org=True):
            self.guarded(self.logs, maya, gated)

        # a session of its own, after 6.1 (which reads the developer's last session, the one of 5.2)
        self.guarded(McpRelay(self, maya, SESSION_ROUTES).run)

        if self.invitee:
            self.begin("2.6", "ostrom members remove")
            device = self.ostrom(maya, "members", "remove", self.invitee, "--yes")
            self.check(device.returncode == 77 and "console" in device.stderr,
                       f"the device's token cannot remove a member: the console can ({device.returncode})")
            done = self.ostrom(maya, "members", "remove", self.invitee, "--yes", console=True)
            self.check(done.returncode == 0, f"a console token removes @{self.invitee} ({done.returncode}): "
                                             f"{done.stderr.strip()[:300]}")
            self.check(self.role_of(maya, org, self.invitee) is None,
                       f"the server no longer lists @{self.invitee}")
            listed = (read_json(self.ostrom(maya, "members", "list", "--json").stdout) or {}).get("items", [])
            self.check(len(listed) == 1, f"members list shows the owner alone ({len(listed)})")

    def role_of(self, maya, org, login):
        """[login]'s role in [org], as `GET /orgs/{org}/members` lists it to the console's token; None if not listed."""
        status, body, _ = self.call("GET", f"/api/v1/orgs/{org['id']}/members", token=maya["token"], org=org["id"])
        items = body.get("items", []) if status == 200 and isinstance(body, dict) else []
        return next((m.get("role") for m in items if m.get("login") == login), None)

    def logs(self, maya, gated):
        last = self.ostrom(maya, "logs", "--json")
        thread = read_json(last.stdout) or {}
        session = thread.get("session") or {}
        if not self.check(last.returncode == 0 and session.get("id"),
                          f"ostrom logs --json reads the developer's last session ({last.returncode}): "
                          f"{(last.stderr or last.stdout).strip()[:300]}"):
            return
        gates = thread.get("gates") or []
        if gated:
            self.check(len(gates) > 0, f"with the request for a yes it asked ({len(gates)})")
        # first, as it depends on nothing below
        gone = self.ostrom(maya, "logs", f"ses_none_{self.stamp}")
        self.check(gone.returncode == 66, f"a session that does not exist is 66 ({gone.returncode})")
        shown = self.ostrom(maya, "logs", session["id"])
        lines = shown.stdout.splitlines()
        self.check(shown.returncode == 0 and lines[:1] and lines[0].startswith(f"Session {session['id']}"),
                   f"ostrom logs {session['id']} heads its thread ({shown.returncode}): {shown.stderr.strip()[:300]}")
        self.check(any("session started" in line for line in lines), "from its start")
        if gated:
            self.check(any("asked for a yes" in line for line in lines), "through the request for a yes")
            if not any(gate.get("status") for gate in gates if isinstance(gate, dict)):
                # a gate the device's token may not read is shown bare: its answer is then not on the thread
                print(f"  note  no gate of the thread could be read: {gates[:3]}", flush=True)
        # ostrom claude has returned: the session is over, and -f on it must print and return, not wait
        if not self.check(session.get("endedAt") and any("session ended" in line for line in lines),
                          f"to its end ({session.get('endedAt')})"):
            return
        try:
            followed = self.ostrom(maya, "logs", session["id"], "-f", timeout=60)
        except subprocess.TimeoutExpired:
            self.check(False, "-f on an ended session returns (it was still waiting after 60 s)")
            return
        tail = followed.stdout.splitlines()
        self.check(followed.returncode == 0 and tail[:1] == lines[:1] and any("session ended" in t for t in tail),
                   f"-f on an ended session prints its thread to the end and returns ({followed.returncode})")

    def harness(self, maya):
        name = f"deploy-{self.stamp}"
        document = {
            "id": name, "name": name, "domain": "deploy", "status": "proposed", "foundAt": "2026-10-30T00:00:00Z",
            "start": "push",
            "nodes": [{"id": "push", "kind": "trigger", "label": "git push on main"},
                      {"id": "yes", "kind": "gate", "label": "A second person says yes"}],
            "edges": [{"from": "push", "to": "yes"}, {"from": "yes", "to": None}],
        }
        path = os.path.join(maya["home"], f"{name}.json")
        with open(path, "w") as handle:
            json.dump(document, handle)
        device = self.ostrom(maya, "harness", "add", path, "--yes")
        self.check(device.returncode == 77, f"the device's token cannot add a workflow ({device.returncode})")
        done = self.ostrom(maya, "harness", "add", path, "--yes", console=True)
        if not self.check(done.returncode == 0, f"harness add, on trial ({done.returncode}): "
                                                f"{done.stderr.strip()[:300]}"):
            return
        listed = self.ostrom(maya, "harness", "list", "--all", "--json")
        items = (read_json(listed.stdout) or {}).get("items", [])
        workflow = next((w for w in items if w.get("name") == name), None)
        if not self.check(workflow is not None and workflow.get("status") == "proposed",
                          f"harness list shows it on trial ({listed.returncode})"):
            return
        # a personal token (OSTROM_TOKEN) never governs: enforcing and removing take ostrom login --admin
        refused = self.ostrom(maya, "harness", "enforce", workflow["id"], console=True)
        self.check(refused.returncode == 77, f"a personal token cannot enforce it ({refused.returncode})")
        if not self.admin_signed_in(maya):
            return
        done = self.governed(maya, "harness", "enforce", workflow["id"])
        self.check(done == 0, f"harness enforce, with the admin token ({done})")
        shown = self.ostrom(maya, "harness", "show", workflow["id"], "--json")
        status = (read_json(shown.stdout) or {}).get("status")
        self.check(shown.returncode == 0 and status in ("accepted", "edited"), f"it is enforced ({status})")
        done = self.governed(maya, "harness", "remove", workflow["id"], "--yes")
        self.check(done == 0, f"harness remove, with the admin token ({done})")
        listed = self.ostrom(maya, "harness", "list", "--all", "--json")
        items = (read_json(listed.stdout) or {}).get("items", [])
        self.check(listed.returncode == 0 and all(w.get("id") != workflow["id"] for w in items),
                   f"it is gone from the harness ({listed.returncode})")

    def admin_signed_in(self, maya):
        """ostrom login --admin, tried once and approved by the owner (the token lasts 8 hours): True when it is
        there. A failed attempt is not tried again: its failing checks are said once."""
        if self.admin_token is None:
            self.admin_token = self.login(maya, maya["email"], admin=True)
        return self.admin_token

    def holds(self):
        """True when the server holds a token's governance write for a console confirmation (audit H1, server#146)."""
        if self.held_writes is None:
            self.held_writes = self.served("GET", "/api/v1/confirmations/journey")
        return self.held_writes

    def governed(self, maya, *arguments, answer="confirm"):
        """A governance write with the admin token: written at once, or held by the server until an owner confirms it
        on the console (202, audit H1), which the owner does here from a fresh browser session. Its exit code."""
        shown = "ostrom " + " ".join(shlex.quote(a) for a in arguments)
        process = subprocess.Popen([self.binary, *arguments], cwd=maya["repo"], env=self.environment(maya),
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        said = []
        held = threading.Event()
        reader = threading.Thread(target=lambda: [said.append(line) or (CONFIRM.search(line) and held.set())
                                                  for line in process.stderr], daemon=True)
        reader.start()
        while process.poll() is None and not held.wait(0.2):
            pass
        if held.is_set():
            confirmation = next(CONFIRM.search(line).group(1) for line in said if CONFIRM.search(line))
            session = self.sign_in(maya["email"])
            if session is not None:
                status, body, _ = self.call("POST", f"/api/v1/confirmations/{urllib.parse.quote(confirmation)}/answer",
                                            {"answer": answer}, session=session, org=self.org_id)
                self.check(status == 200, f"the owner answers {answer} on the console ({status}): "
                                          f"{error_of(body)[:200]}")
        try:
            process.wait(timeout=120)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        out = process.stdout.read()
        reader.join(5)
        self.record(maya, shown, process.returncode, out, "".join(said))
        if self.holds():
            # a server that holds the governance writes holds this one: the confirmation was played, not skipped
            self.check(held.is_set(), f"{shown} is held until the console confirms it (H1)")
        return process.returncode

    def gated_session(self, maya, tom_email):
        """5.2 to 5.7 with the session alive: held at the gate, answered on the console, then let through in it."""
        hold = os.path.join(self.work, "hold")
        started = self.claude(maya, hold)
        held = wait_for(hold + ".held", started["process"], 120)
        try:
            gate = self.gate(maya, tom_email, held) if held else None
        finally:
            # whatever became of the yes, the session goes on and ends
            open(hold, "w").close()
        seen = self.claude_ended(started)
        if held is None and seen is not None:
            # never held: 5.3 says why from what the agent saw
            self.gate(maya, tom_email, seen)
        elif gate and seen is not None:
            self.passed(maya, gate, seen)
        if seen is not None and seen.get("perf"):
            self.latency(seen)

    def latency(self, seen):
        """5.8: the hook's latency in the session, against the server's budget (it runs at every tool call)."""
        self.begin("5.8", "the hook's latency, at every tool call of the agent")
        measured = seen["perf"]
        first = next((h for h in seen["hooks"] if h["event"] == "PreToolUse" and h["tool"] == "ls -la"), {})
        rows = [("local decision (policy in the relay's memory)", measured["local"]),
                ("policy asked of Ostrom (the relay's memory missed)", measured["miss"]),
                ("command under a gate (a request to Ostrom)", measured["gated"])]
        print(f"  {'':52} {'p50':>7} {'p95':>7} {'p99':>7} {'max':>7}  inside p50/p95  runs  exits", flush=True)
        for label, row in rows:
            print(f"  {label:52} {row['p50']:7.1f} {row['p95']:7.1f} {row['p99']:7.1f} {row['max']:7.1f}  "
                  f"{row['inside_p50']}/{row['inside_p95']}  {row['runs']:4}  {row['codes']}", flush=True)
        print(f"  first harmless call of the session, through sh as Claude Code runs it: {first.get('millis')} ms",
              flush=True)
        self.step["latency"] = {**measured, "first": first.get("millis")}
        local, server = self.options.max_local_p95_ms, self.options.max_server_p95_ms
        # a series that failed fast would pass any budget: each must have taken the path it times
        for name, code in (("local", 0), ("miss", 0), ("gated", 2)):
            self.check(measured[name]["codes"] == [code], f"every call of the {name} series exits {code} "
                                                          f"(got {measured[name]['codes']})")
        self.check(measured["local"]["p95"] < local, f"a local decision: p95 {measured['local']['p95']} ms < {local}")
        for name in ("miss", "gated"):
            self.check(measured[name]["p95"] < server, f"a round trip to Ostrom ({name}): p95 "
                                                       f"{measured[name]['p95']} ms < {server}")

    def claude(self, maya, hold=None):
        """Starts `ostrom claude` with the fake agent; [claude_ended] waits for it and checks what it saw."""
        agents = os.path.join(self.work, "agents")
        os.makedirs(agents, exist_ok=True)
        script = os.path.join(agents, "claude.py")
        shutil.copy(os.path.join(HERE, "fake_agent.py"), script)
        os.chmod(script, 0o755)
        with open(os.path.join(agents, "claude"), "w") as handle:
            handle.write(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(script)} \"$@\"\n")
        os.chmod(os.path.join(agents, "claude"), 0o755)
        report = os.path.join(self.work, "claude-report.json")
        extra = {"PATH": agents + os.pathsep + os.environ["PATH"], "E2E_REPORT": report, "E2E_EXIT": "0",
                 "E2E_OSTROM": self.binary, **({"E2E_HOLD": hold} if hold else {}),
                 **({"E2E_PERF": str(self.options.perf_runs)} if hold and self.options.perf_runs else {})}
        # files, not pipes: nobody reads them while the session waits for its yes
        out, err = (open(os.path.join(self.work, f"claude.{name}"), "w+") for name in ("out", "err"))
        process = subprocess.Popen([self.binary, "claude", "-p", "hello"], cwd=maya["repo"],
                                   env=self.environment(maya, False, extra), stdin=subprocess.DEVNULL,
                                   stdout=out, stderr=err, text=True)
        return {"process": process, "step": self.step, "report": report, "maya": maya, "out": out, "err": err}

    def claude_ended(self, started):
        """The end of `ostrom claude`, checked in its own step (5.2); the agent's report, or None."""
        current, self.step = self.step, started["step"]
        try:
            return self.claude_checks(started)
        finally:
            self.step = current

    def claude_checks(self, started):
        maya, report, process = started["maya"], started["report"], started["process"]
        try:
            process.wait(timeout=420)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        stdout, stderr = (said(started[name]) for name in ("out", "err"))
        self.step["commands"].append({"who": maya["name"], "command": "ostrom claude -p hello",
                                      "exit": process.returncode, "stdout": stdout, "stderr": stderr})
        self.check(process.returncode == 0, f"ostrom claude runs the agent and hands back its exit 0 "
                                            f"({process.returncode}): {stderr.strip()[-300:]}")
        if not self.check(os.path.exists(report), "the agent was started with Ostrom's harness"):
            return None
        with open(report) as handle:
            seen = json.load(handle)
        self.check("OSTROM_TOKEN" not in seen["environment"] and
                   maya["token"] not in json.dumps(seen["environment"]),
                   "no Ostrom token of the developer is in the agent's environment")
        hooks = {(hook["event"], hook["tool"]): hook for hook in seen["hooks"]}
        start = hooks.get(("SessionStart", None)) or {}
        self.check(start.get("code") == 0, f"the session starts on the server (hook exit {start.get('code')})")
        harmless = hooks.get(("PreToolUse", "ls -la")) or {}
        self.check(harmless.get("code") == 0, f"a harmless tool runs (hook exit {harmless.get('code')})")
        return seen

    def gate_workflow(self, maya):
        """An enforced workflow whose gate covers a deployment, with the owner and the invitee as its approvers."""
        name = f"prod-{self.stamp}"
        document = {
            "id": name, "name": name, "domain": "deploy", "status": "proposed", "foundAt": "2026-10-30T00:00:00Z",
            "start": "deploy",
            "nodes": [{"id": "deploy", "kind": "trigger", "label": "kubectl apply to production"},
                      {"id": "yes", "kind": "gate", "label": "Deploy to production",
                       "approvers": [self.owner, self.invitee],
                       "actions": [{"class": "deploy", "label": "Deploy to production",
                                    "patterns": ["\\bkubectl\\s+apply\\b"], "repos": []}]}],
            "edges": [{"from": "deploy", "to": "yes"}, {"from": "yes", "to": None}],
        }
        path = os.path.join(maya["home"], f"{name}.json")
        with open(path, "w") as handle:
            json.dump(document, handle)
        done = self.ostrom(maya, "harness", "add", path, "--yes", console=True)
        if not self.check(done.returncode == 0, f"harness add ({done.returncode}): {done.stderr.strip()[:300]}"):
            return None
        listed = self.ostrom(maya, "harness", "list", "--all", "--json")
        workflow = next((w for w in (read_json(listed.stdout) or {}).get("items", []) if w.get("name") == name), None)
        if not self.check(workflow is not None, f"harness list shows it ({listed.returncode})"):
            return None
        # only a person signed in to the console enforces a workflow: the owner's browser session, as the console does
        session = self.owner_signed_in(maya)
        path = f"/api/v1/workflows/{workflow['id']}"
        status, current, _ = self.call("GET", path, session=session, org=self.org_id) if session else (0, None, None)
        if not self.check(status == 200, f"the console reads it ({status})"):
            return None
        document = {k: v for k, v in current.items() if k not in ("version", "updatedBy", "updatedAt")}
        enforced = {"workflow": {**document, "status": "accepted"}, "expectedVersion": current.get("version")}
        status, body, _ = self.call("PUT", path, enforced, session=session, org=self.org_id)
        self.check(status in (200, 201, 204), f"the owner enforces it on the console ({status}): "
                                              f"{error_of(body)[:200]}")
        return workflow["id"]

    def owner_signed_in(self, maya):
        """The owner's browser session, signed in once."""
        if self.owner_session is None:
            self.owner_session = self.sign_in(maya["email"])
        return self.owner_session

    def gate(self, maya, tom_email, seen):
        """5.3 to 5.7: the deployment held, the asker's yes refused, the second person's yes, the wait, the receipt."""
        self.begin("5.3", "the deployment the agent tries: the gate opens and holds it")
        fired = (seen or {}).get("hooks", [])
        deploy = next((h for h in fired if h["event"] == "PreToolUse" and h["tool"] == "kubectl apply -f prod.yaml"),
                      {})
        said = (deploy.get("stdout", "") + deploy.get("stderr", "")).strip()
        self.check(deploy.get("code") not in (None, 0), f"the hook holds the deployment (exit {deploy.get('code')})"
                                                        f": {said[-300:]}")
        waited = next((h for h in fired if h["event"] == "wait"), None)
        if not self.check(waited is not None, "the hook names the request (ostrom wait <id>)"):
            return
        gate = waited["tool"]
        self.check(waited["code"] == 75, f"ostrom wait, in the session, is still waiting (exit {waited['code']}): "
                                         f"{(waited['stdout'] + waited['stderr']).strip()[-300:]}")

        self.begin("5.4 inbox", "the second person sees the request (ostrom inbox)")
        if not self.needs(("GET", "/api/v1/approvals"), ("POST", "/api/v1/approvals/x/answer"),
                          ("GET", "/api/v1/receipts"), org=True):
            return
        tom = self.machine("tom")
        tom_session = self.sign_in(tom_email)
        if self.login(tom, tom_email):
            inbox = self.ostrom(tom, "inbox", "--json")
            items = read_json(inbox.stdout) or []
            self.check(inbox.returncode == 0 and len(items) >= 1, f"ostrom inbox lists the request "
                                                                 f"({inbox.returncode}, {len(items)}): "
                                                                 f"{inbox.stderr.strip()[:300]}")
        if tom_session is None:
            return
        status, body, _ = self.call("GET", "/api/v1/approvals?mine=true&status=pending", session=tom_session)
        pending = (body or {}).get("items", []) if isinstance(body, dict) else []
        if not self.check(status == 200 and pending, f"the console lists it for {who(tom_email)} ({status}, "
                                                     f"{len(pending)}): {error_of(body)[:200]}"):
            return
        # the request of this gate; a fresh organisation has no other, so a lone one is it
        approval = next((a for a in pending if gate in json.dumps(a)), pending[0] if len(pending) == 1 else None)
        if not self.check(approval is not None, f"the request of {gate} is among the {len(pending)} pending"):
            return
        answer = {"answer": "approve", "shownDigest": approval.get("shownDigest", ""), "note": "go"}

        self.begin("5.6", "the asker's own yes is refused (self-approval never)")
        owner_session = self.owner_signed_in(maya)
        if owner_session:
            status, body, _ = self.call("POST", f"/api/v1/approvals/{approval['id']}/answer", answer,
                                        session=owner_session)
            self.check(status == 403, f"the server refuses it ({status}): {error_of(body)[:200]}")

        self.begin("5.4", "the second person says yes")
        # the yes's wall clock: 5.5 measures from it to the action passing in the session
        self.yes_at = time.time()
        status, body, _ = self.call("POST", f"/api/v1/approvals/{approval['id']}/answer", answer, session=tom_session)
        self.check(status in (200, 201), f"{who(tom_email)}'s yes is taken ({status}): {error_of(body)[:200]}")

        return gate

    def passed(self, maya, gate, seen):
        """5.5 and 5.7, once the session that was held has ended."""
        self.begin("5.5", "the action passes: ostrom wait answers yes, in the session")
        fired = seen.get("hooks", [])
        waited = next((h for h in fired if h["event"] == "wait-after"), {})
        self.check(waited.get("code") == 0, f"ostrom wait, in the session, exits 0 ({waited.get('code')}): "
                                            f"{(waited.get('stdout', '') + waited.get('stderr', '')).strip()[-300:]}")
        retry = next((h for h in fired if h["event"] == "retry"), {})
        self.check(retry.get("code") == 0, f"the same deployment, again, passes the hook ({retry.get('code')}): "
                                           f"{(retry.get('stdout', '') + retry.get('stderr', '')).strip()[-300:]}")
        yes_at = getattr(self, "yes_at", None)
        timed = retry.get("at") and waited.get("at")
        if yes_at is not None and waited.get("code") == 0 and not timed:
            self.check(False, "the session stamps the end of ostrom wait and of the retry (the fake agent's \"at\")")
        elif yes_at is not None and waited.get("code") == 0:
            # ostrom wait was already waiting (Ostrom holds its read): the yes reaches it, not the next poll
            answered, passed = (waited["at"] - yes_at) * 1000, (retry["at"] - yes_at) * 1000
            print(f"  from the yes sent: ostrom wait answers in {answered:.0f} ms, "
                  f"the action passes in {passed:.0f} ms", flush=True)
            self.step["yes_to_action_ms"] = {"wait": round(answered), "action": round(passed)}
            self.check(passed < self.options.max_yes_to_action_ms,
                       f"the action passes {passed:.0f} ms after the yes < {self.options.max_yes_to_action_ms:.0f}")

        self.begin("5.7", "the signed receipt: listed, pulled, verified")
        # the device's token reads receipts once it has receipts:read (server#83); until then, a console token
        listed = self.ostrom(maya, "receipts", "list", "--json")
        console = listed.returncode != 0
        if console:
            print(f"  note  receipts list with the device's token: exit {listed.returncode} "
                  f"{listed.stderr.strip()[:200]}", flush=True)
            listed = self.ostrom(maya, "receipts", "list", "--json", console=True)
        receipts = read_json(listed.stdout) or []
        if not self.check(listed.returncode == 0, f"receipts list exits 0 ({listed.returncode}): "
                                                  f"{listed.stderr.strip()[:300]}"):
            return
        receipt = next((r for r in receipts if gate in json.dumps(r)), None)
        if not self.check(receipt is not None, f"the receipt of {gate} is among the {len(receipts)} listed"):
            return
        done = self.ostrom(maya, "receipts", "pull", receipt["id"], console=console)
        self.check(done.returncode == 0, f"receipts pull {receipt['id']} ({done.returncode}): "
                                         f"{done.stderr.strip()[:300]}")
        done = self.ostrom(maya, "verify", receipt["id"], "--json")
        self.check(done.returncode == 0, f"ostrom verify, offline ({done.returncode}): "
                                         f"{(done.stdout + done.stderr).strip()[-300:]}")

    def report(self):
        counts = {status: sum(1 for s in self.steps if s["status"] == status) for status in ("ok", "skipped", "failed")}
        print(f"\nJourney: {counts['ok']} steps ok, {counts['skipped']} skipped (waiting for the server), "
              f"{counts['failed']} failed.")
        for step in self.steps:
            if step["missing"]:
                print(f"  {step['step']} {step['title']}: waits for: {', '.join(step['missing'])}")
        if self.options.report:
            with open(self.options.report, "w") as handle:
                json.dump({"server": self.server, "steps": self.steps}, handle, indent=2)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as handle:
                handle.write("### The CLI journey against the real server\n\n| Step | | Status |\n|---|---|---|\n")
                for step in self.steps:
                    missing = f" (waits for: {', '.join(step['missing'])})" if step["missing"] else ""
                    handle.write(f"| {step['step']} | {step['title']} | {step['status']}{missing} |\n")
        return 1 if counts["failed"] else 0


CODE = re.compile(r"one-time code: (\S+)")
# the link a held governance write is confirmed at (ostrom-cli ConfirmationWait)
CONFIRM = re.compile(r"Confirm it on the console: \S*/confirm/([^\s/?#]+)")
# what ostrom claude needs from the server (5.2), and so does the read of the session it ran (6.1)
SESSION_ROUTES = (("GET", "/api/v1/policy/agents"), ("POST", "/api/v1/launches"), ("POST", "/api/v1/sessions"))

# what ostrom status --json always holds (CLI.md principle 6: null when unknown, never missing)
STATUS_KEYS = {"login", "organization", "role", "server", "repository", "harness", "waiting", "waitingComplete",
               "version", "pinnedKey"}
ORG_HEADER = "Ostrom-Org (the organisation of a token bound to none, server#64)"


def said(handle):
    """All a finished process wrote to [handle], closed after."""
    with handle:
        handle.seek(0)
        return handle.read()


def wait_for(path, process, seconds):
    """The JSON a running agent writes at [path], or None when it ends, or [seconds] pass, before."""
    deadline = time.monotonic() + seconds
    while not os.path.exists(path):
        if process.poll() is not None or time.monotonic() > deadline:
            return None
        time.sleep(0.2)
    with open(path) as handle:
        return json.load(handle)


def read_json(raw):
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def cookies(headers):
    """The cookies an answer sets, as a request's cookie header (name=value; ...); empty when it sets none."""
    return "; ".join(c.split(";", 1)[0] for c in (headers.get_all("set-cookie") if headers else None) or [])


def error_of(body):
    """The message of an error answer, whatever its shape."""
    error = body.get("error") if isinstance(body, dict) else None
    return error.get("message", "") if isinstance(error, dict) else str(error or "")


def who(email):
    return email.split("@", 1)[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--binary", required=True, help="the ostrom binary")
    parser.add_argument("--server", default=os.environ.get("OSTROM_E2E_SERVER", "http://127.0.0.1:4600"))
    parser.add_argument("--mail-dir", default=os.environ.get("OSTROM_E2E_MAIL_DIR"), required=False,
                        help="the server's OSTROM_MAIL_DIR")
    seed = parser.add_mutually_exclusive_group(required=True)
    seed.add_argument("--seed", help="the command that runs `ostrom-server seed-test-account` (the e-mail is added)")
    seed.add_argument("--seeded", help="a file with the line seed-test-account printed")
    parser.add_argument("--strict", action="store_true", default=os.environ.get("OSTROM_E2E_STRICT") == "true",
                        help="a route not served yet fails the run")
    parser.add_argument("--show", action="store_true", help="print every command and what it printed (the film)")
    parser.add_argument("--pace", type=float, default=0, help="seconds to wait before each step (the film)")
    parser.add_argument("--perf-runs", type=int, default=100, help="calls per latency series in 5.8 (0: none)")
    parser.add_argument("--max-local-p95-ms", type=float, default=20, help="5.8: a local decision, at p95")
    # 75 ms, not the 150 first proposed: the journey measured 13.7 ms (policy asked) and 25.1 ms (gated) at p95 (run
    # 41500895660); 150 would let a sixfold regression through, 75 keeps three times the gated one for the noise.
    parser.add_argument("--max-server-p95-ms", type=float, default=75, help="5.8: a round trip to Ostrom, at p95")
    # a wait already held by Ostrom answers within the yes's own round trip; 5 s leaves the runner its noise, and a
    # wait that polls each second (or misses the yes until its read ends, 30 s) shows here
    parser.add_argument("--max-yes-to-action-ms", type=float, default=5_000, help="5.5: from the yes to the action")
    parser.add_argument("--report", help="write every step as JSON there")
    parser.add_argument("--work", help="the folder of the machines (default: a new temporary one)")
    # 2.9: the fake MCP server, in HTTPS on 127.0.0.1, whose certificate the server trusts (2.9 is skipped without)
    parser.add_argument("--fake-mcp-port", type=int, help="2.9: the port of the fake MCP server")
    parser.add_argument("--fake-mcp-cert", help="2.9: its certificate (PEM), trusted by the server")
    parser.add_argument("--fake-mcp-key", help="2.9: its private key (PEM)")
    options = parser.parse_args()
    if not options.mail_dir:
        parser.error("--mail-dir (or OSTROM_E2E_MAIL_DIR) is needed: people sign in by their e-mailed link")
    journey = Journey(options)
    try:
        journey.run()
    except BaseException as error:  # noqa: BLE001 - the step that stopped the run is failed in the report, then said
        if journey.step is not None:
            journey.step["status"] = "failed"
            journey.step["checks"].append({"ok": False, "what": f"the step stopped: {type(error).__name__}"})
        if not isinstance(error, SystemExit):
            traceback.print_exc()
        elif error.code not in (None, 0):
            print(error.code, file=sys.stderr)
    code = journey.report()
    if journey.temporary:
        # the machines hold the device tokens ostrom login stored
        shutil.rmtree(journey.work, ignore_errors=True)
    sys.exit(code)


if __name__ == "__main__":
    main()

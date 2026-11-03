# SPDX-License-Identifier: Apache-2.0
"""6.6 of demo/PARCOURS.md on the CLI side: a TeamCode team into Ostrom with the real `ostrom import teamcode`.

The team is the fake one of ostrom-helm (compose/demo/teamcode, in MySQL, read by the read-only user `teamcode_ro`
with its fake public key file). The plan writes nothing, the import shares the accounts, an account to reconnect
waits while TeamCode runs, then the owner's cutover revives it with TeamCode's tokens, refreshed by a fake provider
token endpoint (the server's OSTROM_SHARING_CLAUDE_TOKEN_URL, ostrom-server#96), and is audited in the owner's name.
As ostrom-e2e's E2E-TC-003/004, but against a published image of the server and the CLI's own journey.
"""
import json
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl

# TeamCode's refresh tokens of the fake team's two Claude accounts (helm compose/demo/teamcode/seed.mjs, fake), and the
# provider accounts a refresh must name for a revival (ostrom-server#117)
ACCOUNTS = {"sk-ant-ort-demo-1": "acct-a-max-1", "sk-ant-ort-demo-2": "acct-a-max-2"}
# the public client id of Claude Code, sent with every refresh (the server's HttpOAuthRefresher)
CLAUDE_CODE_CLIENT = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
ROUTES = (("POST", "/api/v1/tokens"), ("POST", "/api/v1/import/teamcode"), ("GET", "/api/v1/accounts"))
# a console token as an owner makes one: the device token of ostrom login cannot import
SCOPES = ["import:write", "accounts:read", "profile:read"]
MAX_BODY = 64 * 1024


class FakeTokens:
    """
    A provider's OAuth token endpoint for the shared accounts' refresh (RFC 6749 § 5.1): 200 with a new pair naming
    the account, as Claude does (`account.uuid`). Every refresh it is sent is recorded; no real provider's token.
    """

    def __init__(self, port):
        self.refreshes = []
        tokens = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - the name http.server calls
                length = int(self.headers.get("content-length") or 0)
                raw = self.rfile.read(min(length, MAX_BODY)).decode("utf-8", errors="replace")
                try:
                    body = json.loads(raw)
                except ValueError:
                    body = dict(parse_qsl(raw))
                body = body if isinstance(body, dict) else {}
                refresh = {key: body.get(key) for key in ("grant_type", "refresh_token", "client_id")}
                tokens.refreshes.append(refresh)
                if refresh["grant_type"] != "refresh_token" or not refresh["refresh_token"]:
                    return self.send(400, {"error": "invalid_request"})
                count = len(tokens.refreshes)
                pair = {"access_token": f"fake-access-{count}", "refresh_token": f"fake-refresh-{count}",
                        "expires_in": 28_800, "token_type": "Bearer"}
                account = ACCOUNTS.get(refresh["refresh_token"])
                return self.send(200, {**pair, "account": {"uuid": account}} if account else pair)

            def send(self, status, body):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def missing_options(options):
    """The options 6.6 runs with, those not given."""
    wanted = {"--teamcode-db": options.teamcode_db, "--teamcode-key-file": options.teamcode_key_file,
              "--fake-tokens-port": options.fake_tokens_port, "--database-admin-url": options.database_admin_url}
    return [name for name, value in wanted.items() if not value]


class TeamCodeImport:
    """6.6, run by the journey once the owner's organisation is set up (2.1)."""

    def __init__(self, journey, maya):
        self.journey = journey
        self.maya = maya
        self.options = journey.options
        self.session = None
        self.token = None

    def run(self):
        j = self.journey
        j.begin("6.6", "ostrom import teamcode: the plan, the import, the owner's cutover")
        missing = missing_options(self.options)
        if missing:
            j.step["missing"] = missing
            if self.options.strict:
                j.check(False, f"the fake TeamCode team is given ({', '.join(missing)} missing)")
            else:
                j.step["status"] = "skipped"
                print(f"  skip  no fake TeamCode team: {', '.join(missing)}", flush=True)
            return
        if not j.needs(*ROUTES, on_main=True, org=True):
            return
        self.session = j.owner_signed_in(self.maya)
        if not self.session or not self.console_token():
            return
        if self.plan_and_import():
            tokens = FakeTokens(self.options.fake_tokens_port)
            try:
                self.cutover(tokens)
            finally:
                tokens.stop()

    def console_token(self):
        status, body, _ = self.journey.call("POST", "/api/v1/tokens", {"name": "journey TeamCode import",
                                                                       "scopes": SCOPES, "expiresInDays": 1},
                                            session=self.session)
        self.token = body.get("secret") if isinstance(body, dict) else None
        return self.journey.check(status == 201 and self.token, f"the owner makes a console token to import "
                                                                f"({status})")

    def ostrom(self, *arguments):
        extra = {"OSTROM_TOKEN": self.token, "MYSQL_PWD": self.options.teamcode_password or ""}
        return self.journey.ostrom(self.maya, "import", "teamcode", "--db", self.options.teamcode_db,
                                   "--key-file", self.options.teamcode_key_file, *arguments, extra=extra)

    def accounts(self):
        """The organisation's shared accounts, as `[label, status]` sorted by label; None when it cannot read them."""
        status, body, _ = self.journey.call("GET", "/api/v1/accounts", session=self.session, org=self.journey.org_id)
        if not self.journey.check(status == 200, f"the owner reads the shared accounts ({status})"):
            return None
        return sorted([str(item.get("label")), str(item.get("status"))] for item in (body or {}).get("items", []))

    def sql(self, statement):
        """[statement] as the superuser of the run's throwaway database: its rows, tab-separated."""
        done = subprocess.run(["psql", self.options.database_admin_url, "-At", "-F", "\t", "-v", "ON_ERROR_STOP=1",
                               "-c", statement], capture_output=True, text=True, timeout=30)
        if done.returncode != 0:
            raise RuntimeError(f"psql failed ({done.returncode}): {done.stderr.strip()[:300]}")
        return [line for line in done.stdout.splitlines() if line]

    def plan_and_import(self):
        j = self.journey
        plan = self.ostrom()
        j.check(plan.returncode == 0 and "Plan (nothing imported yet):" in plan.stdout,
                f"the plan exits 0 and says it imports nothing yet ({plan.returncode}): {plan.stderr.strip()[:300]}")
        j.check("account  claude/Claude Max 1" in plan.stdout, "the plan names the team's Claude account")
        j.check("! Left out: account Never finished" in plan.stdout, "it says what it leaves out, and why")
        j.check(self.accounts() == [], "the plan writes nothing")
        done = self.ostrom("--yes")
        j.check(done.returncode == 0 and "created  account  claude/Claude Max 1" in done.stdout,
                f"--yes imports ({done.returncode}): {done.stderr.strip()[:300]}")
        shared = self.accounts()
        return j.check(shared == [["Claude Max 1", "ready"], ["Claude Max 2 (paused)", "disabled"]],
                       f"the accounts are shared, the paused one turned off ({shared})")

    def cutover(self, tokens):
        j = self.journey
        # Ostrom's copies lose their tokens, as when a refresh is refused: they wait for a reconnection, and while
        # TeamCode runs an import never tests TeamCode's tokens (testing rotates them)
        org = j.org_id
        self.sql(f"UPDATE sharing_accounts SET status = 'RECONNECT_REQUIRED', version = version + 1 "
                 f"WHERE org_id = '{uuid(org)}'")
        again = self.ostrom("--yes")
        j.check(again.returncode == 0 and "2 to revive at the cutover." in again.stdout,
                f"an import while TeamCode runs leaves them for the cutover ({again.returncode})")
        j.check(tokens.refreshes == [], "and tests none of TeamCode's tokens")
        refused = self.ostrom("--cutover", "--yes")
        j.check(refused.returncode == 64, f"--yes is no cutover: the word is ({refused.returncode})")
        before = len(self.audited(org))
        done = self.ostrom("--cutover", "--confirm", "cutover")
        j.check(done.returncode == 0, f"the owner's cutover exits 0 ({done.returncode}): {done.stderr.strip()[:300]}")
        j.check("updated  account  claude/Claude Max 1" in done.stdout, "it revives the account with TeamCode's tokens")
        sent = sorted(str(r.get("refresh_token")) for r in tokens.refreshes)
        j.check(sent == sorted(ACCOUNTS), f"refreshed at the provider with TeamCode's refresh tokens ({len(sent)})")
        j.check(all(r.get("client_id") == CLAUDE_CODE_CLIENT for r in tokens.refreshes),
                "as Claude Code refreshes them")
        shared = self.accounts()
        j.check(shared == [["Claude Max 1", "ready"], ["Claude Max 2 (paused)", "disabled"]],
                f"the accounts are back, the paused one still off ({shared})")
        left = self.ostrom("--cutover", "--confirm", "cutover")
        j.check(left.returncode == 0 and "nothing to do" in left.stdout, f"a second cutover has nothing to do "
                                                                           f"({left.returncode})")
        events = self.audited(org)[before:]
        status, me, _ = j.call("GET", "/api/v1/auth/me/account", session=self.session)
        owner = me.get("id") if status == 200 and isinstance(me, dict) else None
        j.check(owner is not None, f"the owner's id is read ({status})")
        j.check(len(events) == 1, f"one audit event import.teamcode.cutover ({len(events)})")
        j.check(len(events) == 1 and events[0][0] == owner and json.loads(events[0][1]).get("mode") == "cutover",
                f"in the owner's name ({[actor for actor, _ in events]})")

    def audited(self, org):
        rows = self.sql(f"SELECT actor_id, details FROM identity_audit_events WHERE org_id = '{uuid(org)}' "
                        f"AND action = 'import.teamcode.cutover' ORDER BY sequence")
        return [row.split("\t", 1) for row in rows]


def uuid(value):
    """[value], a UUID, never anything a statement would read as SQL."""
    if not value or len(value) != 36 or any(c not in "0123456789abcdef-" for c in value):
        raise ValueError(f"not a uuid: {value!r}")
    return value

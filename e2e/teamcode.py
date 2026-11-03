# SPDX-License-Identifier: Apache-2.0
"""6.6 of demo/PARCOURS.md on the CLI side: a TeamCode team into Ostrom with `ostrom import teamcode`.

The team is the demo's fake one (ostrom-helm compose/demo/teamcode: TeamCode's own MySQL tables, its secrets sealed with
a fake key), read by the real binary with TeamCode's read-only user, its password in MYSQL_PWD. The owner makes a
console token that may import (`import:write`, which the seeded token does not hold), then: the plan, which writes
nothing; the import (`--yes`); what Ostrom now holds, read back by its API (the MCP servers, the secrets they name, the
shared accounts), never only the exit code; and the same import again, which changes nothing. The token is revoked at
the end; what was imported stays in the run's organisation, the journey's last step.
"""
import json
import os
import re

ROUTES = (("POST", "/api/v1/tokens"), ("POST", "/api/v1/import/teamcode"), ("GET", "/api/v1/mcp/servers"),
          ("GET", "/api/v1/secrets"), ("GET", "/api/v1/accounts"))
SCOPES = ["import:write", "profile:read", "mcp:read", "secrets:read", "accounts:read"]

# The demo team's MCP variants (compose/demo/teamcode/02-seed.sql), as Ostrom's console lists them once imported:
# name -> (url, scope, credential header, whether an org secret holds a shared credential)
SERVERS = {
    "github": ("https://api.githubcopilot.com/mcp/", "shared", None, True),
    "linear": ("https://mcp.linear.app/mcp", "per-member", None, False),
    "sentry": ("https://mcp.sentry.dev/mcp", "shared", "X-Sentry-Token", True),
    "docs": ("https://mcp.context7.com/mcp", "shared", None, False),
}
# Its shared accounts, label -> status: a-never never finished its enrolment and is left out
ACCOUNTS = {"Claude Max 1": "ready", "Claude Max 2 (paused)": "disabled"}
LEFT_OUT = "! Left out: account Never finished"
REPORT_LINE = re.compile(r"^  (\S+)  (\S+)  (.+)$")


def missing_options(options):
    """The options 6.6 needs that were not given."""
    wanted = {"--teamcode-db": options.teamcode_db, "--teamcode-key-file": options.teamcode_key_file,
              "OSTROM_E2E_TEAMCODE_PASSWORD": os.environ.get("OSTROM_E2E_TEAMCODE_PASSWORD")}
    return [name for name, value in wanted.items() if not value]


def report_lines(out):
    """The report's item lines, as (action, kind, name): `  created  server  github`."""
    return [match.groups() for match in map(REPORT_LINE.match, out.splitlines()) if match]


class TeamCode:
    """6.6, run by the journey once the owner's organisation is set up (2.1)."""

    def __init__(self, journey, maya):
        self.journey = journey
        self.maya = maya
        self.options = journey.options
        self.session = None
        self.token = None

    def run(self):
        j = self.journey
        j.begin("6.6", "ostrom import teamcode: a TeamCode team into Ostrom, read back by its API")
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
        # a token is made on the console with a sign-in of the last minutes
        self.session = j.recent_sign_in(self.maya["email"])
        if not self.session:
            return
        token_id = self.console_token()
        try:
            if self.token and self.planned():
                self.imported()
                self.again()
        finally:
            if token_id:
                session = j.recent_sign_in(self.maya["email"]) or self.session
                revoked, _, _ = j.call("DELETE", f"/api/v1/tokens/{token_id}", session=session)
                j.check(revoked in (200, 204), f"the import's token is revoked on the console ({revoked})")

    def console_token(self):
        """A token the owner makes on the console, which may import: its id (the secret is kept in self.token)."""
        j = self.journey
        status, made, _ = j.call("POST", "/api/v1/tokens", {"name": "journey TeamCode import", "scopes": SCOPES,
                                                            "expiresInDays": 1}, session=self.session)
        made = made if isinstance(made, dict) else {}
        self.token = made.get("secret")
        j.check(status == 201 and self.token, f"the owner makes a console token that may import ({status}): "
                                              f"{json.dumps(made.get('error', made))[:200]}")
        return (made.get("token") or {}).get("id")

    def ostrom_import(self, *arguments):
        j = self.journey
        machine = {**self.maya, "token": self.token}
        return j.ostrom(machine, "import", "teamcode", "--db", self.options.teamcode_db, "--key-file",
                        self.options.teamcode_key_file, *arguments, console=True,
                        extra={"MYSQL_PWD": os.environ["OSTROM_E2E_TEAMCODE_PASSWORD"]})

    def planned(self):
        """The plan: every MCP server and shared account of the team planned, nothing written."""
        j = self.journey
        plan = self.ostrom_import()
        out = plan.stdout
        if not j.check(plan.returncode == 0 and "Plan (nothing imported yet):" in out,
                       f"ostrom import teamcode shows the plan ({plan.returncode}): "
                       f"{(plan.stderr or out).strip()[-300:]}"):
            return False
        lines = report_lines(out)
        servers = sorted(name for action, kind, name in lines if (action, kind) == ("planned", "server"))
        j.check(servers == sorted(SERVERS), f"it plans the team's four MCP servers ({servers})")
        accounts = sorted(name for action, kind, name in lines if (action, kind) == ("planned", "account"))
        j.check(accounts == sorted(f"claude/{label}" for label in ACCOUNTS),
                f"and its two finished shared accounts ({accounts})")
        j.check(LEFT_OUT in out and "Run the same command with --yes to import." in out,
                f"leaving out the one never finished, and saying how to import: {out.strip()[-300:]}")
        held = self.servers()
        j.check(held is not None and not set(SERVERS) & set(held),
                f"the plan writes nothing: Ostrom has none of them ({sorted(held or {})})")
        return True

    def imported(self):
        """The import, then what Ostrom holds, read back by its API."""
        j = self.journey
        done = self.ostrom_import("--yes")
        out = done.stdout
        j.check(done.returncode == 0 and "Imported:" in out,
                f"ostrom import teamcode --yes imports ({done.returncode}): {(done.stderr or out).strip()[-300:]}")
        created = sorted(name for action, kind, name in report_lines(out) if (action, kind) == ("created", "server"))
        j.check(created == sorted(SERVERS), f"it says each server created ({created})")
        held = self.servers() or {}
        secrets = self.secret_names()
        for name, (url, scope, header, shared) in SERVERS.items():
            server = held.get(name)
            if not j.check(server is not None, f"Ostrom lists {name} ({sorted(held)})"):
                continue
            seen = {key: server.get(key) for key in ("url", "transport", "scope", "credentialHeader", "secretNames")}
            j.check((seen["url"], seen["transport"], seen["scope"], seen["credentialHeader"]) ==
                    (url, "http", scope, header), f"{name} as in TeamCode: {url}, {scope}, header {header} ({seen})")
            named = seen["secretNames"] or []
            if shared:
                j.check(named and all(secret in secrets for secret in named),
                        f"its shared credential is an org secret Ostrom lists ({named}, {sorted(secrets)})")
            else:
                j.check(named == [], f"with no shared credential ({named})")
        accounts = self.accounts()
        j.check(accounts == ACCOUNTS, f"Ostrom shares the team's accounts, the paused one turned off ({accounts})")

    def again(self):
        """The same import again: nothing created, nothing updated, the servers and accounts as they were."""
        j = self.journey
        before = self.servers() or {}
        done = self.ostrom_import("--yes")
        out = done.stdout
        changed = [line for line in report_lines(out) if line[0] in ("created", "updated")]
        j.check(done.returncode == 0 and not changed and re.search(r"^0 created, 0 updated, \d+ unchanged", out, re.M),
                f"the same import again changes nothing ({done.returncode}, {changed}): {out.strip()[-300:]}")
        after = self.servers() or {}
        j.check({n: after.get(n, {}).get("version") for n in SERVERS} == {n: before.get(n, {}).get("version")
                                                                        for n in SERVERS},
                "and the servers keep their version")
        j.check(self.accounts() == ACCOUNTS, "and the accounts their status")

    def servers(self):
        """Ostrom's MCP servers, by name, as the console lists them; None when it cannot."""
        j = self.journey
        status, body, _ = j.call("GET", "/api/v1/mcp/servers", session=self.session, org=j.org_id)
        if not j.check(status == 200 and isinstance(body, dict), f"the console lists the MCP servers ({status})"):
            return None
        return {server.get("name"): server for server in body.get("items", []) if isinstance(server, dict)}

    def secret_names(self):
        j = self.journey
        status, body, _ = j.call("GET", "/api/v1/secrets", session=self.session, org=j.org_id)
        j.check(status == 200 and isinstance(body, dict), f"the console lists the org's secrets ({status})")
        items = body.get("items", []) if isinstance(body, dict) else []
        return {secret.get("name") for secret in items if isinstance(secret, dict)}

    def accounts(self):
        """The shared accounts, label -> status."""
        j = self.journey
        status, body, _ = j.call("GET", "/api/v1/accounts", session=self.session, org=j.org_id)
        j.check(status == 200 and isinstance(body, dict), f"the console lists the shared accounts ({status})")
        items = body.get("items", []) if isinstance(body, dict) else []
        return {account.get("label"): account.get("status") for account in items if isinstance(account, dict)}

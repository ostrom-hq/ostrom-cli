# SPDX-License-Identifier: Apache-2.0
"""3.1 of demo/PARCOURS.md on the CLI side: the rules the analysis proposed, listed, shown and decided by `ostrom`.

The analysis reads the repositories of the organisation's GitHub App (proved on the server and on the console, line
3.1); the CLI's journey has no GitHub, so the owner writes the proposals on the console instead (`POST /api/v1/rules`,
status `proposed`, a browser session), as the analysis would have left them. Then, with the real binary:
`ostrom policy rules list` finds them, `show` tells one, and the decisions, `accept`, `reject` (several ids, one bulk
write) and `edit`, go with the token of `ostrom login --admin`: the server holds each until the owner confirms it on the
console (audit H1), which the owner does here from the same browser session. Every decision is read back from the
server, never from what the CLI printed. No rule has a gate: nothing the later steps run is held by them.
"""
import json
import re
import shlex
import subprocess
import threading
import time
import urllib.parse

ROUTES = (("GET", "/api/v1/rules"), ("POST", "/api/v1/rules"), ("PATCH", "/api/v1/rules/rule_journey"),
          ("POST", "/api/v1/rules/bulk"))
# the link a held governance write is confirmed at (ostrom-cli ConfirmationWait)
CONFIRM = re.compile(r"Confirm it on the console: \S*/confirm/([^\s/?#]+)")
PROPOSALS = (
    "Every change to acme-api runs its tests before it is pushed",
    "Nothing under infra/ changes without a second person",
    "Secrets never go into the repository",
    "A migration ships with its rollback",
    "Dependencies are pinned to an exact version",
)
EDITED = "Every change to acme-api runs its tests and its linter before it is pushed"
WAIT_S = 120


class Rules:
    """3.1, run by the journey once the owner's organisation is set up (2.1) and the admin token approved (3.3)."""

    def __init__(self, journey, maya):
        self.journey = journey
        self.maya = maya
        self.group = f"journey-{journey.stamp}"
        self.session = None

    def run(self):
        j = self.journey
        j.begin("3.1", "ostrom policy rules: list, show, accept, reject, edit what the analysis proposed")
        if not j.needs(*ROUTES, org=True):
            return
        # the owner's browser: the proposals, then the confirmations of the held writes (a sign-in of minutes); a
        # recent one when the journey keeps it (a person gets 10 sign-in links per 10 minutes)
        self.session = getattr(j, "recent_sign_in", j.sign_in)(self.maya["email"])
        if not self.session or not j.admin_signed_in(self.maya):
            j.check(False, "the owner has a browser session and the admin token")
            return
        ids = self.proposed()
        if len(ids) != len(PROPOSALS):
            return
        self.listed(ids)
        self.shown(ids[0])
        self.decided(ids)

    def proposed(self):
        """The owner writes the analysis's proposals on the console: their ids, in order."""
        j = self.journey
        ids = []
        for statement in PROPOSALS:
            rule = {"statement": statement, "group": self.group, "status": "proposed", "strength": "recommended",
                    "scope": ["acme-api"], "why": "Found in the repository's CI and CODEOWNERS."}
            status, body, _ = j.call("POST", "/api/v1/rules", rule, session=self.session, org=j.org_id)
            if not j.check(status == 201 and isinstance(body, dict) and body.get("status") == "proposed",
                           f"a proposal is written on the console ({status}): {str(body)[:200]}"):
                return ids
            ids.append(body["id"])
        return ids

    def listed(self, ids):
        j = self.journey
        done = j.ostrom(self.maya, "policy", "rules", "list", "--group", self.group, "--status", "proposed", "--json")
        items = (read_json(done.stdout) or {}).get("items") or []
        j.check(done.returncode == 0 and sorted(r.get("id") for r in items) == sorted(ids),
                f"rules list --status proposed finds the {len(ids)} proposals ({done.returncode}, {len(items)}): "
                f"{done.stderr.strip()[:300]}")
        text = j.ostrom(self.maya, "policy", "rules", "list", "--group", self.group)
        j.check(text.returncode == 0 and f"{len(ids)} rules in" in text.stdout and ids[0] in text.stdout,
                f"and says them in words ({text.returncode}): {text.stdout.strip()[:300]}")

    def shown(self, rule):
        j = self.journey
        done = j.ostrom(self.maya, "policy", "rules", "show", rule)
        lines = done.stdout.splitlines()
        j.check(done.returncode == 0 and lines[:1] == [PROPOSALS[0]],
                f"rules show {rule} heads with its statement ({done.returncode}): {done.stdout.strip()[:300]}")
        j.check(any(line.split() == ["Status", "proposed", "·", "recommended"] for line in lines),
                f"with its status and strength ({[line for line in lines if 'Status' in line]})")
        j.check(any(line.split()[:1] == ["Why"] for line in lines), "and why it was proposed")
        shown = j.ostrom(self.maya, "policy", "rules", "show", rule, "--json")
        body = read_json(shown.stdout) or {}
        j.check(shown.returncode == 0 and body.get("id") == rule and body.get("group") == self.group,
                f"rules show --json gives the rule ({shown.returncode}): {shown.stdout.strip()[:200]}")
        gone = j.ostrom(self.maya, "policy", "rules", "show", f"rule_none_{j.stamp}")
        j.check(gone.returncode == 66, f"a rule that does not exist is 66 ({gone.returncode})")

    def decided(self, ids):
        """accept, reject (two ids, one bulk write), edit: each with the admin token, confirmed on the console."""
        j = self.journey
        accepted, edited, rejected = ids[0], ids[1], ids[2:4]
        outside = j.ostrom(self.maya, "policy", "rules", "accept", accepted, "--proposed")
        j.check(outside.returncode == 64, f"ids and --proposed together are refused, 64 ({outside.returncode})")
        code, out = self.governed("policy", "rules", "accept", accepted)
        j.check(code == 0 and "1 rule accepted" in out, f"rules accept {accepted} exits 0 ({code}): {out.strip()}")
        self.rule_is(accepted, "accepted", PROPOSALS[0])
        code, out = self.governed("policy", "rules", "reject", *rejected)
        j.check(code == 0 and "2 rules rejected" in out, f"rules reject of two ids exits 0 ({code}): {out.strip()}")
        for rule, statement in zip(rejected, PROPOSALS[2:4]):
            self.rule_is(rule, "rejected", statement)
        code, out = self.governed("policy", "rules", "edit", edited, "--statement", EDITED, "--strength", "required")
        j.check(code == 0 and EDITED in out, f"rules edit {edited} exits 0 and says the rule ({code}): {out.strip()}")
        rule = self.rule_is(edited, "edited", EDITED)
        j.check(rule.get("strength") == "required", f"edited to required ({rule.get('strength')})")
        bad = j.ostrom(self.maya, "policy", "rules", "edit", edited, "--strength", "maybe")
        j.check(bad.returncode == 64, f"a strength that is neither required nor recommended is 64 ({bad.returncode})")
        # the one left proposed is still proposed: a decision changed only the rules it named
        self.rule_is(ids[4], "proposed", PROPOSALS[4])

    def rule_is(self, rule, status, statement):
        """The rule as the server has it, checked for [status] and [statement]; the rule, or {}."""
        j = self.journey
        code, body, _ = j.call("GET", f"/api/v1/rules/{urllib.parse.quote(rule)}", session=self.session,
                               org=j.org_id)
        body = body if code == 200 and isinstance(body, dict) else {}
        decided = status == "proposed" or bool(body.get("decidedBy"))
        j.check(body.get("status") == status and body.get("statement") == statement and decided,
                f"the server has {rule} {status} ({code}: {body.get('status')}, by {body.get('decidedBy')})")
        return body

    def governed(self, *arguments):
        """
        A decision with the admin token: written at once, or held by the server until the owner confirms it on the
        console (202, audit H1), which the owner does with this step's browser session. Its exit code and stdout.
        """
        j = self.journey
        shown = "ostrom " + " ".join(shlex.quote(a) for a in arguments)
        process = subprocess.Popen([j.binary, *arguments], cwd=self.maya["repo"], env=j.environment(self.maya),
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        said = []
        held = threading.Event()
        reader = threading.Thread(target=lambda: [said.append(line) or (CONFIRM.search(line) and held.set())
                                                  for line in process.stderr], daemon=True)
        reader.start()
        deadline = time.monotonic() + WAIT_S
        while process.poll() is None and not held.wait(0.2) and time.monotonic() < deadline:
            pass
        if held.is_set():
            confirmation = next(CONFIRM.search(line).group(1) for line in said if CONFIRM.search(line))
            status, body, _ = j.call("POST", f"/api/v1/confirmations/{urllib.parse.quote(confirmation)}/answer",
                                     {"answer": "confirm"}, session=self.session, org=j.org_id)
            j.check(status == 200, f"the owner confirms {shown} on the console ({status}): {json.dumps(body)[:200]}")
        try:
            process.wait(timeout=WAIT_S)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        out = process.stdout.read()
        reader.join(5)
        j.record(self.maya, shown, process.returncode, out, "".join(said))
        return process.returncode, out


def read_json(raw):
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None

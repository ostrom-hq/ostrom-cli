# SPDX-License-Identifier: Apache-2.0
"""4.3 of demo/PARCOURS.md on the CLI side: an agent asks a person through Ostrom's own MCP server.

In an `ostrom claude` session, the fake agent starts `ostrom mcp --session <session-env.json> --server ostrom`, as an
agent that only starts stdio servers does. The session's local relay sends `/mcp/ostrom` to the server's waits MCP
server (`/api/v1/waits/mcp`), with the launch's agent token and the session its start hook opened. The agent asks two
questions (`ask_human`); the person who launched it sees them on the console and answers the first; the agent reads
that answer (`get_wait`) and cancels the second (`cancel_wait`).
"""
import json
import os
import subprocess
import tempfile
import time

from mcp_relay import fake_claude

ROUTES = (("POST", "/api/v1/waits/mcp"), ("GET", "/api/v1/waits"), ("POST", "/api/v1/waits/x/answer"))
TOOLS = ["ask_human", "cancel_wait", "get_wait", "list_waits"]
ASKED_WAIT_S = 120


class McpWaits:
    """4.3, run by the journey once the owner's organisation is set up (2.1) and its first session has run (5.2)."""

    def __init__(self, journey, maya, session_routes):
        self.journey = journey
        self.maya = maya
        self.session_routes = session_routes

    def run(self):
        j = self.journey
        j.begin("4.3", "ostrom mcp --server ostrom: the agent asks a person, the console answers, the agent reads it")
        if not j.needs(*ROUTES, *self.session_routes, on_main=True, org=True):
            return
        # answering is a person's, in the console (any sign-in age): the owner's browser session
        session = j.owner_signed_in(self.maya)
        if not session:
            return
        seen = self.session_answered(session)
        if seen is not None:
            self.agent_saw(seen, session)

    def session_answered(self, session):
        """`ostrom claude` with the fake agent asking; the console answers its first question: the agent's report."""
        j = self.journey
        agents = fake_claude(j)
        report = os.path.join(j.work, "claude-waits-report.json")
        asked = os.path.join(j.work, "claude-waits")
        extra = {"PATH": agents + os.pathsep + os.environ["PATH"], "E2E_REPORT": report, "E2E_EXIT": "0",
                 "E2E_OSTROM": j.binary, "E2E_WAITS": asked}
        # files, not pipes: nobody reads them while the agent waits for the answer
        out, err = (tempfile.TemporaryFile("w+") for _ in range(2))
        running = subprocess.Popen([j.binary, "claude", "-p", "hello"], cwd=self.maya["repo"], stdout=out,
                                   stderr=err, text=True, env=j.environment(self.maya, False, extra),
                                   stdin=subprocess.DEVNULL)
        try:
            self.answered(asked + ".asked", running, session)
            code = running.wait(timeout=300)
        except subprocess.TimeoutExpired:
            running.kill()
            running.wait()
            j.check(False, "ostrom claude ends (still running after 300 s)")
            return None
        finally:
            if running.poll() is None:
                running.kill()
                running.wait()
        out.seek(0)
        err.seek(0)
        stdout, stderr = out.read(), err.read()
        j.record(self.maya, "ostrom claude -p hello", code, stdout, stderr)
        j.check(code == 0, f"ostrom claude runs the agent and hands back its exit 0 ({code}): {stderr.strip()[-300:]}")
        if not j.check(os.path.exists(report), "the agent was started with Ostrom's harness"):
            return None
        with open(report) as handle:
            return json.load(handle)

    def answered(self, path, running, session):
        """Once the agent has asked: the console shows the first question open, and its person answers it."""
        j = self.journey
        deadline = time.monotonic() + ASKED_WAIT_S
        while not os.path.exists(path) and running.poll() is None and time.monotonic() < deadline:
            time.sleep(0.2)
        if not j.check(os.path.exists(path), f"the agent asked through ostrom mcp --server ostrom (within "
                                             f"{ASKED_WAIT_S} s)"):
            return
        with open(path) as handle:
            asked = json.load(handle)
        answers = {a.get("id"): a for a in (asked.get("asked") or {}).get("answers") or [] if isinstance(a, dict)}
        info = ((answers.get(1) or {}).get("result") or {}).get("serverInfo") or {}
        j.check(info.get("name") == "ostrom-waits", f"initialize: Ostrom's own server answers the agent ({info}): "
                                                   f"{str((asked.get('asked') or {}).get('stderr'))[-300:]}")
        tools = sorted(t.get("name") for t in ((answers.get(2) or {}).get("result") or {}).get("tools") or [])
        j.check(tools == TOOLS, f"tools/list: ask_human, get_wait, list_waits, cancel_wait ({tools})")
        ids = asked.get("ids") or [None, None]
        j.step["waits"] = ids
        if not j.check(all(ids), f"ask_human, twice: two waits open ({ids}): "
                                 f"{json.dumps([answers.get(3), answers.get(4)])[:400]}"):
            return
        status, body, _ = j.call("GET", f"/api/v1/waits/{ids[0]}", session=session, org=j.org_id)
        shown = body if isinstance(body, dict) else {}
        j.check(status == 200 and shown.get("status") == "open" and shown.get("sessionId"),
                f"the console shows the question open, in the agent's session ({status}: {json.dumps(shown)[:300]})")
        status, body, _ = j.call("POST", f"/api/v1/waits/{ids[0]}/answer", {"action": "done"}, session=session,
                                 org=j.org_id)
        j.check(status == 200, f"its person answers it on the console ({status}): {json.dumps(body)[:300]}")

    def agent_saw(self, seen, session):
        """The answer as the agent read it, the second question cancelled, as the console shows it too."""
        j = self.journey
        relayed = seen.get("mcp") or {}
        answered = relayed.get("answered") if isinstance(relayed.get("answered"), dict) else {}
        answer = answered.get("answer") if isinstance(answered.get("answer"), dict) else {}
        j.check(answered.get("status") == "resolved" and answer.get("action") == "done",
                f"get_wait: the agent reads the person's answer ({answered.get('status')}, {answer.get('action')})")
        ids = relayed.get("ids") or [None, None]
        if not ids[1]:
            return
        cancelled = relayed.get("cancelled") or {}
        said = next((a for a in cancelled.get("answers") or [] if isinstance(a, dict) and a.get("id") == 2), {})
        content = (said.get("result") or {}).get("content") or []
        text = next((c.get("text") for c in content if isinstance(c, dict)), None)
        j.check(text == f"Cancelled wait {ids[1]}.", f"cancel_wait: the agent withdraws its second question ({text!r})")
        after = relayed.get("after") if isinstance(relayed.get("after"), dict) else {}
        status, body, _ = j.call("GET", f"/api/v1/waits/{ids[1]}", session=session, org=j.org_id)
        shown = body if isinstance(body, dict) else {}
        j.check(after.get("status") == "cancelled" and status == 200 and shown.get("status") == "cancelled",
                f"it is cancelled, for the agent and on the console ({after.get('status')}, {status} "
                f"{shown.get('status')})")

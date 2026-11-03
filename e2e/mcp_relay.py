# SPDX-License-Identifier: Apache-2.0
"""2.9 of demo/PARCOURS.md on the CLI side: an MCP server of the organisation, reached by an agent through Ostrom.

The owner adds a server on the console (`POST /api/v1/mcp/servers`, a browser session signed in minutes ago) and gives
it to everyone; Ostrom lists it to the agent (`?mine=true`, what `ostrom claude` connects); then, in an `ostrom claude`
session, the fake agent starts `ostrom mcp --session <session-env.json> --server <name>` as an agent that only starts
stdio servers does, and asks it `initialize`, `tools/list` and `tools/call`: each line goes to the session's local
relay, then to Ostrom's MCP relay, then to the server, a fake one in HTTPS on this machine. The server's address is
loopback: the workflow sets OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS=true on the server it starts, never anywhere else, and
gives the server the fake's certificate to trust.
"""
import json
import os
import shlex
import ssl
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROUTES = (("POST", "/api/v1/mcp/servers"), ("GET", "/api/v1/mcp/servers"),
          ("PUT", "/api/v1/mcp/servers/x/assignments/all"), ("GET", "/api/v1/mcp/calls"))
SAID = "hello from the agent"
MAX_BODY = 64 * 1024


class FakeMcp:
    """
    An MCP server over Streamable HTTP, in HTTPS (JSON answers): one tool, `echo`, which says back its `text`. Every
    request it is sent is recorded (method, and the arguments of a call); no real server is reached.
    """

    def __init__(self, port, cert, key):
        self.requests = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802 - the name http.server calls
                length = int(self.headers.get("content-length") or 0)
                try:
                    message = json.loads(self.rfile.read(min(length, MAX_BODY)) or b"null")
                except ValueError:
                    message = None
                if not isinstance(message, dict):
                    return self.send(400, {"jsonrpc": "2.0", "id": None,
                                           "error": {"code": -32600, "message": "one JSON-RPC message"}})
                method, params = message.get("method"), message.get("params") or {}
                fake.requests.append({"method": method, "arguments": params.get("arguments")
                                      if method == "tools/call" else None})
                if "id" not in message:
                    return self.send(202, None)
                return self.send(200, {"jsonrpc": "2.0", "id": message["id"], **answer(method, params)})

            def do_DELETE(self):  # noqa: N802 - the end of an upstream session, as the relay sends it
                self.send(200, None)

            def send(self, status, body):
                data = json.dumps(body).encode() if body is not None else b""
                self.send_response(status)
                if body is not None:
                    self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def calls(self):
        return [r["arguments"] for r in self.requests if r["method"] == "tools/call"]

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


def answer(method, params):
    """The result, or the error, of one request of the fake server."""
    if method == "initialize":
        return {"result": {"protocolVersion": params.get("protocolVersion") or "2025-06-18",
                           "capabilities": {"tools": {"listChanged": False}},
                           "serverInfo": {"name": "journey-echo", "version": "1"}}}
    if method == "tools/list":
        schema = {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}
        return {"result": {"tools": [{"name": "echo", "description": "Says back its text.", "inputSchema": schema}]}}
    if method == "tools/call" and params.get("name") == "echo":
        text = str((params.get("arguments") or {}).get("text", ""))
        return {"result": {"content": [{"type": "text", "text": f"echo: {text}"}], "isError": False}}
    return {"error": {"code": -32601, "message": "Method not found."}}


def missing_options(options):
    """The options 2.9 runs with, those not given."""
    wanted = {"--fake-mcp-port": options.fake_mcp_port, "--fake-mcp-cert": options.fake_mcp_cert,
              "--fake-mcp-key": options.fake_mcp_key}
    return [name for name, value in wanted.items() if not value]


class McpRelay:
    """2.9, run by the journey once the owner's organisation is set up (2.1) and its first session has run (5.2)."""

    def __init__(self, journey, maya, session_routes):
        self.journey = journey
        self.maya = maya
        self.options = journey.options
        self.session_routes = session_routes
        self.name = f"echo-{journey.stamp}"
        self.session = None

    def run(self):
        j = self.journey
        j.begin("2.9", "ostrom mcp: a server added on the console, listed to the agent, called through the relay")
        missing = missing_options(self.options)
        if missing:
            j.step["missing"] = missing
            if self.options.strict:
                j.check(False, f"the fake MCP server is given ({', '.join(missing)} missing)")
            else:
                j.step["status"] = "skipped"
                print(f"  skip  no fake MCP server: {', '.join(missing)}", flush=True)
            return
        if not j.needs(*ROUTES, *self.session_routes, on_main=True, org=True):
            return
        # a change of an MCP server takes a sign-in of the last minutes: the owner signs in again, not the 5.x session
        self.session = j.sign_in(self.maya["email"])
        if not self.session:
            return
        fake = FakeMcp(self.options.fake_mcp_port, self.options.fake_mcp_cert, self.options.fake_mcp_key)
        try:
            if self.added():
                self.relayed(fake)
        finally:
            fake.stop()
            self.removed()

    def added(self):
        """The owner adds the server and gives it to everyone; Ostrom lists it to the developer's token."""
        j = self.journey
        server = {"name": self.name, "url": f"https://127.0.0.1:{self.options.fake_mcp_port}/mcp",
                  "transport": "http", "scope": "shared", "enabled": True}
        status, body, _ = j.call("POST", "/api/v1/mcp/servers", server, session=self.session, org=j.org_id)
        if not j.check(status == 201, f"the owner adds {self.name} on the console ({status}): {error_text(body)}"):
            return False
        status, body, _ = j.call("PUT", f"/api/v1/mcp/servers/{self.name}/assignments/all", {},
                                 session=self.session, org=j.org_id)
        if not j.check(status == 200, f"and gives it to everyone ({status}): {error_text(body)}"):
            return False
        status, body, _ = j.call("GET", "/api/v1/mcp/servers?mine=true", token=self.maya["token"], org=j.org_id)
        mine = next((s for s in (body or {}).get("items", []) if s.get("name") == self.name), None) \
            if isinstance(body, dict) else None
        return j.check(status == 200 and mine is not None and mine.get("agentName") and mine.get("path"),
                       f"Ostrom lists it to the developer, with the name the agent sees ({status}: "
                       f"{json.dumps(mine)[:200]})")

    def relayed(self, fake):
        """A session whose agent calls the server through `ostrom mcp`: the agent's view, then the server's."""
        j = self.journey
        seen = self.session_with_bridge()
        if seen is None:
            return
        mcp = seen.get("mcp") or {}
        j.check(mcp.get("listed"), f"ostrom claude hands the agent the server in its MCP config "
                                   f"({mcp.get('servers')})")
        bridge = mcp.get("bridge") or {}
        j.check(bridge.get("code") == 0, f"ostrom mcp --session … --server {self.name} exits 0 at the end of its "
                                         f"input ({bridge.get('code')}): {str(bridge.get('stderr'))[-300:]}")
        answers = {a.get("id"): a for a in bridge.get("answers") or [] if isinstance(a, dict)}
        info = ((answers.get(1) or {}).get("result") or {}).get("serverInfo") or {}
        j.check(info.get("name") == f"ostrom-{self.name}", f"initialize: Ostrom's relay answers for it ({info})")
        tools = [t.get("name") for t in ((answers.get(2) or {}).get("result") or {}).get("tools") or []]
        j.check(tools == ["echo"], f"tools/list: the server's tool, through the relay ({tools}): "
                                   f"{json.dumps(answers.get(2))[:300]}")
        content = ((answers.get(3) or {}).get("result") or {}).get("content") or []
        text = next((c.get("text") for c in content if isinstance(c, dict)), None)
        j.check(text == f"echo: {SAID}", f"tools/call: the server's answer comes back to the agent ({text!r}): "
                                         f"{json.dumps(answers.get(3))[:300]}")
        j.check(fake.calls() == [{"text": SAID}], f"the server got the agent's call, its arguments untouched "
                                                  f"({fake.calls()})")
        status, body, _ = j.call("GET", f"/api/v1/mcp/calls?server={self.name}", session=self.session, org=j.org_id)
        # the call of the tool echo (the server's name holds "echo-" too: the quoted tool name only)
        items = body.get("items", []) if isinstance(body, dict) else []
        journaled = [c for c in items if '"echo"' in json.dumps(c)]
        j.check(status == 200 and journaled, f"the console's journal shows the call of echo ({status}, "
                                             f"{len(journaled)}): {json.dumps(items[:3])[:300]}")

    def session_with_bridge(self):
        """`ostrom claude` with the fake agent in its MCP mode: the agent's report, or None."""
        j = self.journey
        agents = fake_claude(j)
        report = os.path.join(j.work, "claude-mcp-report.json")
        extra = {"PATH": agents + os.pathsep + os.environ["PATH"], "E2E_REPORT": report, "E2E_EXIT": "0",
                 "E2E_OSTROM": j.binary, "E2E_MCP": self.name, "E2E_MCP_SAY": SAID}
        try:
            done = subprocess.run([j.binary, "claude", "-p", "hello"], cwd=self.maya["repo"], capture_output=True,
                                  text=True, env=j.environment(self.maya, False, extra), stdin=subprocess.DEVNULL,
                                  timeout=300)
        except subprocess.TimeoutExpired:
            j.check(False, "ostrom claude ends (still running after 300 s)")
            return None
        j.record(self.maya, "ostrom claude -p hello", done.returncode, done.stdout, done.stderr)
        j.check(done.returncode == 0, f"ostrom claude runs the agent and hands back its exit 0 ({done.returncode}): "
                                      f"{done.stderr.strip()[-300:]}")
        if not j.check(os.path.exists(report), "the agent was started with Ostrom's harness"):
            return None
        with open(report) as handle:
            return json.load(handle)

    def removed(self):
        j = self.journey
        status, body, _ = j.call("DELETE", f"/api/v1/mcp/servers/{self.name}", session=self.session, org=j.org_id)
        if status != 404:
            j.check(status == 204, f"the owner removes {self.name} ({status}): {error_text(body)}")


def fake_claude(journey):
    """The fake agent installed as `claude` in a folder of the run: the folder, to put first on PATH."""
    agents = os.path.join(journey.work, "agents")
    os.makedirs(agents, exist_ok=True)
    script = os.path.join(agents, "claude.py")
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_agent.py")) as source, \
            open(script, "w") as target:
        target.write(source.read())
    with open(os.path.join(agents, "claude"), "w") as handle:
        handle.write(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(script)} \"$@\"\n")
    os.chmod(os.path.join(agents, "claude"), 0o755)
    return agents


def error_text(body):
    error = body.get("error") if isinstance(body, dict) else None
    return (error.get("message", "") if isinstance(error, dict) else str(error or ""))[:200]

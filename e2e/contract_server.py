# SPDX-License-Identifier: Apache-2.0
"""The routes of ostrom-core's contract (api/openapi.yaml, /api/v1) that `ostrom <agent>` and its hooks call, answered
the way the server answers them, every request written to a log the end-to-end run reads.

It stands in for ostrom-server until the server serves launches, packages and sessions (QUESTIONS.md, CLI-AGENTS);
the run then points OSTROM_URL at the real server and keeps every assertion.

    python3 e2e/contract_server.py --port 0 --log requests.jsonl --ready ready.txt [--package vectors/package.json] \
        [--without-session-reads] [--actions N]

It keeps the sessions the hooks start, their gates and their end, and serves them back to `ostrom logs` with the
device's token (`GET /api/v1/sessions`, `GET /api/v1/sessions/{id}`); a gate, as ostrom-server, it does not give that
token (403).
`--without-session-reads` plays an older server that does not serve those reads.
`--actions N` signs another gate policy, for e2e/perf.py: N actions that gate nothing (a hook matches each tool call
against all of them) and the deployment's gate; with 0, a policy without any action. Each line of the log has its
arrival time (`at`, seconds since the epoch), so that the perf run counts the requests made while a hook ran.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import ed25519

DEVICE_TOKEN = "ost_e2e_device"
# What `GET /api/v1/health` reports, of the contract's MAJOR.MINOR as the server's own "0.1.0-dev" is
SERVER_VERSION = "0.1.0-e2e"
AGENT_TOKEN = "agt_e2e_launch"
# The instance's key in this run, which signs the gate policy (S2-20). A test key: never a real one.
POLICY_SEED = hashlib.sha256(b"ostrom-cli e2e only: instance key").digest()
POLICY_KID = "e2e-instance"

CATALOGUE = {
    "failMode": "closed",
    "actions": [
        {
            "class": "deploy",
            "label": "deploy to production",
            "tools": ["Bash"],
            "patterns": [r"\bkubectl\s+apply\b"],
            "gate": {"enforce": True, "label": "production", "approvers": ["lead"]},
        }
    ],
}


# The one gate of the run: asked by the hook of `kubectl apply`, approved by Lena.
GATE = {"id": "G-e2e", "label": "deploy to production", "status": "approved", "decidedBy": "lena",
        "at": "2026-11-02T10:00:00Z", "decidedAt": "2026-11-02T10:00:05Z", "receiptId": "R-gate"}


def now_stamp():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def instance_keys():
    """`GET /api/v1/instance/keys`, as the server answers it."""
    key = {"keyId": POLICY_KID, "algorithm": "ed25519", "publicKeyPem": ed25519.public_key_pem(POLICY_SEED),
           "firstSeenAt": "2026-10-30T00:00:00Z"}
    return {"keys": [key]}


def catalogue_of(actions):
    """The gate policy of `--actions`: [actions] that gate nothing, then the deployment's gate; with 0, no action."""
    if actions is None:
        return CATALOGUE
    if actions == 0:
        return {"failMode": "closed", "actions": []}
    others = [{"class": f"rule-{i}", "label": f"rule {i}", "tools": ["Bash"],
               "patterns": [rf"\bperf-tool-{i}\s+--write\b"]} for i in range(actions)]
    return {**CATALOGUE, "actions": others + CATALOGUE["actions"]}


def signed_policy(repo, catalogue=CATALOGUE):
    """`GET /api/v1/gates/catalog/signed?repo=`: the [catalogue] signed for [repo], valid for 10 minutes."""
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    stamp = lambda moment: moment.strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = json.dumps({"repo": repo, "issuedAt": stamp(now),
                          "expiresAt": stamp(now + datetime.timedelta(minutes=10)), **catalogue})
    signature = base64.b64encode(ed25519.sign(POLICY_SEED, payload.encode())).decode()
    return {"format": "ostrom.gates/1", "alg": "ed25519", "kid": POLICY_KID, "signature": signature,
            "payload": payload}


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):  # the log file says it all
            pass

        def _answer(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _empty(self, status):
            self.send_response(status)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def _error(self, status, kind, message):
            self._answer(status, {"error": {"type": kind, "message": message}})

        def _handle(self, method):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode() if length else ""
            url = urlsplit(self.path)
            auth = self.headers.get("Authorization", "")
            token = auth[len("Bearer "):] if auth.startswith("Bearer ") else ""
            with state["lock"]:
                state["log"].write(json.dumps({"method": method, "path": url.path, "query": url.query,
                                               "token": token, "body": raw, "at": time.time()}) + "\n")
                state["log"].flush()
            body = json.loads(raw) if raw else {}
            self._route(method, url.path, token, body, parse_qs(url.query))

        def _route(self, method, path, token, body, query):
            device = token == DEVICE_TOKEN
            agent = token == AGENT_TOKEN
            if method == "GET" and device and state["session_reads"]:
                read = self._session_read(path, query)
                if read is not None:
                    return self._answer(*read)
            if method == "GET" and device and path.startswith("/api/v1/sessions"):
                # an older server: the route does not exist, whoever asks (never the agent token's 401 below)
                return self._error(404, "not_found", f"no GET {path} on this server")
            if (method, path) == ("GET", "/api/v1/policy/agents") and device:
                return self._answer(200, {"allowed": True, "note": "e2e policy"})
            if (method, path) == ("POST", "/api/v1/launches") and device:
                return self._answer(201, {"launchId": "L-e2e", "agentToken": AGENT_TOKEN,
                                          "expiresAt": "2099-01-01T00:00:00Z"})
            if (method, path) == ("GET", "/api/v1/packages/current") and device:
                if state["package"] is None:
                    return self._error(404, "not_found", "no package for this repository")
                return self._answer(200, state["package"]["envelope"])
            if (method, path) == ("GET", "/api/v1/instance/keys"):
                return self._answer(200, instance_keys())
            if (method, path) == ("GET", "/api/v1/receipts/keys"):
                return self._answer(200, state["package"]["keys"])
            # the CLI asks only for the servers its agent reaches (`mine`), the one view a CLI token is given
            if (method, path) == ("GET", "/api/v1/mcp/servers") and device and query.get("mine") == ["true"]:
                return self._answer(200, {"items": []})
            if (method, path) == ("GET", "/api/v1/inference/v1/models"):
                return self._error(404, "not_found", "no gateway")
            # the routes the developer's own commands call (e2e/commands.py): doctor, inbox, receipts, logout
            if (method, path) == ("GET", "/api/v1/health"):
                return self._answer(200, {"status": "ok", "version": SERVER_VERSION, "checks": {"database": "ok"}})
            if (method, path) in (("GET", "/api/v1/approvals"), ("GET", "/api/v1/receipts")) and device:
                return self._answer(200, {"items": []})
            if (method, path) == ("GET", "/api/v1/receipts/W-e2e/bundle") and device:
                return self._answer(200, state["bundle"])
            if method == "GET" and re.fullmatch(r"/api/v1/receipts/[^/]+", path) and device:
                return self._error(404, "not_found", "no such receipt")
            if (method, path) == ("POST", "/api/v1/auth/logout") and device:
                return self._empty(204)
            if not agent:
                return self._error(401, "unauthorized", "the launch's agent token only")
            if (method, path) == ("GET", "/api/v1/gates/catalog/signed"):
                return self._answer(200, signed_policy(query.get("repo", [""])[0], state["catalogue"]))
            if (method, path) == ("GET", "/api/v1/gates/catalog"):
                return self._answer(200, state["catalogue"])
            if (method, path) == ("POST", "/api/v1/sessions"):
                # the id this server gives the session: the hooks name it so afterwards (gates, end)
                session = body.get("sessionId") or "seg-1"
                with state["lock"]:
                    fields = {"id": session, "agent": body.get("agent"), "dev": "maya", "repo": body.get("repo"),
                              "model": body.get("model"), "startedAt": now_stamp(), "gates": []}
                    # the server leaves out what it does not know, never a null
                    state["sessions"].setdefault(session, {k: v for k, v in fields.items() if v is not None})
                return self._answer(201, {"session": {"id": session}, "context": "[Ostrom] e2e rules"})
            if method == "POST" and path == "/api/v1/gates":
                with state["lock"]:
                    session = state["sessions"].get(body.get("sessionId"))
                    if session is not None and "G-e2e" not in session["gates"]:
                        session["gates"].append("G-e2e")
                return self._answer(201, {"gate": {"id": "G-e2e", "status": "pending", "receiptId": None}})
            if (method, path) == ("GET", "/api/v1/gates/G-e2e"):
                return self._answer(200, {"gate": GATE})
            match = re.fullmatch(r"/api/v1/sessions/([^/]+)/(alive|end|seen|reopen|workflow-receipt)", path)
            if method == "POST" and match:
                session, action = match.groups()
                if action == "end":
                    with state["lock"]:
                        kept = state["sessions"].get(session)
                        if kept is not None:
                            kept.update({"endedAt": now_stamp(), "endedBy": body.get("source") or "hook",
                                         "exitCode": body.get("exitCode"), "receiptId": "R-e2e"})
                    return self._answer(200, {"session": {"id": session, "receiptId": "R-e2e", "gates": []},
                                              "verification": {"ok": True}})
                if action == "workflow-receipt":
                    return self._answer(200, {"receipt": {"id": "W-e2e"}, "verification": {"ok": True}})
                return self._answer(200, {"id": session})
            if (method, path) == ("GET", "/api/v1/receipts/W-e2e/bundle"):
                return self._answer(200, state["bundle"])
            # the end of `ostrom <agent>`: the launch's own token ends its launch (AUDIT-SECU-RUNNER-2 R2b)
            if (method, path) == ("DELETE", "/api/v1/launches/L-e2e"):
                return self._empty(204)
            return self._error(404, "not_found", f"no {method} {path} in the e2e contract server")

        def _session_read(self, path, query):
            """`ostrom logs`' reads, as (status, body), or None for another route."""
            if path == "/api/v1/sessions":
                dev = query.get("dev", [None])[0]
                with state["lock"]:
                    items = [dict(s) for s in state["sessions"].values() if dev in (None, s["dev"])]
                return 200, {"items": items}
            match = re.fullmatch(r"/api/v1/sessions/([^/]+)", path)
            if match:
                with state["lock"]:
                    session = state["sessions"].get(match.group(1))
                    session = dict(session) if session else None
                if session is None:
                    return 404, {"error": {"type": "not_found", "message": "Session not found."}}
                return 200, {"session": session, "receipt": {"id": "R-e2e", "nodes": [
                    {"id": "n1", "workflowId": "w1", "kind": "check", "label": "Run the tests", "outcome": "passed",
                     "attestation": "workstation_declared"}]}}
            if path.startswith("/api/v1/gates/"):
                # as ostrom-server: the token of `ostrom login` (CLI_SCOPES) may not read gates
                return 403, {"error": {"type": "forbidden", "message": "Missing scope."}}
            return None

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

        def do_DELETE(self):
            self._handle("DELETE")

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--log", required=True)
    parser.add_argument("--ready", required=True)
    parser.add_argument("--package")
    parser.add_argument("--without-session-reads", action="store_true")
    parser.add_argument("--actions", type=int)
    args = parser.parse_args()
    package = json.load(open(args.package)) if args.package else None
    # W-e2e, signed by the TEST key that signs vectors/package.json: `ostrom verify W-e2e` holds once the key is pinned
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "vectors", "receipt-bundle.json")) as handle:
        bundle = json.load(handle)["bundle"]
    state = {"lock": threading.Lock(), "log": open(args.log, "a"), "package": package, "bundle": bundle,
             "sessions": {}, "session_reads": not args.without_session_reads,
             "catalogue": catalogue_of(args.actions)}
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(state))
    with open(args.ready, "w") as ready:
        ready.write(f"http://127.0.0.1:{server.server_address[1]}")
    server.serve_forever()


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
"""The routes of ostrom-core's contract (api/openapi.yaml, /api/v1) that `ostrom <agent>` and its hooks call, answered
the way the server answers them, every request written to a log the end-to-end run reads.

It stands in for ostrom-server until the server serves launches, packages and sessions (QUESTIONS.md, CLI-AGENTS);
the run then points OSTROM_URL at the real server and keeps every assertion.

    python3 e2e/contract_server.py --port 0 --log requests.jsonl --ready ready.txt [--package vectors/package.json]
"""
import argparse
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

DEVICE_TOKEN = "ost_e2e_device"
AGENT_TOKEN = "agt_e2e_launch"

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
                                               "token": token, "body": raw}) + "\n")
                state["log"].flush()
            body = json.loads(raw) if raw else {}
            self._route(method, url.path, token, body)

        def _route(self, method, path, token, body):
            device = token == DEVICE_TOKEN
            agent = token == AGENT_TOKEN
            if (method, path) == ("GET", "/api/v1/policy/agents") and device:
                return self._answer(200, {"allowed": True, "note": "e2e policy"})
            if (method, path) == ("POST", "/api/v1/launches") and device:
                return self._answer(201, {"launchId": "L-e2e", "agentToken": AGENT_TOKEN,
                                          "expiresAt": "2099-01-01T00:00:00Z"})
            if (method, path) == ("GET", "/api/v1/packages/current") and device:
                if state["package"] is None:
                    return self._error(404, "not_found", "no package for this repository")
                return self._answer(200, state["package"]["envelope"])
            if (method, path) == ("GET", "/api/v1/receipts/keys"):
                return self._answer(200, state["package"]["keys"])
            if (method, path) == ("GET", "/api/v1/mcp/servers") and device:
                return self._answer(200, {"items": []})
            if (method, path) == ("GET", "/api/v1/inference/v1/models"):
                return self._error(404, "not_found", "no gateway")
            if not agent:
                return self._error(401, "unauthorized", "the launch's agent token only")
            if (method, path) == ("GET", "/api/v1/gates/catalog"):
                return self._answer(200, CATALOGUE)
            if (method, path) == ("POST", "/api/v1/sessions"):
                session = body.get("sessionId") or "seg-1"
                return self._answer(201, {"session": {"id": session}, "context": "[Ostrom] e2e rules"})
            if method == "POST" and path == "/api/v1/gates":
                return self._answer(201, {"gate": {"id": "G-e2e", "status": "pending", "receiptId": None}})
            if (method, path) == ("GET", "/api/v1/gates/G-e2e"):
                return self._answer(200, {"gate": {"id": "G-e2e", "status": "approved", "decidedBy": "@lena",
                                                   "receiptId": "R-gate"}})
            match = re.fullmatch(r"/api/v1/sessions/([^/]+)/(alive|end|seen|reopen|workflow-receipt)", path)
            if method == "POST" and match:
                session, action = match.groups()
                if action == "end":
                    return self._answer(200, {"session": {"id": session, "receiptId": "R-e2e", "gates": []},
                                              "verification": {"ok": True}})
                if action == "workflow-receipt":
                    return self._answer(200, {"receipt": {"id": "W-e2e"}, "verification": {"ok": True}})
                return self._answer(200, {"id": session})
            if (method, path) == ("GET", "/api/v1/receipts/W-e2e/bundle"):
                return self._answer(200, {"receipt": {"id": "W-e2e"}})
            return self._error(404, "not_found", f"no {method} {path} in the e2e contract server")

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--log", required=True)
    parser.add_argument("--ready", required=True)
    parser.add_argument("--package")
    args = parser.parse_args()
    package = json.load(open(args.package)) if args.package else None
    state = {"lock": threading.Lock(), "log": open(args.log, "a"), "package": package}
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(state))
    with open(args.ready, "w") as ready:
        ready.write(f"http://127.0.0.1:{server.server_address[1]}")
    server.serve_forever()


if __name__ == "__main__":
    main()

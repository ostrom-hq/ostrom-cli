#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# S2-49: in a cloud session, the agent cannot read the runner token (moved from ostrom-server, ci/runner-isolation.sh).
# Builds the runner image (docker/runner) around the linuxX64 binary given as $1, a session image from the template
# (docker/cloud-session-image, without agents, with python3 for the probes below), and runs one session as the
# server's Docker driver does: an init container copies `ostrom` into a volume every other container mounts read-only
# at /opt/ostrom; the agent supervisor as UID 1001 with the workspace and the agent socket, the check host as UID 1001
# in a container of its own with the workspace and the check socket, the runner as UID 1000 with the token and the two
# sockets only, all in group 1000 (TeamCode's image contract). Then:
#   - the runner started the agent's terminal through the supervisor (a bash runs in the agent's container);
#   - the agent's container sees no process of another UID;
#   - nothing the agent's container can read holds the token (every process's environment and command line, and
#     every writable directory);
#   - the same search, run in the runner's container, finds the token: the search itself works;
#   - a check the agent's side asks the runner for runs on the check host, not in the agent's container: the runner
#     returns its exit code and the SHA-256 of the script it ran, the check's environment has no token, the agent's
#     container never sees the check's process nor the check host's socket;
#   - R4: a check that takes the runner's link socket and leaves a process behind changes nothing for the next one;
#   - R7: a link the agent leaves in the workspace never takes a check outside it;
#   - the agent cannot replace the session's `ostrom` binary.
# What the check proves, and what it does not: Ostrom launched the script and read its exit code, not that the agent
# left the workspace untouched (the check runs on the agent's workspace).
# Linux x64 hosts with Docker only (CI).
set -euo pipefail

binary="$1"
runner_image=ostrom-runner:isolation
image=ostrom-session:isolation
context=$(mktemp -d)
mkdir -p "$context/runner/amd64" "$context/session"
cp "$binary" "$context/runner/amd64/ostrom"
docker build --progress=plain --build-arg TARGETARCH=amd64 -f docker/runner/Dockerfile -t "$runner_image" \
  "$context/runner"
docker build --progress=plain -t "$image-base" --build-arg OSTROM_RUNNER_IMAGE="$runner_image" \
  --build-arg CLAUDE_CODE_VERSION= --build-arg CODEX_VERSION= --build-arg OPENCODE_VERSION= \
  --build-arg GEMINI_CLI_VERSION= docker/cloud-session-image
printf 'FROM %s\nUSER root\nRUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*\nUSER 1001:1000\n' \
  "$image-base" | docker build -q -t "$image" - > /dev/null

token="ostrom_runner_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
session=$(cat /proc/sys/kernel/random/uuid)
cleanup() {
  docker rm -f iso-runner iso-agent iso-checks iso-server > /dev/null 2>&1 || true
  docker volume rm iso-workspace iso-socket iso-check iso-bin > /dev/null 2>&1 || true
}
trap cleanup EXIT
fail() {
  echo "$1" >&2
  docker logs iso-agent || true
  docker logs iso-checks || true
  docker logs iso-runner || true
  exit 1
}

for volume in iso-workspace iso-socket iso-check iso-bin; do docker volume create "$volume" > /dev/null; done
hardening=(--read-only --tmpfs "/tmp:rw,nosuid,size=64m" --cap-drop ALL --security-opt no-new-privileges)
# The init container: the runner image copies its binary into the session's binary volume.
docker run --rm --user 1000:1000 "${hardening[@]}" -v iso-bin:/opt/ostrom --entrypoint cp "$runner_image" \
  /usr/local/bin/ostrom /opt/ostrom/ostrom
bin=(-v iso-bin:/opt/ostrom:ro)
docker run -d --name iso-agent --user 1001:1000 "${hardening[@]}" "${bin[@]}" \
  -v iso-workspace:/workspace -v iso-socket:/run/ostrom -w /workspace \
  -e OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock -e HOME=/workspace/.home \
  --entrypoint /opt/ostrom/ostrom "$image" cloud agent-supervisor > /dev/null
docker run -d --name iso-checks --user 1001:1000 "${hardening[@]}" "${bin[@]}" \
  -v iso-workspace:/workspace -v iso-check:/run/ostrom-check -w /workspace \
  -e OSTROM_CHECK_HOST_SOCKET=/run/ostrom-check/check.sock -e HOME=/tmp \
  --entrypoint /opt/ostrom/ostrom "$image" cloud check-host > /dev/null
# A server that serves none of the runner's routes (404), so the runner prepares nothing and the session script starts
# the agent as on a server without the recipe; it shares its network with the runner only, and keeps nothing.
docker run -d --name iso-server --user 1001:1000 "${hardening[@]}" --entrypoint python3 "$image" -c '
from http.server import BaseHTTPRequestHandler, HTTPServer
class Missing(BaseHTTPRequestHandler):
    def answer(self):
        body = b"{\"error\":{\"type\":\"not_found\",\"message\":\"Not found.\"}}"
        self.send_response(404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    do_GET = do_POST = answer
    def log_message(self, *args):
        pass
HTTPServer(("127.0.0.1", 8080), Missing).serve_forever()
' > /dev/null
docker run -d --name iso-runner --user 1000:1000 "${hardening[@]}" "${bin[@]}" --network container:iso-server \
  -v iso-socket:/run/ostrom -v iso-check:/run/ostrom-check \
  -e OSTROM_RUNNER_TOKEN="$token" -e OSTROM_SERVER_URL=http://127.0.0.1:8080 -e OSTROM_SESSION_ID="$session" \
  -e OSTROM_EPOCH=1 -e OSTROM_AGENT=shell -e OSTROM_AGENT_COMMAND=bash \
  -e OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock -e OSTROM_RUNNER_CHECKS=/run/ostrom/checks.sock \
  -e OSTROM_CHECK_HOST_SOCKET=/run/ostrom-check/check.sock -e HOME=/workspace/.home -e TERM=xterm-256color \
  --entrypoint /opt/ostrom/ostrom "$image" cloud runner > /dev/null
for _ in $(seq 1 30); do
  docker exec iso-agent pgrep -x bash > /dev/null 2>&1 && break
  sleep 1
done
docker exec iso-agent pgrep -x bash > /dev/null || fail "The agent's terminal did not start through the agent supervisor."
test "$(docker exec iso-agent id -u):$(docker exec iso-agent id -g)" = 1001:1000 || fail "The agent does not run as 1001:1000."
test "$(docker exec iso-runner id -u):$(docker exec iso-runner id -g)" = 1000:1000 || fail "The runner does not run as 1000:1000."
others=$(docker exec iso-agent ps -eo uid= | awk '$1 != 1001')
test -z "$others" || fail "The agent's container sees processes of another UID: $others"

# What a container's UID can read: every process's environment and command line, and the writable directories.
# Dumped to a file first: `grep -q` stops reading at its first match, and under pipefail the writer's SIGPIPE would
# turn a match into a failure of the pipeline.
readable() {
  docker exec "$1" sh -c 'cat /proc/[0-9]*/environ /proc/[0-9]*/cmdline 2> /dev/null;
    tar -cf - /workspace /run/ostrom /tmp /etc /var /home /root 2> /dev/null; true' > "$context/$1.dump"
}
readable iso-agent
readable iso-runner
if grep -qaF "$token" "$context/iso-agent.dump"; then fail "The agent's container can read the runner token."; fi
grep -qaF "$token" "$context/iso-runner.dump" || fail "The search did not find the token in the runner's container."
echo "S2-49: the agent (UID 1001) cannot read the runner token; the runner (UID 1000, same group) holds it."

# A check, asked by the agent's side as the hook asks it: one JSON line on the runner's checks socket.
ask_check() {
  docker exec -i iso-agent python3 -c '
import json, socket, sys
request = {"sessionId": "s-iso", "digest": "sha256:iso", "nodeId": "n-iso", "scriptId": "iso",
           "script": sys.argv[1], "directory": sys.argv[2] if len(sys.argv) > 2 else "/workspace",
           "timeoutMs": 60000}
client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
client.connect("/run/ostrom/checks.sock")
client.sendall((json.dumps(request) + "\n").encode())
reply = b""
while not reply.endswith(b"\n"):
    chunk = client.recv(65536)
    if not chunk:
        break
    reply += chunk
print(reply.decode().strip())
' "$@"
}
for _ in $(seq 1 30); do
  docker exec iso-agent test -S /run/ostrom/checks.sock && break
  sleep 1
done
docker exec iso-agent test -S /run/ostrom/checks.sock || fail "The runner does not serve the checks' socket."
# The image has the directory, empty: what the agent must not reach is the socket mounted in it.
docker exec iso-agent test ! -e /run/ostrom-check/check.sock || fail "The agent sees the check host's socket."
docker exec iso-checks test ! -e /run/ostrom/agent.sock || fail "The check host's container sees the agent's socket."

script='echo "host=$(hostname) uid=$(id -u)"; env; exit 3'
reply=$(ask_check "$script")
field() { printf '%s' "$reply" | python3 -c "import json, sys; print(json.load(sys.stdin)[sys.argv[1]])" "$1"; }
test "$(field started)" = True || fail "The check did not run: $reply"
test "$(field exit)" = 3 || fail "The runner did not read the check's exit code: $reply"
test "$(field outcome)" = failed || fail "A check that exits 3 is not failed: $reply"
expected=$(printf '%s' "$script" | sha256sum | cut -d' ' -f1)
test "$(field scriptSha)" = "$expected" || fail "The runner did not hash the script it ran: $reply"
checks_host=$(docker exec iso-checks hostname)
case "$(field outputTail)" in
  *"host=$checks_host uid=1001"*) ;;
  *) fail "The check did not run on the check host: $reply" ;;
esac
case "$(field outputTail)" in
  *"$token"*) fail "The check's environment holds the runner token." ;;
esac

# While a check runs, the agent's container does not see its process; the check host's does (the search works). The
# check's command line is computed by the shell, so that the asking client's own arguments never match it.
ask_check 'sleep $((6 + 0)).$((40 + 2))' > /dev/null &
asking=$!
sleep 2
docker exec iso-checks pgrep -f "sleep 6.42" > /dev/null || fail "The search did not find the running check."
if docker exec iso-agent pgrep -f "sleep 6.42" > /dev/null; then fail "The agent's container sees the check."; fi
wait "$asking"
readable iso-checks
if grep -qaF "$token" "$context/iso-checks.dump"; then fail "The check host's container can read the runner token."; fi
echo "Checks: run on the check host (UID 1001, no token), code and script hash read by the runner."

# R4 (audit runner 2): a check can neither take the check host's place nor leave anything to the next one. The runner
# listens and the check host links to it. A script, under the check host's uid, removes the runner's socket, listens at
# its path and leaves a process behind, both out of its process group. The next check still runs on the check host
# (the runner bound its socket again), without that process, in another home, and inherits no descriptor of the check
# host, which no script can read either (it is not dumpable).
takeover='rm -f /run/ostrom-check/check.sock
setsid python3 -c "import socket, time; s = socket.socket(socket.AF_UNIX); s.bind(\"/run/ostrom-check/check.sock\"); s.listen(); time.sleep(600)" > /dev/null 2>&1 &
setsid sleep $((500 + 7)) > /dev/null 2>&1 &
sleep 1
echo "taken=$(stat -c %u /run/ostrom-check/check.sock) home=$HOME"'
reply=$(ask_check "$takeover")
test "$(field exit)" = 0 || fail "The takeover script did not run: $reply"
first_home=$(field outputTail | sed -n 's/.*taken=1001 home=\(\/tmp\/check-[A-Za-z0-9]*\).*/\1/p')
test -n "$first_home" || fail "The script did not take the runner's socket path, or ran without a home of its own: $reply"
probe='echo "home=$HOME host=$(hostname)"
if pgrep -f "sleep 50[7]" > /dev/null; then echo residual=yes; else echo residual=no; fi
if cat /proc/1/environ > /dev/null 2>&1; then echo pid1=readable; else echo pid1=closed; fi
echo fds; ls /proc/$$/fd; echo end'
reply=$(ask_check "$probe")
test "$(field started)" = True || fail "The check after the takeover did not run: $reply"
test "$(field exit)" = 0 || fail "The check after the takeover failed: $reply"
tail_text=$(field outputTail)
case "$tail_text" in
  *"host=$checks_host"*) ;;
  *) fail "The check after the takeover did not run on the check host: $reply" ;;
esac
case "$tail_text" in *residual=no*) ;; *) fail "A process of the previous check survived it: $reply" ;; esac
case "$tail_text" in *pid1=closed*) ;; *) fail "A script can read the check host's process: $reply" ;; esac
printf '%s' "$tail_text" | tr -d '\r' | tr '\n' ' ' | grep -q "fds 0 1 2 end" \
  || fail "A script inherits a descriptor of the check host: $reply"
case "$tail_text" in
  *"home=$first_home "*) fail "Two checks shared a home: $reply" ;;
  *"home=/tmp/check-"*) ;;
  *) fail "The check had no home of its own: $reply" ;;
esac
if docker exec iso-checks pgrep -f "sleep 50[7]" > /dev/null; then fail "A process of a check outlived it."; fi
echo "R4: the runner keeps its link socket, a check leaves no process, no home and no descriptor to the next one."

# R7 (audit runner 2): a link the agent leaves in the workspace never takes a check outside it.
docker exec iso-agent ln -s / /workspace/up
reply=$(ask_check 'pwd; echo ran' /workspace/up)
test "$(field exit)" = 126 || fail "A check ran through a link out of the workspace: $reply"
case "$(field outputTail)" in *ran*) fail "The check's script ran outside the workspace: $reply" ;; esac
docker exec iso-agent rm /workspace/up
echo "R7: a check's folder is its real path, inside the workspace only."

# The group is shared (1000), so what the agent's UID cannot write rests on the modes: the agent socket is 0660, the
# binary copy and its directory belong to the runner's UID without group write.
test "$(docker exec iso-agent stat -c '%u:%g %a' /run/ostrom/agent.sock)" = "1001:1000 660" \
  || fail "The agent socket is not 1001:1000 0660."
test "$(docker exec iso-agent stat -c '%u:%g %a' /opt/ostrom /opt/ostrom/ostrom)" = "$(printf '1000:1000 755\n1000:1000 755')" \
  || fail "The session's ostrom binary is not the runner's, mode 0755."
echo "Sockets and binary: modes that keep the agent out although it shares the runner's group."

# The session's binary is the init container's copy, read-only for the agent: it cannot change what the runner runs.
if docker exec iso-agent sh -c 'echo x >> /opt/ostrom/ostrom' 2> /dev/null; then fail "The agent can change the session's ostrom binary."; fi
echo "The agent cannot replace the session's ostrom binary."

# Cloud session image

A cloud session runs in containers built from two images:

| Setting | What it is |
| --- | --- |
| Session image: the profile's `image`, or a session override its `allowedImageOverrides` allows | The image every container of the session runs: the agent, the runner and the check host. Your toolchain lives here. |
| Runner image and runner binary: the dashboard's cloud runtime settings | An image holding the `ostrom` Linux binary (`ghcr.io/ostrom-hq/ostrom-runner`, binary `/usr/local/bin/ostrom`). An init container copies the binary out of it before the other containers start. |

Ostrom imposes no session image. [`Dockerfile`](Dockerfile) is a template: it installs what Ostrom needs and leaves one block for your tools. It also copies `ostrom` to `/usr/local/bin/ostrom`, so the same image can serve as the runner image.

## What the session sets, and what the image does not have to

The server builds each container itself. None of the following comes from the image:

- **Commands.** The init container runs `cp <runner binary> /opt/ostrom/ostrom` into a volume. Every other container mounts that volume read-only and runs `/opt/ostrom/ostrom cloud agent-supervisor`, `cloud runner` or `cloud check-host`. An `ENTRYPOINT` or `CMD` in the image is never used.
- **Users.**
  - The agent and the check host run as UID 1000, the runner as UID 1001. They share group 2000 for the socket directory `/run/ostrom`.
  - All run non-root, with no privilege escalation and all capabilities dropped.
  - `HOME` is `/workspace/.home`, on the workspace volume.
- **Credentials.** The runner token reaches the runner's container only. The agent's git and `gh` get GitHub through the runner (`GIT_CONFIG_*`, `GH_CONFIG_DIR`), never through the image.
- **The binary.** Every container runs the copy made by the init container. The agent's `PATH` starts with `/opt/ostrom`, so its hooks and MCP servers (`ostrom …`) run that binary too.

## What the image must provide

The template installs all of this before your block and checks it after:

- Linux with glibc, `/bin/sh`, `bash` and the POSIX commands the session scripts use.
- The libraries the `ostrom` binary loads: libcurl, OpenSSL 3 and zlib.
- `git` 2.31 or later.
- The agent CLIs on `PATH`, installed system-wide: `claude`, `codex`, `opencode`, `gemini`, as your profiles' agents require. Claude Code's self-update is turned off.
- Users `agent` (1000) and `runner` (1001) in group `ostrom` (2000).
- `/workspace` owned by UID 1000.
- `/run/ostrom` and `/run/ostrom-check` owned by 1000:2000 with mode 2770, so the volumes mounted there start with these modes.
- `gh` is optional.

## Adding your tools

Put your setup between the two markers and leave the rest of the file as it is:

```dockerfile
# >>> Insert your tools setup here <<<
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless python3 \
    && rm -rf /var/lib/apt/lists/*
# <<< End of your tools setup >>>
```

- The block runs as root. Install system-wide (`/usr`, `/usr/local`, `/opt`) and keep files readable and executable by UIDs 1000 and 1001. `HOME` is on the workspace volume in a session, so anything installed under a home directory at build time is not there.
- Do not add credentials, registry logins or tokens: anyone who can pull the image can read them.
- The contract check after the block fails the build if your setup broke something Ostrom needs.

## Building and using it

```sh
docker build --build-arg OSTROM_RUNNER_IMAGE=ghcr.io/ostrom-hq/ostrom-runner:<version>@sha256:<digest> \
  -t registry.example.org/ostrom-session:2026-11-13 docker/cloud-session-image
```

| Build argument | Purpose |
| --- | --- |
| `OSTROM_RUNNER_IMAGE` | The runner image whose `ostrom` is copied in: your server's version, pinned by digest. |
| `CLAUDE_CODE_VERSION`, `CODEX_VERSION`, `OPENCODE_VERSION`, `GEMINI_CLI_VERSION` | Exact agent versions. Empty leaves one out; with all empty, Node.js is not installed. |
| `NODE_MAJOR` | The Node.js line for the agents. |
| `BASE_IMAGE` | A Debian or Ubuntu base. Others need the package step adapted. |

Push the image to a registry the cluster can pull from, and set the profile's `image` to it. A private registry needs a pull secret in the session namespace, named in the profile's `imagePullSecrets`.

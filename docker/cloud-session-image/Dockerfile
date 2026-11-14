# syntax=docker/dockerfile:1
#
# Template for an Ostrom cloud session image. See README.md next to this file.
#
# Copy this file, put your tools in the block marked "Insert your tools setup here" and leave the rest as it is. The
# rest is what Ostrom needs from the image:
#
#   - the libraries and commands the copied `ostrom` binary and the session's scripts use (glibc, libcurl, OpenSSL 3,
#     zlib, /bin/sh, bash, coreutils, git 2.31 or later);
#   - the agent CLIs the runner starts by name (`claude`, `codex`, `opencode`, `gemini`), installed system-wide, since
#     HOME is on the workspace volume in a session;
#   - named accounts for the session's fixed UIDs, TeamCode's (runner 1000, agent 1001, group 1000), so an image built
#     for TeamCode runs on Ostrom; the check host runs as the agent's UID; /workspace and the socket directories
#     /run/ostrom and /run/ostrom-check belong to the agent's UID and the group;
#   - the `ostrom` binary at /usr/local/bin/ostrom (optional), so this image can also be the runner image.
#
# What the image must NOT contain: an entrypoint a session relies on (the server sets the command of every
# container), an Ostrom token, a provider or GitHub credential, or a kubeconfig. The server gives the runner its token
# at run time, in the runner's container only.
#
# Build: docker build --build-arg OSTROM_RUNNER_IMAGE=ghcr.io/ostrom-hq/ostrom-runner:<version> -t <image>:<tag> .

ARG BASE_IMAGE=debian:bookworm-slim
# The runner image whose `ostrom` binary goes into this one: the version your server runs, pinned by digest.
ARG OSTROM_RUNNER_IMAGE=ghcr.io/ostrom-hq/ostrom-runner:edge

FROM ${OSTROM_RUNNER_IMAGE} AS ostrom

FROM ${BASE_IMAGE}

# Node.js for the agent CLIs (the major line of NodeSource's packages). Empty with every agent version empty skips it.
ARG NODE_MAJOR=22
# Agent CLIs. Empty skips one; pin exact versions so a rebuild gives the same agent.
ARG CLAUDE_CODE_VERSION=2.1.314
ARG CODEX_VERSION=0.166.0
ARG OPENCODE_VERSION=1.19.5
ARG GEMINI_CLI_VERSION=0.66.0

ARG DEBIAN_FRONTEND=noninteractive
# DISABLE_AUTOUPDATER: Claude Code must not update itself in a session, the image pins its version.
ENV LANG=C.UTF-8 \
    DISABLE_AUTOUPDATER=1 \
    NPM_CONFIG_UPDATE_NOTIFIER=false

SHELL ["/bin/sh", "-eu", "-c"]

# --- Ostrom requirements (do not edit) -----------------------------------------------------------

# Runtime libraries of the ostrom binary, git, bash for the session's shells, and what the steps below download with.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash ca-certificates curl git gnupg less libcurl4 libssl3 openssh-client procps zlib1g \
    && rm -rf /var/lib/apt/lists/*

# Node.js and the agent CLIs, system-wide under /usr.
RUN if [ -z "${CLAUDE_CODE_VERSION}${CODEX_VERSION}${OPENCODE_VERSION}${GEMINI_CLI_VERSION}" ]; then exit 0; fi \
    && curl -fsSL --retry 3 https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
       | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
       > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update && apt-get install -y --no-install-recommends nodejs && rm -rf /var/lib/apt/lists/* \
    && packages="" \
    && if [ -n "${CLAUDE_CODE_VERSION}" ]; then packages="${packages} @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"; fi \
    && if [ -n "${CODEX_VERSION}" ]; then packages="${packages} @openai/codex@${CODEX_VERSION}"; fi \
    && if [ -n "${OPENCODE_VERSION}" ]; then packages="${packages} opencode-ai@${OPENCODE_VERSION}"; fi \
    && if [ -n "${GEMINI_CLI_VERSION}" ]; then packages="${packages} @google/gemini-cli@${GEMINI_CLI_VERSION}"; fi \
    && npm install --global --no-fund --no-audit ${packages} \
    && npm cache clean --force \
    && rm -rf /root/.npm /tmp/*

# Accounts for the session's fixed UIDs, TeamCode's: `runner` (1000) and `agent` (1001, the agent and the check host),
# both in group `ostrom` (1000). The server sets the UIDs itself; the names keep tools that look up the current user
# working. A base image's own UID or GID 1000 or UID 1001 (the `ubuntu` user of ubuntu:24.04) is removed first.
# /workspace and the socket directories belong to the agent's UID and the group, setgid, so the volumes mounted on them
# start with these modes (Docker; on Kubernetes the pod's fsGroup 1000 does the same).
RUN for uid in 1000 1001; do \
        user="$(getent passwd "${uid}" | cut -d: -f1)"; \
        if [ -n "${user}" ]; then userdel "${user}"; fi; \
    done \
    && group="$(getent group 1000 | cut -d: -f1)" \
    && if [ -n "${group}" ]; then groupdel "${group}"; fi \
    && groupadd --gid 1000 ostrom \
    && useradd --uid 1000 --gid 1000 --no-create-home --home-dir /workspace/.home --shell /bin/bash runner \
    && useradd --uid 1001 --gid 1000 --no-create-home --home-dir /workspace/.home --shell /bin/bash agent \
    && mkdir -p /workspace /run/ostrom /run/ostrom-check /opt/ostrom \
    && chown 1001:1000 /workspace \
    && chown 1001:1000 /run/ostrom /run/ostrom-check && chmod 2770 /run/ostrom /run/ostrom-check

# The ostrom binary, so this image can also be the runner image (the session runs the copy the init container makes).
COPY --from=ostrom --chmod=0755 /usr/local/bin/ostrom /usr/local/bin/ostrom

# --- Your tools ----------------------------------------------------------------------------------
# Runs as root. Install system-wide (/usr, /usr/local, /opt) and keep everything readable and executable by UIDs 1000
# and 1001: in a session, HOME is on the workspace volume, so anything installed under a home directory here is not
# visible there. Do not add credentials.

# The tools block runs with the default shell (/bin/sh -c); set SHELL inside it if you need another.
SHELL ["/bin/sh", "-c"]

# >>> Insert your tools setup here <<<

# <<< End of your tools setup >>>

SHELL ["/bin/sh", "-eu", "-c"]

# --- Ostrom contract check (do not edit) ---------------------------------------------------------

# Fails the build when the image no longer gives Ostrom what it needs, for example when a tools setup removed git, a
# command the session's preparation runs (clone staging, setup markers), or a library the binary loads (linked or
# opened at run time; an executable file, not a shell builtin), and runs the binary as both session UIDs.
RUN as() { setpriv --reuid="$1" --regid=1000 --clear-groups env HOME=/tmp "$2" "$3" >/dev/null; }; \
    on_path() ( IFS=:; for directory in ${PATH}; do [ -x "${directory}/$1" ] && exit 0; done; exit 1 ); \
    for command in sh bash mkdir rm mv rmdir dirname basename touch test cat git; do \
        on_path "${command}" || { echo "Missing ${command}" >&2; exit 1; }; \
    done \
    && git_version="$(git --version | sed 's/^git version //')" \
    && if ! printf '2.31.0\n%s\n' "${git_version}" | sort -C -V; then echo "git ${git_version} is older than 2.31" >&2; exit 1; fi \
    && [ "$(id -u runner):$(id -g runner):$(id -u agent):$(id -g agent)" = "1000:1000:1001:1000" ] \
    && if ldd /usr/local/bin/ostrom | grep 'not found'; then echo "ostrom is missing a library" >&2; exit 1; fi \
    && for library in libcrypto.so.3 libcurl.so.4 libz.so.1; do \
        ldconfig -p | grep -qF "${library} (" || { echo "Missing ${library}" >&2; exit 1; }; \
    done \
    && as 1000 /usr/local/bin/ostrom version \
    && as 1001 /usr/local/bin/ostrom version \
    && if [ -n "${CLAUDE_CODE_VERSION}" ]; then as 1001 claude --version; fi \
    && if [ -n "${CODEX_VERSION}" ]; then as 1001 codex --version; fi \
    && rm -rf /tmp/* /tmp/.[!.]*

WORKDIR /workspace
USER 1001:1000

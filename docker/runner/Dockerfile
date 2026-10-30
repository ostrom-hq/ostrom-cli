# syntax=docker/dockerfile:1
# Runner image for Ostrom cloud sessions: the `ostrom` CLI plus the agents it drives.
#
# Build context: a directory holding the release binaries as <context>/amd64/ostrom and <context>/arm64/ostrom
# (the linuxX64 and linuxArm64 builds). TARGETARCH picks the right one:
#   docker buildx build --platform linux/amd64,linux/arm64 -f docker/runner/Dockerfile <context>
FROM node:24-bookworm-slim

ARG TARGETARCH
# Pinned: bump on purpose, after trying the new version in a cloud session.
ARG CLAUDE_CODE_VERSION=2.1.312
ARG CODEX_VERSION=0.166.0

LABEL org.opencontainers.image.source="https://github.com/ostrom-hq/ostrom-cli" \
      org.opencontainers.image.description="Ostrom cloud session runner" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apt-get update \
 && apt-get install -y --no-install-recommends git openssh-client ca-certificates libcurl4 ripgrep \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g --omit=dev --no-fund --no-audit \
      "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
      "@openai/codex@${CODEX_VERSION}" \
 && npm cache clean --force

COPY --chmod=0755 ${TARGETARCH}/ostrom /usr/local/bin/ostrom

# Non-root, fixed uid so volumes and the cloud scheduler can rely on it. The base image's `node` user (uid 1000) is
# left alone and unused.
RUN groupadd --gid 10001 ostrom \
 && useradd --uid 10001 --gid 10001 --create-home --shell /bin/bash ostrom \
 && mkdir -p /workspace && chown ostrom:ostrom /workspace
USER 10001:10001
ENV HOME=/home/ostrom
WORKDIR /workspace

ENTRYPOINT ["ostrom"]

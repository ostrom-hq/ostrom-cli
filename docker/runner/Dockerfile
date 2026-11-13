# syntax=docker/dockerfile:1
# The runner image of Ostrom cloud sessions (ghcr.io/ostrom-hq/ostrom-runner): the `ostrom` Linux binary at
# /usr/local/bin/ostrom (mode 0755), and what `cp` and `ostrom cloud machine` need. A session's init container copies
# the binary out of it into the pod (/opt/ostrom/ostrom); the session's containers run the team's own image
# (docker/cloud-session-image). A registered machine runs it as `ostrom cloud machine`.
#
# Build context: a directory holding the release binaries as <context>/amd64/ostrom and <context>/arm64/ostrom
# (the linuxX64 and linuxArm64 builds). TARGETARCH picks the right one:
#   docker buildx build --platform linux/amd64,linux/arm64 -f docker/runner/Dockerfile <context>
FROM debian:bookworm-slim

ARG TARGETARCH

LABEL org.opencontainers.image.source="https://github.com/ostrom-hq/ostrom-cli" \
      org.opencontainers.image.description="Ostrom cloud session runner: the ostrom binary the sessions copy in" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates libcurl4 \
 && rm -rf /var/lib/apt/lists/*

COPY --chmod=0755 ${TARGETARCH}/ostrom /usr/local/bin/ostrom

# Non-root, and none of a session's UIDs (1000, 1001): the init container copies the binary into /opt/ostrom, which a
# Docker volume mounted there inherits on first use, and every other container mounts read-only. `ostrom cloud machine`
# needs no privilege either.
RUN mkdir -p /opt/ostrom && chown 65532:65532 /opt/ostrom
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/ostrom"]

# Multi-stage: build with JDK, run with JRE
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY src src
RUN ./gradlew --no-daemon shadowJar

# Runtime
FROM eclipse-temurin:21-jre-jammy

# TARGETARCH is set automatically by `docker buildx` per platform.
ARG TARGETARCH
ARG TAILSCALE_VERSION=1.74.0

# Install tailscale CLI (for subprocess invocations) and wget.
# wget is kept in the final image because the Ansible-rendered Compose-level
# healthcheck invokes `wget -qO- http://<tailscale_ip>:8091/health`. curl is
# only needed at build time to fetch the Tailscale tarball, and is purged
# afterwards to keep the runtime image lean.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl wget ca-certificates && \
    case "$TARGETARCH" in \
      amd64) TS_ARCH=amd64 ;; \
      arm64) TS_ARCH=arm64 ;; \
      *) echo "unsupported TARGETARCH: $TARGETARCH" && exit 1 ;; \
    esac && \
    curl -fsSL "https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}.tgz" \
      | tar -xz -C /tmp && \
    mv "/tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}/tailscale" /usr/bin/tailscale && \
    chmod +x /usr/bin/tailscale && \
    rm -rf "/tmp/tailscale_${TAILSCALE_VERSION}_${TS_ARCH}" && \
    apt-get purge -y curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/build/libs/dns-ha-agent.jar /app/dns-ha-agent.jar
COPY config/example.yaml /etc/dns-ha-agent/example.yaml

# NO Dockerfile-level HEALTHCHECK.
#
# The agent binds /health to the node's Tailscale IP (NOT 0.0.0.0), so the
# bind address is not known at image-build time. The HEALTHCHECK is defined
# in the Ansible-rendered docker-compose.yml instead (Phase 6), where the
# Tailscale IP can be templated in. That Compose-level healthcheck is
# DIAGNOSTIC ONLY — it does NOT trigger restarts. Recovery from hangs comes
# from the agent self-exiting via its internal WatchdogThread (Phase 4) +
# `restart: unless-stopped`.

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/dns-ha-agent.jar", "--config", "/etc/dns-ha-agent/config.yaml"]

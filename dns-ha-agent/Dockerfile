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
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="dns-ha-agent" \
      org.opencontainers.image.source="https://github.com/IcyNoctiluca/HA-DNS" \
      org.opencontainers.image.revision="${VCS_REF}" \
      io.homelab.dnsha.release-contract-version="1" \
      io.homelab.dnsha.config-schema-version="1" \
      io.homelab.dnsha.health-schema-version="1" \
      io.homelab.dnsha.metrics-contract-version="1" \
      io.homelab.dnsha.route-plan-schema-version="1"

# Install tailscale CLI (for subprocess invocations) and wget.
# wget is retained as a small runtime diagnostic client. curl is only needed
# at build time to fetch the Tailscale tarball, and is purged afterwards to
# keep the runtime image lean.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl wget ca-certificates && \
    EFFECTIVE_ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    case "$EFFECTIVE_ARCH" in \
      amd64) TS_ARCH=amd64 ;; \
      arm64) TS_ARCH=arm64 ;; \
      *) echo "unsupported build architecture: $EFFECTIVE_ARCH" && exit 1 ;; \
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
COPY contract /opt/dns-ha-agent/contract

# NO Dockerfile-level HEALTHCHECK. The agent binds /health to the node's
# Tailscale IP (NOT 0.0.0.0), so a producer image cannot know the address.
# Health supervision and restart policy are consumer deployment concerns.
# Independently of that supervisor, the agent self-exits on a hang through
# its internal WatchdogThread.

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/dns-ha-agent.jar", "--config", "/etc/dns-ha-agent/config.yaml"]

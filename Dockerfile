# SignalBot (Kotlin) Dockerfile
# Multi-stage build: compile a fat jar, bundle signal-cli, run on a slim JRE image.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy Gradle wrapper + build definition first for better layer caching
COPY signalbot-kt/gradlew signalbot-kt/gradlew.bat ./
COPY signalbot-kt/gradle ./gradle
COPY signalbot-kt/settings.gradle.kts signalbot-kt/build.gradle.kts signalbot-kt/gradle.properties ./
RUN chmod +x ./gradlew && ./gradlew --version

# Copy sources and build the shadow (fat) jar
COPY signalbot-kt/src ./src
RUN ./gradlew --no-daemon shadowJar

# ---------- signal-cli (patched) ----------
# Build signal-cli from the upstream tag and apply local patches in
# docker/signal-cli-patches/ (e.g. store profile keys for v2 *requesting* members
# so the bot can show names for join-queue users). Official release tarballs
# are not modified; we compile from source here.
# Java 25 is required by signal-cli v0.14.x. Keep SIGNAL_CLI_VERSION in sync
# with the Kotlin runtime below when bumping signal-cli.
FROM eclipse-temurin:25-jdk AS signal-cli
WORKDIR /src
ARG SIGNAL_CLI_VERSION=0.14.3
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m"
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
COPY docker/signal-cli-patches/ /patches/
RUN git clone --depth 1 --branch "v${SIGNAL_CLI_VERSION}" \
        https://github.com/AsamK/signal-cli.git . \
    && git apply /patches/*.patch \
    && chmod +x ./gradlew \
    && ./gradlew --no-daemon installDist \
    && /src/build/install/signal-cli/bin/signal-cli --version

# ---------- runtime stage ----------
# Java 25 is required by signal-cli v0.14.x. The Kotlin fat jar is built for
# JVM 21 bytecode (see build.gradle.kts `jvmToolchain(21)`), which Java 25
# runs natively thanks to JVM backward compatibility. When bumping signal-cli
# across a Java-version bump, update both this base image and SIGNAL_CLI_VERSION
# in the same commit.
FROM eclipse-temurin:25-jre
WORKDIR /app

# Runtime deps:
#   ca-certificates - TLS to Signal servers
#   curl            - healthcheck
#   tar             - ad-hoc archive ops
#   bash            - entrypoint.sh
#   tini            - PID 1 signal forwarding + zombie reaping
#   gosu            - drop-privileges helper used by entrypoint.sh to step
#                     down from root (needed to fix volume perms at start)
#                     to the non-root app user
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      tar \
      bash \
      tini \
      gosu \
    && rm -rf /var/lib/apt/lists/*

# Install signal-cli to /opt/signal-cli (built from source in stage `signal-cli`)
COPY --from=signal-cli /src/build/install/signal-cli/ /opt/signal-cli/
RUN ln -s /opt/signal-cli/bin/signal-cli /usr/local/bin/signal-cli \
 && signal-cli --version

COPY --from=build /build/build/libs/signalbot.jar /app/signalbot.jar
COPY signalbot-kt/src/main/resources/config.example.yaml /app/config.example.yaml
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# The eclipse-temurin:25-jre image is built on Ubuntu Noble (24.04), which
# ships a default `ubuntu` user + group at UID/GID 1000. We want our app
# user at 1000 for a consistent non-root runtime across hosts, so evict the
# placeholder user first. The `|| true` keeps this idempotent against any
# future base image that no longer pre-creates it.
RUN mkdir -p /data \
 && (userdel -r ubuntu  2>/dev/null || true) \
 && (groupdel    ubuntu 2>/dev/null || true) \
 && useradd -m -u 1000 signalbot \
 && chown -R signalbot:signalbot /app /opt/signal-cli

# We intentionally start as root so entrypoint.sh can chown the runtime-
# mounted /data volume (Railway / Compose / k8s all mount it root-owned on
# first boot, which would otherwise trap the non-root signalbot user on a
# read-only directory). entrypoint.sh drops privileges via `gosu signalbot`
# immediately after fixing /data perms, so the long-running JVM + signal-cli
# daemon never run as root.

ENV SIGNALBOT_CONFIG=/data/config.yaml \
    SIGNALBOT_DB=/data/signalbot.db \
    SIGNALBOT_UI_HOST=0.0.0.0 \
    SIGNAL_CLI_DATA=/data/signal-cli \
    SIGNAL_CLI_TCP=127.0.0.1:7583 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 5000

# First boot of signal-cli can take 30-60s to touch its caches.
HEALTHCHECK --interval=60s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${SIGNALBOT_UI_PORT:-${PORT:-5000}}/health || exit 1

# tini reaps zombies and forwards signals cleanly when entrypoint.sh forks the
# signal-cli daemon. Expressed in CMD (not ENTRYPOINT) so that Railway's
# `startCommand` override lines up with this command without ever double-invoking
# tini on top of itself.
CMD ["/usr/bin/tini", "--", "/app/entrypoint.sh"]

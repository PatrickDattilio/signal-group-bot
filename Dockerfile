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

# ---------- runtime stage ----------
# Java 25 is required by signal-cli v0.14.x. The Kotlin fat jar is built for
# JVM 21 bytecode (see build.gradle.kts `jvmToolchain(21)`), which Java 25
# runs natively thanks to JVM backward compatibility. When bumping signal-cli
# across a Java-version bump, update both this base image and SIGNAL_CLI_VERSION
# in the same commit.
FROM eclipse-temurin:25-jre
WORKDIR /app

ARG SIGNAL_CLI_VERSION=0.14.3

# Runtime deps: ca-certificates for TLS to Signal servers, curl for healthcheck,
# tar/bash for signal-cli install + entrypoint, tini for clean PID 1 signal handling.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      tar \
      bash \
      tini \
    && rm -rf /var/lib/apt/lists/*

# Install signal-cli to /opt/signal-cli and symlink into PATH.
RUN curl -fsSL -o /tmp/signal-cli.tar.gz \
      "https://github.com/AsamK/signal-cli/releases/download/v${SIGNAL_CLI_VERSION}/signal-cli-${SIGNAL_CLI_VERSION}.tar.gz" \
 && tar -xzf /tmp/signal-cli.tar.gz -C /opt/ \
 && mv "/opt/signal-cli-${SIGNAL_CLI_VERSION}" /opt/signal-cli \
 && ln -s /opt/signal-cli/bin/signal-cli /usr/local/bin/signal-cli \
 && rm /tmp/signal-cli.tar.gz \
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
 && chown -R signalbot:signalbot /app /data /opt/signal-cli
USER signalbot

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

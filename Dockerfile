# ============================================
# Portfolio Blog API - Multi-stage Dockerfile
# Java 25 + Spring Boot 4 + WebFlux
# Optimized: layered JARs + BuildKit cache
#
# SUPPLY CHAIN (L-8): base images pinned by digest (multi-arch index digest).
# Refresh with:
#   docker buildx imagetools inspect eclipse-temurin:25-jdk-alpine
#   docker buildx imagetools inspect eclipse-temurin:25-jre-noble
# Dependabot's docker ecosystem keeps the digests fresh now that they are pinned.
# ============================================

# Stage 1: Build with Maven cache
FROM eclipse-temurin:25-jdk-alpine@sha256:09349d79941fd53bb3d487b393ca118d8853c08c09193f416fe6a8718df9e732 AS builder
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

# Resolve dependencies first (cached between builds via BuildKit mount)
ARG NEXUS_HOST=""
RUN --mount=type=cache,target=/root/.m2/repository \
    MAVEN_SETTINGS=""; \
    if [ -n "$NEXUS_HOST" ]; then \
      sed -i "s|__NEXUS_HOST__|${NEXUS_HOST}|g" .mvn/nexus-settings.xml; \
      MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"; \
    fi && \
    ./mvnw dependency:go-offline -B $MAVEN_SETTINGS

COPY src ./src

# -Prelease-jar (A5/H3): strips dev-only resources (application-dev/local/
# e2e/nitro.properties with committed dev credentials + dev/ seed data) from
# the production JAR. See the release-jar profile in pom.xml.
RUN --mount=type=cache,target=/root/.m2/repository \
    MAVEN_SETTINGS=""; \
    if [ -n "$NEXUS_HOST" ]; then \
      MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"; \
    fi && \
    ./mvnw clean package -Dmaven.test.skip=true -B -Prelease-jar $MAVEN_SETTINGS

# Extract Spring Boot layered JAR for optimal Docker caching
# Splits 300MB fat JAR into: dependencies (~250MB, cached) + application (~10-50MB, changes per deploy)
RUN cp target/*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination /app/extracted

# Download Datadog agent with version pinning and checksum verification (Q5.1)
ARG DD_JAVA_AGENT_VERSION=1.63.0
ARG DD_JAVA_AGENT_SHA256=6a8df6bb668bb2ca4318112a9c39e99737663fe1eb887cab02f9f46b59f7418a
RUN wget -q -O /tmp/dd-java-agent.jar \
      "https://github.com/DataDog/dd-trace-java/releases/download/v${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar" && \
    echo "${DD_JAVA_AGENT_SHA256}  /tmp/dd-java-agent.jar" | sha256sum -c -

# Stage 2: Runtime (Debian — Playwright's Chromium requires glibc)
FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e AS runtime

LABEL org.opencontainers.image.title="Portfolio Blog API" \
      org.opencontainers.image.version="2.0.0" \
      org.opencontainers.image.source="https://github.com/catananti/portfolio-blog"

# Q5.15: PLAYWRIGHT_REMOTE=true skips Chromium install (~500MB savings).
# Chromium then runs in a sidecar container (deploy/cloud/playwright/).
ARG PLAYWRIGHT_REMOTE=false

# Daily cache-bust token — CI passes the build date. Without it, BuildKit's
# layer cache (type=gha) served a STALE `apt-get upgrade`: openssl
# 3.0.13-0ubuntu3.9 lingered after Ubuntu shipped the fix (…3.11) for
# CVE-2026-45447, and Trivy's fixable-HIGH gate failed the build. Referencing
# the token in the RUN re-executes apt with fresh metadata at most once a day,
# so OS security patches stay current. Node.js is the Playwright PDF driver.
ARG APT_SECURITY_REFRESH=manual
RUN echo "apt security refresh: ${APT_SECURITY_REFRESH}" \
    && apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends \
      nodejs \
      npm \
      wget \
    && rm -rf /var/lib/apt/lists/*

# Install Playwright's compatible Chromium + all system dependencies
# Skipped when using remote browser sidecar (PLAYWRIGHT_REMOTE=true)
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN if [ "$PLAYWRIGHT_REMOTE" = "false" ]; then \
      npx -y playwright@1.51.0 install --with-deps chromium; \
    else \
      echo "Skipping Chromium install (PLAYWRIGHT_REMOTE=true — using sidecar)"; \
      mkdir -p /ms-playwright; \
    fi

# Datadog APM agent (copied from builder — no curl needed at runtime)
COPY --from=builder /tmp/dd-java-agent.jar /opt/datadog/dd-java-agent.jar

# Playwright: system Node.js for Playwright driver
ENV PLAYWRIGHT_NODEJS_PATH=/usr/bin/node
# Prevent Playwright Java from re-downloading browsers at runtime
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Non-root user + writable dirs (before COPY to avoid chown layer duplication)
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

RUN mkdir -p /app/uploads /app/logs && \
    chown -R appuser:appgroup /app /tmp && \
    chmod -R o+rx /ms-playwright

# Spring Boot layered JAR: each COPY = separate Docker layer
# Dependencies (~250MB) rarely change → cached between deploys
COPY --from=builder --chown=appuser:appgroup /app/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /app/extracted/snapshot-dependencies/ ./
# Application code (~10-50MB) — only this layer rebuilds per deploy
COPY --from=builder --chown=appuser:appgroup /app/extracted/application/ ./

USER appuser
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM tuned for containers. -Djava.security.egd avoids start-up entropy
# stalls in container environments where /dev/random can block.
# UseContainerSupport is default-on since Java 11 but kept explicit for
# auditability. -XX:MaxRAMPercentage respects cgroup memory limits.
# -Duser.timezone=UTC (AUD19C-4): pins the invariant that every LocalDateTime in
# the system is a UTC instant — LocalDateTime.now(), DB TIMESTAMP columns and the
# 'Z'-suffixed JSON serialization (JacksonConfig) all assume it; dev machines may
# run UTC-3 but production must never inherit a host zone.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom -Duser.timezone=UTC" \
    DD_AGENT_ENABLED=false \
    DD_SERVICE=portfolio-blog-api \
    DD_ENV=production \
    DD_VERSION=2.0.0 \
    DD_LOGS_INJECTION=true \
    DD_TRACE_SAMPLE_RATE=0.1 \
    DD_PROFILING_ENABLED=false

ENTRYPOINT ["sh", "-c", "if [ \"$DD_AGENT_ENABLED\" = \"true\" ]; then exec java $JAVA_OPTS -javaagent:/opt/datadog/dd-java-agent.jar -jar application.jar; else exec java $JAVA_OPTS -jar application.jar; fi"]

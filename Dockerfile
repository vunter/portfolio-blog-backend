# ============================================
# Portfolio Blog API - Multi-stage Dockerfile
# Java 25 + Spring Boot 4 + WebFlux
# Optimized: layered JARs + BuildKit cache
# ============================================

# Stage 1: Build with Maven cache
FROM eclipse-temurin:25-jdk-alpine AS builder
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

RUN --mount=type=cache,target=/root/.m2/repository \
    MAVEN_SETTINGS=""; \
    if [ -n "$NEXUS_HOST" ]; then \
      MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"; \
    fi && \
    ./mvnw clean package -Dmaven.test.skip=true -B $MAVEN_SETTINGS

# Extract Spring Boot layered JAR for optimal Docker caching
# Splits 300MB fat JAR into: dependencies (~250MB, cached) + application (~10-50MB, changes per deploy)
RUN cp target/*.jar application.jar && \
    java -Djarmode=tools -jar application.jar extract --layers --destination /app/extracted

# Download Datadog agent in build stage (keeps wget out of runtime)
RUN wget -q -O /tmp/dd-java-agent.jar https://dtdg.co/latest-java-tracer

# Stage 2: Runtime (Alpine, minimal)
FROM eclipse-temurin:25-jre-alpine AS runtime

LABEL maintainer="Leonardo Catananti <leonardo.catananti@gmail.com>" \
      version="2.0.0" \
      description="Portfolio Blog API"

# Node.js + system deps for Playwright PDF generation
# ttf-freefont covers Latin/European; font-noto-cjk removed (saves ~300MB)
RUN apk add --no-cache \
      nss \
      freetype \
      harfbuzz \
      ca-certificates \
      ttf-freefont \
      nodejs \
      npm

# Install Playwright's own Chromium + its required system dependencies
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npx -y playwright@1.51.0 install --with-deps chromium

# Datadog APM agent (copied from builder — no curl needed at runtime)
COPY --from=builder /tmp/dd-java-agent.jar /opt/datadog/dd-java-agent.jar

# Playwright: use Playwright-managed Chromium + system Node.js
ENV PLAYWRIGHT_NODEJS_PATH=/usr/bin/node

# Non-root user + writable dirs (before COPY to avoid chown layer duplication)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

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

# JVM tuned for containers; DD agent conditionally loaded
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC" \
    DD_AGENT_ENABLED=false \
    DD_SERVICE=portfolio-blog-api \
    DD_ENV=production \
    DD_VERSION=2.0.0 \
    DD_LOGS_INJECTION=true \
    DD_TRACE_SAMPLE_RATE=1.0 \
    DD_PROFILING_ENABLED=false

ENTRYPOINT ["sh", "-c", "if [ \"$DD_AGENT_ENABLED\" = \"true\" ]; then exec java $JAVA_OPTS -javaagent:/opt/datadog/dd-java-agent.jar -jar application.jar; else exec java $JAVA_OPTS -jar application.jar; fi"]

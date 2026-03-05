# ============================================
# Portfolio Blog API - Multi-stage Dockerfile
# Java 25 + Spring Boot 4 + WebFlux
# Optimized: ~500MB (down from 1.77GB)
# ============================================

# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

ARG NEXUS_HOST=""
RUN MAVEN_SETTINGS=""; \
    if [ -n "$NEXUS_HOST" ]; then \
      sed -i "s|__NEXUS_HOST__|${NEXUS_HOST}|g" .mvn/nexus-settings.xml; \
      MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"; \
    fi && \
    ./mvnw dependency:go-offline -B $MAVEN_SETTINGS

COPY src ./src

RUN MAVEN_SETTINGS=""; \
    if [ -n "$NEXUS_HOST" ]; then \
      MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"; \
    fi && \
    ./mvnw clean package -Dmaven.test.skip=true -B $MAVEN_SETTINGS

# Download Datadog agent in build stage (keeps curl/wget out of runtime)
RUN wget -q -O /tmp/dd-java-agent.jar https://dtdg.co/latest-java-tracer

# Stage 2: Runtime (Alpine, minimal)
FROM eclipse-temurin:25-jre-alpine AS runtime

LABEL maintainer="Leonardo Catananti <leonardo.catananti@gmail.com>" \
      version="2.0.0" \
      description="Portfolio Blog API"

# Chromium + Node.js for Playwright PDF generation
# ttf-freefont covers Latin/European; font-noto-cjk removed (saves ~300MB)
RUN apk add --no-cache \
      chromium \
      nss \
      freetype \
      harfbuzz \
      ca-certificates \
      ttf-freefont \
      nodejs

# Datadog APM agent (copied from builder — no curl needed at runtime)
COPY --from=builder /tmp/dd-java-agent.jar /opt/datadog/dd-java-agent.jar

# Playwright: use system Chromium + system Node.js
ENV PLAYWRIGHT_BROWSERS_PATH=/usr \
    PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PLAYWRIGHT_NODEJS_PATH=/usr/bin/node

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Writable dirs for read-only rootfs
RUN mkdir -p /app/uploads /app/logs && \
    chown -R appuser:appgroup /app /tmp

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

ENTRYPOINT ["sh", "-c", "if [ \"$DD_AGENT_ENABLED\" = \"true\" ]; then exec java $JAVA_OPTS -javaagent:/opt/datadog/dd-java-agent.jar -jar app.jar; else exec java $JAVA_OPTS -jar app.jar; fi"]

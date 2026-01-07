# ============================================
# Portfolio Blog API - Multi-stage Dockerfile
# Java 25 + Spring Boot 4.1.0-M1 + WebFlux
# ============================================

# Stage 1: Build stage
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom.xml first for caching dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make mvnw executable
RUN chmod +x mvnw

# Maven settings: use Nexus proxy locally, empty for cloud builds
# Override with --build-arg MAVEN_SETTINGS="" to use Maven Central directly
ARG MAVEN_SETTINGS="-s .mvn/nexus-settings.xml"

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B $MAVEN_SETTINGS

# Copy source code
COPY src ./src

# Build the application (skip tests for faster builds)
RUN ./mvnw clean package -Dmaven.test.skip=true -B $MAVEN_SETTINGS

# Deploy SNAPSHOT to Nexus (best-effort, don't fail build)
ARG NEXUS_USERNAME=""
ARG NEXUS_PASSWORD=""
RUN if [ -n "$NEXUS_USERNAME" ]; then \
      NEXUS_USERNAME=$NEXUS_USERNAME NEXUS_PASSWORD=$NEXUS_PASSWORD \
      ./mvnw deploy -Dmaven.test.skip=true -B $MAVEN_SETTINGS || true; \
    fi

# Stage 2: Runtime stage
FROM eclipse-temurin:25-jre-alpine AS runtime

# Add labels for metadata
LABEL maintainer="Leonardo Catananti <leonardo.catananti@gmail.com>"
LABEL version="2.0.0"
LABEL description="Portfolio Blog API - Spring Boot 4.1.0-M1 with WebFlux"

# INFRA-05: Install Chromium + Node.js for Playwright PDF generation
# Node.js is required because Playwright Java bundles a glibc-linked node binary
# that is incompatible with Alpine's musl libc. PLAYWRIGHT_NODEJS_PATH tells
# Playwright to use the system Node.js instead.
RUN apk add --no-cache \
    chromium \
    nss \
    freetype \
    harfbuzz \
    ca-certificates \
    ttf-freefont \
    font-noto-cjk \
    nodejs \
    curl

# Download Datadog Java APM agent
RUN mkdir -p /opt/datadog && \
    curl -Lo /opt/datadog/dd-java-agent.jar https://dtdg.co/latest-java-tracer

# Set Playwright environment to use system Chromium and system Node.js
ENV PLAYWRIGHT_BROWSERS_PATH=/usr
ENV PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_NODEJS_PATH=/usr/bin/node

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# F-411: Read-only filesystem — app only needs /app/uploads and /tmp
RUN mkdir -p /tmp && chown appuser:appgroup /tmp

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM settings for containers (optimized for memory)
# DD agent is conditionally loaded via DD_AGENT_ENABLED env var
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC"
ENV DD_AGENT_ENABLED=false
ENV DD_SERVICE=portfolio-blog-api
ENV DD_ENV=production
ENV DD_VERSION=2.0.0
ENV DD_LOGS_INJECTION=true
ENV DD_TRACE_SAMPLE_RATE=1.0
ENV DD_PROFILING_ENABLED=false

# Run the application (conditionally attach Datadog agent)
ENTRYPOINT ["sh", "-c", "if [ \"$DD_AGENT_ENABLED\" = \"true\" ]; then exec java $JAVA_OPTS -javaagent:/opt/datadog/dd-java-agent.jar -jar app.jar; else exec java $JAVA_OPTS -jar app.jar; fi"]

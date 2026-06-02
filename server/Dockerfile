# =============================================================================
# Idea-Party Server Dockerfile
# Multi-stage build: Maven (compile) → JRE (runtime)
# Target image: ~250MB, runs as non-root
# =============================================================================

# ---- Stage 1: Build ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache dependencies first to leverage Docker layer cache
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build the application (skip tests; CI runs them separately)
COPY src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/ideaparty-server-*.jar /build/app.jar

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Install curl for healthcheck, create non-root user, set timezone
RUN apk add --no-cache curl tzdata \
    && addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/uploads /app/logs \
    && chown -R app:app /app
ENV TZ=Asia/Shanghai

USER app
COPY --from=builder /build/app.jar /app/app.jar

EXPOSE 8080

# JVM container-aware defaults; tune via JAVA_OPTS in compose
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

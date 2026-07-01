# syntax=docker/dockerfile:1.4
# =============================================================================
# Idea-Party Server Dockerfile
# Multi-stage build: Maven (compile) → JRE (runtime)
# Target image: ~250MB, runs as non-root
# =============================================================================

# ---- Stage 1: Build ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# -s 指向烤进镜像的阿里云镜像 settings，强制 central 走国内 CDN，
#   避免从 repo.maven.apache.org 直连被海外 CDN 限速，长期卡在 go-offline。
# --mount=type=cache 把 ~/.m2/repository 持久化到 BuildKit cache，
#   第二次 build 命中缓存后秒过，不再下载任何依赖。
COPY .mvn/settings.xml .mvn/settings.xml
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -s .mvn/settings.xml dependency:go-offline

# Build the application.
# -Dmaven.test.skip=true 既跳过测试运行也跳过测试编译（项目 src/test 跟主代码签名对不上）
# CI 应当单独跑 mvn test（不在 Docker build 里做）。
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -s .mvn/settings.xml -Dmaven.test.skip=true package \
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

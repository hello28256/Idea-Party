# =============================================================================
# Idea-Party Client Dockerfile
# Multi-stage build: Node (build) → Nginx (serve)
# Resulting image: ~50MB, serves SPA + reverse-proxies API/WS to backend
# =============================================================================

# ---- Stage 1: Build ----
FROM node:20-alpine AS builder
WORKDIR /build

# Install deps with cache-friendly layer
# --legacy-peer-deps: 项目把 vite 锁在 v8，但部分 dev-deps 的 peer 仍声明 v5/v6，
# 不绕过的话 npm ci 会硬性退出。运行时无影响。
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund --legacy-peer-deps

# Build args → Vite env (VITE_* is exposed to client code at build time)
ARG VITE_SERVER_PROXY_PORT=8080
ARG VITE_WS_URL=
ENV VITE_SERVER_PROXY_PORT=$VITE_SERVER_PROXY_PORT \
    VITE_WS_URL=$VITE_WS_URL

COPY . .
RUN npm run build

# ---- Stage 2: Runtime (Nginx) ----
FROM nginx:1.27-alpine AS runtime

# Remove default site, copy our config
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/app.conf

# Copy built static assets from builder
COPY --from=builder /build/dist /usr/share/nginx/html

# Non-root nginx user already exists in base image (nginx:8080 in 1.27-alpine)
# Healthcheck hits the static index
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

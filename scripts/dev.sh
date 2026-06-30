#!/usr/bin/env bash
# dev.sh — 本地开发一键启动（后台模式）
#
# 与生产部署（deploy.py）的区别：
#   - 不用 docker 跑前后端（容器镜像里 dist/ 是构建时烤进去的，改源码看不到）
#   - MySQL 仍用 docker 容器（idea-mysql），需要先 expose 127.0.0.1:3306
#   - 前端 vite 监听 80 端口（sudo 因特权端口需要）
#   - 后端 mvn spring-boot:run 直接跑宿主机 JVM，连 localhost:3306
#
# 前置：
#   1. docker-compose.yml 里 mysql service 已放开 ports 段（开发期临时启用）
#   2. 仓库根有 .env（DB_URL / DB_USERNAME / DB_PASSWORD / JWT_SECRET）
#   3. 客户端 .env 已设 VITE_PORT=80
#   4. 宿主机 mvn、node 已安装，且 /etc/sudoers.d/vite 已为 npm/node/vite/pkill/ps 免密
#
# 用法：
#   ./scripts/dev.sh           # 启前后端到后台，日志写到 /tmp/idea-dev-{backend,frontend}.log
#   ./scripts/dev.sh --stop    # 停掉正在后台跑的 dev 进程
#   ./scripts/dev.sh --status  # 看端口 / 进程状态
#   ./scripts/dev.sh --logs    # tail 两个日志
#
# 后台机制：用 nohup + & 把两个进程都脱离当前 shell，
# 再 disown 防止 SIGHUP 杀掉。脚本立刻退出，终端不阻塞。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

BACKEND_LOG="/tmp/idea-dev-backend.log"
FRONTEND_LOG="/tmp/idea-dev-frontend.log"
BACKEND_PID_FILE="/tmp/idea-dev-backend.pid"
FRONTEND_PID_FILE="/tmp/idea-dev-frontend.pid"

stop_dev() {
  echo "=== 停止 dev 进程 ==="
  pkill -f 'spring-boot:run' 2>/dev/null || true
  BACKEND_PID="$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t 2>/dev/null || true)"
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null || true
  sudo -n pkill -f 'node.*vite' 2>/dev/null || true
  sudo -n pkill -f 'node.*esbuild' 2>/dev/null || true
  FRONTEND_PID="$(sudo -n lsof -nP -iTCP:80 -sTCP:LISTEN -t 2>/dev/null || true)"
  [ -n "$FRONTEND_PID" ] && sudo -n kill "$FRONTEND_PID" 2>/dev/null || true
  rm -f "$BACKEND_PID_FILE" "$FRONTEND_PID_FILE"
  echo "OK"
}

status_dev() {
  echo "=== 端口状态 ==="
  echo -n "8080 (backend): "; lsof -nP -iTCP:8080 -sTCP:LISTEN 2>/dev/null | tail -1 || echo "(free)"
  echo -n "80   (frontend): "; sudo -n lsof -nP -iTCP:80 -sTCP:LISTEN 2>/dev/null | tail -1 || echo "(free)"
  echo "=== 日志 ==="
  echo "backend  → tail -f $BACKEND_LOG"
  echo "frontend → tail -f $FRONTEND_LOG"
}

case "${1:-}" in
  --stop)   stop_dev; exit 0 ;;
  --status) status_dev; exit 0 ;;
  --logs)   tail -f "$BACKEND_LOG" "$FRONTEND_LOG"; exit 0 ;;
esac

echo "=== [1/4] 确认 MySQL 容器在跑 ==="
if ! docker ps --format '{{.Names}}' | grep -q '^idea-mysql$'; then
  echo "ERROR: idea-mysql 容器未运行，请先 docker compose up -d mysql"
  exit 1
fi
if ! docker port idea-mysql 2>/dev/null | grep -q '3306'; then
  echo "ERROR: idea-mysql 端口未暴露给宿主机，请检查 docker-compose.yml 的 ports 段"
  exit 1
fi
echo "OK"

echo "=== [2/4] 清掉旧进程（如果残留） ==="
stop_dev
sleep 1

echo "=== [3/4] 启动后端 (mvn spring-boot:run, 后台) ==="
# nohup + & + disown 三件套：进程脱离 shell，shell 退出不会被 SIGHUP 带走
nohup bash -c "cd '$REPO_ROOT/server' && exec mvn -q spring-boot:run -DskipTests" \
  > "$BACKEND_LOG" 2>&1 &
echo $! > "$BACKEND_PID_FILE"
disown
echo "backend PID=$(cat "$BACKEND_PID_FILE")  日志=$BACKEND_LOG"

echo "=== [4/4] 启动前端 (sudo npm run dev, 绑 80 端口, 后台) ==="
# sudo 起的进程会让脚本 hang 在 sudo（即使免密，stdin/tty 行为有时会卡），
# 所以把 sudo 也 nohup 到后台，让 sudo 命令本身脱离前台
nohup bash -c "cd '$REPO_ROOT/client' && exec sudo -n npm run dev" \
  > "$FRONTEND_LOG" 2>&1 &
echo $! > "$FRONTEND_PID_FILE"
disown
echo "frontend PID=$(cat "$FRONTEND_PID_FILE")  日志=$FRONTEND_LOG"

echo
echo "=== 完成 ==="
echo "后端等 ~5s 启动完成；前端立即可访问 http://localhost/"
echo "查看日志：  tail -f $BACKEND_LOG  或  $FRONTEND_LOG"
echo "看状态：    ./scripts/dev.sh --status"
echo "停止：      ./scripts/dev.sh --stop"
echo "（脚本立即退出，前后端在后台运行）"
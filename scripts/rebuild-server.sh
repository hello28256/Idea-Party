#!/usr/bin/env bash
# 重建后端 Docker 镜像并热替换容器。
#
# 为什么默认 --no-cache：
#   Dockerfile 第一阶段用 `mvn -B -q -Dmaven.test.skip=true package` 编译,
#   Maven 对 `src/main/resources/presets.json` 改动的"是否需要重新打包"判断依赖文件 mtime + 内部 hash，
#   Docker BuildKit 缓存层用文件 mtime 决定是否失效 -- 在 bind mount / 容器内 mtime 漂移场景下,
#   改完 presets.json 再 build 经常命中旧 layer, jar md5 不变。
#   场景里 presets.json 不大(<200KB), 强制重建一次 < 90s, 不心疼。

set -euo pipefail

cd "$(dirname "$0")/.."

# Docker compose 需要 .env.production 才能拿到 MYSQL/JWT/DEEPSEEK 凭据,
# 不 source 的话 build 阶段 (FROM maven) 默认环境变量会缺失, 容器启动失败。
set -a
source .env.production
set +a

echo "[rebuild-server] docker compose build server --no-cache"
docker compose build server --no-cache

echo "[rebuild-server] docker compose up -d server"
docker compose up -d server

echo "[rebuild-server] 等待健康检查 /api/health"
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/api/health 2>/dev/null || true)
  code=${code:-000}
  if [ "$code" = "200" ]; then
    echo "✅ 服务就绪 (尝试 $i) — http://localhost:8082/api/health = 200"
    exit 0
  fi
  echo "  等待中 ($i) code=$code"
  sleep 3
done

echo "❌ 服务在 60 秒内未就绪, 请查看日志: docker compose logs --tail=200 server"
exit 1

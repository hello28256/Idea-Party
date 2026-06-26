# Docker 部署

> 本文件只讲 Docker / Compose 部署路径;项目介绍、技术栈、API、环境变量等见主 [README.md](./README.md)。

## 快速开始

```bash
# 1. 准备环境变量
cp .env.production.example .env.production
# 编辑 .env.production,至少填好:
#   MYSQL_ROOT_PASSWORD / JWT_SECRET / DEEPSEEK_API_KEY / PUBLIC_BASE_URL

# 2. 构建并启动
docker compose build
docker compose up -d

# 3. 验证
docker compose ps                  # 应该都 healthy
curl http://localhost/api/health   # 返回 {"status":"UP",...}
# 浏览器打开 http://localhost 即可
```

停止:`docker compose down`(保留数据)
销毁(⚠️ 数据清空):`docker compose down -v`

## 服务编排速览

| 服务 | 镜像/构建 | 对外端口 | 健康检查 | 持久化卷 |
|------|----------|---------|---------|---------|
| `mysql` | `mysql:8.0` | 仅内网 | `mysqladmin ping` | `mysql-data` |
| `server` | `server/Dockerfile` → `ideaparty/server:latest` | `8082 → 8080` | `curl /api/health` | `server-uploads`、`server-logs` |
| `client` | `client/Dockerfile` → `ideaparty/client:latest` | `${CLIENT_PORT:-80} → 80` | `wget /healthz` | `nginx-cache`(代理缓存跨重建) |

启动顺序:`mysql healthy` → `server healthy` → `client healthy`。

## 远程部署(腾讯云 CVM)

```bash
# 配置 .env.deploy(DEPLOY_HOST / DEPLOY_USER / DEPLOY_SSH_KEY / DEPLOY_REMOTE_DIR)
cp .env.deploy.example .env.deploy

# 一键:rsync 差量同步 + 远程 docker compose build && up -d
python3 deploy.py

# 常用子命令
python3 deploy.py --status          # 容器状态
python3 deploy.py --logs server     # tail 日志
python3 deploy.py --restart server  # 单服务重启
python3 deploy.py --sync-only       # 只同步不构建
python3 deploy.py --dry-run         # 只打印命令不执行
```

`deploy.py` 会自动排除 `.git / node_modules / dist / target / .env* / playwright-report / coverage` 等敏感或冗余目录,详见脚本头部注释。

## 完整文档

- 反代 / HTTPS / 安全组 / 密钥管理:`docs/deploy-tencent-cloud.md`
- 主 README:[README.md](./README.md)
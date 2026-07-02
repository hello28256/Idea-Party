# Idea-Party CI/CD

`.github/` 目录下包含项目所有的自动化工作流。

## Workflows

| File | 触发 | 作用 |
|---|---|---|
| `workflows/ci.yml` | push / PR / manual | 跑前端 `typecheck + vitest + build`,后端 `mvn test + package`,Dockerfile `buildx validate` |
| `workflows/cd-deploy.yml` | push `main` / manual | SSH 到 VPS 跑 `python3 deploy.py --skip-uploads`,自动部署生产环境 |
| `workflows/release.yml` | 推送 `v*.*.*` tag / manual | 构建并推送 `server` / `client` 镜像到 GHCR,自动生成 GitHub Release notes |
| `workflows/codeql.yml` | push `main` / PR / 周一 | GitHub 内置 CodeQL 安全扫描(JS + Java) |
| `dependabot.yml` | 每周一 | 自动 PR 升级 `client/`(npm)和 `server/`(maven)依赖 |

## Secrets 配置

进 **Settings → Secrets and variables → Actions → New repository secret** 添加:

| Secret 名 | 用途 | 获取方式 |
|---|---|---|
| `DEPLOY_HOST` | VPS IP / 域名 | 部署目标机器 |
| `DEPLOY_USER` | SSH 登录用户名(如 `ubuntu`) | VPS 系统用户 |
| `DEPLOY_SSH_KEY` | 私钥全文 | `cat ~/.ssh/id_ed25519` |
| `DEPLOY_PORT` | SSH 端口(可选,默认 22) | 自定义才需要 |
| `PUBLIC_BASE_URL` | 部署成功后的 URL(可选) | 让 Deployment 卡片显示链接 |

> **不需要** 为 GHCR 配置账号 — Actions 内置的 `GITHUB_TOKEN` 已自动有写仓库 packages 权限(只要 repo 不被组织后台禁用)。

## 关键约定

- **`client/Dockerfile:16` 用 `npm install --legacy-peer-deps`**:CI `ci.yml` 同步用 `npm ci --legacy-peer-deps`,避免 `lucide-vue-next` 触发 ERESOLVE。
- **`server/Dockerfile:23` 跳测试**:Docker 构建期 `-Dmaven.test.skip=true` 是性能优化。**CI 必须跑测试**,见 `ci.yml` 的 server job。
- **`deploy.py:148-149` 排除 `.env.production`**:CD **不** 注入 `.env.production`,服务器上的 `.env.production` 仍是部署前手工放的(`deploy.py --sync-env` 是一次性入口)。CI 改代码触发 CD 时不会重写 env,audit 友好。
- **`deploy.py:123` uploads/avatars 子目录**:CI/CD 默认 `--skip-uploads`,因为是大文件且通常不变;uploads 同步走人工 `./scripts/oss-sync-avatars.sh` 或 `./deploy.py`(不带 `--skip-uploads`)。
- **`docker-compose.yml` 现在用 `build:`**:CD 走 `deploy.py` 的本地 build(已经能用),没有改 `compose.yml` 去拉 GHCR 镜像;后续单独 PR 做镜像替换。

# 腾讯云 Docker Compose 部署指南

> 目标：把 Idea-Party（Vue 3 + Spring Boot + MySQL）通过 Docker Compose 部署到腾讯云 CVM / Lighthouse。

---

## 1. 总体架构

```
                  Internet
                     │
                     ▼
        ┌─────────────────────────┐
        │  CVM 安全组 (SG)        │  放通: 22, 80, 443
        └────────────┬────────────┘
                     │
                     ▼
        ┌─────────────────────────┐
        │  client (Nginx:80)      │  ← 反代 /api /uploads /ws 到 server
        └────────────┬────────────┘
                     │  idea-net (bridge)
        ┌────────────┴────────────┐
        │                         │
        ▼                         ▼
┌──────────────┐         ┌──────────────┐
│ server:8080  │ ───────▶│ mysql:3306   │
│ (Spring Boot)│         │ (MySQL 8)    │
└──────────────┘         └──────────────┘
   ▲   ▲
   │   └── volume: server-uploads, server-logs
   └────── volume: mysql-data
```

所有服务跑在同一个 `idea-net` bridge 网络中，互相通过服务名解析（`mysql`、`server`、`client`）。**MySQL 不对外暴露端口**——只有 `server` 容器能连。

---

## 2. 服务器初始化（一次性）

### 2.1 选机器

| 推荐配置 | 适用规模 |
|---------|---------|
| S5.SMALL2 (2核2G) | 体验 / 1~10 人 |
| S5.SMALL4 (2核4G) | 20~50 人 |
| S5.MEDIUM4 (4核4G) | 50~200 人 |
| S5.MEDIUM8 (4核8G) | 200+ 人，并发高 |

> **操作系统**：TencentOS Server 3.1 / Ubuntu 22.04 LTS / CentOS 7.6+ 都行，下面以 Ubuntu 22.04 为例。

### 2.2 安全组（关键！）

在腾讯云控制台 → CVM → 安全组 → 入站规则：

| 端口 | 协议 | 来源 | 用途 |
|-----|-----|-----|-----|
| 22  | TCP | 你的 IP / 0.0.0.0/0 | SSH |
| 80  | TCP | 0.0.0.0/0 | HTTP（前端） |
| 443 | TCP | 0.0.0.0/0 | HTTPS（推荐） |
| 3306 | TCP | **仅安全组内 / 关闭** | MySQL（**不要开**） |
| 8080 | TCP | **关闭** | Spring Boot（**不要开**） |

### 2.3 安装 Docker

```bash
# 一键安装（Ubuntu / Debian）
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh --mirror Aliyun  # 国内机器建议加 --mirror

sudo usermod -aG docker $USER
newgrp docker  # 让当前 shell 立即生效

# 验证
docker --version
docker compose version
```

> ⚠️ 腾讯云内 Docker Hub 拉取可能慢，建议配置镜像加速器：编辑 `/etc/docker/daemon.json`，加入 `registry-mirrors` 指向腾讯云 TCR 提供的加速地址。

### 2.4 准备项目目录

```bash
# 假设部署到 /opt/ideaparty
sudo mkdir -p /opt/ideaparty
sudo chown $USER:$USER /opt/ideaparty
cd /opt/ideaparty

# 方式 A：直接 git clone
git clone <your-git-repo> .

# 方式 B：本地构建后 scp 上去（见第 4 节）
```

---

## 3. 配置文件

### 3.1 创建生产环境变量

```bash
cd /opt/ideaparty
cp .env.production.example .env.production
nano .env.production   # 或 vim
```

**必须修改的字段**：

| 字段 | 怎么填 | 示例 |
|-----|-------|------|
| `MYSQL_ROOT_PASSWORD` | 一个 ≥16 位的强密码 | `Tc9!ideaparty_2026#mysql` |
| `JWT_SECRET` | `openssl rand -base64 48` | 生成的 base64 字符串 |
| `DEEPSEEK_API_KEY` | DeepSeek 控制台拿 | `sk-xxxxxxxx` |
| `PUBLIC_BASE_URL` | 实际访问域名/IP | `https://ideaparty.example.com` |
| `APP_CORS_ALLOWED_ORIGINS` | 同上（多域名用逗号） | `https://a.com,https://b.com` |
| `CLIENT_PORT` | 对外端口 | `80`（HTTPS 时改成 443 见第 5 节） |

> 💡 **生成强密码 / JWT Secret 的小技巧**：
> ```bash
> openssl rand -base64 48        # JWT
> openssl rand -base64 24        # MySQL
> ```

### 3.2 验证文件树

```
/opt/ideaparty/
├── docker-compose.yml
├── .env.production              # 已经在 .gitignore
├── docker/
│   └── mysql/
│       ├── conf.d/ideaparty.cnf
│       └── init/00-charset.sql
├── server/
│   ├── Dockerfile
│   ├── .dockerignore
│   ├── pom.xml
│   └── src/...
└── client/
    ├── Dockerfile
    ├── .dockerignore
    ├── nginx.conf
    ├── package.json
    └── src/...
```

---

## 4. 构建 + 启动

### 4.1 本地构建（推荐，编译快）

在**本地机器**上构建产物，再把镜像推到腾讯云：

```bash
# 1. 在 CVM 上：拉腾讯云 TCR 加速（可选）
#    也可在本地构建后 docker save → scp → CVM docker load

# 2. 启动（首次会拉基础镜像 + 构建，约 5~10 分钟）
cd /opt/ideaparty
docker compose build
docker compose up -d
```

### 4.2 用腾讯云容器镜像服务 TCR（更稳）

适合 `docker pull` 慢 / 团队协作 / 多机部署：

```bash
# 在 TCR 控制台：创建命名空间 idea-party，仓库 ideaparty-server 和 ideaparty-client
# 然后登录
sudo docker login ccr.ccs.tencentyun.com -u <用户名>

# 打 tag
docker tag ideaparty/server:latest ccr.ccs.tencentyun.com/<namespace>/ideaparty-server:v1.0.0
docker tag ideaparty/client:latest ccr.ccs.tencentyun.com/<namespace>/ideaparty-client:v1.0.0

# 推送
docker push ccr.ccs.tencentyun.com/<namespace>/ideaparty-server:v1.0.0
docker push ccr.ccs.tencentyun.com/<namespace>/ideaparty-client:v1.0.0

# CVM 上改 docker-compose.yml 的 image 字段
```

### 4.3 验证启动状态

```bash
docker compose ps         # 看到 mysql/server/client 都是 healthy 就 OK
docker compose logs -f server   # 看启动日志，确认没有 ERROR
curl http://localhost/api/health  # 应返回 {"status":"UP","service":"IdeaParty Server"}
```

首次启动 server 会在日志里看到 JPA 自动建表的 SQL（`spring.jpa.hibernate.ddl-auto=update`）。

---

## 5. HTTPS / 域名（强烈推荐）

把 80 端口让给 client 容器后，**最简单的方式**是用腾讯云 CLB（负载均衡）做 HTTPS 卸载：

```
客户端 ──HTTPS──▶ CLB:443 (证书) ──HTTP──▶ CVM:80 (client 容器)
```

或者在 CVM 上自建 Nginx 80/443 双层（推荐用于要直接管理 TLS 的场景）：

```bash
sudo apt install -y nginx certbot python3-certbot-nginx

# /etc/nginx/sites-available/ideaparty
server {
    listen 80;
    server_name ideaparty.example.com;
    client_max_body_size 25m;

    location / {
        proxy_pass http://127.0.0.1:80;   # 转到 client 容器
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

sudo ln -s /etc/nginx/sites-available/ideaparty /etc/nginx/sites-enabled/
sudo certbot --nginx -d ideaparty.example.com   # 自动签证书
sudo systemctl reload nginx
```

⚠️ 这种双层 Nginx 下，记得把 `docker-compose.yml` 里 `CLIENT_PORT` 改成 `127.0.0.1:80:80`（只绑本机，避免端口冲突）。

---

## 6. 日常运维

### 6.1 常用命令

```bash
# 查看状态
docker compose ps

# 实时日志
docker compose logs -f --tail=200 server
docker compose logs -f --tail=200 client

# 进入容器
docker compose exec server sh
docker compose exec mysql mysql -uroot -p

# 重启单个服务
docker compose restart server

# 重新构建（代码更新后）
docker compose build server
docker compose up -d server

# 停止（保留数据）
docker compose down

# 销毁一切（⚠️ 数据会丢）
docker compose down -v
```

### 6.1.1 同步静态资源（avatars）

`python3 deploy.py` 默认会执行 **Step 1.5**：把本地
`server/uploads/avatars/{presets,hot-rooms}` 同步到服务器
`idea-server-uploads` named volume，让 Spring Boot 能立即读
到最新的预设头像和热门聊天室封面。

实现原理是用 `docker run --rm alpine:3.19` 临时容器以 root
身份挂载 volume 写文件，绕开宿主 `/var/lib/docker/.../idea-server-uploads/_data/`
所有者 `dhcpcd:lxd`（uid=100）对 ubuntu 用户的写权限限制。

跳过方式（仅调试 / 已知 volume 健康时）：

```bash
python3 deploy.py --skip-uploads
```

调优环境变量（写到 `.env.deploy`）：

```bash
DEPLOY_UPLOADS_VOLUME=idea-server-uploads   # 与 docker-compose.yml 一致
DEPLOY_UPLOADS_IMAGE=alpine:3.19            # 必须含 cp -a
DEPLOY_UPLOADS_MIN_PRESETS=100              # 验证阈值，低于此值 deploy 失败
```

验证命令：

```bash
# 容器内 preset 数量
docker exec idea-server sh -c 'find /app/uploads/avatars/presets -maxdepth 1 -type f | wc -l'
# 期望 ≥ 600
```

### 6.2 备份与恢复

**MySQL**（最重要）：

```bash
# 备份
docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers ideaparty' \
  > /opt/backups/ideaparty-$(date +%Y%m%d-%H%M).sql

# 恢复
cat /opt/backups/ideaparty-xxx.sql | docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ideaparty
```

**上传文件 + 日志**（命名卷）：

```bash
docker run --rm -v idea-server-uploads:/data -v /opt/backups:/backup \
  alpine tar czf /backup/uploads-$(date +%Y%m%d).tgz /data
```

> 💡 生产环境建议：把 `/opt/backups` 挂到腾讯云 COS 或 CFS，每天 cron 一次。

### 6.3 升级流程

```bash
cd /opt/ideaparty
git pull
docker compose build
docker compose up -d
docker image prune -f   # 清理旧镜像
```

### 6.4 监控

最简方案：腾讯云「云监控」基础指标（CVM CPU/内存/磁盘）免费够用。
进阶：把 client 容器的 `/healthz` 接入 Uptime 监控（curl 返回 200）。

---

## 7. 故障排查

| 现象 | 排查 |
|-----|------|
| `server` 一直 restart | `docker compose logs server` — 90% 是 `JWT_SECRET` 没设 / DB 连不上 / DeepSeek key 错 |
| 前端能打开但登录 404 | CORS：检查 `.env.production` 的 `APP_CORS_ALLOWED_ORIGINS` 是否包含实际域名 |
| 实时聊天断了（无流式响应） | Nginx 没透传 WebSocket 头：检查 `client/nginx.conf` 的 `/ws` 段有没有 `Upgrade`/`Connection` 头 |
| 502 Bad Gateway | server 没起来：`docker compose ps` + `docker compose logs server` |
| MySQL 启动慢 / 连不上 | CVM 内存 < 1G；`innodb_buffer_pool_size=256M` 是底线，必要时降到 128M |
| 磁盘占满 | `docker system df` 看是哪个卷；MySQL ibd 文件膨胀是常见原因 |
| `image pull access denied` | 没登录 TCR / 镜像 tag 不对，参考 4.2 |

---

## 8. 安全 Checklist

- [ ] `.env.production` 权限设为 `chmod 600`，**不要**进 git
- [ ] MySQL 端口（3306）**不对外开放**
- [ ] Spring Boot 8080 端口**不对外开放**
- [ ] 安全组只放通 22/80/443
- [ ] SSH 改用密钥登录，禁用密码
- [ ] 启用 HTTPS（Let's Encrypt / 腾讯云免费证书）
- [ ] 定期 `docker compose pull` 更新基础镜像（JRE / Nginx 漏洞）
- [ ] 设置 CVM 快照策略（每天自动备份整盘）

---

## 9. 文件清单

```
.
├── docker-compose.yml                 # 编排入口
├── .env.production.example            # 模板（已 gitignore .env.production）
├── docker/
│   └── mysql/
│       ├── conf.d/ideaparty.cnf       # MySQL 调优
│       └── init/00-charset.sql        # 首次启动执行
├── server/
│   ├── Dockerfile                     # 多阶段 Maven → JRE
│   └── .dockerignore
├── client/
│   ├── Dockerfile                     # 多阶段 Node → Nginx
│   ├── .dockerignore
│   └── nginx.conf                     # SPA + /api + /ws 反代
└── doc/deploy-tencent-cloud.md        # 本文档
```

代码改动：
- `server/src/main/java/com/ideaparty/config/CorsConfig.java` — CORS origins 走环境变量
- `client/src/composables/useSocket.ts` — WebSocket 走 `window.location.host`，不再硬编码 `localhost`

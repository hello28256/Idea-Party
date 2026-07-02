# Idea-Party · 腾讯云 CODING 持续集成

> 本目录说明如何把项目从 GitHub Actions 迁移到腾讯云 CODING 持续集成。

## 为什么迁移

| 维度 | GitHub Actions (旧) | CODING (新) |
|---|---|---|
| Runner 位置 | 海外（美国/欧洲）| 大陆（深圳/上海）|
| 推腾讯云 VPS 速度 | 10-30 KB/s（跨境）| 100+ MB/s（内网）|
| 一次 deploy 耗时 | 14m+ | 1-3m |
| 运维负担 | 0 | 低（CODING 维护 runner）|

## 一次性配置

### 1. 注册腾讯云账号 + 实名认证

- https://cloud.tencent.com 注册
- 个人认证（要身份证，约 5 分钟）

### 2. 创建 CODING 团队

- https://coding.tencent.com 用腾讯云账号登录
- 创建团队（个人团队，免费）

### 3. 创建项目

- 团队内 → 项目 → 创建项目
- 类型选 **"持续集成"**
- 项目名：`Idea-Party`

### 4. 绑定 GitHub 仓库

- 项目设置 → 仓库设置 → 关联仓库
- 选 **"从 GitHub 导入"** → 授权 GitHub → 选 `hello28256/Idea-Party`
- 自动配 webhook，push 到 main 时 CODING 自动同步并触发构建

### 5. 配置构建节点

- 项目设置 → 构建节点 → 构建节点方案
- 选 **"默认"**（CODING 提供的大陆 runner，免费额度足够个人项目）

### 6. 配置 Secret（凭据管理）

- 项目设置 → 凭据管理 → 添加凭据
- 添加 3 个：

| 凭据名 | 类型 | 值 |
|---|---|---|
| `DEPLOY_HOST` | 凭据类型：自定义 | `150.158.137.186` |
| `DEPLOY_USER` | 凭据类型：自定义 | `ubuntu` |
| `DEPLOY_SSH_KEY` | 凭据类型：SSH 私钥 | 你 Mac 上的 `~/.ssh/id_ed25519` 内容（多行整段）|

> ⚠️ **SSH 私钥内容**整段贴，包含 `-----BEGIN OPENSSH PRIVATE KEY-----` 和 `-----END ...-----`。
>
> 注意：`DEPLOY_SSH_KEY` 在 Jenkinsfile 里是**直接 echo 到文件**（不是 path），
> 所以凭据类型选 "自定义" / "文本"，**不是 "SSH 私钥"** 类型。
> 改用：凭据类型 → **"用户名密码"** 或 **"自定义"**，值是完整私钥内容。

### 7. 触发第一次构建

- 仓库根目录已有 `Jenkinsfile`（本仓库已包含）
- push 一次代码到 GitHub main → CODING 自动同步 → 自动触发
- 或在 CODING 控制台 → 持续集成 → 点 "立即构建"

## 预期第一次构建日志

```
Pre-flight         ✅ env file OK
Materialize key    ✅
Deploy             ✅ (tar 流 + docker compose, 1-3 分钟)
Smoke check        ✅ (3 个容器 healthy)
总耗时              1-3 分钟
```

## GitHub Actions workflow 怎么办

`.github/workflows/cd-deploy.yml` **保留**作 backup。两边会**同时被触发**——所以建议：

1. **第一次跑通 CODING**（验证 deploy 成功）
2. **第二次** 在 GitHub Actions 跑时, 把它**关掉**（`branches: [never-trigger]` 或 `if: false`）
3. GitHub Actions 留作 backup / 灾备

## 故障排查

### 凭据错误

构建报 `Permission denied (publickey)`：
- 检查 `DEPLOY_SSH_KEY` 内容是否完整（多行）
- 检查 VPS `~/.ssh/authorized_keys` 是否有对应公钥

### Pre-flight 失败

```
::error::VPS 上 /opt/ideaparty 不存在
```

参考 `task #6` 的手工恢复流程：建目录 + scp `.env.production` + `docker-compose.yml`。

### 部署慢

检查 `tar` 流是否启用（`--use-tar` flag）。CODING runner 在大陆，跟 VPS 同服务商，**应该 1-3 分钟**。

# Idea Party

> 一个 AI 多角色聊天室平台：在一个对话框里同时和多个 AI 角色对话，类似群聊或圆桌讨论。

[![Vue 3](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-ed8b00)](https://openjdk.org/projects/jdk/21)
[![License](https://img.shields.io/badge/license-MIT-blue)](#许可证)

---

## 简介

**Idea Party** 是一个让用户轻松创建**多元视角 AI 对话场景**的平台。核心思路：

- 在一个房间里放进多个 AI 角色（历史人物 / 领域专家 / 自定义角色）
- 由 **Moderator Agent** 智能编排发言顺序，避免一拥而上或冷场
- 角色 prompt 可由系统根据角色名自动联网检索公开信息生成（Firecrawl + LLM）
- **每个用户的 DeepSeek API Key 只在后端**，前端永远拿不到
- 提供 4 个开箱即用的「场景」模板（面试官 / 产品顾问 / 英语陪练 / 写作编辑）

适合用来做：技术面试模拟、产品头脑风暴、语言学习陪练、稿件审阅、圆桌讨论等。

---

## 功能特性

- 🎭 **角色系统** — 8+ 预设角色（苏格拉底、爱因斯坦、孔子、马云、乔布斯等）+ 自定义角色；上传头像、按角色名自动检索维基百科生成 persona prompt
- 💬 **两种房间模式** — `dialogue`（@提及 + 智能选人）/ `discussion`（多轮 Moderator 编排 + 暂停/恢复/停止）
- ⚡ **实时流式聊天** — 字符级推送，多角色用一次 LLM 调用并行输出（联合 prompt + 行内解析器）
- 👍👎 **反馈系统** — Like / Dislike + 5 类差评（答非所问 / 事实不准 / 不安全 / 风格差 / 其他）+ 备注；后台汇总观测量
- 📊 **管理后台** — `/admin/feedbacks` 查看所有 AI 消息的反馈、流式状态（成功/空/失败）、响应延迟分桶
- 🪟 **场景模板** — 一键启动 4 个常用场景，会先问用户补充输入再创建角色
- 🎨 **主题切换** — 浅色 / 深色 / 跟随系统，后端持久化
- 🛡️ **速率限制** — Bucket4j 按 IP 限流，避免误用

---

## 技术栈

### 前端 (`client/`)

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | UI 框架，组合式 API + `<script setup>` |
| TypeScript | 6.0 | 严格类型检查 |
| Vite | 8.0 | 构建 / HMR |
| Pinia | 3.0 | 状态管理 |
| Vue Router | 5.0 | SPA 路由 |
| Tailwind CSS | 4.3 | 原子化样式 |
| socket.io-client | 4.8 | 实时通信依赖（实际走原生 WebSocket + 自实现 Socket.IO framing） |
| Axios | 1.16 | HTTP 客户端 |
| Lucide | 1.0 | 图标 |
| Vitest + Playwright | — | 单元 / E2E 测试 |

### 后端 (`server/`)

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5 | 应用框架 |
| Java | 21 LTS | 运行时 |
| LangChain4j (OpenAI 兼容) | 1.0.0-beta2 | DeepSeek 编排（chat + streaming） |
| Spring Data JPA + Hibernate | 6.x | ORM，MySQL 自动建表 |
| MySQL | 8.x | 主数据库 |
| Spring WebSocket | — | `/ws` 端点（Socket.IO framing） |
| JJWT | 0.12.5 | JWT 鉴权（HS256） |
| Bucket4j | 8.14 | 速率限制 |
| Springdoc OpenAPI | 2.8.6 | `/swagger-ui.html` |
| Firecrawl | — | 角色联网检索（无 key 时走 mock fallback） |
| dotenv-java | 3.0 | 自动加载 `.env` |

> 完整依赖版本以 `client/package.json` / `server/pom.xml` 为准。

---

## 项目结构

```text
Idea-Party/
├── client/                          # Vue 3 前端
│   ├── src/
│   │   ├── api/                     # axios 接口模块（auth/rooms/messages/...）
│   │   ├── components/              # 业务组件（chat/character/room/feedback/admin/...）
│   │   ├── composables/             # useSocket, useMessageEvents 等
│   │   ├── layouts/                 # LegalLayout 等
│   │   ├── router/                  # 路由表
│   │   ├── stores/                  # Pinia（auth/room/message/character/settings/scenario）
│   │   ├── views/                   # 页面级（Login/Rooms/Chat/CharacterCreate/...）
│   │   └── App.vue / main.ts
│   ├── package.json
│   └── vite.config.ts               # /api /ws /uploads 代理到 :8082
│
├── server/                          # Spring Boot 后端
│   ├── src/main/java/com/ideaparty/
│   │   ├── controller/              # 13 个 REST 控制器
│   │   ├── service/                 # 19 个服务（含 ModeratorAgent / AIService / FirecrawlService）
│   │   ├── socket/                  # ChatSocketHandler — 当前活跃 WS handler
│   │   ├── websocket/               # ChatWebSocketHandler — 旧实现，仍在测试中使用
│   │   ├── entity/                  # 9 个 JPA 实体
│   │   ├── repository/              # 8 个 Spring Data 仓库
│   │   ├── dto/                     # 28 个 DTO
│   │   ├── config/                  # Security / Socket / DataLoader / OpenApi / ...
│   │   ├── filter/                  # Bucket4j 限流
│   │   └── exception/               # GlobalExceptionHandler + 自定义异常
│   ├── src/main/resources/
│   │   ├── application.properties   # 默认配置
│   │   ├── data.sql                 # 中文角色预设（8 个）
│   │   ├── prompts/                 # 角色 prompt 生成器模板
│   │   └── db/migration/            # 早期 SQL 迁移（V2–V6）
│   ├── pom.xml
│   └── Dockerfile
│
├── docker/                          # MySQL 配置（compose 首次启动时挂载）
├── docker-compose.yml               # mysql + server + client 三服务
├── deploy.py                        # 一键部署脚本（rsync + 远程 docker compose）
├── README-DOCKER.md                 # Docker 部署详情
├── doc/                             # 部署 / 测试文档
└── CLAUDE.md                        # AI 助手的项目上下文与约定
```

---

## 快速开始

### 环境要求

- **Node.js** ≥ 20.19
- **Java** 21 LTS
- **Maven** ≥ 3.9
- **MySQL** 8.x（本地或 Docker）
- 一个 **DeepSeek API Key**（[申请](https://platform.deepseek.com)）

### 1. 克隆仓库

```bash
git clone git@github.com:hello28256/Idea-Party.git
cd Idea-Party
```

### 2. 启动 MySQL（任选其一）

**A. 本地已安装 MySQL** — 创建数据库：

```sql
CREATE DATABASE ideaparty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**B. 用 Docker**：

```bash
docker run -d --name idea-mysql \
  -e MYSQL_ROOT_PASSWORD=rootpw \
  -e MYSQL_DATABASE=ideaparty \
  -p 3306:3306 \
  mysql:8
```

### 3. 启动后端

```bash
cd server
cp .env.example .env       # 填入 DB_PASSWORD / JWT_SECRET / DEEPSEEK_API_KEY
./mvnw spring-boot:run
# 默认监听 :8080，Swagger UI: http://localhost:8080/swagger-ui.html
```

**`server/.env` 关键变量：**

| 变量 | 必填 | 说明 |
|------|------|------|
| `DB_PASSWORD` | ✓ | MySQL root 密码 |
| `JWT_SECRET` | ✓ | ≥32 字符的随机串 |
| `DEEPSEEK_API_KEY` | ✓ | LLM key（个人用户可在「设置」页填自己的，覆盖此默认值） |
| `DEEPSEEK_BASE_URL` | — | 默认 `https://api.deepseek.com` |
| `FIRECRAWL_API_KEY` | — | 缺省时角色检索走 mock fallback |
| `ENCRYPTION_KEY` | — | Base64 32 字节；填了之后用户 API Key 在 DB 中加密存储 |

### 4. 启动前端

```bash
cd ../client
npm install
npm run dev
# 默认监听 :5173，dev proxy 把 /api /ws /uploads 转到 :8082
```

打开 <http://localhost:5173>，注册账号 → 在「设置」页填入你的 DeepSeek API Key → 开始对话。

> 如果后端不在 `:8082`，新建 `client/.env` 设置 `VITE_SERVER_PROXY_PORT=8080`。

### 5. 测试

```bash
# 后端
cd server && ./mvnw test

# 前端
cd client && npm run test         # vitest 单元
npx playwright test                # E2E（如有配置）
```

---

## 场景

项目内置 **4 个场景模板**，写在 `client/src/stores/scenario.ts`，开箱即用。每个场景都会先弹窗让用户补充输入（岗位描述 / 产品 idea / 题材 / 草稿），然后自动生成角色 + 创建房间。

| Emoji | 场景 | 房间模式 | 用户输入 |
|-------|------|---------|---------|
| 🎤 | **面试模拟** | single | 你想面试什么岗位 / 行业？ |
| 💡 | **产品头脑风暴** | group | 你想打磨什么样的产品 idea？ |
| 🇬🇧 | **英语陪练** | single | 想练什么场景？ |
| ✍️ | **写作助手** | single | 这次要审什么稿子？ |

场景数据目前写死在前端，后续可改为后端 API。

---

## 反馈后台

管理后台路由：**`/admin/feedbacks`**（需登录 + `User.isAdmin=true`）。

权限模型：
1. **首选**：`users.is_admin = TRUE`
2. **Fallback**：启动时 `DataLoader` 读取 `APP_ADMIN_USER_IDS` 环境变量（逗号分隔 UUID），自动把这些用户置为 admin

能力一览：
- **总览统计**：所有 AI 消息数 / 未评 / 已评 / 已聚合
- **逐条观测**：用户提问预览、AI 回复预览、流式状态（成功/空/失败）、响应延迟分桶（绿 <2s、黄 2–5s、橙 5–10s、红 >10s）、like/dislike 数、最后反馈时间
- **详情弹窗**：原始消息 + 触发该消息的用户消息 + 房间 / 角色上下文
- **隐式信号聚合**：`/api/admin/messages/{id}/signals` 返回该消息的所有 REWRITE / COPY / READ_COMPLETE / EDIT / FOCUS 事件

实现细节见 `client/src/views/admin/AdminFeedbackView.vue` 和 `server/src/main/java/com/ideaparty/service/AdminObservationService.java`。

---

## API 速览

完整 REST 端点参见启动后访问 **`/swagger-ui.html`**。

最常用的几个：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录（返回 JWT） |
| GET  | `/api/characters/presets` | 预设角色列表（无需登录） |
| POST | `/api/characters/generate-prompt` | LLM 根据角色名生成 system prompt |
| GET  | `/api/rooms` | 我的房间列表 |
| POST | `/api/rooms` | 创建房间 |
| POST | `/api/messages/{messageId}/feedback` | 提交 / 更新点赞 / 点踩 |
| GET  | `/api/admin/feedbacks` | 后台反馈列表（admin） |

WebSocket 端点 **`/ws`**：前端用 Socket.IO framing 自实现，事件包括 `join room` / `chat message` / `pause-discussion` / `resume-discussion` / `stop-discussion`；服务端推送 `chat message` / `chat chunk` / `character thinking` / `discussion-state` / `moderator-message`。详见 `client/src/composables/useSocket.ts` 与 `server/src/main/java/com/ideaparty/socket/ChatSocketHandler.java`。

---

## 部署

- **Docker / Compose**：[`README-DOCKER.md`](./README-DOCKER.md)（一键 `docker compose up -d` 起 mysql + server + client）
- **腾讯云 CVM**：[`doc/deploy-tencent-cloud.md`](./doc/deploy-tencent-cloud.md)（含 Nginx 反代、HTTPS、密钥管理等完整步骤）
- **远程脚本**：`python3 deploy.py`（读取 `.env.deploy`，rsync + 远程 `docker compose build && up -d`）

---

## 架构要点

- **JWT + 角色 API Key**：登录用 JWT（HS256，过期 15 分钟），DeepSeek API Key 存 `users.api_key`，可选 AES-256-GCM 加密（设 `ENCRYPTION_KEY` 启用）
- **单 LLM 多角色输出**：`ModeratorAgent.runJointSingleRound` 一次调用让 LLM 输出多角色对话块（`[角色名]: ...<<<END>>>`），`JointStreamParser` 边流式边解析，边持久化边广播
- **Moderator 状态机**：`IDLE → MODERATING → SPEAKING → WAITING_FOR_USER`，支持短消息的「线程延续」(`activeThreadOwner`) 和讨论中用户中途插入
- **WebSocket 安全上下文传递**：自定义 `SecurityContextAwareThread` 让 WS 触发的异步 LLM 调用能拿到当前用户身份，避免 JPA repository 报 `SecurityContext` 为空

---

## 开发约定

- 前端：Vue 3 组合式 API + `<script setup>` + TypeScript 严格模式
- 后端：分 `controller / service / repository` 三层，service 注入而非 static 调用
- 修改 `server/src/**` 后**必须重启后端**（Java 热部署有限）
- AI API Key **永不**出现在前端 bundle 或浏览器 console
- 完整约定见 [`CLAUDE.md`](./CLAUDE.md)

---

## 贡献

欢迎提 Issue / PR。提交前请：
1. 后端 `./mvnw test` 通过
2. 前端 `npm run test` 通过
3. Commit message 遵循 [Conventional Commits](https://www.conventionalcommits.org/)（`feat:` / `fix:` / `docs:` / `refactor:` ...）

---

## 许可证

本项目使用 **MIT License**。
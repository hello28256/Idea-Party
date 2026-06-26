# Idea Party

> AI 多角色聊天室平台：在一个对话框里同时和多个 AI 角色对话,类似群聊或圆桌讨论。

[![Vue 3](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-ed8b00)](https://openjdk.org/projects/jdk/21)
[![License](https://img.shields.io/badge/license-MIT-blue)](#许可证)

---

## 目录

1. [项目简介](#项目简介)
2. [功能特性](#功能特性)
3. [技术栈](#技术栈)
4. [项目结构](#项目结构)
5. [快速开始](#快速开始)
6. [预设角色与分类推荐](#预设角色与分类推荐)
7. [场景模板](#场景模板)
8. [管理后台](#管理后台)
9. [API 速览](#api-速览)
10. [架构要点](#架构要点)
11. [部署](#部署)
12. [环境变量](#环境变量)
13. [开发约定](#开发约定)
14. [常见问题 / 故障排查](#常见问题--故障排查)
15. [贡献](#贡献)
16. [许可证](#许可证)

---

## 项目简介

**Idea Party** 让用户轻松创建**多元视角 AI 对话场景**:

- 在一个房间放进多个 AI 角色(历史人物 / 领域专家 / 自定义角色)
- 由 **Moderator Agent** 智能编排发言顺序,避免一拥而上或冷场
- 角色 prompt 可由系统根据角色名自动联网检索公开信息生成(Firecrawl + LLM)
- **DeepSeek API Key 只存后端**,前端永远拿不到
- 内置 4 个开箱即用的「场景」模板(面试模拟 / 产品头脑风暴 / 英语陪练 / 写作助手)
- 内置 **120 个** 预设角色(苏格拉底、爱因斯坦、孔子、马云、乔布斯等),按分类筛选与推荐

适合用来做:技术面试模拟、产品头脑风暴、语言学习陪练、稿件审阅、圆桌讨论等。

---

## 功能特性

- 🎭 **角色系统** — 120 个预设角色 + 自定义角色;按分类筛选与推荐(`/api/characters/recommended?category=...`);支持上传头像、按角色名自动检索维基百科生成 persona prompt
- 💬 **两种房间模式** — `dialogue`(@提及 + 智能选人)/ `discussion`(多轮 Moderator 编排 + 暂停/恢复/停止)
- ⚡ **实时流式聊天** — 字符级推送,多角色用一次 LLM 调用并行输出(联合 prompt + 行内解析器)
- 👍👎 **反馈系统** — Like / Dislike + 5 类差评(答非所问 / 事实不准 / 不安全 / 风格差 / 其他)+ 备注;后台汇总观测量
- 📊 **管理后台** — `/admin/feedbacks` 查看所有 AI 消息的反馈、流式状态(成功/空/失败)、响应延迟分桶
- 🪟 **场景模板** — 一键启动 4 个常用场景,会先问用户补充输入再创建角色
- 🎨 **主题切换** — 浅色 / 深色 / 跟随系统,后端持久化
- 🛡 **速率限制** — Bucket4j 按 IP 限流,避免误用
- 📎 **简历解析** — 上传 .docx / .pdf / .txt 自动提取文本(Apache Tika)
- 📷 **JD OCR** — JD 截图拖拽 / 粘贴识别

---

## 技术栈

### 前端 (`client/`)

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5.34 | UI 框架,组合式 API + `<script setup>` |
| TypeScript | 6.0.3 | 严格类型检查 |
| Vite | 8.0.11 | 构建 / HMR |
| Pinia | 3.0.4 | 状态管理(7 个 store) |
| Vue Router | 5.0.6 | SPA 路由 |
| Tailwind CSS | 4.3.0 | 原子化样式 |
| socket.io-client | 4.8.3 | 实时通信(实际走原生 WebSocket + 自实现 Socket.IO framing) |
| Axios | 1.16.0 | HTTP 客户端(9 个 api 模块) |
| Lucide | 1.0.0 | 图标 |
| Vitest | 4.1.x | 单元测试 |
| Playwright | 1.59.1 | E2E 测试 |

### 后端 (`server/`)

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.3 | 应用框架 |
| Java | 21 LTS | 运行时(Maven `<java.version>21</java.version>`) |
| LangChain4j (OpenAI 兼容) | 1.0.0-beta2 | DeepSeek 编排(chat + streaming) |
| Spring Data JPA + Hibernate | 6.x | ORM,MySQL 自动建表(`ddl-auto: update`) |
| MySQL | 8.x | 主数据库 |
| Spring WebSocket | — | `/ws` 端点(Socket.IO framing) |
| JJWT | 0.12.5 | JWT 鉴权(HS512) |
| Bucket4j | 8.14.0 | 速率限制 |
| Springdoc OpenAPI | 2.8.6 | `/swagger-ui.html` |
| Apache Tika | 2.9.1 | .docx / .pdf 解析 |
| Firecrawl | — | 角色联网检索(无 key 时走 mock fallback) |
| dotenv-java | 3.0.2 | 自动加载 `.env` |

> 完整依赖版本以 `client/package.json` / `server/pom.xml` 为准。

### 工具链

- 包管理:**pnpm**(已从 npm 切换,见 `pnpm-lock.yaml` / `pnpm-workspace.yaml`)
- 后端构建:Maven 3.9+
- 部署:docker-compose(本地) + `deploy.py`(远程 rsync + docker compose)

---

## 项目结构

```text
Idea-Party/
├── client/                                # Vue 3 前端
│   ├── src/
│   │   ├── api/                           # 9 个 axios 模块:auth/user/settings/characters/scenarios/rooms/messages/messageEvents/messageFeedback
│   │   ├── components/                    # 业务组件(character/chat/room/scenario/feedback/admin/settings/ui)
│   │   ├── composables/                   # useSocket / useToast / useMessageEvents / useCredentialStorage / usePasswordStrength
│   │   ├── layouts/                       # LegalLayout(法务页布局)
│   │   ├── router/                        # 路由表(index.ts)
│   │   ├── stores/                        # 7 个 Pinia:auth/character/room/message/scenario/settings/theme
│   │   ├── views/                         # 11 个页面级(Login/Register/RoomList/Chat/CharacterLibrary/CharacterCreate/Settings/Privacy/Terms/admin/...)
│   │   ├── config/                        # sidebar 配置等
│   │   ├── App.vue / main.ts
│   │   └── style.css
│   ├── tests/                             # vitest 单元 + playwright E2E
│   ├── package.json
│   ├── vite.config.ts                     # /api /ws /uploads 代理到后端
│   └── Dockerfile + nginx.conf           # 容器化构建 + Nginx 反代
│
├── server/                                # Spring Boot 后端
│   ├── src/main/java/com/ideaparty/
│   │   ├── IdeaPartyApplication.java      # @SpringBootApplication 入口
│   │   ├── controller/                    # 15 个 REST 控制器
│   │   ├── service/                       # 23 个服务(ModeratorAgent/AIService/MockAiService/FirecrawlService/...)
│   │   ├── socket/                        # ChatSocketHandler — 当前活跃 WS handler(Socket.IO framing)
│   │   ├── websocket/                     # ChatWebSocketHandler — 旧 STOMP 实现,保留作回退
│   │   ├── entity/                        # 12 个 JPA 实体(含 2 个枚举:CharacterCategory / FeedbackCategory / FeedbackType)
│   │   ├── repository/                    # 9 个 Spring Data 仓库
│   │   ├── dto/                           # 33 个 DTO
│   │   ├── config/                        # Security/Socket/WebSocket/Cors/Web/LangChain4j/RestTemplate/OpenApi/RateLimiter/DataLoader
│   │   ├── cache/                         # PresetCharacterCache(预设角色内存缓存)
│   │   ├── filter/                        # Bucket4j 限流 + JWT
│   │   └── exception/                     # GlobalExceptionHandler + 自定义异常
│   ├── src/main/resources/
│   │   ├── application.yml                # 默认配置
│   │   ├── presets.json                   # 120 个预设角色(JSON 驱动,内存缓存)
│   │   ├── prompts/                       # 4 个 prompt 模板(character-prompt-generator/interview-prompt-generator/moderator/moderator-joint)
│   │   └── db/migration/                  # Flyway 早期 SQL 迁移
│   └── pom.xml
│
├── docker/
│   └── mysql/                             # MySQL 容器配置(conf.d + init)
│       ├── conf.d/ideaparty.cnf
│       └── init/00-charset.sql
├── docker-compose.yml                     # mysql + server + client 三服务
├── deploy.py                              # 一键部署脚本(rsync + 远程 docker compose)
├── README-DOCKER.md                       # Docker 部署详情(独立文档,避免主 README 过长)
├── doc/                                   # 旧版文档(部署/测试),逐步迁出到 docs/
├── docs/                                  # 新版专题文档(message-flow 等)
├── CLAUDE.md                              # AI 助手的项目上下文与约定
├── Idea.md                                # 项目原始需求说明
└── worktree.md                            # Git Worktree 使用规范
```

---

## 快速开始

### 环境要求

- **Node.js** ≥ 20.19(开发前端)
- **pnpm** ≥ 9(前端包管理;`npm i -g pnpm`)
- **Java** 21 LTS(后端)
- **Maven** ≥ 3.9(后端构建)
- **MySQL** 8.x(本地或 Docker)
- 一个 **DeepSeek API Key**([申请](https://platform.deepseek.com))
- 可选:**Firecrawl API Key**(用于角色联网检索;缺省时走 mock fallback)

### 1. 克隆仓库

```bash
git clone git@github.com:hello28256/Idea-Party.git
cd Idea-Party
```

### 2. 启动 MySQL(任选其一)

**A. 用 Docker**(推荐):

```bash
docker run -d --name idea-mysql-dev \
  -e MYSQL_ROOT_PASSWORD=root123 \
  -e MYSQL_DATABASE=ideaparty \
  -p 3306:3306 \
  mysql:8.0
```

**B. 本地已安装 MySQL** — 创建数据库:

```sql
CREATE DATABASE ideaparty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 配置环境变量

仓库根目录有 `.env.production.example`,复制为本地 `.env`(`spring-boot` 通过 `dotenv-java` 自动加载):

```bash
cp .env.production.example .env
```

必填字段(否则启动失败):

| 变量 | 说明 |
|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码(本地默认 `root123`) |
| `JWT_SECRET` | Base64 编码,解码后 ≥ 32 字节;`openssl rand -base64 48` |
| `DEEPSEEK_API_KEY` | LLM Key;个人用户也可在「设置」页填自己的覆盖此值 |

可选字段:见 [环境变量](#环境变量) 完整清单。

### 4. 启动后端

```bash
cd server
mvn spring-boot:run -DskipTests
# 默认监听 :8080(开发常用 :8082;可用 SERVER_PORT=8082 mvn spring-boot:run 覆盖)
# Swagger UI: http://localhost:8080/swagger-ui.html
# 健康检查: http://localhost:8080/api/health
```

> ⚠️ 按项目约定:**修改 `server/src/**` 后必须重启后端**,Java 热部署能力有限。

### 5. 启动前端

```bash
cd ../client
pnpm install
pnpm dev
# 默认监听 :5173,dev proxy 把 /api /ws /uploads 转到 :8080
```

如果后端端口不是 8080,在 `client/.env` 设:

```bash
VITE_SERVER_PROXY_PORT=8082
```

打开 <http://localhost:5173>,注册账号 → 在「设置」页填入你的 DeepSeek API Key → 开始对话。

### 6. 测试

```bash
# 后端单元测试
cd server && mvn test

# 前端单元测试
cd client && pnpm test

# 前端 E2E(需要 Playwright 浏览器)
cd client && pnpm test:e2e

# 前端类型检查
cd client && pnpm typecheck
```

---

## 预设角色与分类推荐

为避免「上百个角色难挑选」的问题,系统提供 **分类枚举 + 推荐接口**,由 `CharacterCategory` 驱动前端 chip 筛选。

- **数据源**:`server/src/main/resources/presets.json`(120 个角色)
- **加载时机**:应用启动时由 `PresetCharacterCache`(`server/src/main/java/com/ideaparty/cache/`)加载到内存
- **接口**:
  - `GET /api/characters/presets` — 全量预设
  - `GET /api/characters/recommended?category=philosopher` — 按分类推荐
- **前端**:「角色库」页面顶部按 `CharacterCategory` 渲染 chip 筛选条;点击创建 / 编辑走 `CreateCharacterModal`(支持 `mode='create' | 'edit'`,复用同一组件,不再有独立的 `CharacterEditView.vue`)

> 预设角色数据曾以 SQL seed 形式存储,现已迁出数据库到 JSON 文件,便于版本管理与不重启服务的本地化扩展(`b2aba88` / `b0e92e6`)。

---

## 场景模板

项目内置 **4 个场景模板**,写在 `client/src/stores/scenario.ts`,开箱即用。每个场景都会先弹窗让用户补充输入(岗位描述 / 产品 idea / 题材 / 草稿),然后自动生成角色 + 创建房间。

| Emoji | ID | 场景 | 房间模式 | 用户输入 |
|-------|------|---------|---------|---------|
| 🎤 | `interview-coach` | **面试模拟** | single | 你想面试什么岗位 / 行业? |
| 💡 | `product-brainstorm` | **产品头脑风暴** | group | 你想打磨什么样的产品 idea? |
| 🇬🇧 | `english-tutor` | **英语陪练** | single | 想练什么场景? |
| ✍️ | `writing-coach` | **写作助手** | single | 这次要审什么稿子? |

支持 **JD 截图 OCR**:在「面试模拟」场景里,可直接把招聘网站的 JD 截图拖到弹窗下方,自动识别文字填充到「岗位描述」输入框。

---

## 管理后台

后台路由:**`/admin/feedbacks`**(需登录 + `User.isAdmin=true`)。

权限模型(双重兜底):

1. **首选**:`users.is_admin = TRUE`
2. **Fallback**:启动时 `DataLoader` 读取 `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`(默认 `admin123/admin123`,生产请通过环境变量覆盖),在「该用户名不存在」时按用户名幂等创建管理员账号

能力一览:

- **总览统计** — 所有 AI 消息数 / 未评 / 已评 / 已聚合
- **逐条观测** — 用户提问预览、AI 回复预览、流式状态(成功/空/失败)、响应延迟分桶(绿 <2s、黄 2–5s、橙 5–10s、红 >10s)、like/dislike 数、最后反馈时间
- **详情弹窗** — 原始消息 + 触发该消息的用户消息 + 房间 / 角色上下文
- **隐式信号聚合** — `/api/admin/messages/{id}/signals` 返回该消息的所有 REWRITE / COPY / READ_COMPLETE / EDIT / FOCUS 事件

实现细节见 `client/src/views/admin/AdminFeedbackView.vue` 和 `server/src/main/java/com/ideaparty/service/AdminObservationService.java`。

---

## API 速览

完整 REST 端点参见启动后访问 **`/swagger-ui.html`**。常用:

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 无 | 注册 |
| POST | `/api/auth/login` | 无 | 登录(返回 JWT) |
| GET  | `/api/characters/presets` | 无 | 120 个预设角色 |
| GET  | `/api/characters/recommended?category=...` | 无 | 按 `CharacterCategory` 推荐 |
| POST | `/api/characters/generate-prompt` | JWT | LLM 根据角色名生成 system prompt |
| GET  | `/api/rooms` | JWT | 我的房间列表 |
| POST | `/api/rooms` | JWT | 创建房间 |
| POST | `/api/rooms/{id}/messages` | JWT | 发送消息(也走 WebSocket) |
| POST | `/api/messages/{id}/feedback` | JWT | 点赞 / 点踩 |
| GET  | `/api/admin/feedbacks` | Admin | 后台反馈列表 |

WebSocket 端点 **`/ws`**(前端用 Socket.IO framing 自实现):

| 方向 | 事件 | 用途 |
|------|------|------|
| 客户端→服务端 | `join room` | 进入房间(带 JWT) |
| 客户端→服务端 | `chat message` | 发送消息 |
| 客户端→服务端 | `pause-discussion` / `resume-discussion` / `stop-discussion` | 控制讨论 |
| 服务端→客户端 | `chat message` | 完整消息(带 ID 用于去重) |
| 服务端→客户端 | `chat chunk` | 流式字符片段 |
| 服务端→客户端 | `character thinking` | 当前正在思考的角色 |
| 服务端→客户端 | `discussion-state` | 讨论状态机变化 |
| 服务端→客户端 | `moderator-message` | Moderator 的内部消息 |

详见 `client/src/composables/useSocket.ts` 与 `server/src/main/java/com/ideaparty/socket/ChatSocketHandler.java`。

---

## 架构要点

### 鉴权

- **JWT (HS512)**:登录返回 token,过期 15 分钟(默认 `JWT_EXPIRATION=900000`),前后端都存 localStorage
- **角色 API Key**:`users.api_key` 字段存用户自己的 DeepSeek Key;可选用 AES-256-GCM 加密(设 `ENCRYPTION_KEY` 启用)
- **速率限制**:Bucket4j 按 IP 限流,全局 `RateLimitingFilter`

### AI 编排

- **单 LLM 多角色输出**:`ModeratorAgent.runJointSingleRound` 一次调用让 LLM 输出多角色对话块(`[角色名]: ...<<<END>>>`),`JointStreamParser` 边流式边解析,边持久化边广播
- **Moderator 状态机**:`IDLE → MODERATING → SPEAKING → WAITING_FOR_USER`,支持短消息的「线程延续」(`activeThreadOwner`)和讨论中用户中途插入
- **WebSocket 安全上下文传递**:自定义 `SecurityContextAwareThread` 让 WS 触发的异步 LLM 调用能拿到当前用户身份,避免 JPA repository 报 `SecurityContext` 为空
- **Fallback 链**:`AIService` → `MockAiService`,DeepSeek Key 缺失 / 调用失败时不报错,提供可用的本地化回复

### 数据模型(12 个实体)

```
User ──< Room >── RoomMember
                │
                └──< Message >── MessageFeedback
                │            └──< MessageEvent (隐式信号)
                │            └── MessageObservation (流式状态/延迟)
                │
                └──< Character (含 CharacterCategory 枚举)
                │
UserScenario (场景模板实例化)
FeedbackCategory / FeedbackType (枚举)
```

关键级联:`Room.members` / `Room.messages` / `Message.events` / `Message.feedbacks` 都是 `cascade=ALL, orphanRemoval=true`,删除 Room 会自动清理所有关联数据。

### 前后端通信

- **REST**:`axios` 实例 + 拦截器(自动注入 JWT、错误 toast、统一 401 跳登录)
- **实时**:`socket.io-client` 但服务端是自实现 Socket.IO framing(`socket/ChatSocketHandler`),不走 STOMP;`websocket/ChatWebSocketHandler` 是旧 STOMP 实现,保留作回退
- **鉴权握手**:连接 `/ws` 时在 query 或首条 `join room` 消息携带 JWT

### 文件上传

- 上传大小:`spring.servlet.multipart.max-file-size=25MB`,单文件 5MB(头像 / 简历)
- 持久化:Docker 部署挂载 `server-uploads` 卷到容器内 `/app/uploads`;重启不丢
- 简历解析:Apache Tika 2.9.1 抽取 docx / pdf / txt 文本

---

## 部署

- **Docker / Compose 一键起**:见 [`README-DOCKER.md`](./README-DOCKER.md)
- **腾讯云 CVM**:`python3 deploy.py` 走 rsync 差量 + 远程 `docker compose build && up -d`,配置见 `.env.deploy`(仓库不提交,提交 `.env.deploy.example`)
- **反代 / HTTPS**:Nginx 前置(见 `docs/deploy-tencent-cloud.md`)

### 本地开发 vs Docker 对照

| 维度 | 本地开发 | Docker Compose |
|------|---------|----------------|
| 前端端口 | 5173(Vite dev server) | 80(Nginx) |
| 后端端口 | 8080(Spring Boot) | 8082 → 8080 映射 |
| MySQL 端口 | 3306(本地或独立容器) | 不暴露到主机,仅 `idea-net` 内网 |
| 上传文件 | `server/uploads/` 目录 | `server-uploads` 卷(`/app/uploads`) |
| 日志 | 控制台 | `server-logs` 卷(`/app/logs`) |
| Nginx 代理缓存 | 无 | `nginx-cache` 卷(跨重建持久化) |

---

## 环境变量

完整字段以仓库根目录的 `.env.production.example` 为准;`docker-compose.yml` 通过 `env_file` 注入,Spring Boot relaxed binding 允许 `SPRING_DATASOURCE_PASSWORD` 等带前缀的同名变量覆盖 `application.yml`。

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `MYSQL_ROOT_PASSWORD` | ✅ | — | MySQL root 密码 |
| `MYSQL_DATABASE` | — | `ideaparty` | 数据库名 |
| `JWT_SECRET` | ✅ | — | Base64,解码 ≥ 256 bit |
| `JWT_EXPIRATION` | — | `900000`(15 min) | JWT 过期毫秒数 |
| `DEEPSEEK_API_KEY` | ✅ | — | LLM Key;缺省则走 `MockAiService` 不报错 |
| `DEEPSEEK_BASE_URL` | — | `https://api.deepseek.com` | OpenAI 兼容 base URL |
| `DEEPSEEK_MODEL` | — | `deepseek-chat` | 模型名 |
| `PUBLIC_BASE_URL` | — | `http://localhost` | 用于 CORS / OAuth 回调 |
| `APP_CORS_ALLOWED_ORIGINS` | — | 写死 localhost:5173-5177 | 逗号分隔;生产请覆盖为正式域名 |
| `APP_ADMIN_USERNAME` | — | `admin123` | 首次启动 DataLoader 种子管理员(仅在该用户名不存在时生效) |
| `APP_ADMIN_PASSWORD` | — | `admin123` | 同上;生产务必覆盖 |
| `CLIENT_PORT` | — | `80` | Nginx 对外端口 |
| `SERVER_JAVA_OPTS` | — | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8` | JVM 参数 |
| `SERVER_PORT` | — | `8080` | 后端端口(开发常用 `8082`) |
| `ENCRYPTION_KEY` | — | — | Base64 32 字节;填了之后用户 API Key 在 DB 中 AES-256-GCM 加密 |
| `FIRECRAWL_API_KEY` | — | — | 联网检索;缺省走 mock fallback |
| `VITE_SERVER_PROXY_PORT` | — | `8080` | 前端 dev proxy 目标端口 |
| `VITE_WS_URL` | — | 空(同源) | 前端 WebSocket 目标 |

> `.env.production`、`.env`、`.env.deploy` 均在 `.gitignore` 中,**严禁提交**;模板文件 `.env.production.example` / `.env.deploy.example` 可公开。

---

## 开发约定

- 前端:Vue 3 组合式 API + `<script setup>` + TypeScript 严格模式
- 后端:分 `controller / service / repository` 三层,service 注入而非 static 调用
- 修改 `server/src/**` 后**必须重启后端**(Java 热部署有限)
- AI API Key **永不**出现在前端 bundle 或浏览器 console
- 所有面向阅读者的注释用**中文**(Java / Vue / SQL 通用)
- 前端包管理:**pnpm**(已锁定,新增依赖前先 `pnpm add <pkg>`,不要再 `npm install`)
- 完整约定见 [`CLAUDE.md`](./CLAUDE.md)

---

## 常见问题 / 故障排查

<details>
<summary><b>Q: 启动报错 "Access denied for user 'root'@'localhost'"</b></summary>

检查 `MYSQL_ROOT_PASSWORD` / `SPRING_DATASOURCE_PASSWORD` 是否与 MySQL 实际密码一致(本地默认 `root123`)。Docker 部署走 compose 默认值,环境变量覆盖后需 `docker compose up -d --force-recreate server` 才生效。

</details>

<details>
<summary><b>Q: 前端调 API 返回 401</b></summary>

JWT 过期或未正确设置 `Authorization: Bearer xxx` 头。重新登录获取新 token;`JWT_EXPIRATION` 默认 15 分钟,生产可调大。

</details>

<details>
<summary><b>Q: 删除聊天室报 500 / 外键约束</b></summary>

检查 `Message.events` / `Message.feedbacks` 的 `@OneToMany` 反向级联是否配齐(参见 `server/src/main/java/com/ideaparty/entity/Message.java`)。`Room.members` / `Room.messages` 都应该是 `cascade=ALL, orphanRemoval=true`。

</details>

<details>
<summary><b>Q: LLM 回复乱码或格式不对</b></summary>

通常是 Moderator 联合 prompt 解析失败。看后端日志 `[DEBUG] JointStreamParser` 段输出,确认 prompt 模板(`server/src/main/resources/prompts/moderator-joint-prompt.txt`)没被改坏;同时检查 `CharacterCategory` 枚举值是否在 prompt 模板里被正确列出。

</details>

<details>
<summary><b>Q: Swagger UI 打开 401</b></summary>

`/swagger-ui.html` 默认放行无需鉴权。若返回 401,检查 `SecurityConfig` 中 `AntPathRequestMatcher` 是否包含 `/swagger-ui/**` 与 `/v3/api-docs/**`。

</details>

<details>
<summary><b>Q: WebSocket 频繁断连 / 收不到流式输出</b></summary>

- 浏览器 DevTools → Network → WS 面板观察握手是否带 JWT(在 query string 或首条 join 消息)
- 后端日志搜索 `[SecurityContextAwareThread]` 确认 WS 异步任务能拿到当前用户
- Nginx 反代时记得加 `proxy_set_header Upgrade $http_upgrade;` 与 `proxy_http_version 1.1;`

</details>

<details>
<summary><b>Q: 上传文件失败 / 报 413</b></summary>

`application.yml` 默认 `multipart.max-file-size=25MB`,头像 / 简历单文件 5MB。超过会直接 413,前端 axios 拦截器会 toast 提示。

</details>

<details>
<summary><b>Q: 改后端代码没生效</b></summary>

按项目约定**必须重启**后端:`docker compose restart server` 或本地 `mvn spring-boot:run` 退出再起。Java 的 Spring DevTools / JRebel 都没启用。

</details>

<details>
<summary><b>Q: JPA ddl-auto=update 在生产安全吗?</b></summary>

`update` 模式**只会加列、不会删列、不会改类型**。生产推荐改用 Flyway(`db/migration/`);首次部署后切到 `validate` 模式,避免误改线上表结构。

</details>

---

## 贡献

欢迎提 Issue / PR。提交前请:

1. 后端 `mvn test` 通过
2. 前端 `pnpm test` + `pnpm typecheck` 通过
3. 前端 E2E(`pnpm test:e2e`)在涉及聊天流变更时必跑
4. Commit message 遵循 [Conventional Commits](https://www.conventionalcommits.org/)(`feat:` / `fix:` / `docs:` / `refactor:` ...)
5. 涉及后端改动请确认已重启服务再自测

---

## 许可证

本项目使用 **MIT License**。
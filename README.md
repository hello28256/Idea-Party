# Idea Party

> AI 多角色聊天室平台：在一个对话框里同时和多个 AI 角色对话，类似群聊或圆桌讨论。

[![Vue 3](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-ed8b00)](https://openjdk.org/projects/jdk/21)
[![License](https://img.shields.io/badge/license-MIT-blue)](#许可证)

---

## 项目简介

**Idea Party** 是一个让用户轻松创建**多元视角 AI 对话场景**的平台。核心思路：

- 在一个房间放进多个 AI 角色（历史人物 / 领域专家 / 自定义角色）
- 由 **Moderator Agent** 智能编排发言顺序，避免一拥而上或冷场
- 角色 prompt 可由系统根据角色名自动联网检索公开信息生成（Firecrawl + LLM）
- **DeepSeek API Key 只存后端**，前端永远拿不到
- 提供 4 个开箱即用的「场景」模板（面试模拟 / 产品头脑风暴 / 英语陪练 / 写作助手）

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
- 🛡 **速率限制** — Bucket4j 按 IP 限流，避免误用
- 📎 **简历解析** — 上传 .docx / .pdf / .txt 自动提取文本（Tika）
- 📷 **JD OCR** — JD 截图拖拽 / 粘贴识别（Tesseract.js 前端 + 后端可选 OCR）

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
| socket.io-client | 4.8 | 实时通信（实际走原生 WebSocket + 自实现 Socket.IO framing） |
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
| JJWT | 0.12.5 | JWT 鉴权（HS512） |
| Bucket4j | 8.14 | 速率限制 |
| Springdoc OpenAPI | 2.8.6 | `/swagger-ui.html` |
| Apache Tika | 2.9 | .docx / .pdf 解析 |
| Firecrawl | — | 角色联网检索（无 key 时走 mock fallback） |
| dotenv-java | 3.0 | 自动加载 `.env` |

> 完整依赖版本以 `client/package.json` / `server/pom.xml` 为准。

---

## 项目结构

```text
Idea-Party/
├── client/                                # Vue 3 前端
│   ├── src/
│   │   ├── api/                           # 9 个 axios 模块：auth/rooms/messages/characters/...
│   │   ├── components/                    # 业务组件（chat/character/room/feedback/admin/settings/ui）
│   │   ├── composables/                   # useSocket / useToast / useMessageEvents 等
│   │   ├── layouts/                       # LegalLayout 等
│   │   ├── router/                        # 路由表
│   │   ├── stores/                        # 7 个 Pinia：auth/room/message/character/settings/scenario/theme
│   │   ├── views/                         # 页面级（Login/Rooms/Chat/CharacterLibrary/...）
│   │   ├── App.vue / main.ts
│   │   └── style.css
│   ├── tests/                             # vitest + playwright E2E
│   ├── package.json
│   └── vite.config.ts                     # /api /ws /uploads 代理到后端 :8082
│
├── server/                                # Spring Boot 后端
│   ├── src/main/java/com/ideaparty/
│   │   ├── controller/                    # 14 个 REST 控制器
│   │   ├── service/                       # 22 个服务（含 ModeratorAgent / AIService / FirecrawlService）
│   │   ├── socket/                        # ChatSocketHandler — 当前活跃 WS handler
│   │   ├── websocket/                     # ChatWebSocketHandler — 旧实现，仍保留
│   │   ├── entity/                        # 10 个 JPA 实体
│   │   ├── repository/                    # 8 个 Spring Data 仓库
│   │   ├── dto/                           # 34 个 DTO
│   │   ├── config/                        # Security / Socket / DataLoader / Cors / OpenApi / ...
│   │   ├── filter/                        # Bucket4j 限流 + JWT
│   │   └── exception/                     # GlobalExceptionHandler + 自定义异常
│   ├── src/main/resources/
│   │   ├── application.yml                # 默认配置（端口 8082 由 SERVER_PORT 覆盖）
│   │   ├── prompts/                       # 4 个 prompt 模板（character/interview/moderator/moderator-joint）
│   │   └── db/migration/                  # 早期 SQL 迁移
│   └── pom.xml
│
├── docker/                                # MySQL 配置（compose 首次启动时挂载）
├── docker-compose.yml                     # mysql + server + client 三服务
├── deploy.py                              # 一键部署脚本（rsync + 远程 docker compose）
├── README-DOCKER.md                       # Docker 部署详情
├── doc/                                   # 部署 / 测试文档
├── CLAUDE.md                              # AI 助手的项目上下文与约定
├── Idea.md                                # 项目原始需求说明
└── worktree.md                            # Git Worktree 使用规范
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

**A. 用 Docker**（推荐）：

```bash
docker run -d --name idea-mysql-dev \
  -e MYSQL_ROOT_PASSWORD=root123 \
  -e MYSQL_DATABASE=ideaparty \
  -p 3306:3306 \
  mysql:8.0
```

**B. 本地已安装 MySQL** — 创建数据库：

```sql
CREATE DATABASE ideaparty CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 配置环境变量

项目根目录有 `.env.production.example` 和 `.env.deploy.example`，复制一份本地用：

```bash
# 复制为 .env（项目根目录，Spring Boot 会通过 dotenv-java 自动加载）
cp .env.production.example .env
```

关键变量（**至少填这些才能跑起来**）：

| 变量 | 必填 | 说明 |
|------|------|------|
| `DB_PASSWORD` | ✅ | MySQL 密码（本地默认 `root123`） |
| `JWT_SECRET` | ✅ | ≥32 字符的随机串 |
| `DEEPSEEK_API_KEY` | ✅ | LLM Key；个人用户也可在「设置」页填自己的覆盖此值 |
| `SERVER_PORT` | — | 后端端口，默认 `8080`，开发常用 `8082` |
| `DEEPSEEK_BASE_URL` | — | 默认 `https://api.deepseek.com` |
| `FIRECRAWL_API_KEY` | — | 缺省时角色检索走 mock fallback |
| `ENCRYPTION_KEY` | — | Base64 32 字节；填了之后用户 API Key 在 DB 中 AES-256-GCM 加密 |
| `APP_ADMIN_USER_IDS` | — | 逗号分隔 UUID，启动时自动提升为管理员 |

### 4. 启动后端

```bash
cd server
mvn spring-boot:run -DskipTests
# 默认监听 :8080（设 SERVER_PORT=8082 后改 :8082）
# Swagger UI: http://localhost:8082/swagger-ui.html
```

### 5. 启动前端

```bash
cd ../client
npm install
npm run dev
# 默认监听 :5173，dev proxy 把 /api /ws /uploads 转到 :8082
```

如果后端端口不是 8082，在 `client/.env` 设：

```bash
VITE_SERVER_PROXY_PORT=8080
```

打开 <http://localhost:5173>，注册账号 → 在「设置」页填入你的 DeepSeek API Key → 开始对话。

### 6. 测试

```bash
# 后端单元测试
cd server && mvn test

# 前端单元测试
cd client && npm run test

# 前端 E2E
cd client && npx playwright test
```

---

## 场景模板

项目内置 **4 个场景模板**，写在 `client/src/stores/scenario.ts`，开箱即用。每个场景都会先弹窗让用户补充输入（岗位描述 / 产品 idea / 题材 / 草稿），然后自动生成角色 + 创建房间。

| Emoji | ID | 场景 | 房间模式 | 用户输入 |
|-------|------|---------|---------|---------|
| 🎤 | `interview-coach` | **面试模拟** | single | 你想面试什么岗位 / 行业？ |
| 💡 | `product-brainstorm` | **产品头脑风暴** | group | 你想打磨什么样的产品 idea？ |
| 🇬🇧 | `english-tutor` | **英语陪练** | single | 想练什么场景？ |
| ✍️ | `writing-coach` | **写作助手** | single | 这次要审什么稿子？ |

支持 **JD 截图 OCR**：在「面试模拟」场景里，可直接把招聘网站的 JD 截图拖到弹窗下方，自动识别文字填充到「岗位描述」输入框。

---

## 管理后台

后台路由：**`/admin/feedbacks`**（需登录 + `User.isAdmin=true`）。

权限模型（双重兜底）：
1. **首选**：`users.is_admin = TRUE`
2. **Fallback**：启动时 `DataLoader` 读取 `APP_ADMIN_USER_IDS` 环境变量，自动把白名单用户置为 admin

能力一览：
- **总览统计** — 所有 AI 消息数 / 未评 / 已评 / 已聚合
- **逐条观测** — 用户提问预览、AI 回复预览、流式状态（成功/空/失败）、响应延迟分桶（绿 <2s、黄 2–5s、橙 5–10s、红 >10s）、like/dislike 数、最后反馈时间
- **详情弹窗** — 原始消息 + 触发该消息的用户消息 + 房间 / 角色上下文
- **隐式信号聚合** — `/api/admin/messages/{id}/signals` 返回该消息的所有 REWRITE / COPY / READ_COMPLETE / EDIT / FOCUS 事件

实现细节见 `client/src/views/admin/AdminFeedbackView.vue` 和 `server/src/main/java/com/ideaparty/service/AdminObservationService.java`。

---

## API 速览

完整 REST 端点参见启动后访问 **`/swagger-ui.html`**。常用：

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 无 | 注册 |
| POST | `/api/auth/login` | 无 | 登录（返回 JWT） |
| GET  | `/api/characters/presets` | 无 | 预设角色列表 |
| POST | `/api/characters/generate-prompt` | JWT | LLM 根据角色名生成 system prompt |
| GET  | `/api/rooms` | JWT | 我的房间列表 |
| POST | `/api/rooms` | JWT | 创建房间 |
| POST | `/api/rooms/{id}/messages` | JWT | 发送消息（也走 WebSocket） |
| POST | `/api/messages/{id}/feedback` | JWT | 点赞 / 点踩 |
| GET  | `/api/admin/feedbacks` | Admin | 后台反馈列表 |

WebSocket 端点 **`/ws`**（前端用 Socket.IO framing 自实现）：

| 方向 | 事件 | 用途 |
|------|------|------|
| 客户端→服务端 | `join room` | 进入房间（带 JWT） |
| 客户端→服务端 | `chat message` | 发送消息 |
| 客户端→服务端 | `pause-discussion` / `resume-discussion` / `stop-discussion` | 控制讨论 |
| 服务端→客户端 | `chat message` | 完整消息（带 ID 用于去重） |
| 服务端→客户端 | `chat chunk` | 流式字符片段 |
| 服务端→客户端 | `character thinking` | 当前正在思考的角色 |
| 服务端→客户端 | `discussion-state` | 讨论状态机变化 |
| 服务端→客户端 | `moderator-message` | Moderator 的内部消息 |

详见 `client/src/composables/useSocket.ts` 与 `server/src/main/java/com/ideaparty/socket/ChatSocketHandler.java`。

---

## 部署

- **Docker / Compose**：[`README-DOCKER.md`](./README-DOCKER.md)（一键 `docker compose up -d` 起 mysql + server + client）
- **腾讯云 CVM**：[`doc/deploy-tencent-cloud.md`](./doc/deploy-tencent-cloud.md)（含 Nginx 反代、HTTPS、密钥管理等完整步骤）
- **远程脚本**：`python3 deploy.py`（读取 `.env.deploy`，rsync + 远程 `docker compose build && up -d`）

---

## 架构要点

### 鉴权
- **JWT (HS512)**：登录返回 token，过期 15 分钟，前后端都存 localStorage
- **角色 API Key**：`users.api_key` 字段存用户自己的 DeepSeek Key；可选用 AES-256-GCM 加密（设 `ENCRYPTION_KEY` 启用）
- **速率限制**：Bucket4j 按 IP 限流，全局 `RateLimitingFilter`

### AI 编排
- **单 LLM 多角色输出**：`ModeratorAgent.runJointSingleRound` 一次调用让 LLM 输出多角色对话块（`[角色名]: ...<<<END>>>`），`JointStreamParser` 边流式边解析，边持久化边广播
- **Moderator 状态机**：`IDLE → MODERATING → SPEAKING → WAITING_FOR_USER`，支持短消息的「线程延续」(`activeThreadOwner`) 和讨论中用户中途插入
- **WebSocket 安全上下文传递**：自定义 `SecurityContextAwareThread` 让 WS 触发的异步 LLM 调用能拿到当前用户身份，避免 JPA repository 报 `SecurityContext` 为空

### 数据模型（10 个实体）
- `User` — 用户
- `Room` / `RoomMember` — 聊天室与成员
- `Character` — AI 角色
- `Message` / `MessageObservation` — 消息及观测量
- `MessageFeedback` / `MessageEvent` — 显式反馈 + 隐式事件
- `Scenario` / `ScenarioTemplate` — 场景模板

关键级联：`Room.members` / `Room.messages` / `Message.events` / `Message.feedbacks` 都是 `cascade=ALL, orphanRemoval=true`，删除 Room 会自动清理所有关联数据。

---

## 开发约定

- 前端：Vue 3 组合式 API + `<script setup>` + TypeScript 严格模式
- 后端：分 `controller / service / repository` 三层，service 注入而非 static 调用
- 修改 `server/src/**` 后**必须重启后端**（Java 热部署有限）
- AI API Key **永不**出现在前端 bundle 或浏览器 console
- 所有面向阅读者的注释用**中文**（Java / Vue / SQL 通用）
- 完整约定见 [`CLAUDE.md`](./CLAUDE.md)

---

## 常见问题

<details>
<summary><b>Q: 启动报错 "Access denied for user 'root'@'localhost'"</b></summary>

检查 `DB_PASSWORD` 是否与 MySQL 实际密码一致（本地默认 `root123`）。

</details>

<details>
<summary><b>Q: 前端调 API 返回 401</b></summary>

JWT 过期或未正确设置 `Authorization: Bearer xxx` 头。重新登录获取新 token。

</details>

<details>
<summary><b>Q: 删除聊天室报 500</b></summary>

数据库外键约束。检查 `Message.events` / `Message.feedbacks` 的 `@OneToMany` 反向级联是否配齐（参见 `server/src/main/java/com/ideaparty/entity/Message.java`）。

</details>

<details>
<summary><b>Q: LLM 回复乱码或格式不对</b></summary>

通常是 Moderator 联合 prompt 解析失败。看后端日志 `[DEBUG] JointStreamParser` 段输出，确认 prompt 模板（`server/src/main/resources/prompts/moderator-joint-prompt.txt`）没被改坏。

</details>

---

## 贡献

欢迎提 Issue / PR。提交前请：
1. 后端 `mvn test` 通过
2. 前端 `npm run test` 通过
3. Commit message 遵循 [Conventional Commits](https://www.conventionalcommits.org/)（`feat:` / `fix:` / `docs:` / `refactor:` ...）
4. 涉及后端改动请确认已重启服务再自测

---

## 许可证

本项目使用 **MIT License**。
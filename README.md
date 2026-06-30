# Idea Party

> AI 多角色聊天室平台：在一个对话框里同时和多个 AI 角色对话，类似群聊或圆桌讨论。

[![Vue 3](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-ed8b00)](https://openjdk.org/projects/jdk/21)
[![License](https://img.shields.io/badge/license-MIT-blue)](#许可证)

---

## 目录

1. [项目简介](#项目简介)
2. [核心特性](#核心特性)
3. [技术栈](#技术栈)
4. [项目结构](#项目结构)
5. [快速开始](#快速开始)
6. [预设角色与场景模板](#预设角色与场景模板)
7. [管理后台](#管理后台)
8. [API 速览](#api-速览)
9. [架构要点](#架构要点)
10. [部署](#部署)
11. [环境变量](#环境变量)
12. [开发约定](#开发约定)
13. [常见问题](#常见问题)
14. [贡献](#贡献)
15. [许可证](#许可证)

---

## 项目简介

**Idea Party** 让用户轻松创建**多元视角 AI 对话场景**:

- 在一个房间放进多个 AI 角色(历史人物 / 领域专家 / 自定义角色)
- 由 **Moderator Agent** 智能编排发言顺序,避免一拥而上或冷场
- 角色 prompt 可由系统根据角色名自动联网检索公开信息生成(Firecrawl + DeepSeek)
- **DeepSeek API Key 只存后端**,前端永远拿不到
- 内置 **22 个** 开箱即用的「场景」模板(面试模拟 / 论文答辩 / 客户谈判 / 健身咨询 / 心理倾听 等)
- 内置 **585 个** 预设角色(苏格拉底、爱因斯坦、孔子、马云、乔布斯等),按 **12 个分类** 筛选与推荐

适合用来做:技术面试模拟、产品头脑风暴、语言学习陪练、稿件审阅、圆桌讨论等。

---

## 核心特性

- 🎭 **角色系统** — 585 个预设角色(从 `presets.json` 加载) + 自定义角色;12 类分类筛选(`/api/characters/recommended?category=...`);支持上传头像、按角色名自动检索生成 persona prompt
- 💬 **两种房间模式**
  - `dialogue` — @提及 + 智能选人
  - `discussion` — 多轮 Moderator 编排,支持暂停 / 恢复 / 停止
- ⚡ **实时流式聊天** — 字符级推送,多角色用一次 LLM 调用并行输出(联合 prompt + 行内解析器)
- 👍👎 **反馈系统** — Like / Dislike + 5 类差评(答非所问 / 事实不准 / 不安全 / 风格差 / 其他)+ 备注
- 📊 **管理后台** — `/admin/feedbacks` 查看所有 AI 消息的反馈、流式状态(成功/空/失败)、响应延迟分桶
- 🪟 **场景模板** — 一键启动 22 个常用场景,会先问用户补充输入再创建角色;**用户也可创建私有场景**(`POST /api/user-scenarios`)
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
| socket.io-client | 4.8.3 | 实时通信(经后端原生 WebSocket + 自实现 Socket.IO framing) |
| Axios | 1.16.0 | HTTP 客户端(10 个 api 模块) |
| Lucide | 1.0.0 | 图标 |
| Vitest | 4.1.x | 单元测试 |
| Playwright | 1.59.1 | E2E 测试 |

### 后端 (`server/`)

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.3 | 应用框架 |
| Java | 21 LTS | 运行时 |
| LangChain4j (OpenAI 兼容) | 1.0.0-beta2 | DeepSeek 编排(chat + streaming) |
| Spring Data JPA + Hibernate | 6.x | ORM,MySQL 自动建表(`ddl-auto: update`) |
| MySQL | 8.x | 主数据库 |
| Spring WebSocket | — | `/ws` 端点(自实现 Socket.IO framing) |
| JJWT | 0.12.5 | JWT 鉴权(HS512) |
| Bucket4j | 8.14.0 | 速率限制 |
| Springdoc OpenAPI | 2.8.6 | `/swagger-ui.html` |
| Apache Tika | 2.9.1 | .docx / .pdf 解析 |
| Firecrawl | v1/v2 REST | 角色联网检索(无 key 时走 mock fallback) |
| dotenv-java | 3.0.2 | 自动加载 `.env` |

> 完整依赖版本以 `client/package.json` / `server/pom.xml` 为准。

### 工具链

- 包管理:**npm**(根目录 `package-lock.json`)
- 后端构建:Maven 3.9+
- 部署:docker-compose(本地) + `deploy.py`(远程 rsync + docker compose)

---

## 项目结构

```text
Idea-Party/
├── client/                                # Vue 3 前端
│   ├── src/
│   │   ├── api/                           # 10 个 axios 模块
│   │   │                                  # auth/user/settings/characters/scenarios/rooms
│   │   │                                  # messages/messageEvents/messageFeedback/hotRooms
│   │   ├── components/                    # 业务组件
│   │   │                                  # admin/character/chat/feedback/room/scenario/settings/ui
│   │   ├── composables/                   # useSocket / useToast / useMessageEvents
│   │   │                                  # useCredentialStorage / usePasswordStrength
│   │   ├── layouts/                       # LegalLayout(法务页布局)
│   │   ├── router/                        # 路由表(index.ts)
│   │   ├── stores/                        # 7 个 Pinia:auth/character/room/message/scenario/settings/theme
│   │   ├── views/                         # Login/Register/RoomList/Chat/CharacterLibrary
│   │   │                                  # CharacterCreate/Settings/Privacy/Terms/admin/...
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
│   │   ├── controller/                    # 16 个 REST 控制器
│   │   ├── service/                       # 23 个服务
│   │   │                                  # ModeratorAgent / AIService / MockAiService
│   │   │                                  # FirecrawlService / CharacterPromptBuilder / ...
│   │   ├── socket/                        # ChatSocketHandler — 当前活跃 WS handler(Socket.IO framing)
│   │   ├── websocket/                     # ChatWebSocketHandler — 旧 STOMP 实现,保留作回退
│   │   ├── entity/                        # 12 个 JPA 实体 + 3 个枚举
│   │   │                                  # CharacterCategory / FeedbackCategory / FeedbackType
│   │   ├── repository/                    # 9 个 Spring Data 仓库
│   │   ├── dto/                           # 40 个 DTO
│   │   ├── config/                        # Security / Socket / WebSocket / Cors / Web
│   │   │                                  # LangChain4j / RestTemplate / OpenApi
│   │   │                                  # RateLimiter / DataLoader
│   │   ├── cache/                         # PresetCharacterCache(预设角色内存缓存)
│   │   ├── filter/                        # Bucket4j 限流 + JWT
│   │   └── exception/                     # GlobalExceptionHandler + 自定义异常
│   ├── src/main/resources/
│   │   ├── application.yml                # 全局配置(已合并原 .properties)
│   │   ├── data.sql                       # 初始数据
│   │   ├── presets.json                   # 585 个预设角色(JSON 驱动,内存缓存)
│   │   ├── hotRooms.json                  # 热门房间配置
│   │   ├── prompts/                       # 4 个 prompt 模板
│   │   │                                  # character-prompt-generator / interview-prompt-generator
│   │   │                                  # moderator / moderator-joint
│   │   └── db/                            # SQL 迁移目录
│   └── pom.xml
│
├── docker/
│   └── mysql/                             # MySQL 容器配置(conf.d + init)
├── scripts/                               # 开发/运维脚本
│   ├── dev.sh                             # 本地一键启动(mysql 容器 + 后端 mvn + 前端 vite)
│   ├── rebuild-server.sh                  # --no-cache 重建后端镜像
│   ├── refresh-preset-avatar.sh           # 刷新预设头像
│   └── regen_preset_prompts.py            # 重新生成 preset prompt
├── docker-compose.yml                     # mysql + server + client 三服务
├── deploy.py                              # 一键部署脚本(rsync + 远程 docker compose)
├── README-DOCKER.md                       # Docker 部署详情(独立文档)
├── doc/                                   # 旧版文档,逐步迁出到 docs/
├── docs/                                  # 新版专题文档(message-flow 等)
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

仓库根目录创建 `.env`(参考 `application.yml` 的占位符):

```env
DB_URL=jdbc:mysql://localhost:3306/ideaparty?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8
DB_USERNAME=root
DB_PASSWORD=root123
JWT_SECRET=local-dev-jwt-secret-do-not-use-in-prod-32bytes-min
APP_CORS_ALLOWED_ORIGINS=http://localhost,http://127.0.0.1,http://localhost:5173

# 可选,留空也能跑(走 mock fallback)
DEEPSEEK_API_KEY=sk-your-deepseek-key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat

# 可选,留空走 mock
FIRECRAWL_API_KEY=fc-your-key
```

### 4. 启动后端

```bash
cd server
mvn spring-boot:run
# 启动后访问 http://localhost:8080/api/health 确认健康
```

### 5. 启动前端

```bash
cd client
npm install
npm run dev
# 默认 http://localhost:5173
```

### 6. 一键脚本(本地开发)

仓库根目录提供 `scripts/dev.sh`,可同时拉起 mysql 容器 + 后端 mvn + 前端 vite(后台模式):

```bash
./scripts/dev.sh           # 启动
./scripts/dev.sh --status  # 查看状态
./scripts/dev.sh --logs    # tail 日志
./scripts/dev.sh --stop    # 停止
```

### 7. 首次登录

`DataLoader` 会在首次启动时按 `.env` 中的 `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` 幂等创建默认管理员账号。
默认值为 `admin123` / `admin123`,**生产环境务必通过环境变量覆盖**。

---

## 预设角色与场景模板

### 12 个分类(枚举 `CharacterCategory`)

| 分类 | Emoji | 说明 |
|------|-------|------|
| SCIENTIST | 🔬 | 物理/数学/化学/生物等自然科学奠基人 |
| STAR | 🌟 | 演艺/音乐/体育娱乐领域公众人物 |
| ENTREPRENEUR | 🚀 | 科技/商业领域创始人/CEO/投资人 |
| PHILOSOPHER | 💭 | 东西方思想家,关注伦理/存在/认知 |
| ATHLETE | 🏆 | 足球/篮球/拳击等体育传奇 |
| WRITER | 📖 | 文学家/小说家/诗人 |
| ANIME | 🎨 | 动画/漫画领域虚拟或相关创作者 |
| HISTORICAL | 🏛️ | 政治/军事/宗教领域历史人物 |
| ARTIST | 🖼️ | 画家/雕塑家/音乐家/作曲家 |
| FICTIONAL | 🎭 | 小说/动漫/影视/游戏中的人物 |
| POLITICIAN | 🏛️ | 政治领袖/改革者/革命家 |
| MILITARY_LEADER | ⚔️ | 统帅/将领/军事理论家 |

调用 `GET /api/characters/recommended?category=SCIENTIST` 即可按分类拉取。

### 场景模板

- 系统级:22 个开箱即用模板(面试模拟 / 论文答辩 / 客户谈判 / 健身咨询 / 心理倾听 等)
- 用户级:`POST /api/user-scenarios` 允许登录用户创建自己的私有场景

---

## 管理后台

`/admin/feedbacks` 提供:

- 所有 AI 消息的反馈(Like / Dislike + 5 类差评分类)
- 流式状态(成功 / 空 / 失败)
- 响应延迟分桶

默认账号:`admin123` / `admin123`(**生产环境务必修改**)。前端路由的 `meta.requiresAdmin` 只做兜底,真正的权限拦截由后端接口保证。

---

## API 速览

> 完整定义见 `http://localhost:8080/swagger-ui.html`

| 模块 | 路径前缀 | 说明 |
|------|----------|------|
| Auth | `/api/auth` | 登录 / 注册 / 刷新 token |
| User | `/api/user` | 当前用户信息、修改密码 |
| Characters | `/api/characters` | 角色 CRUD、预设、按分类推荐、生成 prompt |
| Rooms | `/api/rooms` | 聊天室 CRUD、成员管理、模式切换 |
| RoomMember | `/api/rooms/{roomId}/members` | 加入/退出/邀请 |
| Messages | `/api/messages` | 消息历史查询 |
| MessageEvents | `/api/message-events` | 流式事件回放(断线重连用) |
| MessageFeedback | `/api/message-feedback` | 点赞/差评提交 |
| Scenarios | `/api/scenarios` | 面试 prompt 生成、简历解析、JD OCR |
| UserScenarios | `/api/user-scenarios` | 用户私有场景 CRUD |
| Settings | `/api/settings` | 用户设置(主题、LLM API key 等) |
| HotRooms | `/api/hot-rooms` | 热门房间推荐 |
| FileUpload | `/api/upload` | 头像 / 简历 / 图片上传 |
| Admin | `/api/admin/feedbacks` | 反馈观测后台 |
| Health | `/api/health` | 健康检查 |

WebSocket 端点:`ws://<host>/ws` (Socket.IO framing,默认路径见 `application.yml`)。

---

## 架构要点

### 实时聊天流程

```
Browser (socket.io-client)
  └─▶ Nginx (client 容器,反代 /api /ws /uploads)
        └─▶ Spring WebSocket (/ws) — ChatSocketHandler
              ├─▶ AuthService (JWT 校验,绑定 userId)
              ├─▶ ModerationService (内容审核)
              ├─▶ ModeratorAgent (讨论编排,见下)
              │     ├─▶ AIService (LangChain4j → DeepSeek)
              │     ├─▶ CharacterPromptBuilder (拼 system prompt)
              │     └─▶ MessageService (落库 MySQL)
              └─▶ MessageRepository
```

### 讨论编排(`ModeratorAgent`)

- 内部自定义线程池必须继承 Spring SecurityContext,否则子线程拿不到认证
- 状态(房间级 future / 讨论进度)用 `ConcurrentHashMap` 包裹,多线程安全
- 轮次上限硬编码为 `MAX_ROUNDS = 3`,可被调用方覆盖
- 轮间延迟 `ROUND_DELAY_MS = 1500` 让前端先把上一轮渲染完
- 用户中途插入:用房间级互斥锁 (`roomDiscussionLocks`) 串行化,避免并发用户消息触发并行讨论循环

### 联合 Prompt(多角色一次 LLM 调用并行输出)

`moderator-joint-prompt.txt` 模板把多个角色的 system prompt 拼成一次请求,LLM 流式返回后用行内解析器按角色名切分。
比"为每个角色各发起一次 LLM 调用"省 N-1 次握手延迟。

### 房间模式

- `dialogue` — 用户 @ 某角色时只让该角色回;智能选人由 LLM 选最相关角色
- `discussion` — 全部角色并行回 + 互相评论对方观点,达到 `MAX_ROUNDS` 自动结束

### 限流

`Bucket4jConfig` 按 IP 限流,默认对登录/注册等高敏感接口单独配 bucket,见 `RateLimiterConfig`。

---

## 部署

详见 [`README-DOCKER.md`](./README-DOCKER.md)。

简要流程:

```bash
# 1. 准备 .env.production
cp .env.production.example .env.production
# 填入 MYSQL_ROOT_PASSWORD / JWT_SECRET / DEEPSEEK_API_KEY / FIRECRAWL_API_KEY / PUBLIC_BASE_URL / APP_ADMIN_PASSWORD

# 2. 构建并启动
docker compose build
docker compose up -d

# 3. 查看日志
docker compose logs -f

# 4. 远程部署(可选)
#    编辑 .env.deploy,填 DEPLOY_HOST / DEPLOY_USER / DEPLOY_SSH_KEY
./deploy.py
```

### 部署后要点

- 客户端 SPA 的 `dist/` 是构建时烤进镜像的,**改前端源码不 rebuild 看不到**(见 `scripts/rebuild-server.sh`)
- `server-uploads` 是 named volume,宿主机改文件不会同步进容器
- `nginx-cache` 命名卷用于保留 nginx 代理缓存,避免每次部署后第一次访问吃 cache-miss 延迟
- 改了 `presets.json` 必须 `docker compose build server --no-cache`,否则命中 Maven 缓存,jar 不更新

---

## 环境变量

完整列表见 `.env.production.example`,关键项:

| 变量 | 必填 | 说明 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | ✅ | 强密码,≥32 字符 |
| `MYSQL_DATABASE` |  | 库名,默认 `ideaparty` |
| `JWT_SECRET` | ✅ | Base64 编码 ≥32 字节,生产用 `openssl rand -base64 48` |
| `JWT_EXPIRATION` |  | 毫秒,默认 900000(15 分钟) |
| `DEEPSEEK_API_KEY` | ✅ | 缺失时启动不报错但 AIService 走 dummy 模式 |
| `DEEPSEEK_BASE_URL` |  | 默认 `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` |  | 默认 `deepseek-chat` |
| `FIRECRAWL_API_KEY` |  | 缺失走 mock fallback |
| `PUBLIC_BASE_URL` | ✅ | 浏览器访问入口,用于 CORS |
| `APP_CORS_ALLOWED_ORIGINS` |  | 逗号分隔,缺省取 `PUBLIC_BASE_URL` + dev 端口 |
| `APP_ADMIN_USERNAME` |  | DataLoader 首次启动种子的管理员账号 |
| `APP_ADMIN_PASSWORD` |  | 同上,**生产务必覆盖** |
| `APP_ADMIN_USER_IDS` |  | 逗号分隔的 UUID 列表,`is_admin=false` 时按此名单放行 |
| `CLIENT_PORT` |  | 宿主机映射到 client 容器 80 端口的端口 |
| `SERVER_JAVA_OPTS` |  | JVM 参数,默认 `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` |
| `UPLOAD_AVATAR_MAX_SIZE` |  | 字节,默认 5MB |
| `UPLOAD_FILE_MAX_SIZE` |  | 字节,默认 5MB |
| `DB_URL` |  | JDBC URL,默认 `jdbc:mysql://localhost:3306/ideaparty?...` |
| `DB_USERNAME` / `DB_PASSWORD` |  | 数据库凭据 |

---

## 开发约定

1. **后端改完必须重启** — 修改 `server/` 任何 Java 代码后,`mvn spring-boot:run` / 容器内服务不会自动热加载
2. **前端改完不 rebuild 看不到效果** — 部署环境的 `client` 容器是构建时烤的,本地 dev 走 Vite HMR 不受影响
3. **预设数据修改后必须 `--no-cache`** — 改 `presets.json` 后 `docker compose build server --no-cache`
4. **API Key 只走后端** — 不允许前端直连 DeepSeek / Firecrawl
5. **i18n** — 注释 / 提交信息 / 文档默认中文
6. **类型严格** — 前端开启 TypeScript 严格模式;后端关键 DTO 显式标注 `@Valid` / `@NotNull`
7. **薄 Controller** — Controller 只做参数解析 + 鉴权注入 + 状态码映射,业务下沉到 Service
8. **构造器注入** — 避免字段注入,便于单测替换
9. **Git Worktree** — 多人协作时按 `worktree.md` 规范使用

---

## 常见问题

### 1. 改了 `presets.json` 但角色没更新

`docker compose build server` 命中 Maven 缓存,jar md5 不变。强制重建:

```bash
./scripts/rebuild-server.sh
# 或手动:docker compose build server --no-cache
```

### 2. 部署后前端页面看不到新改动

`client` 容器的 `dist/` 是 build 时烤进镜像的。改前端源码后必须:

```bash
docker compose build client
docker compose up -d client
```

### 3. `server/uploads` 文件丢失

`server-uploads` 是 named volume,宿主机直接 `rm` 容器内看不到。**不要手动删 volume**,先备份:

```bash
docker run --rm -v idea-server-uploads:/from -v $(pwd)/backup:/to alpine:3.19 \
  sh -c "cp -a /from/. /to/"
```

### 4. 角色 prompt 生成失败

无 Firecrawl API Key 时走 mock fallback,角色 prompt 会非常粗糙。申请 key:

```env
FIRECRAWL_API_KEY=fc-...
```

### 5. 流式聊天无响应

- 检查后端日志 `com.ideaparty: DEBUG`
- 浏览器 DevTools Network → WS → 看 `/ws` 帧
- 确认 `DEEPSEEK_API_KEY` 有效(走 dummy 模式不会有任何流式输出)

### 6. CORS 报错

`APP_CORS_ALLOWED_ORIGINS` 必须包含浏览器访问的 origin。`PUBLIC_BASE_URL` 是 CORS 兜底,二者至少填一个。

### 7. AI 角色声称自己是真人

`CharacterPromptBuilder` 生成的 system prompt 末尾固定追加「你是基于公开资料模拟的角色,不是真人本人」声明,这是项目硬性合规约束(见 `CLAUDE.md`)。若角色违反,检查 prompt 模板是否被改动。

---

## 贡献

1. Fork + Feature Branch
2. 提交信息建议:`type(scope): 描述` (e.g. `feat(character): 头像自动裁剪`)
3. PR 前跑:

```bash
# 前端
cd client && npm run typecheck && npm test

# 后端
cd server && mvn -DskipTests=false test
```

4. 涉及架构变更请先开 issue 讨论
5. 多人协作走 `worktree.md` 流程

---

## 许可证

MIT

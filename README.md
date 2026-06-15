# Idea Party

> AI 多角色聊天室平台 —— 与多个 AI 角色同时对话，享受圆桌讨论的乐趣。

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java](https://img.shields.io/badge/Java-21_LTS-green.svg)
![Vue](https://img.shields.io/badge/Vue-3.5-green.svg)

---

## 项目简介

Idea Party 是一个 AI 多角色聊天室平台，用户可以在一个对话框中同时与多个 AI 角色对话，类似群聊或圆桌讨论。

- 系统根据角色名称自动从互联网检索公开信息，生成结构化的人设 prompt。
- 由 **主持人 Agent（Moderator Agent）** 智能编排发言顺序、决定谁先说话、谁回应谁。
- 讨论过程中用户可以随时插话，AI 会基于上下文智能响应。
- API Key 仅在后端封装，前端从不持有，支持每位用户自带 DeepSeek Key。

核心价值：让用户轻松创建多元视角的 AI 对话场景，通过智能发言编排实现自然、有逻辑的群聊体验。

---

## 功能特性

### 角色系统

- **创建 AI 角色** —— 自定义角色名称、描述、人设、专业领域、年代、说话风格
- **预设角色库** —— 内置多个经典角色（爱因斯坦、孔子等），快速体验
- **联网检索增强** —— 通过 Firecrawl 抓取公开信息，丰富人设细节（不可用时走 mock）
- **AI 生成 prompt** —— 一键生成角色人设 prompt
- **角色头像上传** —— 支持自定义角色头像
- **角色管理** —— 角色库管理、编辑、删除（仅所有者可操作）

### 聊天室与场景

- **多角色群聊** —— 同时与多个 AI 角色交流
- **场景 Tab** —— 顶栏在「房间 / 场景 / 角色库」之间切换，快速进入不同入口
- **房间成员** —— 支持把其他用户拉入房间（按用户名 / 显示名 / 邮箱关键字邀请）
- **讨论模式（Discussion）** —— 多角色有序讨论，主持人 Agent 全局编排多轮
- **对话模式（Dialogue）** —— 由 LLM 智能选择 1~N 个最合适的角色回复，支持 @ 提及
- **暂停 / 继续 / 停止** —— 灵活控制讨论进程
- **轮次限制** —— 可配置最多讨论轮数

### 聊天体验

- **实时消息推送** —— WebSocket + 流式响应，毫秒级更新
- **字符级流式输出** —— AI 文本逐字推送，模拟真实打字节奏
- **思考指示器** —— AI 生成响应时显示思考状态
- **消息分组** —— 连续消息自动合并，IM 风格展示
- **聊天历史** —— 持久化消息记录，支持分页加载
- **用户插话** —— 讨论过程中可随时插话，主持人会重新组织后续流程
- **自动滚动** —— 新消息自动滚到底部
- **输入法兼容** —— IME 中文输入中按 Enter 不发送

### 用户与设置

- **注册 / 登录** —— JWT 认证
- **个人资料** —— 修改用户名、头像、主题
- **主题切换** —— 浅色 / 深色 / 跟随系统
- **API Key 配置** —— 用户可自带 DeepSeek API Key
- **设置浮层** —— SettingsModal 以浮层形式覆盖在任意页面之上，无需跳转

### 合规与降级

- **内容审核** —— `ModerationService` 在消息入库前过滤，AI 角色不得声称自己是真人
- **联网 / 模型降级** —— Firecrawl 或 DeepSeek 不可用时，自动封装 mock fallback

---

## 技术栈

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5.34 | 核心框架（Composition API + `<script setup>`） |
| TypeScript | 6.0.3 | 类型系统（严格模式） |
| Vite | 8.0.11 | 构建工具 |
| Pinia | 3.0.4 | 状态管理 |
| Vue Router | 5.0.6 | 前端路由 |
| Socket.IO Client | 4.8.3 | WebSocket 客户端（Socket.IO 协议） |
| Tailwind CSS | 4.3.0 | 样式系统 |
| @tailwindcss/vite | 4.2.x | Tailwind Vite 集成 |
| Lucide Vue Next | 1.0.0 | 图标库 |
| Axios | 1.16.0 | HTTP 客户端 |
| @vueuse/core | 14.3.0 | 组合式工具库 |
| @vue/test-utils + Vitest | 2.4 / 4.1 | 单元测试 |
| Playwright | 1.59 | E2E 测试 |

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.3 | 应用框架 |
| Java | 21 LTS | 运行时 |
| MySQL | 8.x | 主数据库 |
| JPA / Hibernate | — | ORM，数据库迁移通过 `db/migration` 下的 SQL 脚本 |
| LangChain4j | 1.0.0-beta2 | AI 编排框架 |
| langchain4j-open-ai | 1.0.0-beta2 | OpenAI 兼容接口（接入 DeepSeek） |
| DeepSeek API | — | LLM（OpenAI 兼容格式） |
| Spring WebSocket | — | WebSocket 服务端（Socket.IO 协议适配） |
| JJWT | 0.12.5 | JWT 签发与校验 |
| Bucket4j | 8.14.0 | API 限流 |
| Firecrawl | — | 联网检索 |
| Springdoc OpenAPI | 2.8.6 | Swagger API 文档 |
| Lombok | — | 简化样板代码 |
| dotenv-java | 3.0.2 | 从 `.env` 加载环境变量 |
| httpclient5 | — | 底层 HTTP 客户端 |

---

## 项目结构

```
Idea-Party/
├── client/                                  # Vue 3 前端应用
│   └── src/
│       ├── api/                             # REST API 封装
│       │   ├── auth.ts                      # 认证
│       │   ├── rooms.ts                     # 聊天室
│       │   ├── characters.ts                # 角色
│       │   ├── messages.ts                  # 消息
│       │   ├── settings.ts                  # 设置 / API Key
│       │   └── user.ts                      # 用户资料
│       ├── components/
│       │   ├── chat/                        # 聊天 UI（IM 风格）
│       │   │   ├── ChatRoomPanel.vue        # 聊天室主面板
│       │   │   ├── MessageList.vue          # 消息列表（自动滚动）
│       │   │   ├── MessageBubble.vue        # 消息气泡
│       │   │   ├── ChatInput.vue            # 输入框（IME 兼容）
│       │   │   └── ThinkingIndicator.vue    # AI 思考指示器
│       │   ├── character/                   # 角色管理
│       │   │   ├── CharacterCard.vue
│       │   │   ├── CharacterSidebar.vue
│       │   │   ├── CharacterAddPanel.vue
│       │   │   ├── CharacterDetailModal.vue
│       │   │   └── CreateCharacterModal.vue
│       │   ├── room/                        # 房间管理
│       │   │   ├── CreateRoomModal.vue
│       │   │   ├── RoomHeader.vue
│       │   │   └── RoomSettingsModal.vue
│       │   ├── settings/
│       │   │   └── SettingsModal.vue        # 设置浮层（任意页可打开）
│       │   └── ui/                          # 通用 UI 组件
│       ├── composables/
│       │   └── useSocket.ts                 # WebSocket 组合式函数
│       ├── layouts/
│       │   └── LegalLayout.vue              # 条款 / 隐私页布局
│       ├── router/index.ts                  # Vue Router 配置
│       ├── services/api.ts                  # Axios 实例与拦截器
│       ├── stores/                          # Pinia 状态
│       │   ├── auth.ts
│       │   ├── room.ts
│       │   ├── message.ts
│       │   ├── character.ts
│       │   ├── settings.ts
│       │   ├── theme.ts
│       │   └── scenario.ts                  # 场景 Tab
│       ├── types/index.ts                   # TypeScript 类型定义
│       └── views/                           # 页面视图
│           ├── LoginView.vue
│           ├── RegisterView.vue
│           ├── RoomListView.vue             # 房间 / 场景 / 角色库三 Tab
│           ├── ChatView.vue
│           ├── CharacterCreateView.vue
│           ├── CharacterLibraryView.vue
│           ├── SettingsView.vue
│           ├── TermsView.vue
│           └── PrivacyView.vue
│
└── server/                                  # Spring Boot 后端
    └── src/main/
        ├── java/com/ideaparty/
        │   ├── IdeaPartyApplication.java
        │   ├── controller/                  # REST 控制器
        │   │   ├── AuthController.java            # /api/auth
        │   │   ├── UserController.java            # /api/user
        │   │   ├── RoomController.java            # /api/rooms
        │   │   ├── RoomMemberController.java      # /api/rooms/{id}/members
        │   │   ├── MessageController.java         # /api/rooms/{id}/messages
        │   │   ├── CharacterController.java       # /api/characters
        │   │   ├── SettingsController.java        # /api/settings
        │   │   ├── FileUploadController.java      # /api/upload
        │   │   └── HealthController.java          # /api/health
        │   ├── service/                     # 业务逻辑
        │   │   ├── AIService.java                 # AI 调用统一抽象
        │   │   ├── MockAiService.java             # Mock 降级
        │   │   ├── ChatService.java               # 消息持久化与编排
        │   │   ├── ModeratorAgent.java            # 主持人 Agent（核心编排器）
        │   │   ├── CharacterPromptBuilder.java    # 角色 prompt 拼装
        │   │   ├── CharacterService.java
        │   │   ├── RoomService.java
        │   │   ├── RoomMemberService.java         # 房间成员与邀请
        │   │   ├── MessageService.java
        │   │   ├── SettingsService.java           # API Key 管理
        │   │   ├── FirecrawlService.java          # 联网检索
        │   │   ├── ModerationService.java         # 内容审核
        │   │   ├── FileStorageService.java        # 头像上传
        │   │   └── AuthService.java
        │   ├── socket/ChatSocketHandler.java # WebSocket 消息路由
        │   ├── websocket/                   # WebSocket 框架适配（备用）
        │   ├── entity/                      # JPA 实体
        │   │   ├── User.java
        │   │   ├── Room.java
        │   │   ├── Message.java
        │   │   ├── Character.java
        │   │   └── RoomMember.java
        │   ├── repository/                  # Spring Data JPA 仓库
        │   ├── dto/                         # 请求 / 响应 DTO
        │   ├── filter/                      # 安全 / 限流过滤器
        │   ├── exception/                   # 全局异常处理
        │   ├── util/                        # 工具类
        │   └── config/                      # Spring 配置
        └── resources/
            ├── application.properties       # Spring 主配置
            ├── application.yml              # YAML 配置
            ├── data.sql                     # 初始数据
            ├── db/migration/                # SQL 迁移脚本
            └── prompts/                     # LLM prompt 模板
                ├── character-prompt-generator.txt
                ├── moderator-prompt.txt
                └── moderator-joint-prompt.txt
```

---

## 路由与 API 参考

### 前端路由

| 路径 | 名称 | 是否需要登录 | 说明 |
|------|------|--------------|------|
| `/` | — | — | 已登录跳转 `/rooms`，未登录跳转 `/login` |
| `/login` | login | 否 | 登录 |
| `/register` | register | 否 | 注册 |
| `/rooms` | rooms | 是 | 房间列表（默认 Tab） |
| `/scenarios` | scenarios | 是 | 场景 Tab |
| `/characters` | characters | 是 | 角色库 Tab |
| `/characters/create` | character-create | 是 | 新建角色 |
| `/characters/edit/:id` | character-edit | 是 | 编辑角色 |
| `/chat/:roomId` | chat | 是 | 进入聊天室 |
| `/settings` | settings | 是 | 设置（也可由 `SettingsModal` 浮层打开） |
| `/terms` | terms | 否 | 服务条款 |
| `/privacy` | privacy | 否 | 隐私政策 |

### REST API 概览

> 所有需要鉴权的接口请在 Header 中携带 `Authorization: Bearer <jwt>`。

#### 健康检查

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/health` | 服务健康状态 |

#### 认证

| 方法 | 端点 | 描述 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录，返回 JWT |
| PUT | `/api/auth/profile` | 更新个人资料 |
| PATCH | `/api/auth/change-password` | 修改密码 |

#### 用户

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/user/profile` | 获取当前用户资料 |
| POST | `/api/user/avatar` | 上传用户头像（multipart） |
| PUT | `/api/user/preferences` | 更新主题等偏好 |

#### 聊天室

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/rooms` | 获取当前用户的所有房间 |
| POST | `/api/rooms` | 创建房间 |
| GET | `/api/rooms/{id}` | 获取房间详情 |
| DELETE | `/api/rooms/{id}` | 删除房间（仅所有者） |
| POST | `/api/rooms/{id}/characters/{characterId}` | 添加角色到房间 |
| PATCH | `/api/rooms/{id}/mode` | 切换 chatMode / maxDiscussionRounds |
| PATCH | `/api/rooms/{id}/enter` | 记录用户进入房间（用于排序 / 统计） |

#### 房间成员

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/rooms/{roomId}/members` | 列出房间成员（需是成员） |
| POST | `/api/rooms/{roomId}/members/invite` | 按关键字邀请用户加入房间 |

#### 消息

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/rooms/{roomId}/messages` | 获取房间全部消息历史 |
| GET | `/api/rooms/{roomId}/messages/paginated?page=&size=` | 分页获取消息 |
| POST | `/api/rooms/{roomId}/messages` | 主动发送消息（HTTP 形式，聊天主流程走 WebSocket） |

#### 角色

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/characters` | 获取所有可见角色 |
| GET | `/api/characters/presets` | 获取系统预设角色 |
| GET | `/api/characters/recommended` | 获取推荐角色 |
| GET | `/api/characters/{id}` | 获取单个角色 |
| POST | `/api/characters` | 创建角色 |
| PUT | `/api/characters/{id}` | 更新角色 |
| DELETE | `/api/characters/{id}` | 删除角色（仅所有者） |
| POST | `/api/characters/generate-prompt` | 调用 LLM 自动生成角色 prompt |

#### 设置（API Key）

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/settings/api-key` | 获取当前 API Key（脱敏） |
| POST | `/api/settings/api-key` | 设置 / 更新 API Key |
| DELETE | `/api/settings/api-key` | 清除 API Key |

#### 文件上传

| 方法 | 端点 | 描述 |
|------|------|------|
| POST | `/api/upload/avatar` | 上传头像（multipart，字段名 `avatar`） |
| GET | `/api/upload/avatars/{filename}` | 访问已上传的头像 |

### WebSocket（Socket.IO 协议）

- 端点：`/ws`（开发环境走 Vite 代理，生产环境走同源或 `VITE_WS_URL` 指定）
- 协议前缀：客户端发送 `42["event_name", data]`，服务端推送同格式
- 心跳：客户端发 `2`（ping），服务端回 `3`（pong）

#### 客户端 → 服务端

| 事件 | Payload | 说明 |
|------|---------|------|
| `join room` | `{ roomId, token? }` | 加入房间；token 用于在该会话上建立 SecurityContext |
| `leave room` | `{ roomId }` | 离开房间 |
| `chat message` | `{ roomId, content, senderType?, characterId? }` | 发送消息（核心入口） |
| `trigger-ai` | `{ roomId }` | 手动触发 AI 响应 |
| `pause-discussion` | `{ roomId }` | 暂停讨论 |
| `resume-discussion` | `{ roomId }` | 继续讨论 |
| `stop-discussion` | `{ roomId }` | 停止讨论 |

#### 服务端 → 客户端

| 事件 | Payload | 说明 |
|------|---------|------|
| `room-joined` | `{ roomId }` | 加入成功确认 |
| `chat message` | `{ id, content, senderType, characterId, characterName, userId, avatarUrl, roomId }` | 完整消息（用于消息列表追加） |
| `message stream` / `chat chunk` | `{ content, characterId, characterName, avatarUrl, roomId, streaming: true }` | 流式片段，逐字推送 |
| `character thinking` | `{ characterId }` | 角色开始思考 |
| `discussion-state` | `{ phase, selectedCharacters, message }` | 主持人状态机阶段变化 |
| `moderator-message` | `{ content, type }` | 主持人旁白（SELECT / INVITE） |
| `discussion-paused` / `discussion-resumed` | — | 讨论状态同步 |
| `error` | `{ message }` | 错误通知 |

---

## 数据库模型

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│    User     │     │    Room     │     │  Character   │
├─────────────┤     ├─────────────┤     ├──────────────┤
│ id (PK)     │────<│ id (PK)     │     │ id (PK)      │
│ email       │     │ name        │     │ name         │
│ username    │     │ topic       │     │ description  │
│ displayName │     │ ownerId(FK) │>────│ avatarUrl    │
│ password    │     │ chatMode    │     │ prompt       │
│ avatarUrl   │     │ maxRounds   │     │ expertise    │
│ apiKey      │     │ characters  │<────│ era          │
│ themeMode   │     │ members     │     │ speakingStyle│
│ lastUsername│     └─────────────┘     │ persona      │
│ ChangeAt    │            │            │ ownerId (FK) │
└─────────────┘            │            │ isPreset     │
       │                   │            └──────────────┘
       │                   ▼
       │            ┌─────────────┐
       │            │ RoomMember  │
       │            ├─────────────┤
       │            │ id (PK)     │
       └───────────>│ userId (FK) │
                    │ roomId (FK) │
                    │ role        │   (OWNER / MEMBER)
                    │ status      │   (ACTIVE / PENDING ...)
                    │ joinedAt    │
                    └─────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │   Message   │
                    ├─────────────┤
                    │ id (PK)     │
                    │ roomId (FK) │>───┐
                    │ senderType  │    │ (USER / CHARACTER)
                    │ characterId │>───┘
                    │ userId (FK) │>───┘
                    │ content     │
                    │ createdAt   │
                    └─────────────┘
```

- `Room.chatMode`：`dialogue`（智能选 1~N 个角色回复）｜`discussion`（主持人多轮编排）
- `Room.maxDiscussionRounds`：讨论模式下的最大轮数
- 迁移脚本位于 `server/src/main/resources/db/migration/`

---

## 快速开始

### 环境要求

- Node.js 20+
- Java 21
- MySQL 8.x
- Maven 3.9+

### 1. 克隆项目

```bash
git clone git@github.com:hello28256/Idea-Party.git
cd Idea-Party
```

### 2. 启动 MySQL（Docker 示例）

```bash
docker run -d \
  --name idea-party-mysql \
  -e MYSQL_ROOT_PASSWORD=your_password \
  -e MYSQL_DATABASE=ideaparty \
  -p 3306:3306 \
  mysql:8
```

或在 MySQL 客户端中执行：

```sql
CREATE DATABASE IF NOT EXISTS ideaparty CHARACTER SET utf8mb4;
```

### 3. 配置环境变量

在 `server/.env`（由 `dotenv-java` 加载）写入：

```env
DB_URL=jdbc:mysql://localhost:3306/ideaparty?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
DB_USERNAME=root
DB_PASSWORD=your_db_password
JWT_SECRET=your_jwt_secret_min_32_chars
DEEPSEEK_API_KEY=your_deepseek_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com
FIRECRAWL_API_KEY=your_firecrawl_api_key   # 可选，缺省时走 mock
```

可选：在 `client/.env` 中设置 `VITE_WS_URL` 指定 WebSocket 域名（不填则走同源）。

### 4. 启动后端

```bash
cd server
./mvnw spring-boot:run
```

> 后端默认监听 `8080`；启动后可访问 `http://localhost:8080/swagger-ui.html` 查看接口文档。

### 5. 启动前端

```bash
cd client
npm install
npm run dev
```

访问 `http://localhost:5173`，进入 **设置**（浮层或独立页均可）配置 API Key 后即可使用。

### 6. 运行测试

```bash
# 前端单元测试
cd client
npm run test

# 后端测试
cd server
./mvnw test
```

---

## 使用指南

### 创建 AI 角色

1. 进入 **角色库 Tab**，点击「+」或「创建角色」
2. 填写：名称、描述、人设、专业领域、年代、说话风格
3. 可选：点击「AI 生成」自动完善 prompt；可选「联网检索」补充公开信息
4. 保存后即可在聊天室中添加该角色

### 发起讨论

1. 创建房间，添加 1~N 个角色
2. 在 **房间设置** 中切换为「讨论模式」
3. 点击「开始讨论」，主持人 Agent 会智能编排发言顺序
4. 讨论过程中可随时插话，AI 会重新组织后续流程
5. 用「暂停 / 继续 / 停止」控制讨论进程

### 对话模式

1. 创建房间并添加角色，模式保持为「对话模式」
2. 直接发送消息，系统会智能选择最合适的角色响应
3. 支持 `@角色名` 强制指定某个角色回应
4. 短句 / 上下文性消息会被自动路由到上一次发言角色，保持对话连续性

### 场景 Tab

顶栏的「场景」入口展示预置 / 推荐的对话场景（多角色已配好的房间模板），点开即可进入对应的聊天室。

---

## 配置与运维

### API Key 流转

```
浏览器
  └─ JWT 鉴权 ──▶ Spring 后端
                      └─ SettingsService / User.apiKey
                            └─ AIService.createChatModelWithApiKey
                                  └─ DeepSeek (OpenAI 兼容)
```

**API Key 永远只存在于后端**，前端无法通过任何方式读取明文。

### 限流

`Bucket4j` 8.14.0 提供令牌桶限流，默认在 `filter/` 下的过滤器中配置，可针对敏感接口（登录、注册、消息发送等）调优。

### 降级策略

| 模块 | 降级方式 |
|------|----------|
| DeepSeek | `MockAiService` 返回固定模板响应 |
| Firecrawl | 跳过联网检索，仅使用角色基础字段拼 prompt |
| 头像上传 | 本地文件系统 `uploads/avatars/`（生产可替换为对象存储） |

### 部署

仓库根目录提供 `deploy.py` 与 `docker-compose.yml`，可一键构建并启动前后端 + MySQL 容器，详见 `README-DOCKER.md`。

---

## 核心设计要点

### 前后端分离

- 前端纯 SPA，JWT 鉴权，所有后端调用统一经过 `services/api.ts` 的 Axios 拦截器
- WebSocket 与 REST 共用后端 8080 端口，路径 `/ws`
- API Key 后端统一管理，避免泄露

### AI 编排：联合 Prompt + 单轮多角色

`ModeratorAgent.runJointSingleRound` 的核心做法：

1. 把「角色名册 + 上一轮发言 + 当前用户消息」一次性塞进 prompt
2. 让 LLM 用统一格式输出多角色台词：
   ```
   [角色A]: 台词
   <<<END>>>
   [角色B]: 台词
   <<<END>>>
   ```
3. `JointStreamParser` 增量解析流式响应，分别回调「流式片段」与「完整块」
4. 完整块持久化进 MySQL，广播到房间

这样**单轮只调用一次 LLM**，比 N 次串行调用更便宜，并且模型天然拥有跨角色上下文。

### 状态机

`DiscussionPhase`（`IDLE / MODERATING / SPEAKING / WAITING_FOR_USER`）驱动 UI 状态变化与主持人动作；用户插话会立即清空 pendingQueue、重新组织讨论。

### 线程连续性

短句 / 上下文性消息（"继续"、"为什么"、"有道理" 等）会被识别为「线程延续」，自动路由到 `activeThreadOwner`，避免每条新消息都重新分派角色造成上下文割裂。

### SecurityContext 透传

`ModeratorAgent` 内部用 `SecurityContextAwareThread` 包装执行器，把父线程的 Spring SecurityContext 显式继承到子线程，避免异步任务里访问受保护的 JPA 实体时丢失权限。

---

## 许可证

MIT License —— 详见根目录 `LICENSE`。

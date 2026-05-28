# Idea Party

> AI 多角色聊天室平台 — 与多个 AI 角色同时对话，享受圆桌讨论的乐趣。

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Java](https://img.shields.io/badge/Java-21_LTS-green.svg)
![Vue](https://img.shields.io/badge/Vue-3.5-green.svg)

---

## 功能特性

### 角色系统

- **创建 AI 角色** — 自定义角色名称、描述、人设 prompt、专业领域、年代、说话风格
- **预设角色库** — 内置多个经典角色，快速体验
- **联网检索增强** — 自动从互联网检索角色公开信息，完善人设（Firecrawl）
- **AI 生成 prompt** — 一键生成角色人设 prompt
- **角色头像上传** — 支持自定义角色头像
- **角色管理** — 角色库管理、编辑、删除（仅所有者）

### 聊天功能

- **多角色群聊** — 同时与多个 AI 角色交流，类似圆桌会议
- **智能发言编排** — Moderator Agent 智能决定发言顺序
- **实时消息推送** — WebSocket + 流式响应，毫秒级更新
- **思考指示器** — AI 生成响应时显示思考状态
- **消息分组** — 连续消息自动合并，IM 风格展示
- **聊天历史** — 持久化消息记录，随时回溯
- **用户插话** — 讨论过程中可随时插话，AI 智能响应

### 讨论模式

- **Dialogue 模式** — 智能选择单一角色响应（基于 @提及、上下文）
- **Discussion 模式** — 多角色有序讨论，主持人 Agent 全局编排
- **暂停/继续/停止** — 灵活控制讨论进程
- **轮次限制** — 可配置最多讨论轮数

### 用户系统

- **注册/登录** — JWT 认证
- **个人资料** — 修改用户名、头像
- **主题切换** — 支持浅色/深色/跟随系统
- **API Key 配置** — 用户可自带 DeepSeek API Key

---

## 技术栈

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.5.x | 核心框架（Composition API + `<script setup>`） |
| TypeScript | 6.0.x | 类型系统 |
| Vite | 8.x | 构建工具 |
| Pinia | 3.x | 状态管理 |
| Vue Router | 5.x | 前端路由 |
| Socket.IO Client | 4.8.x | WebSocket 客户端 |
| Tailwind CSS | 4.x | 样式系统 |
| Lucide Vue | 1.x | 图标库 |
| Axios | 1.x | HTTP 客户端 |
| @vueuse/core | 14.x | 组合式工具库 |

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.x | 核心框架 |
| Java | 21 LTS | 运行时 |
| MySQL | 8.x | 主数据库 |
| JPA/Hibernate | — | ORM |
| LangChain4j | 1.0.0-beta2 | AI 编排框架 |
| DeepSeek API | — | AI 模型（OpenAI 兼容格式） |
| JWT (jjwt) | 0.12.x | 认证 |
| Bucket4j | 8.x | API 限流 |
| Firecrawl | — | 联网检索 |
| Springdoc OpenAPI | 2.8.x | API 文档 |

---

## 项目结构

```
Idea-Party/
├── client/                         # Vue 3 前端应用
│   └── src/
│       ├── api/                   # API 调用封装
│       │   ├── auth.ts            # 认证 API
│       │   ├── rooms.ts           # 聊天室 API
│       │   ├── characters.ts      # 角色 API
│       │   └── messages.ts        # 消息 API
│       ├── components/
│       │   ├── chat/              # 聊天 UI 组件（IM 风格）
│       │   │   ├── ChatRoomPanel.vue     # 聊天室面板
│       │   │   ├── MessageList.vue       # 消息列表
│       │   │   ├── MessageBubble.vue     # 消息气泡（微信风格）
│       │   │   ├── ChatInput.vue        # 输入框
│       │   │   └── ThinkingIndicator.vue # AI 思考指示器
│       │   ├── character/          # 角色管理组件
│       │   ├── room/              # 聊天室组件
│       │   └── ui/               # 通用 UI 组件
│       ├── composables/
│       │   └── useSocket.ts       # WebSocket 组合式函数
│       ├── stores/                # Pinia 状态管理
│       │   ├── auth.ts           # 认证状态
│       │   ├── room.ts           # 聊天室状态
│       │   ├── message.ts        # 消息状态
│       │   ├── character.ts       # 角色状态
│       │   └── theme.ts          # 主题状态
│       ├── types/index.ts         # TypeScript 类型定义
│       └── views/                 # 页面视图
│
└── server/                         # Spring Boot 后端
    └── src/main/java/com/ideaparty/
        ├── controller/             # REST API 控制器
        │   ├── AuthController.java      # 认证
        │   ├── RoomController.java      # 聊天室
        │   ├── MessageController.java   # 消息
        │   ├── CharacterController.java # 角色
        │   ├── UserController.java     # 用户
        │   └── SettingsController.java # 设置
        ├── entity/                 # 数据库实体
        │   ├── User.java
        │   ├── Room.java
        │   ├── Message.java
        │   ├── Character.java
        │   └── RoomMember.java
        ├── service/               # 业务逻辑
        │   ├── AIService.java           # AI 对话服务
        │   ├── ChatService.java        # 聊天编排服务
        │   ├── ModeratorAgent.java     # 主持人 Agent（讨论编排）
        │   ├── FirecrawlService.java   # 联网检索服务
        │   └── ModerationService.java  # 内容审核
        ├── socket/                 # WebSocket 处理
        │   └── ChatSocketHandler.java  # Socket.IO 消息处理
        ├── dto/                   # 数据传输对象
        ├── repository/            # 数据访问层
        └── config/                # 配置类
```

---

## API 参考

### 认证 API

| 方法 | 端点 | 描述 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录 |
| PUT | `/api/auth/profile` | 更新个人资料 |
| PATCH | `/api/auth/change-password` | 修改密码 |

### 聊天室 API

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/rooms` | 获取用户的所有聊天室 |
| POST | `/api/rooms` | 创建聊天室 |
| GET | `/api/rooms/{id}` | 获取聊天室详情 |
| DELETE | `/api/rooms/{id}` | 删除聊天室 |
| POST | `/api/rooms/{id}/characters/{characterId}` | 添加角色到聊天室 |
| PATCH | `/api/rooms/{id}/mode` | 更新聊天模式（dialogue/discussion） |
| GET | `/api/rooms/{roomId}/messages` | 获取消息历史 |

### 角色 API

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/characters` | 获取所有角色 |
| GET | `/api/characters/presets` | 获取预设角色 |
| GET | `/api/characters/recommended` | 获取推荐角色 |
| POST | `/api/characters` | 创建角色 |
| POST | `/api/characters/generate-prompt` | AI 生成角色 prompt |
| PUT | `/api/characters/{id}` | 更新角色 |
| DELETE | `/api/characters/{id}` | 删除角色 |

### 设置 API

| 方法 | 端点 | 描述 |
|------|------|------|
| GET | `/api/settings/api-key` | 获取 API Key |
| POST | `/api/settings/api-key` | 设置 API Key |
| DELETE | `/api/settings/api-key` | 清除 API Key |

### WebSocket 事件

**客户端 → 服务端：**

| 事件名 | 描述 |
|--------|------|
| `join room` | 加入聊天室 |
| `leave room` | 离开聊天室 |
| `chat message` | 发送消息 |
| `trigger-ai` | 触发 AI 响应 |
| `pause-discussion` | 暂停讨论 |
| `resume-discussion` | 继续讨论 |
| `stop-discussion` | 停止讨论 |

**服务端 → 客户端：**

| 事件名 | 描述 |
|--------|------|
| `room-joined` | 加入成功确认 |
| `chat message` | 新消息 |
| `chat chunk` / `message stream` | 流式消息片段 |
| `character thinking` | AI 思考中 |
| `discussion-state` | 讨论状态更新 |
| `moderator-message` | 主持人消息 |

---

## 数据库模型

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│    User     │     │    Room     │     │  Character  │
├─────────────┤     ├─────────────┤     ├─────────────┤
│ id (PK)     │────<│ id (PK)     │     │ id (PK)     │
│ email       │     │ name        │     │ name        │
│ username    │     │ topic       │     │ description  │
│ displayName │     │ ownerId (FK)│>────│ avatarUrl   │
│ password    │     │ chatMode    │     │ prompt       │
│ avatarUrl   │     │ characters  │<────│ expertise    │
│ apiKey      │     │ members     │     │ era          │
│ themeMode   │     └─────────────┘     │ speakingStyle│
└─────────────┘            │            │ persona      │
       │                   │            │ ownerId (FK) │
       │                   │            │ isPreset     │
       ▼                   ▼            └──────────────┘
┌─────────────┐     ┌─────────────┐
│ RoomMember  │     │   Message   │
├─────────────┤     ├─────────────┤
│ id (PK)     │     │ id (PK)     │
│ roomId (FK) │>───┐│ roomId (FK) │>───┐
│ userId (FK) │    ││ senderType  │     │
│ role        │    ││ characterId │>────┘
│ status      │    ││ userId (FK)│>───┘
│ joinedAt    │    ││ content    │
└─────────────┘    ││ createdAt  │
                   └─────────────┘
```

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

### 2. 配置数据库

启动 MySQL（Docker）:

```bash
docker run -d \
  --name idea-party-mysql \
  -e MYSQL_ROOT_PASSWORD=your_password \
  -e MYSQL_DATABASE=ideaparty \
  -p 3306:3306 \
  mysql:8
```

创建数据库:

```sql
CREATE DATABASE IF NOT EXISTS ideaparty CHARACTER SET utf8mb4;
```

修改 `server/.env`:

```env
DB_PASSWORD=your_db_password
JWT_SECRET=your_jwt_secret_min_32_chars
DEEPSEEK_API_KEY=your_deepseek_api_key
FIRECRAWL_API_KEY=your_firecrawl_api_key   # 可选
```

### 3. 启动后端

```bash
cd server
./mvnw spring-boot:run
```

### 4. 启动前端

```bash
cd client
npm install
npm run dev
```

访问 `http://localhost:5173`，然后在**设置页面**配置 API Key。

---

## 使用指南

### 创建 AI 角色

1. 点击侧边栏 **"+"** 或 **"创建角色"**
2. 填写角色信息：
   - **名称**: 角色名字（如"爱因斯坦"）
   - **描述**: 一句话介绍
   - **人设**: 详细的人格描述
   - **专业领域**: 角色的专长（如"物理学、数学"）
   - **年代**: 角色所在时代
   - **说话风格**: 如何表达（正式/幽默/学术等）
3. 可选：点击 **"AI 生成"** 自动完善 prompt
4. 保存后在聊天室添加该角色即可对话

### 发起讨论

1. 进入聊天室，选择多个角色
2. 切换到 **"讨论模式"**
3. 点击 **"开始讨论"** 按钮
4. Moderator Agent 会智能编排发言顺序
5. 讨论过程中可随时 **插话**，AI 会智能响应
6. 使用 **暂停/继续/停止** 控制讨论进程

### 对话模式

1. 进入聊天室，添加感兴趣的角色
2. 直接发送消息，系统会智能选择最合适的角色响应
3. 支持 **@提及** 指定特定角色

---

## 配置说明

### API Key 配置

首次使用需要配置以下 API Key：

| 服务 | 用途 | 获取地址 |
|------|------|---------|
| **DeepSeek** | AI 对话 | [platform.deepseek.com](https://platform.deepseek.com) |
| **Firecrawl** | 联网检索（可选） | [firecrawl.dev](https://firecrawl.dev) |

配置位置：**设置 → AI 配置**

> 注意：API Key 仅存储在后端，不会暴露给前端。

---

## 架构亮点

### 前后端分离

- 前端纯 SPA，Token 鉴权
- API Key 后端统一管理，避免泄露
- WebSocket 实时双向通信

### AI 编排

- LangChain4j 提供统一 AI 接口
- 支持 DeepSeek 等 OpenAI 兼容 API
- Moderator Agent 智能编排讨论流程
- 用户可自带 API Key，降低使用成本

### 消息处理

- WebSocket + 流式响应，实时显示 AI 输出
- 消息分组，连续消息自动合并
- 思考状态指示器，提升交互体验

---

**Made with ❤️ by Idea Party**

# Idea Party

> 在一个对话框里,和一群 AI 角色围坐圆桌——苏格拉底、孔子、乔布斯、爱因斯坦、马云……你想让谁上场,就拉谁入场。

[![Vue 3](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f)](https://spring.io/projects/spring-boot)
[![Java 21 LTS](https://img.shields.io/badge/Java-21%20LTS-ed8b00)](https://openjdk.org/projects/jdk/21)
[![DeepSeek](https://img.shields.io/badge/LLM-DeepSeek-0066ff)](https://platform.deepseek.com)
[![License](https://img.shields.io/badge/license-MIT-blue)](#许可证)

Idea Party 是一个 **AI 多角色聊天室平台**:用户在一个房间里放进多个 AI 角色,系统由 **Moderator Agent** 智能编排发言顺序,角色以流式消息形式依次回应——既有群聊的热闹,也有圆桌的秩序。

适合:技术面试模拟、产品脑暴、语言学习陪练、稿件审阅、剧本杀式的角色推演……任何需要「多元视角交锋」的场景。

<!-- 建议尺寸 1280x720 或 16:9。放在 docs/screenshots/hero.png -->
<!-- ![Hero](docs/screenshots/hero.png) -->
<p align="center"><em>📸 截图占位 — 主聊天界面:多角色头像、流式回复、Moderator 编排</em></p>

---

## 目录

- [Idea Party 是什么](#idea-party-是什么)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [架构概览](#架构概览)
- [预设内容](#预设内容)
- [文档导航](#文档导航)
- [常见问题](#常见问题)
- [贡献](#贡献)
- [许可证](#许可证)

---

## Idea Party 是什么

把一个房间想成一个圆桌:你坐主位,对面坐着若干 AI 角色。用户说一句,Moderator Agent 决定这一轮谁先接话,谁后反驳,谁补刀——而不是机械地轮流发言。

**它解决了什么**

- 写 Prompt 太累 —— 写好角色名就行,系统用 Firecrawl 联网检索公开资料,自动生成 persona prompt
- 多角色机械抢答 —— Moderator Agent 看上下文决定发言顺序,讨论更像真人圆桌
- API Key 泄露 —— DeepSeek / Firecrawl Key 只走后端,前端永远拿不到
- 角色扮真人 —— 所有 prompt 模板强制附加「AI 模拟角色,非本人」声明

<!-- ![多角色圆桌示意](docs/screenshots/room-discussion.png) -->
<p align="center"><em>📸 截图占位 — 圆桌讨论模式:多角色并发输出 + Moderator 编排</em></p>

---

## 核心特性

| | |
|---|---|
| 🎭 **角色系统** | 内置 585 个预设角色(苏格拉底 / 爱因斯坦 / 孔子 / 乔布斯……),按 12 个分类筛选;支持自定义角色和上传头像 |
| 🪟 **场景模板** | 开箱即用的「场景」(面试模拟 / 论文答辩 / 客户谈判 / 健身咨询 / 心理倾听 等);登录用户也能创建自己的私有场景 |
| 💬 **两种房间模式** | `dialogue`(@ 提及 + 智能选人)/ `discussion`(Moderator 多轮编排,支持暂停 / 恢复 / 停止) |
| ⚡ **流式聊天** | 字符级推送;多角色并行输出用一次 LLM 调用 + 行内解析器,比 N 次串行调用省 N-1 次握手 |
| 🛡 **合规与安全** | 角色声明强制注入 + Bucket4j 按 IP 限流 + JWT 鉴权 + Spring Security + 内容审核 |
| 👍👎 **反馈系统** | Like / Dislike + 5 类差评(答非所问 / 事实不准 / 不安全 / 风格差 / 其他);`/admin/feedbacks` 看所有反馈、流式状态、响应延迟分桶 |
| 🎨 **主题切换** | 浅色 / 深色 / 跟随系统,后端持久化 |
| 📎 **简历解析 + JD OCR** | 上传 .docx / .pdf / .txt 自动抽文本;JD 截图拖拽 / 粘贴识别 |

<!-- ![](docs/screenshots/character-library.png) -->
<p align="center"><em>📸 截图占位 — 角色库:分类筛选 + 头像 + persona 摘要</em></p>

---

## 技术栈

### 前端 (`client/`)

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue 3 | 3.5 | 组合式 API + `<script setup>` |
| TypeScript | 6.x | 严格模式 |
| Vite | 8.x | 构建 / HMR,冷启动 < 100ms |
| Pinia | 3.x | 状态管理(7 个 store) |
| Vue Router | 5.x | SPA 路由 |
| Tailwind CSS | 4.x | 原子化样式 |
| socket.io-client | 4.8 | 实时通信 |
| Vitest + Playwright | 4.x / 1.x | 单元 + E2E |

### 后端 (`server/`)

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.5 | 应用框架 |
| Java | 21 LTS | 运行时 |
| LangChain4j | 1.0-beta | OpenAI 兼容 → DeepSeek 编排 |
| Spring Data JPA | — | ORM,MySQL 自动建表 |
| MySQL | 8.x | 主数据库 |
| Spring WebSocket | — | `/ws` 端点,自实现 Socket.IO framing |
| JJWT | 0.12 | JWT 鉴权(HS512) |
| Bucket4j | 8.x | 速率限制 |
| Firecrawl | v1/v2 REST | 角色联网检索(无 Key 走 mock fallback) |
| Apache Tika | 2.9 | .docx / .pdf 解析 |
| Springdoc OpenAPI | 2.8 | `/swagger-ui.html` |

### 存储 / 部署

- **图片**:生产走腾讯云 COS(`ap-guangzhou`,前端浏览器拿 STS 临时凭证直传)
- **部署**:docker-compose(本地) + `deploy.py`(远程 rsync + 远程 docker compose)

完整依赖以 `client/package.json` / `server/pom.xml` 为准。

---

## 快速开始

### 最快路径(Docker,推荐)

```bash
# 1. 克隆
git clone https://github.com/hello28256/Idea-Party.git
cd Idea-Party

# 2. 准备环境变量
cp .env.production.example .env.production
# 至少填好 MYSQL_ROOT_PASSWORD / JWT_SECRET / DEEPSEEK_API_KEY / PUBLIC_BASE_URL
# FIRECRAWL_API_KEY 可选 —— 留空则角色联网检索走 mock fallback

# 3. 一键启动
docker compose up -d

# 4. 验证
curl http://localhost/api/health      # → {"status":"UP",...}
# 浏览器打开 http://localhost
```

详细参数、远程部署、HTTPS、Tencent Cloud CVM 等见 [`README-DOCKER.md`](./README-DOCKER.md) 和 [`docs/deploy-tencent-cloud.md`](./docs/deploy-tencent-cloud.md)。

### 本地源码开发

```bash
# 后端
cd server && mvn spring-boot:run
# 启动后访问 http://localhost:8080/api/health

# 前端(新终端)
cd client && npm install && npm run dev
# 默认 http://localhost:5173

# 一键拉起 MySQL 容器 + 后端 + 前端(后台模式)
./scripts/dev.sh
```

### 首次登录

`DataLoader` 首次启动按 `.env` 中的 `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` 幂等创建管理员,默认 `admin123` / `admin123`。**生产环境务必覆盖**。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│  Browser (Vue 3 SPA + socket.io-client)                      │
└────────────────────────┬─────────────────────────────────────┘
                         │ /api  /ws  /uploads
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Nginx (client 容器) — 反代 + 静态资源缓存                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot (server 容器)                                   │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  ChatSocketHandler (/ws, Socket.IO framing)           │  │
│  │   ├─ AuthService (JWT 校验 → 绑定 userId)             │  │
│  │   ├─ ModerationService (内容审核)                     │  │
│  │   └─ ModeratorAgent                                    │  │
│  │       ├─ AIService (LangChain4j → DeepSeek)           │  │
│  │       ├─ CharacterPromptBuilder (拼 system prompt)    │  │
│  │       └─ MessageService (落库 + 推流)                 │  │
│  └────────────────────────────────────────────────────────┘  │
└────────────┬─────────────────────────────┬───────────────────┘
             │                             │
             ▼                             ▼
       ┌──────────┐                  ┌──────────────┐
       │  MySQL   │                  │ 腾讯云 COS   │
       │ (容器内) │                  │ (图片桶)     │
       └──────────┘                  └──────────────┘
```

**讨论编排(`ModeratorAgent`)**:
- 自定义线程池必须继承 Spring SecurityContext,子线程才拿得到认证
- 房间级 future / 进度用 `ConcurrentHashMap` 包裹
- 轮次上限 `MAX_ROUNDS = 3`,轮间延迟 `ROUND_DELAY_MS = 1500`,让前端先把上一轮渲染完
- 用户中途插入用房间级互斥锁串行化,避免并发触发并行讨论循环

**联合 Prompt(多角色一次 LLM 调用)**:
`moderator-joint-prompt.txt` 把多个角色的 system prompt 拼成一次请求,流式返回后用行内解析器按角色名切分。比「为每个角色各发一次 LLM 调用」省 N-1 次握手。

更多架构细节见 [`docs/message-flow.md`](./docs/message-flow.md)。

---

## 预设内容

### 预设角色

`server/src/main/resources/presets.json` 维护 585 个预设角色,启动时由 `PresetCharacterCache` 加载到内存。

按分类(`CharacterCategory` 枚举)调用:

```bash
GET /api/characters/recommended?category=SCIENTIST
```

| 分类 | | 分类 | |
|---|---|---|---|
| 🔬 科学家 | 🏛️ 历史人物 | 💭 思想家 | 🎨 动画 |
| 🚀 企业家 | ⚔️ 军事家 | 🖼️ 艺术家 | 🎭 虚构人物 |
| 🌟 明星 | 🏆 运动员 | 📖 文学家 | 🏛️ 政治人物 |

改 `presets.json` 后,生产环境需要 `docker compose build server --no-cache`(否则命中 Maven 缓存,jar 不更新)。

### 热门房间

`hotRooms.json` 维护热门房间推荐(用于首页 / 侧边栏「热门」入口)。

### 场景模板

内置多套开箱即用模板(面试模拟、论文答辩、客户谈判、健身咨询、心理倾听 等),会先问用户补充输入再创建角色。登录用户也能用 `POST /api/user-scenarios` 创建自己的私有场景。

---

## 文档导航

| 文档 | 用途 |
|------|------|
| [`README-DOCKER.md`](./README-DOCKER.md) | Docker / Compose 部署细节 |
| [`docs/deploy-tencent-cloud.md`](./docs/deploy-tencent-cloud.md) | 腾讯云 CVM 部署 + HTTPS + 安全组 |
| [`docs/message-flow.md`](./docs/message-flow.md) | 流式聊天的消息生命周期与事件回放 |
| [`doc/test.md`](./doc/test.md) | 测试策略与脚本 |
| [`CLAUDE.md`](./CLAUDE.md) | 项目硬约束、合规条款、Convention |
| `http://localhost:8080/swagger-ui.html` | OpenAPI 文档(启动后端后访问) |

---

## 常见问题

<details>
<summary><b>改了 <code>presets.json</code> 但角色没更新?</b></summary>

`docker compose build server` 命中 Maven 缓存,jar md5 不变。强制重建:

```bash
./scripts/rebuild-server.sh
# 或手动:docker compose build server --no-cache
```
</details>

<details>
<summary><b>部署后前端页面看不到新改动?</b></summary>

`client` 容器的 `dist/` 是 build 时烤进镜像的。改前端源码后必须:

```bash
docker compose build client
docker compose up -d client
```

本地 dev 走 Vite HMR 不受影响。
</details>

<details>
<summary><b>无 <code>FIRECRAWL_API_KEY</code> 也能跑吗?</b></summary>

能。Firecrawl 缺失时角色联网检索走 mock fallback,生成的 prompt 会比较粗糙,但聊天主流程不受影响。建议生产配置 Key。
</details>

<details>
<summary><b>无 <code>DEEPSEEK_API_KEY</code> 呢?</b></summary>

启动不会报错,但 AIService 走 dummy 模式,**不会有任何流式输出**。必须配置。
</details>

<details>
<summary><b>后端改完要重启吗?</b></summary>

是的。修改 `server/` 下任何 Java 代码后,`mvn spring-boot:run` / 容器内服务不会自动热加载。这是项目硬约定。
</details>

<details>
<summary><b>CORS 报错?</b></summary>

`APP_CORS_ALLOWED_ORIGINS` 必须包含浏览器访问的 origin。`PUBLIC_BASE_URL` 是兜底,二者至少填一个。
</details>

---

## 贡献

1. Fork + Feature Branch
2. 提交信息建议:`type(scope): 描述`(e.g. `feat(character): 头像自动裁剪`)
3. PR 前跑:

```bash
# 前端
cd client && npm run typecheck && npm test

# 后端
cd server && mvn test
```

4. 涉及架构变更请先开 issue 讨论
5. 多人协作建议用 git worktree,各自独立分支开发

---

## 许可证

MIT

---

## 致谢

- [LangChain4j](https://docs.langchain4j.dev/) — Java 生态的 AI 编排
- [DeepSeek](https://platform.deepseek.com) — OpenAI 兼容的高性价比 LLM
- [Firecrawl](https://firecrawl.dev) — 一键把网页变成 LLM-ready Markdown
- [Vue.js](https://vuejs.org) / [Spring Boot](https://spring.io/projects/spring-boot) — 前后端框架

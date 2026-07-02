## Project

**AI 多角色聊天室平台**

一个 AI 多角色聊天室平台，用户可以在一个对话框中同时与多个 AI 角色对话，类似群聊或圆桌讨论。系统根据角色名称自动从互联网检索公开信息生成角色 prompt，并使用 Moderator Agent 智能编排发言顺序。

**Core Value:** 让用户轻松创建多元视角的 AI 对话场景，通过智能发言编排实现自然、有逻辑的群聊体验。

### Constraints

- **AI 调用安全**: API Key 仅在后端封装，不允许前端暴露
- **合规**: 不能让 AI 角色声称自己是真人
- **技术**: 前后端接口必须真实打通，不允许只写 mock
- **Fallback**: 暂时无法接入 Firecrawl 或 DeepSeek 时，需封装接口并提供 mock fallback
- **图片存储**: 生产环境图片统一走阿里云 OSS（华南1，桶 `idea-party-uploads`，公共读）。前端拿 STS 临时凭证浏览器直传 OSS，**不再写入 server/uploads 卷**。后端代码里所有 OSS 配置走 `${ALIYUN_*}` 环境变量，**禁止字面量**（含桶名 `idea-party-uploads` 带横杠，不要写成 `ideaparty-uploads`）

## Technology Stack

## Recommended Stack
### Frontend Core
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Vue 3 | 3.5.x | UI 框架 | 组合式 API + `<script setup>` 最佳实践，2025 年 Vue 2 EOL 全面转向 3 |
| Vite | 8.x | 构建工具 | Node 20.19+ / 22.12+ 支持，冷启动 <100ms，热更新快 |
| TypeScript | 5.x | 类型系统 | 严格类型检查减少运行时错误，AI 服务接口必须有类型 |
| Pinia | 3.x | 状态管理 | 2025 年放弃 Vue 2 支持，轻量、TypeScript-first、DevTools 集成好 |
| Vue Router | 5.x | 前端路由 | v4/v5 双轨并行，v5.0.6 是 ESM 构建最新稳定版 |
# 创建 Vue 3 + TypeScript 项目
# 安装依赖
### Frontend Supporting Libraries
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| socket.io-client | 4.8.x | WebSocket 客户端 | 与后端 Socket.IO 通信，流式消息推送 |
| axios | 1.x | HTTP 客户端 | REST API 调用（角色管理、聊天室 CRUD） |
| @vueuse/core | 12.x | 组合式工具库 | useWebSocket、useEventListener 等实用组合函数 |
### Backend Core
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Spring Boot | 3.5.x | 应用框架 | Java 21 LTS 支持，WebSocket/STOMP 原生集成，2025 年 Q2 最新稳定版 |
| Java | 21 LTS | 运行时 | LangChain4j 要求 Java 17+，项目需要 LTS 版本 |
| LangChain4j | 1.13.0-beta | AI 编排 | Java 生态最佳 AI 框架，支持 OpenAI 兼容接口（DeepSeek） |
| MySQL | 8.x | 主数据库 | 聊天室、角色、聊天记录存储 |
| Redis | 7.x | 缓存/会话 | Socket.IO 适配器、刷新 token 存储、可选 |
### Backend AI Integration
| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| langchain4j-open-ai-spring-boot-starter | 1.13.0-beta23 | OpenAI 兼容接口 | 配置 DeepSeek base URL + API key 即可使用，无需换 SDK |
| DeepSeek V4 | - | LLM | OpenAI SDK 兼容格式，国产模型成本可控 |
### Backend WebSocket
| Technology | Purpose | Why |
|------------|---------|-----|
| Spring WebSocket (STOMP) | 聊天消息实时推送 | Spring Boot 原生支持，无需额外依赖 |
| Socket.IO | 跨浏览器兼容 | 自动降级到 long-polling，v4.8.3 支持双向流 |
- Redis Adapter for Socket.IO（多实例消息广播）
- Spring WebFlux + WebSocket（响应式方案）
### Web Scraping
| Technology | Purpose | Why |
|------------|---------|-----|
| Firecrawl | 联网检索 | 结构化网页抓取，输出 LLM-ready markdown，无需解析 HTML |
| @mendable/firecrawl-js | Node.js SDK | 前端项目使用（未来可能需要 SSR 场景） |
| firecrawl-java-sdk | Java SDK | 后端项目使用（角色信息检索） |
## Alternatives Considered
| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| 前端路由 | Vue Router 5 | React Router | 项目技术栈锁定 Vue 3 |
| 后端实时通信 | Spring STOMP WebSocket | Socket.IO 后端 | Spring 原生支持更轻量，Socket.IO 增加运维复杂度 |
| AI 编排 | LangChain4j | LangChain (JS) | 后端是 Java，LangChain4j 是 Java 生态最佳选择 |
| 数据库 | PostgreSQL | MySQL | 项目要求 MySQL 或 PostgreSQL，PostgreSQL JSON/JSONB 支持更好，AI 场景更合适 |
| 缓存 | Redis | 不使用 | 可选，初期不需要，扩展时再加 |
| 前端状态 | Pinia 3 | Vuex 5 | Vuex 已停止维护，Pinia 是官方推荐 |
| 构建工具 | Vite 8 | Webpack | Vite 基于 ESM，开发体验碾压 Webpack |
## Anti-Patterns to Avoid
| Anti-Pattern | Why Avoid | Instead |
|--------------|-----------|---------|
| 前端直接调用 DeepSeek API | API Key 暴露风险 | 后端统一封装 AIService |
| 使用 Vue 2 | Vue 2 已 EOL，无安全更新 | Vue 3 + Composition API |
| 使用 Webpack | 开发体验差，冷启动慢 | Vite |
| 不使用 TypeScript | 运行时类型错误难排查 | TypeScript 严格模式 |
| 前端存储聊天记录 | 浏览器限制、隐私风险 | 后端 MySQL 持久化 |
## Installation
### Frontend (client/)
### Backend (server/)
## Sources
- [Vue.js 3 Docs](https://ctx7.com/vuejs/vue) — Context7
- [Spring Boot Docs](https://ctx7.com/spring-projects/spring-boot) — Context7
- [LangChain4j Docs](https://ctx7.com/langchain4j/langchain4j) — Context7
- [Socket.IO v4 Docs](https://ctx7.com/websites/socket_io) — Context7
- [Firecrawl Docs](https://ctx7.com/websites/firecrawl_dev) — Context7
- [Vue Router Installation](https://router.vuejs.org/installation.html) — Official
- [Pinia Introduction](https://pinia.vuejs.org/introduction.html) — Official
- [Vite Guide](https://vite.dev/guide/) — Official
- [LangChain4j OpenAI Integration](https://docs.langchain4j.dev/integrations/language-models/openai-compatible) — Official
- [Firecrawl Node.js SDK](https://docs.firecrawl.dev/sdks/node) — Official

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.

## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.


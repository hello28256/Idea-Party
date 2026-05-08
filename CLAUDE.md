<!-- GSD:project-start source:PROJECT.md -->
## Project

**AI Multi-Character Chat Platform**

A group chat platform where one human can converse simultaneously with multiple AI personas (historical figures, celebrities, writers, experts) in a shared chat room. Characters are auto-generated from internet data with distinct personalities, speaking styles, and domain expertise. A system moderator coordinates turn-taking based on topic relevance and character expertise.

**Core Value:** **Natural multi-party AI conversations that feel like talking to a group of interesting people, not a chatbot.**

### Constraints

- **Platform**: Web application (React + Node backend)
- **AI Provider**: Claude API for conversation generation
- **Data**: Character data from public web sources (Wikipedia, official sites)
- **v1 scope**: Single chat room, up to 6 characters + 1 human
<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->
## Technology Stack

## Recommended Stack
### Frontend
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| React | 19.x | UI Framework | Concurrent features, automatic batching, new use() hook for async. Context7: `/facebook/react` v19.2.0 latest |
| Vite | 8.x | Build Tool | Instant HMR, ESM-native. Context7: `/vitejs/vite` v8.x. Faster than Webpack/CRA |
| TypeScript | 5.x | Type Safety | Catch errors at compile time, better DX |
| TanStack Query | 5.x | Server State | Better than Redux for async data. Built-in caching, refetching, optimistic updates |
| Socket.IO Client | 4.x | Real-time | Bidirectional communication. Context7: `/websites/socket_io_v4` |
### Backend
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Node.js | 22.x | Runtime | Current LTS with native fetch, better performance. Context7: `/nodejs/node` v22.17.0 |
| Express | 4.x | HTTP Server | Battle-tested, massive ecosystem, simple middleware. (Fastify is faster but less documented for AI use cases) |
| TypeScript | 5.x | Type Safety | Share types with frontend |
### AI Integration
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Claude API | Latest | AI Responses | Project constraint: using Claude. Context7: `/websites/platform_claude_en_api` |
| @anthropic/sdk | 0.2.x | Official SDK | Type-safe client for Messages API |
### Database
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| PostgreSQL | 16/17/18 | Primary Database | Relational integrity for characters/rooms/messages. Context7: `/websites/postgresql` |
| Prisma | 7.x | ORM | Type-safe queries, migrations, DX. Context7: `/websites/prisma_io` v7.6.0 |
### Real-time Communication
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Socket.IO | 4.x | WebSocket | Fallback polling, reconnection, rooms/namespaces for chat rooms |
## Installation
# Frontend
# Backend
## Architecture Overview
## Alternatives Considered
| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Build Tool | Vite | Next.js | SSR not needed for v1, adds complexity |
| State Management | TanStack Query | Redux | Redux is overkill for server state; TanStack Query handles caching automatically |
| Runtime | Node.js 22 | Bun/Deno | Better ecosystem stability, more libraries |
| HTTP Server | Express | Fastify | Express has more AI-related examples, simpler middleware |
| ORM | Prisma | Sequelize | Prisma has better TypeScript support, cleaner migrations |
| WebSocket | Socket.IO | Native WS | Socket.IO has fallback, reconnection, room semantics built-in |
## Key Dependencies
### Client (package.json)
### Server (package.json)
## What NOT To Use
| Technology | Why Avoid | Instead Use |
|------------|-----------|-------------|
| Create React App | Deprecated, slow | Vite |
| Redux Toolkit | Overkill for this scale | TanStack Query + React Context |
| MongoDB | Wrong data model | PostgreSQL (relational fits characters/rooms/messages) |
| tRPC | Adds complexity | REST API + fetch (simpler for v1) |
| Next.js API Routes | Vendor lock-in | Standalone Express server |
## Sources
- React: Context7 `/facebook/react` v19.2.0
- Vite: Context7 `/vitejs/vite` v8.x
- Node.js: Context7 `/nodejs/node` v22.17.0
- PostgreSQL: Context7 `/websites/postgresql`
- Prisma: Context7 `/websites/prisma_io` v7.6.0
- Socket.IO: Context7 `/websites/socket_io_v4`
- Claude API: Context7 `/websites/platform_claude_en_api`
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->



<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->

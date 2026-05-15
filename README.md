# Idea Party

> AI 多角色聊天社区平台 — 创建独特的 AI 角色，与多个角色同时对话。

## 功能特性

### 角色系统
- **创建 AI 角色** - 自定义角色名称、描述、人设和说话风格
- **Prompt 人格系统** - 通过 prompt 定义角色行为和专业知识
- **角色库管理** - 浏览、编辑和删除已创建的角色
- **模态框编辑** - 直观的弹窗编辑体验

### 聊天功能
- **多角色群聊** - 同时与多个 AI 角色交流讨论
- **实时消息** - WebSocket 驱动的即时消息
- **思考指示器** - AI 生成响应时显示思考状态
- **聊天历史** - 持久化消息记录

## 技术栈

**前端**: Vue 3 + TypeScript + Vite + Pinia + Vue Router + Socket.io

**后端**: Spring Boot 3.5 + Java 21 + MySQL + LangChain4j

**AI**: DeepSeek (对话)

## 项目结构

```
client/                    # Vue 3 前端
  src/
    api/                 # API 调用
    components/          # 组件
      character/         # 角色相关组件
      chat/             # 聊天组件
      ui/               # UI 组件
    stores/             # Pinia 状态管理
    views/              # 页面视图
server/                   # Spring Boot 后端
  src/main/java/
    controller/         # REST 控制器
    entity/             # 数据库实体
    service/            # 业务逻辑
    repository/         # 数据访问层
```

## 快速开始

### 环境要求

- Node.js 20+
- Java 21
- MySQL 8.x
- Maven 3.9+

### 1. 克隆项目

```bash
git clone <repo-url>
cd Idea-Party
```

### 2. 配置数据库

创建 MySQL 数据库：

```sql
CREATE DATABASE ideaparty CHARACTER SET utf8mb4;
```

修改 `server/.env`：

```env
DB_PASSWORD=your_db_password
JWT_SECRET=your_jwt_secret_min_32_chars
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

访问 `http://localhost:5173`，然后在**设置页面**配置你的 API Key。

## API Key 配置

首次使用需要在前端设置页面配置以下 API Key：

| 服务 | 用途 | 获取地址 |
|------|------|---------|
| **DeepSeek** | AI 对话 | [platform.deepseek.com](https://platform.deepseek.com) |
| **Firecrawl** | 联网检索（可选） | [firecrawl.dev](https://firecrawl.dev) |

配置位置：**设置 → AI 配置**

> 注意：API Key 存储在浏览器本地，仅供当前用户使用。

## 创建 AI 角色

1. 点击侧边栏 **"+"** 或 **"创建角色"**
2. 填写角色信息：
   - **名称**: 角色名字（如"爱因斯坦"）
   - **描述**: 一句话介绍
   - **人设**: 详细的人格描述
   - **专业领域**: 角色的专长（如"物理学、数学"）
   - **年代**: 角色所在时代
   - **说话风格**: 如何表达（正式/幽默/学术等）
3. 保存后在聊天室添加该角色即可对话

---

**Made with ❤️ by Idea Party**

# 用户发送消息全链路解析

> **场景**：用户在 Idea-Party 聊天室里输入"你好"，按 Enter。
> **目标**：完整跟踪这条消息在前端、后端、数据库、AI 之间的流转。

---

## 一、宏观流程图

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                           前端 (Vue 3)                                  │
│                                                                         │
│  ChatInput.handleSend()                                                 │
│      │                                                                  │
│      ├─ emit('send', text)  ──────────────────────┐                     │
│      └─ 清空输入框 + 重置高度                       │                     │
│                                                   │                     │
│      ▼                                            │                     │
│  ChatRoomPanel.handleSend(content)  ◀──────────────┘                     │
│      │                                                                  │
│      ├─ ① messageStore.addMessage(userMsg)  ───► [UI 立即显示气泡]      │
│      │                                                                  │
│      ├─ ② roomStore.isDiscussing = true       ───► [触发 AI 编排]       │
│      │                                                                  │
│      └─ ③ sendMessage(content.trim())                                    │
│                │                                                        │
│                ▼                                                        │
│         useSocket.sendMessage(content)                                  │
│                │                                                        │
│                ▼                                                        │
│         sendSocketIO('chat message', { roomId, content })                │
│                │                                                        │
│                └─► WebSocket.send(payload)  ═══════╗                     │
└───────────────────────────────────────────────────║─────────────────────┘
                                                ║  走 WebSocket
                                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         后端 (Spring Boot)                              │
│                                                                         │
│  ChatWebSocketHandler.handleTextMessage()                                │
│      │                                                                  │
│      ├─ 鉴权：解析 JWT → userId                                         │
│      ├─ 解析事件名 == "chat message"                                     │
│      │                                                                  │
│      ▼                                                                  │
│  ChatService.processUserMessage(roomId, content, userId, characters...) │
│      │                                                                  │
│      ├─ ModerationService.moderate(content)  ──► 违规? 抛异常            │
│      ├─ MessageService.saveMessage(...)         ──► 写 MySQL            │
│      ├─ broadcast('chat message', message)       ──► 全房间广播         │
│      │                                                                  │
│      ▼                                                                  │
│  ModeratorAgent.processMessage(...)  【异步线程】                        │
│      │                                                                  │
│      ├─ 加载最近 20 条历史（喂给 LLM 当上下文）                           │
│      ├─ AIService.generateStream(...) ──► DeepSeek API                  │
│      ├─ 流式解析每个 AI 角色的回复                                        │
│      │                                                                  │
│      ├─ 每收到一段 → onStream 回调                                       │
│      │       └─► ChatSocketHandler.broadcast('stream chunk', ...)        │
│      │                                                                  │
│      └─ 全部完成 → onMessage 回调                                       │
│              └─► MessageService.saveMessage(...)  ──► 写 MySQL           │
│              └─► ChatSocketHandler.broadcast('chat message', ...)       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                                ║
                                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          前端接收回包                                    │
│                                                                         │
│  useSocket.ts → ws.onmessage(event)                                     │
│      │                                                                  │
│      ├─ 解析 Socket.IO 协议帧                                           │
│      ├─ 匹配事件名分支                                                  │
│      │                                                                  │
│      ├─ 'stream chunk'  ─► messageStore.appendStreamChunk(...)          │
│      │                       └─► UI 实时追加文字（打字机效果）             │
│      │                                                                  │
│      ├─ 'chat message'  ─► messageStore.addMessage(aiMsg)              │
│      │                       └─► UI 完整显示 AI 气泡                     │
│      │                                                                  │
│      └─ 'thinking' / 'phase change' / 'error'                           │
│              └─► roomStore 更新圆桌状态机                                 │
└─────────────────────────────────────────────────────────────────────────┘
```text

---

## 二、前端逐层拆解

### 步骤 1：用户敲键盘 → 子组件触发

**文件**：`client/src/components/chat/ChatInput.vue`（第 49-59 行）

```typescript
function handleSend() {
  if (!canSend.value) return                                    // ① 兜底校验

  emit('send', content.value.trim())                            // ② 抛给父组件
  content.value = ''                                            // ③ 清空输入框

  if (textareaRef.value) {                                      // ④ 重置高度
    textareaRef.value.style.height = 'auto'
  }
}
```text

**关键点**：
- `emit('send', text)` 是 Vue 的"子→父"事件机制
- 这个子组件**不知道**消息要去哪儿，它只负责"清空自己 + 抛文本上去"
- `canSend` 检查文本非空 + 未被父组件禁用

### 步骤 2：父组件接收 → 三件事并行

**文件**：`client/src/components/chat/ChatRoomPanel.vue`（第 244-265 行）

```typescript
function handleSend(content: string) {
  if (!content.trim()) return

  // ① 构造"用户消息"对象
  const userMsg: ChatMessage = {
    id: 'temp-' + Date.now(),                                    // 临时 ID
    roomId: props.roomId,
    characterId: null,
    senderType: 'USER',
    userId: authStore.user?.id || null,
    content: content.trim(),
    createdAt: new Date().toISOString()
  }
  messageStore.addMessage(userMsg)                               // ② 乐观更新

  if (isDiscussionMode.value) {
    roomStore.isDiscussing = true                                // ③ 标记 AI 编排
  }

  // ④ 真正发到后端
  sendMessage(content.trim())
}
```text

**乐观更新（Optimistic UI）**：
- 第 ② 步先在本地 store 塞一条"假消息"（ID 是 `temp-xxx`）
- UI 立刻显示用户气泡，**不等后端**
- 等后端真返回时，会用真实 ID 替换掉临时 ID
- 这样用户感觉"瞬间发出去了"，体感延迟 0

### 步骤 3：useSocket 包装 → WebSocket 发出

**文件**：`client/src/composables/useSocket.ts`（第 158-172 行）

```typescript
function sendSocketIO(event: string, data: object) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    console.warn('[useSocket] not connected, dropping:', event)
    return
  }
  ws.send(formatSocketIOMessage(event, data))    // 序列化成 Socket.IO 协议帧
}

function sendMessage(content: string) {
  sendSocketIO('chat message', { roomId, content })
}
```text

**为什么用 WebSocket 而不是 HTTP POST？**

| 通道 | 优劣 |
|------|------|
| HTTP POST | 单向：发完要等响应；AI 回复要前端轮询 |
| **WebSocket** | **双向**：消息发出 + AI 流式回复共用一个长连接，延迟低、实时性强 |

AI 回复是**流式生成**的（DeepSeek 一次吐几十个字），用 WebSocket 才能做到"打字机效果"。HTTP 只能等整段生成完才能拿到。

### 步骤 4：协议帧

WebSocket 上跑的是 **Socket.IO 协议**（不是裸 WebSocket），帧结构类似：

```text
42["chat message",{"roomId":"xxx","content":"你好"}]
│ │  └─ 事件名      └─ 载荷（自动 JSON 序列化）
│ └─  "message" 类型（Socket.IO 数字协议）
└─ Engine.IO  "message" 标识
```text

后端 `ChatWebSocketHandler` 会解析这个 42 前缀。

---

## 三、后端逐层拆解

### 步骤 5：WebSocket 入口 → 鉴权 + 路由

**文件**：`server/src/main/java/com/ideaparty/websocket/ChatWebSocketHandler.java`

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // ① 解析 Engine.IO 帧（去掉 "42[" 前缀，转成 JSON）
    // ② 提取事件名（"chat message"）和 data
    // ③ 从 session 属性拿 userId（握手时由 JwtAuthFilter 写入）
    // ④ 按事件名分发到对应 handler
}
```text

**鉴权时机**：连接建立时（`afterConnectionEstablished`），前端会在 URL 带 `?token=xxx`，`JwtAuthFilter` 验证后写入 `session.getAttributes().put("userId", uuid)`。后续每条消息都从这里取 userId，**不需要每条消息都带 token**。

### 步骤 6：业务分发 → ChatService

**文件**：`server/src/main/java/com/ideaparty/service/ChatService.java`（第 135 行）

```java
public void processUserMessage(UUID roomId, String content, UUID userId,
                                List<Character> characters, ...) {
    // ① 内容审核
    ModerationResult result = moderationService.moderate(content);
    if (!result.isAllowed()) throw new IllegalArgumentException(result.getReason());

    // ② 持久化用户消息
    Message userMsg = messageService.saveMessage(
        roomId, null, SenderType.USER, content, userId);

    // ③ 广播给房间所有人（包括发件人自己，替换掉 tempId）
    broadcastToRoom(roomId, "chat message", userMsg);

    // ④ 触发 Moderator 异步编排
    moderatorAgent.processMessage(roomId, userId, content, characters, ...);
}
```text

### 步骤 7：内容审核

**文件**：`server/src/main/java/com/ideaparty/service/ModerationService.java`

简单的关键词过滤（违禁词列表 + 正则）：
- 命中 → 抛 `IllegalArgumentException`
- `GlobalExceptionHandler` 捕获 → 返回 400 + 错误信息
- 前端收到 → UI 提示"内容违规"

### 步骤 8：持久化

**文件**：`server/src/main/java/com/ideaparty/service/MessageService.java`

```java
public Message saveMessage(UUID roomId, UUID characterId, SenderType type,
                            String content, UUID userId) {
    Message msg = new Message();
    msg.setContent(content);
    msg.setSenderType(type);
    msg.setRoom(roomRepository.findById(roomId).orElseThrow());
    if (type == SenderType.CHARACTER) msg.setCharacter(characterRepository.findById(characterId).orElseThrow());
    if (type == SenderType.USER) msg.setUser(userRepository.findById(userId).orElseThrow());
    return messageRepository.save(msg);   // JPA → MySQL INSERT
}
```text

**关键点**：
- `sender_type` 决定 `character_id` 和 `user_id` 哪个非空
- `room_id` 永远非空（消息必属某房间）
- 主键 UUID 是数据库生成（`@GeneratedValue(strategy = GenerationType.UUID)`）

### 步骤 9：广播给房间所有人

```java
public void broadcastToRoom(String roomId, String event, Object data) {
    String payload = formatSocketIO(event, data);   // 42["chat message",{...}]
    for (WebSocketSession session : roomMembers.get(roomId)) {
        session.sendMessage(new TextMessage(payload));
    }
}
```text

**房间订阅**：维护一个 `Map<roomId, Set<WebSocketSession>>`，每次广播遍历集合。这个 Map 在 `joinRoom` / `leaveRoom` 时维护。

### 步骤 10：触发 Moderator（**异步！**）

```java
public void processMessage(String roomId, String userId, String content,
                            List<Character> characters, ...) {
    // 创建一个新线程（SecurityContextAwareThread），不阻塞 WebSocket 线程
    new SecurityContextAwareThread(() -> {
        orchestrateDiscussion(roomId, content, characters, ...);
    }).start();
}
```text

**为什么异步？**
- AI 生成可能耗时 5-30 秒
- WebSocket 线程池线程数有限（默认 100）
- 如果同步执行，第 11 个用户发消息就会卡住
- 用独立线程 + `SecurityContextAwareThread` 复制安全上下文（AI Service 需要用户身份调 DeepSeek）

### 步骤 11：Moderator 编排 —— 项目**最核心**的逻辑

**文件**：`server/src/main/java/com/ideaparty/service/ModeratorAgent.java`（第 548 行）

```java
public void processMessage(String roomId, String userId, String content,
                            List<Character> characters, ...) {

    // ① 加载上下文：最近 20 条历史消息
    String recentHistory = loadRecentHistory(roomId, 20);

    // ② 构造 Moderator 的 system prompt
    String systemPrompt = buildModeratorPrompt(characters, recentHistory);

    // ③ 调 DeepSeek API（流式）
    aiService.generateResponseStream(systemPrompt, ..., (chunk) -> {
        // 每收到一段 chunk：
        // - 解析是"哪个角色在说话"
        // - 调 broadcastToRoom('stream chunk', { characterId, text })
    });

    // ④ 等整段流结束，把每段拼成最终消息
    // - 调 messageService.saveMessage(...) 落库
    // - 调 broadcastToRoom('chat message', finalMsg)
}
```text

**Moderator 的本质**：
- 它**不是**一个固定的角色，而是 LLM 调用
- 输入：所有 AI 角色的名字 + 设定 + 聊天历史
- 输出：JSON 序列，每个元素指定"哪个角色说什么"
- 例如：
  ```json
  [
    { "character": "苏格拉底", "content": "我认为这个问题需要先厘清概念本身。" },
    { "character": "孔子",     "content": "善哉问也，君子当三省吾身。" }
  ]
  ```
- 后端拿到这个 JSON，**逐个角色**调用 DeepSeek 生成完整回复
- 每个角色调用完都流式推送回前端

### 步骤 12：流式生成 → 多次小广播

**文件**：`server/src/main/java/com/ideaparty/service/AIService.java`

```java
public void generateResponseStream(String systemPrompt, String userPrompt,
                                    StreamCallback callback) {
    // 调 DeepSeek Chat Completions API，stream=true
    // 每次收到 SSE 事件 → 回调 callback.onChunk(text)
}
```text

DeepSeek API 返回 SSE（Server-Sent Events），每收到一个 chunk：
1. 解析出当前 token
2. 调用 callback
3. callback 里把这段文字包装成 Socket.IO 帧
4. 推送给前端

### 步骤 13：完整消息入库 + 最终广播

流结束后：
```java
Message finalMsg = messageService.saveMessage(
    roomId, characterId, SenderType.CHARACTER, fullText, ownerUserId);
broadcastToRoom(roomId, "chat message", MessageDto.fromEntity(finalMsg));
```text

---

## 四、前端接收回包

**文件**：`client/src/composables/useSocket.ts`（第 103-114 行）

```typescript
ws.onmessage = (event) => {
    const { eventName, data } = parseSocketIOMessage(event.data)
    switch (eventName) {
        case 'stream chunk':
            // 打字机效果：追加文字，不替换
            messageStore.appendStreamChunk(data.characterId, data.text)
            break

        case 'chat message':
            if (data.senderType === 'CHARACTER') {
                // AI 完整消息到达
                messageStore.addMessage(data)
            } else if (data.senderType === 'USER') {
                // 自己刚发的消息回执：替换临时 ID
                messageStore.replaceTempMessage(data)
            }
            break

        case 'thinking':
            // Moderator 在思考中：显示"苏格拉底正在组织语言..."
            roomStore.setThinkingCharacter(data.characterId)
            break

        case 'phase change':
            // 圆桌状态变化：开场 → 自由讨论 → 收尾
            roomStore.setDiscussionPhase(data.phase)
            break

        case 'error':
            toast.error(data.message)
            break
    }
}
```text

---

## 五、数据库长什么样

### users 表（简版）
```sql
CREATE TABLE users (
    id BINARY(16) PRIMARY KEY,        -- UUID
    username VARCHAR(50) UNIQUE NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,    -- BCrypt 哈希
    email VARCHAR(255) UNIQUE,
    is_admin BOOLEAN DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
```text

### rooms 表
```sql
CREATE TABLE rooms (
    id BINARY(16) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    mode VARCHAR(20) NOT NULL,         -- 'discussion' / 'one-on-one'
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);
```text

### messages 表（**核心**）
```sql
CREATE TABLE messages (
    id BINARY(16) PRIMARY KEY,
    content TEXT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,   -- 'USER' / 'CHARACTER'
    character_id BINARY(16) NULL,
    user_id BINARY(16) NULL,
    room_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
    FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE SET NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    -- CHECK：sender_type=USER 时 user_id 非空，character_id 为空
    --       sender_type=CHARACTER 时 character_id 非空，user_id 为空
);
```text

### 一条用户消息的最终样子
```json
{
  "id": "7c8e3b1a-...-f72c",
  "content": "你好",
  "senderType": "USER",
  "userId": "a3f1d2e4-...-8b9c",
  "characterId": null,
  "characterName": null,
  "roomId": "1e7c9a82-...-4d3f",
  "createdAt": "2026-06-20T16:42:18.123Z"
}
```text

---

## 六、为什么这么设计？

### Q：为什么用 Socket.IO 而不是裸 WebSocket？
**A**：自动处理重连、断线检测、房间订阅、事件名路由 —— 不用自己写 ping/pong。

### Q：为什么 Moderator 要异步？
**A**：AI 生成 5-30 秒，同步会卡 WebSocket 线程池。独立线程隔离阻塞 + 复制 SecurityContext。

### Q：为什么不用 HTTP POST 发消息？
**A**：AI 回复是流式的（一个字一个字吐），HTTP 只能等整段。WebSocket 可以边生成边推。

### Q：为什么先 addMessage 再 sendMessage？
**A**：乐观更新（Optimistic UI）。用户感知的延迟从 200ms 变成 0ms，等后端回包再用真实 ID 替换。

### Q：为什么 Moderator Agent 不是真"一个人"，而是 LLM 调用？
**A**：主持人没有固定人格，它的作用是**编排发言顺序**。本质上是让 LLM 分析"现在该谁说话"，输出 JSON 序列，后端按序列逐个调 AI 角色。

---

## 七、关键文件索引

| 层 | 文件 | 关键行 |
|----|------|--------|
| 前端 - 子组件 | `client/src/components/chat/ChatInput.vue` | 49-59（emit） |
| 前端 - 父组件 | `client/src/components/chat/ChatRoomPanel.vue` | 244-265（构造 + 发送） |
| 前端 - WebSocket | `client/src/composables/useSocket.ts` | 158-172（sendMessage） |
| 前端 - Store | `client/src/stores/message.ts` | addMessage / appendStreamChunk |
| 后端 - WebSocket 入口 | `server/src/main/java/com/ideaparty/websocket/ChatWebSocketHandler.java` | handleTextMessage |
| 后端 - 业务 | `server/src/main/java/com/ideaparty/service/ChatService.java` | 135（processUserMessage） |
| 后端 - 编排 | `server/src/main/java/com/ideaparty/service/ModeratorAgent.java` | 548（processMessage） |
| 后端 - AI 调用 | `server/src/main/java/com/ideaparty/service/AIService.java` | generateResponseStream |
| 后端 - 持久化 | `server/src/main/java/com/ideaparty/service/MessageService.java` | saveMessage |
| 后端 - 审核 | `server/src/main/java/com/ideaparty/service/ModerationService.java` | moderate |

---

## 八、一句话总结

> **用户按 Enter → 前端乐观更新 UI + 通过 WebSocket 发 `'chat message'` 事件 → 后端鉴权+审核+入库+广播 → 异步启动 Moderator Agent → 调 DeepSeek 流式生成 → 每段 chunk 通过 WebSocket 推回前端 → 前端实时追加（打字机效果）→ 流结束完整消息入库 → 前端收到完整 AI 消息 → UI 显示完整气泡。**

整个链路里**用户消息同步**、**AI 回复异步 + 流式**，所以体感是"消息秒发、AI 边想边打字"。
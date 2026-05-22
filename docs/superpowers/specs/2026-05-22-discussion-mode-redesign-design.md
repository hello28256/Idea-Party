# Discussion Mode 重构设计文档

## 1. 概述

**目标：** 将 Discussion Mode 从"AI 无限群聊"转变为"用户参与的 AI 圆桌讨论"。

**核心变化：**
- 用户始终是讨论核心
- AI 角色不能无限连续发言
- Moderator 主动邀请用户参与
- 用户可随时插话打断 AI
- 动态选择最合适的角色组合

---

## 2. 状态机设计

### 2.1 状态定义

| 状态 | 描述 | 可转换到 |
|------|------|----------|
| `IDLE` | 无讨论进行 | `MODERATING` |
| `MODERATING` | Moderator 分析问题、选择角色 | `SPEAKING` |
| `SPEAKING` | AI 角色发言中 | `SPEAKING`(继续)、`WAITING_FOR_USER`、`IDLE` |
| `WAITING_FOR_USER` | 等待用户输入 | `MODERATING`(用户发消息)、`SPEAKING`(用户点击继续) |
| `PAUSED` | 用户手动暂停 | `SPEAKING`(用户点击继续) |

### 2.2 状态转换图

```
                    ┌─────────────────┐
                    │   IDLE          │
                    └────────┬────────┘
                             │ 用户发送消息
                             ▼
                    ┌─────────────────┐
                    │  MODERATING     │
                    └────────┬────────┘
                             │ 选择角色(1-2个)
                             ▼
              ┌─────────────────────────────────┐
              │          SPEAKING               │
              │  AI 角色发言 (最多 3 条消息)     │
              └──────────────┬──────────────────┘
                             │ ≥2条AI消息后
                             ▼
                    ┌─────────────────┐
                    │WAITING_FOR_USER│
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
    用户发消息          用户点击继续       用户暂停
          │                  │                  │
          ▼                  ▼                  ▼
   ┌───────────┐     ┌───────────┐     ┌───────────┐
   │MODERATING │     │  SPEAKING │     │  PAUSED  │
   │(重新分析) │     │(继续发言) │     └────┬──────┘
   └───────────┘     └───────────┘          │
                                  用户点击继续 │
                                         ▼
                                   ┌───────────┐
                                   │  SPEAKING │
                                   └───────────┘
```

### 2.3 状态机实现

```java
public enum DiscussionPhase {
    IDLE,           // 无讨论
    MODERATING,     // Moderator 分析中
    SPEAKING,       // AI 角色发言中
    WAITING_FOR_USER, // 等待用户输入
    PAUSED          // 用户手动暂停
}

public static class DiscussionState {
    volatile DiscussionPhase phase = DiscussionPhase.IDLE;
    volatile boolean isRunning = false;
    volatile boolean paused = false;
    volatile boolean userInterjected = false;  // 用户插话标志

    volatile int aiMessageCount = 0;          // 当前轮 AI 消息计数
    volatile int maxAiMessagesPerRound = 3;   // 每轮最多 AI 消息

    List<Character> selectedCharacters = new ArrayList<>();
    List<Character> pendingQueue = new ArrayList<>();
    String currentUserMessage = "";

    AtomicBoolean cancelled = new AtomicBoolean(false);
}
```

---

## 3. 新的 Discussion Flow

### 3.1 标准讨论流程

```
阶段 1: 用户发送消息
    │
    ▼
阶段 2: MODERATING
    │  - 分析用户问题
    │  - 动态选择 1-2 个最合适的角色
    │  - 根据角色生成不同观点
    │
    ▼
阶段 3: SPEAKING
    │  - 角色1 发言 (简洁，1-3段)
    │  - [可选] 角色2 发言 (与角色1 观点对立或补充)
    │
    ▼ (2条AI消息后或角色说完)
阶段 4: WAITING_FOR_USER
    │  - Moderator 总结各方观点
    │  - Moderator 主动邀请用户参与
    │
    ▼
    [等待用户输入]
```

### 3.2 用户插话流程

```
用户发送新消息
    │
    ▼
1. 设置 userInterjected = true
2. 清空 pendingQueue (后续等待发言的角色)
3. 如果有角色正在流式输出，尝试中断
4. 立即切换到 MODERATING 状态
5. Moderator 重新分析新消息
6. 选择与用户观点相关的新角色组合
```

### 3.3 用户点击"继续讨论"

```
用户点击继续
    │
    ▼
1. 如果是 WAITING_FOR_USER → 直接继续 SPEAKING
2. 如果是 PAUSED → 继续 SPEAKING
3. 从 pendingQueue 取下一个角色发言
4. 或重新选择角色继续
```

---

## 4. Moderator Agent 重设计

### 4.1 职责定义

Moderator **不回答问题**，只负责：
1. **分析问题** - 理解用户想问什么
2. **选择角色** - 从角色池中选择最相关的 1-2 个
3. **控制节奏** - 确保 AI 不连续发言超过 3 条
4. **邀请用户** - 每轮结束后主动提问用户
5. **总结观点** - 用 1-2 句话概括各方立场

### 4.2 Moderator Prompt

```
# 角色设定
你是"圆桌讨论主持人"，不是参与者。你不回答问题。

你的职责：
1. 分析用户问题，选择最适合的 1-2 个角色参与讨论
2. 控制讨论节奏，防止 AI 无限对话
3. 在适当时邀请观众（用户）参与
4. 总结不同角色的核心观点

# 核心规则
- 每轮最多让 2 个角色发言
- 连续 AI 消息不超过 3 条
- 每轮结束后必须邀请用户参与
- 优先制造观点冲突或对立
- 用户消息优先级最高：收到用户消息后立即重新组织讨论
- 保持角色发言简洁（2-4 句话）

# 输出格式
你必须选择角色时，输出：
[SELECT:角色名1,角色名2]
理由：...

你必须邀请用户时，输出：
[INVITE:你更支持谁的观点？/你怎么看这个问题？/你有什么不同看法？]

你必须总结时，输出：
[SUMMARY:角色A认为...；角色B认为...]
```

### 4.3 角色选择策略

```java
private List<Character> selectCharacters(String userMessage, List<Character> availableCharacters) {
    // 1. 分析用户问题的关键词
    // 2. 匹配角色的 expertise、personality
    // 3. 优先选择观点对立或互补的角色组合
    // 4. 避免连续选择相同角色
}
```

---

## 5. 中断机制（关键）

### 5.1 用户插话中断

```java
public void handleUserInterjection(String roomId, String userMessage) {
    DiscussionState state = discussionStates.get(roomId);

    // 1. 立即设置中断标志
    state.userInterjected.set(true);

    // 2. 清空等待发言的队列
    state.pendingQueue.clear();

    // 3. 如果有角色正在流式输出，中断它
    if (state.currentStream != null) {
        state.currentStream.cancel();
        state.currentStream = null;
    }

    // 4. 重置 AI 消息计数
    state.aiMessageCount = 0;

    // 5. 切换到 MODERATING 状态
    state.phase = DiscussionPhase.MODERATING;
    state.currentUserMessage = userMessage;

    // 6. 通知前端状态变化
    broadcastStateChange(roomId, "MODERATING");

    // 7. 立即开始新的 Moderator 分析
    processModeratorAnalysis(roomId);
}
```

### 5.2 流式输出中断

```java
// 当用户在角色流式输出时发送消息
// 需要能够中断正在进行的 generateCharacterResponse()

// 方案：使用 CompletableFuture + cancel
CompletableFuture<String> currentResponse = new CompletableFuture<>();

// 中断时调用
currentResponse.cancel(true);

// 在流式输出循环中检查
if (Thread.currentThread().isInterrupted() || currentResponse.isCancelled()) {
    // 清理并退出
    return;
}
```

---

## 6. 前端修改

### 6.1 状态管理

```typescript
// message.ts 新增
const discussionPhase = ref<'IDLE' | 'MODERATING' | 'SPEAKING' | 'WAITING_FOR_USER' | 'PAUSED'>('IDLE')
const selectedCharacterIds = ref<string[]>([])  // 当前选中的角色

// 前端状态映射
function setDiscussionPhase(phase: string) {
    discussionPhase.value = phase as any
}
```

### 6.2 UI 显示

```
┌─────────────────────────────────────────────┐
│  状态指示器:                                │
│  [讨论中] [等待你参与] [已暂停] [Moderating]│
├─────────────────────────────────────────────┤
│                                             │
│  [马斯克头像]: AI会极大提升编程效率...      │
│                                             │
│  [马云头像]: 但人的创造力是AI无法替代的...  │
│                                             │
│  ══════════════════════════════════════    │
│  🎤 主持人: 你更认同谁的观点？马斯克还是   │
│     马云？或者你有不同看法？                │
│  ══════════════════════════════════════    │
│                                             │
├─────────────────────────────────────────────┤
│  [💬 输入框...]              [发送]        │
├─────────────────────────────────────────────┤
│  [暂停讨论]  [停止讨论]  [继续讨论]         │
└─────────────────────────────────────────────┘
```

### 6.3 Moderator 消息样式

```vue
<!-- Moderator 消息特殊样式 -->
<div class="moderator-message bg-yellow-50 border-l-4 border-yellow-400">
    <div class="flex items-center gap-2 mb-1">
        <span class="text-yellow-600">🎤</span>
        <span class="font-semibold text-yellow-700">主持人</span>
    </div>
    <div class="text-yellow-800">{{ content }}</div>
</div>
```

---

## 7. 后端修改

### 7.1 ModeratorAgent.java 重写要点

| 方法 | 变化 |
|------|------|
| `processMessage()` | 改为先进入 MODERATING 状态 |
| `runSequentialDiscussion()` | 改为 `runDiscussionRound()`，每轮只让选中的角色发言 |
| `generateCharacterResponse()` | 支持流式中断 |
| `selectCharacters()` | 新方法：根据问题动态选择角色 |
| `buildModeratorPrompt()` | 完全重写 prompt |
| `shouldWaitForUser()` | 新方法：判断是否应该暂停等用户 |
| `interruptCurrentRound()` | 新方法：中断当前发言 |

### 7.2 ChatSocketHandler.java 修改

| 事件 | 处理变化 |
|------|----------|
| `chat message` | 检测到 userInterjected 时调用 `handleUserInterjection()` |
| `resume-discussion` | 从 `WAITING_FOR_USER` 或 `PAUSED` 恢复 |
| 新增 `discussion-state` | 广播状态变化给前端 |

### 7.3 WebSocket 事件扩展

```typescript
// 前端 ← 后端
interface DiscussionStateEvent {
    phase: 'IDLE' | 'MODERATING' | 'SPEAKING' | 'WAITING_FOR_USER' | 'PAUSED'
    selectedCharacters?: string[]  // 当前选中角色
    message?: string               // Moderator 的邀请/总结
}
```

---

## 8. 发言限制规则

| 规则 | 值 | 说明 |
|------|-----|------|
| 每轮最大 AI 消息数 | 3 | 超过后强制等待用户 |
| 每轮最大角色数 | 2 | Moderator 选择的角色数 |
| 角色发言长度 | 2-4 句话 | 通过 prompt 控制 |
| Moderator 邀请频率 | 每轮一次 | 每次暂停时必须邀请 |

---

## 9. 成功标准

1. 用户发送消息后，Moderator 立即分析并选择角色
2. AI 角色连续发言不超过 3 条
3. 每轮结束后，Moderator 主动邀请用户参与
4. 用户插话时，立即中断当前讨论
5. 用户点击"继续讨论"时，从断点继续
6. 前端清晰显示当前讨论状态

---

## 10. 实施顺序

1. **后端状态机重构** - `ModeratorAgent.java`
2. **中断机制实现** - 流式输出中断、队列清空
3. **Moderator Prompt 重写** - 新 prompt、新输出格式
4. **Socket 事件扩展** - 新增状态同步事件
5. **前端状态管理** - 新增 `discussionPhase`
6. **UI 更新** - 状态指示器、Moderator 样式

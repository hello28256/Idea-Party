# Discussion Mode 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Discussion Mode 从"AI 无限群聊"转变为"用户参与的 AI 圆桌讨论"，实现 Moderator 主持人机制、用户插话中断、动态角色选择。

**Architecture:** 后端重写 ModeratorAgent 状态机和 prompt，前端新增 discussionPhase 状态和 Moderator 消息样式。WebSocket 事件扩展支持状态同步。

**Tech Stack:** Spring Boot 3.5 / LangChain4j / Vue 3 / Pinia / Socket.IO

---

## 文件结构

```
server/src/main/java/com/ideaparty/
├── service/ModeratorAgent.java          # 重写状态机和 prompt
├── socket/ChatSocketHandler.java       # 扩展事件处理
├── dto/DiscussionStateEvent.java       # 新增：状态同步事件 DTO
└── dto/ModeratorMessage.java           # 新增：Moderator 消息 DTO

client/src/
├── stores/message.ts                   # 新增 discussionPhase 状态
├── composables/useSocket.ts            # 扩展状态事件处理
└── components/chat/
    └── ChatRoomPanel.vue               # 状态指示器、Moderator 样式
```

---

## 任务分解

### Task 1: 新增 DiscussionPhase 枚举和状态 DTO

**Files:**
- Create: `server/src/main/java/com/ideaparty/dto/DiscussionPhase.java`
- Modify: `server/src/main/java/com/ideaparty/dto/DiscussionStateEvent.java` (新建)
- Test: `server/src/test/java/com/ideaparty/service/ModeratorAgentTest.java`

- [ ] **Step 1: 创建 DiscussionPhase 枚举**

```java
// server/src/main/java/com/ideaparty/dto/DiscussionPhase.java
package com.ideaparty.dto;

public enum DiscussionPhase {
    IDLE,           // 无讨论
    MODERATING,    // Moderator 分析中
    SPEAKING,       // AI 角色发言中
    WAITING_FOR_USER, // 等待用户输入
    PAUSED          // 用户手动暂停
}
```

- [ ] **Step 2: 创建 DiscussionStateEvent DTO**

```java
// server/src/main/java/com/ideaparty/dto/DiscussionStateEvent.java
package com.ideaparty.dto;

import java.util.List;

public class DiscussionStateEvent {
    private DiscussionPhase phase;
    private List<String> selectedCharacters;
    private String message;  // Moderator 的邀请/总结

    public DiscussionStateEvent() {}

    public DiscussionStateEvent(DiscussionPhase phase, List<String> selectedCharacters, String message) {
        this.phase = phase;
        this.selectedCharacters = selectedCharacters;
        this.message = message;
    }

    // getters and setters
    public DiscussionPhase getPhase() { return phase; }
    public void setPhase(DiscussionPhase phase) { this.phase = phase; }
    public List<String> getSelectedCharacters() { return selectedCharacters; }
    public void setSelectedCharacters(List<String> selectedCharacters) { this.selectedCharacters = selectedCharacters; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
```

- [ ] **Step 3: 创建 ModeratorMessage DTO**

```java
// server/src/main/java/com/ideaparty/dto/ModeratorMessage.java
package com.ideaparty.dto;

public class ModeratorMessage {
    private String content;
    private String type; // "INVITE", "SUMMARY", "SELECT"

    public ModeratorMessage() {}

    public ModeratorMessage(String content, String type) {
        this.content = content;
        this.type = type;
    }

    // getters and setters
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
```

- [ ] **Step 4: 提交**

```bash
git add server/src/main/java/com/ideaparty/dto/DiscussionPhase.java
git add server/src/main/java/com/ideaparty/dto/DiscussionStateEvent.java
git add server/src/main/java/com/ideaparty/dto/ModeratorMessage.java
git commit -m "feat(discussion): add DiscussionPhase enum and DTOs"
```

---

### Task 2: 重写 ModeratorAgent 状态机

**Files:**
- Modify: `server/src/main/java/com/ideaparty/service/ModeratorAgent.java`
- Test: `server/src/test/java/com/ideaparty/service/ModeratorAgentTest.java`

- [ ] **Step 1: 添加新的状态字段到 DiscussionState**

在 `ModeratorAgent.java` 的 `DiscussionState` 内部类中新增：

```java
private static class DiscussionState {
    // ... 保留现有字段 ...

    // 新增字段
    volatile DiscussionPhase phase = DiscussionPhase.IDLE;
    volatile boolean userInterjected = false;
    volatile int aiMessageCount = 0;
    volatile int maxAiMessagesPerRound = 3;
    List<Character> selectedCharacters = new ArrayList<>();
    List<Character> pendingQueue = new ArrayList<>();
    volatile String moderatorMessage = "";
    volatile String currentUserMessage = "";

    // 流式输出中断控制
    volatile AtomicBoolean currentStreamCancelled = new AtomicBoolean(false);
}
```

- [ ] **Step 2: 新增状态转换方法**

```java
private void transitionTo(DiscussionState state, DiscussionPhase newPhase) {
    state.phase = newPhase;
    String roomId = findRoomIdByState(state);
    if (roomId != null) {
        broadcastStateChange(roomId, newPhase, state.selectedCharacters, state.moderatorMessage);
    }
}

private void broadcastStateChange(String roomId, DiscussionPhase phase,
                                  List<Character> selectedCharacters, String message) {
    DiscussionStateEvent event = new DiscussionStateEvent(
        phase,
        selectedCharacters.stream().map(Character::getId).map(UUID::toString).collect(Collectors.toList()),
        message
    );
    chatSocketHandler.broadcastToRoom(roomId, "discussion-state", event);
}
```

- [ ] **Step 3: 修改 handleUserInterjection 方法**

新增 `handleUserInterjection` 方法：

```java
public void handleUserInterjection(String roomId, String userMessage) {
    DiscussionState state = discussionStates.get(roomId);
    if (state == null) return;

    // 1. 立即设置中断标志
    state.userInterjected = true;

    // 2. 清空等待发言的队列
    state.pendingQueue.clear();

    // 3. 重置 AI 消息计数
    state.aiMessageCount = 0;

    // 4. 切换到 MODERATING 状态
    transitionTo(state, DiscussionPhase.MODERATING);
    state.currentUserMessage = userMessage;

    // 5. 立即开始新的 Moderator 分析
    processModeratorAnalysis(roomId, userMessage);
}
```

- [ ] **Step 4: 新增 processModeratorAnalysis 方法**

```java
private void processModeratorAnalysis(String roomId, String userMessage) {
    DiscussionState state = discussionStates.get(roomId);
    if (state == null) return;

    Room room = roomRepository.findWithCharactersById(UUID.fromString(roomId));
    if (room == null) return;

    List<Character> availableCharacters = room.getCharacters();

    // 调用 LLM 选择角色
    String selection = callModeratorForSelection(userMessage, availableCharacters);

    // 解析 [SELECT:角色1,角色2] 格式
    Pattern pattern = Pattern.compile("\\[SELECT:([^\\]]+)\\]");
    Matcher matcher = pattern.matcher(selection);

    if (matcher.find()) {
        String[] selectedNames = matcher.group(1).split(",");
        List<Character> selected = availableCharacters.stream()
            .filter(c -> Arrays.asList(selectedNames).contains(c.getName().trim()))
            .limit(2)
            .collect(Collectors.toList());

        state.selectedCharacters = selected;
        state.pendingQueue = new ArrayList<>(selected);

        // 发送 Moderator 选择消息给前端
        String selectMsg = "正在邀请: " + selected.stream().map(Character::getName).collect(Collectors.joining(", "));
        state.moderatorMessage = selectMsg;
        broadcastModeratorMessage(roomId, selectMsg, "SELECT");

        // 切换到 SPEAKING 状态
        transitionTo(state, DiscussionPhase.SPEAKING);

        // 开始发言流程
        processNextInQueue(roomId);
    } else {
        // 降级：直接让所有角色发言
        state.selectedCharacters = availableCharacters.subList(0, Math.min(2, availableCharacters.size()));
        transitionTo(state, DiscussionPhase.SPEAKING);
        processNextInQueue(roomId);
    }
}
```

- [ ] **Step 5: 修改 processMessage 方法入口**

找到现有 `processMessage` 方法，修改为：

```java
@Override
public void processMessage(String roomId, String userMessage, UUID userId) {
    DiscussionState state = discussionStates.computeIfAbsent(roomId, k -> new DiscussionState());

    // 重置中断标志
    state.userInterjected = false;
    state.currentUserMessage = userMessage;
    state.isRunning = true;

    // 立即进入 MODERATING 状态
    transitionTo(state, DiscussionPhase.MODERATING);

    // Moderator 分析并选择角色
    processModeratorAnalysis(roomId, userMessage);
}
```

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/ideaparty/service/ModeratorAgent.java
git commit -m "feat(discussion): rewrite ModeratorAgent state machine"
```

---

### Task 3: 实现发言限制和等待用户机制

**Files:**
- Modify: `server/src/main/java/com/ideaparty/service/ModeratorAgent.java`

- [ ] **Step 1: 新增 shouldWaitForUser 判断方法**

```java
private boolean shouldWaitForUser(DiscussionState state) {
    // 连续 AI 消息达到上限
    if (state.aiMessageCount >= state.maxAiMessagesPerRound) {
        return true;
    }
    // 角色队列已空
    if (state.pendingQueue.isEmpty() && state.selectedCharacters.isEmpty()) {
        return true;
    }
    return false;
}
```

- [ ] **Step 2: 修改 processNextInQueue 方法**

找到现有的角色发言循环，修改为：

```java
private void processNextInQueue(String roomId) {
    DiscussionState state = discussionStates.get(roomId);
    if (state == null || !state.isRunning) return;

    // 检查是否应该等待用户
    if (shouldWaitForUser(state)) {
        waitForUserInput(roomId);
        return;
    }

    // 从队列取下一个角色
    if (!state.pendingQueue.isEmpty()) {
        Character character = state.pendingQueue.remove(0);
        generateCharacterResponse(roomId, character, state.currentUserMessage);
    }
}
```

- [ ] **Step 3: 实现 waitForUserInput 方法**

```java
private void waitForUserInput(String roomId) {
    DiscussionState state = discussionStates.get(roomId);
    if (state == null) return;

    // 切换到 WAITING_FOR_USER 状态
    transitionTo(state, DiscussionPhase.WAITING_FOR_USER);

    // 生成邀请用户的消息
    String inviteMessage = generateModeratorInvite(state);
    state.moderatorMessage = inviteMessage;

    // 广播 Moderator 邀请消息
    broadcastModeratorMessage(roomId, inviteMessage, "INVITE");
}
```

- [ ] **Step 4: 新增 generateModeratorInvite 方法**

```java
private String generateModeratorInvite(DiscussionState state) {
    if (state.selectedCharacters.isEmpty()) {
        return "你想讨论什么话题？";
    }

    String characters = state.selectedCharacters.stream()
        .map(Character::getName)
        .collect(Collectors.joining(" 和 "));

    String[] invites = {
        "你更认同谁的观点，" + characters + "？",
        "你怎么看这个问题？",
        "你有什么不同看法？",
        characters + "观点各异，你支持谁？",
        "这场讨论你怎么看？"
    };

    return invites[random.nextInt(invites.length)];
}
```

- [ ] **Step 5: 在 generateCharacterResponse 结束后添加检查**

在角色发言完成后，需要检查是否应该继续或等待用户：

```java
// 在角色流式输出完成后，添加：
state.aiMessageCount++;

// 检查是否需要等待用户
if (shouldWaitForUser(state)) {
    waitForUserInput(roomId);
} else {
    // 继续处理队列中的下一个角色
    processNextInQueue(roomId);
}
```

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/ideaparty/service/ModeratorAgent.java
git commit -m "feat(discussion): implement AI message limit and wait-for-user mechanism"
```

---

### Task 4: 重写 Moderator Prompt

**Files:**
- Modify: `server/src/main/java/com/ideaparty/service/ModeratorAgent.java`

- [ ] **Step 1: 新增 buildModeratorPrompt 方法**

```java
private String buildModeratorPrompt(String userMessage, List<Character> characters) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("# 角色设定\n");
    prompt.append("你是"圆桌讨论主持人"，不是参与者。你不回答问题。\n\n");
    prompt.append("你的职责：\n");
    prompt.append("1. 分析用户问题，选择最适合的 1-2 个角色参与讨论\n");
    prompt.append("2. 控制讨论节奏，防止 AI 无限对话\n");
    prompt.append("3. 在适当时邀请观众（用户）参与\n");
    prompt.append("4. 总结不同角色的核心观点\n\n");
    prompt.append("# 核心规则\n");
    prompt.append("- 每轮最多让 2 个角色发言\n");
    prompt.append("- 连续 AI 消息不超过 3 条\n");
    prompt.append("- 每轮结束后必须邀请用户参与\n");
    prompt.append("- 优先制造观点冲突或对立\n");
    prompt.append("- 用户消息优先级最高：收到用户消息后立即重新组织讨论\n");
    prompt.append("- 保持角色发言简洁（2-4 句话）\n\n");
    prompt.append("# 可用角色\n");
    for (Character c : characters) {
        prompt.append("- ").append(c.getName())
            .append(" (专家领域: ").append(String.join(", ", c.getExpertise())).append(")")
            .append(" - ").append(c.getPersonality()).append("\n");
    }
    prompt.append("\n# 用户问题\n");
    prompt.append(userMessage).append("\n\n");
    prompt.append("# 输出要求\n");
    prompt.append("你必须选择角色时，输出：\n");
    prompt.append("[SELECT:角色名1,角色名2]\n");
    prompt.append("理由：...\n\n");
    prompt.append("你必须邀请用户时，输出：\n");
    prompt.append("[INVITE:你更支持谁的观点？/你怎么看这个问题？/你有什么不同看法？]\n\n");
    prompt.append("你必须总结时，输出：\n");
    prompt.append("[SUMMARY:角色A认为...；角色B认为...]\n");

    return prompt.toString();
}
```

- [ ] **Step 2: 新增 callModeratorForSelection 方法**

```java
private String callModeratorForSelection(String userMessage, List<Character> characters) {
    String prompt = buildModeratorPrompt(userMessage, characters);

    // 使用 LangChain4j 调用 LLM
    StringSystemMessage systemMessage = StringSystemMessage.from(prompt);

    String response = chatModel.generate(List.of(systemMessage)).content().text();

    return response;
}
```

- [ ] **Step 3: 修改 buildCharacterPrompt 以控制发言长度**

找到现有的 `buildCharacterPrompt` 方法，修改 `keep responses conversational and relatively brief (2-4 sentences)` 为更严格的约束：

```java
// 在 prompt 中添加
"IMPORTANT RESTRICTION: Your response MUST be exactly 2-4 sentences. No more than 4 sentences total. Be concise."
```

- [ ] **Step 4: 提交**

```bash
git add server/src/main/java/com/ideaparty/service/ModeratorAgent.java
git commit -m "feat(discussion): rewrite Moderator prompt with selection logic"
```

---

### Task 5: 扩展 ChatSocketHandler 事件处理

**Files:**
- Modify: `server/src/main/java/com/ideaparty/socket/ChatSocketHandler.java`

- [ ] **Step 1: 添加 broadcastModeratorMessage 方法**

```java
private void broadcastModeratorMessage(String roomId, String content, String type) {
    ModeratorMessage message = new ModeratorMessage(content, type);
    broadcastToRoom(roomId, "moderator-message", message);
}
```

- [ ] **Step 2: 修改 handleChatMessage 中的讨论模式处理**

找到现有的 discussion mode 处理逻辑，修改为：

```java
// 在 handleChatMessage 方法中
if (isDiscussionMode) {
    if (moderatorAgent.isDiscussionRunning(roomId)) {
        // 继续讨论时，用户插话
        moderatorAgent.handleUserInterjection(roomId, content);
    } else {
        // 启动新讨论
        moderatorAgent.processMessage(roomId, content, userId);
    }
}
```

- [ ] **Step 3: 添加 broadcastToRoom 重载方法**

```java
public void broadcastToRoom(String roomId, String event, Object data) {
    for (Map.Entry<String, SocketIOClient> entry : clientsByRoom.get(roomId).entrySet()) {
        entry.getValue().sendEvent(event, data);
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add server/src/main/java/com/ideaparty/socket/ChatSocketHandler.java
git commit -m "feat(discussion): extend ChatSocketHandler for moderator events"
```

---

### Task 6: 前端状态管理新增

**Files:**
- Modify: `client/src/stores/message.ts`

- [ ] **Step 1: 新增 discussionPhase 状态**

```typescript
// message.ts
const discussionPhase = ref<'IDLE' | 'MODERATING' | 'SPEAKING' | 'WAITING_FOR_USER' | 'PAUSED'>('IDLE')
const selectedCharacterIds = ref<string[]>([])
const moderatorMessage = ref<{ content: string; type: string } | null>(null)
```

- [ ] **Step 2: 新增状态更新方法**

```typescript
function setDiscussionPhase(phase: string, characters?: string[], message?: string) {
    discussionPhase.value = phase as any
    if (characters) {
        selectedCharacterIds.value = characters
    }
    if (message) {
        moderatorMessage.value = { content: message, type: phase }
    }
}

function clearModeratorMessage() {
    moderatorMessage.value = null
}
```

- [ ] **Step 3: 导出新状态**

```typescript
return {
    // ... existing exports
    discussionPhase,
    selectedCharacterIds,
    moderatorMessage,
    setDiscussionPhase,
    clearModeratorMessage,
}
```

- [ ] **Step 4: 提交**

```bash
git add client/src/stores/message.ts
git commit -m "feat(discussion): add discussionPhase state to message store"
```

---

### Task 7: 前端 Socket 事件处理扩展

**Files:**
- Modify: `client/src/composables/useSocket.ts`

- [ ] **Step 1: 添加新事件回调类型**

```typescript
interface UseSocketOptions {
    // ... existing fields
    onDiscussionState?: (data: { phase: string; selectedCharacters?: string[]; message?: string }) => void
    onModeratorMessage?: (data: { content: string; type: string }) => void
}
```

- [ ] **Step 2: 在 handleMessageCase 中添加事件处理**

```typescript
case 'discussion-state':
    onDiscussionState?.(eventData)
case 'moderator-message':
    onModeratorMessage?.(eventData)
```

- [ ] **Step 3: 提交**

```bash
git add client/src/composables/useSocket.ts
git commit -m "feat(discussion): extend socket events for discussion state"
```

---

### Task 8: ChatRoomPanel UI 更新

**Files:**
- Modify: `client/src/components/chat/ChatRoomPanel.vue`

- [ ] **Step 1: 添加状态指示器**

```vue
<div v-if="isDiscussionMode" class="discussion-status-bar">
    <span :class="statusClass">{{ statusText }}</span>
</div>
```

```typescript
const statusClass = computed(() => {
    switch (messageStore.discussionPhase) {
        case 'MODERATING': return 'text-blue-500'
        case 'SPEAKING': return 'text-green-500'
        case 'WAITING_FOR_USER': return 'text-yellow-500'
        case 'PAUSED': return 'text-gray-500'
        default: return 'text-gray-400'
    }
})

const statusText = computed(() => {
    switch (messageStore.discussionPhase) {
        case 'MODERATING': return '主持人分析中...'
        case 'SPEAKING': return '讨论中'
        case 'WAITING_FOR_USER': return '等待你参与'
        case 'PAUSED': return '已暂停'
        default: return ''
    }
})
```

- [ ] **Step 2: 添加 Moderator 消息样式**

```vue
<div v-if="message.senderType === 'MODERATOR'" class="moderator-message">
    <div class="flex items-center gap-2 mb-1">
        <span class="text-lg">🎤</span>
        <span class="font-semibold">主持人</span>
    </div>
    <div>{{ message.content }}</div>
</div>
```

```typescript
.moderator-message {
    @apply bg-yellow-50 border-l-4 border-yellow-400 px-4 py-3 rounded-r-lg;
}
```

- [ ] **Step 3: 集成 Socket 回调**

```typescript
const { onDiscussionState, onModeratorMessage } = useSocket()

onDiscussionState((data) => {
    messageStore.setDiscussionPhase(data.phase, data.selectedCharacters, data.message)
})

onModeratorMessage((data) => {
    messageStore.moderatorMessage = data
    // 添加 Moderator 消息到列表
    messageStore.addMessage({
        id: `mod-${Date.now()}`,
        content: data.content,
        senderType: 'MODERATOR',
        roomId: roomStore.currentRoom?.id || '',
        characterId: null,
        characterName: '主持人',
        streaming: false,
        createdAt: new Date().toISOString()
    })
})
```

- [ ] **Step 4: 提交**

```bash
git add client/src/components/chat/ChatRoomPanel.vue
git commit -m "feat(discussion): update ChatRoomPanel UI with status indicator"
```

---

### Task 9: 测试和验证

**Files:**
- Test: `server/src/test/java/com/ideaparty/service/ModeratorAgentTest.java`
- Integration: 前后端联调测试

- [ ] **Step 1: 编写状态转换测试**

```java
@Test
void testStateTransition_IDLE_to_MODERATING() {
    // Given
    String roomId = UUID.randomUUID().toString();
    String userMessage = "AI会取代程序员吗？";

    // When
    moderatorAgent.processMessage(roomId, userMessage, UUID.randomUUID());

    // Then
    verify(mockChatSocketHandler).broadcastToRoom(
        eq(roomId),
        eq("discussion-state"),
        argThat((DiscussionStateEvent e) -> e.getPhase() == DiscussionPhase.MODERATING)
    );
}
```

- [ ] **Step 2: 编写用户插话中断测试**

```java
@Test
void testUserInterruption_ClearsQueue() {
    // Given
    String roomId = UUID.randomUUID().toString();
    moderatorAgent.processMessage(roomId, "第一个问题", UUID.randomUUID());

    // When - 用户发送新消息
    moderatorAgent.handleUserInterjection(roomId, "新问题");

    // Then - 状态重置
    verify(mockChatSocketHandler).broadcastToRoom(
        eq(roomId),
        eq("discussion-state"),
        argThat((DiscussionStateEvent e) -> e.getPhase() == DiscussionPhase.MODERATING)
    );
}
```

- [ ] **Step 3: 手动测试场景**

1. 启动后端服务
2. 启动前端服务
3. 进入讨论模式房间
4. 发送消息 "AI会取代程序员吗？"
5. 验证 Moderator 选择角色
6. 验证 AI 发言不超过 3 条
7. 验证 Moderator 邀请用户参与
8. 用户发送新消息
9. 验证队列被清空，重新选择角色

- [ ] **Step 4: 提交**

```bash
git add server/src/test/java/com/ideaparty/service/ModeratorAgentTest.java
git commit -m "test(discussion): add ModeratorAgent state machine tests"
```

---

## 实施顺序

1. Task 1: 新增 DiscussionPhase 枚举和状态 DTO
2. Task 2: 重写 ModeratorAgent 状态机
3. Task 3: 实现发言限制和等待用户机制
4. Task 4: 重写 Moderator Prompt
5. Task 5: 扩展 ChatSocketHandler 事件处理
6. Task 6: 前端状态管理新增
7. Task 7: 前端 Socket 事件处理扩展
8. Task 8: ChatRoomPanel UI 更新
9. Task 9: 测试和验证

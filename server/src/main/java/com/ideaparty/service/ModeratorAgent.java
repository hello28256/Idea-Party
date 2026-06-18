package com.ideaparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.dto.DiscussionPhase;
import com.ideaparty.dto.DiscussionStateEvent;
import com.ideaparty.dto.ModeratorMessage;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.socket.ChatSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.core.context.SecurityContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import java.nio.charset.StandardCharsets;

/**
 * Moderator Agent for multi-round group discussion orchestration.
 *
 * Discussion flow:
 * 1. Round 1: All characters respond to the user's question in PARALLEL
 * 2. Round 2+: Characters comment on each other's responses, forming a debate
 * 3. After MAX_ROUNDS, discussion ends
 *
 * 为什么存在：本类把"多角色群聊"从一组孤立调用升级为有状态的多轮编排——既负责挑选本轮发言者（Moderator LLM），
 * 又负责驱动多轮对话、暂停/恢复、用户中途插入、线程归属跟踪，并通过 ChatSocketHandler 把进度推给前端。
 * 与 AIService、CharacterPromptBuilder、SettingsService、ChatSocketHandler 协作；DisposableBean 用于关闭时清理执行器。
 */
/**
 * 字段说明：
 * - executor：自定义线程池，必须继承 Spring SecurityContext；否则子线程拿不到认证，AI 调用会失败。
 * - roomFutures：按 roomId 跟踪运行中的 CompletableFuture，用于在 cancelRoom / destroy 时取消仍在飞的 LLM 调用，避免资源泄漏。
 * - roomDiscussionState：每个房间的会话级可变状态，多线程访问因此用 ConcurrentHashMap 包裹。
 * - objectMapper：仅用于把 ModeratorMessage / DiscussionStateEvent / 错误事件序列化进 Socket.IO 帧，前缀 42[...] 即 Socket.IO MESSAGE 帧格式。
 */
/**
 * 常量说明：
 * - MAX_ROUNDS：硬编码 3 轮上限，避免无终止地递归辩论造成 token 浪费；调用方可在 processMessage 通过 maxRounds 覆盖。
 * - ROUND_DELAY_MS：轮间间隔，让前端先把上一轮内容渲染完再开始下一轮；用户触发时会被忽略以保证响应即时性。
 */
@Slf4j
@Service
public class ModeratorAgent implements DisposableBean {

    private final AIService aiService;
    private final MessageRepository messageRepository;
    private final SettingsService settingsService;
    private final ChatSocketHandler chatSocketHandler;
    private final ResourceLoader resourceLoader;
    private final CharacterPromptBuilder characterPromptBuilder;
    private final CharacterRepository characterRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Maximum discussion rounds
    private static final int MAX_ROUNDS = 3;

    // Delay between rounds (milliseconds) to let responses propagate
    private static final long ROUND_DELAY_MS = 1500;

    // Executor for async operations - propagates SecurityContext to child threads
    private final ExecutorService executor;

    // Room-level futures tracking for cancellation
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    // Room-level current discussion state
    private final ConcurrentHashMap<String, DiscussionState> roomDiscussionState = new ConcurrentHashMap<>();

    // Discussion state class
    private static class DiscussionState {
        volatile boolean isRunning = false;
        volatile boolean paused = false;
        volatile int currentRound = 0;
        volatile int currentCharacterIndex = 0;
        volatile int maxRounds = 3;
        List<Character> characters = new CopyOnWriteArrayList<>();
        List<ResponseFragment> responses = new CopyOnWriteArrayList<>();
        String userMessage = "";
        String context = "";
        AtomicBoolean userTriggered = new AtomicBoolean(false);

        // New fields for state machine
        volatile DiscussionPhase phase = DiscussionPhase.IDLE;
        volatile boolean userInterjected = false;
        volatile int aiMessageCount = 0;
        volatile int maxAiMessagesPerRound = 3;
        List<Character> selectedCharacters = new CopyOnWriteArrayList<>();
        List<Character> pendingQueue = new CopyOnWriteArrayList<>();
        volatile String moderatorMessage = "";
        volatile String currentUserMessage = "";
        AtomicBoolean currentStreamCancelled = new AtomicBoolean(false);
        String userId = "";  // User ID for API key lookup
        List<String> userMessageHistory = new CopyOnWriteArrayList<>();  // Last 10 user messages

        // Thread ownership tracking - the active conversation thread agent
        volatile String activeThreadOwner = null;  // Character name who owns the current thread
        volatile long lastThreadUpdateTime = 0;    // Timestamp of last thread update
        volatile String lastTopic = "";            // Current discussion topic for continuity check
    }

    // ========== State Machine Methods ==========

    private void transitionTo(DiscussionState state, DiscussionPhase newPhase) {
        state.phase = newPhase;
        String roomId = findRoomIdByState(state);
        if (roomId != null) {
            broadcastStateChange(roomId, newPhase, state.selectedCharacters, state.moderatorMessage);
        }
    }

    private String findRoomIdByState(DiscussionState state) {
        for (Map.Entry<String, DiscussionState> entry : roomDiscussionState.entrySet()) {
            if (entry.getValue() == state) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void broadcastStateChange(String roomId, DiscussionPhase phase,
                                      List<Character> selectedCharacters, String message) {
        try {
            DiscussionStateEvent event = new DiscussionStateEvent(
                phase,
                selectedCharacters.stream().map(Character::getId).map(UUID::toString).collect(Collectors.toList()),
                message
            );
            String eventJson = objectMapper.writeValueAsString(event);
            String socketMessage = "42[\"discussion-state\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastStateChange failed: {}", e.getMessage());
        }
    }

    /**
     * 处理用户在多轮讨论中途发送的新消息。
     * 中断语义：清空待发队列、置位 userInterjected、把消息写入历史后立即触发新一轮 Moderator 选人。
     * 用户在交互中途打断时，不希望等待当前 pendingQueue 排空，所以这里是"硬重置"而不是"追加"。
     */
    public void handleUserInterjection(String roomId, String userId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        // 1. Set interrupt flag immediately
        state.userInterjected = true;

        // 2. Clear pending queue
        state.pendingQueue.clear();

        // 3. Reset AI message count
        state.aiMessageCount = 0;

        // 4. Add to user message history (keep last 10)
        state.userMessageHistory.add(userMessage);
        if (state.userMessageHistory.size() > 10) {
            state.userMessageHistory.remove(0);
        }

        // 5. Transition to MODERATING
        transitionTo(state, DiscussionPhase.MODERATING);
        state.currentUserMessage = userMessage;
        state.userMessage = userMessage;  // Also set userMessage for generateCharacterResponse
        state.userId = userId;

        // 6. Immediately start new Moderator analysis
        processModeratorAnalysis(roomId, userMessage);
    }

    /**
     * Update the active thread owner after a character responds.
     * This maintains conversation continuity by tracking who the user is currently talking to.
     */
    private void updateActiveThread(String roomId, DiscussionState state, String characterName) {
        if (state == null || characterName == null) return;

        long now = System.currentTimeMillis();
        state.activeThreadOwner = characterName;
        state.lastThreadUpdateTime = now;

        log.info("[Moderator] Thread updated for room {}: active owner = {}, timestamp = {}",
            roomId, characterName, now);
    }

    /**
     * Check if the current user message is likely continuing the same thread.
     * Returns true if the message should be routed to the active thread owner.
     */
    private boolean isContinuingThread(DiscussionState state, String userMessage, String selectedAgentName) {
        if (state == null || state.activeThreadOwner == null) {
            return false;
        }

        // If user explicitly mentioned a different agent, don't force thread continuity
        if (selectedAgentName != null && !selectedAgentName.equals(state.activeThreadOwner)) {
            return false;
        }

        // Short contextual messages strongly suggest thread continuation
        if (isShortContextualMessage(userMessage)) {
            return true;
        }

        // If message is very short and thread was recently active (within 2 minutes)
        long twoMinutesAgo = System.currentTimeMillis() - 120000;
        if (userMessage.trim().length() < 20 && state.lastThreadUpdateTime > twoMinutesAgo) {
            return true;
        }

        return false;
    }

    private boolean isShortContextualMessage(String message) {
        if (message == null || message.isBlank()) return false;
        String trimmed = message.trim();
        if (trimmed.length() < 10) return true;

        String[] phrases = {"继续", "为什么", "展开", "说说", "然后呢", "有意思", "有道理", "同意", "对", "没错", "不是", "我同意", "我不同意", "嗯", "哈哈"};
        for (String phrase : phrases) {
            if (trimmed.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * 调 Moderator LLM 选本轮发言者，并把选人结果广播到房间。
     * 三层兜底：LLM 返回 JSON 时按 JSON 解析；JSON 解析失败回退到 [SELECT:xxx] 正则；选不到人再回退到第一个角色。
     * 线程归属：当语义匹配只命中一人且属于"延续性短句"，优先复用 activeThreadOwner 避免在多角色场景下被错误切线。
     */
    private void processModeratorAnalysis(String roomId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        List<Character> availableCharacters = state.characters;

        if (availableCharacters == null || availableCharacters.isEmpty()) {
            log.warn("[Moderator] No characters available for room: {}", roomId);
            return;
        }

        // Call LLM to select characters (with user message history context)
        String selection = callModeratorForSelection(state.userId, userMessage, state.userMessageHistory, availableCharacters);
        log.info("[Moderator] LLM selection result: {}", selection);

        // Parse JSON format response
        List<Character> selected = parseModeratorResponse(selection, availableCharacters);

        // Fallback if no characters matched
        if (selected.isEmpty()) {
            selected = availableCharacters.subList(0, Math.min(1, availableCharacters.size()));
        }

        // Thread continuity check: if message is likely continuing same thread, prefer active thread owner
        if (selected.size() == 1 && isContinuingThread(state, userMessage, selected.get(0).getName())) {
            String activeOwner = state.activeThreadOwner;
            if (activeOwner != null) {
                Character threadOwner = availableCharacters.stream()
                    .filter(c -> c.getName().equals(activeOwner))
                    .findFirst()
                    .orElse(null);
                if (threadOwner != null && !selected.contains(threadOwner)) {
                    log.info("[Moderator] Thread continuity: routing to active thread owner {} instead of semantic match", activeOwner);
                    selected = new ArrayList<>();
                    selected.add(threadOwner);
                }
            }
        }

        state.selectedCharacters = new ArrayList<>(selected);
        state.pendingQueue = new ArrayList<>(selected);

        // Broadcast selection
        String selectMsg = "正在邀请: " + selected.stream().map(Character::getName).collect(Collectors.joining(", "));
        state.moderatorMessage = selectMsg;
        broadcastModeratorMessage(roomId, selectMsg, "SELECT");

        // Transition to SPEAKING
        transitionTo(state, DiscussionPhase.SPEAKING);

        // Start speaking process
        processNextInQueue(roomId);
    }

    /**
     * Parse moderator LLM response in JSON format.
     */
    private List<Character> parseModeratorResponse(String response, List<Character> availableCharacters) {
        List<Character> selected = new ArrayList<>();
        try {
            // Try to parse as JSON
            Map<String, Object> json = objectMapper.readValue(response, Map.class);
            String messageType = (String) json.get("message_type");
            List<String> targetAgents = (List<String>) json.get("target_agents");
            String confidence = (String) json.get("confidence");

            log.info("[Moderator] Parsed JSON - type: {}, confidence: {}, targets: {}",
                messageType, confidence, targetAgents);

            // Handle broadcast - all characters speak
            if ("broadcast".equals(messageType)) {
                log.info("[Moderator] Broadcast requested - all characters will speak");
                selected.addAll(availableCharacters);
                return selected;
            }

            // Handle invite - no characters selected, will be handled as INVITE
            if ("invite".equals(messageType)) {
                log.info("[Moderator] INVITE requested - no character selection");
                return selected;
            }

            // Handle direct_reply or multi_target_reply
            if (targetAgents != null) {
                for (String agentName : targetAgents) {
                    final String trimmedName = agentName.trim();
                    Character found = availableCharacters.stream()
                        .filter(c -> c.getName().trim().equals(trimmedName))
                        .findFirst()
                        .orElse(null);
                    if (found != null && !selected.contains(found)) {
                        selected.add(found);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Moderator] Failed to parse JSON response, trying regex fallback: {}", e.getMessage());
            // Fallback to old [SELECT:xxx] format
            Pattern pattern = Pattern.compile("\\[SELECT:([^\\]]+)\\]");
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String[] selectedNames = matcher.group(1).split(",");
                for (String name : selectedNames) {
                    final String trimmedName = name.trim();
                    Character found = availableCharacters.stream()
                        .filter(c -> c.getName().trim().equals(trimmedName))
                        .findFirst()
                        .orElse(null);
                    if (found != null && !selected.contains(found)) {
                        selected.add(found);
                    }
                }
            }
        }
        return selected;
    }

    private void processNextInQueue(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null || !state.isRunning) return;

        // Check if should wait for user
        if (shouldWaitForUser(state)) {
            waitForUserInput(roomId);
            return;
        }

        // Get next character from queue - synchronized for thread safety
        synchronized (state.pendingQueue) {
            if (!state.pendingQueue.isEmpty()) {
                Character character = state.pendingQueue.remove(0);
                generateCharacterResponse(roomId, character, state, state.userId, state.currentUserMessage, state.context);
            }
        }
    }

    private boolean shouldWaitForUser(DiscussionState state) {
        if (state.aiMessageCount >= state.maxAiMessagesPerRound) {
            return true;
        }
        if (state.pendingQueue.isEmpty() && state.selectedCharacters.isEmpty()) {
            return true;
        }
        return false;
    }

    private void waitForUserInput(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        transitionTo(state, DiscussionPhase.WAITING_FOR_USER);

        String inviteMessage = generateModeratorInvite(state);
        state.moderatorMessage = inviteMessage;
        broadcastModeratorMessage(roomId, inviteMessage, "INVITE");
    }

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

        Random random = new Random();
        return invites[random.nextInt(invites.length)];
    }

    private void broadcastModeratorMessage(String roomId, String content, String type) {
        try {
            ModeratorMessage message = new ModeratorMessage(content, type);
            String eventJson = objectMapper.writeValueAsString(message);
            String socketMessage = "42[\"moderator-message\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastModeratorMessage failed: {}", e.getMessage());
        }
    }

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository, SettingsService settingsService, @Lazy ChatSocketHandler chatSocketHandler, ResourceLoader resourceLoader, CharacterPromptBuilder characterPromptBuilder, CharacterRepository characterRepository, RoomRepository roomRepository, UserRepository userRepository) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
        this.settingsService = settingsService;
        this.chatSocketHandler = chatSocketHandler;
        this.resourceLoader = resourceLoader;
        this.characterPromptBuilder = characterPromptBuilder;
        this.characterRepository = characterRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        // Wrap executor so SecurityContext is inherited by async threads
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            // Inherit SecurityContext from the thread that submits the task
            SecurityContext ctx = SecurityContextHolder.getContext();
            t.setContextClassLoader(null);
            return new Thread(ctx != null ? new SecurityContextAwareThread(ctx, t) : t);
        });
    }

    // Wraps a Thread to set SecurityContext before run()
    private static class SecurityContextAwareThread extends Thread {
        private final SecurityContext context;

        SecurityContextAwareThread(SecurityContext context, Thread delegate) {
            super(delegate);
            this.context = context;
        }

        @Override
        public void run() {
            try {
                SecurityContextHolder.setContext(context);
                SecurityContextHolder.getContext().setAuthentication(context.getAuthentication());
                super.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * Process a user message and orchestrate AI character discussion.
     *
     * @param roomId The room ID
     * @param userId The user ID (passed explicitly to avoid SecurityContext threading issues)
     * @param userMessage The user's message
     * @param characters List of characters in the room
     * @param isContinuous If true, run multiple rounds (discussion mode); if false, single round (dialogue mode)
     * @param maxRounds Maximum rounds for continuous mode
     * @param onThinking Callback for "thinking" state
     * @param onChunk Callback for streaming chunks (called as content is generated)
     * @param onResponse Callback for each character's complete response
     */
    /**
     * WebSocket / 控制器层入口。根据 isContinuous 路由到"联合多轮讨论"或"单轮对话"两条路径。
     * 关键前置：先校验 API Key——这是 chatSocketHandler 调用方常常遗漏的失败点，提前通过 onError 上报，
     * 避免前端停留在 thinking 状态却没有任何回应。
     */
    public void processMessage(String roomId, String userId, String userMessage, List<Character> characters,
                               boolean isContinuous, int maxRounds,
                               Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                               Consumer<ResponseFragment> onResponse,
                               Consumer<ModeratorError> onError) {
        if (characters == null || characters.isEmpty()) {
            return;
        }

        // Pre-check API key so we can notify the user instead of silently doing nothing.
        // Both dialogue and discussion modes ultimately call runJointSingleRound which
        // would otherwise return without telling the frontend why nothing happened.
        String userApiKey = settingsService.getApiKeyById(userId);
        if (userApiKey == null || userApiKey.isBlank()) {
            log.warn("[Moderator] processMessage - no API key for userId: {}", userId);
            if (onError != null) {
                onError.accept(new ModeratorError(
                    ModeratorError.Code.MISSING_API_KEY,
                    "请先在「设置」中配置 LLM API Key 后再发送消息"
                ));
            }
            return;
        }

        log.info("[Moderator] processMessage - roomId: {}, userId: {}, charCount: {}, isContinuous: {}",
            roomId, userId, characters.size(), isContinuous);

        // Build initial context with user message
        String initialContext = buildInitialContext(userMessage);

        // Track responses for each round
        Map<Integer, List<ResponseFragment>> roundResponses = new ConcurrentHashMap<>();

        try {
            if (isContinuous) {
                // Discussion mode: sequential turn-based discussion
                // Each round uses ONE joint LLM call so all characters in a round
                // come from the same inference (cheaper, smarter cross-character context).
                runJointDiscussion(roomId, userId, userMessage, characters, maxRounds,
                    initialContext, onThinking, onChunk, onResponse);
            } else {
                // Dialogue mode: a single joint round — moderator picks 1..N speakers
                // and we stream each speaker's reply in order from one inference.
                runJointSingleRound(roomId, userId, userMessage, initialContext, characters,
                    onChunk, onResponse);
            }
        } catch (Exception e) {
            log.error("[Moderator] processMessage caught exception: {}", e.getMessage(), e);
            if (onError != null) {
                onError.accept(new ModeratorError(
                    ModeratorError.Code.LLM_ERROR,
                    "AI 服务调用失败: " + e.getMessage()
                ));
            }
        }
    }

    /** Structured error passed to the WebSocket layer so the frontend can react. */
    public static class ModeratorError {
        public enum Code { MISSING_API_KEY, LLM_ERROR }

        private final Code code;
        private final String message;

        public ModeratorError(Code code, String message) {
            this.code = code;
            this.message = message;
        }

        public Code getCode() { return code; }
        public String getMessage() { return message; }
    }

    // ================== JOINT (single-LLM-call) FLOW ==================

    /**
     * 联合推理：把整轮（多角色依次发言）打包成一次 LLM 调用，让所有角色从同一个上下文中生成。
     * 比每个角色各调一次更省 token，且 LLM 能看到全角色视角实现相互引用。
     * 副作用：通过 JointStreamParser 增量派发 onChunk/onResponse，并通过 chatSocketHandler 广播错误事件。
     */
    private void runJointSingleRound(String roomId, String userId, String userMessage, String context,
                                     List<Character> characters,
                                     Consumer<ResponseFragment> onChunk,
                                     Consumer<ResponseFragment> onResponse) {
        log.info("[Moderator] runJointSingleRound - roomId: {}, userId: {}, charCount: {}",
            roomId, userId, characters.size());

        String userApiKey = settingsService.getApiKeyById(userId);
        if (userApiKey == null || userApiKey.isBlank()) {
            log.error("[Moderator] runJointSingleRound - no API key for userId: {}", userId);
            return;
        }

        // Load last 10 messages for the prompt
        String conversationHistory = loadRecentHistory(roomId, 100);
        String prompt = buildJointPrompt(userMessage, conversationHistory, context, characters);
        log.info("[Moderator] runJointSingleRound - prompt length: {}", prompt.length());

        JointStreamParser parser = new JointStreamParser(characters, onChunk, onResponse);

        StringBuilder accumulated = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean settled = new AtomicBoolean(false);

        aiService.generateResponseStream(
            prompt,
            userMessage,
            userApiKey,
            chunk -> {
                accumulated.append(chunk);
                try {
                    parser.onChunk(chunk);
                } catch (Exception e) {
                    log.warn("[Moderator] joint parser.onChunk failed: {}", e.getMessage());
                }
            },
            complete -> {
                if (!settled.compareAndSet(false, true)) return;
                try {
                    parser.flush(accumulated.toString());
                } catch (Exception e) {
                    log.warn("[Moderator] joint parser.flush failed: {}", e.getMessage());
                }
                latch.countDown();
            },
            error -> {
                if (!settled.compareAndSet(false, true)) return;
                log.error("[Moderator] runJointSingleRound - LLM error: {}", error.getMessage());
                try {
                    String errorEvent = "42[\"error\","
                        + objectMapper.writeValueAsString(Map.of("message", error.getMessage()))
                        + "]";
                    chatSocketHandler.broadcastToRoom(roomId, errorEvent);
                } catch (Exception e) {
                    log.warn("[Moderator] joint error broadcast failed: {}", e.getMessage());
                }
                latch.countDown();
            }
        );

        try {
            boolean done = latch.await(120, TimeUnit.SECONDS);
            if (!done) {
                log.warn("[Moderator] runJointSingleRound - 120s timeout, flushing");
                parser.flush(accumulated.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 联合多轮讨论：每轮调一次 runJointSingleRound，把上一轮所有发言塞进下一轮的 prompt 实现"互相反驳"。
     * 使用 capturingOnResponse 把每段发言累积到 state.responses——下一轮 buildRoundContext 要从这里读。
     * state.responses 在 round>1 时清空，避免上一轮残留污染本轮 context；本轮快照在调用后留作"上一轮"传入下轮。
     */
    private void runJointDiscussion(String roomId, String userId, String userMessage, List<Character> characters,
                                    int maxRounds, String initialContext,
                                    Consumer<String> onThinking,
                                    Consumer<ResponseFragment> onChunk,
                                    Consumer<ResponseFragment> onResponse) {
        log.info("[Moderator] runJointDiscussion START - roomId: {}, charCount: {}, maxRounds: {}",
            roomId, characters.size(), maxRounds);

        DiscussionState state = new DiscussionState();
        state.characters = new ArrayList<>(characters);
        state.userMessage = userMessage;
        state.currentUserMessage = userMessage;
        state.context = initialContext;
        state.currentRound = 1;
        state.maxRounds = maxRounds;
        state.isRunning = true;
        state.paused = false;
        state.userId = userId;
        roomDiscussionState.put(roomId, state);

        // 把 future 注册到 roomFutures：cancelRoom 才能真正中断仍在飞的 LLM 调用，
        // 否则仅设置 isRunning=false，但 runJointSingleRound 内部的 latch.await 会一直等待上游 SSE。
        CompletableFuture<Void> discussionFuture = CompletableFuture.runAsync(() -> {
            try {
                while (state.isRunning && state.currentRound <= state.maxRounds) {
                    // Wait for pause
                    while (state.paused && state.isRunning) {
                        Thread.sleep(500);
                    }
                    if (!state.isRunning) break;

                    // Inter-round delay 1.5s
                    for (int i = 0; i < 3; i++) {
                        if (!state.isRunning) break;
                        if (state.userTriggered.get()) {
                            state.userTriggered.set(false);
                            break;
                        }
                        Thread.sleep(500);
                    }
                    if (!state.isRunning) break;

                    log.info("[Moderator] [Joint Round {}/{}] starting", state.currentRound, state.maxRounds);

                    // Build the round context that includes prior round responses
                    String roundContext = buildRoundContext(
                        state.context, state.responses, state.currentRound - 1);

                    // Wrap onResponse so each completed speaker block is captured into
                    // state.responses — the next round's buildRoundContext needs this
                    // to feed the previous round's output back to the LLM. Without
                    // this, round 2+ have no idea what round 1 said and just ramble.
                    final int roundBeingRun = state.currentRound;
                    Consumer<ResponseFragment> capturingOnResponse = fragment -> {
                        // Capture for next round's context, then forward to caller.
                        state.responses.add(fragment);
                        onResponse.accept(fragment);
                    };

                    // For round > 1, clear stale responses from the prior round so the
                    // context section only shows what we want the LLM to react to.
                    if (state.currentRound > 1) {
                        state.responses.clear();
                    }

                    runJointSingleRound(roomId, userId, userMessage, roundContext, characters,
                        onChunk, capturingOnResponse);

                    // Snapshot this round's responses for the next iteration
                    List<ResponseFragment> thisRound = new ArrayList<>(state.responses);

                    state.currentRound++;
                    if (!state.isRunning) break;
                }

                log.info("[Moderator] Discussion ended - rounds: {}", state.currentRound - 1);
                state.isRunning = false;
                roomDiscussionState.remove(roomId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.isRunning = false;
            } catch (Exception e) {
                log.error("[Moderator] runJointDiscussion error: {}", e.getMessage(), e);
                state.isRunning = false;
            }
        }, executor);

        // 注册到 roomFutures 让 cancelRoom 能找到并取消；自然结束后自动清理
        roomFutures.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(discussionFuture);
        discussionFuture.whenComplete((r, e) -> {
            List<CompletableFuture<?>> list = roomFutures.get(roomId);
            if (list != null) {
                list.remove(discussionFuture);
                if (list.isEmpty()) {
                    roomFutures.remove(roomId, list);
                }
            }
        });
    }

    /**
     * Build the joint prompt by loading the moderator-joint-prompt.txt template and
     * filling in character roster, recent history, and user message.
     */
    private String buildJointPrompt(String userMessage, String conversationHistory,
                                    String context, List<Character> characters) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/moderator-joint-prompt.txt");
            String template = resource.getContentAsString(StandardCharsets.UTF_8);

            StringBuilder charactersList = new StringBuilder();
            for (Character c : characters) {
                charactersList.append("- 角色名：**").append(c.getName()).append("**\n");
                if (c.getPersona() != null && !c.getPersona().isBlank()) {
                    charactersList.append("  人设：").append(c.getPersona()).append("\n");
                }
                if (c.getExpertise() != null && !c.getExpertise().isEmpty()) {
                    charactersList.append("  专长：").append(String.join("、", c.getExpertise())).append("\n");
                }
                if (c.getPrompt() != null && !c.getPrompt().isBlank()) {
                    // Include the first 800 chars of the character prompt as flavor
                    String snippet = c.getPrompt();
                    if (snippet.length() > 800) snippet = snippet.substring(0, 800) + "...";
                    charactersList.append("  设定摘录：").append(snippet).append("\n");
                }
                charactersList.append("\n");
            }

            String historySection = (conversationHistory == null || conversationHistory.isBlank())
                ? "（暂无历史对话）"
                : conversationHistory;

            return template
                .replace("{characters}", charactersList.toString())
                .replace("{conversationHistory}", historySection)
                .replace("{userMessage}", userMessage == null ? "" : userMessage);
        } catch (Exception e) {
            log.error("[Moderator] Failed to load joint prompt template: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Load recent N messages from the room as a formatted history string.
     * Avoids lazy Character access by pre-fetching character names in a single
     * findAllById round-trip. Self-invocation from runJointSingleRound would
     * otherwise bypass the @Transactional proxy, so we do not rely on it here.
     */
    /**
     * 预取最近 N 条消息的角色名：一次性 findAllById 解决懒加载代理问题，避免在序列化时触发 LazyInitializationException。
     * 不通过 this 内部调用同名 @Transactional 方法——会绕过 Spring 代理导致事务失效。
     */
    public String loadRecentHistory(String roomId, int limit) {
        try {
            UUID roomUuid = UUID.fromString(roomId);
            List<Message> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomUuid);
            if (messages.isEmpty()) return "";

            // Collect all character ids referenced by CHARACTER messages
            Set<UUID> charIds = new HashSet<>();
            for (Message m : messages) {
                if (m.getCharacter() != null) {
                    charIds.add(m.getCharacter().getId());
                }
            }

            // Resolve names in one round-trip (eager load — no lazy proxy issues)
            Map<UUID, String> nameById = new HashMap<>();
            if (!charIds.isEmpty()) {
                for (Character c : characterRepository.findAllById(charIds)) {
                    nameById.put(c.getId(), c.getName());
                }
            }

            int from = Math.max(0, messages.size() - limit);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.getSenderType() == Message.SenderType.USER) {
                    sb.append("User: ").append(m.getContent()).append("\n");
                } else {
                    String name = "Character";
                    if (m.getCharacter() != null) {
                        name = nameById.getOrDefault(m.getCharacter().getId(), "Character");
                    }
                    sb.append(name).append(": ").append(m.getContent()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Moderator] loadRecentHistory failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Incremental parser for the joint LLM stream. Output protocol from the LLM:
     *
     *   [角色名]: 台词内容（可换行）
     *   <<<END>>>
     *   [另一角色]: ...
     *   <<<END>>>
     *
     * For each complete block:
     *  - onChunk(fragment, isComplete=false) is called with the full content
     *    (so the frontend sees the speaker's text appear when the turn ends)
     *  - onResponse(fragment, isComplete=true) is called so the orchestrator
     *    can persist and broadcast
     */
    private static class JointStreamParser {
        private static final String END_MARKER = "<<<END>>>";
        private static final Pattern BLOCK = Pattern.compile(
            "\\[([^\\]]+)\\]:\\s*([\\s\\S]*?)<<<END>>>");

        private final Map<String, Character> byName;
        private final List<String> knownNames;
        private final Consumer<ResponseFragment> onChunk;
        private final Consumer<ResponseFragment> onResponse;
        private final StringBuilder buffer = new StringBuilder();
        private int consumed = 0;
        private final Set<String> emittedSpeakers = new HashSet<>();

        JointStreamParser(List<Character> characters,
                          Consumer<ResponseFragment> onChunk,
                          Consumer<ResponseFragment> onResponse) {
            this.byName = new HashMap<>();
            this.knownNames = new ArrayList<>();
            for (Character c : characters) {
                String key = c.getName().trim().toLowerCase();
                byName.put(key, c);
                knownNames.add(c.getName());
            }
            this.onChunk = onChunk;
            this.onResponse = onResponse;
        }

        void onChunk(String chunk) {
            buffer.append(chunk);
            parseNewBlocks();
        }

        void flush(String finalText) {
            // Make sure the buffer contains whatever the LLM produced in full
            if (buffer.length() < finalText.length()) {
                buffer.append(finalText.substring(buffer.length()));
            }
            parseNewBlocks();
            // Anything left that looks like an unfinished block — drop with a warning
            if (consumed < buffer.length()) {
                String leftover = buffer.substring(consumed).trim();
                if (!leftover.isEmpty()) {
                    log.warn("[Moderator] joint stream ended with unterminated block: {}",
                        leftover.substring(0, Math.min(120, leftover.length())));
                }
            }
        }

        private void parseNewBlocks() {
            while (true) {
                Matcher m = BLOCK.matcher(buffer);
                m.region(consumed, buffer.length());
                if (!m.find()) break;
                String rawName = m.group(1).trim();
                String content = m.group(2).trim();
                Character c = byName.get(rawName.toLowerCase());
                if (c == null) {
                    log.warn("[Moderator] joint stream emitted unknown speaker '{}' — skipping block", rawName);
                    consumed = m.end();
                    continue;
                }
                if (emittedSpeakers.contains(c.getId().toString())) {
                    // Same speaker appears twice in one joint response — keep the first
                    log.warn("[Moderator] joint stream repeated speaker '{}' — keeping first block", rawName);
                    consumed = m.end();
                    continue;
                }
                emit(c, content);
                emittedSpeakers.add(c.getId().toString());
                consumed = m.end();
            }
            // Trim consumed prefix periodically to keep the buffer small
            if (consumed > 4096) {
                buffer.delete(0, consumed);
                consumed = 0;
            }
        }

        private void emit(Character c, String content) {
            if (content == null || content.isEmpty()) {
                log.info("[Moderator] joint stream: speaker {} emitted empty block, skipped", c.getName());
                return;
            }
            String charId = c.getId().toString();
            // Stream chunk: the user sees the text appear as the turn ends
            onChunk.accept(new ResponseFragment(
                charId, c.getName(), content, false, c.getAvatarUrl()));
            // Complete: orchestrator persists + broadcasts
            onResponse.accept(new ResponseFragment(
                charId, c.getName(), content, true, c.getAvatarUrl()));
        }
    }

    /**
     * Run sequential turn-based discussion mode.
     * Characters take turns speaking one by one with random delays between turns.
     * Supports pause/resume and user message triggering.
     */
    /**
     * 顺序讨论（每角色单独调一次 LLM 的旧路径，目前主要保留作 fallback）。
     * 与 runJointDiscussion 的关键差异：每个角色独立 prompt，无法看到彼此视角，代价更高但隔离性更好；
     * 由随机 1-3s 间隔模拟"真人发言节奏"，同时 userTriggered 允许用户中途插队。
     */
    private void runSequentialDiscussion(String roomId, String userId, DiscussionState state,
                                         Consumer<String> onThinking) {
        log.info("[Moderator] runSequentialDiscussion START - roomId: {}, chars: {}, rounds: {}",
            roomId, state.characters.size(), state.maxRounds);

        // Start the discussion loop in a background thread
        CompletableFuture.runAsync(() -> {
            try {
                while (state.isRunning && state.currentRound <= state.maxRounds) {
                    Character character = state.characters.get(state.currentCharacterIndex);

                    // Wait for pause - check every 500ms
                    while (state.paused && state.isRunning) {
                        log.info("[Moderator] Discussion paused, waiting...");
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Random delay 1-3 seconds before this character speaks
                    int delaySeconds = 1 + (int) (Math.random() * 2);
                    log.info("[Moderator] [Round {}] Waiting {}s before {} speaks",
                        state.currentRound, delaySeconds, character.getName());

                    for (int i = 0; i < delaySeconds * 2; i++) {
                        if (!state.isRunning) break;
                        if (state.userTriggered.get()) {
                            log.info("[Moderator] User triggered, skipping delay");
                            state.userTriggered.set(false);
                            break;
                        }
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Character speaks (blocking stream)
                    log.info("[Moderator] [Round {}] {} is now speaking", state.currentRound, character.getName());
                    generateCharacterResponse(roomId, character, state, state.userId, state.userMessage, state.context);

                    // Move to next character
                    state.currentCharacterIndex++;

                    // If all characters have spoken this round
                    if (state.currentCharacterIndex >= state.characters.size()) {
                        state.currentCharacterIndex = 0;
                        state.currentRound++;
                        state.context = buildRoundContext(state.context, state.responses, state.currentRound - 1);
                        log.info("[Moderator] Completed round {}, moving to round {}",
                            state.currentRound - 1, state.currentRound);
                    }

                    if (!state.isRunning) break;
                }

                log.info("[Moderator] Discussion ended - rounds completed: {}", state.currentRound - 1);
                state.isRunning = false;
                roomDiscussionState.remove(roomId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[Moderator] Discussion interrupted");
                state.isRunning = false;
            } catch (Exception e) {
                log.error("[Moderator] Discussion error: {}", e.getMessage(), e);
                state.isRunning = false;
            }
        }, executor);
    }

    /**
     * Generate response for a single character with streaming.
     * Merged overload: internally fetches API key, broadcasts canonical events
     * (message stream / chat message / error) directly, and persists the
     * complete message via messageRepository.save.
     */
    /**
     * 单角色阻塞式生成 + 直接广播/落库（合并重载）。上层调用方不再需要自己处理 Socket 推送和持久化。
     * 关键并发守卫：settled AtomicBoolean 防止 latch 超时与 onComplete/onError 回调同时触发，导致消息被广播两次、落库两次。
     * context 已由调用方嵌入用户问题；这里不再追加，避免与 discussion 路径重复。
     */
    private void generateCharacterResponse(String roomId, Character character, DiscussionState state,
                                           String userId, String userMessage, String context) {
        String userApiKey = settingsService.getApiKeyById(userId);
        if (userApiKey == null) {
            log.error("[Moderator] [{}] No API key available for user: {}", character.getName(), userId);
            return;
        }

        try {
            String characterPrompt = characterPromptBuilder.build(character, true);
            // Note: in discussion mode, `context` already contains the user's question
            // (see buildInitialContext L976 which embeds "The user has asked: ...").
            // In dialogue mode (runSingleRound), `context` is the raw conversation history
            // and does not embed the question. We only need the trailing line when context
            // does not already include it — but to keep both paths simple, the caller is
            // expected to embed the question in context. So we don't append it here.
            String fullPrompt = characterPrompt + "\n\n" + context;

            log.info("[Moderator] [{}] Prompt length: {}", character.getName(), fullPrompt.length());

            StringBuilder fullResponse = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            // Guard against the race where latch.await times out but the AI stream
            // completes shortly after — without this, the same response would be
            // persisted to DB twice and broadcast to the frontend twice.
            AtomicBoolean settled = new AtomicBoolean(false);

            aiService.generateResponseStream(
                fullPrompt,
                userMessage,
                userApiKey,
                chunk -> {
                    fullResponse.append(chunk);
                    try {
                        // Stream chunk to frontend via canonical 'message stream' event
                        Map<String, Object> chunkData = new java.util.HashMap<>();
                        chunkData.put("content", chunk);
                        chunkData.put("senderType", "CHARACTER");
                        chunkData.put("characterId", character.getId().toString());
                        chunkData.put("characterName", character.getName());
                        chunkData.put("roomId", roomId);
                        chunkData.put("avatarUrl", character.getAvatarUrl());
                        chunkData.put("streaming", true);
                        String chunkEvent = "42[\"message stream\","
                            + objectMapper.writeValueAsString(chunkData)
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, chunkEvent);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] chunk broadcast failed: {}", character.getName(), e.getMessage());
                    }
                },
                completeResponse -> {
                    if (!settled.compareAndSet(false, true)) {
                        latch.countDown();
                        return;
                    }
                    String responseText = fullResponse.toString();

                    // Persist message to DB
                    try {
                        Message savedMessage = persistCharacterMessage(roomId, character, userId, responseText);
                        state.responses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true,
                            character.getAvatarUrl()
                        ));
                        log.info("[Moderator] [{}] Complete - length: {}, saved id: {}",
                            character.getName(), responseText.length(), savedMessage.getId());

                        // Broadcast complete response via canonical 'chat message' event
                        Map<String, Object> responseData = new java.util.HashMap<>();
                        responseData.put("content", responseText);
                        responseData.put("senderType", "CHARACTER");
                        responseData.put("characterId", character.getId().toString());
                        responseData.put("characterName", character.getName());
                        responseData.put("avatarUrl", character.getAvatarUrl());
                        responseData.put("roomId", roomId);
                        responseData.put("id", savedMessage.getId());
                        responseData.put("streaming", false);
                        String responseEvent = "42[\"chat message\","
                            + objectMapper.writeValueAsString(responseData)
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, responseEvent);
                    } catch (Exception e) {
                        log.error("[Moderator] [{}] persist/broadcast complete failed: {}",
                            character.getName(), e.getMessage());
                    }
                    latch.countDown();
                },
                error -> {
                    if (!settled.compareAndSet(false, true)) {
                        latch.countDown();
                        return;
                    }
                    log.error("[Moderator] [{}] Error: {}", character.getName(), error.getMessage());
                    state.responses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        "Error: " + error.getMessage(),
                        true,
                        character.getAvatarUrl()
                    ));
                    // Broadcast error to frontend
                    try {
                        String errorEvent = "42[\"error\","
                            + objectMapper.writeValueAsString(Map.of("message", error.getMessage()))
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, errorEvent);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] error broadcast failed: {}", character.getName(), e.getMessage());
                    }
                    latch.countDown();
                }
            );

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[Moderator] [{}] Timeout", character.getName());
                if (!settled.compareAndSet(false, true)) {
                    // onComplete or onError already won the race while we were waiting
                } else {
                    String responseText = fullResponse.length() > 0 ? fullResponse.toString() : "Error: Response timed out (90s)";
                    try {
                        Message savedMessage = persistCharacterMessage(roomId, character, userId, responseText);
                        Map<String, Object> responseData = new java.util.HashMap<>();
                        responseData.put("content", responseText);
                        responseData.put("senderType", "CHARACTER");
                        responseData.put("characterId", character.getId().toString());
                        responseData.put("characterName", character.getName());
                        responseData.put("avatarUrl", character.getAvatarUrl());
                        responseData.put("roomId", roomId);
                        responseData.put("id", savedMessage.getId());
                        responseData.put("streaming", false);
                        String responseEvent = "42[\"chat message\","
                            + objectMapper.writeValueAsString(responseData)
                            + "]";
                        chatSocketHandler.broadcastToRoom(roomId, responseEvent);
                    } catch (Exception e) {
                        log.error("[Moderator] [{}] timeout persist/broadcast failed: {}",
                            character.getName(), e.getMessage());
                    }
                    state.responses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true,
                        character.getAvatarUrl()
                    ));
                }
            }

            // State machine: after completion, process next in queue
            state.aiMessageCount++;
            synchronized (state.pendingQueue) {
                if (shouldWaitForUser(state)) {
                    waitForUserInput(roomId);
                } else {
                    processNextInQueue(roomId);
                }
            }

        } catch (Exception e) {
            log.error("[Moderator] [{}] generateCharacterResponse failed: {}", character.getName(), e.getMessage());
        }
    }

    /**
     * Persist a CHARACTER message to the DB. Looks up Character/Room/User entities
     * by id, populates the Message, and returns the saved entity (with id).
     */
    private Message persistCharacterMessage(String roomId, Character character, String userId, String content) {
        UUID characterUuid = character.getId();
        UUID roomUuid = UUID.fromString(roomId);
        Character characterEntity = characterRepository.findById(characterUuid).orElse(character);
        Room roomEntity = roomRepository.findById(roomUuid).orElse(null);
        User userEntity = (userId != null && !userId.isEmpty())
            ? userRepository.findById(UUID.fromString(userId)).orElse(null)
            : null;

        Message message = new Message();
        message.setContent(content);
        message.setSenderType(Message.SenderType.CHARACTER);
        message.setCharacter(characterEntity);
        message.setRoom(roomEntity);
        message.setUser(userEntity);
        return messageRepository.save(message);
    }

    /**
     * Pause discussion for a room.
     */
    public void pauseDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = true;
            log.info("[Moderator] Discussion paused for room: {}", roomId);
        }
    }

    /**
     * Resume discussion for a room.
     */
    /**
     * 恢复 + 触发即时继续：通过 userTriggered=true 让轮间循环立刻跳出等待，不必等满 ROUND_DELAY_MS。
     * 这是"用户期待响应即时"的权衡——恢复时不再维持原有的延迟节奏。
     */
    public void resumeDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = false;
            state.userTriggered.set(true); // Trigger immediate continuation
            log.info("[Moderator] Discussion resumed for room: {}", roomId);
        }
    }

    /**
     * Trigger discussion to continue when user sends a message.
     */
    public void triggerUserMessage(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null && state.isRunning) {
            state.userTriggered.set(true);
            // Also resume if paused
            if (state.paused) {
                state.paused = false;
            }
            log.info("[Moderator] User message triggered discussion for room: {}", roomId);
        }
    }

    /**
     * Stop discussion for a room.
     */
    public void stopDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.remove(roomId);
        if (state != null) {
            state.isRunning = false;
            log.info("[Moderator] Discussion stopped for room: {}", roomId);
        }
    }

    /**
     * Check if a discussion is currently running for a room.
     */
    public boolean isDiscussionRunning(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        return state != null && state.isRunning;
    }

    /**
     * Run a single dialogue round (dialogue mode).
     */
    /**
     * 单角色顺序对话模式（非联合推理的旧路径）。同步阻塞等 latch，角色之间随机 1-3s 间隔。
     * 适用场景：联合推理暂不可用或用户偏好"逐个出场"的体验；runJointSingleRound 是当前主路径，本方法保留作兼容。
     */
    private void runSingleRound(String roomId, String userId, String userMessage, String context,
                                List<Character> characters,
                                Map<Integer, List<ResponseFragment>> roundResponses,
                                Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                Consumer<ResponseFragment> onResponse) {

        log.info("[Moderator] runSingleRound - roomId: {}, userId: {}, characters: {}, isContinuous: false",
            roomId, userId, characters.size());

        // Get API key in main thread before async tasks (avoids SecurityContext threading issues)
        String userApiKey = null;
        try {
            userApiKey = settingsService.getApiKeyById(userId);
            log.info("[Moderator] Got API key for userId: {}", userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key for userId {}: {}", userId, e.getMessage());
        }
        final String userApiKeyFinal = userApiKey;

        // Notify thinking for all characters first
        for (Character character : characters) {
            log.info("[Moderator] Notifying thinking for character: {} ({})", character.getName(), character.getId());
            onThinking.accept(character.getId().toString());
        }

        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(1, thisRoundResponses);

        // Process characters sequentially with random delay between them
        for (int i = 0; i < characters.size(); i++) {
            Character character = characters.get(i);

            // Random delay between 1-3 seconds before each character starts
            if (i > 0) {
                int delayMs = 1000 + (int) (Math.random() * 2000); // 1-3 seconds
                log.info("[Moderator] [{}] Waiting {}ms before starting (character {})",
                    character.getName(), delayMs, i + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            log.info("[Moderator] [{}] Starting AI generation", character.getName());

            // Build character prompt and generate response synchronously (blocking)
            try {
                String characterPrompt = characterPromptBuilder.build(character, true);
                String fullPrompt = characterPrompt + "\n\n" + context;

                log.info("[Moderator] [{}] Prompt built, length: {}, calling AI service",
                    character.getName(), fullPrompt.length());

                StringBuilder fullResponse = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);

                aiService.generateResponseStream(
                    fullPrompt,
                    userMessage,
                    userApiKeyFinal,
                    chunk -> {
                        fullResponse.append(chunk);
                        // Stream chunk directly to frontend (not delta)
                        try {
                            String charId = character.getId().toString();
                            log.info("[Moderator] [{}] onChunk - chunkLen={}, totalLen={}",
                                character.getName(), chunk.length(), fullResponse.length());
                            if (chunk != null && !chunk.isEmpty()) {
                                onChunk.accept(new ResponseFragment(
                                    charId,
                                    character.getName(),
                                    chunk,
                                    false,
                                    character.getAvatarUrl()
                                ));
                            }
                        } catch (Exception e) {
                            log.warn("[Moderator] [{}] onChunk callback failed: {}",
                                character.getName(), e.getMessage());
                        }
                    },
                    completeResponse -> {
                        String responseText = fullResponse.toString();
                        log.info("[Moderator] [{}] onComplete - response length: {}",
                            character.getName(), responseText.length());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true,
                            character.getAvatarUrl()
                        ));
                        latch.countDown();
                    },
                    error -> {
                        log.error("[Moderator] [{}] onError: {}", character.getName(), error.getMessage());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            "Error: " + error.getMessage(),
                            true,
                            character.getAvatarUrl()
                        ));
                        latch.countDown();
                    }
                );

                log.info("[Moderator] [{}] Waiting for AI response (60s timeout)", character.getName());
                boolean latchReleased = latch.await(60, TimeUnit.SECONDS);
                if (!latchReleased) {
                    log.warn("[Moderator] [{}] Latch timed out after 60s", character.getName());
                    String responseText = fullResponse.toString();
                    if (responseText.isEmpty()) {
                        responseText = "Error: Response timed out (60s)";
                    }
                    thisRoundResponses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true,
                        character.getAvatarUrl()
                    ));
                } else {
                    log.info("[Moderator] [{}] AI response completed", character.getName());
                }

                // Send final complete response
                ResponseFragment finalFragment = thisRoundResponses.get(thisRoundResponses.size() - 1);
                try {
                    onResponse.accept(finalFragment);
                } catch (Exception e) {
                    log.error("[Moderator] [{}] onResponse callback failed: {}",
                        character.getName(), e.getMessage(), e);
                }

            } catch (Exception e) {
                log.error("[Moderator] [{}] Unexpected error: {}", character.getName(), e.getMessage(), e);
                thisRoundResponses.add(new ResponseFragment(
                    character.getId().toString(),
                    character.getName(),
                    "Error: " + e.getMessage(),
                    true,
                    character.getAvatarUrl()
                ));
            }
        }

        log.info("[Moderator] All characters completed, total responses: {}", thisRoundResponses.size());
    }

    /**
     * Build initial context with user message and system prompt.
     */
    private String buildInitialContext(String userMessage) {
        return "The user has asked: \"" + userMessage + "\"\n\n" +
               "This is a GROUP DISCUSSION. Everyone should respond to the user's question first, " +
               "then in subsequent rounds, comment on and debate each other's viewpoints.\n\n" +
               "Keep responses conversational and relatively brief (2-4 sentences).";
    }

    /**
     * Build context for subsequent rounds with previous responses.
     *
     * 把上一轮所有角色的发言拼接到 prompt 中，并显式要求本轮"互相引用/反驳"——解决"各说各话"问题。
     * 模板里强约束：禁止把用户消息当主语、禁止绕回原始问题、必须直接点名或引用上一轮某句话。
     */
    private String buildRoundContext(String previousContext, List<ResponseFragment> responses, int roundNum) {
        StringBuilder context = new StringBuilder();
        context.append(previousContext);
        context.append("\n\n=== 上一轮 ").append(roundNum).append(" 发言 ===\n");

        for (ResponseFragment r : responses) {
            context.append("[").append(r.getCharacterName()).append("]: ").append(r.getContent()).append("\n");
        }

        context.append("\n=== 现在是第 ").append(roundNum + 1).append(" 轮 ===\n");
        context.append("**这一轮是讨论**，不要把用户消息当成主语。");
        context.append("**必须**针对上一轮里某个人**具体的观点/用词/数据**做回应：\n");
        context.append("- 同意对方哪一点？为什么？\n");
        context.append("- 反驳对方哪一点？理由是什么？\n");
        context.append("- 在对方观点上**补充**一个他没提到的角度。\n");
        context.append("- 或者把 A 和 B 的观点做**对比**，点出矛盾。\n");
        context.append("**禁止**大家各说各的、不引用上一轮。**禁止**绕回用户原始消息。\n");
        context.append("每位角色 2~4 句，必须**直接点名**或**直接引用**上一轮里别人的某句话。");

        return context.toString();
    }

    /**
     * Build the system prompt for Moderator to select characters.
     */
    private String buildModeratorPrompt(String userMessage, List<String> userMessageHistory, List<Character> characters) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/moderator-prompt.txt");
            String template = resource.getContentAsString(StandardCharsets.UTF_8);

            // Build characters list
            StringBuilder charactersList = new StringBuilder();
            for (Character c : characters) {
                charactersList.append("- ").append(c.getName())
                    .append(" (专家领域: ").append(c.getExpertise() != null ? String.join(", ", c.getExpertise()) : "未知").append(")")
                    .append(" - ").append(c.getPersona() != null ? c.getPersona() : "").append("\n");
            }

            // Build user message history (last 10)
            StringBuilder historyBuilder = new StringBuilder();
            if (userMessageHistory != null && !userMessageHistory.isEmpty()) {
                int index = 1;
                for (String msg : userMessageHistory) {
                    historyBuilder.append("用户消息").append(index).append(": \"").append(msg).append("\"\n");
                    index++;
                }
            } else {
                historyBuilder.append("(暂无历史对话)\n");
            }

            // Replace placeholders
            return template.replace("{characters}", charactersList.toString())
                          .replace("{userMessage}", userMessage)
                          .replace("{conversationHistory}", historyBuilder.toString());
        } catch (Exception e) {
            log.error("[Moderator] Failed to load moderator prompt template: {}", e.getMessage());
            // Fallback to empty prompt
            return "";
        }
    }

    /**
     * Call LLM to select characters for the discussion.
     */
    private String callModeratorForSelection(String userId, String userMessage, List<String> userMessageHistory, List<Character> characters) {
        String userApiKey = settingsService.getApiKeyById(userId);
        if (userApiKey == null) {
            log.warn("[Moderator] No API key for user {}, using fallback selection", userId);
            return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
        }

        try {
            String prompt = buildModeratorPrompt(userMessage, userMessageHistory, characters);
            ChatLanguageModel chatModel = aiService.createChatModelWithApiKey(userApiKey);
            String response = chatModel.chat(prompt);
            log.info("[Moderator] LLM selection response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("[Moderator] LLM selection failed: {}, using fallback", e.getMessage());
            return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
        }
    }

    @Override
    public void destroy() throws Exception {
        // Cancel all ongoing room discussions
        roomFutures.keySet().forEach(this::cancelRoom);
        roomFutures.clear();

        executor.shutdown();
        // Wait up to 60 seconds for tasks to complete
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[DEBUG] ModeratorAgent: Executor did not terminate");
            }
        }
        log.info("[DEBUG] ModeratorAgent: ExecutorService shut down");
    }

    /**
     * Cancel all ongoing AI processing for a room.
     * This method cancels all tracked CompletableFutures associated with the room.
     *
     * @param roomId The room ID to cancel
     */
    /**
     * 取消房间时先 stopDiscussion 把 isRunning 置位 false，再 cancel 所有跟踪的 future；
     * 顺序很重要——先停调度再中断任务，避免生成中回调看到不一致状态。
     */
    public void cancelRoom(String roomId) {
        // Stop discussion state if running
        stopDiscussion(roomId);

        List<CompletableFuture<?>> futures = roomFutures.remove(roomId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * Response fragment from a character.
     */
    public static class ResponseFragment {
        private final String characterId;
        private final String characterName;
        private final String content;
        private final boolean isComplete;
        private final String avatarUrl;

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete) {
            this(characterId, characterName, content, isComplete, null);
        }

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete, String avatarUrl) {
            this.characterId = characterId;
            this.characterName = characterName;
            this.content = content;
            this.isComplete = isComplete;
            this.avatarUrl = avatarUrl;
        }

        public String getCharacterId() { return characterId; }
        public String getCharacterName() { return characterName; }
        public String getContent() { return content; }
        public boolean isComplete() { return isComplete; }
        public String getAvatarUrl() { return avatarUrl; }
    }
}

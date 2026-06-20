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
 * 主持代理（Moderator Agent）：多轮群组讨论编排器。
 *
 * 讨论流程：
 * 1. 第 1 轮：所有角色并行回答用户的问题
 * 2. 第 2 轮及之后：角色之间互相评论对方的回答，形成辩论
 * 3. 达到 MAX_ROUNDS 后讨论结束
 *
 * 为什么存在：本类把"多角色群聊"从一组孤立调用升级为有状态的多轮编排——既负责挑选本轮发言者（Moderator LLM），
 * 又负责驱动多轮对话、暂停/恢复、用户中途插入、线程归属跟踪，并通过 ChatSocketHandler 把进度推给前端。
 * 与 AIService、CharacterPromptBuilder、SettingsService、ChatSocketHandler 协作；DisposableBean 用于关闭时清理执行器。
 *
 * 字段说明：
 * - executor：自定义线程池，必须继承 Spring SecurityContext；否则子线程拿不到认证，AI 调用会失败。
 * - roomFutures：按 roomId 跟踪运行中的 CompletableFuture，用于在 cancelRoom / destroy 时取消仍在飞的 LLM 调用，避免资源泄漏。
 * - roomDiscussionState：每个房间的会话级可变状态，多线程访问因此用 ConcurrentHashMap 包裹。
 * - objectMapper：仅用于把 ModeratorMessage / DiscussionStateEvent / 错误事件序列化进 Socket.IO 帧，前缀 42[...] 即 Socket.IO MESSAGE 帧格式。
 *
 * 常量说明：
 * - MAX_ROUNDS：硬编码 3 轮上限，避免无终止地递归辩论造成 token 浪费；调用方可在 processMessage 通过 maxRounds 覆盖。
 * - ROUND_DELAY_MS：轮间间隔，让前端先把上一轮内容渲染完再开始下一轮；用户触发时会被忽略以保证响应即时性。
 */
@Slf4j
@Service
public class ModeratorAgent implements DisposableBean {

    // 调用 LLM 流式生成和创建 ChatLanguageModel 的门面；多轮讨论和选人都依赖它。
    private final AIService aiService;
    // 把角色完整回复落库到 MySQL；前端"刷新仍能看到历史"全靠它。
    private final MessageRepository messageRepository;
    // 按 userId 读取用户 LLM API Key；密钥不落数据库时是唯一注入点。
    private final SettingsService settingsService;
    // WebSocket 广播通道；所有"moderator-message / chat message / state"事件最终都走这里推到前端。
    private final ChatSocketHandler chatSocketHandler;
    // 从 classpath 加载 prompt 模板文件；解耦模板和 Java 代码以便独立迭代提示词。
    private final ResourceLoader resourceLoader;
    // 根据角色实体拼装最终 system prompt（含人设、专长、抓取到的设定等）。
    private final CharacterPromptBuilder characterPromptBuilder;
    // 解析消息时需要反查角色名，因此必须可按 id 查询。
    private final CharacterRepository characterRepository;
    // 持久化消息时要按 roomId 关联 Room 实体。
    private final RoomRepository roomRepository;
    // 持久化消息时要按 userId 关联 User 实体，方便后续按用户查询聊天记录。
    private final UserRepository userRepository;
    // Socket.IO 帧需要 JSON 字符串；线程局部 ThreadLocal 没必要，独立实例即可。
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 最大讨论轮数
    private static final int MAX_ROUNDS = 3;

    // 轮间延迟（毫秒），让回复有时间传递
    private static final long ROUND_DELAY_MS = 1500;

    // 异步操作执行器 —— 向子线程传递 SecurityContext
    private final ExecutorService executor;

    // 房间级 future 跟踪，用于取消
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    // 房间级当前讨论状态
    private final ConcurrentHashMap<String, DiscussionState> roomDiscussionState = new ConcurrentHashMap<>();

    // 讨论状态内部类
    private static class DiscussionState {
        // 讨论是否在进行中；cancelRoom/stopDiscussion 把它置 false 让 while 循环自然退出。
        volatile boolean isRunning = false;
        // 用户主动暂停；runJointDiscussion 内的循环每 500ms 自旋检查它。
        volatile boolean paused = false;
        // 当前轮次（从 1 开始）；用于 buildRoundContext 拼接"上一轮发言"。
        volatile int currentRound = 0;
        // 顺序讨论路径下，当前发言角色的下标；联合推理路径用 pendingQueue 替代。
        volatile int currentCharacterIndex = 0;
        // 调用方传入的轮次上限（默认 3）；processMessage 显式覆盖时生效。
        volatile int maxRounds = 3;
        // 房间初始角色清单；顺序讨论模式下循环引用它。
        List<Character> characters = new CopyOnWriteArrayList<>();
        // 当前轮已完成的发言片段；下一轮通过 buildRoundContext 把它们喂回 LLM。
        List<ResponseFragment> responses = new CopyOnWriteArrayList<>();
        // 顺序讨论路径下锁定的用户原始消息（不再变化）；联合推理用 currentUserMessage。
        String userMessage = "";
        // 累积的"轮间上下文"，由 buildRoundContext 不断追加上一轮发言。
        String context = "";
        // 用户在轮间等待时发来消息，置 true 让等待循环立刻跳出以保证响应即时性。
        AtomicBoolean userTriggered = new AtomicBoolean(false);

        // 状态机新增字段
        // 状态机当前阶段；前端依据它渲染"选人中 / 发言中 / 等你参与"等 UI。
        volatile DiscussionPhase phase = DiscussionPhase.IDLE;
        // 用户在讨论中途发送新消息时置位，触发新一轮 Moderator 选人。
        volatile boolean userInterjected = false;
        // 本轮已生成的 AI 消息数；达到 maxAiMessagesPerRound 就停下来邀请用户。
        volatile int aiMessageCount = 0;
        // 每轮最多发言的角色数；防止 LLM 一口气生成过多内容淹没用户。
        volatile int maxAiMessagesPerRound = 3;
        // Moderator 本轮选中的角色；用于"邀请发言"广播和 INVITE 文案拼接。
        List<Character> selectedCharacters = new CopyOnWriteArrayList<>();
        // 待发言队列：每完成一个角色就 remove(0)，空了就触发 waitForUserInput。
        List<Character> pendingQueue = new CopyOnWriteArrayList<>();
        // 最近一次 Moderator 文案（如"正在邀请：A、B"），用于前端展示和重连时回放。
        volatile String moderatorMessage = "";
        // 联合推理路径下的"当前用户问题"；中途用户插话时会更新它。
        volatile String currentUserMessage = "";
        // 当前流式生成被取消的标记位；外部 stop 时翻转为 true 让 SSE 回调尽早返回。
        AtomicBoolean currentStreamCancelled = new AtomicBoolean(false);
        // 业务关键字段：用于 settingsService.getApiKeyById 拉取该用户专属 API Key。
        String userId = "";
        // 滚动窗口：保留最近 10 条用户消息，给 Moderator 选人时作为上下文。
        List<String> userMessageHistory = new CopyOnWriteArrayList<>();  // 最近 10 条用户消息

        // 线程归属跟踪：当前用户"正在和谁对话"的判断依据；用于延续性短句的路由。
        // 当前线程归属角色的姓名
        volatile String activeThreadOwner = null;
        // 上次活跃时间；超过 2 分钟就认为旧线已结束，不再强制续线。
        // 最近线程更新时间戳
        volatile long lastThreadUpdateTime = 0;
        // 最近讨论话题；保留字段用于将来"话题切换检测"，目前未被强引用。
        // 当前讨论话题（用于连续性检查）
        volatile String lastTopic = "";
    }

    // ========== 状态机相关方法 ==========

    // 写入新阶段 + 广播"discussion-state"事件，让前端 UI 同步（思考/说话/邀请等）。
    private void transitionTo(DiscussionState state, DiscussionPhase newPhase) {
        state.phase = newPhase;
        String roomId = findRoomIdByState(state);
        if (roomId != null) {
            broadcastStateChange(roomId, newPhase, state.selectedCharacters, state.moderatorMessage);
        }
    }

    // 反查房间 ID：因为 transitionTo 只持有 state 实例，需要回到全局 map 找归属；并发场景下用 == 比对引用确保精确。
    private String findRoomIdByState(DiscussionState state) {
        for (Map.Entry<String, DiscussionState> entry : roomDiscussionState.entrySet()) {
            if (entry.getValue() == state) {
                return entry.getKey();
            }
        }
        return null;
    }

    // 拼装 DiscussionStateEvent 后以 Socket.IO MESSAGE 帧格式广播；42[...] 是 Engine.IO 协议的事件帧前缀。
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

        // 1. 立即设置中断标志位
        state.userInterjected = true;

        // 2. 清空待发队列
        state.pendingQueue.clear();

        // 3. 重置 AI 消息计数
        state.aiMessageCount = 0;

        // 4. 添加到用户消息历史（仅保留最近 10 条）
        state.userMessageHistory.add(userMessage);
        if (state.userMessageHistory.size() > 10) {
            state.userMessageHistory.remove(0);
        }

        // 5. 切换到 MODERATING 阶段
        transitionTo(state, DiscussionPhase.MODERATING);
        state.currentUserMessage = userMessage;
        state.userMessage = userMessage;  // 同时设置 userMessage 供 generateCharacterResponse 使用
        state.userId = userId;

        // 6. 立即启动新一轮 Moderator 分析
        processModeratorAnalysis(roomId, userMessage);
    }

    /**
     * 在角色回复后更新活跃线程归属者。
     * 通过追踪用户当前正在与谁对话来保持会话连续性。
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
     * 判断当前用户消息是否在延续同一线程。
     * 如果应路由到活跃线程归属者，则返回 true。
     */
    private boolean isContinuingThread(DiscussionState state, String userMessage, String selectedAgentName) {
        if (state == null || state.activeThreadOwner == null) {
            return false;
        }

        // 如果用户明确提到了别的 agent，不要强制保持线程连续性
        if (selectedAgentName != null && !selectedAgentName.equals(state.activeThreadOwner)) {
            return false;
        }

        // 短小的上下文衔接类消息强烈暗示线程延续
        if (isShortContextualMessage(userMessage)) {
            return true;
        }

        // 消息非常短且线程最近活跃（在 2 分钟内）
        long twoMinutesAgo = System.currentTimeMillis() - 120000;
        if (userMessage.trim().length() < 20 && state.lastThreadUpdateTime > twoMinutesAgo) {
            return true;
        }

        return false;
    }

    // 短句/承接词判断：列表覆盖中英文常见承接词；命中即认为"用户是在回应上一句"而非"开启新话题"。
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

        // 调用 LLM 选人（附带用户消息历史上下文）
        String selection = callModeratorForSelection(state.userId, userMessage, state.userMessageHistory, availableCharacters);
        log.info("[Moderator] LLM selection result: {}", selection);

        // 解析 JSON 格式的回复
        List<Character> selected = parseModeratorResponse(selection, availableCharacters);

        // 兜底：若没有匹配到任何角色
        if (selected.isEmpty()) {
            selected = availableCharacters.subList(0, Math.min(1, availableCharacters.size()));
        }

        // 线程连续性检查：若消息大概率延续同一线程，优先使用活跃线程归属者
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

        // 广播选人结果
        String selectMsg = "正在邀请: " + selected.stream().map(Character::getName).collect(Collectors.joining(", "));
        state.moderatorMessage = selectMsg;
        broadcastModeratorMessage(roomId, selectMsg, "SELECT");

        // 切换到 SPEAKING 阶段
        transitionTo(state, DiscussionPhase.SPEAKING);

        // 启动发言流程
        processNextInQueue(roomId);
    }

    /**
     * 以 JSON 格式解析 Moderator LLM 的回复。
     */
    // 解析 Moderator LLM 的回复：优先 JSON 解析（message_type + target_agents）；失败再回退到 [SELECT:...] 正则；最终兜底交给 caller。
    private List<Character> parseModeratorResponse(String response, List<Character> availableCharacters) {
        List<Character> selected = new ArrayList<>();
        try {
            // 尝试按 JSON 解析
            Map<String, Object> json = objectMapper.readValue(response, Map.class);
            String messageType = (String) json.get("message_type");
            List<String> targetAgents = (List<String>) json.get("target_agents");
            String confidence = (String) json.get("confidence");

            log.info("[Moderator] Parsed JSON - type: {}, confidence: {}, targets: {}",
                messageType, confidence, targetAgents);

            // 处理 broadcast —— 所有角色都发言
            if ("broadcast".equals(messageType)) {
                log.info("[Moderator] Broadcast requested - all characters will speak");
                selected.addAll(availableCharacters);
                return selected;
            }

            // 处理 invite —— 没有选中任何角色，会作为 INVITE 处理
            if ("invite".equals(messageType)) {
                log.info("[Moderator] INVITE requested - no character selection");
                return selected;
            }

            // 处理 direct_reply 或 multi_target_reply
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
            // 兜底使用旧的 [SELECT:xxx] 格式
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

    // 弹出 pendingQueue 头部角色并触发生成；先检查是否需要"等用户"，避免空转。
    private void processNextInQueue(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null || !state.isRunning) return;

        // 检查是否需要等待用户输入
        if (shouldWaitForUser(state)) {
            waitForUserInput(roomId);
            return;
        }

        // 从队列取出下一个角色 —— 同步块保证线程安全
        synchronized (state.pendingQueue) {
            if (!state.pendingQueue.isEmpty()) {
                Character character = state.pendingQueue.remove(0);
                generateCharacterResponse(roomId, character, state, state.userId, state.currentUserMessage, state.context);
            }
        }
    }

    // 是否本轮已发够，需要停下来邀请用户：达上限 / 队列空 + 未选人都视为"该让用户说话了"。
    private boolean shouldWaitForUser(DiscussionState state) {
        if (state.aiMessageCount >= state.maxAiMessagesPerRound) {
            return true;
        }
        if (state.pendingQueue.isEmpty() && state.selectedCharacters.isEmpty()) {
            return true;
        }
        return false;
    }

    // 切到 WAITING_FOR_USER 阶段并广播 INVITE 文案，提示用户该他说一句了。
    private void waitForUserInput(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        transitionTo(state, DiscussionPhase.WAITING_FOR_USER);

        String inviteMessage = generateModeratorInvite(state);
        state.moderatorMessage = inviteMessage;
        broadcastModeratorMessage(roomId, inviteMessage, "INVITE");
    }

    // 随机挑一条邀请语，避免用户多次讨论后看到完全相同的措辞；选中的角色名被拼进问句保持上下文一致。
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

    // 把 Moderator 的引导/总结文本包装成 ModeratorMessage，以 "moderator-message" 事件广播；type 让前端区分 SELECT/INVITE/SUMMARY。
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

    // 构造器：把协作组件注入进来，并自建"会传播 SecurityContext"的自定义线程池，避免子线程访问受保护资源时鉴权失败。
    // @Lazy 防止 ModeratorAgent 与 ChatSocketHandler 互相依赖时形成构造期循环。
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
        // 包装 executor，使 SecurityContext 能被异步线程继承
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            // 从提交任务的线程继承 SecurityContext
            SecurityContext ctx = SecurityContextHolder.getContext();
            t.setContextClassLoader(null);
            return new Thread(ctx != null ? new SecurityContextAwareThread(ctx, t) : t);
        });
    }

    // 包装 Thread，在 run() 前设置 SecurityContext
    // 在 run() 前把"提交任务时的 SecurityContext"重新安装到当前线程，使 LLM 调用方等链路能继续以用户身份访问受保护资源。
    private static class SecurityContextAwareThread extends Thread {
        // 被捕获的父线程 SecurityContext；构造时固化，避免异步任务跑到一半上下文被其他线程覆盖。
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
     * 处理用户消息并编排 AI 角色讨论。
     *
     * @param roomId 房间 ID
     * @param userId 用户 ID（显式传入，避免 SecurityContext 线程传递问题）
     * @param userMessage 用户消息
     * @param characters 房间内角色列表
     * @param isContinuous true 表示多轮讨论模式；false 表示单轮对话模式
     * @param maxRounds 多轮模式下的最大轮数
     * @param onThinking "思考中"状态的回调
     * @param onChunk 流式块的回调（每生成一段内容就会触发）
     * @param onResponse 每个角色完成回复时的回调
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

        // 预先校验 API key，这样可以在不发声的情况下主动通知用户。
        // 对话和讨论模式最终都会调用 runJointSingleRound，
        // 否则它会在不告知前端"为什么什么也没发生"的情况下直接返回。
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

        // 用用户消息构建初始上下文
        String initialContext = buildInitialContext(userMessage);

        // 按轮次跟踪回复
        Map<Integer, List<ResponseFragment>> roundResponses = new ConcurrentHashMap<>();

        try {
            if (isContinuous) {
                // 讨论模式：基于轮次的顺序讨论
                // 每一轮使用一次联合 LLM 调用，使得同轮的所有角色
                // 都来自同一次推理（更省 token、跨角色上下文更连贯）。
                runJointDiscussion(roomId, userId, userMessage, characters, maxRounds,
                    initialContext, onThinking, onChunk, onResponse);
            } else {
                // 对话模式：单次联合推理 —— moderator 选出 1..N 个发言者，
                // 我们从同一次推理中按顺序流式输出每位发言者的回复。
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

    /** 传递给 WebSocket 层的结构化错误，供前端做出反应。 */
    public static class ModeratorError {
        // 错误分类枚举：MISSING_API_KEY 提示用户去设置页；LLM_ERROR 表示上游调用失败。
        public enum Code { MISSING_API_KEY, LLM_ERROR }

        // 结构化错误码，前端依据它做差异化提示（如引导跳转 vs 通用重试）。
        private final Code code;
        // 给人类看的错误描述，直接渲染到聊天提示框。
        private final String message;

        public ModeratorError(Code code, String message) {
            this.code = code;
            this.message = message;
        }

        // 错误码枚举，由 WebSocket 控制层读取后决定弹哪种 toast。
        public Code getCode() { return code; }
        // 错误文案，给前端原样展示。
        public String getMessage() { return message; }
    }

    // ================== 联合（单次 LLM 调用）流程 ==================

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

        // 为 prompt 加载最近 10 条消息
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
                    // 等待暂停解除
                    while (state.paused && state.isRunning) {
                        Thread.sleep(500);
                    }
                    if (!state.isRunning) break;

                    // 轮间延迟 1.5s
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

                    // 构建包含上一轮回复的轮次上下文
                    String roundContext = buildRoundContext(
                        state.context, state.responses, state.currentRound - 1);

                    // 包装 onResponse，将每个完成的发言块捕获到
                    // state.responses —— 下一轮的 buildRoundContext 需要用它
                    // 把上一轮的输出回喂给 LLM。没有这层包装，
                    // 第 2 轮及之后根本不知道第 1 轮说了什么，只能自说自话。
                    final int roundBeingRun = state.currentRound;
                    Consumer<ResponseFragment> capturingOnResponse = fragment -> {
                        // 捕获到下一轮的上下文，然后转发给上层调用方。
                        state.responses.add(fragment);
                        onResponse.accept(fragment);
                    };

                    // 第 2 轮起，清空上一轮残留的回复，确保
                    // 上下文段只展示我们希望 LLM 回应的那部分内容。
                    if (state.currentRound > 1) {
                        state.responses.clear();
                    }

                    runJointSingleRound(roomId, userId, userMessage, roundContext, characters,
                        onChunk, capturingOnResponse);

                    // 快照本轮回复，供下一轮迭代使用
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
     * 通过加载 moderator-joint-prompt.txt 模板并填入
     * 角色名单、最近历史和用户消息来构建联合 prompt。
     */
    // 加载联合推理 prompt 模板，把角色花名册 + 最近历史 + 用户消息三段拼好后注入占位符。
    // 角色 prompt 仅截前 800 字作为"风味片段"，避免模板过长导致 token 爆掉。
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
                    // 取角色 prompt 的前 800 字符作为"风味片段"
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
     * 预取最近 N 条消息的角色名：一次性 findAllById 解决懒加载代理问题，避免在序列化时触发 LazyInitializationException。
     * 不通过 this 内部调用同名 @Transactional 方法——会绕过 Spring 代理导致事务失效。
     */
    public String loadRecentHistory(String roomId, int limit) {
        try {
            UUID roomUuid = UUID.fromString(roomId);
            List<Message> messages = messageRepository.findByRoomIdOrderByCreatedAtAsc(roomUuid);
            if (messages.isEmpty()) return "";

            // 收集所有 CHARACTER 类型消息引用的角色 ID
            Set<UUID> charIds = new HashSet<>();
            for (Message m : messages) {
                if (m.getCharacter() != null) {
                    charIds.add(m.getCharacter().getId());
                }
            }

            // 一次往返查询解析出名字（预先加载 —— 没有懒加载代理问题）
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
     * 联合 LLM 流的增量解析器。LLM 的输出协议：
     *
     *   [角色名]: 台词内容（可换行）
     *   <<<END>>>
     *   [另一角色]: ...
     *   <<<END>>>
     *
     * 对于每个完整的发言块：
     *  - 以完整内容调用 onChunk(fragment, isComplete=false)
     *    （这样前端在该轮发言结束时能看到发言者的文字）
     *  - 以 isComplete=true 调用 onResponse(fragment, isComplete=true)，
     *    让编排层持久化并广播
     */
    private static class JointStreamParser {
        // LLM 输出里"一段发言结束"的固定标记，必须与 prompt 模板里写的字符串保持一致。
        private static final String END_MARKER = "<<<END>>>";
        // 单个发言块的正则：[角色名]: 内容（可换行）... <<<END>>>
        private static final Pattern BLOCK = Pattern.compile(
            "\\[([^\\]]+)\\]:\\s*([\\s\\S]*?)<<<END>>>");

        // 按小写角色名索引；解析时直接 toLowerCase 查表，容忍 LLM 大小写飘移。
        private final Map<String, Character> byName;
        // 保留原始角色名列表，方便未来对未知发言做"最接近匹配"提示。
        private final List<String> knownNames;
        // 增量分发回调：每完成一个发言块就推一次（isComplete=false）让前端立刻出现文字。
        private final Consumer<ResponseFragment> onChunk;
        // 整段完成回调：用于持久化和"chat message"广播（isComplete=true）。
        private final Consumer<ResponseFragment> onResponse;
        // 累计收到的所有 token；保留全量是为了让正则可以反复回溯查找完整块。
        private final StringBuilder buffer = new StringBuilder();
        // 已经处理过的字符数；parseNewBlocks 永远只看 [consumed, end] 这段。
        private int consumed = 0;
        // 防止同一角色在同一轮里被重复生成（如 LLM 偶发回声）；已发言的角色 id 进集合。
        private final Set<String> emittedSpeakers = new HashSet<>();

        // 构造时建立角色名索引，onChunk/onResponse 直接透传给上层编排。
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

        // 每段 SSE chunk 进来后追加到 buffer 并尝试切出完整发言块。
        void onChunk(String chunk) {
            buffer.append(chunk);
            parseNewBlocks();
        }

        // 流式结束时调用：补齐 buffer、解析剩余块，并对"未闭合的尾段"做警告日志。
        void flush(String finalText) {
            // 确保 buffer 包含 LLM 产生的全部内容
            if (buffer.length() < finalText.length()) {
                buffer.append(finalText.substring(buffer.length()));
            }
            parseNewBlocks();
            // 任何残留的看起来像未完成块的内容 —— 丢弃并打警告
            if (consumed < buffer.length()) {
                String leftover = buffer.substring(consumed).trim();
                if (!leftover.isEmpty()) {
                    log.warn("[Moderator] joint stream ended with unterminated block: {}",
                        leftover.substring(0, Math.min(120, leftover.length())));
                }
            }
        }

        // 循环扫 buffer 找出所有 [Name]: ... <<<END>>> 完整块；每找到一个就消费掉。
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
                    // 同一发言者在同一次联合回复中重复出现 —— 保留第一个
                    log.warn("[Moderator] joint stream repeated speaker '{}' — keeping first block", rawName);
                    consumed = m.end();
                    continue;
                }
                emit(c, content);
                emittedSpeakers.add(c.getId().toString());
                consumed = m.end();
            }
            // 周期性地裁剪已消费前缀，让 buffer 保持较小
            if (consumed > 4096) {
                buffer.delete(0, consumed);
                consumed = 0;
            }
        }

        // 真正分发：先 onChunk（让前端流式出现文字），再 onResponse（让上层持久化 + 广播）。
        private void emit(Character c, String content) {
            if (content == null || content.isEmpty()) {
                log.info("[Moderator] joint stream: speaker {} emitted empty block, skipped", c.getName());
                return;
            }
            String charId = c.getId().toString();
            // 流式块：用户在发言结束时看到文字出现
            onChunk.accept(new ResponseFragment(
                charId, c.getName(), content, false, c.getAvatarUrl()));
            // 完成：编排层持久化 + 广播
            onResponse.accept(new ResponseFragment(
                charId, c.getName(), content, true, c.getAvatarUrl()));
        }
    }

    /**
     * 顺序讨论模式（每角色单独调一次 LLM 的旧路径，目前主要保留作 fallback）。
     * 与 runJointDiscussion 的关键差异：每个角色独立 prompt，无法看到彼此视角，代价更高但隔离性更好；
     * 由随机 1-3s 间隔模拟"真人发言节奏"，同时 userTriggered 允许用户中途插队。
     */
    private void runSequentialDiscussion(String roomId, String userId, DiscussionState state,
                                         Consumer<String> onThinking) {
        log.info("[Moderator] runSequentialDiscussion START - roomId: {}, chars: {}, rounds: {}",
            roomId, state.characters.size(), state.maxRounds);

        // 在后台线程启动讨论循环
        CompletableFuture.runAsync(() -> {
            try {
                while (state.isRunning && state.currentRound <= state.maxRounds) {
                    Character character = state.characters.get(state.currentCharacterIndex);

                    // 等待暂停解除 —— 每 500ms 检查一次
                    while (state.paused && state.isRunning) {
                        log.info("[Moderator] Discussion paused, waiting...");
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // 角色发言前随机延迟 1-3 秒
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

                    // 角色发言（阻塞式流式）
                    log.info("[Moderator] [Round {}] {} is now speaking", state.currentRound, character.getName());
                    generateCharacterResponse(roomId, character, state, state.userId, state.userMessage, state.context);

                    // 移动到下一个角色
                    state.currentCharacterIndex++;

                    // 若本轮所有角色都已发言
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
            // 注意：discussion 模式下 `context` 已经包含用户的问题
            // （见 buildInitialContext，它内嵌了 "The user has asked: ..."）。
            // dialogue 模式（runSingleRound）下，`context` 是原始对话历史，
            // 不会内嵌用户问题。我们仅在 context 不包含问题时追加尾行 —— 但为了
            // 两条路径都简单，约定由调用方在 context 里嵌入问题，故此处不再追加。
            String fullPrompt = characterPrompt + "\n\n" + context;

            log.info("[Moderator] [{}] Prompt length: {}", character.getName(), fullPrompt.length());

            StringBuilder fullResponse = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            // 防止 latch.await 超时但 AI 流稍后完成的竞态
            // —— 没有这道护栏，同一回复会被持久化两次并向前端广播两次。
            AtomicBoolean settled = new AtomicBoolean(false);

            aiService.generateResponseStream(
                fullPrompt,
                userMessage,
                userApiKey,
                chunk -> {
                    fullResponse.append(chunk);
                    try {
                        // 通过规范的 'message stream' 事件向前端流式推送
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

                    // 持久化消息到数据库
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

                        // 通过规范的 'chat message' 事件广播完整回复
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
                    // 向前端广播错误
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
                    // onComplete 或 onError 已在等待期间抢先执行
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

            // 状态机：完成后，处理队列中的下一个
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
     * 将 CHARACTER 消息持久化到数据库。按 id 查询 Character/Room/User 实体，
     * 填充 Message 后返回已保存的实体（带 id）。
     */
    // 落库角色消息：按 id 反查三个外键实体后组装 Message 并 save；返回带 id 的实体供广播事件使用。
    // 找不到 entity 时直接复用调用方传入的"轻量引用"，避免缺数据时整轮讨论崩溃。
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
     * 暂停某个房间的讨论。
     */
    // 暂停：仅翻 paused 位，由讨论循环下次醒来时（500ms 间隔）自行停止下一角色生成。
    public void pauseDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = true;
            log.info("[Moderator] Discussion paused for room: {}", roomId);
        }
    }

    /**
     * 恢复某个房间的讨论。
     */
    /**
     * 恢复 + 触发即时继续：通过 userTriggered=true 让轮间循环立刻跳出等待，不必等满 ROUND_DELAY_MS。
     * 这是"用户期待响应即时"的权衡——恢复时不再维持原有的延迟节奏。
     */
    public void resumeDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null) {
            state.paused = false;
            state.userTriggered.set(true); // 触发立即继续
            log.info("[Moderator] Discussion resumed for room: {}", roomId);
        }
    }

    /**
     * 当用户发送消息时触发讨论继续。
     */
    // 用户发言触发器：跳过轮间等待；如果当前暂停则顺手恢复。供 socket 层在收到用户新消息时调用。
    public void triggerUserMessage(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null && state.isRunning) {
            state.userTriggered.set(true);
            // 若当前处于暂停状态，一并恢复
            if (state.paused) {
                state.paused = false;
            }
            log.info("[Moderator] User message triggered discussion for room: {}", roomId);
        }
    }

    /**
     * 停止某个房间的讨论。
     */
    // 彻底停止讨论：从全局 map 移除 state 并翻 isRunning=false；常被 cancelRoom 调用做"先停调度"的前置动作。
    public void stopDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.remove(roomId);
        if (state != null) {
            state.isRunning = false;
            log.info("[Moderator] Discussion stopped for room: {}", roomId);
        }
    }

    /**
     * 检查某房间当前是否正在进行讨论。
     */
    // 查询接口：给 socket 层/前端判断房间是否还在跑讨论；无 state 也算"未运行"。
    public boolean isDiscussionRunning(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        return state != null && state.isRunning;
    }

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

        // 在主线程获取 API key 再进入异步任务（避免 SecurityContext 线程传递问题）
        String userApiKey = null;
        try {
            userApiKey = settingsService.getApiKeyById(userId);
            log.info("[Moderator] Got API key for userId: {}", userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key for userId {}: {}", userId, e.getMessage());
        }
        final String userApiKeyFinal = userApiKey;

        // 先通知所有角色进入思考态
        for (Character character : characters) {
            log.info("[Moderator] Notifying thinking for character: {} ({})", character.getName(), character.getId());
            onThinking.accept(character.getId().toString());
        }

        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(1, thisRoundResponses);

        // 按顺序处理角色，每位之间加入随机延迟
        for (int i = 0; i < characters.size(); i++) {
            Character character = characters.get(i);

            // 每位角色发言前随机延迟 1-3 秒
            if (i > 0) {
                int delayMs = 1000 + (int) (Math.random() * 2000); // 1-3 秒
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

            // 构建角色 prompt 并同步（阻塞）生成回复
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
                        // 直接把流式块推给前端（非 delta）
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

                // 发送最终的完整回复
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
     * 用用户消息和系统提示构建初始上下文。
     */
    private String buildInitialContext(String userMessage) {
        return "The user has asked: \"" + userMessage + "\"\n\n" +
               "This is a GROUP DISCUSSION. Everyone should respond to the user's question first, " +
               "then in subsequent rounds, comment on and debate each other's viewpoints.\n\n" +
               "Keep responses conversational and relatively brief (2-4 sentences).";
    }

    /**
     * 为后续轮次构建包含上一轮回复的上下文。
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
     * 为 Moderator 构建选人的系统提示。
     */
    private String buildModeratorPrompt(String userMessage, List<String> userMessageHistory, List<Character> characters) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/moderator-prompt.txt");
            String template = resource.getContentAsString(StandardCharsets.UTF_8);

            // 构建角色清单
            StringBuilder charactersList = new StringBuilder();
            for (Character c : characters) {
                charactersList.append("- ").append(c.getName())
                    .append(" (专家领域: ").append(c.getExpertise() != null ? String.join(", ", c.getExpertise()) : "未知").append(")")
                    .append(" - ").append(c.getPersona() != null ? c.getPersona() : "").append("\n");
            }

            // 构建用户消息历史（最近 10 条）
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

            // 替换占位符
            return template.replace("{characters}", charactersList.toString())
                          .replace("{userMessage}", userMessage)
                          .replace("{conversationHistory}", historyBuilder.toString());
        } catch (Exception e) {
            log.error("[Moderator] Failed to load moderator prompt template: {}", e.getMessage());
            // 兜底返回空 prompt
            return "";
        }
    }

    /**
     * 调用 LLM 为讨论选择角色。
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

    // Spring 容器关闭钩子：cancel 所有还在飞的房间讨论，再优雅关闭线程池；超时兜底用 shutdownNow 强停。
    @Override
    public void destroy() throws Exception {
        // 取消所有仍在进行的房间讨论
        roomFutures.keySet().forEach(this::cancelRoom);
        roomFutures.clear();

        executor.shutdown();
        // 等待任务完成，最多 60 秒
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[DEBUG] ModeratorAgent: Executor did not terminate");
            }
        }
        log.info("[DEBUG] ModeratorAgent: ExecutorService shut down");
    }

    /**
     * 取消某房间所有正在进行的 AI 处理。
     * 此方法会取消该房间关联的所有已跟踪 CompletableFuture。
     *
     * @param roomId 要取消的房间 ID
     */
    /**
     * 取消房间时先 stopDiscussion 把 isRunning 置位 false，再 cancel 所有跟踪的 future；
     * 顺序很重要——先停调度再中断任务，避免生成中回调看到不一致状态。
     */
    public void cancelRoom(String roomId) {
        // 若讨论在运行，先停止其状态
        stopDiscussion(roomId);

        List<CompletableFuture<?>> futures = roomFutures.remove(roomId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * 来自某个角色的回复片段。
     */
    public static class ResponseFragment {
        // 发言角色 UUID；下游落库和前端按角色路由都靠它。
        private final String characterId;
        // 发言角色名；冗余存储是为了避免调用方再查库显示。
        private final String characterName;
        // 完整或部分的发言文本；isComplete 决定它是中间片段还是最终全文。
        private final String content;
        // true = 这一段已完整生成，可持久化和广播；false = 还在流的中间片段。
        private final boolean isComplete;
        // 头像 URL；可选，主要给前端聊天列表渲染头像用。
        private final String avatarUrl;

        // 兼容重载：历史调用方没传头像 URL 时使用 null（前端会回落到默认头像）。
        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete) {
            this(characterId, characterName, content, isComplete, null);
        }

        // 主构造器：5 参版本，所有字段一次性固化；不可变对象便于在多线程间安全共享。
        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete, String avatarUrl) {
            this.characterId = characterId;
            this.characterName = characterName;
            this.content = content;
            this.isComplete = isComplete;
            this.avatarUrl = avatarUrl;
        }

        // 角色 UUID；socket 监听器据此判断是否要给当前房间染色。
        public String getCharacterId() { return characterId; }
        // 角色展示名；前端气泡标题直接渲染。
        public String getCharacterName() { return characterName; }
        // 发言正文；前端做流式追加或一次性替换。
        public String getContent() { return content; }
        // 是否完整；true 时前端把这条片段"固化"为一条消息。
        public boolean isComplete() { return isComplete; }
        // 头像 URL；可能为 null，前端需做兜底。
        public String getAvatarUrl() { return avatarUrl; }
    }
}

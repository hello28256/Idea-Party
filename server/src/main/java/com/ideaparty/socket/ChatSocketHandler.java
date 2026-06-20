package com.ideaparty.socket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.dto.DiscussionStateEvent;
import com.ideaparty.dto.ModeratorMessage;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.AuthService;
import com.ideaparty.service.ModeratorAgent;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebSocket 入口处理器，负责前端 Socket.IO 客户端与后端聊天室之间的实时消息路由。
 * 角色：解析 Socket.IO 协议、把客户端事件分发给 ModeratorAgent、组织讨论/对话模式下的发言编排。
 * 配合 AuthService（JWT 校验）、ModerationService（内容审核）、MessageService（持久化）、ModeratorAgent（AI 编排）共同构成聊天室后端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSocketHandler extends TextWebSocketHandler {

    // 全局房间注册表：roomId -> 当前连接到该房间的所有 WebSocket 会话；用于按房间广播消息。
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // 反向索引：sessionId -> session 当前所在的 roomId；用于连接断开时精准清理房间状态。
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    // 记录每个 WebSocket 会话对应的已认证 userId；在 join room 时写入，供后续消息落库使用。
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    // 房间最近一次发言的角色名；用于上下文续接判断（不直接控制路由，仅作为线索参考）。
    private final ConcurrentHashMap<String, String> roomLastSpeaker = new ConcurrentHashMap<>();  // Track last speaker per room
    // 当前房间的主线发言持有者；与 lastSpeaker 区分，用于表达"哪个角色正在主导当前话题线程"。
    // Track active conversation thread owner per room (distinct from lastSpeaker for clearer thread semantics)
    private final ConcurrentHashMap<String, String> roomActiveThreadOwner = new ConcurrentHashMap<>();
    // 每个房间最近 5 条 AI 回复的上下文快照，供下一次发言时拼接到 prompt 中做跨角色感知。
    // Track recent AI responses per room for cross-agent context (keeps last 5 responses)
    private final ConcurrentHashMap<String, List<RecentResponse>> roomRecentResponses = new ConcurrentHashMap<>();
    // 房间级互斥锁：将"讨论是否在跑"判定与"启动新讨论"打包成原子段，避免并发用户消息触发并行讨论循环。
    // 锁对象按需懒创建，房间空时清理（见 afterConnectionClosed）。
    // Per-room locks used to make "is discussion running? then route / start" check-and-start atomic.
    // Two concurrent user messages can otherwise both pass the "not running" check and start parallel
    // discussion loops, overwriting the same DiscussionState.
    private final ConcurrentHashMap<String, Object> roomDiscussionLocks = new ConcurrentHashMap<>();
    // Jackson JSON 序列化器，用于将事件 payload 包装成 Socket.IO 帧以及解析入站消息。
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 内部数据结构：记录一条 AI 回复的角色名、内容、时间戳，供后续 prompt 上下文使用。
    // Simple record to store recent response context
    private static class RecentResponse {
        final String characterName;
        final String content;
        final long timestamp;

        RecentResponse(String characterName, String content) {
            this.characterName = characterName;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }
    // 由 @RequiredArgsConstructor 注入：用于把用户消息与 AI 回复写入 MySQL。
    private final MessageService messageService;
    // 由 @RequiredArgsConstructor 注入：用户消息入站前的内容安全审核。
    private final ModerationService moderationService;
    // 由 @RequiredArgsConstructor 注入：按 roomId 加载聊天室及其角色列表的 JPA 仓库。
    private final RoomRepository roomRepository;
    // 由 @RequiredArgsConstructor 注入：AI 编排核心，负责发言顺序、轮次、流式回调。
    private final ModeratorAgent moderatorAgent;
    // 由 @RequiredArgsConstructor 注入：JWT 校验，前端在 join room 时带 token 上来识别身份。
    private final AuthService authService;

    /**
     * WebSocket 握手成功后的钩子；Socket.IO 客户端随后会发 "40" connect 包，由 handleTextMessage 走协议分支响应。
     * 此处刻意留空，不在此阶段分配房间资源——等到 join room 事件到达时再注册。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Socket.IO client sends "40" (connect packet) initially
    }

    /**
     * WebSocket 文本帧入口；按 Socket.IO Engine.IO 协议分发：4xx 是 Engine.IO 控制帧，42 是 MESSAGE 数据帧。
     * 调用方：Spring WebSocket 容器；副作用：根据帧类型回 pong/connect-ack，或进一步解析事件并广播。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("[WS] Received message: {} (sessionId={})", payload, session.getId());

        // Socket.IO protocol: "42" prefix means MESSAGE with event name
        // Format: 42["event_name", data]
        if (payload.startsWith("42")) {
            handleSocketIOMessage(session, payload.substring(2));
        } else if (payload.equals("2")) {
            // "2" is a ping, respond with "3" (pong)
            log.debug("[WS] Sending pong");
            session.sendMessage(new TextMessage("3"));
        } else if (payload.startsWith("40")) {
            // "40" is a connect packet, respond with "40" (connection acknowledged)
            log.debug("[WS] Sending connection ack");
            session.sendMessage(new TextMessage("40"));
        } else {
            log.debug("[WS] Unknown payload format");
        }
    }

    /**
     * 解析 Socket.IO MESSAGE 帧（已剥掉 "42" 前缀的 JSON 数组），按事件名分派到对应处理器。
     * 约定：前端发送格式为 ["event_name", data]；调用方为 handleTextMessage。
     */
    private void handleSocketIOMessage(WebSocketSession session, String data) throws Exception {
        JsonNode node = objectMapper.readTree(data);
        if (node.isArray() && node.size() >= 2) {
            String event = node.get(0).asText();
            JsonNode eventData = node.get(1);

            switch (event) {
                case "join room":
                    handleJoinRoom(session, eventData);
                    break;
                case "leave room":
                    handleLeaveRoom(session, eventData);
                    break;
                case "chat message":
                    handleChatMessage(session, eventData);
                    break;
                case "trigger-ai":
                    handleTriggerAI(session, eventData);
                    break;
                case "pause-discussion":
                    handlePauseDiscussion(session, eventData);
                    break;
                case "resume-discussion":
                    handleResumeDiscussion(session, eventData);
                    break;
                case "stop-discussion":
                    handleStopDiscussion(session, eventData);
                    break;
            }
        }
    }

    /**
     * 处理客户端 join room 事件：可选校验 JWT、注册会话到房间注册表、记录 userId 映射。
     * 副作用：更新 rooms / sessionRooms / sessionUsers / SecurityContext；向客户端回 room-joined 确认帧。
     * 调用方：handleSocketIOMessage。
     */
    private void handleJoinRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();

        // Authenticate via JWT if provided
        String userId = null;
        if (data.has("token") && !data.get("token").isNull()) {
            String token = data.get("token").asText();
            try {
                UUID validatedUserId = authService.validateToken(token);
                userId = validatedUserId.toString();
                // Set SecurityContext for this WebSocket session
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        userId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.info("[WS] Authenticated user {} for session {}", userId, session.getId());
            } catch (Exception e) {
                log.warn("[WS] JWT validation failed for session {}: {}", session.getId(), e.getMessage());
            }
        }

        joinRoom(roomId, session);
        if (userId != null) {
            sessionUsers.put(session.getId(), userId);
        }
        session.sendMessage(new TextMessage("42[\"room-joined\",{\"roomId\":\"" + roomId + "\"}]"));
    }

    /**
     * 处理客户端 leave room 事件：从房间注册表与反向索引中移除当前会话。
     * 调用方：handleSocketIOMessage；空房间清理交给 afterConnectionClosed 统一处理。
     */
    private void handleLeaveRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        leaveRoom(roomId, session);
    }

    /**
     * 处理客户端 chat message 事件：审核 → 持久化 → 广播 → 触发 AI 回复的完整流水线。
     * 关键策略：审核失败直接回 error 帧不广播；持久化失败必须中断（防静默丢消息）；
     * USER 类型消息根据房间 chatMode 分流到 discussion 模式（per-room 锁原子启动）或 dialogue 模式（moderator 路由）。
     * 调用方：handleSocketIOMessage。
     */
    private void handleChatMessage(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        String content = data.get("content").asText();
        String senderType = data.has("senderType") ? data.get("senderType").asText() : "USER";
        String characterId = data.has("characterId") && !data.get("characterId").isNull()
            ? data.get("characterId").asText()
            : null;

        // Check moderation before processing
        ModerationService.ModerationResult result = moderationService.moderate(content);
        if (!result.isAllowed()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of(
                    "message", result.getReason()
                ))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // Save message to database and get generated ID
        String messageId = null;
        String msgUserId = sessionUsers.get(session.getId());
        try {
            Message.SenderType type = Message.SenderType.valueOf(senderType);
            UUID roomUuid = UUID.fromString(roomId);
            UUID characterUuid = characterId != null ? UUID.fromString(characterId) : null;
            UUID userUuid = msgUserId != null ? UUID.fromString(msgUserId) : null;
            Message savedMessage = messageService.saveMessage(roomUuid, characterUuid, type, content, userUuid);
            messageId = savedMessage.getId();
            if (savedMessage.getUser() != null) {
                msgUserId = savedMessage.getUser().getId().toString();
            }
        } catch (Exception e) {
            // 持久化失败时必须通知发送方并中断后续流程：
            // 原来仅 log 然后继续广播，前端会收到一条"成功"的消息但数据库实际未持久化，
            // 下游 Moderator/observation 流水线也会丢失这条消息，造成静默数据丢失。
            log.error("[WS] Failed to save message for room {}: {}", roomId, e.getMessage(), e);
            String errorEvent = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of(
                    "message", "消息保存失败，请重试",
                    "code", "PERSISTENCE_FAILED"
                ))
                + "]";
            session.sendMessage(new TextMessage(errorEvent));
            return;
        }

        // Broadcast to all clients in the room (include message id for deduplication)
        Map<String, Object> broadcastData = new java.util.HashMap<>();
        broadcastData.put("content", content);
        broadcastData.put("senderType", senderType);
        broadcastData.put("characterId", characterId != null ? characterId : "");
        broadcastData.put("userId", msgUserId != null ? msgUserId : "");
        broadcastData.put("roomId", roomId);
        if (messageId != null) {
            broadcastData.put("id", messageId);
        }
        String broadcastMessage = "42[\"chat message\","
            + objectMapper.writeValueAsString(broadcastData)
            + "]";
        broadcastToRoom(roomId, broadcastMessage);

        // Trigger AI response for user messages
        if ("USER".equals(senderType)) {
            // Get userId from sessionUsers map (set during join room)
            String userId = sessionUsers.get(session.getId());
            log.info("[WS] handleChatMessage - roomId: {}, userId from session: {}", roomId, userId);

            // Check if room is in discussion mode
            Room room = roomRepository.findWithCharactersById(UUID.fromString(roomId)).orElse(null);
            boolean isDiscussionMode = room != null && "discussion".equals(room.getChatMode());

            if (isDiscussionMode) {
                // 用 per-room 锁把 "isRunning 检查 + 启动" 包成一个原子段：
                // 否则并发用户消息会双双通过 isRunning=false 检查，启动两条并行讨论循环，
                // 后启动的会覆盖前者的 roomDiscussionState 导致 round 计数错乱。
                Object roomLock = roomDiscussionLocks.computeIfAbsent(roomId, k -> new Object());
                synchronized (roomLock) {
                    if (moderatorAgent.isDiscussionRunning(roomId)) {
                        // 用户插话，立即中断并重新组织讨论
                        moderatorAgent.handleUserInterjection(roomId, userId, content);
                        log.info("[WS] Discussion mode: user interjection, reorganizing discussion");
                    } else {
                        // Start new discussion
                        log.info("[WS] Discussion mode: starting new discussion");
                        triggerAIForRoom(roomId, content, userId, null);
                    }
                }
            } else {
                // In dialogue mode, use moderator to select who should respond
                // This ensures only the relevant character replies, not everyone
                log.info("[WS] Dialogue mode: routing through moderator for character selection");
                triggerAIViaModerator(roomId, content, userId, room);
            }
        }
    }

    /**
     * Extract character name from message content.
     * Supports multiple formats:
     * 1. @角色名 - @mention format (takes priority)
     * 2. 角色名你怎么看 - direct name followed by question (no space needed)
     * 3. 角色名 at message start - matched against room characters
     *
     * Returns the matched character name or null if no explicit mention found.
     */
    /**
     * 从用户消息文本中提取显式 @ 提及的角色名，是 Java 端唯一保留的结构化路由规则。
     * 支持三种格式：@角色名、角色名+疑问词（无空格）、消息开头的角色名（带空格）。
     * 返回房间内匹配的角色名（原始大小写）或 null；调用方：triggerAIViaModerator。
     */
    private String extractMentionedCharacter(String content, List<Character> characters) {
        if (content == null || content.isBlank() || characters == null || characters.isEmpty()) {
            return null;
        }

        String trimmed = content.trim();

        // Format 1: @角色名 - extract name after @
        if (trimmed.startsWith("@")) {
            String afterAt = trimmed.substring(1);
            // Split by whitespace or common punctuation
            String[] parts = afterAt.split("[\\s，。！？、,.!?\\[\\](){}《》]");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                String mentioned = parts[0];
                // First try exact match
                for (Character c : characters) {
                    if (c.getName().equalsIgnoreCase(mentioned)) {
                        return c.getName(); // Return actual character name
                    }
                }
                // Then try prefix match (for Chinese names without space separator)
                for (Character c : characters) {
                    if (mentioned.toLowerCase().startsWith(c.getName().toLowerCase())) {
                        return c.getName(); // Return actual character name
                    }
                }
            }
        }

        // Format 2: 角色名 + 疑问词 (no space) - e.g., "马云你怎么看", "马化腾你觉得呢"
        // Check if message starts with a character name followed by a question word
        for (Character c : characters) {
            String name = c.getName();
            if (trimmed.toLowerCase().startsWith(name.toLowerCase())) {
                String afterName = trimmed.substring(name.length());
                // If what follows looks like a question or comment, it's a mention
                if (afterName.matches("[，,， ].*") || afterName.matches("[？?！!].*") || afterName.matches(".*[你说看觉得怎么看觉得如何怎么样].*")) {
                    return c.getName();
                }
            }
        }

        // Format 3: 角色名 at message start (with space separator)
        String[] words = trimmed.split("[ \\t\\n\\r\\f]");
        if (words.length > 0) {
            String firstWord = words[0];
            if (firstWord.length() <= 30 && !firstWord.contains("@")) {
                // Only return if this word matches a character name in the room
                for (Character c : characters) {
                    if (c.getName().equalsIgnoreCase(firstWord)) {
                        return c.getName(); // Return actual character name
                    }
                }
            }
        }

        return null;
    }

    /**
     * Routing is delegated to the moderator LLM via the joint prompt. The only
     * structural rule we keep in Java is explicit @-mention detection (above).
     * Topical / thread-continuity / multi-character decisions are all made by
     * the LLM reading the character roster + chat history.
     */

    /**
     * triggerAIForRoom 的三参重载：不指定提及角色时调用四参版本，让 moderator 自行决定发言者。
     * 调用方：外部测试 / 业务代码。
     */
    public void triggerAIForRoom(String roomId, String userMessage, String userId) {
        triggerAIForRoom(roomId, userMessage, userId, null);
    }

    /**
     * 触发指定房间的 AI 回复流程：@mention 时收敛到单角色，否则把全部角色交给 moderator 编排。
     * 通过 moderatorAgent.processMessage 注入四个回调（onThinking / onChunk / onResponse / onError），
     * 完成流式广播、最终回复持久化、错误事件转发；discussion 模式下走多轮循环。
     * 调用方：handleChatMessage、handleTriggerAI、triggerAIViaModerator。
     */
    public void triggerAIForRoom(String roomId, String userMessage, String userId, String mentionedCharacter) {
        log.info("[WS] triggerAIForRoom START - roomId: {}, message: {}, userId: {}, mentionedCharacter: {}",
            roomId, userMessage, userId, mentionedCharacter);

        // userId is now passed directly from handleChatMessage (retrieved from sessionUsers map during join room)

        // Even if userId is null, continue - we'll use system API key in that case
        try {
            Room room = roomRepository.findWithCharactersById(UUID.fromString(roomId)).orElse(null);
            if (room == null || room.getCharacters().isEmpty()) {
                log.warn("[WS] triggerAIForRoom - room is null or has no characters");
                return;
            }

            List<Character> allCharacters = room.getCharacters().stream().toList();

            // Filter to only mentioned character if @mentioned, otherwise all characters respond
            List<Character> characters;
            if (mentionedCharacter != null && !mentionedCharacter.isBlank()) {
                // Match character by name (case-insensitive)
                characters = allCharacters.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(mentionedCharacter))
                    .toList();
                if (characters.isEmpty()) {
                    log.warn("[WS] No character found matching @{}", mentionedCharacter);
                    // Fallback: no one responds
                    return;
                }
                log.info("[WS] @mention detected: {} -> character: {}", mentionedCharacter, characters.get(0).getName());
            } else {
                characters = allCharacters;
            }

            boolean isContinuous = "discussion".equals(room.getChatMode());
            int maxRounds = room.getMaxDiscussionRounds() != null ? room.getMaxDiscussionRounds() : 5;

            log.info("[WS] Calling moderatorAgent.processMessage - charCount: {}, isContinuous: {}",
                characters.size(), isContinuous);

            moderatorAgent.processMessage(roomId, userId, userMessage, characters, isContinuous, maxRounds,
                // onThinking: 不发送沉思状态，直接流式输出
                characterId -> {
                    log.debug("[WS] onThinking callback (ignored) - characterId: {}", characterId);
                },
                // onChunk: 流式内容 - 立即发送到前端
                fragment -> {
                    try {
                        if (!fragment.isComplete()) {
                            // 这是流式内容，立即广播
                            Map<String, Object> chunkData = new java.util.HashMap<>();
                            chunkData.put("content", fragment.getContent());
                            chunkData.put("senderType", "CHARACTER");
                            chunkData.put("characterId", fragment.getCharacterId());
                            chunkData.put("characterName", fragment.getCharacterName());
                            // (truncated for display)
                            chunkData.put("roomId", roomId);
                            chunkData.put("streaming", true);
                            String chunkEvent = "42[\"chat chunk\","
                                + objectMapper.writeValueAsString(chunkData)
                                + "]";
                            broadcastToRoom(roomId, chunkEvent);
                        }
                    } catch (Exception e) {
                        log.warn("[WS] onChunk callback failed: {}", e.getMessage());
                    }
                },
                // onResponse: 收到角色完整回复
                fragment -> {
                    try {
                        log.info("[WS] onResponse callback - characterId: {}, content length: {}, isComplete: {}",
                            fragment.getCharacterId(), fragment.getContent().length(), fragment.isComplete());

                        // Update last speaker and active thread owner for this room
                        roomLastSpeaker.put(roomId, fragment.getCharacterName());
                        roomActiveThreadOwner.put(roomId, fragment.getCharacterName());
                        log.info("[WS] Updated roomLastSpeaker and activeThreadOwner for room {}: {}", roomId, fragment.getCharacterName());

                        // Store recent response for cross-agent context
                        addRecentResponse(roomId, fragment.getCharacterName(), fragment.getContent());

                        // 保存消息到数据库
                        UUID characterUuid = UUID.fromString(fragment.getCharacterId());
                        Message savedMessage = messageService.saveMessage(UUID.fromString(roomId), characterUuid,
                            Message.SenderType.CHARACTER, fragment.getContent(), null);

                        // 广播到 WebSocket 房间（包含 message id 用于去重）
                        Map<String, Object> responseData = new java.util.HashMap<>();
                        responseData.put("content", fragment.getContent());
                        responseData.put("senderType", "CHARACTER");
                        responseData.put("characterId", fragment.getCharacterId());
                        responseData.put("characterName", fragment.getCharacterName());
                        responseData.put("avatarUrl", fragment.getAvatarUrl());
                        responseData.put("roomId", roomId);
                        responseData.put("id", savedMessage.getId());
                        responseData.put("streaming", false);
                        String responseEvent = "42[\"chat message\","
                            + objectMapper.writeValueAsString(responseData)
                            + "]";
                        log.info("[WS] Broadcasting final response to room: {}, event: {}", roomId, responseEvent);
                        broadcastToRoom(roomId, responseEvent);
                    } catch (Exception e) {
                        log.error("[WS] Failed to send response event: {}", e.getMessage(), e);
                    }
                },
                // onError: structured error (e.g. missing API key) — broadcast to room
                err -> {
                    try {
                        String errorEvent = "42[\"error\","
                            + objectMapper.writeValueAsString(Map.of(
                                "message", err.getMessage(),
                                "code", err.getCode().name()
                            ))
                            + "]";
                        log.info("[WS] Broadcasting moderator error to room {}: code={}, message={}",
                            roomId, err.getCode(), err.getMessage());
                        broadcastToRoom(roomId, errorEvent);
                    } catch (Exception ex) {
                        log.warn("[WS] Failed to send moderator error event: {}", ex.getMessage());
                    }
                }
            );

            log.info("[WS] triggerAIForRoom - moderatorAgent.processMessage called, returning");
        } catch (Exception e) {
            log.error("[WS] triggerAIForRoom - exception: {}", e.getMessage(), e);
            // Broadcast AI error to room
            try {
                String errorEvent = "42[\"error\","
                    + objectMapper.writeValueAsString(Map.of(
                        "message", "AI 服务调用失败: " + e.getMessage()
                    ))
                    + "]";
                broadcastToRoom(roomId, errorEvent);
            } catch (Exception ex) {
                log.warn("[WS] Failed to send error event: {}", ex.getMessage());
            }
        }
    }

    /**
     * 处理客户端 trigger-ai 事件：前端在某些场景下手动触发 AI 续接（无需伴随新用户消息）。
     * 从 SecurityContext 取已认证 userId，要求消息中带 roomId；校验通过后调用 moderatorAgent.processMessage。
     * 调用方：handleSocketIOMessage。
     */
    private void handleTriggerAI(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        String userMessage = data.has("message") ? data.get("message").asText() : "";

        // Get userId from SecurityContext (set during join room)
        String userId = null;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() != null) {
                userId = auth.getPrincipal().toString();
            }
        } catch (Exception e) {
            log.error("[WS] handleTriggerAI - failed to get userId: {}", e.getMessage());
        }

        if (userId == null) {
            session.sendMessage(new TextMessage("42[\"error\",{\"message\":\"User not authenticated\"}]"));
            return;
        }

        Room room = roomRepository.findWithCharactersById(UUID.fromString(roomId)).orElse(null);
        if (room == null) {
            session.sendMessage(new TextMessage("42[\"error\",{\"message\":\"Room not found\"}]"));
            return;
        }

        List<Character> characters = room.getCharacters().stream().toList();
        if (characters.isEmpty()) {
            session.sendMessage(new TextMessage("42[\"error\",{\"message\":\"No characters in room\"}]"));
            return;
        }

        boolean isContinuous = "discussion".equals(room.getChatMode());
        int maxRounds = room.getMaxDiscussionRounds() != null ? room.getMaxDiscussionRounds() : 5;

        // 使用 ModeratorAgent 进行智能发言编排和流式响应
        moderatorAgent.processMessage(roomId, userId, userMessage, characters, isContinuous, maxRounds,
            // onThinking: 角色开始思考
            characterId -> {
                String event = "42[\"character thinking\",{\"characterId\":\"" + characterId + "\"}]";
                broadcastToRoom(roomId, event);
            },
            // onChunk: 流式内容片段（用于实时更新 UI）
            fragment -> {
                log.info("[WS] onChunk callback CALLED charId={} contentLen={}", fragment.getCharacterId(), fragment.getContent().length());
                try {
                    Map<String, Object> chunkData = new java.util.HashMap<>();
                    chunkData.put("content", fragment.getContent());
                    chunkData.put("senderType", "CHARACTER");
                    chunkData.put("characterId", fragment.getCharacterId());
                    chunkData.put("characterName", fragment.getCharacterName());
                    chunkData.put("roomId", roomId);
                    String chunkEvent = "42[\"chat chunk\","
                        + objectMapper.writeValueAsString(chunkData)
                        + "]";
                    log.info("[WS] onChunk TS={} charId={} chunkLen={} chunkPreview='{}'",
                        System.currentTimeMillis(), fragment.getCharacterId(),
                        fragment.getContent().length(), fragment.getContent());
                    broadcastToRoom(roomId, chunkEvent);
                } catch (Exception e) {
                    // Log error
                }
            },
            // onResponse: 收到角色完整回复
            fragment -> {
                try {
                    // 保存消息到数据库
                    UUID characterUuid = UUID.fromString(fragment.getCharacterId());
                    Message savedMessage = messageService.saveMessage(UUID.fromString(roomId), characterUuid,
                        Message.SenderType.CHARACTER, fragment.getContent(), null);

                    // Update active thread owner
                    roomActiveThreadOwner.put(roomId, fragment.getCharacterName());
                    roomLastSpeaker.put(roomId, fragment.getCharacterName());

                    // 广播到 WebSocket 房间（包含 message id 用于去重）
                    Map<String, Object> responseData = new java.util.HashMap<>();
                    responseData.put("content", fragment.getContent());
                    responseData.put("senderType", "CHARACTER");
                    responseData.put("characterId", fragment.getCharacterId());
                    responseData.put("characterName", fragment.getCharacterName());
                    responseData.put("avatarUrl", fragment.getAvatarUrl());
                    responseData.put("roomId", roomId);
                    responseData.put("id", savedMessage.getId());
                    String responseEvent = "42[\"chat message\","
                        + objectMapper.writeValueAsString(responseData)
                        + "]";
                    broadcastToRoom(roomId, responseEvent);
                } catch (Exception e) {
                    // Log error
                }
            },
            // onError: structured error (e.g. missing API key) — broadcast to room
            err -> {
                try {
                    String errorEvent = "42[\"error\","
                        + objectMapper.writeValueAsString(Map.of(
                            "message", err.getMessage(),
                            "code", err.getCode().name()
                        ))
                        + "]";
                    log.info("[WS] Broadcasting moderator error to room {}: code={}, message={}",
                        roomId, err.getCode(), err.getMessage());
                    broadcastToRoom(roomId, errorEvent);
                } catch (Exception ex) {
                    log.warn("[WS] Failed to send moderator error event: {}", ex.getMessage());
                }
            }
        );
    }

    /**
     * 处理客户端 pause-discussion 事件：通知 ModeratorAgent 暂停多轮讨论循环（保留当前状态），并广播确认。
     * 调用方：handleSocketIOMessage。
     */
    private void handlePauseDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Pause discussion requested for room: {}", roomId);

        moderatorAgent.pauseDiscussion(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-paused\",{\"roomId\":\"" + roomId + "\"}]"));
        broadcastToRoom(roomId, "42[\"discussion-paused\",{\"roomId\":\"" + roomId + "\"}]");
    }

    /**
     * 处理客户端 resume-discussion 事件：从暂停状态恢复 ModeratorAgent 的讨论循环，并广播确认。
     * 调用方：handleSocketIOMessage。
     */
    private void handleResumeDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Resume discussion requested for room: {}", roomId);

        moderatorAgent.resumeDiscussion(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-resumed\",{\"roomId\":\"" + roomId + "\"}]"));
        broadcastToRoom(roomId, "42[\"discussion-resumed\",{\"roomId\":\"" + roomId + "\"}]");
    }

    /**
     * 处理客户端 stop-discussion 事件：彻底取消 ModeratorAgent 中该房间的进行中讨论（清理状态）。
     * 仅向发起方回确认帧，不广播——避免对房间内其他旁观者造成"被中断"的误读。
     * 调用方：handleSocketIOMessage。
     */
    private void handleStopDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Stop discussion requested for room: {}", roomId);

        // Cancel ongoing discussion in ModeratorAgent
        moderatorAgent.cancelRoom(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-stopped\",{\"roomId\":\"" + roomId + "\"}]"));
    }

    /**
     * Route AI response through moderator for dialogue mode.
     * This ensures only the relevant character responds based on:
     * 1. Thread continuity (active conversation thread owner) - HIGHEST PRIORITY
     * 2. Explicit @mention
     * 3. Last speaker (if user message is contextual)
     * 4. Moderator semantic analysis
     */
    /**
     * dialogue 模式下的发言路由：唯一保留的 Java 端结构化规则是显式 @mention；
     * 其它场景（话题续接、群邀请、多角色挑选）一律交由 moderator LLM 在 joint prompt 中决策。
     * 检测到 @mention 时把该角色钉为 thread owner 并附上最近上下文；否则把全部角色交给 moderator。
     * 调用方：handleChatMessage。
     */
    private void triggerAIViaModerator(String roomId, String content, String userId, Room room) {
        try {
            if (room == null) {
                log.warn("[WS] triggerAIViaModerator - room is null");
                return;
            }

            List<Character> allCharacters = room.getCharacters().stream().toList();
            if (allCharacters.isEmpty()) {
                log.warn("[WS] triggerAIViaModerator - no characters in room");
                return;
            }

            // Routing policy: Java only handles the ONE thing it's good at —
            // structural detection of explicit @-mentions. Everything else
            // (topical matching, group invitations, thread continuity, "should
            // I pick 1 or 3 speakers?") is delegated to the moderator LLM via
            // the joint prompt. Hand-coding keyword tables in Java for this
            // is brittle and lossy by construction.

            // 1. Explicit single @-mention (e.g., "@马化腾 你怎么看") — route to that one.
            String mentionedCharacter = extractMentionedCharacter(content, allCharacters);
            if (mentionedCharacter != null) {
                log.info("[WS] triggerAIViaModerator - explicit @mention detected: {}", mentionedCharacter);
                roomLastSpeaker.put(roomId, mentionedCharacter);
                roomActiveThreadOwner.put(roomId, mentionedCharacter);
                String messageWithContext = content + getRecentContext(roomId);
                triggerAIForRoom(roomId, messageWithContext, userId, mentionedCharacter);
                return;
            }

            // 2. Anything else — pass ALL characters to the moderator LLM and let
            //    it decide who speaks (1, several, or nobody), based on the joint
            //    prompt's routing rules + the room's actual character personas.
            log.info("[WS] triggerAIViaModerator - delegating to moderator (no @mention, {} chars)", allCharacters.size());
            triggerAIForRoom(roomId, content, userId, null);

        } catch (Exception e) {
            log.error("[WS] triggerAIViaModerator - error: {}", e.getMessage(), e);
        }
    }

    // Track active conversation thread owner per room (distinct from lastSpeaker for clearer thread semantics)
    // Field is declared near other room-* maps above.

    /**
     * Update the active thread owner for a room after a character responds.
     * Call this after a character's complete response is broadcast.
     */
    /**
     * 更新房间的主线发言持有者：在角色完整回复落库后由业务侧调用，把 thread owner 切换到当前发言人。
     * 调用方：外部业务代码 / ModeratorAgent 回调链；null 入参做安全短路。
     */
    public void updateRoomActiveThread(String roomId, String characterName) {
        if (roomId == null || characterName == null) return;
        roomActiveThreadOwner.put(roomId, characterName);
        log.info("[WS] Updated active thread owner for room {}: {}", roomId, characterName);
    }

    // NOTE: Previously this class contained isOpenEndedQuestion,
    // isShortOrContextualMessage, pickTopicalTarget, and firstLongChineseWord —
    // hand-coded routing heuristics. Removed in the "routing to LLM" refactor.
    // The moderator LLM (joint prompt) is now the single source of truth for
    // who speaks, how many, and in what order. Java only detects explicit
    // @-mentions, which is a structural signal.

    /**
     * Store a recent AI response for cross-agent context.
     */
    /**
     * 将一条 AI 回复追加进房间最近上下文队列（CopyOnWriteArrayList，线程安全）；
     * 队列超过 5 条时丢最早的，供后续 prompt 拼上下文用——是有界窗口避免 prompt 膨胀。
     * 调用方：triggerAIForRoom 内 onResponse 回调 / 外部测试。
     */
    public void addRecentResponse(String roomId, String characterName, String content) {
        if (roomId == null || characterName == null || content == null) return;
        List<RecentResponse> responses = roomRecentResponses.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>());
        responses.add(new RecentResponse(characterName, content));
        // Keep only last 5 responses
        while (responses.size() > 5) {
            responses.remove(0);
        }
        log.info("[WS] Added recent response from {} in room {}, total recent responses: {}",
            characterName, roomId, responses.size());
    }

    /**
     * Get recent responses as a formatted string for context.
     */
    /**
     * 把房间最近 5 条 AI 回复格式化成 markdown 文本片段，注入到下一次 prompt 的 system 提示中做跨角色感知。
     * 单条超过 200 字会截断，避免长上下文压垮 token 上限；空房间返回空串。
     * 调用方：triggerAIViaModerator。
     */
    public String getRecentContext(String roomId) {
        List<RecentResponse> responses = roomRecentResponses.get(roomId);
        if (responses == null || responses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[Recent conversation context - other characters said:]\n");
        for (RecentResponse r : responses) {
            // Truncate long responses
            String truncated = r.content.length() > 200 ? r.content.substring(0, 200) + "..." : r.content;
            sb.append("- ").append(r.characterName).append(": \"").append(truncated).append("\"\n");
        }
        return sb.toString();
    }

    /**
     * WebSocket 关闭钩子：从房间注册表移除会话、清 SecurityContext；
     * 当房间空时一并清理 per-room 状态（thread owner / last speaker / recent responses / discussion lock）防止长期运行的内存泄漏。
     * 调用方：Spring WebSocket 容器。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Remove session from its room
        String roomId = sessionRooms.remove(session.getId());
        if (roomId != null) {
            leaveRoom(roomId, session);
        }
        // Remove user mapping
        sessionUsers.remove(session.getId());
        // Clear SecurityContext for this session
        SecurityContextHolder.clearContext();

        // If no more sessions remain in this room, release per-room state to prevent unbounded growth.
        Set<WebSocketSession> remaining = roomId != null ? rooms.get(roomId) : null;
        if (roomId != null && (remaining == null || remaining.isEmpty())) {
            roomActiveThreadOwner.remove(roomId);
            roomLastSpeaker.remove(roomId);
            roomRecentResponses.remove(roomId);
            // 只有房间为空时才释放锁对象；如果还有其它会话在线，保留供它们继续互斥
            if (remaining == null || remaining.isEmpty()) {
                roomDiscussionLocks.remove(roomId);
            }
            log.debug("[WS] Released per-room state for empty room {}", roomId);
        }
    }

    /**
     * 把会话挂到指定房间注册表，并写入 sessionId -> roomId 反向索引。
     * 调用方：handleJoinRoom；线程安全：ConcurrentHashMap + HashSet 组合（HashSet 内的写由调用方保证单线程触发 join/leave）。
     */
    public void joinRoom(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> new HashSet<>()).add(session);
        sessionRooms.put(session.getId(), roomId);
    }

    /**
     * 把会话从指定房间注册表中移除；房间集合空时整张 roomId 条目也清理。
     * 调用方：handleLeaveRoom、afterConnectionClosed。
     */
    public void leaveRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null) {
            room.remove(session);
            if (room.isEmpty()) {
                rooms.remove(roomId);
            }
        }
        sessionRooms.remove(session.getId());
    }

    /**
     * 把已组装好的 Socket.IO 帧原文广播到指定房间的所有在线会话，跳过已关闭的会话。
     * 调用方：triggerAIForRoom、handlePauseDiscussion、handleResumeDiscussion、broadcastToRoom(event,data) 重载。
     */
    public void broadcastToRoom(String roomId, String message) {
        log.info("[WS] broadcastToRoom CALLED roomId={} msgLen={} rooms={}",
            roomId, message != null ? message.length() : 0, rooms.keySet());
        Set<WebSocketSession> room = rooms.get(roomId);
        if (room != null && message != null) {
            TextMessage textMessage = new TextMessage(message);
            for (WebSocketSession s : room) {
                if (s.isOpen()) {
                    try {
                        s.sendMessage(textMessage);
                    } catch (Exception e) {
                        // Handle send error
                    }
                }
            }
        }
    }

    /**
     * Broadcast an event with data to all clients in a room.
     * Automatically wraps data in Socket.IO format (42["event",data]).
     */
    /**
     * 广播便捷重载：把事件名 + 数据对象自动包装成 Socket.IO 帧（42["event", data]）后再走字符串广播。
     * 调用方：外部业务代码（moderator-message 等系统事件）。
     */
    public void broadcastToRoom(String roomId, String event, Object data) {
        try {
            String message = "42[\"" + event + "\"," + objectMapper.writeValueAsString(data) + "]";
            broadcastToRoom(roomId, message);
        } catch (Exception e) {
            log.warn("[WS] broadcastToRoom(event, data) failed: {}", e.getMessage());
        }
    }

    /**
     * 构造一条 ModeratorMessage（系统提示类消息，如 moderator 自身发言）并广播到房间。
     * 调用方：当前未在文件内引用，预留给后续 moderator 系统消息场景。
     */
    private void broadcastModeratorMessage(String roomId, String content, String type) {
        ModeratorMessage message = new ModeratorMessage(content, type);
        broadcastToRoom(roomId, "moderator-message", message);
    }
}

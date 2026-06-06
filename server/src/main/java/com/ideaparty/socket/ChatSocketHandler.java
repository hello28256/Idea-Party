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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> roomLastSpeaker = new ConcurrentHashMap<>();  // Track last speaker per room
    // Track recent AI responses per room for cross-agent context (keeps last 5 responses)
    private final ConcurrentHashMap<String, List<RecentResponse>> roomRecentResponses = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private final MessageService messageService;
    private final ModerationService moderationService;
    private final RoomRepository roomRepository;
    private final ModeratorAgent moderatorAgent;
    private final AuthService authService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Socket.IO client sends "40" (connect packet) initially
    }

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

    private void handleLeaveRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        leaveRoom(roomId, session);
    }

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
            log.error("[WS] Failed to save message: {}", e.getMessage());
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
                // In discussion mode, start or continue the discussion
                // Check if discussion is already running
                if (moderatorAgent.isDiscussionRunning(roomId)) {
                    // 用户插话，立即中断并重新组织讨论
                    moderatorAgent.handleUserInterjection(roomId, userId, content);
                    log.info("[WS] Discussion mode: user interjection, reorganizing discussion");
                } else {
                    // Start new discussion
                    log.info("[WS] Discussion mode: starting new discussion");
                    triggerAIForRoom(roomId, content, userId, null);
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
     * Extract multiple character names from message content for multi-target scenarios.
     * e.g., "马云和马化腾观点有什么不同" → [马云, 马化腾]
     */
    private List<String> extractMultipleMentions(String content, List<Character> characters) {
        List<String> mentioned = new ArrayList<>();
        if (content == null || characters == null || characters.isEmpty()) {
            return mentioned;
        }

        String trimmed = content.trim();

        // Check for "角色1和角色2" or "角色1、角色2" patterns
        // Both names can appear before or after the separator
        for (Character c : characters) {
            String name = c.getName();
            // Check if this character name appears in the content
            if (trimmed.contains(name)) {
                // Check if it's part of a "X和Y" or "X、Y" pattern
                // X can be before OR after the separator
                String beforeAnd = name + "和";
                String beforeComma = name + "、";
                String afterAnd = "和" + name;
                String afterComma = "、" + name;

                boolean isPartOfMultiTarget =
                    trimmed.contains(beforeAnd) || trimmed.contains(beforeComma) ||
                    trimmed.contains(afterAnd) || trimmed.contains(afterComma);

                if (isPartOfMultiTarget && !mentioned.contains(name)) {
                    mentioned.add(name);
                }
            }
        }

        return mentioned;
    }

    public void triggerAIForRoom(String roomId, String userMessage, String userId) {
        triggerAIForRoom(roomId, userMessage, userId, null);
    }

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
                            chunkData.put("roomId", roomId);
                            chunkData.put("streaming", true);
                            String chunkEvent = "42[\"message stream\","
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
            }
        );
    }

    private void handlePauseDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Pause discussion requested for room: {}", roomId);

        moderatorAgent.pauseDiscussion(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-paused\",{\"roomId\":\"" + roomId + "\"}]"));
        broadcastToRoom(roomId, "42[\"discussion-paused\",{\"roomId\":\"" + roomId + "\"}]");
    }

    private void handleResumeDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Resume discussion requested for room: {}", roomId);

        moderatorAgent.resumeDiscussion(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-resumed\",{\"roomId\":\"" + roomId + "\"}]"));
        broadcastToRoom(roomId, "42[\"discussion-resumed\",{\"roomId\":\"" + roomId + "\"}]");
    }

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

            // Step 1: Check if user explicitly mentioned a character (overrides thread continuity)
            String mentionedCharacter = extractMentionedCharacter(content, allCharacters);
            String recentContext = getRecentContext(roomId);
            if (mentionedCharacter != null) {
                log.info("[WS] triggerAIViaModerator - explicit @mention detected: {}", mentionedCharacter);
                // Update last speaker when user explicitly mentions someone
                roomLastSpeaker.put(roomId, mentionedCharacter);
                roomActiveThreadOwner.put(roomId, mentionedCharacter);
                // Append recent context so the new character knows what was discussed
                String messageWithContext = content + recentContext;
                triggerAIForRoom(roomId, messageWithContext, userId, mentionedCharacter);
                return;
            }

            // Step 1.5: Check for multi-target mentions (e.g., "马云和马化腾观点有什么不同")
            List<String> multipleMentions = extractMultipleMentions(content, allCharacters);
            if (multipleMentions.size() > 1) {
                log.info("[WS] triggerAIViaModerator - multi-target mentions detected: {}", multipleMentions);
                // Route to first mentioned character for now (sequential responses)
                String firstMention = multipleMentions.get(0);
                roomLastSpeaker.put(roomId, firstMention);
                roomActiveThreadOwner.put(roomId, firstMention);
                String messageWithContext = content + recentContext;
                triggerAIForRoom(roomId, messageWithContext, userId, firstMention);
                return;
            }

            // Step 2: Check thread continuity FIRST (before semantic matching)
            // If message is short/contextual and we have an active thread owner, continue that thread
            String activeThreadOwner = roomActiveThreadOwner.get(roomId);
            boolean isContextualMessage = isShortOrContextualMessage(content);
            if (activeThreadOwner != null && isContextualMessage) {
                // Verify the active thread owner is still in the room
                Character threadOwner = allCharacters.stream()
                    .filter(c -> c.getName().equals(activeThreadOwner))
                    .findFirst()
                    .orElse(null);
                if (threadOwner != null) {
                    log.info("[WS] triggerAIViaModerator - thread continuity: routing to active thread owner: {}", activeThreadOwner);
                    triggerAIForRoom(roomId, content, userId, activeThreadOwner);
                    return;
                }
            }

            // Step 3: Check last speaker if message is short/contextual (fallback to recent speaker)
            String lastSpeaker = roomLastSpeaker.get(roomId);
            if (lastSpeaker != null && isContextualMessage) {
                log.info("[WS] triggerAIViaModerator - using last speaker: {} for contextual message", lastSpeaker);
                triggerAIForRoom(roomId, content, userId, lastSpeaker);
                return;
            }

            // Step 3.5: Check for open-ended question - invite multiple characters
            if (isOpenEndedQuestion(content)) {
                log.info("[WS] triggerAIViaModerator - open-ended question detected, inviting multiple characters");
                // Let all characters respond (pass null to indicate no single target)
                triggerAIForRoom(roomId, content, userId, null);
                return;
            }

            // Step 3.6: Topical routing — if the message mentions a known brand / company
            // / domain that one of the room's characters is the expert of, route to that
            // character even when the user didn't @-mention them. This avoids the
            // 'fallback to first character' problem (e.g. user asks about Tencent and
            // 马化腾 is the obvious answer, not whoever happened to be first in the list).
            String topicalTarget = pickTopicalTarget(content, allCharacters);
            if (topicalTarget != null) {
                log.info("[WS] triggerAIViaModerator - topical routing: {} -> {}", content, topicalTarget);
                roomLastSpeaker.put(roomId, topicalTarget);
                roomActiveThreadOwner.put(roomId, topicalTarget);
                String messageWithContext = content + recentContext;
                triggerAIForRoom(roomId, messageWithContext, userId, topicalTarget);
                return;
            }

            // Step 4: Fallback to first character (simple default)
            log.info("[WS] triggerAIViaModerator - falling back to first character");
            // For first character in a new topic, include recent context
            String messageWithContext = content + recentContext;
            triggerAIForRoom(roomId, messageWithContext, userId, allCharacters.get(0).getName());

        } catch (Exception e) {
            log.error("[WS] triggerAIViaModerator - error: {}", e.getMessage(), e);
        }
    }

    // Track active conversation thread owner per room (distinct from lastSpeaker for clearer thread semantics)
    private final ConcurrentHashMap<String, String> roomActiveThreadOwner = new ConcurrentHashMap<>();

    /**
     * Update the active thread owner for a room after a character responds.
     * Call this after a character's complete response is broadcast.
     */
    public void updateRoomActiveThread(String roomId, String characterName) {
        if (roomId == null || characterName == null) return;
        roomActiveThreadOwner.put(roomId, characterName);
        log.info("[WS] Updated active thread owner for room {}: {}", roomId, characterName);
    }

    /**
     * Check if message is an open-ended question that invites multiple responses.
     * Examples: "大家怎么看", "每个人都说说", "你们觉得呢"
     */
    private boolean isOpenEndedQuestion(String content) {
        if (content == null || content.isBlank()) return false;
        String trimmed = content.trim();

        // First check: direct question patterns - these are NOT open-ended
        // Single person addressed directly
        if (trimmed.matches("^(你|您)觉得.*") ||
            trimmed.matches("^(你|您)怎么.*") ||
            trimmed.matches("^(你|您)看.*")) {
            return false;
        }

        // Ends with question particle "吗" (definite question)
        if (trimmed.matches(".*[吗吗？?]$")) {
            return false;
        }

        // Open-ended patterns - these INVITE multiple responses
        String[] openPatterns = {
            // 直接 @ 群里所有人
            "群里各位", "群里的各位", "在座各位", "群里各位大佬",
            "群里", "群里人", "群里的小伙伴", "群里的大佬",
            "各位", "各位大佬", "各位怎么看", "各位觉得",
            // 经典"大家怎么看"
            "大家", "大家怎么看", "大家觉得", "大家说", "大家说下", "大家聊下",
            "大家有何", "大家有什么", "大家怎么看", "大家怎么看？",
            "你们", "你们都", "你们觉得", "你们怎么看", "你们说",
            "各位说说", "说说", "聊一下", "聊一聊", "讨论一下", "讨论讨论",
            "每个人都", "每个人都说说", "大家都说说", "大家都出来",
            "都有什么", "都有些什么", "都说说", "都发表一下"
        };
        for (String pattern : openPatterns) {
            if (trimmed.contains(pattern)) {
                return true;
            }
        }

        // If starts with "大家" or "你们" and short, likely open-ended
        if ((trimmed.startsWith("大家") || trimmed.startsWith("你们")) && trimmed.length() <= 15) {
            return true;
        }

        return false;
    }

    /**
     * Store a recent AI response for cross-agent context.
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
     * Check if a message is short or highly contextual (likely a reply).
     * Used for thread continuity decisions.
     */
    private boolean isShortOrContextualMessage(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();

        // If message ends with question mark, NOT contextual (direct question)
        if (trimmed.contains("？") || trimmed.contains("?")) {
            return false;
        }

        // Short messages (less than 8 chars for Chinese) - highly likely to be contextual
        if (trimmed.length() <= 8) {
            return true;
        }

        // Contextual phrases that indicate a reply - only for relatively short messages
        if (trimmed.length() <= 30) {
            String[] contextualPhrases = {
                "好", "好的", "同意", "有道理", "对", "没错",
                "继续", "展开", "为什么", "不是", "我不同意", "我同意",
                "哈哈", "嗯", "嗯嗯", "有意思", "继续说", "然后呢", "后来呢"
            };
            for (String phrase : contextualPhrases) {
                if (trimmed.equals(phrase) || trimmed.startsWith(phrase + " ") || trimmed.endsWith(" " + phrase)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Pick the best character to answer based on topical keywords in the message.
     * Two strategies:
     *   1. Hard-coded well-known keyword → character map (e.g. "腾讯" → 马化腾)
     *   2. Scan character.name / persona / expertise for words that appear in the message
     * Returns null if no topical match is found (caller should fall through to other rules).
     */
    private String pickTopicalTarget(String content, List<Character> characters) {
        if (content == null || content.isBlank() || characters == null || characters.isEmpty()) {
            return null;
        }
        String lower = content.toLowerCase();

        // Strategy 1: hard-coded well-known mappings (covers the common case where
        // a character data row is sparse or the user mentions a brand directly).
        Map<String, String> hardcoded = new HashMap<>();
        hardcoded.put("腾讯", "马化腾");
        hardcoded.put("微信", "马化腾");
        hardcoded.put("qq", "马化腾");
        hardcoded.put("游戏", "马化腾");
        hardcoded.put("王者荣耀", "马化腾");
        hardcoded.put("阿里", "马云");
        hardcoded.put("淘宝", "马云");
        hardcoded.put("天猫", "马云");
        hardcoded.put("支付宝", "马云");
        hardcoded.put("电商", "马云");
        hardcoded.put("特斯拉", "马斯克");
        hardcoded.put("spacex", "马斯克");
        hardcoded.put("火星", "马斯克");
        hardcoded.put("星链", "马斯克");
        hardcoded.put("starlink", "马斯克");
        hardcoded.put("neuralink", "马斯克");

        for (Map.Entry<String, String> e : hardcoded.entrySet()) {
            if (lower.contains(e.getKey())) {
                Character match = characters.stream()
                    .filter(c -> c.getName().equals(e.getValue()))
                    .findFirst()
                    .orElse(null);
                if (match != null) {
                    return match.getName();
                }
            }
        }

        // Strategy 2: scan each character's persona / expertise for substrings in the
        // message. This catches the long tail where we don't have a hardcoded entry
        // but the character data clearly tags them as the expert.
        for (Character c : characters) {
            String name = c.getName();
            if (name != null && !name.isBlank() && content.contains(name)) {
                return name;
            }
            if (c.getExpertise() != null) {
                for (String tag : c.getExpertise()) {
                    if (tag != null && !tag.isBlank() && content.contains(tag)) {
                        return name;
                    }
                }
            }
            if (c.getPersona() != null) {
                String hit = firstLongChineseWord(c.getPersona(), 2);
                if (hit != null && content.contains(hit)) {
                    return name;
                }
            }
        }

        return null;
    }

    /**
     * Return the first Chinese word of at least {@code minLen} characters from the input.
     * Used to extract a distinctive keyword from a freeform persona string.
     */
    private String firstLongChineseWord(String s, int minLen) {
        if (s == null) return null;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FA5) {
                cur.append(ch);
            } else {
                if (cur.length() >= minLen) return cur.toString();
                cur.setLength(0);
            }
        }
        if (cur.length() >= minLen) return cur.toString();
        return null;
    }

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
    }

    public void joinRoom(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> new HashSet<>()).add(session);
        sessionRooms.put(session.getId(), roomId);
    }

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
    public void broadcastToRoom(String roomId, String event, Object data) {
        try {
            String message = "42[\"" + event + "\"," + objectMapper.writeValueAsString(data) + "]";
            broadcastToRoom(roomId, message);
        } catch (Exception e) {
            log.warn("[WS] broadcastToRoom(event, data) failed: {}", e.getMessage());
        }
    }

    private void broadcastModeratorMessage(String roomId, String content, String type) {
        ModeratorMessage message = new ModeratorMessage(content, type);
        broadcastToRoom(roomId, "moderator-message", message);
    }
}

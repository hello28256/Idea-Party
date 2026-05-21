package com.ideaparty.socket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUsers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
        try {
            Message.SenderType type = Message.SenderType.valueOf(senderType);
            UUID roomUuid = UUID.fromString(roomId);
            UUID characterUuid = characterId != null ? UUID.fromString(characterId) : null;
            Message savedMessage = messageService.saveMessage(roomUuid, characterUuid, type, content);
            messageId = savedMessage.getId();
        } catch (Exception e) {
            log.error("[WS] Failed to save message: {}", e.getMessage());
        }

        // Broadcast to all clients in the room (include message id for deduplication)
        Map<String, Object> broadcastData = new java.util.HashMap<>();
        broadcastData.put("content", content);
        broadcastData.put("senderType", senderType);
        broadcastData.put("characterId", characterId != null ? characterId : "");
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
            triggerAIForRoom(roomId, content, userId);
        }
    }

    public void triggerAIForRoom(String roomId, String userMessage, String userId) {
        log.info("[WS] triggerAIForRoom START - roomId: {}, message: {}, userId: {}", roomId, userMessage, userId);

        // userId is now passed directly from handleChatMessage (retrieved from sessionUsers map during join room)

        // Even if userId is null, continue - we'll use system API key in that case
        try {
            Room room = roomRepository.findWithCharactersById(UUID.fromString(roomId)).orElse(null);
            if (room == null || room.getCharacters().isEmpty()) {
                log.warn("[WS] triggerAIForRoom - room is null or has no characters");
                return;
            }

            List<Character> characters = room.getCharacters().stream().toList();
            boolean isContinuous = "discussion".equals(room.getChatMode());
            int maxRounds = room.getMaxDiscussionRounds() != null ? room.getMaxDiscussionRounds() : 5;

            log.info("[WS] Calling moderatorAgent.processMessage - charCount: {}, isContinuous: {}",
                characters.size(), isContinuous);

            moderatorAgent.processMessage(roomId, userId, userMessage, characters, isContinuous, maxRounds,
                // onThinking: 角色开始思考
                characterId -> {
                    try {
                        log.info("[WS] onThinking callback - characterId: {}", characterId);
                        String event = "42[\"character thinking\",{\"characterId\":\"" + characterId + "\"}]";
                        broadcastToRoom(roomId, event);
                    } catch (Exception e) {
                        log.warn("[WS] Failed to send thinking event: {}", e.getMessage());
                    }
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
                        // 保存消息到数据库
                        UUID characterUuid = UUID.fromString(fragment.getCharacterId());
                        Message savedMessage = messageService.saveMessage(UUID.fromString(roomId), characterUuid,
                            Message.SenderType.CHARACTER, fragment.getContent());

                        // 广播到 WebSocket 房间（包含 message id 用于去重）
                        Map<String, Object> responseData = new java.util.HashMap<>();
                        responseData.put("content", fragment.getContent());
                        responseData.put("senderType", "CHARACTER");
                        responseData.put("characterId", fragment.getCharacterId());
                        responseData.put("characterName", fragment.getCharacterName());
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
            // onResponse: 收到角色回复
            fragment -> {
                try {
                    // 保存消息到数据库
                    UUID characterUuid = UUID.fromString(fragment.getCharacterId());
                    Message savedMessage = messageService.saveMessage(UUID.fromString(roomId), characterUuid,
                        Message.SenderType.CHARACTER, fragment.getContent());

                    // 广播到 WebSocket 房间（包含 message id 用于去重）
                    Map<String, Object> responseData = new java.util.HashMap<>();
                    responseData.put("content", fragment.getContent());
                    responseData.put("senderType", "CHARACTER");
                    responseData.put("characterId", fragment.getCharacterId());
                    responseData.put("characterName", fragment.getCharacterName());
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

    private void handleStopDiscussion(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        log.info("[WS] Stop discussion requested for room: {}", roomId);

        // Cancel ongoing discussion in ModeratorAgent
        moderatorAgent.cancelRoom(roomId);

        session.sendMessage(new TextMessage("42[\"discussion-stopped\",{\"roomId\":\"" + roomId + "\"}]"));
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
}

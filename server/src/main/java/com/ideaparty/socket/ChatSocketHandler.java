package com.ideaparty.socket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.ClaudeService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;

@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageService messageService;
    private final ModerationService moderationService;
    private final RoomRepository roomRepository;
    private final ClaudeService claudeService;

    public ChatSocketHandler(MessageService messageService, ModerationService moderationService,
                             RoomRepository roomRepository, ClaudeService claudeService) {
        this.messageService = messageService;
        this.moderationService = moderationService;
        this.roomRepository = roomRepository;
        this.claudeService = claudeService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Socket.IO client sends "40" (connect packet) initially
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        // Socket.IO protocol: "42" prefix means MESSAGE with event name
        // Format: 42["event_name", data]
        if (payload.startsWith("42")) {
            handleSocketIOMessage(session, payload.substring(2));
        } else if (payload.equals("2")) {
            // "2" is a ping, respond with "3" (pong)
            session.sendMessage(new TextMessage("3"));
        } else if (payload.startsWith("40")) {
            // "40" is a connect packet, respond with "40" (connection acknowledged)
            session.sendMessage(new TextMessage("40"));
        }
    }

    private void handleSocketIOMessage(WebSocketSession session, String data) throws Exception {
        JsonNode node = objectMapper.readTree(data);
        if (node.isArray() && node.size() >= 2) {
            String event = node.get(0).asText();
            JsonNode eventData = node.get(1);

            switch (event) {
                case "join-room":
                    handleJoinRoom(session, eventData);
                    break;
                case "leave-room":
                    handleLeaveRoom(session, eventData);
                    break;
                case "chat message":
                    handleChatMessage(session, eventData);
                    break;
                case "trigger-ai":
                    handleTriggerAI(session, eventData);
                    break;
            }
        }
    }

    private void handleJoinRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        joinRoom(roomId, session);
        session.sendMessage(new TextMessage("42[\"room-joined\",{\"roomId\":\"" + roomId + "\"}]"));
    }

    private void handleLeaveRoom(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        leaveRoom(roomId, session);
    }

    private void handleChatMessage(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        String content = data.get("content").asText();
        String role = data.has("role") ? data.get("role").asText() : "user";
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

        // Save message to database
        try {
            messageService.saveMessage(roomId, content, role, characterId);
        } catch (Exception e) {
            // Log error but continue broadcasting
        }

        // Broadcast to all clients in the room
        String broadcastMessage = "42[\"chat message\","
            + objectMapper.writeValueAsString(Map.of(
                "content", content,
                "role", role,
                "characterId", characterId != null ? characterId : "",
                "roomId", roomId
            ))
            + "]";
        broadcastToRoom(roomId, broadcastMessage);
    }

    private void handleTriggerAI(WebSocketSession session, JsonNode data) throws Exception {
        String roomId = data.get("roomId").asText();
        String message = data.get("message").asText();

        // Fetch room with characters from repository
        Room room = roomRepository.findById(roomId).orElse(null);
        if (room == null) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "Room not found"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        List<Character> characters = room.getCharacters().stream().toList();
        if (characters.isEmpty()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "No characters in room"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // Subscribe to AI response stream
        claudeService.streamMessage(roomId, characters, message)
            .subscribe(
                // onNext: broadcast each chunk
                aiResponse -> {
                    try {
                        if (!aiResponse.chunk().isEmpty()) {
                            String chunkMessage = "42[\"ai-chunk\","
                                + objectMapper.writeValueAsString(Map.of(
                                    "content", aiResponse.chunk(),
                                    "characterId", aiResponse.characterId() != null ? aiResponse.characterId() : "",
                                    "characterName", aiResponse.characterName() != null ? aiResponse.characterName() : ""
                                ))
                                + "]";
                            broadcastToRoom(roomId, chunkMessage);
                        }

                        // onComplete: save message and broadcast final
                        if (aiResponse.isComplete()) {
                            String fullContent = aiResponse.chunk();
                            if (fullContent != null && !fullContent.isEmpty()) {
                                messageService.saveMessage(roomId, fullContent, "character", aiResponse.characterId());
                                String completeMessage = "42[\"ai-complete\","
                                    + objectMapper.writeValueAsString(Map.of(
                                        "content", fullContent,
                                        "characterId", aiResponse.characterId() != null ? aiResponse.characterId() : "",
                                        "characterName", aiResponse.characterName() != null ? aiResponse.characterName() : "",
                                        "messageId", "ai-" + System.currentTimeMillis()
                                    ))
                                    + "]";
                                broadcastToRoom(roomId, completeMessage);
                            }
                        }
                    } catch (Exception e) {
                        // Log but don't crash the stream
                    }
                },
                // onError: broadcast error
                error -> {
                    try {
                        String errorMessage = "42[\"error\","
                            + objectMapper.writeValueAsString(Map.of(
                                "message", "AI response failed: " + error.getMessage()
                            ))
                            + "]";
                        broadcastToRoom(roomId, errorMessage);
                    } catch (Exception ignored) {
                        // Ignore serialization errors in error handler
                    }
                }
            );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Remove session from its room
        String roomId = sessionRooms.remove(session.getId());
        if (roomId != null) {
            leaveRoom(roomId, session);
        }
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
        if (room != null) {
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

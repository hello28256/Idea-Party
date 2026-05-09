package com.ideaparty.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.ChatService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.MockAiService;
import com.ideaparty.service.ModerationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WebSocket handler for real-time chat with Socket.IO protocol compatibility.
 * Handles events:
 * - 'join room' { roomId } - user joins room
 * - 'chat message' { roomId, content } - user sends message, triggers ChatService
 * - 'leave room' { roomId } - user leaves room
 *
 * Emits events:
 * - 'message' { MessageDto } - new message
 * - 'character thinking' { characterId } - character started thinking
 * - 'message stream' { characterId, chunk } - streaming response chunk (simulated)
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Maps roomId -> set of sessions
    private final ConcurrentHashMap<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // Maps sessionId -> roomId
    private final ConcurrentHashMap<String, String> sessionRooms = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MessageService messageService;
    private final ChatService chatService;
    private final MockAiService mockAiService;
    private final ModerationService moderationService;
    private final RoomRepository roomRepository;

    public ChatWebSocketHandler(MessageService messageService, ChatService chatService,
                               MockAiService mockAiService, ModerationService moderationService,
                               RoomRepository roomRepository) {
        this.messageService = messageService;
        this.chatService = chatService;
        this.mockAiService = mockAiService;
        this.moderationService = moderationService;
        this.roomRepository = roomRepository;
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
                case "join room":
                    handleJoinRoom(session, eventData);
                    break;
                case "leave room":
                    handleLeaveRoom(session, eventData);
                    break;
                case "chat message":
                    handleChatMessage(session, eventData);
                    break;
                default:
                    // Unknown event - log for debugging
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

        // Check moderation
        ModerationService.ModerationResult result = moderationService.moderate(content);
        if (!result.isAllowed()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", result.getReason()))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // Get room with characters
        Room room = roomRepository.findById(UUID.fromString(roomId)).orElse(null);
        if (room == null) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "Room not found"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        List<Character> characters = room.getCharacters().stream().collect(Collectors.toList());
        if (characters.isEmpty()) {
            String errorMessage = "42[\"error\","
                + objectMapper.writeValueAsString(Map.of("message", "No characters in room"))
                + "]";
            session.sendMessage(new TextMessage(errorMessage));
            return;
        }

        // Use ChatService for round-robin processing
        chatService.processUserMessage(
            java.util.UUID.fromString(roomId),
            content,
            characters,
            // onThinking callback
            (characterId) -> {
                try {
                    String thinkingEvent = "42[\"character thinking\","
                        + objectMapper.writeValueAsString(Map.of("characterId", characterId))
                        + "]";
                    broadcastToRoom(roomId, thinkingEvent);
                } catch (Exception ignored) {}
            },
            // onMessage callback
            (messageDto) -> {
                try {
                    String msgEvent = "42[\"message\","
                        + objectMapper.writeValueAsString(Map.of(
                            "id", messageDto.getId() != null ? messageDto.getId() : "",
                            "roomId", messageDto.getRoomId() != null ? messageDto.getRoomId() : "",
                            "characterId", messageDto.getCharacterId() != null ? messageDto.getCharacterId() : "",
                            "characterName", messageDto.getCharacterName() != null ? messageDto.getCharacterName() : "",
                            "senderType", messageDto.getSenderType() != null ? messageDto.getSenderType() : "",
                            "content", messageDto.getContent() != null ? messageDto.getContent() : "",
                            "createdAt", messageDto.getCreatedAt() != null ? messageDto.getCreatedAt().toString() : ""
                        ))
                        + "]";
                    broadcastToRoom(roomId, msgEvent);
                } catch (Exception ignored) {}
            }
        );
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String roomId = sessionRooms.remove(session.getId());
        if (roomId != null) {
            leaveRoom(roomId, session);
        }
    }

    public void joinRoom(String roomId, WebSocketSession session) {
        rooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
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
                    } catch (Exception ignored) {
                        // Log send error but continue
                    }
                }
            }
        }
    }
}

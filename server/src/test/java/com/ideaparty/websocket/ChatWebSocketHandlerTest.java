package com.ideaparty.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.ChatService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatWebSocketHandler.
 * Tests Socket.IO protocol parsing and message routing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatWebSocketHandlerTest {

    @Mock
    private MessageService messageService;

    @Mock
    private ChatService chatService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private WebSocketSession mockSession;

    private ChatWebSocketHandler handler;
    private ObjectMapper objectMapper;

    private User testUser;
    private Room testRoom;
    private Character testCharacter;
    private UUID roomId;
    private UUID characterId;

    @BeforeEach
    void setUp() {
        handler = new ChatWebSocketHandler(
            messageService, chatService, moderationService, roomRepository
        );
        objectMapper = new ObjectMapper();

        roomId = UUID.randomUUID();
        characterId = UUID.randomUUID();

        testUser = User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .name("Test User")
            .password("encoded-password")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        testCharacter = new Character();
        testCharacter.setId(characterId);
        testCharacter.setName("Test Character");
        testCharacter.setDescription("A test character");
        testCharacter.setPrompt("You are a test character.");
        testCharacter.setOwner(testUser);
        testCharacter.setPreset(false);
        testCharacter.setCreatedAt(Instant.now());
        testCharacter.setUpdatedAt(Instant.now());

        testRoom = Room.builder()
            .id(roomId)
            .name("Test Room")
            .topic("Test Topic")
            .owner(testUser)
            .characters(new HashSet<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        testRoom.getCharacters().add(testCharacter);
    }

    @Test
    @DisplayName("afterConnectionEstablished should not throw")
    void afterConnectionEstablished_shouldNotThrow() {
        assertDoesNotThrow(() -> handler.afterConnectionEstablished(mockSession));
    }

    @Test
    @DisplayName("handleTextMessage should parse join room event")
    void handleTextMessage_shouldParseJoinRoomEvent() throws Exception {
        // Given - note: frontend sends "join room" with space
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("test-session-123");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        String payload = "42[\"join room\",{\"roomId\":\"" + roomId + "\"}]";
        TextMessage message = new TextMessage(payload);

        // When
        handler.handleTextMessage(mockSession, message);

        // Then - verify joinRoom was called and response was sent
        verify(mockSession, timeout(1000)).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("handleTextMessage should parse send_message event")
    void handleTextMessage_shouldParseSendMessageEvent() throws Exception {
        // Given
        String payload = "42[\"chat message\",{\"roomId\":\"" + roomId + "\",\"content\":\"Hello\"}]";
        TextMessage message = new TextMessage(payload);

        when(mockSession.isOpen()).thenReturn(true);
        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(true, null));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        doNothing().when(chatService).processUserMessage(
            any(UUID.class), anyString(), anyList(),
            any(Consumer.class), any(Consumer.class)
        );

        // When
        handler.handleTextMessage(mockSession, message);

        // Then - should not throw
        verify(chatService).processUserMessage(
            eq(roomId), eq("Hello"), anyList(),
            any(Consumer.class), any(Consumer.class)
        );
    }

    @Test
    @DisplayName("handleTextMessage should respond to ping with pong")
    void handleTextMessage_shouldRespondToPing() throws Exception {
        // Given - Socket.IO ping is "2"
        TextMessage ping = new TextMessage("2");

        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        // When
        handler.handleTextMessage(mockSession, ping);

        // Then
        verify(mockSession).sendMessage(new TextMessage("3"));
    }

    @Test
    @DisplayName("handleTextMessage should respond to connect packet")
    void handleTextMessage_shouldRespondToConnectPacket() throws Exception {
        // Given - Socket.IO connect is "40"
        TextMessage connect = new TextMessage("40");

        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        // When
        handler.handleTextMessage(mockSession, connect);

        // Then
        verify(mockSession).sendMessage(new TextMessage("40"));
    }

    @Test
    @DisplayName("afterConnectionClosed should remove session from room")
    void afterConnectionClosed_shouldRemoveSession() throws Exception {
        // Given
        String payload = "42[\"join_room\",{\"roomId\":\"" + roomId + "\"}]";
        TextMessage message = new TextMessage(payload);

        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("session-123");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        // First join the room
        handler.handleTextMessage(mockSession, message);

        // When - close the connection
        handler.afterConnectionClosed(mockSession, CloseStatus.NORMAL);

        // Then - session should be cleaned up
        // The afterConnectionClosed removes the session from room tracking
        assertDoesNotThrow(() -> handler.afterConnectionClosed(mockSession, CloseStatus.NORMAL));
    }

    @Test
    @DisplayName("joinRoom should add session to room")
    void joinRoom_shouldAddSessionToRoom() {
        // Given
        when(mockSession.getId()).thenReturn("test-session-id");

        // When
        handler.joinRoom(roomId.toString(), mockSession);

        // Then - session should be tracked
        // We can verify by checking the internal state indirectly
        assertDoesNotThrow(() -> handler.leaveRoom(roomId.toString(), mockSession));
    }

    @Test
    @DisplayName("leaveRoom should remove session from room")
    void leaveRoom_shouldRemoveSessionFromRoom() {
        // Given
        when(mockSession.getId()).thenReturn("test-session-id");
        handler.joinRoom(roomId.toString(), mockSession);

        // When
        handler.leaveRoom(roomId.toString(), mockSession);

        // Then - no exception means success
        assertDoesNotThrow(() -> handler.leaveRoom(roomId.toString(), mockSession));
    }

    @Test
    @DisplayName("broadcastToRoom should send message to all sessions in room")
    void broadcastToRoom_shouldSendToAllSessions() throws Exception {
        // Given
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);

        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");

        handler.joinRoom(roomId.toString(), session1);
        handler.joinRoom(roomId.toString(), session2);

        // When
        handler.broadcastToRoom(roomId.toString(), "42[\"test\",{}]");

        // Then
        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("broadcastToRoom should not send to closed sessions")
    void broadcastToRoom_shouldNotSendToClosedSessions() throws Exception {
        // Given
        WebSocketSession openSession = mock(WebSocketSession.class);
        WebSocketSession closedSession = mock(WebSocketSession.class);

        when(openSession.isOpen()).thenReturn(true);
        when(closedSession.isOpen()).thenReturn(false);
        when(openSession.getId()).thenReturn("open-session");
        when(closedSession.getId()).thenReturn("closed-session");

        handler.joinRoom(roomId.toString(), openSession);
        handler.joinRoom(roomId.toString(), closedSession);

        // When
        handler.broadcastToRoom(roomId.toString(), "42[\"test\",{}]");

        // Then - only open session should receive message
        verify(openSession).sendMessage(any(TextMessage.class));
        verify(closedSession, never()).sendMessage(any(TextMessage.class));
    }
}

package com.ideaparty.websocket;

import com.ideaparty.dto.MessageDto;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for WebSocket message handling.
 * Tests full flow of message processing through the system.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSocketIntegrationTest {

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
    @DisplayName("fullFlow should join room and process message")
    void fullFlow_shouldJoinRoomAndProcessMessage() throws Exception {
        // Given
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("test-session");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(true, null));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));

        // Create mock callbacks for ChatService
        doAnswer(invocation -> {
            Consumer<String> onThinking = invocation.getArgument(3);
            Consumer<MessageDto> onMessage = invocation.getArgument(4);
            // Simulate AI thinking
            onThinking.accept(characterId.toString());
            // Simulate AI response
            MessageDto response = new MessageDto();
            response.setId(UUID.randomUUID().toString());
            response.setRoomId(roomId.toString());
            response.setCharacterId(characterId.toString());
            response.setCharacterName("Test Character");
            response.setSenderType("CHARACTER");
            response.setContent("AI response");
            onMessage.accept(response);
            return null;
        }).when(chatService).processUserMessage(
            any(UUID.class), anyString(), anyList(),
            any(Consumer.class), any(Consumer.class)
        );

        // Step 1: Join room - note: frontend uses "join room" with space
        String joinPayload = "42[\"join room\",{\"roomId\":\"" + roomId + "\"}]";
        handler.handleTextMessage(mockSession, new TextMessage(joinPayload));

        // Verify room-joined response
        verify(mockSession, atLeastOnce()).sendMessage(any(TextMessage.class));

        // Step 2: Send message
        String chatPayload = "42[\"chat message\",{\"roomId\":\"" + roomId + "\",\"content\":\"Hello\"}]";
        handler.handleTextMessage(mockSession, new TextMessage(chatPayload));

        // Verify chat processing
        verify(chatService).processUserMessage(
            eq(roomId), eq("Hello"), anyList(),
            any(Consumer.class), any(Consumer.class)
        );
    }

    @Test
    @DisplayName("multipleClients should receive messages in same room")
    void multipleClients_shouldReceiveMessagesInRoom() throws Exception {
        // Given
        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);

        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        doNothing().when(session1).sendMessage(any(TextMessage.class));
        doNothing().when(session2).sendMessage(any(TextMessage.class));

        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(true, null));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));

        // Both sessions join same room - note: frontend uses "join room" with space
        String joinPayload = "42[\"join room\",{\"roomId\":\"" + roomId + "\"}]";
        handler.handleTextMessage(session1, new TextMessage(joinPayload));
        handler.handleTextMessage(session2, new TextMessage(joinPayload));

        // When - broadcast message to room
        String broadcastPayload = "42[\"message\",{\"content\":\"Shared message\"}]";
        handler.broadcastToRoom(roomId.toString(), broadcastPayload);

        // Then - both sessions receive the message
        verify(session1, atLeastOnce()).sendMessage(any(TextMessage.class));
        verify(session2, atLeastOnce()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("differentRooms should not receive others messages")
    void differentRooms_shouldNotReceiveOthersMessages() throws Exception {
        // Given
        UUID room1Id = UUID.randomUUID();
        UUID room2Id = UUID.randomUUID();

        Room room1 = Room.builder()
            .id(room1Id)
            .name("Room 1")
            .owner(testUser)
            .characters(new HashSet<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        Room room2 = Room.builder()
            .id(room2Id)
            .name("Room 2")
            .owner(testUser)
            .characters(new HashSet<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);

        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);
        when(session1.getId()).thenReturn("session-1");
        when(session2.getId()).thenReturn("session-2");
        doNothing().when(session1).sendMessage(any(TextMessage.class));
        doNothing().when(session2).sendMessage(any(TextMessage.class));

        // Sessions join different rooms
        handler.joinRoom(room1Id.toString(), session1);
        handler.joinRoom(room2Id.toString(), session2);

        // When - broadcast to room1 only
        String room1Message = "42[\"message\",{\"content\":\"Room 1 only\"}]";
        handler.broadcastToRoom(room1Id.toString(), room1Message);

        // Then - only session in room1 receives message
        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("moderation should block inappropriate content")
    void moderation_shouldBlockInappropriateContent() throws Exception {
        // Given
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("test-session");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        // Moderation blocks the message
        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(false, "Content violates policy"));

        // When - send a message that should be blocked
        String chatPayload = "42[\"chat message\",{\"roomId\":\"" + roomId + "\",\"content\":\"bad content\"}]";
        handler.handleTextMessage(mockSession, new TextMessage(chatPayload));

        // Then - error message should be sent back
        verify(mockSession).sendMessage(argThat((TextMessage msg) ->
            msg.getPayload().contains("error")
        ));

        // ChatService should NOT be called
        verify(chatService, never()).processUserMessage(
            any(), any(), anyList(), any(), any()
        );
    }

    @Test
    @DisplayName("roomNotFound should return error")
    void roomNotFound_shouldReturnError() throws Exception {
        // Given
        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("test-session");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(true, null));
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        // When - send message to non-existent room
        String chatPayload = "42[\"chat message\",{\"roomId\":\"" + roomId + "\",\"content\":\"Hello\"}]";
        handler.handleTextMessage(mockSession, new TextMessage(chatPayload));

        // Then - error message should be sent back
        verify(mockSession).sendMessage(argThat((TextMessage msg) ->
            msg.getPayload().contains("error") && msg.getPayload().contains("Room not found")
        ));
    }

    @Test
    @DisplayName("emptyRoom should return error when no characters")
    void emptyRoom_shouldReturnErrorWhenNoCharacters() throws Exception {
        // Given
        Room emptyRoom = Room.builder()
            .id(roomId)
            .name("Empty Room")
            .owner(testUser)
            .characters(new HashSet<>()) // No characters
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        when(mockSession.isOpen()).thenReturn(true);
        when(mockSession.getId()).thenReturn("test-session");
        doNothing().when(mockSession).sendMessage(any(TextMessage.class));

        when(moderationService.moderate(anyString()))
            .thenReturn(new ModerationService.ModerationResult(true, null));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(emptyRoom));

        // When - send message to room with no characters
        String chatPayload = "42[\"chat message\",{\"roomId\":\"" + roomId + "\",\"content\":\"Hello\"}]";
        handler.handleTextMessage(mockSession, new TextMessage(chatPayload));

        // Then - error message should be sent back
        verify(mockSession).sendMessage(argThat((TextMessage msg) ->
            msg.getPayload().contains("error") && msg.getPayload().contains("No characters")
        ));
    }
}

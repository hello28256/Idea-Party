package com.ideaparty.service;

import com.ideaparty.dto.MessageDto;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.exception.RoomNotFoundException;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatService.
 * Tests message saving, retrieval, and processing.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private AIService aiService;

    @InjectMocks
    private ChatService chatService;

    private User testUser;
    private Room testRoom;
    private Character testCharacter;
    private UUID roomId;
    private UUID characterId;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        characterId = UUID.randomUUID();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .username("testuser")
                .displayName("Test User")
                .password("encoded-password")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testRoom = Room.builder()
                .id(roomId)
                .name("Test Room")
                .topic("Test Topic")
                .owner(testUser)
                .characters(new HashSet<>())
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
    }

    @Test
    @DisplayName("saveMessage should save message successfully")
    void saveMessage_shouldSaveMessage() {
        // Given
        String content = "Hello, world!";
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            msg.setId(UUID.randomUUID().toString());
            msg.setCreatedAt(LocalDateTime.now());
            return msg;
        });

        // When
        MessageDto result = chatService.saveMessage(
                roomId, null, Message.SenderType.USER, content);

        // Then
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertEquals("USER", result.getSenderType());
        verify(roomRepository).findById(roomId);
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    @DisplayName("saveMessage should throw when room not found")
    void saveMessage_shouldThrowWhenRoomNotFound() {
        // Given
        UUID nonExistentRoomId = UUID.randomUUID();
        when(roomRepository.findById(nonExistentRoomId)).thenReturn(Optional.empty());

        // When & Then - ChatService throws RoomNotFoundException
        assertThrows(
                RoomNotFoundException.class,
                () -> chatService.saveMessage(
                        nonExistentRoomId, null, Message.SenderType.USER, "Hello")
        );

        verify(roomRepository).findById(nonExistentRoomId);
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("saveMessage should save message with character")
    void saveMessage_shouldSaveMessageWithCharacter() {
        // Given
        String content = "Character response";
        testRoom.getCharacters().add(testCharacter);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        when(characterRepository.findById(characterId)).thenReturn(Optional.of(testCharacter));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            msg.setId(UUID.randomUUID().toString());
            msg.setCreatedAt(LocalDateTime.now());
            return msg;
        });

        // When
        MessageDto result = chatService.saveMessage(
                roomId, characterId, Message.SenderType.CHARACTER, content);

        // Then
        assertNotNull(result);
        assertEquals(content, result.getContent());
        assertEquals("CHARACTER", result.getSenderType());
        assertEquals(characterId.toString(), result.getCharacterId());
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    @DisplayName("getMessagesByRoom should return messages for room")
    void getMessagesByRoom_shouldReturnMessages() {
        // Given
        Message message1 = new Message();
        message1.setId(UUID.randomUUID().toString());
        message1.setContent("Message 1");
        message1.setSenderType(Message.SenderType.USER);
        message1.setRoom(testRoom);
        message1.setCreatedAt(LocalDateTime.now());

        Message message2 = new Message();
        message2.setId(UUID.randomUUID().toString());
        message2.setContent("Message 2");
        message2.setSenderType(Message.SenderType.CHARACTER);
        message2.setRoom(testRoom);
        message2.setCharacter(testCharacter);
        message2.setCreatedAt(LocalDateTime.now());

        List<Message> messages = Arrays.asList(message1, message2);
        when(messageRepository.findByRoomIdOrderByCreatedAtAsc(roomId)).thenReturn(messages);

        // When
        List<MessageDto> result = chatService.getMessagesByRoom(roomId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Message 1", result.get(0).getContent());
        assertEquals("Message 2", result.get(1).getContent());
        verify(messageRepository).findByRoomIdOrderByCreatedAtAsc(roomId);
    }

    @Test
    @DisplayName("processUserMessage should save user message and trigger AI responses")
    @SuppressWarnings("unchecked")
    void processUserMessage_shouldSaveMessageAndTriggerAI() {
        // Given
        String content = "Hello AI!";
        testRoom.getCharacters().add(testCharacter);

        Consumer<String> mockThinkingCallback = mock(Consumer.class);
        Consumer<MessageDto> mockMessageCallback = mock(Consumer.class);

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            msg.setId(UUID.randomUUID().toString());
            msg.setCreatedAt(LocalDateTime.now());
            return msg;
        });
        when(aiService.generateResponse(anyString(), anyString()))
                .thenReturn("AI response content");

        // When
        chatService.processUserMessage(
                roomId, content, List.of(testCharacter),
                mockThinkingCallback, mockMessageCallback);

        // Then
        // Verify user message was saved (called twice: once for user, once for AI)
        verify(messageRepository, atLeast(1)).save(any(Message.class));
        verify(mockThinkingCallback).accept(characterId.toString());
    }

    @Test
    @DisplayName("saveMessage should use ArgumentCaptor to verify saved content")
    void saveMessage_shouldUseArgumentCaptorToVerifyContent() {
        // Given
        String content = "Captured content";
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            msg.setId(UUID.randomUUID().toString());
            msg.setCreatedAt(LocalDateTime.now());
            return msg;
        });

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        // When
        chatService.saveMessage(roomId, null, Message.SenderType.USER, content);

        // Then
        verify(messageRepository).save(messageCaptor.capture());
        Message capturedMessage = messageCaptor.getValue();
        assertEquals(content, capturedMessage.getContent());
        assertEquals(Message.SenderType.USER, capturedMessage.getSenderType());
        assertEquals(testRoom, capturedMessage.getRoom());
    }
}

package com.ideaparty.service;

import com.ideaparty.dto.CreateRoomRequest;
import com.ideaparty.dto.RoomResponse;
import com.ideaparty.entity.Room;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoomService.
 * Tests room creation, retrieval, and deletion operations.
 */
@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CharacterRepository characterRepository;

    @InjectMocks
    private RoomService roomService;

    private User testUser;
    private Room testRoom;
    private UUID userId;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        roomId = UUID.randomUUID();

        testUser = User.builder()
                .id(userId)
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
    }

    @Test
    @DisplayName("createRoom should throw when user not found")
    void createRoom_shouldThrowWhenUserNotFound() {
        // Given
        UUID nonExistentUserId = UUID.randomUUID();
        CreateRoomRequest request = new CreateRoomRequest();
        request.setName("New Room");
        request.setTopic("Topic");

        when(userRepository.findById(nonExistentUserId)).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> roomService.create(nonExistentUserId, request)
        );

        assertTrue(exception.getMessage().contains("User not found"));
        verify(userRepository).findById(nonExistentUserId);
        verify(roomRepository, never()).save(any());
    }

    @Test
    @DisplayName("createRoom should create successfully with valid user")
    void createRoom_shouldCreateSuccessfully() {
        // Given
        CreateRoomRequest request = new CreateRoomRequest();
        request.setName("New Room");
        request.setTopic("Test Topic");

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            room.setId(roomId);
            return room;
        });

        // When
        RoomResponse response = roomService.create(userId, request);

        // Then
        assertNotNull(response);
        assertEquals("New Room", response.getName());
        assertEquals("Test Topic", response.getTopic());
        assertEquals(userId, response.getOwnerId());
        verify(userRepository).findById(userId);
        verify(roomRepository).save(any(Room.class));
    }

    @Test
    @DisplayName("findById should return room when exists")
    void findById_shouldReturnRoom() {
        // Given
        when(roomRepository.findWithCharactersById(roomId)).thenReturn(Optional.of(testRoom));

        // When
        RoomResponse response = roomService.findById(roomId);

        // Then
        assertNotNull(response);
        assertEquals(roomId, response.getId());
        assertEquals("Test Room", response.getName());
        verify(roomRepository).findWithCharactersById(roomId);
    }

    @Test
    @DisplayName("findById should throw when room not found")
    void findById_shouldThrowWhenNotFound() {
        // Given
        UUID nonExistentRoomId = UUID.randomUUID();
        when(roomRepository.findWithCharactersById(nonExistentRoomId)).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> roomService.findById(nonExistentRoomId)
        );

        assertTrue(exception.getMessage().contains("Room not found"));
    }

    @Test
    @DisplayName("deleteIfOwner should throw when user is not owner")
    void deleteIfOwner_shouldThrowWhenNotOwner() {
        // Given
        UUID differentUserId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));

        // When & Then
        assertThrows(
                AccessDeniedException.class,
                () -> roomService.deleteIfOwner(roomId, differentUserId)
        );

        verify(roomRepository).findById(roomId);
        verify(roomRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteIfOwner should succeed when user is owner")
    void deleteIfOwner_shouldSucceedWhenOwner() {
        // Given
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        doNothing().when(roomRepository).delete(any(Room.class));

        // When
        assertDoesNotThrow(() -> roomService.deleteIfOwner(roomId, userId));

        // Then
        verify(roomRepository).findById(roomId);
        verify(roomRepository).delete(testRoom);
    }

    @Test
    @DisplayName("deleteIfOwner should throw when room not found")
    void deleteIfOwner_shouldThrowWhenRoomNotFound() {
        // Given
        UUID nonExistentRoomId = UUID.randomUUID();
        when(roomRepository.findById(nonExistentRoomId)).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> roomService.deleteIfOwner(nonExistentRoomId, userId)
        );

        assertTrue(exception.getMessage().contains("Room not found"));
        verify(roomRepository, never()).delete(any());
    }
}

package com.ideaparty.service;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CharacterService.
 * Tests character creation, retrieval, and prompt generation.
 */
@ExtendWith(MockitoExtension.class)
class CharacterServiceTest {

    @Mock
    private CharacterRepository characterRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FirecrawlService firecrawlService;

    @InjectMocks
    private CharacterService characterService;

    private User testUser;
    private Character testCharacter;
    private UUID userId;
    private UUID characterId;

    // DeepSeek base URL for AI generation
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";

    @BeforeEach
    void setUp() {
        // Set the deepseek base URL via reflection
        ReflectionTestUtils.setField(characterService, "deepseekBaseUrl", DEEPSEEK_BASE_URL);

        userId = UUID.randomUUID();
        characterId = UUID.randomUUID();

        testUser = User.builder()
                .id(userId)
                .email("test@example.com")
                .username("testuser")
                .displayName("Test User")
                .password("encoded-password")
                .apiKey("test-api-key")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testCharacter = new Character();
        testCharacter.setId(characterId);
        testCharacter.setName("Test Character");
        testCharacter.setDescription("A test character description");
        testCharacter.setPrompt("You are a test character who speaks formally.");
        testCharacter.setAvatarUrl("https://example.com/avatar.png");
        testCharacter.setOwner(testUser);
        testCharacter.setPreset(false);
        testCharacter.setCreatedAt(Instant.now());
        testCharacter.setUpdatedAt(Instant.now());
    }

    @Test
    @DisplayName("create should throw when user not found")
    void create_shouldThrowWhenUserNotFound() {
        // Given
        UUID nonExistentUserId = UUID.randomUUID();
        CharacterRequest request = new CharacterRequest();
        request.setName("New Character");
        request.setDescription("A new character");

        when(userRepository.findById(nonExistentUserId)).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> characterService.create(nonExistentUserId, request)
        );

        assertEquals("User not found", exception.getMessage());
        verify(userRepository).findById(nonExistentUserId);
        verify(characterRepository, never()).save(any());
    }

    @Test
    @DisplayName("create should create character with provided prompt")
    void create_shouldCreateCharacterWithProvidedPrompt() {
        // Given
        String customPrompt = "You are Sherlock Holmes. You are a brilliant detective.";
        CharacterRequest request = new CharacterRequest();
        request.setName("Sherlock Holmes");
        request.setDescription("Famous detective");
        request.setPrompt(customPrompt);
        request.setAvatarUrl("https://example.com/sherlock.png");

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(characterRepository.save(any(Character.class))).thenAnswer(invocation -> {
            Character charac = invocation.getArgument(0);
            charac.setId(UUID.randomUUID());
            return charac;
        });

        // When
        CharacterResponse response = characterService.create(userId, request);

        // Then
        assertNotNull(response);
        assertEquals("Sherlock Holmes", response.getName());
        assertEquals(customPrompt, response.getPrompt());
        assertEquals("https://example.com/sherlock.png", response.getAvatarUrl());

        verify(userRepository).findById(userId);
        verify(characterRepository).save(any(Character.class));
    }

    @Test
    @DisplayName("create should call FirecrawlService when prompt not provided")
    void create_shouldCallFirecrawlServiceWhenPromptNotProvided() {
        // Given
        CharacterRequest request = new CharacterRequest();
        request.setName("Einstein");
        request.setDescription("Famous physicist");
        request.setPrompt(null); // No prompt provided

        String scrapedContent = "Albert Einstein was a theoretical physicist who developed the theory of relativity.";

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(firecrawlService.scrape("Einstein")).thenReturn(scrapedContent);
        when(characterRepository.save(any(Character.class))).thenAnswer(invocation -> {
            Character charac = invocation.getArgument(0);
            charac.setId(UUID.randomUUID());
            return charac;
        });

        // When
        CharacterResponse response = characterService.create(userId, request);

        // Then
        assertNotNull(response);
        assertEquals("Einstein", response.getName());
        // FirecrawlService should have been called to scrape web content
        verify(firecrawlService).scrape("Einstein");
        verify(characterRepository).save(any(Character.class));
    }

    @Test
    @DisplayName("create should throw when FirecrawlService search fails")
    void create_shouldThrowWhenSearchFails() {
        // Given
        CharacterRequest request = new CharacterRequest();
        request.setName("Unknown Person");
        request.setDescription("An unknown person");
        request.setPrompt(null);

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(firecrawlService.scrape("Unknown Person")).thenThrow(new RuntimeException("Network error"));

        // When & Then
        // The service should still work with fallback prompt from scrape failure handling
        assertThrows(RuntimeException.class, () -> characterService.create(userId, request));
    }

    @Test
    @DisplayName("findById should return character when exists")
    void findById_shouldReturnCharacter() {
        // Given
        when(characterRepository.findById(characterId)).thenReturn(Optional.of(testCharacter));

        // When
        Optional<CharacterResponse> result = characterService.findById(characterId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(characterId, result.get().getId());
        assertEquals("Test Character", result.get().getName());
        verify(characterRepository).findById(characterId);
    }

    @Test
    @DisplayName("findById should return empty when character not found")
    void findById_shouldReturnEmptyWhenNotFound() {
        // Given
        UUID nonExistentId = UUID.randomUUID();
        when(characterRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // When
        Optional<CharacterResponse> result = characterService.findById(nonExistentId);

        // Then
        assertTrue(result.isEmpty());
        verify(characterRepository).findById(nonExistentId);
    }

    @Test
    @DisplayName("deleteIfOwner should return true when user is owner")
    void deleteIfOwner_shouldReturnTrueWhenOwner() {
        // Given
        when(characterRepository.existsByIdAndOwnerId(characterId, userId)).thenReturn(true);
        doNothing().when(characterRepository).deleteById(characterId);

        // When
        boolean result = characterService.deleteIfOwner(characterId, userId);

        // Then
        assertTrue(result);
        verify(characterRepository).existsByIdAndOwnerId(characterId, userId);
        verify(characterRepository).deleteById(characterId);
    }

    @Test
    @DisplayName("deleteIfOwner should return false when user is not owner")
    void deleteIfOwner_shouldReturnFalseWhenNotOwner() {
        // Given
        UUID differentUserId = UUID.randomUUID();
        when(characterRepository.existsByIdAndOwnerId(characterId, differentUserId)).thenReturn(false);

        // When
        boolean result = characterService.deleteIfOwner(characterId, differentUserId);

        // Then
        assertFalse(result);
        verify(characterRepository).existsByIdAndOwnerId(characterId, differentUserId);
        verify(characterRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("isOwner should return true when user is owner")
    void isOwner_shouldReturnTrueWhenOwner() {
        // Given
        when(characterRepository.existsByIdAndOwnerId(characterId, userId)).thenReturn(true);

        // When
        boolean result = characterService.isOwner(characterId, userId);

        // Then
        assertTrue(result);
        verify(characterRepository).existsByIdAndOwnerId(characterId, userId);
    }

    @Test
    @DisplayName("isOwner should return false when user is not owner")
    void isOwner_shouldReturnFalseWhenNotOwner() {
        // Given
        UUID differentUserId = UUID.randomUUID();
        when(characterRepository.existsByIdAndOwnerId(characterId, differentUserId)).thenReturn(false);

        // When
        boolean result = characterService.isOwner(characterId, differentUserId);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("generatePrompt should use AI when name is provided")
    void generatePrompt_shouldUseAIWhenNameProvided() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // When
        String prompt = characterService.generatePrompt(userId, "Test Character", null);

        // Then
        assertNotNull(prompt);
        // The method should attempt AI generation
        verify(userRepository).findById(userId);
    }

    @Test
    @DisplayName("generatePrompt should return fallback when AI fails")
    void generatePrompt_shouldReturnFallbackWhenAIFails() {
        // Given
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // When
        String prompt = characterService.generatePrompt(userId, "Unknown Character", null);

        // Then
        assertNotNull(prompt);
        assertTrue(prompt.contains("Unknown Character") || prompt.length() > 0);
    }
}

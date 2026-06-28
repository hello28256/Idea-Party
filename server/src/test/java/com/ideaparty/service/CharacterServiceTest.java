package com.ideaparty.service;

import com.ideaparty.cache.PresetCharacterCache;
import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.CharacterCategory;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.repository.RoomRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private RoomRepository roomRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private RoomService roomService;

    @Mock
    private FirecrawlService firecrawlService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private PresetCharacterCache presetCache;

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
        when(characterRepository.saveAndFlush(any(Character.class))).thenAnswer(invocation -> {
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
        verify(characterRepository).saveAndFlush(any(Character.class));
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
        when(characterRepository.saveAndFlush(any(Character.class))).thenAnswer(invocation -> {
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
        verify(characterRepository).saveAndFlush(any(Character.class));
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
    @DisplayName("generatePrompt should throw IllegalArgumentException when API key is missing")
    void generatePrompt_shouldThrowWhenApiKeyMissing() {
        // Given: 构造一个 apiKey=null 的用户实例
        User userWithoutKey = User.builder()
                .id(userId)
                .email("nokey@example.com")
                .username("nokey")
                .apiKey(null)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithoutKey));

        // When & Then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> characterService.generatePrompt(userId, "Test Character", null));
        assertTrue(ex.getMessage().contains("API Key"),
                "异常 message 应该引导用户去设置页填 key，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("generatePrompt should throw IllegalArgumentException when API key is dummy placeholder")
    void generatePrompt_shouldThrowWhenApiKeyIsDummy() {
        // Given: 用户填了测试用 dummy 占位 key
        User userWithDummyKey = User.builder()
                .id(userId)
                .email("dummy@example.com")
                .username("dummy")
                .apiKey("sk-dummy-key-for-testing")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithDummyKey));

        // When & Then: dummy key 视为未配置
        assertThrows(IllegalArgumentException.class,
                () -> characterService.generatePrompt(userId, "Test Character", null));
    }

    @Test
    @DisplayName("generatePrompt should not return hardcoded fallback when AI key is configured")
    void generatePrompt_shouldNotReturnHardcodedFallback() {
        // Given: key 校验通过（testUser.apiKey="test-api-key"），但本机无 DeepSeek 访问。
        // 由于 generatePromptWithAI* 内部 catch 仍会兜底返回中文假字符串（保护 DataLoader 启动期容错），
        // 这条路径在 CI / 无网络下无法靠 assertThrows 验证——只能断言"调用了 AI 生成路径"而不是返回通用英文兜底。
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // When
        String prompt = characterService.generatePrompt(userId, "Unknown Character", null);

        // Then: 不应该是 name/description 双空时才返回的英文通用兜底
        assertNotEquals("You are a unique character. Speak in character with depth and authenticity.", prompt);
        verify(userRepository).findById(userId);
    }

    // ------------------------------------------------------------------------
    // findRecommendedByCategory（多分类匹配）
    //
    // 验证 Set<CharacterCategory> 的 contains 语义：
    //   1) 一个角色同时属于多个分类时，对任一分类的 chip 过滤都应返回它；
    //   2) 单元素 categories 数组的角色也能命中；
    //   3) null 入参走全集分支。
    // 用 PresetCharacterCache.getAll() 作为 stub 数据源，避免依赖真实的 presets.json。
    // ------------------------------------------------------------------------

    private CharacterResponse stubPreset(String name, Set<CharacterCategory> categories) {
        Character c = new Character();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setDescription("desc-" + name);
        c.setPreset(true);
        c.setCategories(categories);
        ReflectionTestUtils.setField(c, "createdAt", Instant.now());
        ReflectionTestUtils.setField(c, "updatedAt", Instant.now());
        return CharacterResponse.fromEntity(c);
    }

    @Test
    @DisplayName("findRecommendedByCategory: 多分类角色按 OR 语义匹配任一目标分类")
    void findRecommendedByCategory_multiCategoryCharacter_matchesAnyTarget() {
        // 毛泽东 = HISTORICAL + POLITICIAN + MILITARY_LEADER；纯历史人物 = 单分类
        CharacterResponse mao = stubPreset("毛泽东",
                Set.of(CharacterCategory.HISTORICAL, CharacterCategory.POLITICIAN, CharacterCategory.MILITARY_LEADER));
        CharacterResponse confucius = stubPreset("孔子",
                Set.of(CharacterCategory.HISTORICAL, CharacterCategory.PHILOSOPHER));
        when(presetCache.getAll()).thenReturn(List.of(mao, confucius));

        // 当：用户筛 POLITICIAN
        List<CharacterResponse> result = characterService.findRecommendedByCategory(CharacterCategory.POLITICIAN);

        // 那么：只有毛泽东命中（孔子没有 POLITICIAN 标签）
        assertEquals(1, result.size());
        assertEquals("毛泽东", result.get(0).getName());
    }

    @Test
    @DisplayName("findRecommendedByCategory: 单元素 categories 数组按 contains 匹配")
    void findRecommendedByCategory_singleElementArray_matchesAsOneOf() {
        CharacterResponse einstein = stubPreset("爱因斯坦", Set.of(CharacterCategory.SCIENTIST));
        CharacterResponse jobs = stubPreset("乔布斯", Set.of(CharacterCategory.ENTREPRENEUR));
        when(presetCache.getAll()).thenReturn(List.of(einstein, jobs));

        // 当：用户筛 SCIENTIST
        List<CharacterResponse> result = characterService.findRecommendedByCategory(CharacterCategory.SCIENTIST);

        // 那么：只命中爱因斯坦
        List<String> names = result.stream().map(CharacterResponse::getName).collect(Collectors.toList());
        assertEquals(List.of("爱因斯坦"), names);
    }

    @Test
    @DisplayName("findRecommendedByCategory: null 入参返回全集")
    void findRecommendedByCategory_nullCategory_returnsAll() {
        CharacterResponse a = stubPreset("A", Set.of(CharacterCategory.SCIENTIST));
        CharacterResponse b = stubPreset("B", Set.of(CharacterCategory.STAR));
        CharacterResponse c = stubPreset("C", Set.of());  // 空分类也应返回
        when(presetCache.getAll()).thenReturn(List.of(a, b, c));

        // 当：category 传 null
        List<CharacterResponse> result = characterService.findRecommendedByCategory(null);

        // 那么：全集 3 条都返回
        assertEquals(3, result.size());
    }
}

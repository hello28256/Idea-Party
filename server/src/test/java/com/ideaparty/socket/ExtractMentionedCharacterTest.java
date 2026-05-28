package com.ideaparty.socket;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.service.AuthService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import com.ideaparty.repository.RoomRepository;
import com.ideaparty.service.ModeratorAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Tests for mention extraction behavior.
 * Tests the extractMentionedCharacter private method via reflection.
 */
@ExtendWith(MockitoExtension.class)
class ExtractMentionedCharacterTest {

    @Mock
    private MessageService messageService;

    @Mock
    private ModerationService moderationService;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private ModeratorAgent moderatorAgent;

    @Mock
    private AuthService authService;

    private ChatSocketHandler handler;
    private Method extractMethod;
    private List<Character> roomCharacters;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ChatSocketHandler(
            messageService, moderationService, roomRepository, moderatorAgent, authService
        );
        extractMethod = ChatSocketHandler.class.getDeclaredMethod("extractMentionedCharacter", String.class, List.class);
        extractMethod.setAccessible(true);

        // Set up room characters for testing
        User owner = User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("testuser")
            .displayName("Test User")
            .password("encoded-password")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        Character liSi = new Character();
        liSi.setId(UUID.randomUUID());
        liSi.setName("李四");
        liSi.setDescription("A test character");
        liSi.setPrompt("You are 李四.");
        liSi.setOwner(owner);
        liSi.setPreset(false);
        liSi.setCreatedAt(Instant.now());
        liSi.setUpdatedAt(Instant.now());

        Character alice = new Character();
        alice.setId(UUID.randomUUID());
        alice.setName("Alice");
        alice.setDescription("An English character");
        alice.setPrompt("You are Alice.");
        alice.setOwner(owner);
        alice.setPreset(false);
        alice.setCreatedAt(Instant.now());
        alice.setUpdatedAt(Instant.now());

        roomCharacters = List.of(liSi, alice);
    }

    private String extract(String content, List<Character> characters) throws Exception {
        return (String) extractMethod.invoke(handler, content, characters);
    }

    @Test
    @DisplayName("@name format should extract character name")
    void withAtSign_shouldExtractName() throws Exception {
        assertEquals("李四", extract("@李四 今天天气怎么样", roomCharacters));
        assertEquals("李四", extract("@李四今天很忙", roomCharacters));
        assertEquals("Alice", extract("@Alice hello", roomCharacters));
    }

    @Test
    @DisplayName("Direct character name at message start should be extracted")
    void directNameAtStart_shouldExtractName() throws Exception {
        assertEquals("李四", extract("李四 今天天气怎么样", roomCharacters));
        assertEquals("Alice", extract("Alice hello", roomCharacters));
    }

    @Test
    @DisplayName("@name takes priority over direct name")
    void atSignTakesPriority() throws Exception {
        assertEquals("李四", extract("@李四 今天天气", roomCharacters));
    }

    @Test
    @DisplayName("Message without matching character name should return null")
    void noMatchingCharacter_shouldReturnNull() throws Exception {
        // No name in message
        assertNull(extract("今天天气怎么样", roomCharacters));
        // Name doesn't match any character in room
        assertNull(extract("王五 hello", roomCharacters));
        assertNull(extract("@王五 hello", roomCharacters));
        assertNull(extract("", roomCharacters));
        assertNull(extract("   ", roomCharacters));
        assertNull(extract(null, roomCharacters));
    }

    @Test
    @DisplayName("Character name in middle of message should not trigger single character mode")
    void nameInMiddle_shouldReturnNull() throws Exception {
        // Direct name in middle - should not extract
        assertNull(extract("你好李四，今天天气", roomCharacters));
        // @ with name not in room
        assertNull(extract("@王五 hello", roomCharacters));
    }

    @Test
    @DisplayName("Case-insensitive matching for English names")
    void caseInsensitive_shouldMatch() throws Exception {
        assertEquals("Alice", extract("alice hello", roomCharacters));
        assertEquals("Alice", extract("@ALICE hello", roomCharacters));
    }

    @Test
    @DisplayName("Name followed by question words without space should extract name")
    void nameWithQuestionWords_noSpace_shouldExtract() throws Exception {
        // "name + question word" patterns - no space between name and question
        assertEquals("李四", extract("李四你怎么看", roomCharacters));
        assertEquals("李四", extract("李四你觉得呢", roomCharacters));
        assertEquals("李四", extract("李四，怎么了", roomCharacters));
        assertEquals("Alice", extract("Alice你觉得呢", roomCharacters));
    }

    @Test
    @DisplayName("Name followed by comma and text should extract name")
    void nameWithComma_shouldExtract() throws Exception {
        assertEquals("李四", extract("李四，你对这个问题怎么看", roomCharacters));
    }

    // ====== Tests for isOpenEndedQuestion ======

    private boolean isOpenEnded(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("isOpenEndedQuestion", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(handler, content);
    }

    @Test
    @DisplayName("Open-ended questions should be detected")
    void openEndedQuestion_shouldDetect() throws Exception {
        assertTrue(isOpenEnded("大家怎么看这个问题"));
        assertTrue(isOpenEnded("你们觉得呢"));
        assertTrue(isOpenEnded("每个人都说说自己的想法"));
        assertTrue(isOpenEnded("大家都有些什么看法"));
        assertTrue(isOpenEnded("讨论一下这个问题"));
        assertTrue(isOpenEnded("你们都有些什么意见"));
    }

    @Test
    @DisplayName("Direct questions should NOT be detected as open-ended")
    void directQuestion_shouldNotDetect() throws Exception {
        assertFalse(isOpenEnded("马云你怎么看"));
        assertFalse(isOpenEnded("你觉得怎么样"));
        assertFalse(isOpenEnded("这个问题怎么解决"));
        assertFalse(isOpenEnded("李四说说你的看法")); // singular, not "每个人都"
        assertFalse(isOpenEnded("你怎么认为"));
    }

    // ====== Tests for extractMultipleMentions ======

    private List<String> extractMultiple(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("extractMultipleMentions", String.class, List.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(handler, content, roomCharacters);
    }

    @Test
    @DisplayName("Multi-target mentions should detect multiple characters")
    void multiTargetMention_shouldDetectMultiple() throws Exception {
        // This test uses the existing roomCharacters (李四, Alice)
        // When we have 李四 and Alice in the room, mentions should be detected
        String content1 = "李四和Alice观点有什么不同";
        List<String> result1 = extractMultiple(content1);
        assertTrue(result1.contains("李四"), "Should contain 李四, got: " + result1);
        assertTrue(result1.contains("Alice"), "Should contain Alice, got: " + result1);
    }

    @Test
    @DisplayName("Multi-target with Chinese comma separator")
    void multiTargetWithCommaSeparator() throws Exception {
        String content = "李四、Alice都觉得对";
        List<String> result = extractMultiple(content);
        assertTrue(result.contains("李四"), "Should contain 李四, got: " + result);
        assertTrue(result.contains("Alice"), "Should contain Alice, got: " + result);
    }
}

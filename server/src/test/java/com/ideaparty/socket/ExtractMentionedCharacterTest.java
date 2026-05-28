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
}

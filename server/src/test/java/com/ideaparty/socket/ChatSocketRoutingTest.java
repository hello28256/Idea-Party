package com.ideaparty.socket;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.service.AuthService;
import com.ideaparty.service.MessageService;
import com.ideaparty.service.ModerationService;
import com.ideaparty.service.ModeratorAgent;
import com.ideaparty.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Comprehensive tests for ChatSocketHandler routing logic.
 * Tests the private routing methods via reflection.
 */
@ExtendWith(MockitoExtension.class)
class ChatSocketRoutingTest {

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
    private List<Character> roomCharacters;

    // Test characters
    private Character 马云;
    private Character 马化腾;
    private Character 张一鸣;
    private Character 李开复;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ChatSocketHandler(
            messageService, moderationService, roomRepository, moderatorAgent, authService
        );

        // Set up test characters
        User owner = User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .username("testuser")
            .displayName("Test User")
            .password("encoded-password")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        马云 = createCharacter("马云", owner);
        马化腾 = createCharacter("马化腾", owner);
        张一鸣 = createCharacter("张一鸣", owner);
        李开复 = createCharacter("李开复", owner);

        roomCharacters = List.of(马云, 马化腾, 张一鸣, 李开复);
    }

    private Character createCharacter(String name, User owner) {
        Character c = new Character();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setDescription("A test character: " + name);
        c.setPrompt("You are " + name + ".");
        c.setOwner(owner);
        c.setPreset(false);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }

    // ====== Helper Methods ======

    private String extractMention(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("extractMentionedCharacter", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(handler, content, roomCharacters);
    }

    private List<String> extractMultiple(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("extractMultipleMentions", String.class, List.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(handler, content, roomCharacters);
    }

    private boolean isOpenEnded(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("isOpenEndedQuestion", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(handler, content);
    }

    private boolean isContextual(String content) throws Exception {
        Method method = ChatSocketHandler.class.getDeclaredMethod("isShortOrContextualMessage", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(handler, content);
    }

    // ====== Section 1: 显式 @mention 路由 ======

    @Nested
    @DisplayName("1. 显式 @mention 路由")
    class AtMentionRouting {

        @Test
        @DisplayName("@名称 应该提取角色名")
        void atSign_shouldExtractName() throws Exception {
            assertEquals("马云", extractMention("@马云 今天天气"));
            assertEquals("马化腾", extractMention("@马化腾 你怎么看"));
            assertEquals("张一鸣", extractMention("@张一鸣 说点什么"));
        }

        @Test
        @DisplayName("@名称后无空格 也应该提取")
        void atSignNoSpace_shouldExtract() throws Exception {
            assertEquals("马云", extractMention("@马云今天怎么样"));
        }

        @Test
        @DisplayName("@中文名 精确匹配")
        void atSignChineseExactMatch() throws Exception {
            assertEquals("马云", extractMention("@马云"));
            assertEquals("马化腾", extractMention("@马化腾"));
        }
    }

    // ====== Section 2: 直接名称提及路由 ======

    @Nested
    @DisplayName("2. 直接名称提及路由")
    class DirectNameRouting {

        @Test
        @DisplayName("名称 + 空格 + 内容 应该提取")
        void nameWithSpace_shouldExtract() throws Exception {
            assertEquals("马云", extractMention("马云 今天天气怎么样"));
            assertEquals("马化腾", extractMention("马化腾 你怎么看创业"));
        }

        @Test
        @DisplayName("名称 + 逗号 + 内容 应该提取")
        void nameWithComma_shouldExtract() throws Exception {
            assertEquals("马云", extractMention("马云，你怎么看这个问题"));
            assertEquals("李开复", extractMention("李开复，谈谈你的看法"));
        }

        @Test
        @DisplayName("名称 + 疑问词（无空格）应该提取")
        void nameWithQuestionWordNoSpace_shouldExtract() throws Exception {
            assertEquals("马云", extractMention("马云你怎么看"));
            assertEquals("马化腾", extractMention("马化腾你觉得呢"));
            assertEquals("张一鸣", extractMention("张一鸣怎么想"));
        }

        @Test
        @DisplayName("名称在句中不应该提取")
        void nameInMiddle_shouldNotExtract() throws Exception {
            assertNull(extractMention("我觉得马云说得对"));
            assertNull(extractMention("除了马云还有谁"));
        }

        @Test
        @DisplayName("不匹配的名称不应该提取")
        void nonMatchingName_shouldNotExtract() throws Exception {
            assertNull(extractMention("王五怎么看"));
            assertNull(extractMention("张三说了什么"));
        }
    }

    // ====== Section 3: 开放性问题检测 ======

    @Nested
    @DisplayName("3. 开放性问题检测")
    class OpenEndedQuestionTests {

        @Test
        @DisplayName("'大家' 相关问题是开放性问题")
        void daJiaPatterns_areOpenEnded() throws Exception {
            assertTrue(isOpenEnded("大家怎么看这个问题"));
            assertTrue(isOpenEnded("大家都有些什么看法"));
            assertTrue(isOpenEnded("大家觉得如何"));
            assertTrue(isOpenEnded("大家都来发表一下意见"));
        }

        @Test
        @DisplayName("'你们' 相关问题是开放性问题")
        void niMenPatterns_areOpenEnded() throws Exception {
            assertTrue(isOpenEnded("你们觉得呢"));
            assertTrue(isOpenEnded("你们都有些什么意见"));
            assertTrue(isOpenEnded("你们都来说说"));
        }

        @Test
        @DisplayName("'讨论' 相关是开放性问题")
        void discussPatterns_areOpenEnded() throws Exception {
            assertTrue(isOpenEnded("讨论一下这个问题"));
            assertTrue(isOpenEnded("大家一起讨论"));
        }

        @Test
        @DisplayName("'每个人' 相关是开放性问题")
        void everyonePatterns_areOpenEnded() throws Exception {
            assertTrue(isOpenEnded("每个人都说说自己的想法"));
            assertTrue(isOpenEnded("每个人都要发言"));
        }

        @Test
        @DisplayName("直接问句不是开放性问题")
        void directQuestions_areNotOpenEnded() throws Exception {
            assertFalse(isOpenEnded("马云你怎么看"));
            assertFalse(isOpenEnded("你觉得怎么样"));
            assertFalse(isOpenEnded("这个问题怎么解决"));
            assertFalse(isOpenEnded("李开复说说你的看法"));
        }

        @Test
        @DisplayName("'你觉得' 开头的短句不是开放性问题")
        void niJueDe_isNotOpenEnded() throws Exception {
            assertFalse(isOpenEnded("你觉得呢"));
            assertFalse(isOpenEnded("你觉得如何"));
            assertFalse(isOpenEnded("你觉得对不对"));
        }
    }

    // ====== Section 4: 多目标检测 ======

    @Nested
    @DisplayName("4. 多目标检测")
    class MultiTargetTests {

        @Test
        @DisplayName("'X和Y' 模式应检测到两个角色")
        void hePattern_shouldDetectBoth() throws Exception {
            List<String> result = extractMultiple("马云和马化腾观点有什么不同");
            assertTrue(result.contains("马云"), "Should contain 马云, got: " + result);
            assertTrue(result.contains("马化腾"), "Should contain 马化腾, got: " + result);
        }

        @Test
        @DisplayName("'X、Y' 模式应检测到两个角色")
        void commaPattern_shouldDetectBoth() throws Exception {
            List<String> result = extractMultiple("马云、马化腾和张一鸣都怎么看");
            assertTrue(result.contains("马云"), "Should contain 马云, got: " + result);
            assertTrue(result.contains("马化腾"), "Should contain 马化腾, got: " + result);
            assertTrue(result.contains("张一鸣"), "Should contain 张一鸣, got: " + result);
        }

        @Test
        @DisplayName("只有单个角色时不应触发多目标")
        void singleCharacter_shouldNotTrigger() throws Exception {
            List<String> result = extractMultiple("马云怎么看创业");
            assertTrue(result.isEmpty(), "Single mention should not trigger multi-target, got: " + result);
        }

        @Test
        @DisplayName("三个角色都提到时")
        void threeCharacters_shouldDetectAll() throws Exception {
            List<String> result = extractMultiple("马云、马化腾、李开复都同意");
            assertTrue(result.contains("马云"), "Should contain 马云");
            assertTrue(result.contains("马化腾"), "Should contain 马化腾");
            assertTrue(result.contains("李开复"), "Should contain 李开复");
        }
    }

    // ====== Section 5: 短消息/上下文消息检测 ======

    @Nested
    @DisplayName("5. 短消息/上下文消息检测")
    class ContextualMessageTests {

        @Test
        @DisplayName("短文本应被识别为上下文消息")
        void shortMessages_areContextual() throws Exception {
            assertTrue(isContextual("好"));
            assertTrue(isContextual("好的"));
            assertTrue(isContextual("有道理"));
            assertTrue(isContextual("继续"));
            assertTrue(isContextual("为什么"));
            assertTrue(isContextual("嗯"));
        }

        @Test
        @DisplayName("含常见上下文词应被识别")
        void commonContextualWords_areContextual() throws Exception {
            assertTrue(isContextual("继续说"));
            assertTrue(isContextual("展开说"));
            assertTrue(isContextual("有意思"));
            assertTrue(isContextual("有道理"));
            assertTrue(isContextual("我同意"));
            assertTrue(isContextual("你说的对"));
        }

        @Test
        @DisplayName("长文本不应被识别为上下文消息")
        void longMessages_areNotContextual() throws Exception {
            assertFalse(isContextual("我觉得这个问题需要从多个角度来分析"));
            assertFalse(isContextual("马云的观点很有意思，但我觉得还可以补充一些内容"));
            assertFalse(isContextual("请详细介绍一下你关于人工智能的看法"));
        }

        @Test
        @DisplayName("问句不应被识别为纯上下文消息")
        void questions_areNotPureContextual() throws Exception {
            assertFalse(isContextual("为什么你会这么认为？"));
            assertFalse(isContextual("具体应该怎么做？"));
        }
    }

    // ====== Section 6: 边界情况 ======

    @Nested
    @DisplayName("6. 边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("空消息应返回 null")
        void emptyMessage_shouldReturnNull() throws Exception {
            assertNull(extractMention(""));
            assertNull(extractMention("   "));
            assertNull(extractMention(null));
        }

        @Test
        @DisplayName("纯标点符号不应提取")
        void purePunctuation_shouldReturnNull() throws Exception {
            assertNull(extractMention("？？？？"));
            assertNull(extractMention("..."));
            assertNull(extractMention("！！！"));
        }

        @Test
        @DisplayName("英文名大小写应该兼容")
        void englishNameCaseInsensitive() throws Exception {
            // Create room with English names
            User owner = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .username("testuser")
                .displayName("Test User")
                .password("encoded-password")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

            Character alice = createCharacter("Alice", owner);
            Character bob = createCharacter("Bob", owner);
            List<Character> englishRoom = List.of(alice, bob);

            Method method = ChatSocketHandler.class.getDeclaredMethod("extractMentionedCharacter", String.class, List.class);
            method.setAccessible(true);

            assertEquals("Alice", (String) method.invoke(handler, "alice hello", englishRoom));
            assertEquals("Alice", (String) method.invoke(handler, "@ALICE hello", englishRoom));
            assertEquals("Bob", (String) method.invoke(handler, "Bob what do you think", englishRoom));
        }

        @Test
        @DisplayName("混合中英文应该工作正常")
        void mixedChineseEnglish() throws Exception {
            User owner = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .username("testuser")
                .displayName("Test User")
                .password("encoded-password")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

            Character 小明 = createCharacter("小明", owner);
            Character tom = createCharacter("Tom", owner);
            List<Character> mixedRoom = List.of(小明, tom);

            Method method = ChatSocketHandler.class.getDeclaredMethod("extractMentionedCharacter", String.class, List.class);
            method.setAccessible(true);

            assertEquals("小明", (String) method.invoke(handler, "小明 hello", mixedRoom));
            assertEquals("Tom", (String) method.invoke(handler, "@Tom hi", mixedRoom));
        }
    }

    // ====== Section 7: 综合路由场景 ======

    @Nested
    @DisplayName("7. 综合路由场景（模拟 triggerAIViaModerator 逻辑）")
    class ComprehensiveRoutingTests {

        @Test
        @DisplayName("场景1: '@马云 中午吃什么' → 只路由马云")
        void scenario1_atMentionMaYun() throws Exception {
            String result = extractMention("@马云 中午吃什么");
            assertEquals("马云", result);
            assertFalse(isOpenEnded("@马云 中午吃什么"));
        }

        @Test
        @DisplayName("场景2: '吃点辣的吧' → 短消息，继续线程")
        void scenario2_shortMessage_continueThread() throws Exception {
            assertTrue(isContextual("吃点辣的吧"));
            assertFalse(isOpenEnded("吃点辣的吧"));
            assertNull(extractMention("吃点辣的吧"));
        }

        @Test
        @DisplayName("场景3: '马云你怎么看' → 无@但名称+疑问词")
        void scenario3_nameWithQuestion_noAt() throws Exception {
            assertEquals("马云", extractMention("马云你怎么看"));
        }

        @Test
        @DisplayName("场景4: '大家怎么看创业' → 开放性问题")
        void scenario4_openEndedQuestion() throws Exception {
            assertTrue(isOpenEnded("大家怎么看创业"));
            assertNull(extractMention("大家怎么看创业"));
        }

        @Test
        @DisplayName("场景5: '马云和马化腾观点有什么不同' → 多目标")
        void scenario5_multiTarget() throws Exception {
            List<String> result = extractMultiple("马云和马化腾观点有什么不同");
            assertTrue(result.size() >= 2, "Should detect at least 2 characters");
            assertTrue(result.contains("马云"));
            assertTrue(result.contains("马化腾"));
        }

        @Test
        @DisplayName("场景6: '马云，你怎么看' → 名称+逗号")
        void scenario6_nameWithComma() throws Exception {
            assertEquals("马云", extractMention("马云，你怎么看"));
        }

        @Test
        @DisplayName("场景7: '你觉得呢' → 非开放性，是上下文")
        void scenario7_youThinkWhat() throws Exception {
            assertTrue(isContextual("你觉得呢"));
            assertFalse(isOpenEnded("你觉得呢"));
        }

        @Test
        @DisplayName("场景8: '马云，你比较喜欢哪个' → 名称+逗号+内容")
        void scenario8_nameWithCommaContent() throws Exception {
            assertEquals("马云", extractMention("马云，你比较喜欢哪个"));
        }
    }
}

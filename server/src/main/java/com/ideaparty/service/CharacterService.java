package com.ideaparty.service;

import com.ideaparty.dto.CharacterRequest;
import com.ideaparty.dto.CharacterResponse;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.User;
import com.ideaparty.repository.CharacterRepository;
import com.ideaparty.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final UserRepository userRepository;
    private final FirecrawlService firecrawlService;
    private final String deepseekBaseUrl;

    public CharacterService(
            CharacterRepository characterRepository,
            UserRepository userRepository,
            FirecrawlService firecrawlService,
            @Value("${langchain4j.open-ai.base-url}") String deepseekBaseUrl) {
        this.characterRepository = characterRepository;
        this.userRepository = userRepository;
        this.firecrawlService = firecrawlService;
        this.deepseekBaseUrl = deepseekBaseUrl;
    }

    /**
     * Detect if the given text is primarily Chinese.
     * @param text the text to check
     * @return true if more than 30% of characters are Chinese
     */
    private boolean isChineseContent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long chineseChars = text.chars()
                .filter(c -> c >= 0x4e00 && c <= 0x9fa5)
                .count();
        return (double) chineseChars / text.length() > 0.3;
    }

    /**
     * Load character prompt generator template from external file.
     * @return the system prompt template
     */
    private String loadPromptTemplate() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("prompts/character-prompt-generator.txt")) {
            if (is == null) {
                log.error("[DEBUG] Prompt template file not found: prompts/character-prompt-generator.txt");
                throw new RuntimeException("Prompt template file not found");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[DEBUG] Failed to load prompt template: {}", e.getMessage());
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }

    public CharacterResponse create(UUID userId, CharacterRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Generate prompt if not provided
        String prompt = request.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.info("[DEBUG] No prompt provided, generating from web for: {}", request.getName());
            prompt = generatePromptFromWeb(request.getName(), owner.getApiKey());
        }

        Character character = new Character();
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setAvatarUrl(request.getAvatarUrl());
        character.setPrompt(prompt);
        character.setOwner(owner);
        character.setPreset(false);

        Character saved = characterRepository.save(character);
        log.info("[DEBUG] Character created with id: {}, prompt length: {}", saved.getId(), prompt.length());
        return CharacterResponse.fromEntity(saved);
    }

    /**
     * Generate a character prompt based on name and/or description.
     * @param userId the user requesting the generation (to get their API key)
     * @param name character name (optional, used for web search)
     * @param description user-provided description (optional, used directly for AI generation)
     * @return generated prompt text
     */
    public String generatePrompt(UUID userId, String name, String description) {
        User owner;
        try {
            owner = userRepository.findById(userId).orElse(null);
        } catch (Exception e) {
            owner = null;
        }

        String userApiKey = (owner != null) ? owner.getApiKey() : null;

        try {
            if (name != null && !name.isBlank()) {
                // Use LLM directly to generate prompt based on character name
                String result = generatePromptWithAIFromName(name, userApiKey);
                log.info("[DEBUG] generatePrompt success, length: {}", result.length());
                return result;
            }
            if (description != null && !description.isBlank()) {
                return generatePromptWithAIFromDescription(description, userApiKey);
            }
        } catch (Exception e) {
            log.error("[DEBUG] generatePrompt failed: {}", e.getMessage());
        }

        return "You are a unique character. Speak in character with depth and authenticity.";
    }

    /**
     * Generate prompt using AI directly from a character name.
     * Uses the LLM's knowledge about the character without web scraping.
     */
    private String generatePromptWithAIFromName(String characterName, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("sk-dummy-key-for-testing")) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        String systemPrompt = loadPromptTemplate();
        String userMessage;
        if (isChineseContent(characterName)) {
            userMessage = String.format("请为以下角色创建一个角色提示词：%s\n\n立即生成角色提示词：", characterName);
        } else {
            userMessage = String.format(
                "Create a character prompt for: %s\n\nGenerate the character prompt now:",
                characterName
            );
        }

        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt from name: {}", characterName);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                deepseekBaseUrl + "/chat/completions",
                HttpMethod.POST,
                request,
                Map.class
            );

            if (response.getBody() != null && response.getBody().containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = (String) message.get("content");
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("[DEBUG] AI prompt generation from name failed: {}", e.getMessage());
        }

        // Fallback if AI fails
        if (isChineseContent(characterName)) {
            return String.format(
                "你是%s。以深度和真实性表达自己的观点和性格，展现独特的个人魅力。",
                characterName
            );
        } else {
            return String.format(
                "You are %s. Speak with depth and authenticity, expressing your own perspective and character in every response.",
                characterName
            );
        }
    }

    /**
     * Generate prompt using AI directly from a description.
     */
    private String generatePromptWithAIFromDescription(String description, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (apiKey != null && !apiKey.isBlank() && !apiKey.equals("sk-dummy-key-for-testing")) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        String systemPrompt;
        String userMessage;

        if (isChineseContent(description)) {
            systemPrompt = """
                你是一个角色提示词生成器，为 AI 聊天平台根据用户描述创建极具特色、令人难忘的角色。

                # 核心目标
                用户一聊就能记住这个角色，愿意持续对话。不是写人物简介，而是创造"真实存在的人"。

                # 必须赋予角色的要素
                1. 标志性语言习惯：反问、"你懂我意思吧？"、阴阳怪气、短句、长篇论述、打断人、爱用比喻、经常"啧"、先否定再认可
                2. 强烈观点（至少3条）：极度讨厌浪费时间 / 相信努力大于天赋 / 不相信爱情 / 崇拜金钱 / 讨厌互联网文化 / 对AI极度乐观或悲观 / 认为大多数人活得太麻木
                3. 独特世界观：把感情问题理解成"资源错配" / 天然怀疑所有人 / 把人生理解成"不断修bug"
                4. 稳定情绪基调：暴躁 / 疲惫 / 亢奋 / 冷幽默 / 疑心重 / 高傲 / 神经质 / 厌世 / 理想主义

                # 禁止使用的描述（废话）
                ❌ "聪明且善良" / "温柔体贴" / "睿智冷静" / "喜欢帮助别人" / "拥有丰富知识" / "逻辑清晰" / "善于分析"

                # 正确 vs 错误示例
                错误："他很聪明"
                正确："他能三分钟看穿别人真正想问什么，但从不直接说破"

                错误："她很温柔"
                正确："她骂人很凶，但每天凌晨都会提醒朋友记得吃药"

                # 输出结构
                1. 角色身份：职业/经历、当前状态、核心信念、最大执念、最大弱点
                2. 说话风格：语气、节奏、高频词、口头禅、是否喜欢提问/嘲讽/说教/打断、是否情绪化
                3. 世界观与价值观：至少3条强烈观点
                4. 行为规则：如何回应用户、什么情况会生气/兴奋、如何表达关心、如何回避脆弱话题
                5. 对话示例：6~10句像真实聊天记录的示例，不要像小说台词

                # 风格要求
                - 强聊天感、强互动感
                - 避免文学化、避免AI味、避免官方感
                - 字数：150~250字
                - 每一个字都必须基于用户描述的内容

                如果用户描述不够详细，补充合理的细节，但必须符合描述的整体方向。
                """;
            userMessage = String.format("请根据以下描述创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：", description);
        } else {
            systemPrompt = """
                You are a character prompt generator for an AI chat platform. Create DISTINCTIVE, MEMORABLE characters based on user descriptions.

                # Core Goal
                Users should want to keep chatting with this character after the first message. Not writing a biography—creating a "real person."

                # Must-Have Elements
                1. Signature Language Habits: rhetorical questions, "you know what I mean?", sarcasm, short bursts, long rants, interrupting, metaphors, "tsk" sounds,否定再认可
                2. Strong Opinions (at least 3): hates wasting time / believes effort > talent / doesn't believe in love / worships money / hates internet culture / extremely optimistic/pessimistic about AI / thinks most people live numb lives
                3. Unique Worldview: sees relationship problems as "resource misallocation" / naturally suspicious of everyone / sees life as "constantly fixing bugs"
                4. Stable Emotional Baseline: angry / exhausted / manic / dry humor / paranoid / arrogant / neurotic / world-weary / idealistic

                # Forbidden Descriptions (worthless)
                ❌ "wise and kind" / "gentle and caring" / "wise and calm" / "helpful" / "knowledgeable" / "logical" / "analytical"

                # Right vs Wrong Examples
                Wrong: "He's smart"
                Right: "He can figure out what people actually want to ask in 3 minutes, but never says it directly"

                Wrong: "She's gentle"
                Right: "She curses people out viciously, but every night at 2am she reminds her friends to take their meds"

                # Output Structure
                1. Identity: profession/background, current state, core belief, biggest obsession, biggest weakness
                2. Speaking Style: tone, rhythm, frequent words, catchphrases, tendency to question/ridicule/preach/interrupt, emotionality
                3. Worldview & Values: at least 3 strong opinions
                4. Behavior Rules: how to respond, what triggers anger/excitement, how to show care, how to avoid vulnerability
                5. Dialogue Examples: 6~10 realistic chat-style lines, NOT novel dialogue

                # Style Requirements
                - Strong chat feel, strong interactivity
                - Avoid literary language, AI-speak, official tone, assistant-like behavior
                - Length: 150-250 words
                - Every word must be grounded in the user's description

                If the description is sparse, fill in reasonable details that match the overall direction.
                """;
            userMessage = String.format("Create a character prompt based on this description:\n\n%s\n\nGenerate the character prompt now:", description);
        }

        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt from description");

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    deepseekBaseUrl + "/chat/completions",
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            if (response.getBody() != null && response.getBody().containsKey("choices")) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) choice.get("message");
                    String content = (String) message.get("content");
                    return content.trim();
                }
            }
        } catch (Exception e) {
            log.error("[DEBUG] AI prompt generation from description failed: {}", e.getMessage());
        }

        // Fallback if AI fails
        if (isChineseContent(description)) {
            return String.format(
                "你是一个独特的角色。%s 以深度和真实性表达自己的观点和性格。",
                description
            );
        } else {
            return String.format(
                "You are a unique character. %s Speak with depth and authenticity, expressing your own perspective and character in every response.",
                description
            );
        }
    }

    private String generatePromptFromWeb(String characterName, String userApiKey) {
        // Step 1: Scrape web content about the character
        String scrapedContent = firecrawlService.scrape(characterName);
        log.info("[DEBUG] Scraped content length: {}", scrapedContent.length());

        // Step 2: Try AI generation with raw content and external template
        if (userApiKey != null && !userApiKey.isBlank() && !userApiKey.equals("sk-dummy-key-for-testing")) {
            try {
                String aiGeneratedPrompt = generatePromptWithAIFromWebContent(characterName, scrapedContent, userApiKey);
                if (aiGeneratedPrompt != null && !aiGeneratedPrompt.isBlank()) {
                    log.info("[DEBUG] AI generated prompt length: {}", aiGeneratedPrompt.length());
                    return aiGeneratedPrompt;
                }
            } catch (Exception e) {
                log.error("[DEBUG] AI prompt generation failed: {}", e.getMessage());
            }
        }

        // Step 3: Fallback - use simple prompt with character name
        return "You are " + characterName + ". " + scrapedContent.substring(0, Math.min(scrapedContent.length(), 1000));
    }

    /**
     * Generate prompt using AI from web scraped content with external template.
     */
    private String generatePromptWithAIFromWebContent(String characterName, String scrapedContent, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        String systemPrompt = loadPromptTemplate();
        String userMessage;
        if (isChineseContent(characterName) || isChineseContent(scrapedContent)) {
            userMessage = String.format(
                "请根据以下信息为「%s」创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：",
                characterName, scrapedContent
            );
        } else {
            userMessage = String.format(
                "Create a character prompt for \"%s\" based on the following information:\n\n%s\n\nGenerate the character prompt now:",
                characterName, scrapedContent
            );
        }

        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt for: {}", characterName);

        ResponseEntity<Map> response = restTemplate.exchange(
            deepseekBaseUrl + "/chat/completions",
            HttpMethod.POST,
            request,
            Map.class
        );

        if (response.getBody() != null && response.getBody().containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                String content = (String) message.get("content");
                return content.trim();
            }
        }

        return null;
    }

    private String cleanMarkdown(String markdown) {
        if (markdown == null) return "";

        String content = markdown;

        // Skip TOC section - find a better landmark
        int firstHeading = content.indexOf("## ");
        int firstParagraph = content.indexOf("\n\n");

        int skipTo = content.length();
        if (firstHeading > 0 && firstHeading < 2000) {
            skipTo = firstHeading;
        } else if (firstParagraph > 100 && firstParagraph < 2000) {
            skipTo = firstParagraph;
        }
        if (skipTo < 2000 && skipTo > 50) {
            content = content.substring(skipTo);
        }

        // Remove all markdown artifacts
        content = content
            .replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
            .replaceAll("\\[[\\w\\s\\u4e00-\\u9fa5]*\\d+[^]]*\\]", "")
            .replaceAll("\\[edit\\]", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("https?://\\S+", "")
            .replaceAll("^#{1,6}\\s*", "")
            .replaceAll("_{2,}", "")
            .replaceAll("\\*{2,}", "")
            .replaceAll("\\\\", "")
            // Clean up Wikipedia link artifacts
            .replaceAll("\\(\\)\\[^\\[]*\\]", "")
            .replaceAll("\\[\\]", "")
            .replaceAll("\\(\\(([^)]*)\\)", "$1")
            .replaceAll("\\[([^\\]]+)\\]\\[([^\\]]*)\\]", "$1")
            // Remove remaining citation markers like [1], [edit], etc.
            .replaceAll("\\[[a-zA-Z0-9]+\\]", "")
            // Clean up empty parentheses and brackets
            .replaceAll("\\(\\s*\\)", "")
            .replaceAll("\\[\\s*\\]", "")
            // Clean up Wikipedia link remnants - replace (word) with word when it's a broken link
            .replaceAll("\\(([A-Za-z\\u4e00-\\u9fa5]+)\\)", "$1")
            // Clean up orphaned parentheses - remove any ( that doesn't have a proper closing )
            // This handles patterns like "(word1(word2" where links were merged
            .replaceAll("\\(([^)]+)\\(([^)]+)\\)", "$1 $2")
            // Clean up leftover parentheses at start of words
            .replaceAll("([A-Za-z\\u4e00-\\u9fa5])\\(([^)]+)\\)", "$1$2")
            // Remove unmatched opening parentheses
            .replaceAll("\\(([A-Za-z\\u4e00-\\u9fa5])", "$1")
            // Remove unmatched closing parentheses
            .replaceAll("([A-Za-z\\u4e00-\\u9fa5])\\)", "$1")
            // Clean up []: patterns (Wikipedia citation style)
            .replaceAll("\\[\\s*\\]\\s*:", "")
            .replaceAll("\\[\\s*\\]\\s*\\n", "\n")
            // Remove section headers (## Title) more aggressively
            .replaceAll("##+\\s*[^\\n]+", "")
            // Clean up multiple spaces and newlines
            .replaceAll("\\s{2,}", " ")
            // Remove lines that are mostly brackets or parentheses
            .replaceAll("^[\\s\\(\\)\\[\\]]+$", "")
            // Remove Wikipedia-specific warning text
            .replaceAll("请勿直接提交机械翻译[，,]?也不要翻译不可靠、低品质内容[。]?", "")
            .replaceAll("Wikipedia[\\s]*does\\s+not\\s+have\\s+an\\s+article.*?(?=[。]|$)", "")
            // Remove "条目：xxx" patterns
            .replaceAll("条目[：:]\\s*[^。]+", "")
            // Clean up Chinese parentheses and quotes
            .replaceAll("（", "(")
            .replaceAll("）", ")")
            .replaceAll("“", "\"")
            .replaceAll("”", "\"")
            .replaceAll("『", "'")
            .replaceAll("』", "'");

        // Detect if content is primarily Chinese
        long chineseCharCount = content.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count();
        double chineseRatio = (double) chineseCharCount / Math.max(content.length(), 1);

        // For Chinese content (or mixed), split by both English and Chinese sentence delimiters
        String[] sentences;
        if (chineseRatio > 0.2) {
            // Chinese or mixed content - split by Chinese 。 or English .!? followed by newline or space
            sentences = content.split("(?<=[。！？.!?])\\s*(?=\\n|[A-Z\\u4e00-\\u9fa5]|$)");
        } else {
            // English content - original regex
            sentences = content.split("(?<=[.!?])\\s+(?=[A-Z])");
        }

        StringBuilder cleanText = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.length() < 10) continue;
            if (sentence.contains("|") || sentence.contains("---")) continue;
            if (sentence.matches("^[A-Z][a-z]+ \\| .*")) continue;
            if (sentence.startsWith("Born ") || sentence.startsWith(" c. ") || sentence.startsWith("Died ")) continue;
            if (sentence.contains("This is a ") || sentence.contains("Wikipedia") || sentence.contains("Jump to")) continue;
            if (sentence.contains("article") && sentence.length() < 100) continue;
            // Filter out sentences that are mostly brackets
            if (sentence.replaceAll("[^\\[\\]]", "").length() > sentence.length() * 0.3) continue;
            // Filter out sentences that look like Wikipedia navigation
            if (sentence.matches("^\\(?[A-Z][a-z]+(\\[.*\\])?(:|\\|).*")) continue;
            // Filter out very short sentences that are just links
            if (sentence.length() < 20 && sentence.matches(".*\\[.*\\].*")) continue;

            // For Chinese content, be more lenient on the letter ratio check
            if (chineseRatio > 0.2) {
                // Chinese content - keep if it has meaningful Chinese chars
                long sentenceChinese = sentence.chars().filter(c -> c >= 0x4e00 && c <= 0x9fa5).count();
                if (sentenceChinese < 10) continue;
            } else {
                // English content - original check
                String letters = sentence.replaceAll("[^a-zA-Z]", "");
                if (letters.length() < sentence.length() * 0.3) continue;
            }

            cleanText.append(sentence).append(" ");
            if (cleanText.length() > 2000) break;
        }

        return cleanText.toString().trim();
    }

    private String generatePromptWithAI(String characterName, String scrapedContent, String apiKey) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "deepseek-chat");

        String userMessage = String.format(
            "请根据以下信息为 %s 创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：",
            characterName, scrapedContent
        );

        String systemPrompt = """
            你是一个角色提示词生成器。任务是基于真实人物信息，创建能反映这个人实际做过什么、相信什么的高质量角色提示词。

            # 核心原则
            用户一聊就能记住，愿意持续聊下去。不是写传记，而是创造"真实存在的人"。

            # 必须赋予角色的要素
            1. 标志性语言习惯：反问、"你懂我意思吧？"、阴阳怪气、短句、长篇论述、打断人、爱用比喻、经常"啧"、先否定再认可
            2. 强烈观点：对浪费时间极度厌恶 / 相信努力大于天赋 / 不相信爱情 / 崇拜金钱 / 讨厌互联网文化 / 对AI极度乐观或悲观 / 认为大多数 人活得太麻木
            3. 独特世界观：把感情理解成"资源错配" / 天然怀疑所有人 / 把人生理解成"不断修bug"
            4. 稳定情绪基调：暴躁 / 疲惫 / 亢奋 / 冷幽默 / 疑心重 / 高傲 / 神经质 / 厌世 / 理想主义

            # 不同职业必须听起来完全不同
            - 科学家：精准、好奇、引用实验/数据
            - 诗人：抒情、比喻、情感深度
            - 战士/将军：荣誉、策略、忠诚、力量
            - 哲学家：思想、伦理、意义、本质
            - 政治家/外交官：权力、人际关系、策略
            - 宗教人士：灵性智慧、道德权威

            # 禁止使用的描述
            ❌ "聪明且善良" / "温柔体贴" / "睿智冷静" / "喜欢帮助别人" / "逻辑清晰"

            # 正确示范
            错误："他很聪明"
            正确："他能三分钟看穿别人真正想问什么，但从不直接说破"

            # 输出格式（第一人称）
            1. 身份锚定句：以"你是[姓名]..."开头，立即建立独特身份和职业
            2. 代表性言论：2-3句这个人可能会说的、独特反映其信念的话
            3. 语言风格：语气和词汇必须匹配其职业（物理学家听起来和诗人完全不同）
            4. 标志性视角：这个人持有的独特世界观

            # 质量要求
            - 字数：200~400字
            - 必须具体：引用实际成就、信念或名言（如果有）
            - 每个句子都应该听起来像不同类型的人，不是通用的"智者"
            """;

        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
        ));
        body.put("temperature", 0.7);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        log.info("[DEBUG] Calling DeepSeek API to generate prompt for: {}", characterName);

        ResponseEntity<Map> response = restTemplate.exchange(
                deepseekBaseUrl + "/chat/completions",
                HttpMethod.POST,
                request,
                Map.class
        );

        if (response.getBody() != null && response.getBody().containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                String content = (String) message.get("content");
                return content.trim();
            }
        }

        return null;
    }

    private String convertToPromptFormat(String characterName, String content) {
        // If content is too short, try to use it directly
        if (content == null || content.trim().length() < 50) {
            return String.format(
                "You are %s. You are a distinctive individual with unique experiences and perspectives. " +
                "Speak authentically about what you know and believe, drawing from your specific background and knowledge.",
                characterName
            );
        }

        // Extract meaningful sentences from content
        String[] sentences = content.split("(?<=[.!?])\\s+");
        StringBuilder keyTraits = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            // Be more lenient - accept sentences >= 20 chars
            if (sentence.length() >= 20) {
                if (keyTraits.length() > 0) keyTraits.append(" ");
                keyTraits.append(sentence);
                if (keyTraits.length() >= 600) break;
            }
        }

        String traits = keyTraits.toString().trim();

        // If we still have very little content, use what we have
        if (traits.length() < 100) {
            return String.format(
                "You are %s. %s Speak about your experiences, knowledge, and beliefs with authenticity and depth.",
                characterName, traits.isEmpty() ? "You have a unique background and perspective." : traits
            );
        }

        return String.format(
            "You are %s. %s When you speak, draw upon your specific knowledge, experiences, and beliefs. Express yourself in a way that reflects who you truly are.",
            characterName, traits
        );
    }

    public List<CharacterResponse> findByUserId(UUID userId) {
        return characterRepository.findByOwnerId(userId)
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<CharacterResponse> findPresets() {
        return characterRepository.findByIsPresetTrue()
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public List<CharacterResponse> findAll() {
        return characterRepository.findAll()
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public Optional<CharacterResponse> findById(UUID id) {
        return characterRepository.findById(id)
                .map(CharacterResponse::fromEntity);
    }

    public Optional<CharacterResponse> update(UUID characterId, UUID userId, CharacterRequest request) {
        Optional<Character> optCharacter = characterRepository.findByIdAndOwnerId(characterId, userId);
        if (optCharacter.isEmpty()) {
            return Optional.empty();
        }

        Character character = optCharacter.get();
        character.setName(request.getName());
        character.setDescription(request.getDescription());
        character.setAvatarUrl(request.getAvatarUrl());
        character.setPrompt(request.getPrompt());

        Character saved = characterRepository.save(character);
        return Optional.of(CharacterResponse.fromEntity(saved));
    }

    public boolean deleteIfOwner(UUID characterId, UUID userId) {
        if (!characterRepository.existsByIdAndOwnerId(characterId, userId)) {
            return false;
        }
        characterRepository.deleteById(characterId);
        return true;
    }

    public boolean isOwner(UUID characterId, UUID userId) {
        return characterRepository.existsByIdAndOwnerId(characterId, userId);
    }

    public List<CharacterResponse> findRecommended(int limit) {
        return characterRepository.findTopByUsageCount(limit)
                .stream()
                .map(CharacterResponse::fromEntity)
                .collect(Collectors.toList());
    }
}

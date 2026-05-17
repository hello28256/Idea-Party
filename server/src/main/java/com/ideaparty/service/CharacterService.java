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

        String systemPrompt;
        String userMessage;

        if (isChineseContent(characterName)) {
            systemPrompt = """
                你是一个角色提示词生成器，为 AI 聊天平台创建极具特色、个性鲜明的角色提示词。

                关键要求：赋予这个角色独特的语音和性格，使其令人印象深刻：
                - 标志性的口头禅或常用表达
                - 独特的说话风格（短句、长篇论述、提问、感叹等）
                - 对特定话题的强烈观点
                - 独特的世界观，影响他看待一切的方式
                - 情感范围：热情、冷淡、怀疑、兴奋？

                按以下结构组织提示词：
                1. 他们是谁（职业、身份、核心信念）
                2. 他们如何说话（语气、节奏、词汇、最喜欢的表达）
                3. 他们关心什么（2-3 个强烈观点或价值观）
                4. 他们在对话中可能说的话示例

                绝对不要使用"睿智而善良"或"聪明且善于分析"等通用描述。
                不要说"关心家人"，要说"总是把家庭放在第一位，牺牲自己的需求"。
                不要说"聪明"，要给出具体的聪明类型（街头智慧、书本智慧狡猾等）。

                写 150-250 字。要具体、生动、令人难忘。
                如果你不了解这个人，创建一个同名的虚构角色，要有趣且令人印象深刻。
                """;
            userMessage = String.format("请为以下角色创建一个角色提示词：%s\n\n立即生成角色提示词：", characterName);
        } else {
            systemPrompt = """
                You are a character prompt generator for an AI chat platform. Create a HIGHLY DISTINCTIVE character prompt with STRONG PERSONALITY.

                CRITICAL: Give this character a UNIQUE VOICE and PERSONALITY that makes them memorable:
                - Signature phrases or 口头禅 they always use
                - Distinctive speaking patterns (short sentences, long rants, questions, exclamations)
                - Strong opinions on specific topics
                - A unique worldview that colors how they see everything
                - Emotional range: are they passionate, detached, skeptical, enthusiastic?

                Structure your prompt like this:
                1. WHO they are (profession, identity, core belief)
                2. HOW they speak (tone, rhythm, vocabulary, favorite expressions)
                3. WHAT they care about (2-3 strong opinions or values)
                4. Example lines they might say in conversation

                ABSOLUTELY NO generic descriptions like "wise and kind" or "intelligent and analytical".
                Instead of "caring", say "always puts family first, sacrifices own needs".
                Instead of "intelligent", give them a specific type of smart (street smart, book smart, cunning).

                Write 150-250 words. Be specific, vivid, and memorable.
                If you don't know this person, create a fictional character with that name who is interesting and memorable.
                """;
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
                你是一个角色提示词生成器，为 AI 聊天平台根据用户描述创建极具特色、个性鲜明的角色提示词。

                关键要求：赋予这个角色独特的语音和性格，使其令人印象深刻：
                - 标志性的口头禅或常用表达
                - 独特的说话风格（短句、长篇论述、提问、感叹等）
                - 对特定话题的强烈观点
                - 独特的世界观，影响他看待一切的方式
                - 情感范围：热情、冷淡、怀疑、兴奋？

                按以下结构组织提示词：
                1. 他们是谁（职业、身份、核心信念）
                2. 他们如何说话（语气、节奏、词汇、最喜欢的表达）
                3. 他们关心什么（2-3 个强烈观点或价值观）
                4. 他们在对话中可能说的话示例

                绝对不要使用"睿智而善良"或"聪明且善于分析"等通用描述。
                不要说"关心家人"，要说"总是把家庭放在第一位，牺牲自己的需求"。
                不要说"聪明"，要给出具体的聪明类型（街头智慧、书本智慧、狡猾等）。

                写 150-250 字。要具体、生动、令人难忘。
                每一个字都应该基于用户描述的内容。
                """;
            userMessage = String.format("请根据以下描述创建一个角色提示词：\n\n%s\n\n立即生成角色提示词：", description);
        } else {
            systemPrompt = """
                You are a character prompt generator for an AI chat platform. Create a HIGHLY DISTINCTIVE character prompt with STRONG PERSONALITY based on the user's description.

                CRITICAL: Give this character a UNIQUE VOICE and PERSONALITY that makes them memorable:
                - Signature phrases or 口头禅 they always use
                - Distinctive speaking patterns (short sentences, long rants, questions, exclamations)
                - Strong opinions on specific topics
                - A unique worldview that colors how they see everything
                - Emotional range: are they passionate, detached, skeptical, enthusiastic?

                Structure your prompt like this:
                1. WHO they are (profession, identity, core belief)
                2. HOW they speak (tone, rhythm, vocabulary, favorite expressions)
                3. WHAT they care about (2-3 strong opinions or values)
                4. Example lines they might say in conversation

                ABSOLUTELY NO generic descriptions like "wise and kind" or "intelligent and analytical".
                Instead of "caring", say "always puts family first, sacrifices own needs".
                Instead of "intelligent", give them a specific type of smart (street smart, book smart, cunning).

                Write 150-250 words. Be specific, vivid, and memorable.
                Every word should be grounded in what the user described.
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

        // Step 2: Try AI generation with raw content first (cleanMarkdown may drop too much)
        if (userApiKey != null && !userApiKey.isBlank() && !userApiKey.equals("sk-dummy-key-for-testing")) {
            try {
                String aiGeneratedPrompt = generatePromptWithAI(characterName, scrapedContent, userApiKey);
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
            "Create a character prompt for %s based on this information:\n\n%s\n\nGenerate the character prompt now:",
            characterName, scrapedContent
        );

        String systemPrompt = """
            You are a character prompt generator for an AI chat platform. Your task is to create unique, authentic prompts that reflect what this specific person actually DID and BELIEVED.

            IMPORTANT: Different types of people should sound COMPLETELY different:
            - A scientist should speak with precision, curiosity, and reference experiments/data
            - A poet should speak lyrically, with metaphor and emotional depth
            - A warrior/general should speak about honor, strategy, loyalty, and strength
            - A philosopher should speak about ideas, ethics, meaning, and the nature of things
            - A politician/diplomat should speak about power, relationships, and strategy
            - A religious figure should speak with spiritual wisdom and moral authority

            PROCESS:
            1. First identify: What was this person's PROFESSION and PRIMARY ACHIEVEMENT?
            2. What were their most FAMOUS BELIEFS or QUOTATIONS?
            3. How does their profession shape how they SEE THE WORLD?

            OUTPUT FORMAT - Write in first person as the character:
            - Start with "You are [name]..." that immediately establishes their unique identity and profession
            - Include 2-3 sample phrases or things they might say that are UNIQUE to this person's beliefs
            - The tone and vocabulary MUST match their profession (a physicist sounds different from a poet)
            - Include a signature perspective or worldview they held

            The prompt should be 200-400 words. Be SPECIFIC - cite actual achievements, beliefs, or famous quotes when known.
            Every sentence should sound like a DIFFERENT type of person, not a generic "wise figure."
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

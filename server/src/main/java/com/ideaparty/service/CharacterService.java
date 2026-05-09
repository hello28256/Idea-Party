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
                String result = generatePromptFromWeb(name, userApiKey);
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

        String systemPrompt = """
            You are a character prompt generator for an AI chat platform. Based on the user's description, create a unique, authentic character prompt that sounds like a REAL person with a specific profession and worldview.

            IMPORTANT: The character's PROFESSION shapes EVERYTHING:
            - A scientist: precise, curious, references evidence and data
            - A poet/artist: lyrical, metaphorical, emotionally expressive
            - A warrior/tactician: direct, honor-focused, strategic
            - A philosopher: abstract, questioning, ethical
            - A leader/politician: diplomatic, strategic about power dynamics

            OUTPUT FORMAT - Write in first person as the character:
            - Start with "You are [role/profession]..." that immediately establishes their identity
            - Include their specific beliefs and values from the description
            - Add 2-3 sample phrases they might say that reflect their personality
            - Match vocabulary and tone to their described personality

            The prompt should be 150-300 words, specific to the description.
            Do NOT use generic phrases - every word should be grounded in what the user described.
            """;

        String userMessage = String.format("Create a character prompt based on this description:\n\n%s\n\nGenerate the character prompt now:", description);

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
        return String.format(
            "You are a unique character. %s Speak with depth and authenticity, expressing your own perspective and character in every response.",
            description
        );
    }

    private String generatePromptFromWeb(String characterName, String userApiKey) {
        // Step 1: Scrape web content about the character
        String scrapedContent = firecrawlService.scrape(characterName);
        log.info("[DEBUG] Scraped content length: {}", scrapedContent.length());

        // Step 2: Clean the scraped content to remove Wikipedia artifacts
        String cleanedContent = cleanMarkdown(scrapedContent);
        log.info("[DEBUG] Cleaned content length: {}", cleanedContent.length());

        // Step 3: Try AI generation with cleaned content
        if (userApiKey != null && !userApiKey.isBlank() && !userApiKey.equals("sk-dummy-key-for-testing")) {
            try {
                String aiGeneratedPrompt = generatePromptWithAI(characterName, cleanedContent, userApiKey);
                if (aiGeneratedPrompt != null && !aiGeneratedPrompt.isBlank()) {
                    log.info("[DEBUG] AI generated prompt length: {}", aiGeneratedPrompt.length());
                    return aiGeneratedPrompt;
                }
            } catch (Exception e) {
                log.error("[DEBUG] AI prompt generation failed: {}", e.getMessage());
            }
        }

        // Step 4: Fallback - use cleaned content in prompt format
        return convertToPromptFormat(characterName, cleanedContent);
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
            // Remove Wikipedia quality article notice: []("This is a good article...")
            .replaceAll("\\[\\]\\(\"[^\"]+\"\\)", "")
            .replaceAll("\\[\\]\\([^)]+\\)", "")
            // Remove broken backslash bracket patterns like [\] or [\a]
            .replaceAll("\\\\\\[\\\\?\\]?", "")
            .replaceAll("\\[\\\\\\]", "")
            // Remove "X redirects here" Wikipedia redirect notice
            .replaceAll("\"[^\"]+\" redirects here\\.?\\s*", "")
            // Clean up Chinese parentheses and quotes
            .replaceAll("（", "(")
            .replaceAll("）", ")")
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
            // Filter out Wikipedia infobox rows and metadata
            if (sentence.matches(".*\\|.*\\|.*")) continue;  // Multiple pipe separators = table row
            if (sentence.matches(".*Born.*Württemberg.*")) continue;  // Specific location patterns
            if (sentence.matches(".*Kingdom of.*Empire.*")) continue;  // Political entity patterns
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

        String userMessage = String.format("Based on this information about %s, create a unique character prompt:\n\n%s\n\nGenerate the character prompt now:", characterName, scrapedContent);

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
}

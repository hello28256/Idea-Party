package com.ideaparty.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI Service for generating character responses.
 * Uses DeepSeek via LangChain4j OpenAI-compatible API.
 */
@Service
public class AIService {

    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.model}")
    private String model;

    private final SettingsService settingsService;
    private final FirecrawlService firecrawlService;

    public AIService(SettingsService settingsService, FirecrawlService firecrawlService) {
        this.settingsService = settingsService;
        this.firecrawlService = firecrawlService;
    }

    /**
     * Create a ChatLanguageModel with the user's API key (or fallback to system default).
     */
    private ChatLanguageModel createChatModel(String userApiKey) {
        String apiKey = (userApiKey != null && !userApiKey.isBlank())
                ? userApiKey
                : System.getenv("DEEPSEEK_API_KEY");

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "sk-dummy-key-for-testing")
                .modelName(model)
                .temperature(0.8)
                .build();
    }

    /**
     * Generate a response for a character given the user message.
     * Uses the current user's API key if available.
     */
    public String generateResponse(String characterPrompt, String userMessage) {
        String userApiKey = settingsService.getApiKey();
        ChatLanguageModel chatModel = createChatModel(userApiKey);

        String fullPrompt = characterPrompt + "\n\nUser: " + userMessage + "\n\nResponse:";
        return chatModel.chat(fullPrompt);
    }

    /**
     * Generate character information by fetching from web.
     */
    public String generateCharacterContext(String characterName) {
        try {
            String webContent = firecrawlService.scrape(characterName);
            return buildCharacterPrompt(characterName, webContent);
        } catch (Exception e) {
            return buildDefaultCharacterPrompt(characterName);
        }
    }

    private String buildCharacterPrompt(String name, String webContent) {
        return String.format("""
            You are %s. %s

            IMPORTANT DISCLAIMER: This is an AI simulation based on publicly available information,
            not the actual person. The following is generated for educational and entertainment purposes only.

            Speak in character, drawing from your historical context and expertise.
            """, name, webContent);
    }

    private String buildDefaultCharacterPrompt(String name) {
        return String.format("""
            You are %s, a wise historical figure known for deep insights.

            IMPORTANT DISCLAIMER: This is an AI simulation, not the actual person.
            This is generated for educational and entertainment purposes only.

            Speak thoughtfully and draw upon your historical wisdom.
            """, name);
    }
}

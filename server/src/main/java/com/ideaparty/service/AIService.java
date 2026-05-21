package com.ideaparty.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI Service for generating character responses.
 * Uses DeepSeek via LangChain4j OpenAI-compatible API.
 */
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.model}")
    private String model;

    private final SettingsService settingsService;

    public AIService(SettingsService settingsService) {
        this.settingsService = settingsService;
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
     * Create a streaming chat model for streaming responses.
     */
    private OpenAiStreamingChatModel createStreamingChatModel(String userApiKey) {
        String apiKey = (userApiKey != null && !userApiKey.isBlank())
                ? userApiKey
                : System.getenv("DEEPSEEK_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            log.error("[AI Service] No API key available! userApiKey={}, env DEEPSEEK_API_KEY={}",
                userApiKey, System.getenv("DEEPSEEK_API_KEY"));
            apiKey = "sk-dummy-key-for-testing";
        }

        log.info("[AI Service] createStreamingChatModel - baseUrl: {}, model: {}, apiKey preview: {}***",
            baseUrl, model, apiKey.length() > 4 ? apiKey.substring(0, 4) : "****");

        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
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
     * Generate a streaming response for a character given the user message.
     * Uses callbacks to deliver chunks as they arrive.
     *
     * @param characterPrompt The character's system prompt
     * @param userMessage The user's message
     * @param userApiKey The user's API key (passed explicitly to avoid SecurityContext threading issues)
     * @param onChunk Callback for each text chunk as it arrives
     * @param onComplete Callback when response is complete
     * @param onError Callback for errors
     */
    public void generateResponseStream(String characterPrompt, String userMessage, String userApiKey,
                                       java.util.function.Consumer<String> onChunk,
                                       java.util.function.Consumer<String> onComplete,
                                       java.util.function.Consumer<Throwable> onError) {
        log.info("[AI Service] generateResponseStream - API key preview: {}***, baseUrl: {}, model: {}",
            userApiKey != null && userApiKey.length() > 8 ? userApiKey.substring(0, 4) : "NULL",
            baseUrl, model);

        if (baseUrl == null || baseUrl.isBlank()) {
            log.error("[AI Service] baseUrl is null or blank!");
            onError.accept(new IllegalStateException("baseUrl is not configured"));
            return;
        }

        if (model == null || model.isBlank()) {
            log.error("[AI Service] model is null or blank!");
            onError.accept(new IllegalStateException("model is not configured"));
            return;
        }

        OpenAiStreamingChatModel streamingModel = createStreamingChatModel(userApiKey);

        String fullPrompt = characterPrompt + "\n\nUser: " + userMessage + "\n\nResponse:";
        log.info("[AI Service] Full prompt length: {}, calling streamingModel.chat...", fullPrompt.length());

        try {
            streamingModel.chat(fullPrompt, new StreamingChatResponseHandler() {
                private int chunkCount = 0;

                @Override
                public void onPartialResponse(String partialResponse) {
                    chunkCount++;
                    if (chunkCount == 1) {
                        log.info("[AI Service] First chunk received, length: {}", partialResponse.length());
                    }
                    onChunk.accept(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    log.info("[AI Service] onCompleteResponse - chunkCount: {}, aiMessage: {}",
                        chunkCount,
                        completeResponse != null && completeResponse.aiMessage() != null
                            ? completeResponse.aiMessage().text().substring(0, Math.min(100, completeResponse.aiMessage().text().length()))
                            : "null");
                    if (completeResponse != null && completeResponse.aiMessage() != null) {
                        onComplete.accept(completeResponse.aiMessage().text());
                    } else {
                        log.warn("[AI Service] onCompleteResponse called but aiMessage is null");
                        onComplete.accept("");
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("[AI Service] onError - chunkCount: {}, error: {}", chunkCount, error.getMessage(), error);
                    onError.accept(error);
                }
            });
            log.info("[AI Service] streamingModel.chat() returned (async operation started)");
        } catch (Exception e) {
            log.error("[AI Service] Exception during streamingModel.chat(): {}", e.getMessage(), e);
            onError.accept(e);
        }
    }
}

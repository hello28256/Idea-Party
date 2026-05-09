package com.ideaparty.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

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

        return OpenAiStreamingChatModel.builder()
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
     * Generate a streaming response for a character given the user message.
     * Uses callbacks to deliver chunks as they arrive.
     *
     * @param characterPrompt The character's system prompt
     * @param userMessage The user's message
     * @param onChunk Callback for each text chunk as it arrives
     * @param onComplete Callback when response is complete
     * @param onError Callback for errors
     */
    public void generateResponseStream(String characterPrompt, String userMessage,
                                       java.util.function.Consumer<String> onChunk,
                                       java.util.function.Consumer<String> onComplete,
                                       java.util.function.Consumer<Throwable> onError) {
        String userApiKey = settingsService.getApiKey();
        OpenAiStreamingChatModel streamingModel = createStreamingChatModel(userApiKey);

        String fullPrompt = characterPrompt + "\n\nUser: " + userMessage + "\n\nResponse:";

        streamingModel.chat(fullPrompt, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                onChunk.accept(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (completeResponse != null && completeResponse.aiMessage() != null) {
                    onComplete.accept(completeResponse.aiMessage().text());
                } else {
                    onComplete.accept("");
                }
            }

            @Override
            public void onError(Throwable error) {
                onError.accept(error);
            }
        });
    }
}

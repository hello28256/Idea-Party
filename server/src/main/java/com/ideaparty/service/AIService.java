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

import java.time.Duration;

/**
 * AI Service for generating character responses.
 * Uses DeepSeek via LangChain4j OpenAI-compatible API.
 *
 * <p>集中封装 LLM 调用，避免业务层直接依赖 LangChain4j / DeepSeek SDK。
 * 同时屏蔽「用户自带 Key 优先、缺失时回落到系统环境变量」这一安全策略，
 * 配合 {@link SettingsService} 实现按用户隔离的调用配额与可观测性。
 */
// 之所以同时提供同步与流式两套入口：Moderator 选择需要一次性返回（见 createChatModelWithApiKey），
// 而前端圆桌对话要求逐 token 推送（见 generateResponseStream），共用底层模型构建逻辑避免配置漂移。
@Service
public class AIService {

    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    // baseUrl / model 来自 application.yml，便于不改代码即可切换 DeepSeek / 其它 OpenAI 兼容服务。
    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.model}")
    private String model;

    // 显式读取超时配置：application.properties 里的 langchain4j.open-ai.timeout 不会自动
    // 传到 LangChain4j 的 OpenAiChatModel / OpenAiStreamingChatModel builder，
    // 必须显式调用 .timeout(...) 才能避免上游 SSE 卡死时回调永不触发。
    @Value("${langchain4j.open-ai.timeout:120s}")
    private Duration chatTimeout;

    // 注入 SettingsService 用于读取当前登录用户的 API Key，避免在 AI 调用链路上访问 SecurityContext（线程切换场景易丢上下文）。
    private final SettingsService settingsService;

    public AIService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Create a ChatLanguageModel with the user's API key (or fallback to system default).
     *
     * <p>契约：userApiKey 为空时回落系统环境变量；二者都缺时使用占位 dummy key，
     * 让模型构建不抛错（实际请求会由上游失败），便于在前端未配置 Key 的开发态下联调 UI。
     */
    private ChatLanguageModel createChatModel(String userApiKey) {
        String apiKey = (userApiKey != null && !userApiKey.isBlank())
                ? userApiKey
                : System.getenv("DEEPSEEK_API_KEY");

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "sk-dummy-key-for-testing")
                .modelName(model)
                // 0.8 偏创造性，契合多角色圆桌「观点多样」的产品定位；过低会变得机械单调。
                .temperature(0.8)
                // 必须显式设置 builder 的 timeout，否则 application.properties 的 timeout 不会生效，
                // SSE/上游卡死时回调永不返回，整个 WS 流和线程会被持续占用。
                .timeout(chatTimeout)
                .build();
    }

    /**
     * Create a streaming chat model for streaming responses.
     *
     * <p>与 {@link #createChatModel} 的差异：流式路径额外做了 key 缺失的显式日志，
     * 因为前端会依赖首个 token 的延迟感知「AI 在思考」，配置错位必须暴露在日志而不是静默 dummy。
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
                // 与同步路径保持一致 0.8，避免同一角色在两种调用下风格漂移。
                .temperature(0.8)
                // 同步显式设置超时，避免上游卡死时 onError 永不回调 → WS 流和线程占用
                .timeout(chatTimeout)
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
     * Generate a response with conversation history context.
     * @param characterPrompt The character's system prompt
     * @param userMessage The current user message
     * @param conversationHistory Formatted history string (e.g., "User: xxx\nResponse: yyy\nUser: zzz\nResponse: ...")
     * @return AI response
     */
    public String generateResponseWithHistory(String characterPrompt, String userMessage, String conversationHistory) {
        // 设计取舍：把历史拼成纯文本前缀喂给模型，而不是用 ChatMemory 多轮消息结构，
        // 是为了让 Moderator/角色复用同一份 prompt 模板，省去维护多套会话状态。
        String userApiKey = settingsService.getApiKey();
        ChatLanguageModel chatModel = createChatModel(userApiKey);

        String fullPrompt = characterPrompt;
        if (conversationHistory != null && !conversationHistory.isBlank()) {
            fullPrompt += "\n\n[Conversation History]\n" + conversationHistory + "\n[/Conversation History]\n\n";
        }
        fullPrompt += "User: " + userMessage + "\n\nResponse:";
        return chatModel.chat(fullPrompt);
    }

    /**
     * Generate a chat model with the user's API key for moderator selection.
     */
    public ChatLanguageModel createChatModelWithApiKey(String userApiKey) {
        return createChatModel(userApiKey);
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
        // 之所以用回调而不是返回 Flux/Publisher：Spring WebFlux 之外的同步 Servlet 栈也能直接复用，
        // 并且错误可以在任意阶段短路，不必处理背压（DeepSeek 流速本身远低于 UI 渲染）。
        log.info("[AI Service] generateResponseStream - API key preview: {}***, baseUrl: {}, model: {}",
            userApiKey != null && userApiKey.length() > 8 ? userApiKey.substring(0, 4) : "NULL",
            baseUrl, model);

        if (baseUrl == null || baseUrl.isBlank()) {
            // 显式早退：LangChain4j 在 builder 阶段不会校验，这里拦截可以避免请求带着 null 打到上游拿到不可读的报错。
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

        // StringBuilder to accumulate chunks for final response
        final StringBuilder accumulatedResponse = new StringBuilder();

        try {
            streamingModel.chat(fullPrompt, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    // 累加器只用于 onComplete 兜底输出（部分 SDK 在异常时不会回调 onCompleteResponse）。
                    accumulatedResponse.append(partialResponse);
                    log.info("[AI Service] onPartialResponse TS={} - token len={}, total={}, token='{}'",
                        System.currentTimeMillis(), partialResponse.length(), accumulatedResponse.length(), partialResponse);
                    // 向回调传「增量 token」而非累计文本：前端按 delta 拼接，避免重复渲染历史片段。
                    onChunk.accept(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String responseText = accumulatedResponse.toString();
                    log.info("[AI Service] onCompleteResponse - response length: {}",
                        responseText.length());
                    onComplete.accept(responseText);
                }

                @Override
                public void onError(Throwable error) {
                    log.error("[AI Service] onError - error: {}", error.getMessage(), error);
                    onError.accept(error);
                }
            });
            log.info("[AI Service] streamingModel.chat() returned (async operation started)");
        } catch (Exception e) {
            // chat() 内部异步执行，正常错误走 onError 回调；这里只兜底同步阶段（如参数校验、连接建立）的失败。
            log.error("[AI Service] Exception during streamingModel.chat(): {}", e.getMessage(), e);
            onError.accept(e);
        }
    }
}

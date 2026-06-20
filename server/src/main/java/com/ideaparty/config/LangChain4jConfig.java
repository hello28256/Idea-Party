package com.ideaparty.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 与 Spring Boot 集成的配置类。
 * 负责把 application.yml 中声明的 OpenAI 兼容配置（DeepSeek base URL / API Key / 模型名）
 * 注入到 LangChain4j 的 ChatModel Bean 中，供 AIService 层的非流式与流式调用复用。
 */
@Configuration
public class LangChain4jConfig {

    /**
     * OpenAI 兼容服务的 base URL（如 DeepSeek 的 https://api.deepseek.com/v1）。
     * LangChain4j 的 openai starter 借此复用同一套 SDK 调用国产/第三方模型，避免硬编码到代码里。
     */
    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    /**
     * 上游 LLM 提供商的 API Key。集中从配置注入是为了保证密钥只出现在后端配置中，
     * 杜绝泄露到前端或 git 仓库的风险（对应项目 CLAUDE.md 的 "AI 调用安全" 约束）。
     */
    @Value("${langchain4j.open-ai.api-key}")
    private String apiKey;

    /**
     * 实际要调用的模型名（如 deepseek-chat）。通过配置切换便于在不同环境/不同模型之间
     * 灰度，无需重新发布代码。
     */
    @Value("${langchain4j.open-ai.model}")
    private String model;

    /**
     * 构建非流式 ChatLanguageModel Bean。
     * 用于角色一次性生成完整回复的场景（Moderator 编排、角色 prompt 生成等）。
     * temperature=0.8 在多角色群聊中提供适度创造性，但又不至于过度发散。
     *
     * @return 配置好的非流式聊天模型实例
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.8)
                .build();
    }

    /**
     * 构建流式 StreamingChatLanguageModel Bean。
     * 用于聊天室场景下逐 token 推送给前端（Socket.IO/STOMP 转发），降低用户感知延迟。
     * 与非流式 Bean 共享 baseUrl/apiKey/model，但独立 Bean 是为了避免 LangChain4j 内部状态互相污染。
     *
     * @return 配置好的流式聊天模型实例
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.8)
                .build();
    }
}

package com.ideaparty.service;

import com.ideaparty.entity.Character;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mock AI service for Phase 1 - generates round-robin mock responses.
 * In Phase 2, this will be replaced with LangChain4j Moderator Agent.
 */
@Slf4j
@Service
public class MockAiService implements DisposableBean {

    // 共享随机源：用于模拟思考延迟与挑选用语模板，所有并发请求共用同一实例即可（Random 本身线程安全）。
    private final Random random = new Random();
    // 异步执行器：将每条 mock 回复放到独立线程模拟网络/推理耗时，让 Controller 可以立即返回 Future；缓存线程池避免频繁创建/销毁。
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 按人设类型组织的 Mock 回复，丰富多样性
    // 哲学家人设的回复模板库：占位符 %s 在运行时替换为角色名，让 mock 看起来像是"该角色本人在说"。
    private static final String[] PHILOSOPHER_RESPONSES = {
        "从哲学的角度来看，这个问题触及了存在的本质。%s的观点值得我们深思。",
        "我认为 %s 的思想为我们提供了一个独特的视角。让我分享我的思考...",
        "正如 %s 曾经思考的那样，这个问题没有简单的答案。",
        "让我们从 %s 的哲学传统出发，来探讨这个话题。",
    };

    // 科学家人设的回复模板库：用"推理/分析/研究"等措辞与哲学家区分；同样通过 %s 注入角色名以增强代入感。
    private static final String[] SCIENTIST_RESPONSES = {
        "根据我的分析，%s 的方法论给了我们重要启示。让我解释一下...",
        "这个问题很有趣。让我用 %s 的科学思维方式来思考...",
        "从科学角度来看，%s 的研究为我们提供了有价值的参考。",
        "我的推理是这样的：如果我们遵循 %s 的方法，可能会发现...",
    };

    // 艺术家人设的回复模板库：采用更具感性色彩的措辞，与科学家/哲学家的语气形成对比。
    private static final String[] ARTIST_RESPONSES = {
        "啊，%s 的灵感来了！这个话题让我想起...",
        "用 %s 的话说，这就像是色彩与情感的交织。",
        "这个问题如同艺术本身一样美丽。让我以 %s 的风格来回应...",
        "在我作为 %s 的视角中，这个问题充满了创造的可能性。",
    };

    // 通用人设的回复模板库：当角色描述无法匹配任何具体原型时回退到这里，保证 mock 始终有可输出的内容。
    private static final String[] GENERAL_RESPONSES = {
        "这是个很好的观点。让我从 %s 的角度来思考...",
        "%s 会怎么说呢？我认为应该从多个角度来看待这个问题。",
        "作为 %s，我有一些不同的想法。让我分享给大家...",
        "这个问题很有趣。%s 会如何回应呢？让我试着模拟...",
    };

    /**
     * Generate a mock AI response for a character.
     * Response is based on character archetype and includes character's name for personalization.
     *
     * 异步生成该角色的 mock 回复。
     * @param character 触发回复的角色实体；用其 name/description 决定模板分支
     * @param userMessage 用户消息；当前 mock 不消费内容，保留参数是为了与未来真实 AIService 保持签名一致
     * @return 异步完成的回复字符串；由 Moderator/Room 端通过 .join() 或 .thenAccept() 编排发言顺序
     */
    public CompletableFuture<String> generateResponse(Character character, String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            // Simulate thinking delay (1-3 seconds) using non-blocking approach
            int delayMs = 1000 + random.nextInt(2000);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String response = buildResponse(character, userMessage);
            return response;
        }, executor);
    }

    /**
     * 根据角色人设与原始用户消息拼装出最终回复文本。
     * 选择模板 + 注入角色名两步完成；与 {@link #generateResponse} 拆分便于未来替换为真实 LLM 调用。
     */
    private String buildResponse(Character character, String userMessage) {
        String characterName = character.getName();
        String[] responses = getResponsesForArchetype(character);

        String template = responses[random.nextInt(responses.length)];
        return String.format(template, characterName);
    }

    /**
     * 依据角色描述（description）中的关键词做粗粒度人设分类，返回对应的模板库。
     * 使用关键词匹配而非枚举：避免要求用户填写结构化字段，从而降低前端录入成本；缺点是匹配不够精确，但作为 mock 已够用。
     */
    private String[] getResponsesForArchetype(Character character) {
        String description = character.getDescription() != null
            ? character.getDescription().toLowerCase()
            : "";

        if (description.contains("philosoph") || description.contains("thinker")) {
            return PHILOSOPHER_RESPONSES;
        } else if (description.contains("scientist") || description.contains("physics")
                || description.contains("research") || description.contains("inventor")) {
            return SCIENTIST_RESPONSES;
        } else if (description.contains("artist") || description.contains("writer")
                || description.contains("poet") || description.contains("musician")) {
            return ARTIST_RESPONSES;
        } else {
            return GENERAL_RESPONSES;
        }
    }

    /**
     * Spring 容器关闭时回调：优雅停掉内部线程池。
     * 之所以实现 {@link DisposableBean} 而不是依赖 @PreConstruct + @Bean(destroyMethod)，
     * 是因为本类用 @Service 注册，没有显式 @Bean 可挂 destroyMethod。
     * 双重 awaitTermination 兜底：第一次等待温和关停，第二次强制 shutdownNow 后再确认。
     */
    @Override
    public void destroy() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[DEBUG] MockAiService: Executor did not terminate");
            }
        }
        log.info("[DEBUG] MockAiService: ExecutorService shut down");
    }
}

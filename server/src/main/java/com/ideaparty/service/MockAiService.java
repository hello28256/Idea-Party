package com.ideaparty.service;

import com.ideaparty.entity.Character;
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
@Service
public class MockAiService implements DisposableBean {

    private final Random random = new Random();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Mock responses organized by archetype for variety
    private static final String[] PHILOSOPHER_RESPONSES = {
        "从哲学的角度来看，这个问题触及了存在的本质。%s的观点值得我们深思。",
        "我认为 %s 的思想为我们提供了一个独特的视角。让我分享我的思考...",
        "正如 %s 曾经思考的那样，这个问题没有简单的答案。",
        "让我们从 %s 的哲学传统出发，来探讨这个话题。",
    };

    private static final String[] SCIENTIST_RESPONSES = {
        "根据我的分析，%s 的方法论给了我们重要启示。让我解释一下...",
        "这个问题很有趣。让我用 %s 的科学思维方式来思考...",
        "从科学角度来看，%s 的研究为我们提供了有价值的参考。",
        "我的推理是这样的：如果我们遵循 %s 的方法，可能会发现...",
    };

    private static final String[] ARTIST_RESPONSES = {
        "啊，%s 的灵感来了！这个话题让我想起...",
        "用 %s 的话说，这就像是色彩与情感的交织。",
        "这个问题如同艺术本身一样美丽。让我以 %s 的风格来回应...",
        "在我作为 %s 的视角中，这个问题充满了创造的可能性。",
    };

    private static final String[] GENERAL_RESPONSES = {
        "这是个很好的观点。让我从 %s 的角度来思考...",
        "%s 会怎么说呢？我认为应该从多个角度来看待这个问题。",
        "作为 %s，我有一些不同的想法。让我分享给大家...",
        "这个问题很有趣。%s 会如何回应呢？让我试着模拟...",
    };

    /**
     * Generate a mock AI response for a character.
     * Response is based on character archetype and includes character's name for personalization.
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

    private String buildResponse(Character character, String userMessage) {
        String characterName = character.getName();
        String[] responses = getResponsesForArchetype(character);

        String template = responses[random.nextInt(responses.length)];
        return String.format(template, characterName);
    }

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

    @Override
    public void destroy() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                System.err.println("[DEBUG] MockAiService: Executor did not terminate");
            }
        }
        System.out.println("[DEBUG] MockAiService: ExecutorService shut down");
    }
}

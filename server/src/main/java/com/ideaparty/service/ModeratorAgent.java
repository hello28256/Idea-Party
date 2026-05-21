package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.service.FirecrawlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.DisposableBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContext;

/**
 * Moderator Agent for multi-round group discussion orchestration.
 *
 * Discussion flow:
 * 1. Round 1: All characters respond to the user's question in PARALLEL
 * 2. Round 2+: Characters comment on each other's responses, forming a debate
 * 3. After MAX_ROUNDS, discussion ends
 */
@Slf4j
@Service
public class ModeratorAgent implements DisposableBean {

    private final AIService aiService;
    private final MessageRepository messageRepository;
    private final FirecrawlService firecrawlService;
    private final SettingsService settingsService;

    // Maximum discussion rounds
    private static final int MAX_ROUNDS = 3;

    // Delay between rounds (milliseconds) to let responses propagate
    private static final long ROUND_DELAY_MS = 1500;

    // Executor for async operations - propagates SecurityContext to child threads
    private final ExecutorService executor;

    // Room-level futures tracking for cancellation
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository, FirecrawlService firecrawlService, SettingsService settingsService) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
        this.firecrawlService = firecrawlService;
        this.settingsService = settingsService;
        // Wrap executor so SecurityContext is inherited by async threads
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            // Inherit SecurityContext from the thread that submits the task
            SecurityContext ctx = SecurityContextHolder.getContext();
            t.setContextClassLoader(null);
            return new Thread(ctx != null ? new SecurityContextAwareThread(ctx, t) : t);
        });
    }

    // Wraps a Thread to set SecurityContext before run()
    private static class SecurityContextAwareThread extends Thread {
        private final SecurityContext context;

        SecurityContextAwareThread(SecurityContext context, Thread delegate) {
            super(delegate);
            this.context = context;
        }

        @Override
        public void run() {
            try {
                SecurityContextHolder.setContext(context);
                SecurityContextHolder.getContext().setAuthentication(context.getAuthentication());
                super.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * Process a user message and orchestrate AI character discussion.
     *
     * @param roomId The room ID
     * @param userId The user ID (passed explicitly to avoid SecurityContext threading issues)
     * @param userMessage The user's message
     * @param characters List of characters in the room
     * @param isContinuous If true, run multiple rounds (discussion mode); if false, single round (dialogue mode)
     * @param maxRounds Maximum rounds for continuous mode
     * @param onThinking Callback for "thinking" state
     * @param onChunk Callback for streaming chunks (called as content is generated)
     * @param onResponse Callback for each character's complete response
     */
    public void processMessage(String roomId, String userId, String userMessage, List<Character> characters,
                               boolean isContinuous, int maxRounds,
                               Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                               Consumer<ResponseFragment> onResponse) {
        if (characters == null || characters.isEmpty()) {
            return;
        }

        log.info("[Moderator] processMessage - roomId: {}, userId: {}, charCount: {}, isContinuous: {}",
            roomId, userId, characters.size(), isContinuous);

        // Build initial context with user message
        String initialContext = buildInitialContext(userMessage);

        // Track responses for each round
        Map<Integer, List<ResponseFragment>> roundResponses = new ConcurrentHashMap<>();

        try {
            if (isContinuous) {
                // Discussion mode: multi-round
                runDiscussionRound(roomId, userId, userMessage, initialContext, characters, 1,
                    maxRounds, roundResponses, onThinking, onChunk, onResponse);
            } else {
                // Dialogue mode: single round only
                runSingleRound(roomId, userId, userMessage, initialContext, characters,
                    roundResponses, onThinking, onChunk, onResponse);
            }
        } catch (Exception e) {
            log.error("[Moderator] processMessage caught exception: {}", e.getMessage(), e);
        }
    }

    /**
     * Run a single dialogue round (dialogue mode).
     */
    private void runSingleRound(String roomId, String userId, String userMessage, String context,
                                List<Character> characters,
                                Map<Integer, List<ResponseFragment>> roundResponses,
                                Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                Consumer<ResponseFragment> onResponse) {

        log.info("[Moderator] runSingleRound - roomId: {}, userId: {}, characters: {}, isContinuous: false",
            roomId, userId, characters.size());

        // Get API key in main thread before async tasks (avoids SecurityContext threading issues)
        String userApiKey = null;
        try {
            userApiKey = settingsService.getApiKeyById(userId);
            log.info("[Moderator] Got API key for userId: {}", userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key for userId {}: {}", userId, e.getMessage());
        }
        final String userApiKeyFinal = userApiKey;

        // Notify thinking for all characters first
        for (Character character : characters) {
            log.info("[Moderator] Notifying thinking for character: {} ({})", character.getName(), character.getId());
            onThinking.accept(character.getId().toString());
        }

        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(1, thisRoundResponses);

        // Process characters sequentially with random delay between them
        for (int i = 0; i < characters.size(); i++) {
            Character character = characters.get(i);

            // Random delay between 1-5 seconds before each character starts
            if (i > 0) {
                int delayMs = 1000 + (int) (Math.random() * 4000); // 1-5 seconds
                log.info("[Moderator] [{}] Waiting {}ms before starting (character {})",
                    character.getName(), delayMs, i + 1);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            log.info("[Moderator] [{}] Starting AI generation", character.getName());

            // Build character prompt and generate response synchronously (blocking)
            try {
                String characterPrompt = buildCharacterPrompt(character);
                String fullPrompt = characterPrompt + "\n\n" + context;

                log.info("[Moderator] [{}] Prompt built, length: {}, calling AI service",
                    character.getName(), fullPrompt.length());

                StringBuilder fullResponse = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);

                aiService.generateResponseStream(
                    fullPrompt,
                    userMessage,
                    userApiKeyFinal,
                    chunk -> {
                        fullResponse.append(chunk);
                        // Stream each chunk to frontend immediately
                        try {
                            onChunk.accept(new ResponseFragment(
                                character.getId().toString(),
                                character.getName(),
                                fullResponse.toString(),
                                false
                            ));
                        } catch (Exception e) {
                            log.warn("[Moderator] [{}] onChunk callback failed: {}",
                                character.getName(), e.getMessage());
                        }
                    },
                    completeResponse -> {
                        String responseText = fullResponse.toString();
                        log.info("[Moderator] [{}] onComplete - response length: {}",
                            character.getName(), responseText.length());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true
                        ));
                        latch.countDown();
                    },
                    error -> {
                        log.error("[Moderator] [{}] onError: {}", character.getName(), error.getMessage());
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            "Error: " + error.getMessage(),
                            true
                        ));
                        latch.countDown();
                    }
                );

                log.info("[Moderator] [{}] Waiting for AI response (60s timeout)", character.getName());
                boolean latchReleased = latch.await(60, TimeUnit.SECONDS);
                if (!latchReleased) {
                    log.warn("[Moderator] [{}] Latch timed out after 60s", character.getName());
                    String responseText = fullResponse.toString();
                    if (responseText.isEmpty()) {
                        responseText = "Error: Response timed out (60s)";
                    }
                    thisRoundResponses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true
                    ));
                } else {
                    log.info("[Moderator] [{}] AI response completed", character.getName());
                }

                // Send final complete response
                ResponseFragment finalFragment = thisRoundResponses.get(thisRoundResponses.size() - 1);
                try {
                    onResponse.accept(finalFragment);
                } catch (Exception e) {
                    log.error("[Moderator] [{}] onResponse callback failed: {}",
                        character.getName(), e.getMessage(), e);
                }

            } catch (Exception e) {
                log.error("[Moderator] [{}] Unexpected error: {}", character.getName(), e.getMessage(), e);
                thisRoundResponses.add(new ResponseFragment(
                    character.getId().toString(),
                    character.getName(),
                    "Error: " + e.getMessage(),
                    true
                ));
            }
        }

        log.info("[Moderator] All characters completed, total responses: {}", thisRoundResponses.size());
    }

    /**
     * Run a single discussion round (for continuous discussion mode).
     */
    private void runDiscussionRound(String roomId, String userId, String userMessage, String context,
                                   List<Character> characters, int roundNum, int maxRounds,
                                   Map<Integer, List<ResponseFragment>> roundResponses,
                                   Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                   Consumer<ResponseFragment> onResponse) {

        log.info("[Moderator] runDiscussionRound - roomId: {}, userId: {}, round: {}/{}, characters: {}",
            roomId, userId, roundNum, maxRounds, characters.size());

        // Get API key in main thread before async tasks (avoids SecurityContext threading issues)
        String userApiKey = null;
        try {
            userApiKey = settingsService.getApiKeyById(userId);
            log.info("[Moderator] Got API key for userId: {} in round {}", userId, roundNum);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key for userId {}: {}", userId, e.getMessage());
        }
        final String userApiKeyFinal = userApiKey;

        // Notify thinking for all characters
        for (Character character : characters) {
            log.info("[Moderator] [Round {}] Notifying thinking for character: {} ({})",
                roundNum, character.getName(), character.getId());
            onThinking.accept(character.getId().toString());
        }

        // Collect completions for this round
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(roundNum, thisRoundResponses);

        for (Character character : characters) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                log.info("[Moderator] [Round {}] [{}] Starting async task", roundNum, character.getName());
                try {
                    String characterPrompt = buildCharacterPrompt(character);
                    String fullPrompt = characterPrompt + "\n\n" + context;

                    log.info("[Moderator] [Round {}] [{}] Prompt built, length: {}, calling AI service",
                        roundNum, character.getName(), fullPrompt.length());

                    StringBuilder fullResponse = new StringBuilder();
                    CountDownLatch latch = new CountDownLatch(1);

                    aiService.generateResponseStream(
                        fullPrompt,
                        roundNum == 1 ? userMessage : "Continue the discussion",
                        userApiKeyFinal,
                        // onChunk - stream each chunk to frontend immediately
                        chunk -> {
                            fullResponse.append(chunk);
                            try {
                                onChunk.accept(new ResponseFragment(
                                    character.getId().toString(),
                                    character.getName(),
                                    fullResponse.toString(),
                                    false
                                ));
                            } catch (Exception e) {
                                log.warn("[Moderator] [Round {}] [{}] onChunk callback failed: {}",
                                    roundNum, character.getName(), e.getMessage());
                            }
                        },
                        // onComplete
                        completeResponse -> {
                            String responseText = fullResponse.toString();
                            log.info("[Moderator] [Round {}] [{}] onComplete - response length: {}",
                                roundNum, character.getName(), responseText.length());
                            thisRoundResponses.add(new ResponseFragment(
                                character.getId().toString(),
                                character.getName(),
                                responseText,
                                true
                            ));
                            latch.countDown();
                        },
                        // onError
                        error -> {
                            log.error("[Moderator] [Round {}] [{}] onError: {}",
                                roundNum, character.getName(), error.getMessage());
                            thisRoundResponses.add(new ResponseFragment(
                                character.getId().toString(),
                                character.getName(),
                                "Error: " + error.getMessage(),
                                true
                            ));
                            latch.countDown();
                        }
                    );

                    log.info("[Moderator] [Round {}] [{}] Waiting for latch (60s timeout)",
                        roundNum, character.getName());
                    boolean latchReleased = latch.await(60, TimeUnit.SECONDS);
                    if (!latchReleased) {
                        log.warn("[Moderator] [Round {}] [{}] Latch timed out after 60s",
                            roundNum, character.getName());
                        String responseText = fullResponse.toString();
                        if (responseText.isEmpty()) {
                            responseText = "Error: Response timed out (60s)";
                        }
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true
                        ));
                    } else {
                        log.info("[Moderator] [Round {}] [{}] Latch released successfully",
                            roundNum, character.getName());
                    }
                } catch (Exception e) {
                    log.error("[Moderator] [Round {}] [{}] Unexpected error in async task: {}",
                        roundNum, character.getName(), e.getMessage(), e);
                    thisRoundResponses.add(new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        "Error: " + e.getMessage(),
                        true
                    ));
                }
            }, executor);
            futures.add(future);
            // Track future for room-level cancellation
            roomFutures.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(future);
        }

        // Wait for all characters to complete this round
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allFutures.thenRun(() -> {
            log.info("[Moderator] [Round {}] All futures completed, sending {} responses",
                roundNum, thisRoundResponses.size());
            // Send all responses from this round
            for (ResponseFragment fragment : thisRoundResponses) {
                try {
                    onResponse.accept(fragment);
                } catch (Exception e) {
                    log.error("[Moderator] [Round {}] onResponse callback failed for character {}: {}",
                        roundNum, fragment.getCharacterId(), e.getMessage(), e);
                }
            }
            // Remove futures from tracking after completion
            roomFutures.remove(roomId, futures);

            // Check if we should continue to next round
            if (roundNum < maxRounds) {
                // Build context for next round with all previous responses
                String nextContext = buildRoundContext(context, thisRoundResponses, roundNum);

                // Delay before next round
                try {
                    log.info("[Moderator] [Round {}] Delaying {}ms before next round",
                        roundNum, ROUND_DELAY_MS);
                    Thread.sleep(ROUND_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Continue to next round
                runDiscussionRound(roomId, userId, userMessage, nextContext, characters, roundNum + 1, maxRounds,
                    roundResponses, onThinking, onChunk, onResponse);
            }
        }).exceptionally(ex -> {
            log.error("[Moderator] [Round {}] allOf failed: {}", roundNum, ex.getMessage());
            for (ResponseFragment fragment : thisRoundResponses) {
                try {
                    onResponse.accept(fragment);
                } catch (Exception e) {
                    log.error("[Moderator] [Round {}] onResponse callback failed in exception handler: {}", e.getMessage());
                }
            }
            roomFutures.remove(roomId, futures);
            return null;
        });

        // Safety timeout for this round
        final int currentRound = roundNum;
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(90000);
                log.warn("[Moderator] [Round {}] Safety timeout (90s) reached, forcing response send", currentRound);
                for (ResponseFragment fragment : thisRoundResponses) {
                    try {
                        onResponse.accept(fragment);
                    } catch (Exception e) {
                        log.error("[Moderator] [Round {}] onResponse callback failed in timeout: {}", e.getMessage());
                    }
                }
                roomFutures.remove(roomId, futures);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Build initial context with user message and system prompt.
     */
    private String buildInitialContext(String userMessage) {
        return "The user has asked: \"" + userMessage + "\"\n\n" +
               "This is a GROUP DISCUSSION. Everyone should respond to the user's question first, " +
               "then in subsequent rounds, comment on and debate each other's viewpoints.\n\n" +
               "Keep responses conversational and relatively brief (2-4 sentences).";
    }

    /**
     * Build context for subsequent rounds with previous responses.
     */
    private String buildRoundContext(String previousContext, List<ResponseFragment> responses, int roundNum) {
        StringBuilder context = new StringBuilder();
        context.append(previousContext);
        context.append("\n\n=== Round ").append(roundNum).append(" Responses ===\n");

        for (ResponseFragment r : responses) {
            context.append("[").append(r.getCharacterName()).append("]: ").append(r.getContent()).append("\n");
        }

        context.append("\n=== Round ").append(roundNum + 1).append(" ===\n");
        context.append("Now COMMENT on or RESPOND to what others said. Agree, disagree, or add new perspectives. " +
                      "Keep it conversational (2-4 sentences). Address specific points others made.");

        return context.toString();
    }

    /**
     * Build the system prompt for a character.
     */
    private String buildCharacterPrompt(Character character) {
        log.info("[Moderator] [{}] Building character prompt", character.getName());
        StringBuilder prompt = new StringBuilder();

        // First fetch web context for role info
        log.info("[Moderator] [{}] Calling firecrawlService.scrape()", character.getName());
        long startTime = System.currentTimeMillis();
        String webContext = firecrawlService.scrape(character.getName());
        long scrapeTime = System.currentTimeMillis() - startTime;
        log.info("[Moderator] [{}] firecrawlService.scrape() returned in {}ms, content length: {}",
            character.getName(), scrapeTime, webContext != null ? webContext.length() : 0);

        if (webContext != null && !webContext.isBlank()) {
            prompt.append("Background information: ").append(webContext).append("\n\n");
        }

        prompt.append("You are ").append(character.getName());
        if (character.getEra() != null) {
            prompt.append(", from the ").append(character.getEra());
        }
        prompt.append(".\n\n");

        if (character.getDescription() != null) {
            prompt.append("Description: ").append(character.getDescription()).append("\n\n");
        }

        if (character.getSpeakingStyle() != null) {
            prompt.append("Speaking Style: ").append(character.getSpeakingStyle()).append("\n\n");
        }

        if (character.getPersona() != null) {
            prompt.append("Personality: ").append(character.getPersona()).append("\n\n");
        }

        if (character.getExpertise() != null && !character.getExpertise().isEmpty()) {
            prompt.append("Areas of expertise: ").append(String.join(", ", character.getExpertise())).append("\n\n");
        }

        prompt.append("IMPORTANT: This is an AI simulation for educational/entertainment purposes only.\n\n");

        prompt.append("You are in a GROUP DISCUSSION. Engage with the topic and with what others say. " +
                      "Be concise, conversational, and true to your character's perspective.");

        log.info("[Moderator] [{}] Character prompt built, total length: {}", character.getName(), prompt.length());
        return prompt.toString();
    }

    @Override
    public void destroy() throws Exception {
        // Cancel all ongoing room discussions
        roomFutures.keySet().forEach(this::cancelRoom);
        roomFutures.clear();

        executor.shutdown();
        // Wait up to 60 seconds for tasks to complete
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[DEBUG] ModeratorAgent: Executor did not terminate");
            }
        }
        log.info("[DEBUG] ModeratorAgent: ExecutorService shut down");
    }

    /**
     * Cancel all ongoing AI processing for a room.
     * This method cancels all tracked CompletableFutures associated with the room.
     *
     * @param roomId The room ID to cancel
     */
    public void cancelRoom(String roomId) {
        List<CompletableFuture<?>> futures = roomFutures.remove(roomId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * Response fragment from a character.
     */
    public static class ResponseFragment {
        private final String characterId;
        private final String characterName;
        private final String content;
        private final boolean isComplete;

        public ResponseFragment(String characterId, String characterName, String content, boolean isComplete) {
            this.characterId = characterId;
            this.characterName = characterName;
            this.content = content;
            this.isComplete = isComplete;
        }

        public String getCharacterId() { return characterId; }
        public String getCharacterName() { return characterName; }
        public String getContent() { return content; }
        public boolean isComplete() { return isComplete; }
    }
}

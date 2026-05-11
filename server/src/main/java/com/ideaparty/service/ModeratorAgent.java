package com.ideaparty.service;

import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.service.FirecrawlService;
import lombok.extern.slf4j.Slf4j;
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

    // Maximum discussion rounds
    private static final int MAX_ROUNDS = 3;

    // Delay between rounds (milliseconds) to let responses propagate
    private static final long ROUND_DELAY_MS = 1500;

    // Executor for async operations
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Room-level futures tracking for cancellation
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository, FirecrawlService firecrawlService) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
        this.firecrawlService = firecrawlService;
    }

    /**
     * Process a user message and orchestrate AI character discussion.
     *
     * @param roomId The room ID
     * @param userMessage The user's message
     * @param characters List of characters in the room
     * @param isContinuous If true, run multiple rounds (discussion mode); if false, single round (dialogue mode)
     * @param maxRounds Maximum rounds for continuous mode
     * @param onThinking Callback for "thinking" state
     * @param onResponse Callback for each character's response
     */
    public void processMessage(String roomId, String userMessage, List<Character> characters,
                               boolean isContinuous, int maxRounds,
                               Consumer<String> onThinking, Consumer<ResponseFragment> onResponse) {
        if (characters == null || characters.isEmpty()) {
            return;
        }

        // Build initial context with user message
        String initialContext = buildInitialContext(userMessage);

        // Track responses for each round
        Map<Integer, List<ResponseFragment>> roundResponses = new ConcurrentHashMap<>();

        if (isContinuous) {
            // Discussion mode: multi-round
            runDiscussionRound(roomId, userMessage, initialContext, characters, 1,
                maxRounds, roundResponses, onThinking, onResponse);
        } else {
            // Dialogue mode: single round only
            runSingleRound(roomId, userMessage, initialContext, characters,
                roundResponses, onThinking, onResponse);
        }
    }

    /**
     * Run a single dialogue round (dialogue mode).
     */
    private void runSingleRound(String roomId, String userMessage, String context,
                                List<Character> characters,
                                Map<Integer, List<ResponseFragment>> roundResponses,
                                Consumer<String> onThinking, Consumer<ResponseFragment> onResponse) {

        // Notify thinking for all characters
        for (Character character : characters) {
            onThinking.accept(character.getName());
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(1, thisRoundResponses);

        for (Character character : characters) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                String characterPrompt = buildCharacterPrompt(character);
                String fullPrompt = characterPrompt + "\n\n" + context;

                StringBuilder fullResponse = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);

                aiService.generateResponseStream(
                    fullPrompt,
                    userMessage,
                    chunk -> fullResponse.append(chunk),
                    completeResponse -> {
                        String responseText = fullResponse.toString();
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            responseText,
                            true
                        ));
                        latch.countDown();
                    },
                    error -> {
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            "Error: " + error.getMessage(),
                            true
                        ));
                        latch.countDown();
                    }
                );

                try {
                    latch.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor);
            futures.add(future);
            // Track future for room-level cancellation
            roomFutures.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(future);
        }

        // Wait for all characters and send responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
            for (ResponseFragment fragment : thisRoundResponses) {
                onResponse.accept(fragment);
            }
            // Remove futures from tracking after completion
            roomFutures.remove(roomId, futures);
        });
    }

    /**
     * Run a single discussion round (for continuous discussion mode).
     */
    private void runDiscussionRound(String roomId, String userMessage, String context,
                                   List<Character> characters, int roundNum, int maxRounds,
                                   Map<Integer, List<ResponseFragment>> roundResponses,
                                   Consumer<String> onThinking, Consumer<ResponseFragment> onResponse) {

        // Notify thinking for all characters
        for (Character character : characters) {
            onThinking.accept(character.getName());
        }

        // Collect completions for this round
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<ResponseFragment> thisRoundResponses = Collections.synchronizedList(new ArrayList<>());
        roundResponses.put(roundNum, thisRoundResponses);

        for (Character character : characters) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                String characterPrompt = buildCharacterPrompt(character);
                String fullPrompt = characterPrompt + "\n\n" + context;

                StringBuilder fullResponse = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);

                aiService.generateResponseStream(
                    fullPrompt,
                    roundNum == 1 ? userMessage : "Continue the discussion",
                    // onChunk
                    chunk -> fullResponse.append(chunk),
                    // onComplete
                    completeResponse -> {
                        String responseText = fullResponse.toString();
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
                        thisRoundResponses.add(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            "Error: " + error.getMessage(),
                            true
                        ));
                        latch.countDown();
                    }
                );

                try {
                    latch.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor);
            futures.add(future);
            // Track future for room-level cancellation
            roomFutures.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(future);
        }

        // Wait for all characters to complete this round
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
            // Send all responses from this round
            for (ResponseFragment fragment : thisRoundResponses) {
                onResponse.accept(fragment);
            }
            // Remove futures from tracking after completion
            roomFutures.remove(roomId, futures);

            // Check if we should continue to next round
            if (roundNum < maxRounds) {
                // Build context for next round with all previous responses
                String nextContext = buildRoundContext(context, thisRoundResponses, roundNum);

                // Delay before next round
                try {
                    Thread.sleep(ROUND_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Continue to next round
                runDiscussionRound(roomId, userMessage, nextContext, characters, roundNum + 1, maxRounds,
                    roundResponses, onThinking, onResponse);
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
        StringBuilder prompt = new StringBuilder();

        // First fetch web context for role info
        String webContext = firecrawlService.scrape(character.getName());
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

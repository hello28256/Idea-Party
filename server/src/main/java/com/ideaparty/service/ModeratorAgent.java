package com.ideaparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaparty.dto.DiscussionPhase;
import com.ideaparty.dto.DiscussionStateEvent;
import com.ideaparty.dto.ModeratorMessage;
import com.ideaparty.entity.Character;
import com.ideaparty.entity.Message;
import com.ideaparty.repository.MessageRepository;
import com.ideaparty.service.FirecrawlService;
import com.ideaparty.socket.ChatSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Lazy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final ChatSocketHandler chatSocketHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Maximum discussion rounds
    private static final int MAX_ROUNDS = 3;

    // Delay between rounds (milliseconds) to let responses propagate
    private static final long ROUND_DELAY_MS = 1500;

    // Executor for async operations - propagates SecurityContext to child threads
    private final ExecutorService executor;

    // Room-level futures tracking for cancellation
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> roomFutures = new ConcurrentHashMap<>();

    // Track last sent length per character for delta computation
    private final ConcurrentHashMap<String, Integer> lastSentLengths = new ConcurrentHashMap<>();

    // Room-level paused state for discussion mode
    private final ConcurrentHashMap<String, AtomicBoolean> roomPaused = new ConcurrentHashMap<>();

    // Room-level current discussion state
    private final ConcurrentHashMap<String, DiscussionState> roomDiscussionState = new ConcurrentHashMap<>();

    // Discussion state class
    private static class DiscussionState {
        volatile boolean isRunning = false;
        volatile boolean paused = false;
        volatile int currentRound = 0;
        volatile int currentCharacterIndex = 0;
        volatile int maxRounds = 3;
        List<Character> characters = new CopyOnWriteArrayList<>();
        List<ResponseFragment> responses = new CopyOnWriteArrayList<>();
        String userMessage = "";
        String context = "";
        AtomicBoolean userTriggered = new AtomicBoolean(false);

        // New fields for state machine
        volatile DiscussionPhase phase = DiscussionPhase.IDLE;
        volatile boolean userInterjected = false;
        volatile int aiMessageCount = 0;
        volatile int maxAiMessagesPerRound = 3;
        List<Character> selectedCharacters = new CopyOnWriteArrayList<>();
        List<Character> pendingQueue = new CopyOnWriteArrayList<>();
        volatile String moderatorMessage = "";
        volatile String currentUserMessage = "";
        AtomicBoolean currentStreamCancelled = new AtomicBoolean(false);
        String userId = "";  // User ID for API key lookup
    }

    // ========== State Machine Methods ==========

    private void transitionTo(DiscussionState state, DiscussionPhase newPhase) {
        state.phase = newPhase;
        String roomId = findRoomIdByState(state);
        if (roomId != null) {
            broadcastStateChange(roomId, newPhase, state.selectedCharacters, state.moderatorMessage);
        }
    }

    private String findRoomIdByState(DiscussionState state) {
        for (Map.Entry<String, DiscussionState> entry : roomDiscussionState.entrySet()) {
            if (entry.getValue() == state) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void broadcastStateChange(String roomId, DiscussionPhase phase,
                                      List<Character> selectedCharacters, String message) {
        try {
            DiscussionStateEvent event = new DiscussionStateEvent(
                phase,
                selectedCharacters.stream().map(Character::getId).map(UUID::toString).collect(Collectors.toList()),
                message
            );
            String eventJson = objectMapper.writeValueAsString(event);
            String socketMessage = "42[\"discussion-state\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastStateChange failed: {}", e.getMessage());
        }
    }

    public void handleUserInterjection(String roomId, String userId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        // 1. Set interrupt flag immediately
        state.userInterjected = true;

        // 2. Clear pending queue
        state.pendingQueue.clear();

        // 3. Reset AI message count
        state.aiMessageCount = 0;

        // 4. Transition to MODERATING
        transitionTo(state, DiscussionPhase.MODERATING);
        state.currentUserMessage = userMessage;
        state.userId = userId;

        // 5. Immediately start new Moderator analysis
        processModeratorAnalysis(roomId, userMessage);
    }

    private void processModeratorAnalysis(String roomId, String userMessage) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        List<Character> availableCharacters = state.characters;

        if (availableCharacters == null || availableCharacters.isEmpty()) {
            log.warn("[Moderator] No characters available for room: {}", roomId);
            return;
        }

        // Call LLM to select characters
        String selection = callModeratorForSelection(userMessage, availableCharacters);
        log.info("[Moderator] LLM selection result: {}", selection);

        // Parse [SELECT:角色1,角色2] format
        Pattern pattern = Pattern.compile("\\[SELECT:([^\\]]+)\\]");
        Matcher matcher = pattern.matcher(selection);

        List<Character> selected = new ArrayList<>();
        if (matcher.find()) {
            String[] selectedNames = matcher.group(1).split(",");
            for (String name : selectedNames) {
                final String trimmedName = name.trim();
                Character found = availableCharacters.stream()
                    .filter(c -> c.getName().trim().equals(trimmedName))
                    .findFirst()
                    .orElse(null);
                if (found != null && selected.size() < 2) {
                    selected.add(found);
                }
            }
        }

        // Fallback if no characters matched
        if (selected.isEmpty()) {
            selected = availableCharacters.subList(0, Math.min(2, availableCharacters.size()));
        }

        state.selectedCharacters = new ArrayList<>(selected);
        state.pendingQueue = new ArrayList<>(selected);

        // Broadcast selection
        String selectMsg = "正在邀请: " + selected.stream().map(Character::getName).collect(Collectors.joining(", "));
        state.moderatorMessage = selectMsg;
        broadcastModeratorMessage(roomId, selectMsg, "SELECT");

        // Transition to SPEAKING
        transitionTo(state, DiscussionPhase.SPEAKING);

        // Start speaking process
        processNextInQueue(roomId);
    }

    private void processNextInQueue(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null || !state.isRunning) return;

        // Check if should wait for user
        if (shouldWaitForUser(state)) {
            waitForUserInput(roomId);
            return;
        }

        // Get next character from queue - synchronized for thread safety
        synchronized (state.pendingQueue) {
            if (!state.pendingQueue.isEmpty()) {
                Character character = state.pendingQueue.remove(0);
                generateCharacterResponse(roomId, character, state);
            }
        }
    }

    private boolean shouldWaitForUser(DiscussionState state) {
        if (state.aiMessageCount >= state.maxAiMessagesPerRound) {
            return true;
        }
        if (state.pendingQueue.isEmpty() && state.selectedCharacters.isEmpty()) {
            return true;
        }
        return false;
    }

    private void waitForUserInput(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state == null) return;

        transitionTo(state, DiscussionPhase.WAITING_FOR_USER);

        String inviteMessage = generateModeratorInvite(state);
        state.moderatorMessage = inviteMessage;
        broadcastModeratorMessage(roomId, inviteMessage, "INVITE");
    }

    private String generateModeratorInvite(DiscussionState state) {
        if (state.selectedCharacters.isEmpty()) {
            return "你想讨论什么话题？";
        }

        String characters = state.selectedCharacters.stream()
            .map(Character::getName)
            .collect(Collectors.joining(" 和 "));

        String[] invites = {
            "你更认同谁的观点，" + characters + "？",
            "你怎么看这个问题？",
            "你有什么不同看法？",
            characters + "观点各异，你支持谁？",
            "这场讨论你怎么看？"
        };

        Random random = new Random();
        return invites[random.nextInt(invites.length)];
    }

    private void broadcastModeratorMessage(String roomId, String content, String type) {
        try {
            ModeratorMessage message = new ModeratorMessage(content, type);
            String eventJson = objectMapper.writeValueAsString(message);
            String socketMessage = "42[\"moderator-message\"," + eventJson + "]";
            chatSocketHandler.broadcastToRoom(roomId, socketMessage);
        } catch (Exception e) {
            log.error("[Moderator] broadcastModeratorMessage failed: {}", e.getMessage());
        }
    }

    public ModeratorAgent(AIService aiService, MessageRepository messageRepository, FirecrawlService firecrawlService, SettingsService settingsService, @Lazy ChatSocketHandler chatSocketHandler) {
        this.aiService = aiService;
        this.messageRepository = messageRepository;
        this.firecrawlService = firecrawlService;
        this.settingsService = settingsService;
        this.chatSocketHandler = chatSocketHandler;
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
                // Discussion mode: sequential turn-based discussion
                DiscussionState state = new DiscussionState();
                state.characters = new ArrayList<>(characters);
                state.userMessage = userMessage;
                state.context = initialContext;
                state.currentRound = 1;
                state.maxRounds = maxRounds;
                state.isRunning = true;
                state.paused = false;
                state.userTriggered.set(false);
                roomDiscussionState.put(roomId, state);
                roomPaused.put(roomId, new AtomicBoolean(false));

                runSequentialDiscussion(roomId, userId, state, onThinking, onChunk, onResponse);
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
     * Run sequential turn-based discussion mode.
     * Characters take turns speaking one by one with random delays between turns.
     * Supports pause/resume and user message triggering.
     */
    private void runSequentialDiscussion(String roomId, String userId, DiscussionState state,
                                         Consumer<String> onThinking, Consumer<ResponseFragment> onChunk,
                                         Consumer<ResponseFragment> onResponse) {
        log.info("[Moderator] runSequentialDiscussion START - roomId: {}, chars: {}, rounds: {}",
            roomId, state.characters.size(), state.maxRounds);

        String userApiKey = getApiKey(userId);

        // Start the discussion loop in a background thread
        CompletableFuture.runAsync(() -> {
            try {
                while (state.isRunning && state.currentRound <= state.maxRounds) {
                    Character character = state.characters.get(state.currentCharacterIndex);

                    // Wait for pause - check every 500ms
                    while (state.paused && state.isRunning) {
                        log.info("[Moderator] Discussion paused, waiting...");
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Random delay 10-40 seconds before this character speaks
                    int delaySeconds = 10 + (int) (Math.random() * 31);
                    log.info("[Moderator] [Round {}] Waiting {}s before {} speaks",
                        state.currentRound, delaySeconds, character.getName());

                    for (int i = 0; i < delaySeconds * 2; i++) {
                        if (!state.isRunning) break;
                        if (state.userTriggered.get()) {
                            log.info("[Moderator] User triggered, skipping delay");
                            state.userTriggered.set(false);
                            break;
                        }
                        Thread.sleep(500);
                    }

                    if (!state.isRunning) break;

                    // Character speaks (blocking stream)
                    log.info("[Moderator] [Round {}] {} is now speaking", state.currentRound, character.getName());
                    generateCharacterResponse(roomId, character, state, userApiKey, onChunk, onResponse);

                    // Move to next character
                    state.currentCharacterIndex++;

                    // If all characters have spoken this round
                    if (state.currentCharacterIndex >= state.characters.size()) {
                        state.currentCharacterIndex = 0;
                        state.currentRound++;
                        state.context = buildRoundContext(state.context, state.responses, state.currentRound - 1);
                        log.info("[Moderator] Completed round {}, moving to round {}",
                            state.currentRound - 1, state.currentRound);
                    }

                    if (!state.isRunning) break;
                }

                log.info("[Moderator] Discussion ended - rounds completed: {}", state.currentRound - 1);
                state.isRunning = false;
                roomDiscussionState.remove(roomId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[Moderator] Discussion interrupted");
                state.isRunning = false;
            } catch (Exception e) {
                log.error("[Moderator] Discussion error: {}", e.getMessage(), e);
                state.isRunning = false;
            }
        }, executor);
    }

    /**
     * Generate response for a single character with streaming.
     */
    private void generateCharacterResponse(String roomId, Character character, DiscussionState state,
                                           String userApiKey,
                                           Consumer<ResponseFragment> onChunk,
                                           Consumer<ResponseFragment> onResponse) {
        try {
            String characterPrompt = buildCharacterPrompt(character);
            String fullPrompt = characterPrompt + "\n\n" + state.context + "\n\nUser's question: " + state.userMessage;

            log.info("[Moderator] [{}] Prompt length: {}", character.getName(), fullPrompt.length());

            StringBuilder fullResponse = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            aiService.generateResponseStream(
                fullPrompt,
                state.userMessage,
                userApiKey,
                chunk -> {
                    fullResponse.append(chunk);
                    try {
                        onChunk.accept(new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            chunk,
                            false
                        ));
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] onChunk failed: {}", character.getName(), e.getMessage());
                    }
                },
                completeResponse -> {
                    String responseText = fullResponse.toString();
                    ResponseFragment fragment = new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true
                    );
                    state.responses.add(fragment);
                    log.info("[Moderator] [{}] Complete - length: {}", character.getName(), responseText.length());
                    latch.countDown();
                },
                error -> {
                    log.error("[Moderator] [{}] Error: {}", character.getName(), error.getMessage());
                    ResponseFragment fragment = new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        "Error: " + error.getMessage(),
                        true
                    );
                    state.responses.add(fragment);
                    latch.countDown();
                }
            );

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[Moderator] [{}] Timeout", character.getName());
                // Timeout - send timeout response
                String timeoutResponse = fullResponse.length() > 0 ? fullResponse.toString() : "Error: Response timed out (90s)";
                ResponseFragment timeoutFragment = new ResponseFragment(
                    character.getId().toString(),
                    character.getName(),
                    timeoutResponse,
                    true
                );
                state.responses.add(timeoutFragment);
                onResponse.accept(timeoutFragment);
                return;
            }

            // Send final response callback
            if (!state.responses.isEmpty()) {
                ResponseFragment last = state.responses.get(state.responses.size() - 1);
                if (last.getCharacterId().equals(character.getId().toString())) {
                    onResponse.accept(last);
                }
            }

            // State machine: after completion, process next in queue
            state.aiMessageCount++;
            if (shouldWaitForUser(state)) {
                waitForUserInput(roomId);
            } else {
                processNextInQueue(roomId);
            }

        } catch (Exception e) {
            log.error("[Moderator] [{}] generateCharacterResponse failed: {}", character.getName(), e.getMessage());
        }
    }

    /**
     * Generate response for a character (state machine version with internal callbacks).
     * Used by processNextInQueue for queue-based discussion.
     */
    private void generateCharacterResponse(String roomId, Character character, DiscussionState state) {
        String userId = state.userId;
        String userApiKey = getApiKey(userId);
        if (userApiKey == null) {
            log.error("[Moderator] [{}] No API key available for user: {}", character.getName(), userId);
            return;
        }

        try {
            String characterPrompt = buildCharacterPrompt(character);
            String fullPrompt = characterPrompt + "\n\n" + state.context + "\n\nUser's question: " + state.currentUserMessage;

            log.info("[Moderator] [StateMachine] [{}] Prompt length: {}", character.getName(), fullPrompt.length());

            StringBuilder fullResponse = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);

            aiService.generateResponseStream(
                fullPrompt,
                state.currentUserMessage,
                userApiKey,
                chunk -> {
                    fullResponse.append(chunk);
                    try {
                        // Broadcast chunk to room
                        ResponseFragment fragment = new ResponseFragment(
                            character.getId().toString(),
                            character.getName(),
                            chunk,
                            false
                        );
                        String eventJson = objectMapper.writeValueAsString(Map.of(
                            "characterId", fragment.getCharacterId(),
                            "characterName", fragment.getCharacterName(),
                            "content", fragment.getContent(),
                            "isComplete", fragment.isComplete()
                        ));
                        String socketMessage = "42[\"ai-chunk\"," + eventJson + "]";
                        chatSocketHandler.broadcastToRoom(roomId, socketMessage);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] chunk broadcast failed: {}", character.getName(), e.getMessage());
                    }
                },
                completeResponse -> {
                    String responseText = fullResponse.toString();
                    ResponseFragment fragment = new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        responseText,
                        true
                    );
                    state.responses.add(fragment);
                    log.info("[Moderator] [StateMachine] [{}] Complete - length: {}", character.getName(), responseText.length());

                    try {
                        // Broadcast complete response to room
                        String eventJson = objectMapper.writeValueAsString(Map.of(
                            "characterId", fragment.getCharacterId(),
                            "characterName", fragment.getCharacterName(),
                            "content", fragment.getContent(),
                            "isComplete", fragment.isComplete()
                        ));
                        String socketMessage = "42[\"ai-response\"," + eventJson + "]";
                        chatSocketHandler.broadcastToRoom(roomId, socketMessage);
                    } catch (Exception e) {
                        log.warn("[Moderator] [{}] response broadcast failed: {}", character.getName(), e.getMessage());
                    }

                    latch.countDown();
                },
                error -> {
                    log.error("[Moderator] [StateMachine] [{}] Error: {}", character.getName(), error.getMessage());
                    ResponseFragment fragment = new ResponseFragment(
                        character.getId().toString(),
                        character.getName(),
                        "Error: " + error.getMessage(),
                        true
                    );
                    state.responses.add(fragment);
                    latch.countDown();
                }
            );

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[Moderator] [StateMachine] [{}] Timeout", character.getName());
            }

            // State machine: after completion, process next in queue
            // Synchronize around pendingQueue to prevent race conditions
            state.aiMessageCount++;
            synchronized (state.pendingQueue) {
                if (shouldWaitForUser(state)) {
                    waitForUserInput(roomId);
                } else {
                    processNextInQueue(roomId);
                }
            }

        } catch (Exception e) {
            log.error("[Moderator] [StateMachine] [{}] generateCharacterResponse failed: {}", character.getName(), e.getMessage());
        }
    }

    private String getApiKey(String userId) {
        try {
            return settingsService.getApiKeyById(userId);
        } catch (Exception e) {
            log.error("[Moderator] Failed to get API key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pause discussion for a room.
     */
    public void pauseDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        AtomicBoolean paused = roomPaused.get(roomId);
        if (state != null && paused != null) {
            state.paused = true;
            paused.set(true);
            log.info("[Moderator] Discussion paused for room: {}", roomId);
        }
    }

    /**
     * Resume discussion for a room.
     */
    public void resumeDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        AtomicBoolean paused = roomPaused.get(roomId);
        if (state != null && paused != null) {
            state.paused = false;
            paused.set(false);
            state.userTriggered.set(true); // Trigger immediate continuation
            log.info("[Moderator] Discussion resumed for room: {}", roomId);
        }
    }

    /**
     * Trigger discussion to continue when user sends a message.
     */
    public void triggerUserMessage(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        if (state != null && state.isRunning) {
            state.userTriggered.set(true);
            // Also resume if paused
            if (state.paused) {
                state.paused = false;
                AtomicBoolean paused = roomPaused.get(roomId);
                if (paused != null) paused.set(false);
            }
            log.info("[Moderator] User message triggered discussion for room: {}", roomId);
        }
    }

    /**
     * Stop discussion for a room.
     */
    public void stopDiscussion(String roomId) {
        DiscussionState state = roomDiscussionState.remove(roomId);
        if (state != null) {
            state.isRunning = false;
            log.info("[Moderator] Discussion stopped for room: {}", roomId);
        }
        roomPaused.remove(roomId);
    }

    /**
     * Check if a discussion is currently running for a room.
     */
    public boolean isDiscussionRunning(String roomId) {
        DiscussionState state = roomDiscussionState.get(roomId);
        return state != null && state.isRunning;
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
                        // Stream chunk directly to frontend (not delta)
                        try {
                            String charId = character.getId().toString();
                            log.info("[Moderator] [{}] onChunk - chunkLen={}, totalLen={}",
                                character.getName(), chunk.length(), fullResponse.length());
                            if (chunk != null && !chunk.isEmpty()) {
                                onChunk.accept(new ResponseFragment(
                                    charId,
                                    character.getName(),
                                    chunk,
                                    false
                                ));
                            }
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
                        // onChunk - stream chunk directly to frontend
                        chunk -> {
                            fullResponse.append(chunk);
                            try {
                                String charId = character.getId().toString();
                                log.info("[Moderator] [Round {}] [{}] onChunk - chunkLen={}, totalLen={}",
                                    roundNum, character.getName(), chunk.length(), fullResponse.length());
                                if (chunk != null && !chunk.isEmpty()) {
                                    onChunk.accept(new ResponseFragment(
                                        charId,
                                        character.getName(),
                                        chunk,
                                        false
                                    ));
                                }
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
                      "Be concise, conversational, and true to your character's perspective.\n\n");

        prompt.append("IMPORTANT RESTRICTION: Your response MUST be exactly 2-4 sentences. No more than 4 sentences total. Be concise and direct.\n\n");

        prompt.append("CRITICAL: When responding, ONLY speak as yourself. Do NOT repeat, quote, or include " +
                      "other people's messages in your response. Your reply should be your own words only, " +
                      "expressed from your character's perspective.");

        log.info("[Moderator] [{}] Character prompt built, total length: {}", character.getName(), prompt.length());
        return prompt.toString();
    }

    /**
     * Build the system prompt for Moderator to select characters.
     */
    private String buildModeratorPrompt(String userMessage, List<Character> characters) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# 角色设定\n");
        prompt.append("你是\"圆桌讨论主持人\"，不是参与者。你不回答问题。\n\n");
        prompt.append("你的职责：\n");
        prompt.append("1. 分析用户问题，选择最适合的 1-2 个角色参与讨论\n");
        prompt.append("2. 控制讨论节奏，防止 AI 无限对话\n");
        prompt.append("3. 在适当时邀请观众（用户）参与\n");
        prompt.append("4. 总结不同角色的核心观点\n\n");
        prompt.append("# 核心规则\n");
        prompt.append("- 每轮最多让 2 个角色发言\n");
        prompt.append("- 连续 AI 消息不超过 3 条\n");
        prompt.append("- 每轮结束后必须邀请用户参与\n");
        prompt.append("- 优先制造观点冲突或对立\n");
        prompt.append("- 用户消息优先级最高：收到用户消息后立即重新组织讨论\n");
        prompt.append("- 保持角色发言简洁（2-4 句话）\n\n");
        prompt.append("# 可用角色\n");
        for (Character c : characters) {
            prompt.append("- ").append(c.getName())
                .append(" (专家领域: ").append(c.getExpertise() != null ? String.join(", ", c.getExpertise()) : "未知").append(")")
                .append(" - ").append(c.getPersonality() != null ? c.getPersonality() : "").append("\n");
        }
        prompt.append("\n# 用户问题\n");
        prompt.append(userMessage).append("\n\n");
        prompt.append("# 输出要求\n");
        prompt.append("你必须选择角色时，输出：\n");
        prompt.append("[SELECT:角色名1,角色名2]\n");
        prompt.append("理由：...\n\n");
        prompt.append("你必须邀请用户时，输出：\n");
        prompt.append("[INVITE:你更支持谁的观点？/你怎么看这个问题？/你有什么不同看法？]\n\n");
        prompt.append("你必须总结时，输出：\n");
        prompt.append("[SUMMARY:角色A认为...；角色B认为...]\n");

        return prompt.toString();
    }

    /**
     * Call LLM to select characters for the discussion.
     */
    private String callModeratorForSelection(String userMessage, List<Character> characters) {
        String prompt = buildModeratorPrompt(userMessage, characters);
        log.info("[Moderator] Calling LLM for character selection, prompt length: {}", prompt.length());

        StringBuffer fullResponse = new StringBuffer();  // thread-safe
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // Get API key - use first character's room or system default
            String userApiKey = settingsService.getDefaultApiKey();

            aiService.generateResponseStream(
                prompt,
                "选择最合适的角色参与讨论",
                userApiKey,
                chunk -> {
                    synchronized(fullResponse) {
                        fullResponse.append(chunk);
                    }
                },
                completeResponse -> latch.countDown(),
                error -> {
                    log.error("[Moderator] LLM selection error: {}", error.getMessage());
                    latch.countDown();
                }
            );

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            if (completed) {
                synchronized(fullResponse) {
                    return fullResponse.toString();
                }
            } else {
                log.warn("[Moderator] LLM selection timed out");
                return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
            }
        } catch (Exception e) {
            log.error("[Moderator] callModeratorForSelection failed: {}", e.getMessage());
            return "[SELECT:" + characters.stream().limit(2).map(Character::getName).collect(Collectors.joining(",")) + "]";
        }
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
        // Stop discussion state if running
        stopDiscussion(roomId);

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
